# Moduł alertów: kto orzeka, że coś jest alertem

| | |
|---|---|
| **Status** | projekt, **nic nie zaimplementowane** |
| **Skąd** | pytanie „a jak te alerty mogłyby wyglądać?" przy propozycji wydzielenia `pnl` i `risk` jako osobnych modułów |
| **Zakres** | nowy `vidulum-alerts`; fakty do opublikowania w `cashflow`, `cashflow_forecast_processor`, `vidulum-pnl`, `vidulum-risk` (od kroku 09 osobne artefakty, oba bez `-api` — ten moduł jest pierwszym kandydatem na ich pierwszego konsumenta); topic `alerts` w `websocket-gateway` |
| **Zastępuje** | sekcję 7 („Alert System Design") w `2026-02-14-business-analysis-alerts-cashchange-lifecycle.md` — analiza biznesowa i katalog typów z tamtego dokumentu zostają aktualne, część projektowa jest sprzed podziału na moduły |
| **Data** | 2026-10-15 |

---

## 1. Stan: najdroższa część istnieje, najtańsza nie

Policzone w repo, nie założone:

| | stan |
|---|---|
| **dostawa in-app** | ✅ `websocket-gateway` — **osobny deployment**: JWT (`JwtService`), rejestr sesji (`SessionRegistry`), subskrypcje po użytkowniku i `cashFlowId` (`SubscriptionManager`), konsument Kafki (`KafkaEventConsumer`) na topicach `cash_flow` i `bank_data_ingestion`, rozsyłka po WebSockecie (`EventBroadcaster`) |
| **e-mail / SMS / push** | ❌ zero zależności w pomach — ani `spring-boot-starter-mail`, ani Twilio, ani Firebase |
| **kod alertów** | ❌ zero klas; `grep -rli "alert\|notification"` po wszystkich `src/main` nie zwraca nic |
| **fakty do alertowania** | ⚠️ topic `cash_flow` niesie ~25 rekordów zdarzeń, jest budżetowanie (`BudgetingSetEvent`) i prognoza — ale **`CashChangeStatus` to `PENDING, CONFIRMED, REJECTED, ARCHIVED`**: nie ma `OVERDUE` ani `UNMATCHED`, i **nikt nie porównuje `dueDate` z dniem bieżącym** |

Wniosek, który wyznacza kolejność prac: **nie brakuje modułu alertów, brakuje faktów, o których miałby mówić.** Dwa pierwsze alerty z lutowego dokumentu (`OVERDUE_TRANSACTION`, `UNMATCHED_TRANSACTION`) nie mają pod sobą niczego, co ktoś dziś liczy.

## 2. Decyzja, od której zależy cała reszta

```
  (A) alerty czytają cudze dane i same oceniają      (B) domena publikuje fakt, alerty decydują o dostawie
  ┌──────────┐  pyta o salda, terminy, prognozy      ┌──────────┐  fakt   ┌──────────┐  dedup/digest/kanał
  │  alerty  │ ──────────────────────────────▶       │ cashflow │ ──────▶ │  alerty  │ ──────▶ gateway → WS
  └──────────┘  ← musi znać reguły cashflow          └──────────┘         └──────────┘
```

Wariant **A** to drugi mózg: żeby wiedzieć, że prognoza schodzi pod zero, alerty musiałyby umieć to, co umie `cashflow_forecast_processor`, i sięgać do jego wnętrza — dokładnie to, co usunęło osiem kroków z
`2026-10-12-module-contracts-api-impl-split.md`.

**Rekomendacja: B**, z jedną granicą, która nie jest oczywista:

- **warunek domenowy** — „przeterminowane", „prognoza pod zero", „budżet kategorii przekroczony":
  progiem są **dane domeny** (termin, budżet), więc **orzeka domena** i publikuje fakt;
- **próg użytkownika** — „saldo poniżej 5000 PLN", „transakcja powyżej 10 000": to **preferencja
  powiadomień**, nie reguła domeny. Domena publikuje pomiar (kwotę), **predykat stosują alerty**.

Ta linia rozstrzyga również, czego alerty nie robią **nigdy**: nie liczą niczego, czego nie da się policzyć z preferencji użytkownika i treści zdarzenia.

## 3. Kształt modułu

Alerty — jak `pnl` i `risk` — są **liściem**: nikt ich o nic nie pyta, konsumują cudze kontrakty i odpowiadają po HTTP. Więc `vidulum-alerts` **bez `vidulum-alerts-api`**; kontrakt powstanie, gdy pojawi się pierwszy konsument, a nie „na zapas" (lekcja z usuniętego `InMemoryPortfolioModuleApi`, sekcja 21 dokumentu podziału).

```
vidulum-alerts/
├── alerts/domain/          AlertId · Alert · AlertType · Severity · AlertKey
│                           AlertPreferences (agregat per użytkownik) · DeliveryChannel
├── alerts/app/listeners/   CashFlowAlertListener · ValuationAlertListener · RiskAlertListener
├── alerts/app/commands/    RaiseAlertCommand · AcknowledgeAlertCommand · SetAlertPreferencesCommand
├── alerts/app/queries/     GetAlertsQuery(unreadOnly, window)
├── alerts/app/             AlertRestController · AlertDto
└── alerts/infrastructure/  AlertMongoRepository · AlertDataCleaner · AlertEventEmitter → topic "alerts"
```

Zależności: `shared-kernel` + kontrakty źródeł zdarzeń (`quotation-api` niepotrzebne; `portfolio-api` tylko jeśli alert ma nieść nazwę portfela). W reaktorze **za** modułami, których zdarzeń słucha. `AlertDataCleaner` — bo moduł persystuje (patrz checklista encji w `CLAUDE.md`).

```java
public record Alert(AlertId id, UserId owner, AlertType type, Severity severity,
                    AlertKey key,              // klucz deduplikacji, patrz 4
                    String subject,            // „Czynsz 2000 PLN"
                    Map<String, String> facts, // dane do wyrenderowania, bez gotowego zdania
                    ZonedDateTime raisedAt, ZonedDateTime acknowledgedAt) {}

public enum Severity { INFO, WARNING, CRITICAL }
```

Dwie rzeczy celowo:

- **`AlertType` grupowany po źródle faktu, nie po temacie** (`CASHFLOW_*`, `PORTFOLIO_*`, `RISK_*`) —
  żeby po typie było od razu widać, który moduł musi coś opublikować, zanim alert zadziała.
- **`facts` zamiast gotowego `message`.** Tłumaczenie i formatowanie kwot należy do klienta; alert
  zapisany z polskim zdaniem w środku nie da się potem pokazać po angielsku, a historia alertów jest
  trwała.

REST: `GET /alerts?unreadOnly=true`, `POST /alerts/{id}/acknowledge`, `GET|PUT /alerts/preferences`.
Własność z JWT, nigdy z ciała żądania — jak w `/exchange-connection`.

## 4. Najtrudniejsza część to nie wysyłka, a „nie powtarzaj"

Scheduler z lutowego projektu odpala się co dobę i wysyła alert — czyli **ta sama przeterminowana transakcja alarmuje codziennie, aż do zapłaty**. Dodatkowo Kafka dostarcza *przynajmniej raz*, więc to samo zdarzenie może przyjść dwa razy. W repo jest już na to wzorzec:

```java
/** Jedna sprawa, jeden alert: (właściciel, typ, podmiot, okno). Unikalny indeks w Mongo. */
public record AlertKey(UserId owner, AlertType type, String subjectId, String bucket) {}
```

`bucket` to jednostka powtarzalności: `"once"` dla przeterminowania (raz na transakcję), `"2026-10"` dla budżetu miesięcznego, `"2026-10-15"` dla alertu dziennego. Unikalny indeks na `AlertKey` załatwia **jednym mechanizmem** deduplikację reguły i powtórkę z Kafki — tak jak **F1** zrobiło z `OriginTradeId`, a **F10** z dwukrotnym zastosowaniem zapisanej transakcji.

Dwa zastrzeżenia z zadań **F11** i **F12**, bez których indeks jest dekoracją:

1. `@CompoundIndex` nie tworzy niczego, dopóki `spring.data.mongodb.auto-index-creation: true`;
2. `AlertDataCleaner` musi **czyścić kolekcję** (`remove`), nie usuwać jej (`dropCollection`) — inaczej
   zabiera indeks ze sobą.

Test, który to pilnuje, sprawdza indeks **w bazie** (`mongoTemplate.indexOps(...).getIndexInfo()`), a nie obecność adnotacji — jak `TradeUniquenessIndexTest`.

## 5. Czego brakuje po stronie innych modułów

To właściwa lista zadań; alerty same z siebie nie powstaną.

| alert | kto publikuje fakt | czego dziś brakuje |
|---|---|---|
| `CASHFLOW_TRANSACTION_OVERDUE` | cashflow | statusu `OVERDUE` nie ma w `CashChangeStatus`; nikt nie porównuje `dueDate` z dniem bieżącym |
| `CASHFLOW_FORECAST_NEGATIVE` | `cashflow_forecast_processor` | prognoza jest wyliczana, ale nie publikuje faktu „saldo projektowane < 0" |
| `CASHFLOW_BUDGET_EXCEEDED` | cashflow | budżety są (`BudgetingSetEvent`), brakuje porównania wydatków z budżetem jako zdarzenia |
| `PORTFOLIO_VALUATION_RECORDED` | pnl | `PortfolioValuationRecordedEvent` nie istnieje — propozycja kontraktu pnl |
| `RISK_POSITION_WENT_RED` | risk | risk liczy **na żądanie i nie pamięta poprzedniego `RagStatus`**, więc nie ma z czym porównać; wymaga persystencji ostatniej oceny |

Ostatni wiersz jest najważniejszy do zapamiętania: „pozycja zrobiła się RED" wygląda jak najtańszy alert, a jest najdroższy — zmiana wymaga stanu, którego ten moduł świadomie nie trzyma.

## 6. Pierwszy krój: jeden przepływ bez zmian w żadnym innym module

**`CASHFLOW_LARGE_TRANSACTION`** — „zapłacono więcej niż X".

- fakt **już leci**: `PaidCashChangeAppendedEvent` niesie `Money money`, `categoryName`, `paidDate`;
- próg jest **preferencją użytkownika**, czyli po stronie alertów (punkt 2);
- dostawa **już istnieje** (punkt 1).

Przy tym krojeniu wyjdzie jednak rzecz do rozstrzygnięcia od razu: **zdarzenia cashflow nie noszą `UserId`.**

```java
record PaidCashChangeAppendedEvent(CashFlowId cashFlowId, CashChangeId cashChangeId, Name name,
        Description description, Money money, Type type, ZonedDateTime created,
        CategoryName categoryName, ZonedDateTime dueDate, ZonedDateTime paidDate,
        boolean selfTransfer) implements CashFlowEvent {}

record CashChangeConfirmedEvent(CashFlowId cashFlowId, CashChangeId cashChangeId,
        ZonedDateTime endDate) implements CashFlowEvent {}   // ← ani właściciela, ani kwoty
```

`UserId` niesie tylko `CashFlowCreatedEvent`. Trzy drogi:

1. **Alerty trzymają własną projekcję** `cashFlowId → (owner, waluta)` oraz kwoty transakcji, budowaną
   ze zdarzeń tworzenia. Zero zmian u sąsiadów i zero zapytań w poprzek — ceną jest to, że **alerty
   przestają być bezstanowe**, i trzeba to przyjąć świadomie.
2. **Dołożyć `UserId` do zdarzeń** — a to jest i tak tematem **kroku 05c** (trzy `*UnifiedEvent` i
   payloady na recordy). Jeśli alerty mają być pierwszym konsumentem spoza cashflow, to naturalny
   moment.
3. Kontrakt `CashFlowModuleApi.ownerOf(cashFlowId)` — ale **cashflow nie ma dziś żadnego `-api`**, więc
   to otwiera osobny, duży front.

**Rekomendacja: 1 na start, 2 przy 05c.** Projekcja i tak będzie potrzebna, bo bez niej
`CashChangeConfirmedEvent` — same identyfikatory i data — jest nieinterpretowalny.

## 7. Dostawa

**In-app**: nowy topic `alerts`, dopisany do `KafkaEventConsumer` i `SubscriptionManager` w
`websocket-gateway`. Subskrypcje są dziś po `cashFlowId`; alerty są **per użytkownik**, więc gateway
potrzebuje drugiego wymiaru subskrypcji — to jedyna zmiana w nim potrzebna. Uwaga: gateway jest **poza
reaktorem** (zadanie **F4**), więc zmiana w nim to osobny build i osobny deployment.

**E-mail / SMS, gdy przyjdzie czas**: Kafka nie wystarczy. „Wyślij raz" przy at-least-once wymaga
**outboxa** po stronie alertów — zapis alertu i zapis zamiaru wysyłki w jednej transakcji, osobny
proces wysyłający, oznaczanie wysłanych. Bez tego restart konsumenta oznacza drugi mail do
użytkownika. Szablony z lutowego dokumentu zakładają infrastrukturę, której nie ma w żadnym pomie.

**Digest kontra natychmiast** z `AlertSettings` jest realny i tani przy wzorcu z punktu 4: alert
powstaje zawsze od razu (jest zapisem faktu), a `digestMode` decyduje tylko, czy osobny proces
wysyłkowy bierze go pojedynczo, czy raz na dobę zbiorczo.

## 8. Co zrobić z dokumentem z lutego

`2026-02-14-business-analysis-alerts-cashchange-lifecycle.md` (1597 linii) ma dobrą analizę biznesową,
porównanie z Agicapem/Floatem/Pulse i katalog typów alertów — to zostaje. Jego **sekcja 7 jest sprzed
podziału na moduły i sprzed obecnego kształtu agregatów**: scheduler sięga prosto do
`cashChangeRepository`, robi `cc.setStatus(OVERDUE)` i zapisuje, a `CashFlow` jest agregatem ze
snapshotami i zdarzeniami — tak się w nim stanu nie zmienia. Propozycja: zostawić tam analizę i
katalog, a projekt czytać z tego pliku.

## 9. Kolejność

1. **Pierwszy krój** (punkt 6) — `vidulum-alerts` z jednym typem alertu, projekcją właściciela,
   unikalnym indeksem na `AlertKey` i topikiem `alerts`; gateway dostaje subskrypcję per użytkownik.
2. **Preferencje** — `AlertPreferences` i progi; bez nich pierwszy krój ma próg z konfiguracji.
3. **Fakty domenowe** z tabeli w punkcie 5, każdy jako osobne zadanie w module, który je liczy.
   Najtańszy pierwszy: `CASHFLOW_BUDGET_EXCEEDED`, bo budżety już są.
4. **E-mail z outboxem** — dopiero gdy in-app działa i jest co wysyłać.

## 10. Powiązania

- `2026-02-14-business-analysis-alerts-cashchange-lifecycle.md` — analiza biznesowa, katalog typów,
  projekt lifecycle'u `CashChange` (statusy `OVERDUE`/`UNMATCHED` to warunek alertów z punktu 5)
- `2026-10-12-module-contracts-api-impl-split.md` — krok **05c** (`*UnifiedEvent`, `UserId` w
  payloadach), reguła „kontrakt powstaje przy pierwszym konsumencie", wzorzec zadeklarowanej obsługi
  zdarzeń (`TradeConfirmedHandler` z `TOPIC` i `CONTAINER_FACTORY`)
- Zadania **F1**, **F10** — idempotencja i unikalny indeks jako jeden mechanizm
- Zadania **F11**, **F12** — indeks nie przeżyje startu bez `auto-index-creation` i bez cleanera,
  który czyści, a nie usuwa kolekcję
- Zadanie **F4** — `websocket-gateway` poza reaktorem
- `2026-02-07-intelligent-cashflow-reconciliation.md` — pokrywa się z `RULE_MATCH_SUGGESTION` i
  `AMOUNT_ANOMALY`: te alerty mają sens dopiero po tamtym zadaniu
