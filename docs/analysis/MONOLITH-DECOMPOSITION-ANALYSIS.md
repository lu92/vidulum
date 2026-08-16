# Analiza monolitu Vidulum - dekompozycja na serwisy

**Data:** 2026-08-15
**Cel:** Zrozumienie zależności między modułami i ocena możliwości wydzielenia ich do osobnych repozytoriów.

---

## 1. Mapa modułów

### Legenda izolacji

| Symbol | Znaczenie |
|--------|-----------|
| **IZOLOWANY** | Moduł komunikuje się z innymi wyłącznie przez Kafka/REST, nie importuje ich klas domenowych |
| **LUŹNO POWIĄZANY** | Importuje tylko ID/value objects z innych modułów, łatwy do wydzielenia |
| **ŚCIŚLE POWIĄZANY** | Importuje klasy domenowe, repozytoria lub serwisy z innych modułów, wymaga refactoringu przed wydzieleniem |

---

### 1.1 `common` — Shared Kernel

| | |
|---|---|
| **Cel** | Value objects (`Money`, `UserId`, `CashFlowId`), typy zdarzeń Kafka, generatory ID, obsługa błędów |
| **MongoDB** | `sequences` (liczniki atomowe dla BusinessIdGenerator) |
| **REST** | brak |
| **Kafka** | Definiuje typy zdarzeń: `CashFlowUnifiedEvent`, `BankDataIngestionUnifiedEvent`, `UserFinancialProfileUnifiedEvent`, `UserCreatedEvent`, `OrderFilledEvent`, `TradeCapturedEvent` |
| **Izolacja** | **WSPÓŁDZIELONY** — importowany przez KAŻDY moduł. To jest "shared kernel" w sensie DDD |

### 1.2 `shared` — CQRS + DDD Infrastructure

| | |
|---|---|
| **Cel** | `CommandGateway`, `QueryGateway`, `Aggregate`, `DomainRepository`, `EntitySnapshot`, emittery Kafka |
| **MongoDB** | brak |
| **REST** | brak |
| **Kafka producenci** | `UserCreatedEventEmitter` → `user_created`, `TradeCapturedEventEmitter` → `trade_captured`, `OrderFilledEventEmitter` → `order_filled` |
| **Izolacja** | **WSPÓŁDZIELONY** — framework wewnętrzny, importowany przez każdy moduł |

### 1.3 `security` — Autentykacja JWT

| | |
|---|---|
| **Cel** | Rejestracja, logowanie, JWT (access + refresh), role (ADMIN, MANAGER), `ErrorHttpHandler` |
| **MongoDB** | `token` |
| **REST** | `POST /api/v1/auth/register`, `/authenticate`, `/refresh-token`, `/logout`, `/logout-all` |
| **Kafka** | brak |
| **Importuje z** | `user` (DomainUserRepository, User), `cashflow`, `bank_data_ingestion`, `bank_data_adapter`, `user_financial_profile` (wyjątki w ErrorHttpHandler) |
| **Izolacja** | **ŚCIŚLE POWIĄZANY** — `ErrorHttpHandler` jest centralnym handlerem wyjątków z WSZYSTKICH modułów. `AuthenticationService` bezpośrednio woła `RegisterUserCommand`. |

### 1.4 `user` — Zarządzanie użytkownikami

| | |
|---|---|
| **Cel** | Rejestracja użytkownika, aktywacja/deaktywacja, powiązanie z portfolio |
| **MongoDB** | `user` |
| **REST** | `GET /user/userId={id}`, `GET /user`, `PUT /user/userId={id}`, `POST /user/portfolio/register` |
| **Kafka producent** | → `user_created` (przez `UserCreatedEventEmitter`) |
| **Importuje z** | `portfolio` (PortfolioId, PortfolioRestClient — in-process), `security` (Role), `user_financial_profile` (UserFinancialProfileService — bezpośrednie wywołanie przy rejestracji!) |
| **Izolacja** | **ŚCIŚLE POWIĄZANY** — bezpośrednio tworzy portfolio (in-process call) i profil finansowy przy rejestracji |

### 1.5 `cashflow` — Zarządzanie przepływami pieniężnymi (CORE)

