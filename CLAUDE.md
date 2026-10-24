# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**File size limit**: keep this file under 50KB — it is loaded into every session, and it currently
sits just under, so there is no room: **prefer editing an existing section over adding one**, pay
for an addition by trimming, and put walkthroughs, request bodies and diagrams in `docs/` with a
pointer from here. Check with `wc -c CLAUDE.md`; this line has claimed a stale number before.

## Before Manual Testing

Read "CSV Import Business Flow" for the endpoint flow and "REST API Endpoints Reference" for what
exists. **Do not invent an endpoint** — if it is not listed, read the controller.

## Build & Test Commands

**Build and test only the affected module, not the whole project.** The scope decision comes before
the command — guessing afterwards from a red log costs more than the analysis. Say which modules you
are running and why.

**`-pl` takes `:artifactId`, not a path.** Modules live under `market/` and `wealth/`, so
`-pl vidulum-portfolio` does not resolve — Maven looks for a directory of that name at the root.
A whole group builds with `-f vidulum-app-cashflow/pom.xml`, `-f market/pom.xml` or
`-f wealth/pom.xml`.

```bash
./mvnw compile -pl :vidulum-cashflow                        # one module, ~1-3s
./mvnw test -pl :vidulum-portfolio -Dtest=PortfolioTest     # one class
./mvnw test -pl :vidulum-cashflow -Dtest=CashFlowControllerTest#shouldCreateCashFlow
./mvnw install -pl :vidulum-shared-kernel -DskipTests        # then rebuild dependents
./mvnw clean test                                            # full reactor, ~7min, 21 modules
./mvnw package -DskipTests                                   # what a Docker rebuild needs
./mvnw spring-boot:run -pl :vidulum-app
```

Whole-module runs: cashflow ~5s (96) · portfolio ~10s (208) · trading ~1s (10) · pnl ~2s (31) ·
cashflow-tests ~4min (416) · wealth-tests ~1min (60) · app ~1min (49)

### Deciding the scope

```
shared-kernel ← cashflow
              ← market/ (quotation±api · exchange±api · okx)
                   ↑ *-api only
                wealth/ (portfolio±api · trading±api · pnl · risk) ← wealth-tests
                                                                  ← app
```

Contracts carry no Spring and no implementation. **Only `vidulum-app` depends on implementation
modules at `compile` scope** — everyone else compiles against contracts and pulls an implementation
in at `test` scope at most. Arrows point from a module to the ones that depend on it; run **what
changed plus everything downstream**, and nothing else:

| Changed | Run | Why |
|---------|-----|-----|
| `vidulum-portfolio` | `-pl :vidulum-portfolio` then `-pl :vidulum-wealth-tests,:vidulum-app` | the cross-module tests and the bootstrap use it |
| `vidulum-trading` | `-pl :vidulum-trading,:vidulum-wealth-tests,:vidulum-app` | same reason |
| `vidulum-pnl` / `vidulum-risk` | that module, then `-pl :vidulum-wealth-tests,:vidulum-app` | leaves: only the bootstrap and cross-module tests use them |
| `vidulum-wealth-tests` | `-pl :vidulum-wealth-tests` | **nothing depends on it** — tests only |
| `cashflow` | `-pl :vidulum-cashflow` | nothing depends on it except the bootstrap |
| `vidulum-bank-data-ingestion` | `-pl :vidulum-bank-data-ingestion` then `-pl :vidulum-cashflow,:vidulum-app` | 36 tests in seconds; the ones needing a running cash flow live in the parent |
| `vidulum-bank-data-adapter` | `-pl :vidulum-bank-data-adapter` then `-pl :vidulum-cashflow,:vidulum-app` | 111 tests, no Spring; the two needing a running cash flow live in the parent |
| `vidulum-exchange` | `-pl :vidulum-exchange,:vidulum-portfolio,:vidulum-wealth-tests,:vidulum-okx,:vidulum-app` | portfolio_spec uses connections |
| any `vidulum-*-api` | `install` it, then everything downstream of its implementations | the contract is the compile-time surface |
| several modules in one group | `-f market/pom.xml` or `-f wealth/pom.xml` | the aggregator's reactor; needs the other group installed |
| `vidulum-shared-kernel` | **full `./mvnw clean test`** | everything depends on it — no exceptions |

A downstream module resolves from the local repository, so `install` the changed module first.

Two traps:

- **`package -DskipTests` compiles every module but runs no tests** — not a full test run.
- **After reshaping a record or a signature, `clean` is mandatory.** Incremental compilation
  reported `BUILD SUCCESS` on eight constructor calls left one argument short; only
  `clean test-compile` found them.
- **Check `docker ps` before any Testcontainers run.** A `docker-compose` stack left running
  starves them, and the failures look exactly like regressions: registrations answering 500,
  `TimeoutException: Timed out waiting for a node assignment`, tests taking minutes. Take the stack
  down (`docker-compose -f docker-compose-final.yml down -v`) rather than debugging the code.

Java 21 with preview features (`--enable-preview`, configured in pom.xml).

## Architecture Overview

Vidulum is a multi-portfolio financial application built with Spring Boot 4.0.0, MongoDB, and Kafka. It follows **Domain-Driven Design (DDD)** and **CQRS** (Command Query Responsibility Segregation) patterns.

### Maven Module Structure

