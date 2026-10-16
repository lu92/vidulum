# Kontrakty modułów: podział `-api` / impl (wzorzec NPay)

| | |
|---|---|
| **Status** | wykonane: **02**, **02b**, **04**, **04b**, **05a**, **03**, **06**, **06b**, **06c**, **08**, **09**, **10**, **11a**, **12** (zawiera **01**). Pozostaje: 05b, 05c, 05d, 07 |
| **Zakres** | `vidulum-wealth`: portfolio, trading, pnl, quotation, risk_management, portfolio_spec |
| **Poprzednik** | `VID-103-maven-multi-module-migration.md` — tamten dokument rozciął projekt na moduły Maven; ten rozcina wnętrze `vidulum-wealth` na kontrakt i implementację |
| **Źródła** | [devmentors/NPay](https://github.com/devmentors/NPay) @ `master` (169 plików, .NET 6) — przeczytane w całości; vidulum @ `80d0cff` |
| **Data** | 2026-10-12, zaktualizowany 2026-10-13 (po kroku 04) |

---

## 1. Wnioski w skrócie

NPay nie używa żadnego frameworka do modułów. Cały wzorzec to **pięć decyzji** (sekcja 3) i jeden
mechanizm egzekwowania: `internal` + granica assembly. W Javie odpowiednikiem tego mechanizmu jest
**granica artefaktu Maven** — nie `package-private`.

Sześć problemów w dzisiejszym `vidulum-wealth`, od najdroższego:

| | Problem | Sekcja |
|---|---|---|
| **P1** | `PortfolioRestClientImpl` leży w `vidulum-app` i wstrzykuje 5 konkretnych handlerów portfolio | 10 |
| **P2** | Cykle: portfolio ↔ trading (13/11 importów), quotation → portfolio | 10 |
| **P3** | `trading/infrastructure/WealthDataCleaner` importuje encje trzech innych poddomen | 10 |
| **P4** | 7 integration eventów w `shared-kernel/common/events` zamiast u producentów | 10 |
| **P5** | 45 typów w `common`, w tym typy cudzych domen | 10 |
| **P6** | Zero ArchUnit — nic nie pilnuje granic | 10 |

Najtańszy pierwszy ruch — **`vidulum-quotation-api`** (sekcja 13) — jest **zrobiony** (VID-199),
podobnie jak wydzielenie samej implementacji (VID-200). Reaktor ma dziś jedenaście modułów.
Następny krok, który usuwa realne sprzężenie, to **P1** i `vidulum-portfolio-api` (krok 04).

---

## 2. NPay — anatomia modułu

Trzy moduły biznesowe, jeden globalny shared, jeden bootstrapper. Każdy moduł to **kilka
projektów `.csproj`**, nie jeden — i to rozbicie jest całą esencją wzorca, bo właśnie na granicy
projektów kompilator C# zaczyna pilnować dostępu.

```
src/
├── Shared/NPay.Shared                     # framework: ICommand, IEvent, IClock,
│                                          #   IMessageBroker, error handling, Postgres
│                                          #   ZERO typów biznesowych
├── Modules/
│   ├── Users/
│   │   ├── Users.Shared        PUBLIC     # IUsersModuleApi + DTO + Events
│   │   ├── Users.Core          internal   # User, UsersService, UsersDbContext, migracje
│   │   └── Users.Api           public     # UsersController + AddUsersModule()
│   ├── Wallets/
│   │   ├── Wallets.Shared      PUBLIC     # IWalletsModuleApi + DTO + Events
│   │   ├── Wallets.Core        internal   # Wallet, Owner, VO, I*Repository, domain services
│   │   ├── Wallets.Application internal   # commands/queries + handlery, ExternalEvents,
│   │   │                                  #   WalletsModuleApi (impl), IWalletStorage (port)
│   │   ├── Wallets.Infrastructure         # WalletsDbContext, repozytoria, WalletStorage
│   │   └── Wallets.Api         public     # 3 kontrolery + AddWalletsModule()
│   └── Notifications/
│       ├── Notifications.Shared PUBLIC    # INotificationsModuleApi
│       └── Notifications.Api              # handlery eventów + EmailSender
└── Bootstrapper/NPay.Bootstrapper         # referencje TYLKO do *.Api
```

Notifications nie ma `Core` ani `Application`. **Moduł dostaje tyle warstw, ile ma logiki** —
Wallets ma pięć projektów, Notifications dwa. Nie ma „szablonu modułu", do którego wszystko się
dopasowuje.

### Co w którym projekcie

| Projekt | Zawiera | Widoczność | Zależy od |
|---|---|---|---|
| `*.Shared` | `I<X>ModuleApi`, DTO, integration events | public | `NPay.Shared` |
| `*.Core` | agregaty, VO, interfejsy repozytoriów, domain services | **internal** + `InternalsVisibleTo(Application, Infrastructure)` | `NPay.Shared` |
| `*.Application` | komendy, zapytania, handlery, `ExternalEvents/Handlers`, impl `ModuleApi`, porty read-modelu | internal, API publiczne | `Core`, `Shared`, **obce `*.Shared`** |
| `*.Infrastructure` | `DbContext`, konfiguracje EF, migracje, repozytoria, storage | internal | `Application` |
| `*.Api` | kontrolery + `AddXModule()` / `UseXModule()` | public | `Infrastructure` |

---

## 3. Pięć reguł, które to trzyma

### R1 — jedna powierzchnia

Moduł ma **dokładnie jeden** publiczny projekt kontraktowy: `*.Shared`. Interfejs module-api, DTO
i integration eventy. I nic więcej — zero agregatów, zero repozytoriów, zero handlerów.

```
Wallets.Shared/ → IWalletsModuleApi.cs
                  DTO/{WalletDto, TransferDto}
                  Events/{WalletAdded, FundsAdded, FundsTransfered, OwnerVerified}
```

### R2 — referencja tylko do `*.Shared`

Cross-module referencja wolno pokazać wyłącznie na obcy `*.Shared`. Nie ma **ani jednej**
referencji na obce `Core`, `Application` czy `Infrastructure`:

```
Wallets.Application.csproj → Users.Shared, Wallets.Core, Wallets.Shared
Notifications.Api.csproj   → Users.Shared, Wallets.Shared, Notifications.Shared
Bootstrapper.csproj        → Users.Api, Wallets.Api, Notifications.Api   (i nic więcej)
```

### R3 — wnętrze jest `internal`

Wszystko poza kontraktem jest `internal sealed`, a dostęp między warstwami tego samego modułu
otwiera jawna lista `InternalsVisibleTo`. **Impl module-api też jest internal** — publiczny jest
tylko interfejs.

```csharp
// Wallets.Core/Extensions.cs
[assembly: InternalsVisibleTo("NPay.Modules.Wallets.Application")]
[assembly: InternalsVisibleTo("NPay.Modules.Wallets.Infrastructure")]

// Wallets.Application/Services/WalletsModuleApi.cs
internal sealed class WalletsModuleApi : IWalletsModuleApi
```

### R4 — dwa tryby, bez trzeciego

Synchronicznie przez `I<X>ModuleApi`, asynchronicznie przez `IMessageBroker` + integration event.
Nic innego nie przechodzi granicy: żadnych wstrzykiwanych obcych serwisów, żadnych joinów po
tabelach obcego modułu.

### R5 — bootstrapper nic nie wie

```csharp
// Startup.ConfigureServices
services.AddNotificationsModule();
services.AddUsersModule();
services.AddWalletsModule();
services.AddSharedFramework(_configuration);
```

> **Co tu robi pracę:** R2 i R3 są egzekwowane przez **kompilator**. Próba sięgnięcia do
> `Wallets.Core.Wallet` z modułu Users to błąd kompilacji, nie code review. To jedyny realny
> mechanizm w tym repo i jednocześnie jedyna rzecz, której Java nie daje za darmo. Reszta wzorca
> jest tylko uporządkowaną konwencją.

---

## 4. Dwa tryby integracji

### 4.1 Synchroniczny: `I<X>ModuleApi`

Interfejs jest celowo **mikroskopijny** — tyle metod, ile ktoś naprawdę woła. To nie „publiczne API
domeny", a lista konkretnych pytań, które inne moduły zadają.

```csharp
public interface IWalletsModuleApi {
    Task<WalletDto> GetWalletAsync(Guid walletId);
}

public interface IUsersModuleApi {
    Task<UserDetailsDto> GetUserAsync(Guid userId);
    Task<UserDetailsDto> GetUserAsync(string email);
}

public interface INotificationsModuleApi {
    Task SendEmailAsync(string receiver, string template);
}
```

Implementacja nie ma własnej logiki — deleguje do **własnego dispatchera**, czyli przechodzi
normalną ścieżką CQRS modułu:

```csharp
internal sealed class WalletsModuleApi : IWalletsModuleApi {
    private readonly IDispatcher _dispatcher;   // nie konkretny handler!

    public Task<WalletDto> GetWalletAsync(Guid walletId)
        => _dispatcher.QueryAsync(new GetWallet { WalletId = walletId });
}
```

Konsument wstrzykuje **tylko interfejs**:

```csharp
internal sealed class AddOwnerHandler : ICommandHandler<AddOwner> {
    private readonly IUsersModuleApi _usersModuleApi;   // obcy kontrakt
    private readonly IOwnerRepository _ownerRepository; // własna domena

    public async Task HandleAsync(AddOwner command, CancellationToken ct = default) {
        var user = await _usersModuleApi.GetUserAsync(command.Email);
        if (user is null) throw new UserNotFoundException(command.Email);
        await _ownerRepository.AddAsync(
            new Owner(user.UserId, user.FullName, user.Nationality, now));
    }
}
```

### 4.2 Asynchroniczny: integration event

Event jest **własnością producenta** i leży w jego `*.Shared`. To ustawia kierunek zależności raz
na zawsze: konsument zależy od kontraktu producenta, nigdy odwrotnie. Nazwa w czasie przeszłym,
record, minimum danych — ale **wystarczające**, żeby konsument nie musiał dopytywać.

```csharp
// Users.Shared/Events — niesie wszystko, czego potrzebują Wallets i Notifications
public record UserCreated(Guid UserId, string Email, string FullName, string Nationality) : IEvent;

// Wallets.Shared/Events
public record WalletAdded(Guid WalletId, Guid OwnerId, string Currency) : IEvent;
```

Handler konsumenta leży w **jego własnym** module, w katalogu `ExternalEvents/`:

```
Wallets.Application/
├── Owners/ExternalEvents/Handlers/UserCreatedHandler.cs    # UserCreated  → utwórz Ownera
├── Owners/ExternalEvents/Handlers/UserVerifiedHandler.cs
└── Wallets/ExternalEvents/Handlers/UserVerifiedHandler.cs  # UserVerified → utwórz Wallet
                                                            #   + publikuj własny WalletAdded
Notifications.Api/Handlers/
├── Users/{UserCreatedHandler, UserVerifiedHandler}.cs
└── Wallets/{WalletAddedHandler, FundsAddedHandler, FundsTransferredHandler}.cs
```

Pod spodem: `IMessageBroker` → `Channel<IEvent>` → `EventDispatcherJob` (BackgroundService) →
`IEventHandler<T>` z kontenera. Moduły widzą wyłącznie `IMessageBroker.PublishAsync`, więc podmiana
in-memory na Kafkę nie dotyka ani jednego handlera.

### 4.3 Reguła wyboru

| Pytanie | Tryb | Przykład z NPay |
|---|---|---|
| Potrzebuję **odpowiedzi, żeby podjąć decyzję** tu i teraz | module-api (sync) | `AddOwnerHandler` pyta Users o usera, zanim utworzy Ownera — bez tego nie wie, czy komenda jest legalna |
| **Reaguję na fakt**, który już się stał | event (async) | `UserVerified` → Wallets zakłada portfel; `WalletAdded` → Notifications wysyła maila |
| Chcę wysłać event, a potem **dopytać producenta o szczegóły** | **nie wolno** | to sync przebrany za async; dlatego `UserCreated` niesie `Email`, `FullName` i `Nationality`, a nie tylko `UserId` |

---

## 5. Dane: replikacja, nie współdzielenie

Najmocniejsza i najmniej oczywista decyzja w NPay. Users ma agregat `User`. Wallets **nie używa**
`Usera` — ma własny agregat `Owner` (`fullName`, `nationality`), budowany z eventu `UserCreated`
i trzymany we własnej tabeli.

- Każdy moduł ma **własny DbContext i własne migracje** (`UsersDbContext`, `WalletsDbContext`) —
  jedna baza, osobne zestawy tabel, **zero FK przez granicę**.
- Globalny `NPay.Shared` ma **zero typów biznesowych**. Nie ma w nim `Money`, nie ma `UserId`.
  `Currency`, `Amount`, `OwnerId` są w `Wallets.Core` i są internal.
- Read-model omija repozytorium agregatu: query handler dostaje osobny port `IWalletStorage`
  (internal interfejs w Application, impl na `DbSet` w Infrastructure, `AsNoTracking`).
- **Wyjątków też się nie reużywa.** Wallets ma własny `Owners/Exceptions/UserNotFoundException`,
  mimo że Users ma klasę o identycznej nazwie. Centralne mapowanie na HTTP robi
  `ExceptionToResponseMapper` po wspólnym `NPayException`.

Widać to też w miejscu, gdzie wzorzec zaboli: `Notifications/Services/EmailResolver` ma
`// TODO: Resolve the user/owner email address from other modules` i tymczasowo generuje
`{ownerId}@npay.io`. Autor nie mógł sięgnąć do tabeli userów — musiałby albo zawołać
`IUsersModuleApi`, albo dołożyć email do eventu. **Kontrakt wymusił świadomą decyzję zamiast
cichego joina.**

---

## 6. Kontrolery i DTO

Kontrolery siedzą w **osobnym projekcie `*.Api`** — drugim i ostatnim publicznym obok `*.Shared`.
Łańcuch to `Api → Infrastructure → Application → Core`, więc kontroler widzi komendy i zapytania
(są `public`), ale nie widzi handlerów ani agregatów (są `internal`).

I rzecz nieoczywista: **kontrolery NPay nie mają własnych DTO.**

```csharp
// Wallets.Api/Controllers/WalletsController.cs
public async Task<ActionResult<WalletDto>> Get(Guid walletId)     // ← DTO z Wallets.Shared
    => Ok(await _dispatcher.QueryAsync(new GetWallet { WalletId = walletId }));

public async Task<IActionResult> Post(AddWallet command)          // ← request body TO KOMENDA
    { await _dispatcher.SendAsync(command); … }

// Wallets.Api/Controllers/FundsController.cs — to samo dwa razy
public async Task<IActionResult> Post(AddFunds command)
public async Task<IActionResult> Post(TransferFunds command)
```

| Warstwa HTTP | Czym jest w NPay | Gdzie leży |
|---|---|---|
| request body | klasa komendy (`public record AddWallet(…) : ICommand`) | `*.Application` |
| response body | DTO kontraktowe (`WalletDto`) | `*.Shared` |
| mapper agregat → DTO | `internal static Extensions.AsDto()` | `*.Application/Queries/Handlers/` |
| kontroler | `public class`, wstrzykuje tylko `IDispatcher` | `*.Api` |

Zero klas `*Json`, zero osobnej warstwy webowej. Users idzie jeszcze dalej: `UserDetailsDto` jest
request **i** response, z `[Required]` / `[EmailAddress]` wprost w DTO kontraktowym, a
`UsersController` wstrzykuje `IUsersService` i pomija dispatcher.

### Decyzja dla Vidulum

NPay łączy DTO webowe z kontraktowym świadomie, ale **dla portfolio rozdzielamy je** — z jawnego
powodu, nie przez analogię:

- `PortfolioDto.PortfolioSummaryJson` ma już zobowiązania wobec frontendu i nazwy JSON-owe,
- trzej konsumenci w procesie (trading, pnl, risk) potrzebują z niego **ułamka** pól,
- jedno DTO sprzęgłoby front z tradingiem bez żadnego zysku.

**Reguła: jedno DTO i HTTP, gdy po drugiej stronie faktycznie jest sieć; zwykły interfejs i własne
DTO kontraktowe, gdy wywołanie jest w procesie.** Dlatego w `user-financial-profile-api` wasze
jedno DTO jest słuszne — tam konsument naprawdę jest klientem HTTP.

---

## 7. Mapowanie C# → Java/Maven

| NPay (.NET) | Odpowiednik w Vidulum | Uwaga |
|---|---|---|
| `*.Shared` projekt | osobny moduł Maven `vidulum-<x>-api` | macie już: `vidulum-cashflow/user-financial-profile-api` |
| `internal` + `InternalsVisibleTo` | **granica artefaktu Maven** + `package-private` w obrębie pakietu + ArchUnit | reactor wykrywa cykl jako błąd builda — ten sam efekt co kompilator C# |
| `I<X>ModuleApi` | interfejs w module `-api`, impl `@Component` package-private w module właściwym | dziś rolę grają `PortfolioRestClient` / `TradingRestClient` / `QuoteRestClient` |
| `IDispatcher` w impl api | `CommandGateway` / `QueryGateway` | klucz: api wstrzykuje **gateway**, nigdy konkretnego handlera |
| `IMessageBroker` + in-memory channel | Kafka + `EventEmitter` + `@KafkaListener` | wasza wersja jest mocniejsza — macie trwałość |
| `*.Shared/Events` | **u was wszystko w `shared-kernel/common/events`** | do rozdania producentom (P4) |
| `AddXModule()` extension | `@AutoConfiguration` / `@Configuration` per moduł | wzorzec już macie: `OkxQuotationConfiguration` |
| osobny `DbContext` per moduł | osobne kolekcje + `DataCleaner` per moduł | macie, z jednym wyciekiem (P3) |
| `ExternalEvents/Handlers/` | `<modul>/app/listeners/` | nazewnictwo inne, układ ten sam |

### Model mentalny: `internal` = artefakt, nie pakiet

**`internal` w C# to widoczność w obrębie assembly, czyli w Javie widoczność w obrębie artefaktu
Maven.** Nie ma sensu walczyć o `package-private` *wewnątrz* `vidulum-portfolio` — `Portfolio` musi
być `public`, żeby handler z innego pakietu go użył, i to jest w porządku: **ścianą jest jar, nie
modyfikator**. `package-private` zostawiamy tam, gdzie klasy siedzą w jednym pakiecie: impl obok
interfejsu, mapper obok kontrolera.

---

## 8. Composition root: `Startup.cs` kontra `VidulumApplication`

```csharp
ConfigureServices:  AddNotificationsModule();  AddUsersModule();  AddWalletsModule();
                    AddSharedFramework(_configuration);
Configure:          UseSharedFramework();  UseNotificationsModule(); UseUsersModule();
                    UseWalletsModule();  UseEndpoints(e => e.MapControllers());
```

Dziewięć linii treści, a cała wartość w tym, czego **nie** zawiera:

1. **Kolejność jest nieistotna** — moduły rejestrują się przed frameworkiem, bo DI rozwiązuje
   leniwie. Jeśli composition root wymaga kolejności, moduły nie są rozłączne.
2. **`Use*Module()` dla Users i Wallets są puste** — szew na middleware per moduł zostawiony przed
   tym, zanim był potrzebny. U was odpowiednikiem byłby per-modułowy `WebMvcConfigurer` /
   `SecurityFilterChain`; dziś macie jedną globalną konfigurację bezpieczeństwa w shared-kernel.
3. **`MapControllers()` jeden na całość** — kontrolery z wszystkich modułów są wykrywane, bo
   projekty `*.Api` są referowane. Odpowiednik waszego component scanu.

### Różnice po stronie Vidulum

- `@SpringBootApplication` w `com.multi.vidulum` **skanuje wszystko** pod tym pakietem na
  classpath → rejestracja beanów jest niejawna. NPay-owa jawność to `@AutoConfiguration` +
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` per moduł.
- `VidulumApplication` buduje `CommandGateway` z `List<CommandHandler<?,?>>` — **jeden gateway dla
  wszystkich modułów**. To jest mechanizm, którym dzieje się przekroczenie granicy w sekcji 14:
  `TradeCapturedEventListener` wysyła `ProcessTradeCommand` przez globalny gateway. Podział na
  artefakty zamyka to **nie** przez zmianę gatewaya, a przez usunięcie klasy komendy z classpath
  tradingu.
- `VidulumApplication` robi dziś rzeczy, których bootstrapper NPay nie robi: rejestruje konta
  Admin/Manager w `CommandLineRunner` i hostuje `PortfolioRestClientImpl`.

> **Test, czy app jest już tylko composition rootem:** czy da się z niego usunąć wszystko poza
> `@Bean`-ami i `main()`.

---

## 9. Vidulum: co już działa jak w NPay

### `vidulum-okx` to wzorcowy plugin

Cztery pliki. Implementuje `ExchangeAdapter` (SPI z `vidulum-exchange`) i rozszerza
`BrokerQuotationProvider` (SPI z `vidulum-wealth`). Nikt nie zależy od okx poza bootstrapem.
`ExchangeAdapter` jest celowo malutki (`broker()` + `validateRegion()`), zamiast próbować
generalizować całą giełdę.

### `user-financial-profile-api` — moduł kontraktowy już istnieje

Osobny artefakt Maven z interfejsem `@HttpExchange` i sześcioma DTO, bez implementacji.
Implementowany przez kontroler po stronie serwera, zamieniany w klienta HTTP przez
`HttpServiceProxyFactory` po stronie konsumenta.

```
impl serwera: user_financial_profile/app/UserFinancialProfileRestController
klient:       bank_data_ingestion/infrastructure/CashFlowServiceClientConfig
              → OwnedAccountRegistry, StageTransactionsCommandHandler
```

To jest R1 + R2. Różnica: dla modułów w jednym procesie (portfolio ↔ trading) interfejs ma być
zwykłym javowym interfejsem, nie `@HttpExchange` — HTTP między portfolio a tradingiem to koszt bez
zysku.

### Listenery Kafki = `ExternalEvents/Handlers`

`portfolio/app/listeners/OrderFilledEventListener` odbiera event i zamienia go na własną komendę
przez `CommandGateway` — nie dotyka cudzego agregatu. Jeden do jednego
`Wallets/ExternalEvents/Handlers/UserVerifiedHandler`.

---

## 10. Vidulum: sześć realnych problemów

### P1 — implementacja kontraktu portfolio leży w `vidulum-app` i wstrzykuje konkretne handlery

`PortfolioRestClient` to w zamyśle module-api portfolio. Jego jedyna produkcyjna implementacja
siedzi w module aplikacyjnym, w pakiecie `user.infrastructure`:

```
vidulum-app/.../user/infrastructure/PortfolioRestClientImpl.java
  private final CreateEmptyPortfolioCommandHandler ...;
  private final LockAssetCommandHandler ...;
  private final UnlockAssetCommandHandler ...;
  private final GetAggregatedPortfolioQueryHandler ...;
  private final GetPortfolioQueryHandler ...;
  private final PortfolioSummaryMapper ...;
```

Skutki: handlery portfolio są de facto publicznym API, a trading/pnl/risk nie uruchomią się bez
całego `vidulum-app`. Dodatkowo interfejs leży w `portfolio.domain.portfolio`, a zwraca
`PortfolioDto.PortfolioSummaryJson` z `portfolio.app` — typ domenowy oddaje DTO warstwy webowej,
więc zmiana JSON-a w REST zmienia kontrakt dla tradingu, pnl i risk.

### P2 — nie ma kierunku zależności, są cykle

```
liczba importów cross-package w vidulum-wealth/src/main:
  portfolio       → trading 13  · quotation 2
  trading         → portfolio 11 · pnl 1 · portfolio_spec 1
  pnl             → portfolio 6  · quotation 4 · trading 1
  risk_management → portfolio 5  · trading 1
  quotation       → portfolio 1    (AssetBasicInfo)
  portfolio_spec  → portfolio 13 · exchange_connection 10
```

W NPay każdy z tych cykli byłby błędem kompilacji. Najtańszy do naprawy: `AssetBasicInfo` leży w
`portfolio/domain/`, a używają go `QuotationService`, `BrokerQuotationProvider` i
`QuoteRestController` — przeniesienie **jednej klasy** zrywa całą zależność quotation → portfolio.

Odwrócenie przez moduły `-api`: `trading → portfolio-api`, `portfolio → trading-api`, a oba `-api`
zależą tylko od shared-kernel. Cykl znika bez łamania żadnej funkcji.

### P3 — wyciek infrastruktury przez granice

```
vidulum-wealth/.../trading/infrastructure/WealthDataCleaner.java
  → portfolio.infrastructure.portfolio.entities.PortfolioEntity
  → pnl.infrastructure.entities.PnlHistoryEntity
  → portfolio_spec.infrastructure.PortfolioSpecEntity
```

Klasa w `trading` zna typy persystencji trzech innych poddomen. To zadanie composition-rootu.
`VidulumApplication` i tak zbiera `List<DataCleaner>` — mechanizm istnieje, trzeba go użyć per
moduł.

### P4 — integration eventy leżą w shared-kernel, nie u producenta

```
shared-kernel/common/events/ → OrderFilledEvent · TradeCapturedEvent · TradeAppliedToPortfolioEvent
                               UserCreatedEvent · CashFlowUnifiedEvent
                               BankDataIngestionUnifiedEvent · UserFinancialProfileUnifiedEvent
```

Każda zmiana kontraktu któregokolwiek z nich to zmiana shared-kernel — a CLAUDE.md mówi, co to
oznacza: *„vidulum-shared-kernel → pełny `./mvnw clean test` — wszystko od niego zależy, bez
wyjątków"*, czyli ~12 minut przy zmianie jednego pola w jednym evencie.

Docelowo: `OrderFilledEvent`, `TradeCapturedEvent` → `vidulum-trading-api`;
`TradeAppliedToPortfolioEvent` → `vidulum-portfolio-api`; `UserCreatedEvent` → `vidulum-user-api`.

### P5 — gruby shared kernel

45 typów w `common`. Obok uczciwie wspólnych `Money`, `Currency`, `UserId` leżą `CashFlowId`,
`BankAccountId`, `BankName`, `CategoryName` (cashflow) oraz `PortfolioId`, `Broker`, `Ticker`,
`OrderStatus`, `StoredTrade`, `PortfolioStatus`, `CostBasis` (wealth). Dla porównania
`NPay.Shared` ma **zero** typów biznesowych.

Efekt docelowy: zmiana `CashFlowId` przestaje przebudowywać wealth, a zmiana `OrderStatus`
przestaje przebudowywać cashflow.

### P6 — nic nie pilnuje granic

Zero wystąpień ArchUnit / ArchRule w repo. Wszystkie reguły z sekcji 3 są dziś konwencją w głowie,
a w NPay są błędem kompilacji.

---

## 11. Docelowy graf modułów

```
vidulum-shared-kernel        CQRS, DDD base, auth, error, Money/Currency — i nic więcej
    │
    ├── vidulum-portfolio-api      PortfolioModuleApi + DTO + TradeAppliedToPortfolioEvent
    ├── vidulum-trading-api        TradingModuleApi + DTO + TradeConfirmedEvent
    ├── vidulum-quotation-api      QuotationApi + AssetBasicInfo + BrokerQuotationProvider (SPI)
    ├── vidulum-exchange-api       ExchangeConnectionApi + ExchangeAdapter (SPI)
    ├── vidulum-user-api           UserModuleApi + UserCreatedEvent
    │
    ├── vidulum-portfolio      → portfolio-api, quotation-api
    ├── vidulum-trading        → trading-api, portfolio-api, quotation-api
    ├── vidulum-quotation      → quotation-api
    ├── vidulum-exchange       → exchange-api
    ├── vidulum-pnl            → portfolio-api, trading-api, quotation-api
    ├── vidulum-risk           → portfolio-api, trading-api, quotation-api
    ├── vidulum-portfolio-spec → portfolio-api, exchange-api, quotation-api
    ├── vidulum-okx            → exchange-api, quotation-api
    │
    └── vidulum-app            tylko @Configuration + Mongo/Kafka/security.
                               ZERO logiki, ZERO *RestClientImpl
```

> **Test, czy się udało:** `vidulum-pnl` i `vidulum-risk` to czyści konsumenci — **nic** nie
> powinno od nich zależeć. Jeśli po przebudowie coś importuje `pnl` (dziś robi to `trading`, przez
> `PnlHistoryEntity`), granica jest w złym miejscu.

Rozbicie `vidulum-wealth` na siedem artefaktów jest celem, nie pierwszym krokiem. Sam podział na
`-api` / impl daje 80% zysku już wtedy, gdy reszta zostaje w jednym module.

### Reaktor Maven i blast radius

**Blast radius** modułu = on sam plus wszystko, co od niego zależy tranzytywnie — czyli to, co
trzeba przebudować i przetestować po jego zmianie. Liczby poniżej policzone z dzisiejszych
`pom.xml`, czasy z tabeli w CLAUDE.md.

```
                           REAKTOR DZIŚ — 8 modułów

┌────────────────────────────────────┐   ┌──────────────────────────────────────┐
│ vidulum-shared-kernel              │   │ vidulum-user-financial-profile-api   │
│ 7/8 modułów · pełny build ~12 min  │   │ 3/8 · ~10 min                        │
│ zależą od niego wszystkie          │   │ jedyny moduł bez shared-kernel       │
│ poza ufp-api                       │   └───────────────┬──────────────────────┘
└────────────────────────────────────┘                   │
                                                         │
┌──────────────────────┐              ┌──────────────────┼──────────┐
│ recurring-rules      │              │ vidulum-exchange │          │
│ 3/8 · ~10 min        │              │ 4/8 · ~3 min     │          │
└──────────┬───────────┘              └──────────┬───────┘          │
           │                                     │                  │
           ▼                                     ▼                  │
┌──────────────────────────────┐      ┌──────────────────────┐      │
│ vidulum-cashflow             │◀─────┤ vidulum-wealth       │      │
│ 2/8 · ~10 min — 723 testy    │      │ 3/8 · ~2,5 min       │      │
└──────────────┬───────────────┘      │ 23 testy             │      │
               │          (ufp-api ───┴──────────┬───────────┘──────┘
               │           → cashflow)           │
               │                                 ▼
               │                      ┌──────────────────────┐
               │                      │ vidulum-okx          │
               │                      │ 2/8 · ~2 min         │
               │                      └──────────┬───────────┘
               ▼                                 │
┌──────────────────────────────┐◀────────────────┘
│ vidulum-app   1/8 · ~2 min   │
└──────────────────────────────┘

strzałka = „jest zależnością"; krawędzie od shared-kernel i do app pominięte
```

**Punkt do zauważenia: liczba modułów i minuty nie korelują.** `cashflow` dotyka dwóch modułów i
kosztuje ~10 min; `exchange` dotyka czterech i kosztuje ~3 min. Blast radius trzeba mierzyć w
minutach, nie w węzłach.

```
                      REAKTOR DOCELOWY — 17 modułów

┌───────────────────────────────────────────────────────────────────────────┐
│ vidulum-shared-kernel      16/17 · po P5 tylko framework                  │
└───────────────────────────────────────────────────────────────────────────┘

KONTRAKTY — konsumenci kompilują się przeciw tym
┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────┐
│ portfolio-api│ │ trading-api  │ │ quotation-api│ │ exchange-api │ │ user-api │
│ 7/17         │ │ 5/17         │ │ 9/17         │ │ 5/17         │ │ 3/17     │
└──────▲───────┘ └──────▲───────┘ └──────▲───────┘ └──────▲───────┘ └──────────┘
       │     ▲          │                │                │
       │     └──────────┼── trading → portfolio-api: CYKL ODWRÓCONY
       │                │                │                │
IMPLEMENTACJE — nikt ich nie importuje   │                │
┌──────┴───────┐ ┌──────┴───────┐ ┌──────┴───────┐ ┌──────┴───────┐ ┌──────────────┐
│ portfolio    │ │ trading      │ │ quotation    │ │ exchange     │ │ cashflow     │
│ 2/17         │ │ 2/17         │ │ 2/17         │ │ 2/17         │ │ recurring-r. │
└──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘ │ ufp-api      │
                                                                    │ 2–3/17       │
CZYŚCI KONSUMENCI — nic od nich nie zależy                          │ ~10 min      │
┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ │ bez zmian    │
│ pnl   2/17   │ │ risk  2/17   │ │ p-spec 2/17  │ │ okx   2/17   │ └──────────────┘
└──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘

┌───────────────────────────────────────────────────────────────────────────┐
│ vidulum-app   1/17 · zależy od wszystkich implementacji,                  │
│                      od żadnego kontraktu wprost                          │
└───────────────────────────────────────────────────────────────────────────┘
```

Rola wiersza mówi wszystko o krawędziach: konsumenci z dolnego wiersza kompilują się przeciw
górnemu i **nie mają na classpath ani jednej implementacji**. Dlatego **blast radius każdej
implementacji jest równy 2** — ona sama i `vidulum-app`.

| Moduł | Dziś | Docelowo | Co to zmienia |
|---|---|---|---|
| `shared-kernel` | 7 / 8 · ~12 min | 16 / 17 · ~12 min | nic — i dlatego ma zostać wyłącznie framework (P5) |
| `cashflow` | 2 / 8 · ~10 min | bez zmian | poza zakresem; tu minuty bolą najbardziej i to osobne zadanie |
| `ufp-api`, `recurring-rules` | 3 / 8 · ~10 min | bez zmian | kontrakt cashflow jest tani w węzłach, drogi w minutach |
| `exchange` | 4 / 8 · ~3 min | `exchange-api` 5 / 17, `exchange` 2 / 17 | zmiana wnętrza przestaje ruszać wealth i okx |
| `wealth` | 3 / 8 · ~2,5 min | 7 artefaktów, każda impl 2 / 17 | **zysk informacyjny, nie czasowy** |
| `okx` | 2 / 8 · ~2 min | 2 / 17 | nic — już dziś jest czystym konsumentem |
| `app` | 1 / 8 · ~2 min | 1 / 17 | nic — liść grafu z definicji |

> **Uczciwie o zysku w wealth: on nie jest w minutach.** Dzisiejszy `vidulum-wealth` ma **23 testy
> i 11 sekund** — przy takim pakiecie podział na siedem artefaktów nie skróci niczego odczuwalnie.
> Zysk jest w tym, że **liczba modułów w buildzie zaczyna nieść informację**: ruszyłem jedną
> implementację (2) czy zmieniłem kontrakt, który widzi pięciu konsumentów (7)? Dziś obie zmiany
> kosztują dokładnie tyle samo i nie da się ich rozróżnić z logu builda.
>
> I druga rzecz, którą te liczby mówią przy okazji: **wealth ma 23 testy, cashflow 723** — przy
> porównywalnej złożoności domeny. Blast radius wealth jest dziś tani nie dlatego, że moduł jest
> czysty, a dlatego, że pokrycie jest cienkie.

---

## 12. Szkic kontraktu portfolio

```java
// === vidulum-portfolio-api ================================================
// Pakiet: com.multi.vidulum.portfolio.api — jedyna publiczna powierzchnia modułu.
// Nazwa bez „RestClient": to nie jest HTTP i nigdy nie było.

public interface PortfolioModuleApi {

    // --- zapis: tyle metod, ile ktoś realnie woła ---
    PortfolioId open(OpenPortfolioRequest request);
    void lock(PortfolioId id, Ticker ticker, OrderId orderId, Quantity quantity);
    void unlock(PortfolioId id, Ticker ticker, OrderId orderId, Quantity quantity);

    // --- czytanie: wycenione w walucie, o którą pyta wołający (B4/C17) ---
    PortfolioView get(PortfolioId id);                       // w walucie portfela
    PortfolioView get(PortfolioId id, Currency valuedIn);
    AggregatedPortfolioView aggregate(UserId owner, Currency valuedIn);
}

// Kto pyta o własność portfela (dziś: PortfolioAccess, komponent portfolio
// wstrzykiwany przez cztery kontrolery z trzech modułów).
public interface PortfolioOwnership {
    /** Zwraca id portfela albo rzuca PortfolioNotFoundException — także dla
        cudzego portfela, bo wołający nie ma prawa się dowiedzieć, że istnieje. */
    PortfolioId requireOwned(String portfolioId);
    UserId currentUser();
}

// DTO kontraktowe — płaskie recordy, własne, NIE PortfolioDto.*Json z warstwy web.
public record PortfolioView(
        PortfolioId portfolioId, UserId owner, Broker broker,
        Currency valuationCurrency, Money value, Money investedBalance,
        List<PositionView> positions) {}

public record PositionView(
        Ticker ticker, SubName subName, Quantity quantity,
        Money value, boolean awaitingCost) {}

// === vidulum-portfolio (implementacja) ====================================
// package-private: odpowiednik `internal sealed class WalletsModuleApi`.

@Component
@AllArgsConstructor
class PortfolioModuleApiImpl implements PortfolioModuleApi {

    private final CommandGateway commandGateway;   // ⟵ gateway,
    private final QueryGateway queryGateway;       //    NIE konkretne handlery

    @Override
    public PortfolioView get(PortfolioId id, Currency valuedIn) {
        Portfolio portfolio = queryGateway.send(new GetPortfolioQuery(id));
        return PortfolioViewMapper.map(portfolio, valuedIn);
    }
}
```

### Dlaczego gateway, a nie handler

To jedyna linijka, która odróżnia dzisiejszy `PortfolioRestClientImpl` od wzorca NPay — i decyduje
o wszystkim. Wstrzyknięty handler to zależność od **środka** modułu; gateway to zależność od jego
**ścieżki wejściowej**. Przy gatewayu implementację api można przenieść do modułu portfolio, bo nie
potrzebuje niczego spoza niego; przy handlerach musi zostać tam, gdzie widać wszystkie bean
definitions — czyli w `vidulum-app`.

### Układ pakietów

```
vidulum-portfolio-api/   com.multi.vidulum.portfolio.api
   PortfolioModuleApi, PortfolioOwnership            (interfejsy)
   PortfolioView, PositionView, OpenPortfolioRequest
   TradeAppliedToPortfolioEvent
   PortfolioNotFoundException, AssetNotFoundException, NotSufficientBalance

vidulum-portfolio/       com.multi.vidulum.portfolio.rest          ← KONTROLERY TU
   PortfolioRestController        @RestController, może być package-private
   PortfolioDto                   kształt JSON-a — package-private, obok kontrolera
   PortfolioJsonMapper            Portfolio → PortfolioDto.*Json
                         com.multi.vidulum.portfolio.app
   commands/, queries/            komendy public — kontroler je widzi
   *CommandHandler                nie wychodzą z artefaktu
   PortfolioModuleApiImpl         wstrzykuje gateway, nie handlery
   PortfolioAutoConfiguration     ← odpowiednik AddPortfolioModule()
                         com.multi.vidulum.portfolio.domain / .infrastructure
   Portfolio, Asset, Mongo        nieosiągalne z zewnątrz — nie ma ich na classpath
```

### Dwa wejścia do modułu — i dlaczego żadne nie jest HTTP

**`vidulum-portfolio-api` to zwykły javowy interfejs w jarze.** Trading wstrzykuje go Springiem i
woła metodę: ta sama JVM, ten sam wątek, ta sama transakcja, zero serializacji, zero gniazda.
Kontroler REST to **drugie, równoległe wejście** — to, którym wchodzi świat zewnętrzny, nie inne
moduły.

```
  świat zewnętrzny (przeglądarka, curl, frontend)
        │  HTTP
        ▼
  ┌──────────────────────────────────────────────────────────┐
  │ vidulum-portfolio                     (jar — nikt go nie │
  │                                        importuje)        │
  │   PortfolioRestController ──┐                            │
  │   + PortfolioDto            │                            │
  │                             ├──▶ CommandGateway /        │
  │   PortfolioModuleApiImpl ───┘    QueryGateway             │
  │   (package-private)                   │                  │
  │         ▲                             ▼                  │
  │         │                   *CommandHandler              │
  │         │                   *QueryHandler                │
  │         │                             │                  │
  │         │                             ▼                  │
  │         │                   Portfolio (agregat) → Mongo  │
  └─────────┼────────────────────────────────────────────────┘
            │ implementuje
  ┌─────────┴───────────────────┐
  │ vidulum-portfolio-api (jar) │   interface PortfolioModuleApi
  └─────────▲───────────────────┘
            │ wywołanie metody w tej samej JVM
  ┌─────────┴───────────────────┐
  │ vidulum-trading             │   @Autowired PortfolioModuleApi api;
  └─────────────────────────────┘

✕ czego na tej drodze nie ma: żądania HTTP, serializacji JSON, RestClienta, timeoutu, retry
```

**Uwaga na nazwę:** dzisiejszy `PortfolioRestClient` kłamie. Jego implementacja wstrzykuje
handlery i woła je bezpośrednio — **już dziś nie ma tam ani jednego żądania HTTP**. Nazwa sugeruje
transport, którego nigdy nie było, i to jest właściwy powód, żeby interfejs nazywał się
`PortfolioModuleApi`.

Ten sam wzorzec pakowania, dwa różne transporty:

| | `user-financial-profile-api` | `portfolio-api` (propozycja) |
|---|---|---|
| interfejs | `@HttpExchange("/api/v1/...")` | zwykły interfejs, zero adnotacji |
| co dostaje konsument | proxy z `HttpServiceProxyFactory` | bean z kontenera |
| co się dzieje przy wywołaniu | żądanie HTTP, serializacja JSON | wywołanie metody |
| po co tak | przewidziane rozdzielenie na usługi | monolit modularny |

### Classpath kompilacji ≠ classpath runtime

- **kompilacja tradingu** — widzi tylko interfejs; próba dotknięcia `Portfolio` to błąd kompilacji
- **runtime produkcyjny** — `vidulum-app` wciąga oba jary, Spring wstrzykuje `PortfolioModuleApiImpl`
- **test komponentowy tradingu** — podstawia dwudziestolinijkowy stub `PortfolioModuleApi` i nie
  potrzebuje ani Mongo, ani portfolio; dziś potrzebuje, bo jedyna implementacja żyje w `vidulum-app`
  (i dlatego istnieją trzy ręcznie utrzymywane stuby: `TestPortfolioRestClient`,
  `ComponentPortfolioRestClient`, impl produkcyjny)
- **zapomniana implementacja** — `NoSuchBeanDefinitionException` przy starcie kontekstu, nie 500 w produkcji

Skoro interfejs jest cienki, **transport staje się decyzją odwracalną**: gdyby portfolio poszło
kiedyś jako osobny deployment, podmienia się to, co stoi za `PortfolioModuleApi` (proxy HTTP
zamiast lokalnego beana) i żaden konsument się nie zmienia.

---

## 13. Najtańszy przykład: `quotation`

24 pliki w module, a cały świat zewnętrzny używa z niego **pięciu rzeczy**.

### Co dziś wychodzi na zewnątrz — pełna lista, nie próbka

```
QuotationService        1× → portfolio/infrastructure/QuoteRestClientAdapter
QuoteNotFoundException  2× → portfolio
fx.FxRate               2× → pnl
fx.FxRates              1× → pnl
fx.RateSourceId         1× → pnl

i jedna zależność w złą stronę:
quotation → portfolio.domain.AssetBasicInfo
   w QuotationService, BrokerQuotationProvider, QuoteRestController
```

### Podział na dwa jary

```
vidulum-quotation-api/          ← 9 typów, zero Springa, zero Kafki
  com.multi.vidulum.quotation.api
     QuotationApi                  interface
     AssetBasicInfo                ← PRZENIESIONE z portfolio.domain
     QuoteNotFoundException
     BrokerNotFoundException
     FxRate, RateSourceId          ← tylko to, czego potrzebuje pnl
     FxRateLookup                  ← czytanie kursów; FxRates (magazyn stanu) zostaje w impl
     BrokerQuotationProvider       SPI — klasa bazowa providerów, metody public final
     PriceChangedEvent             ← musi jechać: jest w sygnaturze SPI

vidulum-quotation/              ← 17 plików, nikt tego nie importuje
     QuotationService implements QuotationApi
       PIVOT · fromFixing() · chainThroughPivot()   cała logika tras
     FxRates, FxRateSource, FxRateRefresher
     NbpApi, NbpFxRateSource                        integracja z NBP
     Binance / PM / Degiro / Exante QuotationProvider
     QuoteRestController, ExchangeStatusRestController
     QuotationDto, ExchangeStatusDto, Reachability
     FxConfiguration, QuotationKafkaConfig
```

```java
// vidulum-quotation-api
public interface QuotationApi {
    AssetPriceMetadata fetch(Broker broker, Symbol symbol);           // rzuca QuoteNotFound
    Optional<AssetPriceMetadata> find(Broker broker, Symbol symbol);  // brak ceny to odpowiedź
    AssetBasicInfo fetchBasicInfoAboutAsset(Broker broker, Ticker ticker);
    void registerAssetBasicInfo(Broker broker, AssetBasicInfo info);
    boolean isRegistered(Broker broker);
    Set<Symbol> quotedSymbols(Broker broker);
}
```

To jest dosłownie dzisiejsze publiczne API `QuotationService` minus `registerBroker`,
`onPriceChange` i `clearCaches` — bo te trzy są wołane tylko z wewnątrz modułu (odpowiednio z
`KafkaTopicConfig`, z Kafki i z testów).

### Siedem krawędzi zależności

```
vidulum-quotation-api  → shared-kernel

vidulum-quotation      → quotation-api, spring-web, spring-kafka
vidulum-portfolio      → quotation-api          wycena pozycji
vidulum-pnl            → quotation-api          FxRate, RateSourceId
vidulum-portfolio-spec → quotation-api          wycena snapshotu
vidulum-okx            → quotation-api          SPI — rozszerza BrokerQuotationProvider
vidulum-app            → vidulum-quotation      jedyny, kto wciąga implementację

            ┌──────────────────────────┐
            │  vidulum-quotation-api   │  ← 5 modułów kompiluje się przeciw
            └──────────────────────────┘
              ▲    ▲    ▲    ▲      ▲
              │    │    │    │      │ implements / extends
    portfolio ┘    │    │    │      │
          pnl ─────┘    │    │   ┌──┴──────────────────┐
  portfolio-spec ───────┘    │   │  vidulum-quotation  │ ← tylko app
                        okx ─┘   │  (NBP, cache, REST) │
                                 └─────────────────────┘

✕ quotation → portfolio          znika — AssetBasicInfo przeniesione
✕ portfolio → QuoteRestClient    znika — patrz ① niżej
```

### ① `QuoteRestClient` i jego adapter znikają — minus dwa pliki

Dziś portfolio ma własny port `QuoteRestClient` (trzy metody) i `QuoteRestClientAdapter`, który
jest czystym przekazaniem:

```
portfolio/infrastructure/QuoteRestClientAdapter
  fetch(broker, symbol)                 → quotationService.fetch(broker, symbol)
  fetchBasicInfoAboutAsset(broker, tic) → quotationService.fetchBasicInfoAboutAsset(…)
  registerBasicInfoAboutAsset(…)        → quotationService.registerAssetBasicInfo(…)
```

Port po stronie konsumenta istnieje dlatego, że **nie ma kontraktu po stronie dostawcy**. Gdy
`QuotationApi` leży w `-api`, portfolio wstrzykuje go wprost, a obie klasy są do usunięcia.
NPay nie ma w Wallets portu `IUsersClient` — ma wstrzyknięty `IUsersModuleApi` z `Users.Shared`.

### ② Pułapka SPI — i to, że podział ją naprawia

`BrokerQuotationProvider` ma trzy metody **package-private**: `onPriceChange`, `fetch`, `find`.
Woła je `QuotationService`, bo siedzi w tym samym pakiecie `quotation.domain`. Jest to celowe —
komentarz w `OkxBrokerQuotationProvider` mówi wprost: *„those members are package-private […], so
no provider outside that package can override them"*.

Po przeniesieniu klasy bazowej do osobnego jara sztuczka przestaje działać: `QuotationService` jest
w innym pakiecie. Rozwiązanie jest **mocniejsze od dzisiejszego**:

```java
// vidulum-quotation-api
public abstract class BrokerQuotationProvider {
    public final Optional<AssetPriceMetadata> find(Symbol symbol) { … }
    public final AssetPriceMetadata fetch(Symbol symbol) { … }
    public final void onPriceChange(PriceChangedEvent event) { … }
}
```

`final` daje **dokładnie tę ochronę, którą opisuje komentarz** — żaden provider nie nadpisze — a
`public` pozwala wołać z drugiego jara. Dzisiejsze package-private jest słabsze: provider
umieszczony w pakiecie `quotation.domain` mógłby je nadpisać i nikt by nie zauważył. Ten przypadek
nie jest kosztem podziału, a jego produktem ubocznym: **ochrona przypadkowa zamieniona na jawną**.

### ③ Jeden typ ciągnie drugi

`PriceChangedEvent` jest dziś w całości wewnętrzny: produkuje go `QuoteRestController`
(`pricingKafkaTemplate.send("quotes", …)`), konsumuje `QuotationService.onPriceChange`. Normalnie
zostałby w implementacji — ale jest w sygnaturze metody klasy bazowej SPI, więc **musi pojechać do
`-api` razem z nią**. Takich pociągnięć zawsze jest kilka i one decydują, czy kontrakt zostanie
wąski.

### Co zostaje w środku i nikogo nie interesuje

Trasa przez pivota USD, czytanie fixingu NBP **przed** łańcuchem, podstawianie USDT za USD,
`EUR/EUR = 1` liczone przed cache'em, branie **starszego** z dwóch znaczników czasu,
`FxRateRefresher` z cronem, cztery providery brokerów, dwa kontrolery z DTO — **nic z tego nie
przecieka do kontraktu**. Konsument pyta „ile kosztuje X w Y" i dostaje odpowiedź albo
`Optional.empty()`.

> **Test, czy granica jest w dobrym miejscu: dziewięć typów na zewnątrz, osiemnaście plików decyzji
> w środku.** Gdy proporcja idzie w drugą stronę — jak dziś w portfolio, gdzie na zewnątrz wychodzą
> agregat, fabryka i repozytorium — granica jest narysowana w złym miejscu albo nie istnieje.

---

## 14. Przebieg: trade zmienia balans portfela

### 14.1 Kto co posiada (docelowo)

| Moduł | Agregaty / stan | Mongo | Endpointy | Emituje | Konsumuje | Nie wie o |
|---|---|---|---|---|---|---|
| `trading-api` | kontrakt — zero stanu | — | — | `TradeConfirmedEvent` (klasa) | — | — |
| `trading` | `Order`, `Trade`, `Position` | `orders`, `trades` | `POST /trades`, `/orders`, `GET /trades` | `TradeConfirmedEvent` | — | `Portfolio`, `Asset`, wycena |
| `portfolio-api` | kontrakt | — | — | `TradeAppliedToPortfolioEvent` (klasa) | — | — |
| `portfolio` | `Portfolio` + `Asset`, `Contribution`, `ExchangeFreeze` | `portfolios` | `/portfolio/**`, `/aggregated-portfolio` | `TradeAppliedToPortfolioEvent` | `TradeConfirmedEvent` | `Order`, `Trade`, OKX |
| `quotation` | cache cen per broker | — | `GET /quote` | — | — | `Portfolio`, `Trade`, użytkownik |
| `pnl` | `PnlHistory` | `pnl_history` | `GET /pnl` | — | `TradeAppliedToPortfolioEvent` | agregat `Portfolio`, agregat `Trade` |
| `okx` | — | — | — | notowania do `quotation` | — | wszystko poza SPI |
| `app` | `@Configuration`, Mongo, Kafka, security | — | — | — | — | logiki biznesowej |

> Najmocniejsza linia: **`trading` nie wie o wycenie, a `portfolio` nie wie o zleceniach.** Trade to
> dla portfela „ileś jednostek X wymienione na ileś jednostek Y w danym momencie" — czy stało za tym
> zlecenie na giełdzie, czy zakup u dealera, jest nieistotne. Dziś portfolio musi to wiedzieć, bo
> odbiera `order_filled`.

### 14.2 Przebieg dzisiejszy

```
POST /trades
 │
 01  TradeRestController (trading)
 │     userId z tokenu, nie z payloadu (G2); originTradeId — generowany, gdy brak
 │
 02  access.requireOwned(portfolioId) ───────────▶ PortfolioAccess (portfolio)   ① GRANICA
 │     trading woła komponent portfolio → DomainPortfolioRepository
 │
 03  MakeTradeCommandHandler (trading)
 │     findByOrigin (F1, + DuplicateKeyException przy wyścigu)
 │     symbol/side ze zlecenia, jeśli istnieje — inaczej z żądania
 │     save(Trade) → Mongo
 │
 04  ──▶ Kafka "trade_captured"
 05  ◀── TradeCapturedEventListener (trading)        ← event wraca do tego samego modułu:
 │                                                     to tam jest routing
 06  orderId zdefiniowany?
 │     ├── tak ──▶ 07 FillOrderCommandHandler (trading)
 │     │             order.addExecution() · save
 │     │           08 ──▶ Kafka "order_filled"
 │     │           09 ──▶ OrderFilledEventListener (portfolio) → ProcessTradeCommand
 │     │
 │     └── nie ──▶ 10 ProcessTradeCommand ─────────▶ (portfolio)                ② GRANICA
 │                   trading buduje komendę portfolio i wysyła globalnym gatewayem
 │
 11  ProcessTradeCommandHandler (portfolio)
 │     portfolio.handleExecutedTrade() → PortfolioEvents.TradeProcessedEvent
 │     hasAlreadyCounted(tradeId) (F10) · calculatePurchased/SoldPortion · swing()
 │     repository.save() → Mongo "portfolios"
 │
 12  ⟂ KONIEC — balans zmieniony, NIE WYCHODZI ŻADEN EVENT
```

Dwa odkrycia z czytania kodu:

1. **`TradeAppliedToPortfolioEvent` leży w shared-kernel i nikt go nigdy nie emituje ani nie
   konsumuje.** Jedyne wystąpienie w repo to własna definicja. Ktoś przewidział krok 12 i nie
   dokończył — dziś to martwy kontrakt w najdroższym możliwym miejscu.
2. **Zdarzenia domenowe agregatu idą do `Map<PortfolioId, List<StoredDomainEvent>>` w pamięci**
   (`DomainPortfolioRepositoryImpl`), czyli giną przy restarcie. To nie jest event store, mimo
   nazwy.

### 14.3 Co widzi kompilator po podziale

Podział na artefakty nie jest przenoszeniem plików — jest **listą błędów kompilacji, z których
każdy jest decyzją projektową**. Dla ścieżki trade'u lista ma siedem pozycji:

| Import, który przestaje istnieć | Kto go ma | Decyzja |
|---|---|---|
| `portfolio.app.PortfolioAccess` | `TradeRestController`, `OrderRestController`, `PnlRestController`, `RiskManagementRestController` | interfejs `PortfolioOwnership` → `portfolio-api`; dzisiejsza klasa zostaje jako impl |
| `portfolio.app.commands.update.ProcessTradeCommand` | `TradeCapturedEventListener` | portfolio słucha samo — patrz 14.4 |
| `portfolio.app.PortfolioDto` | trading, pnl, risk | `PortfolioView` / `PositionView` → `portfolio-api` |
| `portfolio.domain.AssetNotFoundException`, `NotSufficientBalance` | trading | wyjątki kontraktowe → `portfolio-api` |
| `trading.domain.{Order, Trade, Position, OpenedPositions}` | `portfolio/infrastructure/TradingRestClientAdapter` oraz **`PortfolioRestController`** (kontroler portfolio zwraca typ domenowy tradingu) | `TradingModuleApi` → `trading-api` + własny typ widoku; adapter znika |
| `portfolio...PortfolioEntity`, `pnl...PnlHistoryEntity` | `trading/infrastructure/WealthDataCleaner` | `DataCleaner` per moduł |
| `portfolio.domain.portfolio.{Portfolio, Asset, Contribution, PortfolioFactory, DomainPortfolioRepository, ExchangeFreeze}` | `portfolio_spec` | **najtrudniejszy przypadek** — portfolio_spec składa agregat ręcznie; musi dostać `PortfolioModuleApi.open(...)` przyjmujące gotową listę pozycji |

Siedem pozycji na całą ścieżkę trade'u. To mało — i to dobra wiadomość o dzisiejszym kodzie:
granice są w większości w porządku, brakuje tylko czegoś, co je utrzyma.

### 14.4 Przebieg docelowy

```
vidulum-trading                           vidulum-portfolio
  Trade + Order                              swing() → balans
  routing fill/manual ZOSTAJE WEWNĄTRZ
        │                                        │
        │ TradeConfirmedEvent                    │ TradeAppliedToPortfolioEvent
        │ (trading-api)                          │ (portfolio-api) ← NOWE
        ▼                                        ▼
   Kafka "trade_confirmed" ──────────▶      Kafka "trade_applied"
                                                 │
                            ┌────────────────────┼────────────────────┐
                            ▼                    ▼                    ▼
                     websocket-gateway      vidulum-risk          vidulum-pnl
                       push do UI           przelicz RAG       własny read-model

  ⟵ wszyscy trzej dziś musieliby odpytać portfolio przez PortfolioRestClient,
     bo nie mają skąd się dowiedzieć, że coś się zmieniło
```

### 14.5 Trzy decyzje, które podział wyciąga na wierzch

**① Kto pyta o własność portfela.** Dziś cztery kontrolery z trzech modułów wstrzykują
`PortfolioAccess` i przez niego czytają repozytorium portfolio. Po podziale zostaje
`PortfolioOwnership` w kontrakcie. Zysk nie jest kosmetyczny: reguła „cudzy portfel odpowiada 404,
nie 403" przestaje być właściwością klasy, którą każdy może obejść, sięgając do repozytorium obok.

**② Kto nalicza trade do portfela.** Dziś dwie drogi dla jednej operacji. Po podziale druga jest
niemożliwa, więc trzeba wybrać:

- *portfolio słucha dwóch tematów* (`trade_captured` dla ręcznych, `order_filled` dla giełdowych) —
  i wtedy **powtarza routing tradingu**: musiałoby samo sprawdzać `OrderId.isDefined(...)`, żeby nie
  naliczyć trade'u dwa razy. **Zły wybór: ta sama decyzja w dwóch modułach.**
- *trading publikuje jeden `TradeConfirmedEvent`* na końcu swojego routingu — po wypełnieniu
  zlecenia albo od razu, gdy zlecenia nie ma. `trade_captured` zostaje tematem wewnętrznym
  tradingu, `order_filled` traci konsumenta spoza modułu. **Portfolio ma jeden listener i przestaje
  wiedzieć, czym jest zlecenie.**

Drugi wariant jest tym, co mówi reguła z sekcji 4.3: event niesie to, czego potrzebuje konsument.
Portfel potrzebuje „co, ile, po jakiej cenie, kiedy, do którego portfela" — `orderId` jest w tym
zestawie przypadkowym gościem.

**③ Kto się dowiaduje o nowym balansie.** Dziś nikt, więc `pnl` i `risk` muszą pytać —
`MakePnlSnapshotCommandHandler` i `RiskManagementEngine` wstrzykują `PortfolioRestClient` i
`TradingRestClient`. Po podziale wystarczy dokończyć to, co ktoś zaczął: przenieść
`TradeAppliedToPortfolioEvent` do `portfolio-api` i emitować po `repository.save(portfolio)`.
Wtedy `pnl` staje się tym, czym w NPay jest `Owner` w module Wallets: **własnym read-modelem
budowanym z eventów**, a nie klientem cudzego agregatu.

---

## 15. Ścieżka czytania: skąd się bierze balans

Zapis zmienia ilości aktywów. **Balans w złotówkach czy dolarach nie jest nigdzie zapisany** —
powstaje przy czytaniu.

```
GET /portfolio/{id}/{currency}
  │
  ├─ portfolio · PortfolioRestController
  │     access.requireOwned(id)                      ← własność
  │     GetPortfolioQuery → agregat z Mongo
  │
  ├─ portfolio · PortfolioSummaryMapper
  │     dla każdego aktywa: ile jednostek × jaka cena
  │     │
  │     └─▶ quotation · QuotationService.fetch(broker, symbol)
  │              ├─ cache per broker
  │              └─ BrokerQuotationProvider ◀── okx publikuje notowania
  │                 (EUR/EUR liczone jako 1, bez pytania giełdy)
  │
  └─ PortfolioSummaryJson: wartość pozycji, wartość portfela,
     per pozycja: subName + awaitingCost
```

Dzisiejsza granica: portfolio woła `QuoteRestClient` (interfejs w `portfolio.domain`, adapter w
`portfolio.infrastructure`) — port po stronie konsumenta, co jest OK. Ale typ zwracany
`AssetBasicInfo` leży też w `portfolio.domain`, więc quotation musi importować portfolio, żeby go
oddać. Jedna klasa i jedna zależność do zerwania (sekcja 13).

> Warto zauważyć, czego tu **nie ma**: `trading` nie występuje w ścieżce czytania balansu ani razu.
> Zlecenia i trade'y są historią operacji, balans jest stanem aktywów — i to dlatego te dwa moduły
> dają się rozciąć mimo 24 wzajemnych importów w dzisiejszym kodzie.

---

## 16. Event czy module-api — tabela decyzji dla Vidulum

| Przepływ | Tryb | Dlaczego |
|---|---|---|
| `PlaceOrderCommandHandler` sprawdza saldo przed przyjęciem zlecenia | **module-api** | bez odpowiedzi nie wie, czy komenda jest legalna; musi to być stan *teraz*, nie z eventu |
| `OrderFilledEvent` → `ProcessTradeCommand` w portfolio | **event** | reakcja na fakt; dziś już tak działa |
| `RiskManagementEngine` liczy RAG / stop-loss | **module-api** (czytanie) | zapytanie na żądanie; dziś bierze `PortfolioDto` + `TradingDto` + trzy porty — powinny być dwa wąskie `-api` |
| `pnl` robi snapshot wyceny | **event** + własny read-model | pnl to historia — powinien budować własny obraz z eventów, nie odpytywać portfolio o przeszłość |
| `portfolio_spec.confirm` tworzy portfel i aktywuje połączenie | **module-api ×2** | potrzebuje wyniku obu operacji; dziś sięga do `PortfolioFactory`, `DomainPortfolioRepository` i `ConfirmExchangeConnectionCommandHandler` |
| okx rejestruje się jako obsługiwana giełda | **SPI** (trzeci tryb, świadomie) | odwrotny kierunek: moduł wstawia *swoją* implementację do cudzego rejestru |

Ostatni wiersz to rozszerzenie wzorca NPay, nie jego naruszenie. NPay ma trzy moduły znane z góry;
Vidulum ma rodzinę giełd, która ma rosnąć. **SPI (adapter + rejestr) to właściwa odpowiedź na
„pluginy", a module-api na „inny moduł czegoś potrzebuje".**

---

## 17. Kolejność migracji

Posortowane rosnąco po ryzyku. Każdy krok jest samodzielnie użyteczny.

| # | Krok | Uwaga |
|---|---|---|
| 01 | **ArchUnit na stanie dzisiejszym** — ✅ **wykonane w kroku 12** | reguły opisujące docelowy graf + jawna lista odstępstw. Osiem reguł w `ModuleBoundariesArchTest` (`vidulum-app`), trzy listy odstępstw, każda tylko do skracania |
| 02 | **`vidulum-quotation-api`** (sekcja 13) — ✅ **VID-199**, w masterze | `AssetBasicInfo` zerwało zależność quotation → portfolio, `QuotationApi` usunęło `QuoteRestClient` i jego adapter. Kontrakt wyszedł na **trzy metody**, nie sześć — patrz sekcja 21 |
| 02b | **`vidulum-quotation` jako artefakt** — ✅ **VID-200**, gałąź | Wymagało najpierw rozbicia `KafkaTopicConfig`; szczegóły w sekcji 21 |
| 03 | **`DataCleaner` per poddomena** — ✅ **wykonane** | rozbija `WealthDataCleaner`, usuwa wyciek encji (P3). Mechanizm zbierania już jest |
| 04 | **`vidulum-portfolio-api` + `PortfolioModuleApi`** — ✅ **wykonane**, patrz 21 | sedno zmiany (P1). Implementacja przeniesiona z `vidulum-app` do portfolio i przepisana na gateway. Konsumenci zmieniają import i typ DTO |
| 04b | **`OpenedPositions` + `Position` → portfolio** — ✅ **wykonane** | Leżą w `trading.domain`, a używa ich wyłącznie portfolio: to jego read-model zapisany u sąsiada. Zdejmuje 5 z 13 importów portfolio → trading i **nie wymaga `trading-api`** |
| 05a | **Eventy wealth** — ✅ **wykonane**, patrz 21 | P4 w zakresie, w którym pracujemy. `OrderFilledEvent` → `trading-api`, `TradeCapturedEvent` → `vidulum-trading` (nie kontrakt), decyzja o `TradeAppliedToPortfolioEvent`, nazwane `groupId`. **Zamyka ostatni wyciek z kroku 04** |
| 05b | **`UserCreatedEvent`** | Producent siedzi w `vidulum-app`, konsumenci w cashflow i wealth. Wymaga `vidulum-user-api` albo wyjścia domeny użytkownika z bootstrapu — decyzja, nie przenoszenie klasy. Przy okazji: payload na **record** |
| 05c | **Trzy `*UnifiedEvent` z cashflow** | Najpierw o kształcie, potem o pakowaniu. Dwa z nich czyta `websocket-gateway`, który jest **osobnym deploymentem**. Przy okazji: payloady na **record** |
| 05d | **Prowizja w koszcie bazowym** — `2026-10-13-fee-in-cost-basis.md` | Decyzja podjęta: **prowizja wchodzi**. Dziś `fee` nie wychodzi z tradingu wcale, więc koszt nabycia jest zaniżony, a zysk zawyżony. Pole w `TradeConfirmedEvent` → `ProcessTradeCommand` → `ExecutedTrade` → `swing()`. Dotyka 23 plików testowych — osobna zmiana |
| 06 | **`TradingModuleApi` — domknięcie `vidulum-trading-api`** — ✅ **wykonane**, patrz 21 | P2. Artefakt powstał w 05a z samymi eventami; tu dochodzą dwie metody czytające i typy widoku, znika `TradingRestClient` z `portfolio.domain` razem z adapterem, a `pnl` i `risk` przestają zależeć od portfolio po kontrakt tradingu |
| 06b | **`vidulum-exchange-api`** — ✅ **wykonane**, patrz 21 | `portfolio_spec` wstrzykuje trzy handlery i repozytorium exchange (13 importów do wnętrza). Ta sama choroba co P1, inny kierunek. Warunek dla 06c, bo spec jedzie razem z portfolio |
| 06c | **`vidulum-portfolio` + `vidulum-trading` jako artefakty** — ✅ **wykonane**, patrz 21 | Wykonane razem (wariant A), bo wydzielenie portfolio w pojedynkę zamyka cykl w reaktorze po stronie testów — patrz 21 |
| 08 | **Cykl beanów na gatewayach** — ✅ **wykonane**, patrz 21 | Nie było w pierwotnym planie: wyszło z pytania, czy cykl z kroku 04 nadal występuje. `@Lazy` w trzech implementacjach i w pięciu miejscach testowych odkładało cykl, nie usuwało go. Gateway przestał zbierać handlery w swoim konstruktorze — cykl zniknął, `@Lazy` zniknęło |
| 09 | **`vidulum-pnl` + `vidulum-risk` jako artefakty, bez `-api`** — ✅ **wykonane**, patrz 21 | Oba są liśćmi: nikt ich o nic nie pyta, więc moduł kontraktowy miałby zero konsumentów. `vidulum-wealth` zostaje bez `src/main` — jako moduł testowy |
| 10 | **Dwa agregatory: `market/` i `wealth/`** — ✅ **wykonane**, patrz 21 | Jedenaście modułów pod dwa foldery; korzeń z 17 wpisów do ośmiu. Agregatory, nie rodzice: ani jedna zależność i ani jeden `artifactId` się nie zmienia |
| 11a | **Trzy zależności infrastrukturalne do korzenia** — ✅ **wykonane**, patrz 21 | `lombok` i dwa startery testowe wyszły z 15 pomów do `<dependencies>` korzenia. Pierwotny pomysł — „`wealth/pom.xml` jako rodzic" — **odrzucony po pomiarze**, patrz 21 |
| 12 | **Markery kontraktów + ArchUnit** — ✅ **wykonane**, patrz 21 | `ModuleApi`, `ModuleSpi`, `ModuleEventHandler`, `IntegrationEvent` w shared-kernel; osiem reguł, które je czytają. Marker bez reguły byłby dekoracją, więc weszły razem |
| 07 | **Odchudzenie shared-kernel** | P5. Najszersze dotknięcie kodu i największy zysk w czasie builda. Ostatnie, bo wymaga istnienia modułów `-api` |

### Właściciele siedmiu eventów — policzone, nie zgadnięte

Jednolinijkowe „eventy do producentów" ukrywało cztery różne zadania. Stan faktyczny na dzień
rozbicia kroku 05:

| event | producent | konsumenci | gdzie trafia | krok |
|---|---|---|---|---|
| `OrderFilledEvent` | trading | **portfolio** | `trading-api` | 05a |
| `TradeCapturedEvent` | trading | **trading** | `vidulum-trading` — **nie kontrakt** | 05a |
| `TradeAppliedToPortfolioEvent` | **nikt** | **nikt** | decyzja: emitować albo usunąć | 05a |
| `UserCreatedEvent` | `vidulum-app` (user) | cashflow + wealth/pnl | wymaga `vidulum-user-api` | 05b |
| `CashFlowUnifiedEvent` | cashflow, **22 handlery** | cashflow ×2 + websocket-gateway | osobna rozmowa | 05c |
| `BankDataIngestionUnifiedEvent` | cashflow | **tylko** websocket-gateway | jw. | 05c |
| `UserFinancialProfileUnifiedEvent` | cashflow | **nikt** | decyzja | 05c |

Cztery rzeczy, których opis kroku 05 nie oddawał:

**`TradeCapturedEvent` nigdy nie przechodzi granicy modułu.** Produkuje go
`MakeTradeCommandHandler`, konsumuje `TradeCapturedEventListener` — oba w tradingu; jedyna wzmianka
w portfolio to javadoc w `Portfolio.java`. Ten event **nie należy do żadnego kontraktu**, ma tylko
wyjść z shared-kernel do implementacji. Jeden z siedmiu rozwiązuje się bez `-api`.

**`websocket-gateway` nie jest w reaktorze.** Własny `pom.xml`, własny `Dockerfile`, zero wystąpień
w głównym pomie — to osobny deployment czytający dwa eventy cashflow z Kafki. Czyli te kontrakty już
dziś przechodzą granicę **procesu**, a nie modułu: wersjonowanie przestaje być kwestią rebuildu, bo
stary gateway i nowy cashflow będą chodzić obok siebie.

**`*UnifiedEvent` to nie sealed interface, a koperta.** `CashFlowUnifiedEvent` ma dwa pola:
`Map<String, Object> metadata` i `JsonContent content`. Typ nie mówi nic o treści — wariant siedzi w
metadanych, payload jest nieotypowanym JSON-em. Pytanie przestaje być „gdzie umieścić tę klasę", a
staje się **„czy to w ogóle jest kontrakt"**, bo kompilator nie sprawdza tu niczego poza tym, że coś
przyszło.

**Dwa eventy są martwe z dwóch różnych stron.** `TradeAppliedToPortfolioEvent` nikt nie emituje —
klasa czeka na krok, którego nie dokończono. `UserFinancialProfileUnifiedEvent` jest emitowany w
pustkę: ma emitter i konfigurację topicu, nie ma konsumenta.

**Dlaczego 05a pierwsze:** mieści się w jednym PR, nie dotyka cashflow ani domeny użytkownika
(scope testów: wealth + okx + app), zamyka wyciek `TradeCapturedEventListener → ProcessTradeCommand`
pozostawiony po kroku 04 i stawia wzorzec `*Handler` z sekcji 21, który 05b i 05c potem kopiują.

### Co blokuje artefakt portfolio

Po kroku 02 quotation miał **zero** wychodzących zależności do innych poddomen wealth i wydzielenie
było mechaniczne. Portfolio po kroku 04 ma **trzynaście** — wszystkie do tradingu:

```
portfolio/domain/TradingRestClient                 ← port na trading, w pakiecie portfolio
portfolio/infrastructure/TradingRestClientAdapter  ← wstrzykuje DWA query handlery tradingu
portfolio/app/queries/GetPositionViewOfPortfolioQueryHandler
portfolio/app/queries/PositionMapper
portfolio/app/PortfolioRestController
```

To ten sam kształt, który krok 02 usunął przy quotation (`QuoteRestClient` + adapter wstrzykujący
`QuotationService`), tylko wycelowany w trading. Krok 04 nie mógł go ruszyć: dotyczył tego, o co
**inni pytają portfolio**, a nie tego, o co **portfolio pyta trading**.

Trzy rzeczy wyszły przy pomiarze:

**`OpenedPositions` i `Position` są w `trading.domain`, a używa ich wyłącznie portfolio.** Trading
sam ich nie dotyka. To read-model portfolio (co trzymam ∩ jakie mam otwarte zlecenia, czyli
`GET /portfolio/opened-positions/{id}`) zapisany w pakiecie domenowym sąsiada. Tanie do naprawy i
niezależne od `trading-api` — stąd krok 04b.

**`TradingRestClient` leży w `portfolio.domain`, a importują go `pnl` i `risk_management`.** Port na
trading zadeklarowany w trzecim module. Po wydzieleniu portfolio te dwa moduły zależałyby od niego
**tylko po to, żeby dostać kontrakt tradingu**. To twarda blokada, nie niedogodność — i dlatego 06c
jest poniżej 06.

**Strona testowa jest szeroka.** `WealthTestApplication` robi
`@Import({PortfolioAppConfig.class, TradingAppConfig.class})`, a szesnaście plików testowych spoza
portfolio sięga do jego wnętrza — w tym cały `portfolio_spec` (9 plików) i dwa testy pnl. Przy 06c
wealth, okx i app wezmą `vidulum-portfolio` w `<scope>test</scope>`, dokładnie jak przy quotation.

Drobiazg do zrobienia razem z 05a: `OrderFilledEventListener` bierze
`containerFactory = "orderFilledContainerFactory"` — bean zdefiniowany w
`trading/infrastructure/TradingKafkaConfig`, przez string. Po stronie kontraktu to konstanta
(propozycja `*Handler` w sekcji 21).

### portfolio_spec jedzie razem z portfolio

`ConfirmPortfolioSpecCommandHandler` (460 linii) nie używa portfela — **pisze jego agregat**:
`portfolioFactory.withAssets(...)`, `portfolioRepository.save(portfolio)`,
`portfolio.applyExchangeFreeze(...)`, `Contribution.opening(...)`, `ExchangeFreeze.spread(...)`.
Sześć z siedmiu importów to wnętrze portfolio, a druga ścieżka czyta istniejący portfel i go
aktualizuje.

Osobny moduł wymagałby w kontrakcie czegoś takiego:

```java
PortfolioId openFromSnapshot(String name, UserId owner, Broker broker, Currency currency,
                             List<Asset> assets, List<Contribution> opening,
                             Map<Ticker, ExchangeFreeze> freezes);
void resynchronise(PortfolioId id, List<Asset> assets, Map<Ticker, ExchangeFreeze> freezes);
```

czyli **agregatu wystawionego przez interfejs** — dokładnie tego, przed czym broni reguła z sekcji
21. Dwa agregaty w jednym module to norma (Wallets w NPay ma `Wallet` i `Owner`), a granicą jest
artefakt, nie agregat. Dodatkowy sygnał: **nikt nie importuje portfolio_spec** poza
`WealthDataCleaner`, czyli to czysty konsument i własnego `-api` nie potrzebuje.

Cena: `vidulum-portfolio` dziś nie zależy od exchange w ogóle, a po dołączeniu spec zacznie — stąd
06b, żeby zależał od kontraktu, a nie od handlerów.

**Docelowo** spec powinien przestać pisać agregat i publikować `PortfolioSpecConfirmedEvent`, na
który portfolio reaguje własną komendą. Blokada nie jest techniczna:
`POST /portfolio-spec/{id}/confirm` zwraca dziś `portfolioId` synchronicznie i frontend na tym
stoi, a ścieżka drugiej synchronizacji oraz odmowa „giełda się ruszyła" zakładają synchroniczność.
To decyzja produktowa, nie porządkowa — patrz pytanie 2 w sekcji 20.

---

## 18. Ściąga: dodaję X, ruszam Y

| Zmiana | Dotykam | Rebuild |
|---|---|---|
| nowa reguła liczenia pozycji w portfelu | `vidulum-portfolio` | 1 moduł + app |
| nowe pole w odpowiedzi `GET /portfolio` | `vidulum-portfolio` (PortfolioDto to jego prywatna sprawa) | 1 moduł + app |
| nowe pole, którego potrzebuje **pnl** | `portfolio-api` + `portfolio` + `pnl` | 3 moduły — i to jest sygnał, nie koszt |
| nowy typ zlecenia (trailing stop) | `vidulum-trading` | 1 moduł + app |
| nowa giełda (Binance) | nowy `vidulum-binance` + `@Configuration` w app | 1 nowy moduł |
| nowe pole w `TradeConfirmedEvent` | `trading-api` → `trading`, `portfolio` | producent + konsumenci eventu |
| nowe pole w `Money` | `shared-kernel` | **wszystko** — i dlatego shared-kernel ma być chudy |

Dziś każdy wiersz ma tę samą odpowiedź: `vidulum-wealth` + `vidulum-okx` + `vidulum-app`, a przy
dotknięciu shared-kernel pełne 12 minut. Różnica w czasie builda jest skutkiem ubocznym; prawdziwy
zysk w tym, że **liczba modułów, które muszę ruszyć, mówi mi, czy zmiana jest lokalna, czy zmieniam
kontrakt** — a tej informacji dzisiejszy układ nie potrafi dać.

---

## 19. Czego z NPay nie kopiować

Repo jest materiałem szkoleniowym i w kilku miejscach to widać.

- **Brak outboxa.** `InMemoryMessageBroker` wrzuca event do `Channel<IEvent>` poza transakcją —
  przy restarcie proces gubi zdarzenia. Z NPay bierzemy **abstrakcję** (`IMessageBroker` jako
  jedyny punkt styku), nie implementację; Kafka jest tu mocniejsza.
- **Brak jakichkolwiek testów.** W całym repo nie ma projektu testowego. Trzy poziomy testów z
  CLAUDE.md są wyraźnie dalej.
- **`OwnerId` z `implicit operator Guid`.** Value object, który sam się rozpakowuje do primitywu —
  traci dokładnie tę ochronę, po którą się go wprowadza. Nasze `PortfolioId`/`UserId` z `of()` są
  lepsze.
- **DTO jako mutowalne klasy z setterami** (`WalletDto`) obok recordów dla eventów. Niespójne; nasze
  DTO z `@AllArgsConstructor` wymuszanym przez wytyczne testowe są lepsze.
- **Trzy moduły to mało.** NPay nie zderza się z problemem, który nas czeka: poddomena (`pnl`,
  `risk`), która potrzebuje danych z trzech innych i nie ma własnych. Odpowiedź z sekcji 5 (własny
  read-model z eventów) jest w NPay tylko zarysowana przez `Owner`.

---

## 20. Pytania otwarte

1. **Kształt `*UnifiedEvent`** (zaktualizowane przy rozbiciu kroku 05). Nie są sealed interface —
   `CashFlowUnifiedEvent` to koperta: `Map<String, Object> metadata` + `JsonContent content`.
   Pytanie nie brzmi więc „komu je przydzielić", a czy zostają kopertą — i wtedy nazwać to wprost,
   bo kontrakt jest fikcją — czy rozchodzą się na otypowane eventy per fakt. Komplikacja:
   `websocket-gateway` czyta dwa z nich jako osobny deployment, więc zmiana kształtu jest zmianą
   kontraktu międzyprocesowego. Patrz krok 05c.
2. **`portfolio_spec` i tworzenie agregatu** — *rozstrzygnięte inaczej, niż zakładało pytanie*.
   Kontrakt **nie** dostaje szerokiego `openFromSnapshot(...)`; spec zostaje w tym samym artefakcie
   co portfolio (krok 06c), bo metoda przyjmująca listę aktywów, kontrybucje i `ExchangeFreeze` to
   agregat wystawiony przez interfejs. Otwarte zostaje tylko jedno: **czy onboarding może być
   asynchroniczny** — bo dopiero wtedy spec mógłby być osobnym modułem publikującym
   `PortfolioSpecConfirmedEvent`. Dziś `confirm` zwraca `portfolioId` synchronicznie i frontend na
   tym stoi. Decyzja produktowa.
3. **Czy `pnl` i `risk` dostają własne `-api`.** Nic od nich nie zależy, więc kontrakt może być
   zbędny. Decyzja: `-api` tylko wtedy, gdy ktoś konsumuje.
4. **Granulacja `DataCleaner`** po rozbiciu wealth na siedem artefaktów — jeden per artefakt czy
   jeden per kolekcja.
5. **`@AutoConfiguration` kontra component scan.** Przejście na jawną rejestrację per moduł jest
   zgodne z NPay, ale dotyka startu całej aplikacji — osobne zadanie, nie część podziału.

---

## 21. Co pokazało wykonanie kroków 02, 02b i 04

Sekcje 1–20 to analiza sprzed implementacji. Ta jest po — i notuje tylko to, czego analiza nie
przewidziała. Trzy rzeczy wyszły dopiero przy kontakcie z kodem, dwie z nich zmieniły projekt.

### Kontrakt jest trzema metodami, nie sześcioma

Analiza przepisała publiczne API `QuotationService` i wyszło sześć metod. Sprawdzenie, kto każdą z
nich **naprawdę** woła, dało trzy:

| metoda | konsument |
|---|---|
| `fetch` | portfolio, risk, portfolio_spec |
| `fetchBasicInfoAboutAsset` | portfolio |
| `registerBasicInfoAboutAsset` | portfolio |
| ~~`find`~~ | tylko `QuotationService` wewnętrznie, przy wyczerpywaniu tras |
| ~~`isRegistered`~~, ~~`quotedSymbols`~~ | tylko `ExchangeStatusRestController`, w tym samym module |

Skutek praktyczny był duży: `QuotationApi` to **przemianowany `QuoteRestClient`** z identyczną
sygnaturą, więc piętnaście testowych atrap zmieniło jedną linijkę zamiast dorabiać po trzy metody.
Przy szóstce ta zmiana byłaby dwa razy większa i w całości w testach.

**Reguła do zapamiętania przy krokach 04 i 06:** kontraktu nie wyprowadza się z publicznego API
klasy, tylko z listy realnych wywołań. `PortfolioRestClient` ma dziś sześć metod — przed
`vidulum-portfolio-api` trzeba je policzyć tak samo.

### `FxRates` nie mogło pojechać do kontraktu

Analiza wpisała `FxRates` na listę typów kontraktowych. Błąd: to **mutowalny magazyn stanu**
(`record()`, `clear()`), a `pnl/infrastructure/FxValuationRates` wstrzykiwał tę klasę konkretną.
Konsument mogący wołać `record()` przestawiłby kurs, przeciw któremu wszyscy inni wyceniają
portfel.

Rozwiązaniem był nowy, trzyliniowy interfejs `FxRateLookup` — połowa do czytania w kontrakcie,
`FxRates` implementuje go i zostaje w implementacji.

**Reguła:** dzieląc typ na kontrakt i implementację, osobno sprawdzić, czy niesie **stan**. Jeśli
tak, do kontraktu idzie interfejs czytający, nie klasa.

### Package-private w SPI przestaje działać przez granicę jara — i dobrze

`BrokerQuotationProvider.find`/`fetch`/`onPriceChange` były package-private, a javadoc w
`OkxBrokerQuotationProvider` twierdził, że to uniemożliwia providerom nadpisanie ich. Uniemożliwiało
providerom w **innych** pakietach; jeden umieszczony w `quotation.domain` mógł nadpisać bez
przeszkód.

Po wyjściu klasy bazowej do osobnego artefaktu `QuotationService` jej nie dosięga, więc metody
musiały zrobić się `public` — i przy okazji `final`, co daje dokładnie tę ochronę, jaką opisywał
komentarz. **Podział zamienił ochronę przypadkową na jawną.**

Dodatkowo `public onPriceChange` okazało się warunkiem, bez którego nie da się napisać atrapy —
to przez nie `InMemoryQuotationApi` karmi prawdziwy cache zamiast udawać jego zachowanie.

### Trzynaście atrap było objawem braku właściciela kontraktu

Port zadeklarowany w pakiecie konsumenta nie ma oczywistego miejsca na atrapę, więc każdy autor
testu pisał własną. Trzynaście klas anonimowych, 309 linii, już rozjechanych: dwanaście rzucało
`AssertionError` przy braku ceny, jedna `QuoteNotFoundException` (bo testuje właśnie tę ścieżkę),
jedna nie miała tabeli cen i odpowiadała 70 000 na wszystko.

Dwie rzeczy, które to kosztowało:

- **Jedna atrapa karmiła asercję bzdurą** — `USD/EUR = 50 000`. Nic na tym nie stało, ale nikt by
  nie zauważył, gdyby stało.
- **Brak wspólnej atrapy kształtował API produkcyjne.** `PortfolioSummaryMapper.priceOf` ma
  komentarz, że `find` nie wszedł do kontraktu, bo *„a second one would have to be honoured by
  every stub and mock that already implements it"*. Decyzja projektowa podjęta z powodu duplikacji
  w testach.

**Reguła:** modul `-api` dostaje też atrapę swojego kontraktu, w `src/test` i publikowaną jako
`test-jar`. Właściciel kontraktu jest właścicielem jego atrapy. Atrapa powinna **delegować** do
kodu produkcyjnego wszystko, co produkcja już implementuje, i decydować tylko o tym, czego w
produkcji nie ma (tu: co znaczy brak ceny — stąd `strict()` i `asInProduction()`).

### Wydzielenie implementacji blokował jeden plik konfiguracyjny

`quotation/KafkaTopicConfig` trzymał trzy niezwiązane rzeczy, w tym **jedyny `KafkaAdmin` w całej
aplikacji**. Konsekwencja, której nikt nie zapisał: topiki cashflow (`cash_flow`,
`bank_data_ingestion`, `user_financial_profile`) powstawały tylko dlatego, że `vidulum-wealth` był
na classpath. `NewTopic` bez admina jest cicho ignorowany, a broker ma
`KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'false'`, więc pierwszym objawem byłby producent w timeout.

Rozbicie, wykonane **przed** przenoszeniem plików i zweryfikowane osobnym pełnym buildem:

| co | gdzie | dlaczego |
|---|---|---|
| `kafkaAdmin()` | `shared-kernel/shared/kafka/KafkaAdminConfig` | nie należy do żadnego modułu, potrzebują go wszystkie |
| `user_created` topic + producer + template | `vidulum-app/user/infrastructure/UserCreatedKafkaConfig` | obok `UserCreatedEventEmitter` — i dokładnie tam, gdzie javadoc cashflow twierdził, że już jest |
| consumer + containerFactory | `vidulum-wealth/pnl/infrastructure/UserCreatedConsumerConfig` | bliźniak konfiguracji cashflow; każdy słuchający przynosi własne fabryki |
| `quotationService()` + providery | `vidulum-quotation/QuotationConfiguration` | odpowiednik `AddQuotationModule()` |

**Reguła przy krokach 04 i 06:** najpierw rozbić konfigurację *w miejscu*, osobnym buildem, potem
przenosić pliki. Tryb awarii wiringu Kafki jest cichy, a przy jednym wielkim PR nie wiadomo, która
połowa zawiniła.

### Co się nie potwierdziło

**Zysk w minutach.** Sekcja 11 obiecywała, że blast radius zacznie nieść informację — i tak jest —
ale czas builda wealth nie spadł: 1:15 przed i po. Quotation ma 33 testy, wealth 309. Przy tych
rozmiarach cały zysk jest w tym, **co** build mówi, nie jak długo trwa.

**Scope testowy nie zniknął.** `vidulum-wealth` i `vidulum-okx` mają `vidulum-quotation` w
`<scope>test</scope>`: testy integracyjne sieją ceny przez `QuoteRestController`, a
`OkxQuotationComponentTest` konstruuje prawdziwy `QuotationService`. Własność „nikt nie zależy od
implementacji" dotyczy wyłącznie classpathu kompilacji. Żeby objęła też testy, harness musiałby
publikować ceny na topic `quotes` albo przez `InMemoryQuotationApi` — osobne zadanie.

### Pytania z sekcji 20, na które jest już odpowiedź

- **Granulacja `DataCleaner`** (pytanie 4) — nieaktualne dla quotation: moduł nie ma kolekcji Mongo,
  więc `WealthDataCleaner` był nietknięty. Pytanie wraca przy portfolio i pnl.
- **`@AutoConfiguration` kontra component scan** (pytanie 5) — podział wykonany **bez** przejścia na
  jawną rejestrację. `@SpringBootApplication` w `com.multi.vidulum` skanuje nowy jar bez żadnej
  zmiany, pod warunkiem że `vidulum-app` go wciąga. Jawna rejestracja nie jest warunkiem podziału.

### Krok 04: kontrakt odpowiadał publicznemu API, ale DTO już nie

Reguła z kroku 02 — licz wywołania, nie metody — tu nie dała redukcji. Wszystkie sześć metod
`PortfolioRestClient` ma realnych wołających, więc `PortfolioModuleApi` ma sześć metod. Redukcja
wyszła **po stronie danych**:

| | było (web DTO) | jest (kontrakt) |
|---|---|---|
| `PortfolioView` | 21 pól `PortfolioSummaryJson` | **9** |
| `PositionView` | 16 pól `AssetSummaryJson` | **7** |
| `AggregatedPortfolioView` | 18 pól | **6** |

Dwanaście pominiętych pól w widoku portfela to statusy wyceny i kontrybucji, współczynniki pokrycia,
lista niewycenionych aktywów i figury realised/wealth-change — istnieją, żeby `GET /portfolio`
mogło powiedzieć właścicielowi, **jak pewna jest odpowiedź**. Nikt poza tym ekranem ich nigdy nie
czytał, a kontrakt, który by je niósł, zamieniałby każdą zmianę tego ekranu w zmianę jednostki
kompilacji tradingu.

Identyfikatory w kontrakcie są value objectami, nie Stringami. `PortfolioId.of(portfolio.getPortfolioId())`
występowało w trzech konsumentach i zniknęło.

### Gateway tworzy cykl z własną konstrukcją — i to tłumaczy, czemu impl siedział w app

`commandGateway` powstaje z `List<CommandHandler<?, ?>>`; wśród nich jest
`MakePnlSnapshotCommandHandler`, który potrzebuje `PortfolioModuleApi`; implementacja kontraktu
potrzebuje gatewaya. Spring przerywa to jako `BeanCurrentlyInCreationException` i **38 testów
integracyjnych wealth nie wstało**.

Stary `PortfolioRestClientImpl` nie miał tego problemu, bo wstrzykiwał pięć konkretnych handlerów —
czyli **ucieczka od cyklu była niezapisanym powodem, dla którego implementacja mieszkała w
`vidulum-app`**. Usunięty `TestPortfolioRestClient` miał `@Lazy CommandGateway`; ten modyfikator był
jedynym śladem problemu w całym repo.

Rozwiązanie **doraźne**: jawny konstruktor z `@Lazy` na obu gatewayach. Proxy rozwiązywane przy
pierwszym wywołaniu jest mniejszym ustępstwem niż zależność od środka modułu.

**Reguła przy kroku 06:** każdy `*ModuleApiImpl` oparty na gatewayach będzie miał ten cykl, jeśli
dowolny handler w tym samym kontekście konsumuje jego kontrakt. Zakładać `@Lazy` od razu, nie po
czerwonym buildzie.

> **Nieaktualne od kroku 08.** Reguła działała, ale leczyła objaw: `@Lazy` nie usuwa cyklu, tylko
> odkłada go za proxy. Cykl zniknął u źródła — patrz „Krok 08" w tej sekcji. Żadna implementacja
> kontraktu nie potrzebuje już `@Lazy`.

### Lekcja o atrapach z kroku 02 nie generalizuje się

W kroku 02 trzynaście atrap powtarzało te same reguły produkcyjne i wspólny `InMemoryQuotationApi`
był oczywisty. Napisałem więc analogiczny `InMemoryPortfolioModuleApi` — i **usunąłem go, bo nie
miał użytkownika**:

- `PreciousMetalsLifecycleComponentTest` używa teraz **prawdziwej** implementacji
  (`new PortfolioModuleApiImpl(gateway, queries, summaryMapper)` — ten sam pakiet, więc
  package-private wystarcza). Trzydziestoośmiolinijkowa klasa wewnętrzna istniała wyłącznie dlatego,
  że jedyna implementacja była w innym module.
- dwie atrapy w pnl **kodują asercje specyficzne dla testu**: `ValuationCurrencyComponentTest`
  zapisuje, o którą walutę pytano (to jest jego temat) i rzuca `AssertionError`, gdy ktoś czyta
  portfel w jego własnej walucie; `WealthChangeOverWindowComponentTest` celowo ignoruje walutę, żeby
  zainscenizować rozjazd.

**Reguła:** wspólna atrapa opłaca się, gdy doublów jest wiele i **powtarzają regułę produkcyjną**.
Double, który jest nośnikiem asercji, nie jest duplikatem i nie ma czego zastąpić. Przy kroku 06
sprawdzić to przed napisaniem atrapy, nie po.

### Grupowanie kontraktu w podpakiety

`vidulum-portfolio-api` ma `error/` (trzy wyjątki dziedziczące `BusinessException`) i `view/` (trzy
recordy). `error` to słowo tego repo — shared-kernel ma `common/error`. `view`, nie `dto`, bo `dto`
oznacza tu kształt JSON-a wystawiany przez kontroler (`PortfolioDto`, `QuotationDto`), a całym
sensem kroku 04 było rozdzielenie tych dwóch rzeczy.

Efekt uboczny: lista importów u konsumenta mówi, **co** bierze z portfolio — operację, dane, czy
też łapie wyjątki biznesowe. Przy ośmiu typach na płasko to się rozmywało.

`vidulum-quotation-api` został płaski. Przeniesienie go na ten sam układ nie wychodzi dosłownie:
`AssetBasicInfo` jest i zwracane, i przyjmowane przez kontrakt, więc nie jest widokiem i zostaje w
korzeniu; naturalny podział tam to `error/` plus **`spi/`** dla `BrokerQuotationProvider` i
`PriceChangedEvent` (ten drugi jest w kontrakcie wyłącznie dlatego, że siedzi w sygnaturze SPI).

### Propozycja na krok 05: deklarowana obsługa eventów

Dziś nie da się odpowiedzieć na pytanie „kto słucha tego eventu" inaczej niż grepem po nazwie
topicu. Kontrakt mówi, co można modułowi **zapytać**, i milczy o tym, czego on **słucha** — a po
kroku 05/06 eventy przeniosą większość zapisów do portfela, więc to będzie ważniejsza połowa
granicy.

Problemem nie jest testowalność — listenery są już wołalne bezpośrednio
(`new OrderFilledEventListener(gateway).on(event)` w `PreciousMetalsLifecycleComponentTest`).
Problemem są **magiczne stringi subskrypcji**:

```java
// portfolio/app/listeners/OrderFilledEventListener
@KafkaListener(
        groupId = "group_id6",
        topics = "order_filled",                          // ← własność tradingu, string
        containerFactory = "orderFilledContainerFactory")  // ← bean z trading/infrastructure, string
```

Klasa eventu jest sprawdzana przez kompilator; subskrypcja nie. Zmiana nazwy topicu przez tradingu
ucisza portfolio bez żadnego błędu, zmiana nazwy beana nie pozwala wstać aplikacji.

**Czego nie robić:** deklaracji „portfolio obsługuje X" w `portfolio-api`. Wszyscy zależni od
portfolio-api dowiedzieliby się o evencie tradingu — zależność przecieka na boki — i znika jedyna
realna korzyść z eventów, czyli to, że producent nie zna konsumentów. **Deklaracja konsumpcji nie
należy do kontraktu konsumenta.**

**Co robić:** interfejs handlera w kontrakcie **producenta**, razem z faktami subskrypcji, których
producent jest właścicielem.

```java
// vidulum-trading-api
public interface OrderFilledHandler {

    String TOPIC = "order_filled";
    String CONTAINER_FACTORY = "orderFilledContainerFactory";

    void onOrderFilled(OrderFilledEvent event);
}
```

```java
// vidulum-portfolio — package-private, nikt z zewnątrz tego nie nazywa
@Component
@AllArgsConstructor
class OrderFilledToPortfolio implements OrderFilledHandler {

    private final CommandGateway commandGateway;

    @Override
    @KafkaListener(topics = TOPIC, containerFactory = CONTAINER_FACTORY, groupId = "portfolio")
    public void onOrderFilled(OrderFilledEvent event) {
        commandGateway.send(ProcessTradeCommand.builder()… );
    }
}
```

`groupId` zostaje po stronie konsumenta — każdy wybiera własny, a nazwanie go po module jest tym, co
czyni opóźnienie czytelnym per moduł.

Reguła, która z tego wychodzi:

> **W `X-api`:** co można zapytać X **i co X ogłasza** (event + kształt handlera).
> **W `X` (impl):** czego X słucha — wyrażone przez *implementowanie* interfejsów handlerów innych
> modułów.

| pytanie | jak się odpowiada |
|---|---|
| czego słucha portfolio? | które interfejsy `*Handler` implementuje `vidulum-portfolio` |
| kto słucha `OrderFilledEvent`? | implementacje `OrderFilledHandler` — **dziś bez odpowiedzi** |
| co ogłasza portfolio? | interfejsy `*Handler` w `vidulum-portfolio-api` |

Co to kupuje: topic i fabryka przestają być stringami, „kto słucha X" staje się odpowiadalne, da się
to wyegzekwować, `groupId` per moduł zamiast `group_id1`/`group_id4`/`group_id6`. Czego nie kupuje:
testowalności (już jest) ani luźniejszego sprzężenia kompilacyjnego (konsument i tak zależy od
`trading-api`).

Egzekwowanie — bez tego zostaje konwencją:

```java
methods().that().areAnnotatedWith(KafkaListener.class)
    .should().beDeclaredInClassesThat()
        .implement(nameMatching(".*\\.api\\..*Handler"));
```

Odrzucone warianty:

- **generyczny `IntegrationEventHandler<E>` w shared-kernel** — spójny z `CommandHandler` i
  `QueryHandler`, ale nie da się do niego przypiąć topicu, a „znajdź implementacje" zwraca wszystkie
  handlery w systemie, nie odpowiedź per event. Wasz idiom SPI (`ExchangeAdapter`,
  `BrokerQuotationProvider`) to małe interfejsy nazwane konkretem, nie jedna generyczna rama.
- **klasa-manifest** (`PortfolioEvents.CONSUMED = Set.of(...)`) — rozjedzie się z rzeczywistością bez
  testu, a jeśli i tak piszesz test, niech pilnuje typów.
- **Spring Modulith** — generuje taki spis sam (module canvas z published/consumed events) i
  weryfikuje granice, czyli „kup, nie buduj". Ale chce własnych konwencji pakietowych i jego szyna
  zdarzeń jest in-process, a te eventy jadą przez Kafkę. Do rozważenia przy kroku 07.

**Kiedy:** nie osobno. Portfolio ma dziś jeden listener, interfejs dla jednego handlera to ceremonia.
Właściwy moment to krok 05, gdzie i tak przenosisz siedem eventów do modułów producentów — ta sama
robota na tych samych plikach. Jedno wyjątkowo wcześniej i niezależnie: **nazwane `groupId`**,
darmowe do poprawienia i od razu daje czytelne opóźnienia per moduł.

### Krok 05a i dlaczego `vidulum-trading-api` nie jest końcowy

Artefakt powstał w 05a niosąc **dwa typy**: `TradeConfirmedEvent` i `TradeConfirmedHandler`.
Zamknął ostatnie przekroczenie granicy po kroku 04 — `TradeCapturedEventListener` budował
`ProcessTradeCommand`, czyli komendę portfolio, i wysyłał ją wspólnym gatewayem. Teraz oba przebiegi
tradingu kończą się jednym eventem, a grep po `portfolio.app` i `portfolio.domain` w pakiecie
tradingu daje zero.

Przy okazji z shared-kernel zniknęły trzy eventy z siedmiu: `OrderFilledEvent` (zastąpiony),
`TradeCapturedEvent` (przeniesiony do `trading/app` — nigdy nie przechodził granicy) i
`TradeAppliedToPortfolioEvent` (**usunięty jako martwy**; emitowanie eventu bez konsumenta to
dokładnie zarzut postawiony `UserFinancialProfileUnifiedEvent`, więc wróci razem z konsumentem).

**Czytanie nadal chodzi obok kontraktu.** Cztery miejsca, ten sam układ co przed krokiem 02 i 04 —
port zadeklarowany u konsumenta plus adapter wstrzykujący handlery dostawcy:

| kto | przez co | co czyta |
|---|---|---|
| `portfolio/.../GetPositionViewOfPortfolioQueryHandler` | `TradingRestClient` | `getOpenedOrders` → `TradingDto.OrderSummaryJson` |
| `risk_management/domain/RiskManagementEngine` | `TradingRestClient` | `getOpenedOrders` |
| `pnl/.../MakePnlSnapshotCommandHandler` | `TradingRestClient` | `getTradesInDateRange` → `TradingDto.TradeSummaryJson` |
| `portfolio/infrastructure/TradingRestClientAdapter` | — | wstrzykuje **dwa query handlery** i mapuje `Order`/`Trade` na Json |

Dodatkowo `pnl` i `risk` importują ten port **z pakietu portfolio**, czyli żeby dostać kontrakt
tradingu, zależą od trzeciego modułu. Konsumenci używają łącznie **18 różnych getterów**
(`getQuantity` ×9, `getSymbol` ×4, `getSide` ×3, `getOriginOrderId` ×3, `getLimitPrice`,
`getStopPrice`, `getTargetPrice`, `getEntryValue`, `getStopLossValue`…) — to lista, z której w kroku
06 wyprowadza się typy widoku.

**Trzy uwagi o samym module:**

- `TradeConfirmedEvent` jest **mutowalny** (`@Data` daje settery), więc konsument może przestawić
  pola otrzymanego eventu. `portfolio-api` używa recordów i nie ma Lomboka wcale.
- `CONTAINER_FACTORY = "tradeConfirmedContainerFactory"` to **nazwa springowego beana w module,
  który Springa nie widzi**. Działa, bo to string, ale kontrakt wie o konwencji wiringu konsumenta.
  Alternatywa oddaje magiczny string konsumentowi — świadomy kompromis.
- Nic nie pilnuje, żeby moduł został kontraktem; po kroku 01 pilnowałby ArchUnit.

### Trzy decyzje podjęte po 05a

**1. `trading-api` domyka się w kroku 06.** Artefakt rodzi się z eventami, metody czytające dochodzą
razem z usunięciem `TradingRestClient`. Dwie metody, bo tylko dwie mają wołających.

**2. Prowizja wchodzi do kosztu bazowego** — krok 05d.

Stan faktyczny: `fee` żyje w `trading/domain/Trade`, `MakeTradeCommand`, `TradeRestController` i
`TradingDto`, a w całym pakiecie `portfolio` słowo „fee" **nie występuje ani razu**.
`MakeTradeCommandHandler` liczy `totalValue = value + totalFee` i zapisuje je w trade'ie, ale
`TradeConfirmedEvent`, `ProcessTradeCommand` i `ExecutedTrade` nie mają pola prowizji — więc
`Portfolio.swing()` wycenia nabycie jako `cena × ilość`. Koszt jest zaniżony, a `unrealisedProfit` i
`realisedResult` zawyżone.

To nie jest skutek 05a — tak było w `OrderFilledEvent` i `TradeCapturedEvent`. Ale
`TradeConfirmedEvent` jest teraz **jedyną** drogą trade'u do portfela, czyli jedynym miejscem, gdzie
prowizję da się dołożyć.

Rozpisane jako osobne zadanie w `2026-10-13-fee-in-cost-basis.md` — przy pisaniu wyszło, że luk
jest **trzy**, nie jedna: koszt nabycia, wynik zrealizowany, oraz prowizja zapłacona w trzecim
aktywie, która nie jest odejmowana ze stanu w ogóle (`swing()` rusza dokładnie dwie nogi). Oraz że
agregat celowo nie liczy na walutach — ten sam komentarz, który tłumaczy, dlaczego `cost` jest
„recorded, not subtracted", zabrania składania prowizji w `Portfolio`.

Zasięg: pole w evencie → `ProcessTradeCommand` → `ExecutedTrade` →
`PortfolioEvents.TradeProcessedEvent` (a to zdarzenie **persystowane**) →
`calculatePurchasedPortionOfAsset` i `swing()`. Po stronie testów dotyka **23 plików** asertujących
`costBasis`, `unrealisedProfit` lub `realisedResult` — i część ich liczb się zmieni, co jest
właściwym zachowaniem, nie regresją. Osobna zmiana z własnym przebiegiem, nie dodatek do czegoś
innego.

**3. Payloady Kafki jako recordy.** Dziś **każdy** payload w projekcie jest klasą z Lombokiem —
`CashFlowUnifiedEvent`, `PriceChangedEvent`, `UserCreatedEvent`, `TradeCapturedEvent`,
`TradeConfirmedEvent`, `KafkaEvent`. Czyli 05a poszło za istniejącym idiomem, ale moduły kontraktowe
mają teraz dwa style: `portfolio-api` niemutowalny bez Lomboka, `trading-api` mutowalny z Lombokiem.

Decyzja: **record**. Zmiana jest cross-cutting, więc idzie razem z przenoszeniem eventów w 05b i 05c,
nie osobno dla jednego z nich. Wymaga sprawdzenia deserializacji — `JsonDeserializer` na recordzie
działa od Jacksona 2.12 przy włączonym module parameter-names (Spring Boot włącza go domyślnie), ale
w tym repo nie ma jeszcze ani jednego precedensu, więc pierwszy konwertowany event potrzebuje testu
przez prawdziwą Kafkę, nie tylko komponentowego.

### Krok 06: kontrakt tradingu domknięty

`TradingModuleApi` ma **dwie metody**, bo dwie mają wołających: `openedOrders(portfolioId)` (portfolio
i risk) oraz `tradesInRange(owner, window)` (pnl). Składanie, anulowanie i wykonanie zlecenia
przychodzą po HTTP od właściciela, a rozliczony trade wychodzi jako `TradeConfirmedEvent` — żadne z
tego nie potrzebuje miejsca w kontrakcie.

Redukcja znów wyszła po stronie danych:

| | web DTO | kontrakt |
|---|---|---|
| `OrderView` | 12 pól `OrderSummaryJson` | **8** |
| `TradeView` | 10 pól `TradeSummaryJson` | **9** |

Z widoku zlecenia wypadły: wewnętrzny `orderId` (konsumenci kluczują po `originOrderId`, czyli po
tym, jak zlecenie nazywa giełda), `portfolioId` (każdy wołający i tak pytał o jeden portfel) oraz
`type` i `status` — rodzaj zlecenia i jego miejsce we własnym cyklu życia to sprawa tradingu, a
jedyne, co konsument z tą listą robi, to filtr po `side`.

Usunięte: `portfolio.domain.TradingRestClient` i `portfolio.infrastructure.TradingRestClientAdapter`.
**`pnl` i `risk` nie importują już z portfolio niczego poza `portfolio.api`** — wcześniej brały
stamtąd kontrakt tradingu, czyli po dane jednego modułu szły przez trzeci.

**Reguła `@Lazy` z kroku 04 sprawdziła się dokładnie tak, jak zapisana** (i została zniesiona w
kroku 08, który usunął cykl u źródła). `QueryGateway` powstaje z
`List<QueryHandler>`, wśród nich jest `GetPositionViewOfPortfolioQueryHandler`, który konsumuje ten
kontrakt — więc `TradingModuleApiImpl` zamyka ten sam cykl co `PortfolioModuleApiImpl`. Tym razem
`@Lazy` było na miejscu od pierwszej kompilacji, bez 38 czerwonych testów.

Trzy drobiazgi, które wyszły przy przejściu na value objecty:

- **`StopLoss.originOrderId` było typu `OrderId`**, a budowane z *origin* id zlecenia
  (`OrderId.of(orderSummaryJson.getOriginOrderId())`). Kontrakt zwraca `OriginOrderId`, więc
  pomyłka typów wyszła sama; `StopLoss` nie jest persystowany, poprawione.
- `GetPositionViewOfPortfolioQueryHandler` miał **martwy import** `TradingDto` — jedno wystąpienie,
  samego importu.
- pnl grupowało trade'y po `String` id portfela i robiło `getOrDefault(portfolioId.getId(), …)`;
  z `TradeView::portfolioId` mapa kluczuje się po `PortfolioId` i konwersja znika.

**Czego `trading-api` nadal nie ma:** dla dzisiejszych wołających — niczego. Ale moduł trzyma teraz
**dwa style obok siebie**: `TradeConfirmedEvent` to mutowalna klasa z Lombokiem, a `OrderView` i
`TradeView` są recordami. Niespójność przeniosła się z poziomu „dwa moduły kontraktowe" na poziom
„jeden moduł", co tylko wzmacnia decyzję 3 z poprzedniej podsekcji: payloady Kafki na recordy razem
z 05b i 05c.

### Krok 06b: kontrakt exchange

`ExchangeConnectionApi` ma **cztery metody**, bo tyle woła onboarding: `ownedOrThrow`, `confirm`,
`reconnect`, `recordSync`. Rejestrowanie, odbieranie i listowanie połączeń przychodzi po HTTP od
właściciela.

`portfolio_spec` wstrzykiwał wcześniej **trzy command handlery exchange** —
`ConfirmExchangeConnectionCommandHandler`, `ReconnectExchangeCommandHandler`,
`RecordPortfolioSyncCommandHandler` — **i czytał połączenia prosto z
`DomainExchangeConnectionRepository`**. Trzynaście importów do wnętrza obcego modułu.

| | agregat | kontrakt |
|---|---|---|
| `ConnectionView` | 16 pól `ExchangeConnection` | **5** |

Poza widokiem wypadły: account uid, środowisko, region, zgłoszone uprawnienia klucza, tryb
poświadczeń, powód statusu i cztery znaczniki czasu — wszystko to odpowiedzi na pytania właściciela
o jego własne połączenie, czytane przez `GET /exchange-connection/{id}` i przez nikogo więcej.

Do kontraktu pojechały też typy, które przechodziły granicę: `ExchangeConnectionId` i
`ConnectionStatus` do korzenia, trzy wyjątki do `error/`, a **`ExchangeAdapter` do `spi/`** — tak
jak `BrokerQuotationProvider` w quotation-api.

**Nieoczekiwany zysk: `vidulum-okx` schodzi na `test` scope.** Jego kod produkcyjny implementuje
dwumetodowe SPI, a kompilował się przeciw całemu modelowi połączenia. Teraz `okx/src/main` zna tylko
`api.spi.ExchangeAdapter` i `api.error.UnknownExchangeRegionException`; implementacja jest potrzebna
wyłącznie testom endpointowym. **`vidulum-app` jest jedynym modułem z `vidulum-exchange` w compile
scope** — dokładnie jak przy quotation.

**Strona testowa zachowała to, co sprawdza.** Sześć testów portfolio_spec wiązało po trzy prawdziwe
handlery exchange plus dubler repozytorium. Zastąpione jednym `ExchangeConnectionApiForSpec`, który
odpowiada na kontrakt **prawdziwymi handlerami** nad magazynem w pamięci — więc połączenie nadal
faktycznie przechodzi między stanami, a to jest przedmiotem tych testów (onboarding ma zostawić
połączenie w służbie, a wznowienie ma przesunąć kotwicę synchronizacji). Podmieniono tylko Mongo.

Przy okazji poprawiony komentarz w `ConfirmPortfolioSpecCommandHandler`: twierdził, że połączenie
„jest zamykane własną komendą, a nie przez sięganie do jego repozytorium". Była to połowa prawdy —
komendy owszem, ale **odczyt sięgał do repozytorium**. Teraz zdanie jest prawdziwe.

**06c jest odblokowane po stronie produkcyjnej.** Po 04b, 05a, 03, 06 i 06b żaden main nie sięga
już do wnętrza drugiego poddomeny. ~~To już tylko przeniesienie plików i pomy.~~ **To zdanie było
nieprawdziwe** — zostało tu przekreślone, a nie usunięte, bo pomyłka jest pouczająca: patrz „Krok
06c" niżej. Strona testowa miała własną blokadę, której nie widać w importach maina.

### Krok 06c: dwa artefakty naraz, bo jeden zamykał cykl

**Napisałem w tym dokumencie, że zostało „tylko przeniesienie plików i pomy". Nieprawda.** Importy
maina były czyste, więc wyglądało na gotowe — ale zależności Mavena liczą też `test` scope, a tam:

```
portfolio/test  →  trading   (PreciousMetalsLifecycleComponentTest wiąże handlery obu)
trading/test    →  portfolio (TradingPortfolioIntegrationTest, LockingAssetsTests)
```

Wydzielenie `vidulum-portfolio` w pojedynkę dawałoby **cykl w reaktorze**, bo testy portfolio
potrzebują tradingu, a testy pnl i korzeń wealth potrzebują portfolio. Wyjście: **wariant A** —
wydzielić portfolio (z `portfolio_spec`) i trading **w jednym kroku**, a testy międzymodułowe
zostawić w `vidulum-wealth`, który bierze oba artefakty w `test` scope. Wealth przestaje być
modułem domenowym i staje się tym, czym faktycznie jest: pnl + risk + miejscem na testy, które
celowo przechodzą przez więcej niż jedną granicę.

```
                     przed 06c                              po 06c
          ┌────────────────────────────┐    ┌──────────────┬──────────────┬───────────┐
          │       vidulum-wealth       │    │  portfolio   │   trading    │  wealth   │
          │ portfolio · portfolio_spec │ →  │ + spec       │              │ pnl·risk  │
          │ trading · pnl · risk       │    │ 100 main     │ 45 main      │ 46 main   │
          │ 191 main / 55 test         │    │  29 test     │  3 test      │ 23 test   │
          └────────────────────────────┘    └──────────────┴──────────────┴───────────┘
                                                      208 + 10 + 91 = 309 testów
```

**Nic nie zginęło przy podziale** — 309 testów przed i po, bo liczba jest pierwszą rzeczą, którą
łatwo stracić przy `git mv` i pomach (wystarczy, że katalog testów nie trafi pod `testSourceDirectory`
i suita cichnie, zamiast świecić na czerwono).

**Co potwierdziło, że granica jest prawdziwa**, a nie tylko przeniesionymi plikami:

| sprawdzenie | wynik |
|---|---|
| `portfolio/main` → trading, pnl, risk, quotation, exchange poza `.api` | 0 |
| `trading/main` → portfolio, pnl, quotation, exchange poza `.api` | 0 |
| `wealth/main` → portfolio, trading, quotation, exchange poza `.api` | 0 |
| implementacje w `wealth` i `okx` | `test` scope |
| implementacje w `app` | `compile` scope — bootstrap jako jedyny |

Ostatni wiersz jest tym, o co szła cała seria: **tylko `vidulum-app` kompiluje się przeciw
implementacjom**. Każdy inny moduł widzi wyłącznie kontrakty, a implementacje dociąga najwyżej po to,
żeby odpalić test.

Dwa drobne długi zostawione świadomie: `pnl/ValuationCurrencyComponentTest` i
`pnl/WealthChangeOverWindowComponentTest` asertują na `PortfolioDto`, choć testują pnl — należy im
się `PortfolioView`; `UnpricedPositionEndpointTest` sięga w jednym miejscu po `TradingDto`.
Przeniesione bez zmian, żeby podział nie mieszał się z przepisywaniem asercji.

Przy okazji usunięte dwa martwe `import TradingDto` z `MakePnlSnapshotCommandHandler` i
`RiskManagementEngine` — zostały po kroku 06, kompilowały się, bo artefakt był jeszcze jeden, i
upadłyby dopiero teraz.

### Krok 08: cykl beanów usunięty u źródła, a nie odłożony

Pytanie „czy ten cykl nadal występuje" miało jedną poprawną odpowiedź: **tak, i było go więcej niż
w kroku 04.** `@Lazy` nigdy nie usunęło cyklu — wstawiło proxy, które rozwiązuje się przy pierwszym
wywołaniu. Policzone przed zmianą: trzy implementacje kontraktów (`PortfolioModuleApiImpl`,
`TradingModuleApiImpl`, `ExchangeConnectionApiImpl`) i **jeszcze pięć miejsc po stronie testów**
(`TestCashFlowServiceClient` ×2 plus trzy klasy bazowe testów integracyjnych) — ostatnie z zupełnie
innego powodu biznesowego, ale z identycznym kształtem:

```
CommandGateway → ConfigureCategoryMappingCommandHandler → CashFlowServiceClient → CommandGateway
```

**Przyczyną nie są kontrakty, tylko sposób składania gatewaya.** `commandGateway(List<CommandHandler>)`
wymaga, żeby każdy handler istniał, zanim gateway powstanie. Grupą kontrolną jest `QuotationApi`:
realizuje je `QuotationService`, które **nie rozmawia przez gateway** — i nie miało ani jednego
`@Lazy`.

Gateway nie potrzebuje jednak handlerów w momencie konstrukcji, tylko przy `send`. Nowy
`CqrsGatewayConfiguration` w shared-kernel wygląda więc tak:

```java
@Bean CommandGateway commandGateway() { return new CommandGateway(); }   // zero zależności

@Bean SmartInitializingSingleton cqrsHandlerRegistration(
        CommandGateway cg, QueryGateway qg,
        ObjectProvider<CommandHandler<?, ?>> commands,
        ObjectProvider<QueryHandler<?, ?>> queries) {
    return () -> { commands.orderedStream().forEach(cg::registerCommandHandler);
                   queries.orderedStream().forEach(qg::registerQueryHandler); };
}
```

Krawędź `gateway → handlery` przestaje istnieć na etapie tworzenia beanów, więc **cykl znika dla
całego repozytorium naraz**, a nie dla jednego beana. Rejestracja biegnie przed startem beanów
`SmartLifecycle`, czyli kontenery listenerów Kafki nie mogą dostarczyć wiadomości do pustego
gatewaya.

Efekty uboczne, wszystkie w dobrą stronę:

| | przed | po |
|---|---|---|
| `@Lazy` w repo | 8 miejsc | **0** |
| definicje gatewayów | 4 kopie (`VidulumApplication` + 3 konteksty testowe) | 1 konfiguracja, importowana |
| brak handlera | `IllegalArgumentException` **bez komunikatu**, przy pierwszym żądaniu | komunikat z nazwą komendy, a kontekst znów nie wstaje przy starcie |

Ostatni wiersz jest tym, co `@Lazy` cicho zabierało: **start aplikacji przestał dowodzić, że graf
się spina.** Teraz znowu dowodzi.

**Test pilnuje przyczyny, nie objawu.** `CqrsGatewayConfigurationTest` stawia minimalny kontekst o
produkcyjnym kształcie — implementacja kontraktu bierze gateway *eagerly*, a handler konsumuje ten
kontrakt — i **czwarty test stawia ten sam zestaw beanów ze starą konfiguracją, oczekując, że
kontekst nie wstanie.** Bez tego testu nie wiedziałbym, czy nowa konfiguracja jest konieczna, czy
tylko nieszkodliwa.

Przy pierwszej próbie ten negatywny test **przeszedł zamiast upaść** — zbudowałem kształt, w którym
implementacja brała tylko `QueryGateway`, a konsumentem był `CommandHandler`, więc pętla się nie
domykała. Dopóki test negatywny nie upadnie na starej konfiguracji, nie dowodzi niczego; to ten sam
błąd co zielony test, który nic nie sprawdza.

### Krok 09: dwa liście wychodzą bez kontraktu, a wealth zostaje bez maina

Pytanie było o `pnl` i `risk` — „są skromne, wydzielmy je jak resztę, razem z `-api`". Przegląd
importów odpowiedział inaczej:

| | importuje z zewnątrz | **jest importowane przez** |
|---|---|---|
| `pnl` | `portfolio.api` (3 typy), `trading.api` (2), `quotation.api.fx` (3) | **nic w mainie** |
| `risk_management` | `portfolio.api` (3), `trading.api` (2), `quotation.api` (1) | **nic w mainie** |

Oba są **liśćmi**: konsumują cudze kontrakty i odpowiadają po HTTP. `vidulum-pnl-api` i
`vidulum-risk-api` miałyby dziś **zero konsumentów** — czyli byłyby tym samym, co skasowany
`InMemoryPortfolioModuleApi`. Więc artefakty tak, kontrakty nie; kształt obu `ModuleApi` jest
rozpisany na papierze w rozmowie i wróci, gdy pojawi się pierwszy konsument (najpewniej moduł
alertów — `2026-10-15-alerts-module-design.md`).

```
                   przed 09                                        po 09
     ┌────────────────────────────────┐        ┌──────────┬──────────┬──────────────────────┐
     │         vidulum-wealth         │        │   pnl    │   risk   │   wealth             │
     │ pnl · risk_management          │   →    │ 36 main  │ 10 main  │ 0 main / 18 test     │
     │ 46 main / 23 test              │        │  5 test  │  0 test  │ testy międzymodułowe │
     └────────────────────────────────┘        └──────────┴──────────┴──────────────────────┘
                                                        31 + 0 + 60 = 91 testów
```

**`vidulum-wealth` nie ma już `src/main`.** To nie skutek uboczny, a wniosek: od kroku 06c ten moduł
istniał tylko po to, żeby trzymać testy przechodzące przez więcej niż jedną granicę, a pnl i risk
były w nim ostatnimi lokatorami.

**Dlatego nazywa się teraz `vidulum-wealth-tests`.** Nazwa `vidulum-wealth` mówiła o domenie, której
w tym module nie ma — a moduł bez `src/main`, od którego nic nie zależy, jest zbiorem testów i tylko
tym. Zmiana nazwy artefaktu dotyka całego reaktora, więc wyszły przy niej **wszystkie nieaktualne
wskazania nazwy**: dziewięć komentarzy w kodzie i trzy w pomach mówiły „vidulum-wealth" tam, gdzie
chodziło o portfolio albo quotation (przeniesione w krokach 02b–06c), a tabela endpointów w
`2026-09-18-okx-exchange-backend-integration.md` przypisywała `/portfolio`, `/portfolio-spec` i
`/quote` do wealth. Poprawione; historyczne wystąpienia w opisach „jak było" zostawione z notą.
Klasy `WealthIntegrationTest` i `WealthTestApplication` zachowują nazwy — opisują rolę w testach,
nie moduł.

**Tylko `vidulum-app` kompiluje się przeciw implementacjom** — już przeciw sześciu. `vidulum-risk`
jest przy tym najczystszym modułem w repo: **ani jednej zależności od implementacji**, nawet w
testach.

Trzy rzeczy, które wyszły po drodze i których nie było w planie:

- **Dług zapisany po 06c nie istniał.** Notowałem, że dwa testy komponentowe pnl „asertują na
  `PortfolioDto`". Nieprawda: to były **nieużywane importy** — dokładnie ten sam przypadek co dwa
  martwe `import TradingDto` w mainie, znalezione w 06c. Kompilowały się, bo artefakt był jeden.
  Usunięte; pnl nie potrzebuje w testach ani portfolio, ani tradingu.
- **`vidulum-okx` zależał od `vidulum-wealth` bez powodu.** Komentarz w pomie mówił, że „tu mieszka
  `BrokerQuotationProvider` i `QuotationService`" — a quotation wyszło w kroku 02b. okx nie importuje
  z pnl ani riska niczego; zależność usunięta.
- **`vidulum-wealth/src/main/resources/application.yml` wjeżdżał z sekretem JWT na classpath
  aplikacji.** Przy opróżnianiu maina plik wypadł razem z katalogiem; przywrócony do
  `src/test/resources`, bo służy wyłącznie testom springowym tego modułu (`WealthTestApplication`
  skanuje całe `com.multi.vidulum`, więc podnosi `JwtService` z shared-kernel, a ten bez klucza się
  nie zbuduje). Produkcja ma swój plik w `vidulum-app` — czyli jeden `application.yml` mniej na
  classpathzie, o którym nikt nie wiedział.

Jeden wyjątek od „testy jadą z modułem": `FxValuationRatesTest` bierze **prawdziwy** magazyn kursów
`quotation.domain.fx.FxRates`, bo sprawdza odwrócenie fixingu (`USD/PLN` → `PLN/USD`) — atrapa
musiałaby powtórzyć testowaną regułę. Dlatego `vidulum-pnl` ma `vidulum-quotation` w **test scope**,
a main widzi wyłącznie `quotation-api`. `ValuationBasisPersistenceTest` został w module testowym, bo
jest springowy i stawia Testcontainers — tak jak testy endpointów portfolio po 06c.

### Krok 10: foldery zamiast siedemnastu wpisów w korzeniu

Po kroku 09 korzeń repozytorium miał **17 modułów w jednym płaskim wykazie** — i nic w drzewie nie
mówiło, że `vidulum-quotation` i `vidulum-portfolio` należą do dwóch różnych warstw. Jedenaście z
nich zjechało pod dwa agregatory:

```
vidulum/
├── vidulum-shared-kernel/
├── vidulum-cashflow/        (kontener + 2 zagnieżdżone, jak dotąd)
├── market/      ← quotation±api · exchange±api · okx
├── wealth/      ← portfolio±api · trading±api · pnl · risk
├── vidulum-wealth-tests/
└── vidulum-app/
```

**Podział na te dwie grupy nie jest estetyczny, jest zmierzony.** Zanim powstały foldery,
policzyłem krawędź:

| | importy do `portfolio`, `trading`, `pnl`, `risk` |
|---|---|
| `vidulum-quotation-api`, `vidulum-quotation` | **0** |
| `vidulum-exchange-api`, `vidulum-exchange` | **0** |
| `vidulum-okx` | **0** |

A w drugą stronę wealth bierze wyłącznie kontrakty: portfolio → `quotation.api` +
`exchange_connection.api`, pnl → `quotation.api.fx`, risk → `quotation.api`, trading → nic. Czyli
`market → wealth` nie istnieje, a `wealth → market` idzie tylko przez `*-api`. Dwa foldery to dwie
warstwy, nie dwa worki — **ceny i giełdy nie wiedzą, kto co posiada**.

`vidulum-okx` wylądował w `market/`, bo implementuje SPI **obu** kontraktów z tego folderu
(`BrokerQuotationProvider` i `ExchangeAdapter`) i kompiluje się wyłącznie przeciw nim. Drugi adapter
giełdy trafi obok; osobny folder `adapters/` dla jednego modułu byłby na dziś szumem.

**Agregatory, nie rodzice.** `market/pom.xml` i `wealth/pom.xml` mają `packaging=pom` i tylko listę
modułów; każdy moduł **nadal dziedziczy po korzeniu** (`relativePath` zmieniony na `../../pom.xml`).
Dzięki temu krok 10 nie zmienia ani jednej zależności, ani jednego `artifactId`, ani jednej linii
Javy — co czyni go weryfikowalnym jednym zdaniem: albo reaktor się składa, albo `relativePath` jest
zły. Dziedziczenie zależności przez `wealth/pom.xml` to osobny **krok 11**, świadomie nierobiony tu.

**Kolejność reaktora wylicza się z zależności, nie z folderów** — i to warto zobaczyć, bo zmieniła
się bez szkody: `vidulum-okx` buduje się teraz **przed** portfolio (jest ostatni w `market/`, a
`market/` idzie pierwszy), co jest poprawne, bo okx zależy tylko od kontraktów quotation i exchange.

**Koszt, który trzeba było zapłacić: `-pl`.** Sprawdzone na module, który był zagnieżdżony już
wcześniej:

| polecenie | wynik |
|---|---|
| `./mvnw validate -pl :vidulum-recurring-rules` | ✅ |
| `./mvnw validate -pl recurring-rules` | ❌ `Could not find the selected project in the reactor` |

Czyli `-pl vidulum-portfolio` przestało działać. **36 wyrażeń `-pl` w `CLAUDE.md` przepisanych na
formę `:artifactId`**, która jest odporna na każde kolejne przenoszenie katalogów — razem z notą,
dlaczego tak, i z dwiema nowymi możliwościami: `-f market/pom.xml` i `-f wealth/pom.xml` budują całą
grupę (ten reaktor nie zawiera shared-kernela, więc bierze go z lokalnego repozytorium).

Nazwa `vidulum-wealth` wróciła — tym razem jako `artifactId` agregatora z `packaging=pom`, czyli
grupy, a nie jara. Moduł testowy pozostaje `vidulum-wealth-tests` **w korzeniu**, bo testuje
przejścia między grupami (portfolio↔trading, portfolio_spec↔exchange, pnl↔Mongo,
`DenominationChainComponentTest` dotyka quotation) — należy do obu naraz, czyli do żadnej.

### Krok 11a: co wolno wyciągnąć do rodzica, a czego nie

Pytanie było proste: „skoro mamy agregatory, czy `wealth/pom.xml` może nieść zależności, żeby pomy
w środku były chudsze?". Pomiar odpowiedział, że **nie tam**.

**Agregator to nie rodzic.** `market/pom.xml` i `wealth/pom.xml` mają tylko `<modules>`; dziedziczenie
wymagałoby zmiany `<parent>` w jedenastu pomach. A wspólny zbiór zależności **całej grupy** to:

| grupa | wspólne dla wszystkich modułów | wspólne dla samych implementacji |
|---|---|---|
| `wealth/` (6) | `vidulum-shared-kernel` | 9 wpisów |
| `market/` (5) | `vidulum-shared-kernel` | 5 wpisów |

Czyli rodzic grupy odchudziłby każdy pom **o jedną linię**. Wrzucenie tam zbioru implementacji jest
niemożliwe z dwóch powodów, z których pierwszy jest twardy:

1. **Maven to odrzuca.** W zbiorze implementacji wealth są `vidulum-portfolio-api` i
   `vidulum-trading-api` — odziedziczyłby je też sam `vidulum-portfolio-api`. Sprawdzone na
   minimalnym projekcie: `'dependencies.dependency.[com.test:kid:1]' for com.test:kid:1 is
   referencing itself` — build nie czyta nawet pomu.
2. **Kontrakt przestałby być kontraktem w czytelny sposób.** Deklaracja `spring-boot-starter-classic`
   w pomie mówi „ten moduł żyje wewnątrz Springa", a `*-api` nie żyje.

**Miejsce było inne: korzeń, który już jest rodzicem wszystkich 18 modułów** i nie miał ani jednego
`<dependencies>` — tylko `dependencyManagement`. Powtórzenia są zresztą repo-wide, nie per grupa:
`lombok` w 15/18 pomów, `spring-boot-starter-test` w 13/18, `spring-boot-starter-test-classic` w
10/18. Te trzy **nie mówią nic o żadnym module**. Wyciągnięte: 38 deklaracji mniej w 15 pomach, zero
nowych pomów, zero zmian w `<parent>`.

Efekt najlepiej widać na kontraktach — **wszystkie cztery mają teraz dokładnie jedną zależność**:

```
                      przed 11a    po 11a
portfolio-api              1          1
trading-api                2          1
quotation-api              3          1
exchange-api               2          1
portfolio / trading       15 / 11    12 / 8
pnl / risk                13 / 10    10 / 7
quotation / exchange       8 / 10     5 / 7
okx                       13         10
```

**Czego nie wyciągnąłem i dlaczego — to jest właściwa treść tego kroku.** `spring-boot-starter-webmvc`
(11/18), `spring-boot-starter-data-mongodb` (9/18), `spring-kafka` (8/18) i
`spring-boot-starter-classic` (11/18) **są informacją o module**: pom `vidulum-risk` pokazuje na
pierwszy rzut oka, że risk nie ma ani Mongo, ani Kafki — fakt ustalony w kroku 09 (ten moduł nic nie
przechowuje), który czytelnik weryfikuje w trzy sekundy. Po odziedziczeniu „czego ten moduł naprawdę
potrzebuje" wymagałoby `mvn dependency:tree`. W repozytorium, którego cała historia modularna polega
na jawnych granicach, to nie jest koszt stylistyczny.

Dwa drobiazgi z wykonania:

- **Lombok był deklarowany w dwóch wariantach** — `<optional>true</optional>` w dwunastu pomach i
  `<scope>provided</scope>" w trzech. Ujednolicony na wariant większościowy; oba są nietranzytywne, a
  że lombok i tak nie wchodzi do repakowanego jara, sprawdziłem przed i po: `unzip -l` na
  `vidulum-app` daje zero wpisów lomboka w obu przypadkach.
- **Trzy komentarze osierociały** po usunięciu zależności, które opisywały — w `vidulum-quotation-api`
  („FxRateTest exercises a contract type"), w `vidulum-shared-kernel` („First tests in this module…")
  i puste `<!-- Lombok -->` w `recurring-rules`. Zdjęte; komentarz bez swojej zależności jest
  gorszy niż jego brak, bo wygląda na opis czegoś, czego nie ma.

Grupy pozostają **czystymi agregatorami**, i to świadomie: dzięki temu przeniesienie modułu między
folderami nadal nie zmienia żadnej zależności ani żadnego `artifactId` — zaleta, którą dał krok 10 i
której nie warto tracić, gdy w `market/` wyląduje drugi adapter giełdy.

### Krok 12: markery, które mają konsumenta

Pytanie było o nawigację: „chcę oznaczyć `ModuleApi` jednym interfejsem, żeby wiedzieć, kto go
implementuje i gdzie jest używana implementacja". Odpowiedź wyszła z przeglądu: w modułach `-api`
leży **dziewięć interfejsów w trzech różnych rolach**, więc jeden marker zlałby trzy przeciwne
kierunki wywołania.

| rola | kto implementuje | marker | ile typów |
|---|---|---|---|
| **pytanie** („zapytaj mnie") | moduł-właściciel | `ModuleApi` | 6 |
| **gniazdo** („wepnij się we mnie") | ktoś z zewnątrz | `ModuleSpi` | 2 |
| **reakcja** („zareaguj na to, co ogłaszam") | konsument | `ModuleEventHandler` | 1 |
| **ładunek przez Kafkę** | — | `IntegrationEvent` | 7 |

Czwarty marker postawiony **obok `DomainEvent`**, bo różnica między nimi jest treścią obu: zdarzenie
domenowe to fakt wewnątrz agregatu, zapisywany przez `StoredDomainEvent`; zdarzenie integracyjne to
fakt opuszczający proces, serializowany na topic. `CashFlowEvent` jest jednym i drugim — marker
siedzi na tym, **co faktycznie jest serializowane**, czyli na kopercie `CashFlowUnifiedEvent`.

**Marker interfejsowy, nie adnotacja** — świadomie, bo celem jest nawigacja. `Ctrl+H` na `ModuleApi`
pokazuje marker → sześć kontraktów → ich implementacje, czyli oba pytania z pierwotnego pytania
jednym skrótem. „Find usages" adnotacji dałoby listę deklaracji, nie przejście do implementacji.
Precedens też jest w repo: `DomainEvent` to marker, który **nie jest dekoracją** — `Portfolio` trzyma
`List<DomainEvent> uncommittedEvents`, repozytoria zwracają `findDomainEvents`.

**I to jest warunek, który przesądził o kształcie kroku.** Marker spłaca się, gdy coś trzyma ten typ.
Trzech nowych nikt trzymać nie będzie, więc ich jedynym konsumentem może być **zadanie 01 (ArchUnit)**
— a lekcja z usuniętego `InMemoryPortfolioModuleApi` mówi, co się dzieje z abstrakcją bez
użytkownika. Dlatego markery i reguły weszły **jednym krokiem**, i dlatego 01 jest odhaczone tutaj.

#### Osiem reguł w `ModuleBoundariesArchTest`

Test mieszka w `vidulum-app`, bo **to jedyny moduł, który widzi wszystkie implementacje na
classpathzie** — co samo jest jedną ze sprawdzanych reguł.

| reguła | stan |
|---|---|
| kontrakt `ModuleApi` jest interfejsem w pakiecie `..api..` | zielona |
| implementacja `ModuleApi` nie jest publiczna | zielona, 3 odstępstwa |
| kontrakt nie zależy od Springa | zielona |
| implementacja `ModuleSpi` nie mieszka obok gniazda | zielona, 4 odstępstwa |
| `@KafkaListener` tylko w klasie implementującej `ModuleEventHandler` | zielona, 6 odstępstw |
| listener nie jest publiczny | zielona (wyprzedzająca) |
| `IntegrationEvent` jest rekordem | zielona (wyprzedzająca, 7 odstępstw) |
| **nikt nie sięga do `app`/`domain`/`infrastructure` innego modułu** | **zielona, zero odstępstw** |

Ostatni wiersz to rezultat ośmiu wcześniejszych kroków, teraz zamrożony: granica, której pilnował
`grep` w każdej z tych rozmów, pilnuje się sama. Przy okazji potwierdzone, że `vidulum-app`
**nie importuje z wnętrza żadnego modułu ani jednej klasy** — tylko kontrakty.

#### Co reguły znalazły od razu

- **`PortfolioAccess` był publiczny bez powodu.** Reguła „implementacja kontraktu nie jest
  publiczna" wskazała cztery klasy; przy trzech z nich okazało się, że publiczność jest potrzebna dla
  testu z innego artefaktu (`FxValuationRatesTest` buduje prawdziwe `FxRates`,
  `DenominationChainComponentTest` i `OkxQuotationComponentTest` prawdziwe `QuotationService`,
  `PreciousMetalsLifecycleComponentTest` wiąże `TradeConfirmedToPortfolio`). Czwarta —
  `PortfolioAccess` — miała **wszystkie wystąpienia poza artefaktem w javadocu**, więc jest już
  pakietowo-prywatna.
- **`UserFinancialProfileApi` nie jest tym samym gatunkiem kontraktu** i markera nie dostał. To
  deklaratywny interfejs HTTP (`@HttpExchange`, `@GetExchange`), bez zależności od shared-kernel,
  implementowany przez **publiczny** `@RestController`. Oznaczenie go wymagałoby dociągnięcia
  shared-kernela do kontraktu, który celowo go nie ma, i złamałoby dwie reguły naraz.
- **`TradeCapturedEvent` trzyma `TOPIC` i `CONTAINER_FACTORY` na samej klasie zdarzenia** — trzeci
  styl subskrypcji w repo, obok handlera i surowych stringów. Został na liście odstępstw.

#### Czego ten krok nie rozwiązał

Sześć z siedmiu listenerów nadal nazywa topic i fabrykę stringiem. Dwa z nich zatrzymują się nie na
czasie, a na granicy: **`user_created` i `cash_flow` nie mają kontraktu producenta** — pierwszy czeka
na `vidulum-user-api` (krok 05b), drugi na to, że cashflow nie ma żadnego `-api`. To jest właściwa
treść tych odstępstw: lista nie jest długiem technicznym do przeklikania, tylko miarą tego, ile
kontraktów jeszcze nie istnieje.

### Co zostało otwarte po 04

1. **`QuoteFeedListener`** — `@KafkaListener` nadal na metodzie `QuotationService`. Po wydzieleniu
   jest to już wyłącznie wewnętrzna sprawa quotation, więc nie blokuje niczego.
2. **`ExchangeStatusRestController`** odpowiada na `/exchange/status`, a mieszka w quotation.
   Konceptualnie należy do exchange; ruszenie go wciąga `exchange-api` w zakres.
3. **Providery Binance/PM/Degiro/Exante** zostały w `vidulum-quotation`, choć kształtem są
   identyczne z `OkxBrokerQuotationProvider`, który ma własny moduł. Wyjdą, gdy któraś giełda
   dostanie własny adapter.
4. **`FxRateSource`** nie wyszedł do kontraktu — nikt z zewnątrz go nie implementuje. Wyjdzie przy
   drugim źródle kursów spoza modułu.

---

*Wersje z diagramami (prywatne artefakty): [granice modułów](https://claude.ai/code/artifact/c907e225-fdb1-43b3-8a5a-e0d38503421b) ·
[droga trade'u](https://claude.ai/code/artifact/38ba3010-e999-485d-9716-9296ad662fb8)*