| | |
|---|---|
| **Cel** | Główny agregat. Konta bankowe, transakcje (expected/paid/confirmed/rejected), kategorie z budżetowaniem, cykl miesięczny (SETUP→OPEN→ROLLED_OVER→ATTESTED), import historyczny |
| **MongoDB** | `cash-flow-document` |
| **REST** | 20+ endpointów pod `/cash-flow` — tworzenie, import historyczny, atestacja, kategorie, budżetowanie, rollover, CRUD transakcji |
| **Kafka producent** | → `cash_flow` (20+ typów zdarzeń domenowych przez `CashFlowEventEmitter`) |
| **Importuje z** | tylko `common`, `shared` |
| **Izolacja** | **LUŹNO POWIĄZANY** — czysta domena, nie zależy od żadnego innego modułu biznesowego. Komunikuje się na zewnątrz tylko przez Kafka events. To najlepszy kandydat do osobnego serwisu. |

### 1.6 `cashflow_forecast_processor` — Projekcja read-model (CQRS)

| | |
|---|---|
| **Cel** | Eventowy read-model. Słucha WSZYSTKICH zdarzeń z `cash_flow` topic i buduje zdenormalizowaną projekcję forecast: miesięczne podsumowania, kategorie, budżety, ewolucja salda |
| **MongoDB** | `cash-flow-forecast-document`, `cash-flow-forecast-statement` |
| **REST** | `GET /cash-flow-forecast/cf={id}`, `GET /cash-flow-forecast/cf={id}/month-statuses` |
| **Kafka konsument** | ← `cash_flow` (group `group_id7`) |
| **Importuje z** | `cashflow` (CashFlowEvent.*, CashFlowId, BankAccountNumber — typy zdarzeń i ID) |
| **Izolacja** | **LUŹNO POWIĄZANY** — klasyczny CQRS read-side. Jedyna zależność to typy zdarzeń z `cashflow`. Jeśli zdarzenia byłyby zdefiniowane jako schemat (np. Avro/JSON Schema), ten moduł byłby w pełni niezależny. |

### 1.7 `bank_data_adapter` — AI transformacja CSV

| | |
|---|---|
| **Cel** | Przyjmuje surowy CSV z banku, wykrywa format, transformuje przez AI (Claude/GPT) do formatu kanonicznego, wzbogaca (merchant, kategoria bankowa), cache'uje reguły mapowania |
| **MongoDB** | `ai_csv_transformations`, `ai_mapping_rules` |
| **REST** | 8 endpointów pod `/api/v1/bank-data-adapter/` — transform, preview, download, import, mapping rules |
| **Kafka** | brak (deleguje do `bank_data_ingestion` przez HTTP) |
| **Importuje z** | `user` (DomainUserRepository — do pobrania userId z JWT) |
| **Izolacja** | **LUŹNO POWIĄZANY** — komunikuje się z `bank_data_ingestion` przez HTTP (`BankDataIngestionClient`). Jedyna zależność compile-time to `user` dla autentykacji. |

### 1.8 `bank_data_ingestion` — Pipeline importu transakcji

| | |
|---|---|
| **Cel** | Wieloetapowy pipeline: staging → mapowanie kategorii → AI kategoryzacja → import do cashflow. Pełne CQRS (10 komend, 5 zapytań). Obsługuje sesje staging, pattern matching, walidację |
| **MongoDB** | `staging_sessions`, `staged_transactions`, `pattern_mappings`, `category_mappings`, `import_jobs` |
| **REST** | 19 endpointów pod `/api/v1/bank-data-ingestion/cf={cfId}/` |
| **Kafka producent** | → `bank_data_ingestion_events` (zdarzenia importu: started, progress, completed, failed) |
| **Importuje z** | `bank_data_adapter` (TransactionClassification), `cashflow` (CashFlowId, CategoryName, Type), `user` (DomainUserRepository) |
| **Komunikacja** | Woła `cashflow` REST API przez `HttpCashFlowServiceClient` (HTTP, nie in-process!) |
| **Izolacja** | **LUŹNO POWIĄZANY** — komunikuje się z cashflow przez HTTP. Zależności compile-time to głównie typy ID. Dobry kandydat do wydzielenia. |

### 1.9 `recurring_rules` — Reguły cykliczne