```
vidulum/
├── vidulum-shared-kernel/       # Common types, security, CQRS infrastructure
│   └── common/                  #   Money, Ticker, UserId, events, errors
│   └── security/                #   JWT auth, SecurityContextUserProvider
│   └── shared/                  #   DDD base classes, DataCleaner interface
│
├── vidulum-app-cashflow/        # AGGREGATOR: money moving in and out. pom-packaged grouping,
│   │                            #   NOT a parent - children inherit from the root pom, so moving
│   │                            #   one in or out changes no dependency and no artifactId
│   ├── cashflow-api/            #   CONTRACT: CashFlowModuleApi (skeletonOf, exists,
│   │                            #   createCategory, importHistorical, appendExpected,
│   │                            #   rollbackImport, alreadyImported), view/, error/ + forecast/:
│   │                            #   CashFlowForecastApi - a SECOND contract, answered by the
│   │                            #   read model, not the aggregate
│   ├── user-financial-profile-api/ # CONTRACT: UserFinancialProfileApi (@HttpExchange)
│   ├── cashflow/                #   artifactId vidulum-cashflow. THE AGGREGATE + its read model:
│   │                            #   the cashflow/ and
│   │                            #   cashflow_forecast_processor/. Together on purpose - 36 imports
│   │                            #   of CashFlowEvent, one lifecycle; the last assertSealed
│   │                            #   exemption. Unit/component tests only: 96 in 5s, no Docker
│   ├── user-financial-profile/  #   Owned accounts, self-transfer detection
│   ├── bank-data-ai-categorization-api/ # CONTRACT: TransactionCategorizationApi - suggestFor,
│   │                            #   matchLearned, learn, forget (F66)
│   ├── bank-data-ai-categorization/ # Engine + its memory of patterns. ONLY module with spring-ai
│   ├── bank-data-adapter/       #   AI CSV transformation; calls ingestion's endpoint by URL
│   ├── bank-data-ingestion/     #   Six sections, no app/domain/infrastructure (F67): canonical ·
│   │                            #   intake · staging · mappings · importing · classification.
│   │                            #   9 ArchUnit rules keep the directions, zero exemptions
│   └── recurring-rules/         #   Recurring rules, entirely through the contract - no HTTP client
│                                #   of its own left. No consumer here has an -api, nor a
│                                #   test-scope dep on another
│
├── market/                      # AGGREGATOR: prices and venues. Knows nothing about owners
│   ├── vidulum-quotation-api/   #   CONTRACT: QuotationApi, BrokerQuotationProvider, fx/
│   ├── vidulum-quotation/       #   Price quotations, broker providers, FX rates
│   ├── vidulum-exchange-api/    #   CONTRACT: ExchangeConnectionApi, spi/ExchangeAdapter
│   ├── vidulum-exchange/        #   ExchangeConnection, connect/reconnect commands, REST
│   └── vidulum-okx/             #   OKX adapter: implements both SPIs above
│
├── wealth/                      # AGGREGATOR: holdings, trades, valuation, risk
│   ├── vidulum-portfolio-api/   #   CONTRACT: PortfolioModuleApi, view/, error/
│   ├── vidulum-trading-api/     #   CONTRACT: TradingModuleApi, TradeConfirmedEvent + Handler
│   ├── vidulum-portfolio/       #   Assets, cost basis, locking + portfolio_spec (208 tests)
│   ├── vidulum-trading/         #   Orders, trades, execution (10 tests)
│   ├── vidulum-pnl/             #   Daily valuations, PortfolioValuation, wealth change (31)
│   └── vidulum-risk/            #   RAG status, stop-loss; stores nothing (no tests yet)
│
├── vidulum-cashflow-tests/      # No src/main. Every Spring test of the cashflow family - the
│                                #   aggregate's, the forecast processor's, and those of the three
│                                #   modules that left. They cannot live in those modules: each
│                                #   depends on contracts only, never on vidulum-cashflow, which
│                                #   is what keeps the reactor acyclic
│
├── vidulum-wealth-tests/        # No src/main. The tests that deliberately cross a module
│                                #   boundary, kept together to avoid a reactor cycle (60 tests)
│
└── vidulum-app/                 # Bootstrap & cross-module integration (32 tests)
    └── user/                    #   User registration, activation
    └── task/                    #   Task tracking
    └── config/                  #   Application configuration
    └── VidulumApplication       #   @SpringBootApplication entry point
```

**Dependency flow**: contracts, then implementations, then the bootstrap — no cycles. Reactor order
is computed from the dependencies, not the folders: shared-kernel → cashflow → market (quotation-api
→ quotation → exchange-api → exchange → okx) → wealth (portfolio-api → trading-api → portfolio →
trading → pnl → risk) → wealth-tests → app. The two aggregators are `pom`-packaged grouping only —
not parents, so nothing depends on which folder a module sits in.

The rules that hold it together:

- **A module never imports another module's internals** — only its `*-api` contract: `portfolio`
  sees `trading.api`, never `trading.app`.
- **`vidulum-wealth-tests` has no `src/main`.** It holds the tests that deliberately cross a
  boundary (`LockingAssetsTests`, `TradingPortfolioIntegrationTest`, the portfolio and
  portfolio_spec endpoint tests, and two more). Without it, portfolio's tests would need trading and
  vice versa — a reactor cycle.
- **`vidulum-pnl` and `vidulum-risk` have no `-api`**, because nothing asks them anything. The
  contract gets written when a consumer exists (see
  `docs/features-backlog/2026-10-15-alerts-module-design.md`).
- **The `market` → `wealth` edge is one-way, and checked.** Nothing under `market/` imports
  portfolio, trading, pnl or risk; `wealth/` reaches in through `*-api` only. Prices and venues do
  not know who owns what.
- **Contracts are marked, and the markers are checked.** `ModuleApi` (what another module may ask),
  `ModuleSpi` (a socket somebody plugs into), `ModuleEventHandler` (reacting to an announcement) and
  `IntegrationEvent` (a payload on a topic) live in shared-kernel's `shared/module` and
  `shared/ddd/event`. They carry no methods; their job is that `Ctrl+H` answers
  "what can be asked of which module" and that `ModuleBoundariesArchTest` has something to key on.
  **A new contract extends the right marker; a new `@KafkaListener` goes through a handler
  interface.** That test's three exemption lists measure the gap — they may only shrink.
- **`lombok` and the two test starters come from the root pom**, so a module's own pom lists only
  dependencies that say something about it: `spring-boot-starter-webmvc` = "has HTTP endpoints",
  `-data-mongodb` = "persists", `spring-kafka` = "speaks events", `spring-boot-starter-classic` =
  "runs inside Spring". That is why a `*-api` declares none of them and has exactly one dependency.
  Do not hoist those four.

Design and reasoning: `docs/features-backlog/2026-10-12-module-contracts-api-impl-split.md`.

### CQRS Pattern

Commands and queries are routed through gateways:
- **Commands**: `*/app/commands/{action}/{Name}Command` + `{Name}CommandHandler`
- **Queries**: `*/app/queries/{Name}Query` + `{Name}QueryHandler`
- Gateways (`CommandGateway`, `QueryGateway`) use reflection-based handler discovery

Example flow:
```java
commandGateway.send(new CreateCashFlowCommand(...));
queryGateway.send(new GetCashFlowQuery(cashFlowId));
```

