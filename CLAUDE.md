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

## Infrastructure

- **MongoDB**: Database `testDB`, host `mongodb:27017`
- **Kafka**: Broker `kafka:9092`, consumer group `group-id`
- **Docker**: `docker-compose-final.yml` for local development

## Sound Notification

After completing long-running operations (tests, builds, compilations), play a notification sound to alert the user:

```bash
afplay /System/Library/Sounds/Glass.aiff
```

Use this after:
- Running tests (`./mvnw test`)
- Building the project (`./mvnw clean compile`, `./mvnw package`)
- Any operation taking more than 20 seconds