| | |
|---|---|
| **Cel** | Definiowanie i generowanie cyklicznych transakcji (daily/weekly/monthly/quarterly/yearly). Pause/resume, historia zmian kwot, dashboard |
| **MongoDB** | `recurring-rules` |
| **REST** | 16 endpointów pod `/api/v1/recurring-rules/` |
| **Kafka** | brak |
| **Importuje z** | `cashflow` (CashFlowId, CashChangeId, CategoryName), `user` (DomainUserRepository) |
| **Komunikacja** | Woła `cashflow` REST API przez `CashFlowHttpClient` (HTTP!) |
| **Izolacja** | **LUŹNO POWIĄZANY** — komunikuje się z cashflow przez HTTP. Importuje tylko typy ID z cashflow. |

### 1.10 `user_financial_profile` — Profil finansowy użytkownika

| | |
|---|---|
| **Cel** | Rejestr kont bankowych użytkownika. Konta mogą być dodane ręcznie, przez onboarding, lub automatycznie z CashFlowCreatedEvent |
| **MongoDB** | `user_financial_profiles` |
| **REST** | 5 endpointów pod `/api/v1/user/owned-accounts` |
| **Kafka konsument** | ← `cash_flow` (group `owned_accounts_group`) — reaguje na `CashFlowCreatedEvent` |
| **Kafka producent** | → `user_financial_profile_events` |
| **Importuje z** | `cashflow` (BankAccount, BankAccountNumber, BankName, CashFlowId), `user` (User, queries), `security` (JwtService) |
| **Izolacja** | **ŚCIŚLE POWIĄZANY** — importuje wiele typów domenowych z cashflow. Dodatkowo `user.RegisterUserCommandHandler` bezpośrednio woła `UserFinancialProfileService` (tight coupling!) |

### 1.11 `portfolio` — Zarządzanie portfelami inwestycyjnymi

| | |
|---|---|
| **Cel** | Tworzenie portfeli, depozyty/wypłaty, lock/unlock aktywów, przetwarzanie transakcji handlowych |
| **MongoDB** | `portfolio` |
| **REST** | `POST /portfolio`, `/deposit`, `/withdraw`, `/asset/lock`, `/asset/unlock`, `GET /portfolio/{id}/{currency}`, `/aggregated-portfolio/...`, `/opened-positions/...` |
| **Kafka konsument** | ← `order_filled` (group `group_id6`) |
| **Importuje z** | `quotation` (domain — ceny), `trading` (OpenedPositions, TradingDto) |
| **Izolacja** | **ŚCIŚLE POWIĄZANY** — dwukierunkowa zależność z `trading` (trading importuje PortfolioId, portfolio importuje OpenedPositions). Plus `quotation` import. |

### 1.12 `trading` — Zlecenia i transakcje handlowe

| | |
|---|---|
| **Cel** | Składanie zleceń (LIMIT, STOP, TARGET), wykonywanie transakcji, cykl życia zlecenia |
| **MongoDB** | `trade`, `orders` |
| **REST** | `POST /orders`, `PUT /orders`, `DELETE /orders/{id}`, `GET /orders/{portfolioId}`, `POST /trades`, `GET /trades/...` |
| **Kafka konsument** | ← `trade_captured` (group `group_id4`) |
| **Kafka producent** | → `order_filled` (przez `OrderFilledEventEmitter`) |
| **Importuje z** | `portfolio` (PortfolioId) |
| **Izolacja** | **ŚCIŚLE POWIĄZANY** — cykliczna zależność z `portfolio` |

### 1.13 `quotation` — Notowania i ceny aktywów

| | |
|---|---|
| **Cel** | Rejestr cen aktywów, wielobrokerowy (Binance, Degiro, Exante, PM). UWAGA: ten pakiet zawiera też centralną konfigurację Kafka (`KafkaTopicConfig`) dla WSZYSTKICH topików! |
| **MongoDB** | brak |
| **REST** | `GET /quote/{broker}/{origin}/{destination}`, `PUT /quote/{broker}/`, `GET /quote/publish`, `GET /quote/clearCaches` |
| **Kafka konsument** | ← `quotes` (group `group_id1`) |
| **Kafka producent** | Definiuje WSZYSTKIE KafkaTemplate beans (7 topików!) |
| **Importuje z** | `portfolio` (AssetBasicInfo) |
| **Izolacja** | **ŚCIŚLE POWIĄZANY** — jest "hubem" Kafki. `KafkaTopicConfig` definiuje template'y dla topików z cashflow, user, trading, bank_data_ingestion itd. Plus import z portfolio. |

### 1.14 `pnl` — Profit & Loss