**Both gateway beans come from `CqrsGatewayConfiguration` in shared-kernel — `@Import` it, never
redeclare them.** A gateway is built with **no dependencies**, and a `SmartInitializingSingleton`
registers handlers once the singletons exist. Collecting `List<CommandHandler>` inside the gateway's
own bean method closes a cycle — gateway → handler → contract → implementation → gateway — which
used to be papered over with `@Lazy` in eight places. `CqrsGatewayConfigurationTest` pins this, including a negative test that
asserts the old arrangement fails to start, so **a new `*ModuleApiImpl` needs no `@Lazy`**.

### A module contract may have two transports — in-process is always the default

`CashFlowModuleApi` has both: `CashFlowModuleApiImpl` (gateway and repository) and
`HttpCashFlowModuleApi` (REST against the endpoints a browser calls), chosen by
**`vidulum.cashflow.api.transport`** — `in-process` when unset. `BootstrapWiresEveryModuleTest`
asserts exactly one bean exists **and that it is not the HTTP one**.

- **`matchIfMissing = true` on an HTTP implementation is a trap.** The ingestion port had it on
  `vidulum.cashflow-service.enabled`, which nothing set — so the application created categories by
  calling its own REST endpoints over loopback, once per category, inside one JVM. Every
  integration test set it to `false` and substituted a double, which is why nobody noticed.
  **Default to the local path; opt in to HTTP.**
- **The HTTP adapter ships with the module that owns the endpoints.** It knows which URLs exist and
  that a field is called `category`, not `categoryName`. The arrangement it replaced had a consumer
  hand-rolling records for somebody else's API.
- **An implementation translates the contract's encoding into the domain's** — both transports
  must emit the same event. A null `parentCategoryName` means top-level, which the domain spells
  `CategoryName.NOT_DEFINED`. The controller converted, `CashFlowModuleApiImpl` forwarded the null,
  and `CategoryCreatedEventHandler`'s `equals` killed the **forecast projection** with an NPE —
  event dropped after nine retries, every write still 2xx. My own test asserted the pass-through
  (`isNull()`), so a green test pinned the defect: **assert what the domain is told**, not that an
  argument survived.
- **A test double standing in for a contract is a signal.** `createCategory` had three
  implementations: one HTTP, one dead, one gateway-based double 29 test classes used. The double did
  what the in-process one should have, so making the real one available deleted it and moved those
  29 classes onto production code.

### A rule two modules need is a value in the contract, not a method on it

`ImportWindow` in `cashflow-api` decides which dates a cash flow accepts an import for;
`CashFlowSkeletonView.importWindow()` hands it to a consumer, `CashFlowSnapshot.importWindow()`
builds it from the aggregate. One implementation, two callers.

**Why a value, not `canImport(date)`: a predicate asked per row is a request per row** — a 402-row
statement would pay 402. The skeleton carries all three inputs, so the consumer fetches once and
evaluates locally against the producer's logic, not a copy. It replaced two copies of five
conditions that had already diverged. When merging duplicated validation:

- **Preserve the check order.** A date can break two conditions at once and the first wins, which
  decides the `ErrorCode` an endpoint answers with. `CashFlowErrorHandlingTest` depends on it to the
  point of explaining in a comment why it cannot test `IMPORT_DATE_IN_FUTURE` in SETUP mode;
  `ImportWindowTest` pins it.
- **Not everything merges.** `ImportWindow` is the *necessary* condition, not the whole policy.
  Month statuses are stricter and owned by `cashflow_forecast_processor`, so they became
  `CashFlowForecastApi` + `MonthStatuses` in `cashflow.api.forecast` — **a second contract in the
  same artifact**, because a method on `CashFlowModuleApi` would have forced its implementation
  (which holds the domain repository) to open the first `cashflow` → `cashflow_forecast_processor`
  import. Staging asks the window first, the statuses only if it accepted — one bad date, one
  message. Two status enums on purpose, since a contract may not expose a read model's own type,
  kept in step by an exhaustive `switch` plus a test over `Status.values()`.

### Don't let a port invent a poorer type than both ends already use

The ingestion port declared `double` + `String` + `LocalDate` where both ends held `Money` and
`ZonedDateTime`; the client rebuilt the rest with `atStartOfDay(ZoneId.systemDefault())`. Measured,
because the halves differ:

- **Time of day and zone: lost outright.** The stored instant depended on the *server's* timezone,
  so one CSV gave different data per deployment — and near a month boundary that moves a transaction
  into another month, deciding validation and the forecast.
- **Amount: scale, not value.** `BigDecimal.valueOf(double)` uses the shortest round-tripping
  decimal, so `1234.50` came back `1234.5` — equal under `compareTo`, not `equals`. Value loss needs
  >15 significant digits, which corrected my own first analysis: a sabotage test with `1234.57`
  **passed**. **Pick the value that actually breaks before calling something a defect.**

### A consumer of an event declares the shape it reads

`UserFinancialProfileCashFlowListener.CashFlowCreated` is that module's own view of the `cash_flow`
payload: nested records with `@JsonIgnoreProperties`, naming **three** values out of a dozen. It
used to deserialise cashflow's **domain** class, so a consumer of a text payload needed the
producer's internals on its classpath. **A payload on a topic is text; read it with your own shape.**

**Validation belongs at each module's own door.** That module borrowed cashflow's
`BankAccountNumber`, which validates IBANs via iban4j — so its entity re-validated on every read,
and one stored IBAN iban4j would reject today made the whole profile unreadable. Storage
re-validating what it stored is a failure mode with no upside. It now has `OwnedAccountNumber` (a
carrier) plus a validating `fromIban` factory, because its own endpoint takes IBANs typed by a
person. Two locks on two doors is not a duplicated concept.

### Measure how a value crosses a boundary before giving it a module

`TransactionClassification` is declared twice - in `bank_data_adapter.domain` and in
`bank_data_ingestion.canonical` - deliberately. It briefly had a Maven module, which was wrong:
the two **never exchange the type**. The adapter appends a CSV column
(`",classification,classificationReason,location"`), ingestion parses it with `fromString`. The
contract is a column name and a vocabulary of words, so a shared Java type was a convenience
pretending to be a dependency - and `fromString` answering `UNKNOWN` for anything unrecognised was
the giveaway: only a text contract lets a producer know words a consumer does not.

