# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build the project
./mvnw clean compile

# Run all tests (requires Docker for Testcontainers)
./mvnw test

# Run a single test class
./mvnw test -Dtest=CashFlowControllerTest

# Run a specific test method
./mvnw test -Dtest=CashFlowControllerTest#shouldCreateCashFlow

# Package the application
./mvnw package -DskipTests

# Run the application
./mvnw spring-boot:run
```

Note: Java 21 with preview features is required (`--enable-preview` is configured in pom.xml).

## Architecture Overview

Vidulum is a multi-portfolio financial application built with Spring Boot 3.2.4, MongoDB, and Kafka. It follows **Domain-Driven Design (DDD)** and **CQRS** (Command Query Responsibility Segregation) patterns.

### Package Structure

```
com.multi.vidulum/
├── cashflow/                    # Cash flow management (bank accounts, transactions)
├── cashflow_forecast_processor/ # Kafka event-driven forecast generation
├── portfolio/                   # Portfolio and asset management
├── trading/                     # Orders and trade execution
├── user/                        # User management
├── pnl/                         # Profit & Loss calculations
├── risk_management/             # Risk assessment (RAG status, stop-loss)
├── quotation/                   # Price quotations
├── task/                        # Task tracking
├── security/                    # JWT authentication & RBAC
├── common/                      # Shared value objects (Money, Ticker, etc.)
└── shared/                      # CQRS & DDD base infrastructure
```

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

Tests use **Testcontainers** for MongoDB and Kafka. Base class: `IntegrationTest` (in `trading.domain` package).

Key patterns:
- `@SpringBootTest` + `@Testcontainers` for integration tests
- `Awaitility.await()` for async Kafka processing assertions
- Helper methods: `createUser()`, `depositMoney()`, `placeOrder()`, `makeTrade()`

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

## Infrastructure

- **MongoDB**: Database `testDB`, host `mongodb:27017`
- **Kafka**: Broker `kafka:9092`, consumer group `group-id`
- **Docker**: `docker-compose-final.yml` for local development

## Docker Rebuild (Full Restart)

When the user asks to "restart Docker" or "rebuild Docker image", perform a **full clean rebuild**:

```bash
# 1. Build fresh Docker image
docker build -t vidulum-app:latest .

# 2. Stop and remove all containers
docker-compose -f docker-compose-final.yml down

# 3. Start fresh from scratch
docker-compose -f docker-compose-final.yml up -d
```

This ensures:
- New Docker image is built with latest code changes
- All containers (MongoDB, Kafka, app) are stopped and removed
- Fresh containers are started with clean state

## Sound Notification

After completing long-running operations (tests, builds, compilations), play a notification sound to alert the user:

```bash
afplay /System/Library/Sounds/Glass.aiff
```

Use this after:
- Running tests (`./mvnw test`)
- Building the project (`./mvnw clean compile`, `./mvnw package`)
- Any operation taking more than 20 seconds