| | |
|---|---|
| **Cel** | Historia P&L użytkownika, snapshoty zysków/strat per portfolio |
| **MongoDB** | `pnl-history` |
| **REST** | `GET /pnl/userId={userId}`, `POST /pnl` |
| **Kafka konsument** | ← `user_created` (group `group_id2`) — inicjalizuje P&L history |
| **Importuje z** | `portfolio` (PortfolioId, PortfolioDto, TradingRestClient), `trading` (TradingDto), `user` (PortfolioRestClient, UserNotFoundException) |
| **Izolacja** | **ŚCIŚLE POWIĄZANY** — bezpośrednio importuje klasy z 3 modułów biznesowych |

### 1.15 `risk_management` — Zarządzanie ryzykiem

| | |
|---|---|
| **Cel** | Oblicza RAG status (Red/Amber/Green) i stop-lossy per pozycja |
| **MongoDB** | brak (pure computation) |
| **REST** | `GET /risk-management/{portfolioId}` |
| **Kafka** | brak |
| **Importuje z** | `portfolio` (PortfolioId, PortfolioDto, QuoteRestClient, TradingRestClient), `trading` (TradingDto), `user` (PortfolioRestClient) |
| **Izolacja** | **ŚCIŚLE POWIĄZANY** — identyczny pattern jak `pnl` |

### 1.16 `task` — Zarządzanie zadaniami

| | |
|---|---|
| **Cel** | Prosty CRUD zadań z komentarzami i statusami |
| **MongoDB** | `task` |
| **REST** | `POST /task`, `PUT /task/close`, `GET /task/{taskId}`, `GET /task` |
| **Kafka** | brak |
| **Importuje z** | tylko `common`, `shared` |
| **Izolacja** | **IZOLOWANY** — w pełni niezależny moduł |

---

## 2. Diagram zależności

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                    SHARED KERNEL                                        │
│                          common  +  shared  +  security                                 │
│                    (value objects, CQRS, DDD, JWT, ErrorHandler)                         │
└──────────────────────────────────────┬──────────────────────────────────────────────────┘
                                       │ importowany przez wszystko
                                       │
       ┌───────────────────────────────┼───────────────────────────────────┐
       │                               │                                   │
       ▼                               ▼                                   ▼
  ═══════════                    ═══════════════                    ════════════════
  ║  TRADING  ║                  ║  CASHFLOW    ║                  ║    TASK      ║
  ║  CLUSTER  ║                  ║  CLUSTER     ║                  ║  (izolowany) ║
  ═══════════                    ═══════════════                    ════════════════
       │                               │
       │                               │ Kafka: cash_flow topic
       │                               │
  ┌────┴──────────────┐    ┌───────────┼───────────────────────────┐
  │                   │    │           │                           │
  ▼                   ▼    ▼           ▼                           ▼
┌──────────┐  ┌──────────┐ ┌────────────────────┐  ┌──────────────────────────┐
│portfolio │◄►│ trading  │ │cashflow_forecast   │  │ user_financial_profile   │
│          │  │          │ │_processor          │  │                          │
│ Kafka:   │  │ Kafka:   │ │                    │  │ Kafka: ← cash_flow      │
│←order_   │  │←trade_   │ │ Kafka: ← cash_flow│  │ Kafka: → user_fin_prof  │
│  filled  │  │ captured │ │ (read-model/CQRS)  │  │                          │
└────┬─────┘  └──────────┘ └────────────────────┘  └──────────────────────────┘
     │                               ▲                           ▲
     │                               │ importuje typy            │ direct call
     │                               │ zdarzeń                   │
     ▼                     ┌─────────┴──────────┐    ┌───────────┴──────┐
┌──────────┐               │                    │    │                  │
│quotation │               │  bank_data_        │    │     user         │
│          │               │  ingestion    HTTP  │    │                  │
│ Kafka:   │               │  ─────────────►     │    │ Kafka:           │
│←quotes   │               │  cashflow           │    │ → user_created   │
│ HUB:     │               │                    │    │                  │
│ defines  │               │  Kafka: →          │    └──────────────────┘
│ ALL kafka│               │  bank_data_ing     │              ▲
│ templates│               └────────┬───────────┘              │
└──────────┘                        │                          │
     │                              │ importuje                │
     ▼                              │ TransactionClassification│