**Duplication across a text boundary needs a test, not a module.**
`TransactionClassificationVocabularyTest` fails if either side gains a constant the other lacks,
because the tolerance that makes drift safe also makes it silent.

### Idempotency on an embedded array cannot use a unique index

Imported transactions carry `bankTransactionId`, and `CashFlow.findImported` makes re-uploading a
statement a no-op. **The guard is in the aggregate, not an index**: cash changes are an embedded
`List<CashChangeEntity>` inside one document, and MongoDB's unique constraint applies *across*
documents — a multikey unique index permits repeats inside one document's array, and `cashFlowId`
is unique per document anyway. The trade pattern (`@CompoundIndex` on `originTradeId`) works only
because `TradeEntity` is one document per trade. **Check the storage shape before reusing it.**
No storage-level guarantee though — that needs cash changes in their own collection. Concurrent
writes *are* covered now: `CashFlowEntity` carries a `@Version`, so a save is a conditional update
and the loser gets a 409 instead of overwriting in silence (F35); other aggregates have none (F57).

### One mapping path per field, or the field will exist in only one of them

Adding `bankTransactionId` broke idempotency silently at first: `CashChange.from(snapshot)` was
complete, but `CashFlow.from(snapshot)` held a **second, hand-written copy** of that mapping, so
everything loaded from storage read `null`. The test caught it; review would not have. When two
places rebuild one object, the duplicate is the bug waiting to happen.

### Domain Aggregates

Key aggregates with their events:
- **CashFlow**: Manages bank accounts and transactions. Events: `CashFlowCreatedEvent`, `CashChangeAppendedEvent`, `CashChangeConfirmedEvent`, etc.
- **Portfolio**: Holds assets with locking mechanism for orders
- **Order**: Trading orders (LIMIT, STOP, TARGET) with states (PENDING, FILLED, CANCELLED)
- **Trade**: Executed trade records with fee tracking

### Event Flow (Kafka)

```
Domain Aggregate → EventEmitter → Kafka (cash_flow topic) →
CashFlowEventListener → Event Handlers → CashFlowForecastProcessor → MongoDB
```

Event handlers in `cashflow_forecast_processor` module process events asynchronously.

### Repository Pattern

Two-layer repository structure:
1. **Domain repositories**: `DomainCashFlowRepository` (interface)
2. **MongoDB repositories**: `CashFlowMongoRepository` (Spring Data)

Aggregates use snapshot-based persistence with `fromSnapshot()` and `getSnapshot()` methods.

### Security

- JWT-based authentication with refresh tokens
- Roles: ADMIN, MANAGER
- Public endpoints: `/api/v1/auth/**`
- Protected endpoints use permission-based access (e.g., `ADMIN_READ`, `MANAGER_CREATE`)

## Testing

Tests use **Testcontainers** for MongoDB and Kafka. Each module has its own integration test base class:

| Module | Base class | Description |
|--------|-----------|-------------|
| vidulum-cashflow-tests | `CashFlowIntegrationTest` | Own `CashFlowTestApplication`, testcontainers, JWT helpers; wires cashflow, ingestion, adapter, profile and recurring-rules, so every cross-module Spring test in the cashflow family lives here |
| vidulum-wealth-tests | `WealthIntegrationTest` | Own `WealthTestApplication`, testcontainers, user stubs; wires portfolio, trading, pnl and risk, so every cross-module Spring test lives here |
| vidulum-cashflow, vidulum-bank-data-adapter, vidulum-bank-data-ingestion, vidulum-user-financial-profile, vidulum-portfolio, vidulum-trading, vidulum-pnl, vidulum-risk | *(none)* | Unit and component tests only — no Spring, no Docker, in-memory repositories. `cashflow` runs 91 of them in 5 seconds, having had ~500 and four minutes |
| vidulum-app | `AppIntegrationTest` | Full app context with real user domain |

Key patterns:
- `@SpringBootTest` + Testcontainers for integration tests
- `Awaitility.await()` for async Kafka processing assertions
- In-memory repository stubs for pure unit tests (e.g., `InMemoryCashFlowRepository`)

### Integration Test Guidelines

When writing integration tests, follow these rules:

1. **Use Actor pattern**: Create a dedicated `*Actor` or `*HttpActor` class that encapsulates all HTTP/REST interactions with the system under test. This separates test logic from API communication details. Example: `BankDataIngestionHttpActor`, `DualBudgetActor`.

2. **Whole object assertion**: Always validate entire returned objects using `usingRecursiveComparison()` instead of asserting individual fields.

   **Why**: When asserting individual fields, adding a new field to a model doesn't break any tests - you can forget to add assertions for the new field, leading to incomplete test coverage. With whole object comparison, any new field that doesn't match expected value will cause test failure, forcing you to explicitly handle it.
   ```java
   // GOOD - catches any new/changed fields automatically
   assertThat(actualTransaction)
           .usingRecursiveComparison()
           .isEqualTo(expectedTransaction);

   // BAD - new fields go unnoticed, incomplete coverage
   assertThat(actual.getName()).isEqualTo("expected");
   assertThat(actual.getAmount()).isEqualTo(100);
   // forgot to check actual.getNewField()!
   ```

3. **Minimize ignored fields**: Avoid ignoring fields in assertions. Time fields (`created`, `lastModification`) should be validated using `FixedClockConfig` which sets clock to `2022-01-01T00:00:00Z`. Use `ZonedDateTime.parse()` for readability.
   ```java
   private static final ZonedDateTime FIXED_NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z[UTC]");
   ```

4. **Use constructors, not builders for expected objects**: When creating expected objects in tests, always use all-args constructors instead of builders. This ensures that when a field is added to a model, tests will fail to compile until updated - preventing forgotten assertions.
   ```java
   // GOOD - compile-time safety, fails if model changes
   new CashChangeSummaryJson(id, name, description, money, type, category, status, created, dueDate, endDate)

   // BAD - builder silently ignores new fields
   CashChangeSummaryJson.builder().name(name).build()
   ```
   Add `@AllArgsConstructor` to DTO classes if missing to enable this pattern.

5. **Complex test scenarios**: Write elaborate test scenarios that cover multiple aspects:
   - Multiple entities (transactions, categories, subcategories)
   - Multiple time periods (months)
   - Category hierarchies (parent/child relationships)
   - Different types (INFLOW/OUTFLOW)
   - Edge cases and boundary conditions