┌──────────┐               ┌────────┴───────────┐    ┌────────┴────────┐
│   pnl    │               │ bank_data_adapter  │    │recurring_rules  │
│          │               │                    │    │                 │
│ Kafka:   │               │ (AI CSV transform) │    │ HTTP ──────►    │
│←user_    │               │ HTTP ──────►        │    │ cashflow        │
│ created  │               │ bank_data_ingestion│    │                 │
└──────────┘               └────────────────────┘    └─────────────────┘

┌──────────┐
│  risk_   │
│management│
│(stateless│
│ compute) │
└──────────┘
```

### Przepływ Kafka (uproszczony)

```
user registration ──► [user_created] ──► pnl (init history)

make trade ──► [trade_captured] ──► fill order ──► [order_filled] ──► portfolio update

cashflow mutation ──► [cash_flow] ──► forecast_processor (update read-model)
                                 ──► user_financial_profile (register bank account)

import job ──► [bank_data_ingestion_events] ──► (brak konsumenta w kodzie — WebSocket?)

profile change ──► [user_financial_profile_events] ──► (brak konsumenta w kodzie)
```

---

## 3. Naturalne granice serwisów (Bounded Contexts)

Na podstawie analizy zależności widzę **3 naturalne klastry** + moduły izolowane:

### Klaster A: CashFlow (core business)
```
cashflow  +  cashflow_forecast_processor  +  bank_data_ingestion  +  bank_data_adapter  +  recurring_rules
```
- **Spoiwo**: Kafka topic `cash_flow`, typy `CashFlowId`, `CategoryName`, `CashChangeId`
- **Komunikacja wewnętrzna**: bank_data_ingestion → cashflow przez HTTP, recurring_rules → cashflow przez HTTP, forecast_processor ← cashflow przez Kafka
- **To jest Twój główny produkt** — zarządzanie finansami osobistymi

### Klaster B: Trading
```
portfolio  +  trading  +  quotation  +  pnl  +  risk_management
```
- **Spoiwo**: `PortfolioId`, łańcuch Kafka `trade_captured → order_filled`, wspólne typy `TradingDto`, `PortfolioDto`
- **Cykliczna zależność**: portfolio ↔ trading (trzeba by wydzielić shared types)
- **To jest subdomena inwestycyjna** — niezależna od CashFlow

### Klaster C: Identity & Auth
```
user  +  security  +  user_financial_profile
```
- **Spoiwo**: `User`, `UserId`, JWT, role
- **Tight coupling**: user → user_financial_profile (bezpośredni call przy rejestracji)

### Moduł D: Task (izolowany)
```
task
```
- W pełni niezależny, może być osobnym mikroserwisem natychmiast

---

## 4. Problemy blokujące wydzielenie

### 4.1 `KafkaTopicConfig` w `quotation` — GOD CONFIG
`quotation/KafkaTopicConfig.java` definiuje **WSZYSTKIE** KafkaTemplate beans i topic definitions dla całej aplikacji (7 topików). To jest największa przeszkoda w dekompozycji.

**Rozwiązanie**: Przenieść definicje topików i template'ów do modułów, które ich faktycznie używają:
- `cash_flow` template → `cashflow`
- `bank_data_ingestion_events` template → `bank_data_ingestion`
- `user_financial_profile_events` template → `user_financial_profile`
- `user_created` template → `shared` (lub `user`)
- `trade_captured`, `order_filled` templates → `shared` (lub `trading`)
- `quotes` template → zostaje w `quotation`

### 4.2 `ErrorHttpHandler` w `security` — CENTRALNY EXCEPTION HANDLER
`security/config/ErrorHttpHandler.java` obsługuje wyjątki z **WSZYSTKICH** modułów. Importuje klasy exception z: `bank_data_adapter`, `bank_data_ingestion`, `cashflow`, `user_financial_profile`.

**Rozwiązanie**: Przenieść `ErrorHttpHandler` do `common` lub do każdego modułu jego własny handler. Alternatywnie: jeden globalny handler bazujący na hierarchii wyjątków (np. `BusinessException` w `common`).

### 4.3 `user` → `user_financial_profile` — DIRECT CALL
`RegisterUserCommandHandler` bezpośrednio woła `UserFinancialProfileService.createProfile()`.

**Rozwiązanie**: Zamienić na Kafka event — `user_financial_profile` powinien reagować na `UserCreatedEvent` (topic `user_created` już istnieje!).

### 4.4 `user` → `portfolio` — IN-PROCESS CALL
`PortfolioRestClientImpl` w `user` bezpośrednio woła `CommandGateway`/`QueryGateway` z `portfolio` (in-process, nie HTTP).

**Rozwiązanie**: Zamienić na prawdziwy HTTP client, analogicznie jak `bank_data_ingestion` komunikuje się z `cashflow`.

### 4.5 Shared domain types: `CashFlowId`, `PortfolioId`, `CategoryName`
Te typy żyją w swoich modułach (`cashflow.domain`, `portfolio.domain`), ale są importowane przez wiele innych.

**Rozwiązanie**: Wydzielić do `common` jako shared identity types, lub zdefiniować jako proste `String`/wrapper w każdym module osobno.

---

## 5. Strategia wydzielania — od czego zacząć

### Faza 0: Przygotowanie (bez zmiany repo)
1. Przenieść `KafkaTopicConfig` — rozproszyć do modułów
2. Przenieść `ErrorHttpHandler` — do `common` z generic exception hierarchy
3. Przenieść shared ID types (`CashFlowId`, `PortfolioId`) do `common`
4. Zamienić direct calls na HTTP/Kafka (user→portfolio, user→user_financial_profile)

### Faza 1: Maven multi-module (jedno repo, wiele modułów)
```
vidulum/
├── pom.xml (parent)
├── vidulum-common/          ← common + shared
├── vidulum-security/        ← security
├── vidulum-cashflow/        ← cashflow
├── vidulum-forecast/        ← cashflow_forecast_processor
├── vidulum-ingestion/       ← bank_data_ingestion + bank_data_adapter
├── vidulum-recurring/       ← recurring_rules
├── vidulum-trading/         ← portfolio + trading + quotation
├── vidulum-pnl/             ← pnl + risk_management
├── vidulum-user/            ← user + user_financial_profile
├── vidulum-task/            ← task
└── vidulum-app/             ← main Spring Boot application (imports all)
```

**Korzyści bez ryzyka**: wymusza jawne zależności między modułami (Maven dependency graph), kompilacja łapie niechciane importy, ale nadal jedno repo i jeden deploy.

### Faza 2: Osobne repozytoria + mikroserwisy

Kolejność wydzielania (od najprostszego):

| Priorytet | Moduł | Trudność | Powód |
|-----------|-------|----------|-------|
| 1 | `task` | Trivial | Zero zależności biznesowych |
| 2 | `cashflow_forecast_processor` | Łatwa | Komunikuje się wyłącznie przez Kafka, własna baza |
| 3 | `bank_data_adapter` | Łatwa | Komunikuje się przez HTTP, własna baza |
| 4 | `recurring_rules` | Łatwa | Komunikuje się przez HTTP, własna baza |
| 5 | `bank_data_ingestion` | Średnia | HTTP do cashflow, ale importuje typy z cashflow i bank_data_adapter |
| 6 | `cashflow` | Średnia | Core, ale czysta domena — trzeba wydzielić shared types |
| 7 | Trading cluster | Trudna | Cykliczne zależności portfolio ↔ trading |
| 8 | User/Security | Trudna | Centralny auth, ErrorHandler, tight coupling |

---

## 6. Docker deployment po wydzieleniu

### Obecny stan (monolit)
```
1 JAR → 1 Docker image → 1 kontener
+ MongoDB (1 instancja, wiele kolekcji)
+ Kafka (1 broker, 7 topików)
```

### Po wydzieleniu: opcje

#### Opcja A: Mono-repo z wieloma JARami (Maven multi-module)
```
vidulum/
├── docker-compose.yml
├── vidulum-cashflow/Dockerfile       ← osobny JAR
├── vidulum-forecast/Dockerfile       ← osobny JAR
├── vidulum-ingestion/Dockerfile      ← osobny JAR
└── ...
```

```yaml
# docker-compose.yml
services:
  mongodb:
    image: mongo:8.0
    
  kafka:
    image: confluentinc/cp-kafka:7.8.1

  cashflow-service:
    build: ./vidulum-cashflow
    environment:
      SPRING_DATA_MONGODB_DATABASE: cashflow_db
      
  forecast-service:
    build: ./vidulum-forecast
    environment:
      SPRING_DATA_MONGODB_DATABASE: forecast_db

  ingestion-service:
    build: ./vidulum-ingestion
    environment:
      SPRING_DATA_MONGODB_DATABASE: ingestion_db
      CASHFLOW_SERVICE_URL: http://cashflow-service:8080
```

**Zalety**: Jedno repo, jeden `docker-compose`, łatwy development
**Wady**: Nadal jeden git history, jedno CI

#### Opcja B: Multi-repo z Dockploy

Każdy serwis ma własne repo:

```
github.com/you/vidulum-cashflow      ← repo 1
github.com/you/vidulum-forecast      ← repo 2
github.com/you/vidulum-common        ← repo 3 (Maven artifact, publikowany do registry)
```

**Shared code (`vidulum-common`)** — publikujesz jako Maven artifact:
```xml
<!-- W vidulum-cashflow/pom.xml -->
<dependency>
    <groupId>com.multi.vidulum</groupId>
    <artifactId>vidulum-common</artifactId>
    <version>1.2.0</version>
</dependency>
```

**Gdzie publikować artifact?**
- GitHub Packages (darmowe z prywatnym repo)
- Własny Nexus/Artifactory na VPS
- Albo po prostu `git submodule` (prostsze, mniej infrastruktury)

**Docker build per repo:**
```bash
# W każdym repo osobno:
./mvnw package -DskipTests
docker build -t vidulum-cashflow:latest .
docker push your-registry/vidulum-cashflow:latest
```

**Dockploy deployment:**
```yaml
# Na VPS z Dockploy — docker-compose.yml
services:
  mongodb:
    image: mongo:8.0
  kafka:
    image: confluentinc/cp-kafka:7.8.1
    
  cashflow:
    image: your-registry/vidulum-cashflow:latest
    environment:
      SPRING_DATA_MONGODB_URI: mongodb://mongodb:27017/cashflow
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      
  forecast:
    image: your-registry/vidulum-forecast:latest
    environment:
      SPRING_DATA_MONGODB_URI: mongodb://mongodb:27017/forecast
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      
  ingestion:
    image: your-registry/vidulum-ingestion:latest
    environment:
      CASHFLOW_SERVICE_URL: http://cashflow:8080