6. **Test naming**: Use descriptive method names that explain the scenario, e.g., `shouldImportHistoricalTransactionViaRestApi`, `shouldRejectStagingWithUnmappedCategories`.

7. **Reference tests**: See `BankDataIngestionHttpIntegrationTest` and `DualCashflowStatementGeneratorWithHistory` for examples of well-structured integration tests.

### Test Levels

Three levels, each with a distinct job. Put an assertion at the cheapest level that can make it.

| level | how it runs | what belongs there | examples |
|-------|-------------|--------------------|----------|
| **unit** | no Spring, no collaborators | a single aggregate's rules, value-object parsing | `ExchangeConnectionTest`, `ReportedKeyPermissionsTest` |
| **component** | no Spring, real collaborators + `InMemory*Repository` | whole use cases including the entity round trip and DTO mapping; exhaustive whole-object assertions | `PortfolioTest`, `ExchangeConnectionComponentTest`, `ExchangeConnectionControllerComponentTest` |
| **integration** | `@SpringBootTest` + Testcontainers + an `*HttpActor` | what only a running server shows: HTTP status codes, serialisation, indexes, Kafka | `CashFlowControllerTest`, `ExchangeConnectionEndpointTest` |

A component test may drive a `@RestController`'s methods directly — construct it with the real
service and an in-memory repository. That covers request → domain → storage → response in
milliseconds, so the slow integration test can stay focused on HTTP concerns instead of
duplicating payload assertions.

## Infrastructure

- **MongoDB**: Database `testDB`, host `mongodb:27017`
- **Kafka**: Broker `kafka:9092`, consumer group `group-id`
- **Docker**: `docker-compose-final.yml` for local development

## Development Status

**Under active development, no production users.** So: no data migrations, breaking changes are
fine, and when in doubt wipe the data. Don't write migration scripts.

### MongoDB entities, cleaners and indexes

**A new `@Document` entity MUST be added to its subdomain's `DataCleaner`** — `VidulumApplication`
collects them via `List<DataCleaner>`. Add `mongoTemplate.remove(new Query(), NewEntity.class);`.

**Empty the collection, never drop it.** `dropCollection` takes the indexes with it, and the
cleaners run moments after startup created them from the annotations — so every index lived for a
fraction of a second, and the first write recreated the collection bare. The uniqueness task F1
depends on survived the tests and not the container. The rule is on the `DataCleaner` interface.

**Indexes need switching on.** `@Indexed` and `@CompoundIndex` create nothing by default — Boot has
defaulted it to `false` since 3.0, so 28 annotations here were decoration until F1. Enabled in
`vidulum-app/src/main/resources/application.yml` under
**`spring.data.mongodb.auto-index-creation`** — note the key: Boot 4 moved connection properties to
`spring.mongodb` but not this one, and under the wrong key it fails silently. A test asserting an
annotation exists proves nothing; assert the index is in the database
(`mongoTemplate.indexOps(X.class).getIndexInfo()`), as `TradeUniquenessIndexTest` does.

| Module (subdomain) | Cleaner | Collections |
|--------|-------|-------------|
| cashflow | `CashFlowDataCleaner` | CashFlowEntity |
| cashflow_forecast_processor | `CashFlowForecastDataCleaner` | CashFlowForecastEntity, CashFlowForecastStatementEntity |
| ingestion `staging` | `StagingDataCleaner` | StagingSessionEntity, StagedTransactionEntity |
| ingestion `mappings` | `CategoryMappingDataCleaner` | CategoryMappingEntity |
| ingestion `importing` | `ImportJobDataCleaner` | ImportJobEntity |
| bank_data_ai_categorization | `AiCategorizationDataCleaner` | PatternMappingEntity |
| bank_data_adapter | `BankDataAdapterDataCleaner` | AiCsvTransformationDocument, MappingRules |
| user_financial_profile | `UserFinancialProfileDataCleaner` | UserFinancialProfileEntity |
| vidulum-recurring-rules | `RecurringRuleDataCleaner` | RecurringRuleEntity |
| portfolio | `PortfolioDataCleaner` | PortfolioEntity |
| portfolio_spec | `PortfolioSpecDataCleaner` | PortfolioSpecEntity |
| vidulum-trading | `TradingDataCleaner` | OrderEntity, TradeEntity |
| vidulum-pnl | `PnlDataCleaner` | PnlHistoryEntity |
| vidulum-exchange | `ExchangeConnectionDataCleaner` | ExchangeConnectionEntity |
| vidulum-app | `CoreDataCleaner` | Token, UserEntity, TaskEntity |

**One cleaner per subdomain, not per Maven module** — the subdomain owning an entity notices a new
one, and the split survives extraction into its own artifact. `vidulum-quotation` and `vidulum-risk`
have none because they persist nothing. For `bank_data_adapter` this is not cosmetic: a
transformation is a **cache of an AI answer**, so a collection left behind makes a run reuse a
previous model's output and look deterministic when it is not.

## Docker Rebuild (Full Restart)

When the user asks to "restart Docker" or "rebuild Docker image", **ALWAYS** perform a full clean rebuild with volume cleanup:

```bash
# 1. Package the application (create JAR)
# IMPORTANT: `clean`, not a bare `package` - without it the reactor can reuse a stale module jar
# and the image ships code you already replaced. Verify before shipping:
#   unzip -p vidulum-app/target/*.jar BOOT-INF/lib/vidulum-portfolio-*.jar > /tmp/m.jar
#   unzip -p /tmp/m.jar <YourClass>.class | grep -ac <yourNewMethod>
./mvnw clean package -DskipTests

# 2. Build fresh Docker image WITHOUT CACHE
# IMPORTANT: Always use --no-cache to ensure latest JAR is used
# IMPORTANT: Always use vidulum-app:latest (NOT vidulum:latest)
docker build --no-cache -t vidulum-app:latest .

# 3. Stop and remove all containers AND VOLUMES (clean slate)
# The -v flag removes all volumes (MongoDB data, Kafka data, etc.)
docker-compose -f docker-compose-final.yml down -v

# 4. Start fresh from scratch
docker-compose -f docker-compose-final.yml up -d
```

All four steps matter, and the two flags are the ones people skip:

- **`--no-cache`**, or the image ships code you already replaced.
- **`-v`**, which wipes Mongo, Kafka **and the cached AI transformations** — without it a run
  reuses a previous model's answer and looks deterministic when it is not. There are no production
  users (see Development Status), so a clean slate is always the right call.
- **Tag `vidulum-app:latest`.** `vidulum:latest` is a dead name; `docker-compose-final.yml` expects
  the former.

## Sound Notification

After anything taking more than ~20 seconds — a test run, a build, a package — ring the bell:

```bash
afplay /System/Library/Sounds/Glass.aiff
```

## Infrastructure Versions & Support Dates

| Component | Version | Support Until | Notes |
|-----------|---------|---------------|-------|
| **Java** | 21 LTS | Sep 2031 (extended) | Free NFTC license until Sep 2026 |
| **Spring Boot** | 4.0.0 | ~Nov 2027 | Active development |
| **MongoDB** | 8.0 | Oct 2029 | 5-year extended lifecycle |
| **Kafka (Confluent)** | 7.8.1 | Dec 2026 (standard) | Platinum until Dec 2027 |
| **Testcontainers** | 1.20.4 | Active | Follows container versions |

### Docker Images Used
```yaml
# docker-compose-final.yml
mongo:8.0                      # MongoDB with replica set
confluentinc/cp-kafka:7.8.1    # Kafka in KRaft mode (no Zookeeper)
obsidiandynamics/kafdrop:latest # Kafka UI
vidulum-app:latest             # Application image
```

## Documentation Structure

Where new docs go: feature designs → `docs/features-backlog/` (`VID-XXX-name.md` or
`YYYY-MM-DD-name.md`) · market/cost research → `docs/business-analysis/` · test reports and
evidence → `docs/evidence/` (periodically cleaned) · manual test scripts → `docs/manual-testing/` ·
UI mockups → `docs/design/` · reference docs → repo root or `docs/` root.

| File | Description |
|------|-------------|
| `AUTHENTICATION.md` | JWT dual-token system, security assessment |
| `README.md` / `README-DOCKER.md` / `CHANGELOG.md` | overview, Docker deployment, version history |
| `docs/BANK_DATA_INGESTION_GUIDE.md` | bank import API guide |
| `docs/bank-data-ingestion-pipeline.md` | import pipeline architecture |
| `docs/historical-import-user-guide.md` | user guide for CSV import |
| `docs/PERFORMANCE_TESTING_GUIDE.md` | load testing tools, VPS sizing, monitoring |
| `docs/FEATURES_BACKLOG_DETAILED.md` | detailed description of all unimplemented features |
| `docs/manual-testing/CASHFLOW_IMPORT_TEST_GUIDE.md` | **manual CSV import walkthrough** |
| `tools/task-board/refresh.py` | regenerates a task board's derived sections and validates its dependency graph |

## Manual Testing - CSV Import Flow

See **`docs/manual-testing/CASHFLOW_IMPORT_TEST_GUIDE.md`** for the full walkthrough; the
consistency rules and the error table are under "CSV Import Business Flow" above. Start from the
clean slate the Docker rebuild above gives you — `down -v` is the part that matters.

## Error Handling Guidelines

**IMPORTANT**: every business exception MUST be handled by `ErrorHttpHandler` with an `ApiError`.

### How to Add New Exception Handling

1. Create custom exception class in domain package
2. Add `@ExceptionHandler` in `ErrorHttpHandler.java`
3. Define `ErrorCode` in `ErrorCode.java` enum
4. Return proper `ApiError` response

```java
// In ErrorHttpHandler.java
@ExceptionHandler(MyNewException.class)
public ResponseEntity<ApiError> handleMyException(MyNewException ex) {
    log.debug("My exception: {}", ex.getMessage());
    ApiError error = ApiError.of(ErrorCode.MY_ERROR_CODE, ex.getMessage());
    return ResponseEntity.status(error.httpStatus()).body(error);
}
```

**One failure, one exception.** "No such cash flow" was three classes: one in cashflow's domain
(41 usages), one nested in `bank_data_ingestion`'s port extending `RuntimeException`, and one in
recurring-rules. Only `cashflow/api/error/CashFlowNotFoundException`
remains — in the contract, so a consumer catches it without importing anyone's domain. The nested
one was the dangerous shape: a `RuntimeException` with no `ErrorCode`, which `ErrorHttpHandler` does
not map, so every call site had to translate it and two that forgot answered **500 instead of 404**.
If you add a failure that another module can see, put it in that module's `*-api` `error/` package
and give it an `ErrorCode`.

**The same shape appeared twice, so treat the pattern as the warning sign.** "Category already
exists" was also two classes — a `BusinessException` in cashflow's domain and a nested
`RuntimeException` in the same port, translated at four call sites. Only
`cashflow/api/error/CategoryAlreadyExistsException` remains. A nested exception inside a client port
is almost always this bug: a consumer invented a name for the producer's failure, so the `ErrorCode`
the producer already had never reaches anyone.

### Key Classes
- `ErrorHttpHandler` - `security/config/ErrorHttpHandler.java` - central exception handler
- `ApiError` - `common/error/ApiError.java` - error response format
- `ErrorCode` - `common/error/ErrorCode.java` - all error codes with HTTP statuses

## CSV Import Business Flow

**Walkthrough, request bodies, the flow diagram and the staging state machine:
`docs/manual-testing/CASHFLOW_IMPORT_TEST_GUIDE.md`.** Here stays what is easy to get wrong.

Eleven steps: register → `POST /cash-flow/with-history` → AI transform CSV → import to staging →
AI categorize → accept-ai (or `force-uncategorized`) → check staging → start import →
`attest-historical-import` → verify cash flow → verify forecast. Use only the endpoints below.

### Test data must be consistent

| | |
|---|---|
| test CSV | `nestbank_lista_operacji_20260111.csv` in vidulum-cashflow-tests — raw Nest Bank, 402 rows, PLN, 2023-01-13 … 2025-12-31. Canonical 17-column fixtures (Nest, Pekao) sit in `resources/canonical/`; its README explains how they were made |
| `startPeriod` | **the month of the OLDEST row**, so `2023-01`. Check `suggestedStartPeriod` in the AI transform response |
| `initialBalance` | `863.94 PLN` for that file |
| `confirmedBalance` at attestation | the last row's "Saldo po operacji" — `76047.25 PLN` |
| IBAN | `PL61109010140000071219812874`. Validation is strict: `PL` + 2 check digits + 22 digits |