```

#### Opcja C: Hybrydowa (REKOMENDOWANA)

**Mono-repo z Maven multi-module + jeden docker-compose, ale osobne JARy i kontenery.**

```
vidulum/                          ← jedno repo
├── pom.xml                       ← parent POM
├── common/                       ← shared kernel (dependency, nie serwis)
├── cashflow-service/             ← Spring Boot app
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
├── forecast-service/             ← Spring Boot app
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
├── ingestion-service/            ← Spring Boot app
├── trading-service/              ← Spring Boot app
├── docker-compose.yml            ← wszystko razem
└── deploy.sh                     ← buduje wszystkie images i restartuje
```

```bash
# deploy.sh
#!/bin/bash
./mvnw clean package -DskipTests          # buduje wszystkie moduły
docker build --no-cache -t vidulum-cashflow:latest ./cashflow-service
docker build --no-cache -t vidulum-forecast:latest ./forecast-service
docker build --no-cache -t vidulum-ingestion:latest ./ingestion-service
docker-compose down -v
docker-compose up -d
```

**Zalety:**
- Jedno repo = prosty development, jeden PR, jeden CI pipeline
- Maven wymusza granice zależności (moduł A nie może importować z B jeśli nie ma dependency)
- Osobne kontenery = niezależne skalowanie, restart, logi
- Łatwe przejście do multi-repo później (moduł = repo)

**Wady:**
- Więcej RAM na VPS (każdy JVM ~256-512MB)
- Bardziej złożony docker-compose

---

## 7. Rekomendacja

Biorąc pod uwagę, że:
- Aplikacja jest w fazie developmentu (brak produkcji)
- Jesteś jedynym developerem
- Infrastruktura to VPS z Dockploy

### Moja rekomendacja: Faza 1 teraz, Faza 2 później

1. **Teraz**: Rozwiąż problemy z pkt. 4 (KafkaTopicConfig, ErrorHttpHandler, direct calls)
2. **Potem**: Migracja do Maven multi-module (jedno repo, wiele modułów) — to da Ci jasne granice bez overhead mikroserwisów
3. **Kiedy będzie potrzeba**: Wydzielaj moduły do osobnych serwisów (osobne kontenery w jednym docker-compose)
4. **Na koniec**: Multi-repo tylko gdy będziesz miał zespół i potrzebujesz niezależnych cykli deploymentu

Mikroserwisy dla jednego developera to overhead bez korzyści. Maven multi-module da Ci 90% korzyści (wymuszone granice, czytelność) za 10% kosztu.