**Why `startPeriod` matters:** a cash flow creates historical months from `startPeriod` to now, and
a transaction dated before it — or in the future — is rejected.

### Staging session states

`HAS_UNMAPPED_CATEGORIES` → (`ai-categorize` → `AI_SUGGESTIONS_READY` → `accept-ai`) or manual
mappings or `force-uncategorized` → `revalidate` → `READY_FOR_IMPORT` → `IMPORTING` → `COMPLETED`.
Sessions expire after 24h.

### Common errors

| error | cause | fix |
|-------|-------|-----|
| `IMPORT_DATE_BEFORE_START` | transaction date < `startPeriod` | use an earlier `startPeriod` |
| `IMPORT_DATE_IN_FUTURE` | transaction date > today | check the CSV dates |
| `INVALID_BANK_ACCOUNT` | bad IBAN | use the one above |
| `CASHFLOW_NOT_FOUND` | wrong id format | `CF_ID` starts with `CF`, e.g. `CF10000001` |
| `INGESTION_STAGING_NOT_FOUND` | session expired or wrong id | sessions live 24h |
| `CASHFLOW_BALANCE_MISMATCH` | wrong `confirmedBalance` | take the CSV's last "Saldo po operacji" |

Two gotchas: **`paidDate` is required** in staged transactions, and `MappingAction` is `CREATE_NEW`
/ `CREATE_SUBCATEGORY` / `MAP_TO_UNCATEGORIZED` — there is no `MAP_TO_EXISTING`.

### Alternative: skip the AI transform

`POST /api/v1/bank-data-ingestion/cf={CF_ID}/upload` with a multipart file already in canonical
format: `date,amount,currency,type,bankCategory,name,description,merchant`.

## REST API Endpoints Reference

**IMPORTANT**: When testing manually, ONLY use endpoints listed below. DO NOT invent or guess endpoint paths!

### Authentication (`/api/v1/auth`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/auth/register` | Register new user → returns `access_token`, `user_id` |
| POST | `/api/v1/auth/authenticate` | Login → returns `access_token` |
| POST | `/api/v1/auth/refresh-token` | Refresh JWT token |
| POST | `/api/v1/auth/logout` | Logout current session |
| POST | `/api/v1/auth/logout-all` | Logout all sessions |

### CashFlow (`/cash-flow`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/cash-flow` | Create CashFlow (simple) |
| POST | `/cash-flow/with-history` | Create CashFlow with historical periods → returns `CF_ID` |
| GET | `/cash-flow/cf={cashFlowId}` | Get CashFlow details |
| GET | `/cash-flow?userId={userId}` | List user's CashFlows (query param, NOT path!) |
| POST | `/cash-flow/cf={cashFlowId}/category` | Add category to CashFlow |
| POST | `/cash-flow/cf={cashFlowId}/attest-historical-import` | Attest historical import (activates CashFlow) |
| POST | `/cash-flow/cf={cashFlowId}/expected-cash-changes/batch` | Append many expected cash changes atomically → returns their ids |
| GET | `/cash-flow/cf={cashFlowId}/cash-changes/status?sourceRuleId=` | Count a rule's cash changes by state |
| POST | `/cash-flow/cf={cashFlowId}/rollover` | Rollover to next period |
| DELETE | `/cash-flow/cf={cashFlowId}/import` | Rollback historical import |

### CashFlow Forecast (`/cash-flow-forecast`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/cash-flow-forecast/cf={cashFlowId}` | Get forecast for CashFlow |
| GET | `/cash-flow-forecast/cf={cashFlowId}/month-statuses` | Get month statuses |

### AI Bank CSV Adapter (`/api/v1/bank-data-adapter`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/bank-data-adapter/transform` | AI transform CSV → returns `transformationId` |
| GET | `/api/v1/bank-data-adapter/{transformationId}` | Get transformation details |
| GET | `/api/v1/bank-data-adapter/{transformationId}/preview` | Preview transformed data |
| GET | `/api/v1/bank-data-adapter/{transformationId}/download` | Download transformed CSV |
| POST | `/api/v1/bank-data-adapter/{transformationId}/import` | Import to CashFlow (creates staging) |
| GET | `/api/v1/bank-data-adapter/history` | List transformation history |

### Bank Data Ingestion (`/api/v1/bank-data-ingestion/cf={cashFlowId}`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/bank-data-ingestion/cf={cfId}/staging` | Create staging session from transformation |
| GET | `/api/v1/bank-data-ingestion/cf={cfId}/staging` | List staging sessions |
| GET | `/api/v1/bank-data-ingestion/cf={cfId}/staging/{sessionId}` | Get staging session details |
| DELETE | `/api/v1/bank-data-ingestion/cf={cfId}/staging/{sessionId}` | Delete staging session |
| POST | `/api/v1/bank-data-ingestion/cf={cfId}/staging/{sessionId}/revalidate` | Revalidate after mappings |
| POST | `/api/v1/bank-data-ingestion/cf={cfId}/staging/{sessionId}/ai-categorize` | AI categorization suggestions |
| POST | `/api/v1/bank-data-ingestion/cf={cfId}/staging/{sessionId}/accept-ai` | Accept AI suggestions |
| POST | `/api/v1/bank-data-ingestion/cf={cfId}/staging/{sessionId}/force-uncategorized` | Force unmapped to Uncategorized |
| POST | `/api/v1/bank-data-ingestion/cf={cfId}/upload` | Upload CSV directly (multipart) |
| POST | `/api/v1/bank-data-ingestion/cf={cfId}/mappings` | Create category mappings |
| GET | `/api/v1/bank-data-ingestion/cf={cfId}/mappings` | List category mappings |
| DELETE | `/api/v1/bank-data-ingestion/cf={cfId}/mappings/{mappingId}` | Delete mapping |
| POST | `/api/v1/bank-data-ingestion/cf={cfId}/import` | Start import job |
| GET | `/api/v1/bank-data-ingestion/cf={cfId}/import/{jobId}` | Get import job status |
| GET | `/api/v1/bank-data-ingestion/cf={cfId}/import` | List import jobs |

### Exchange Connection (`/exchange-connection`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/exchange-connection` | Register an exchange account → returns connection with `PENDING` status |
| GET | `/exchange-connection` | List caller's connections + `supportedExchanges` |
| GET | `/exchange-connection/{connectionId}` | Get one connection's state |
| POST | `/exchange-connection/{connectionId}/revoke` | Mark a connection as disconnected; the portfolio stays |
| POST | `/exchange-connection/{connectionId}/reconnect` | Resume a `REVOKED` connection, keeping its portfolio |

Notes:
- The user id comes from the JWT, never from the body.
- Another user's connection answers 404, not 403.
- API keys are never sent or stored; `reportedKeyPermissions` must be `read_only`.

### Exchange Status (`/exchange`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/exchange/status` | Readiness of every registered exchange |
| GET | `/exchange/{name}/status` | Readiness of one; answers for unknown exchanges instead of 404 |

Notes:
- `brokerRegistered` and `quotesReady` are live; `reachability` is always `UNKNOWN` — nothing
  probes the exchange yet.
- Not the same question as `GET /exchange-connection/{id}`: that one is per-user.
- A quote of an asset against itself (`EUR/EUR`) is computed as 1 and never needs publishing.

### Portfolio (`/portfolio`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/portfolio` | Open an empty portfolio |
| GET | `/portfolio/{id}/{currency}` | Read it, valued in that currency; every row says which position it is (`subName`) and whether it is `awaitingCost` |
| POST | `/portfolio/deposit` / `/portfolio/withdraw` | Money in and out; both write the contribution ledger |
| POST | `/portfolio/asset/cost` | The owner states what a position cost (task C8) — recorded as `USER_PROVIDED`, which no synchronisation overwrites silently |
| POST | `/portfolio/asset/lock` / `/portfolio/asset/unlock` | Reserve units against one of our own orders |
| GET | `/aggregated-portfolio/{currency}` | The caller's holdings across portfolios |

Notes:
- `POST /trades` answers with the stored trade. Sending the same `originTradeId` twice records one
  trade and returns it again (task F1); omitting it has the backend mint one, which keeps the
  index valid but protects nobody — idempotency comes from the caller's key.

### Portfolio Specification (`/portfolio-spec`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/portfolio-spec` | Compute `snapshot - known state` → returns the differences and any questions |
| GET | `/portfolio-spec/{specId}` | What still has to be decided |
| PUT | `/portfolio-spec/{specId}/answers` | Record answers, anchored to the batch they were given for |
| POST | `/portfolio-spec/{specId}/confirm` | Create the portfolio and put the connection into service |

Notes:
- The snapshot travels in the request body; the backend holds no exchange credentials.
- `confirm` always compares against a fresh snapshot and refuses if the exchange moved on.
- Broker and valuation currency come from the connection; the request may confirm them, not set them.

### Recurring Rules (`/api/v1/recurring-rules`)
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/recurring-rules` | Create recurring rule |
| GET | `/api/v1/recurring-rules/{ruleId}` | Get rule by ID |
| GET | `/api/v1/recurring-rules/cash-flow/{cashFlowId}` | Get rules for CashFlow |
| GET | `/api/v1/recurring-rules/me` | Get current user's rules |
| GET | `/api/v1/recurring-rules/me/dashboard` | Dashboard with cashFlowId param |
| PUT | `/api/v1/recurring-rules/{ruleId}` | Update rule |
| DELETE | `/api/v1/recurring-rules/{ruleId}` | Delete rule |

### Common Patterns
```bash
# Path variables use = not /
/cash-flow/cf={cashFlowId}           # CORRECT
/cash-flow/cf/{cashFlowId}           # WRONG!

# Query params for filtering
/cash-flow?userId={userId}           # CORRECT (query param)
/cash-flow/user={userId}             # ALSO CORRECT (path style)
/cash-flow/user/{userId}             # WRONG!

# Nested resources
/api/v1/bank-data-ingestion/cf={cfId}/staging/{sessionId}/ai-categorize  # CORRECT
```

### Features Backlog
Designs in `docs/features-backlog/`. **Not all of them are waiting** — several describe work that
has since shipped and are kept as the record of why it looks the way it does, so check the Status
column before planning against one.

| File | Feature | Status | Priority |
|------|---------|--------|----------|
| **`ingestion-v2-tasks.md`** | **Ingestion v2 (`data-ingestion-conductor`): saga, small modules, 2 user requests instead of 7.** The board is the source of truth for statuses and links the design, the specs and the boundary audit | open — 3/67 | High |
| `2026-10-13-fee-in-cost-basis.md` | Trading fees must reach the portfolio: cost basis, realised result, and a fee paid in a third asset | open | High |
| `2026-10-15-alerts-module-design.md` | Alerts module: who decides something is an alert — `vidulum-alerts` as a leaf consumer of domain facts | open | Medium |
| `2026-02-14-business-analysis-alerts-cashchange-lifecycle.md` | Alerts system — business analysis + `CashChange` lifecycle; its section 7 design is superseded by the file above | open | Medium |
| `2026-02-07-intelligent-cashflow-reconciliation.md` | Smart reconciliation | open | Medium |
| `AI_CATEGORIZATION_PLAN.md` | AI-powered transaction categorization | open | Medium |
| `AI_USE_CASES.md` | AI use cases overview | open | Medium |
| `TODO-kafka-dead-letter-queue.md` | Dead letter queue for failed events | open | Medium |
| `2026-02-08-canonical-csv-architecture.md` | Unified CSV format | open | Low |
| `2026-10-12-module-contracts-api-impl-split.md` | Module contracts: `-api` / impl split (NPay pattern) | **in progress** — its own migration table is the live list | Medium |
| `VID-103-maven-multi-module-migration.md` | Split into Maven modules | **partly done** — the Maven split shipped; the separate Docker images it was aiming at did not | Low |
| `2026-02-08-month-rollover-ongoing-sync-design.md` | Month rollover sync | ✅ done 2026-02-25 | — |
| `2026-02-14-recurring-rule-engine-design.md` | Recurring transactions | ✅ done ~98% 2026-03-09 | — |

### Business Analysis
Files in `docs/business-analysis/`:
- Open Banking providers comparison
- Kontomatik cost analysis
- B2B market analysis
- Business model analysis
- Bank integration design
