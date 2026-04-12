# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**File size limit**: Keep this file under 50KB for optimal loading. Current: ~30KB.

## Before Manual Testing

**IMPORTANT**: Before performing manual API testing:
1. Read the "CSV Import Business Flow" section below for complete endpoint flow
2. Read the "REST API Endpoints Reference" section for all valid endpoints
3. **DO NOT invent endpoints** - only use documented ones
4. If unsure about an endpoint, check the controller source code first

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

Vidulum is a multi-portfolio financial application built with Spring Boot 4.0.0, MongoDB, and Kafka. It follows **Domain-Driven Design (DDD)** and **CQRS** (Command Query Responsibility Segregation) patterns.

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

## Development Status

**This application is under active development.** There are no production users yet.

Implications:
- **No data migrations required** - we can freely change database schemas, collection structures, etc.
- **No backwards compatibility concerns** - breaking changes are acceptable
- **Clean slate approach** - when in doubt, wipe data and start fresh
- **Focus on features, not migrations** - don't waste time on migration scripts

### MongoDB Entity Clearing at Startup

**IMPORTANT**: When creating a new MongoDB entity (class with `@Document`), you MUST add it to `VidulumApplication.clearData()` method!

The `clearData()` method clears all MongoDB collections on application startup to ensure a clean state for development. If you forget to add a new entity, stale data from previous runs may cause issues.

**Checklist for new entity:**
1. Create entity class with `@Document("collection_name")` annotation
2. Add `mongoTemplate.dropCollection(NewEntity.class);` to `VidulumApplication.clearData()`
3. Add appropriate import statement

**Currently cleared collections:**
```java
// Security & User
Token, UserEntity

// Portfolio & Trading
PortfolioEntity, TradeEntity, OrderEntity

// CashFlow
CashFlowEntity, CashFlowForecastEntity, CashFlowForecastStatementEntity

// Bank Data Ingestion
StagedTransactionEntity, CategoryMappingEntity, ImportJobEntity, PatternMappingEntity

// Bank Data Adapter (AI CSV Transformation)
AiCsvTransformationDocument, MappingRules

// Recurring Rules
RecurringRuleEntity

// Other
TaskEntity, PnlHistoryEntity
```

## Docker Rebuild (Full Restart)

When the user asks to "restart Docker" or "rebuild Docker image", **ALWAYS** perform a full clean rebuild with volume cleanup:

```bash
# 1. Package the application (create JAR)
./mvnw package -DskipTests

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

**IMPORTANT - Docker Build Rules:**
- **ALWAYS use `--no-cache`** when building Docker image - ensures latest code is used
- **ALWAYS use `-v` flag** when stopping containers - removes all volumes for clean slate
- Always build with tag `vidulum-app:latest`
- NEVER use `vidulum:latest` - this is an old deprecated name
- The `docker-compose-final.yml` expects `vidulum-app:latest`

**Why clean volumes every time?**
- Application is under development - no production data to preserve
- Eliminates issues with stale data, cached AI transformations, old schemas
- Ensures reproducible testing environment
- Avoids "works on my machine" issues caused by leftover data

This ensures:
- New Docker image is built with latest code changes (no cache)
- All data is wiped (MongoDB, Kafka, AI cache)
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

## Documentation Structure & Guidelines

### Folder Organization
```
docs/
├── business-analysis/     # Business requirements, cost analysis, market research
├── features-backlog/      # Designs for UNIMPLEMENTED features (TODO)
├── design/                # UI mockups, visual assets
├── evidence/              # Test reports, validation evidence (can be cleaned)
└── manual-testing/        # Manual test scripts (can be cleaned)
```

### Documentation Guidelines
When creating new documentation:

1. **Current/Reference docs** → Place in project root or `docs/` root
   - README, CHANGELOG, ARCHITECTURE, API guides

2. **Feature designs (not yet implemented)** → `docs/features-backlog/`
   - Use naming: `VID-XXX-feature-name.md` or `YYYY-MM-DD-feature-name.md`
   - These are TODO items waiting for implementation

3. **Business analysis** → `docs/business-analysis/`
   - Market research, cost analysis, requirements gathering

4. **Evidence/Reports** → `docs/evidence/`
   - Test reports, migration validations, manual test results
   - This folder can be periodically cleaned

5. **UI Mockups** → `docs/design/`
   - HTML mockups, wireframes, visual designs

### Current Documentation
| File | Description |
|------|-------------|
| `AUTHENTICATION.md` | JWT dual-token system, security assessment |
| `README.md` | Project overview |
| `README-DOCKER.md` | Docker deployment instructions |
| `CHANGELOG.md` | Version history |
| `docs/BANK_DATA_INGESTION_GUIDE.md` | Bank import API guide |
| `docs/bank-data-ingestion-pipeline.md` | Import pipeline architecture |
| `docs/historical-import-user-guide.md` | User guide for CSV import |
| `docs/PERFORMANCE_TESTING_GUIDE.md` | Load testing tools, VPS sizing, monitoring |
| `docs/FEATURES_BACKLOG_DETAILED.md` | Detailed description of all unimplemented features |
| `docs/manual-testing/CASHFLOW_IMPORT_TEST_GUIDE.md` | **Manual testing guide for CSV import flow** |

## Manual Testing - CSV Import Flow

For manual testing of the complete CashFlow CSV import pipeline, refer to:
**`docs/manual-testing/CASHFLOW_IMPORT_TEST_GUIDE.md`**

This guide covers the full 11-step flow:
1. Register User → TOKEN, USER_ID
2. Create CashFlow with history → CF_ID
3. AI Transform CSV → TRANSFORMATION_ID
4. Download Transformed CSV
5. Create Staging Session → SESSION_ID
6. Configure Category Mappings (CREATE_NEW, CREATE_SUBCATEGORY, MAP_TO_UNCATEGORIZED)
7. Revalidate Staging
8. Start Import → JOB_ID
9. Attest Historical Import
10. Verify CashFlow
11. Verify Forecast

**Quick start for fresh test:**
```bash
# Package, build WITHOUT CACHE, and start with CLEAN VOLUMES
./mvnw package -DskipTests
docker build --no-cache -t vidulum-app:latest .
docker-compose -f docker-compose-final.yml down -v
docker-compose -f docker-compose-final.yml up -d
```

**Key gotchas:**
- `paidDate` field is required in staging transactions
- MappingAction enum values: `CREATE_NEW`, `CREATE_SUBCATEGORY`, `MAP_TO_UNCATEGORIZED` (NOT `MAP_TO_EXISTING`)
- Use `down -v` to clear AI transformation cache between tests

## Error Handling Guidelines

**IMPORTANT**: Every custom/business exception MUST be handled by `ErrorHttpHandler` with proper `ApiError` response.

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

### Key Classes
- `ErrorHttpHandler` - `security/config/ErrorHttpHandler.java` - central exception handler
- `ApiError` - `common/error/ApiError.java` - error response format
- `ErrorCode` - `common/error/ErrorCode.java` - all error codes with HTTP statuses

## CSV Import Business Flow (Complete Pipeline)

This is the COMPLETE flow for importing bank CSV and creating CashFlow with forecast.

### ⚠️ CRITICAL: Test Data Consistency Rules

When testing manually, data MUST be consistent. Use these values:

#### Test CSV File
```
File: src/test/resources/lista_operacji_20260111.csv
Bank: Nest Bank
Date range: 2023-01-13 to 2025-12-31 (oldest to newest)
Transactions: 402
Currency: PLN
```

#### CashFlow Creation - MUST Match CSV Dates!
```json
{
  "startPeriod": "2023-01",        // ← Month of OLDEST transaction in CSV!
  "initialBalance": { "amount": 863.94, "currency": "PLN" }
}
```

**Why `startPeriod` matters:**
- CashFlow creates historical months from `startPeriod` to current month
- Transactions with `paidDate` BEFORE `startPeriod` will be REJECTED
- Transactions with `paidDate` in FUTURE will be REJECTED
- Check AI transform response for `suggestedStartPeriod` field!

#### Valid Polish IBAN for Testing
```
PL61109010140000071219812874    ← Use this IBAN
```
IBAN validation is STRICT - must be valid Polish IBAN format (PL + 2 check digits + 24 digits).

#### Complete Request Bodies

**1. Register User:**
```json
POST /api/v1/auth/register
{
  "username": "testuser1",
  "email": "test@test.com",
  "password": "SecurePassword123!"
}
→ Save: TOKEN, USER_ID
```

**2. Create CashFlow with History:**
```json
POST /cash-flow/with-history
Authorization: Bearer {TOKEN}
{
  "userId": "{USER_ID}",
  "name": "Konto Nest Bank",
  "description": "Test import",
  "bankAccount": {
    "bankName": "Nest Bank",
    "bankAccountNumber": {
      "account": "PL61109010140000071219812874",
      "denomination": {"id": "PLN"}
    },
    "balance": {"amount": 0, "currency": "PLN"}
  },
  "startPeriod": "2023-01",
  "initialBalance": {"amount": 863.94, "currency": "PLN"}
}
→ Save: CF_ID (e.g., "CF10000001")
```

**3. AI Transform CSV:**
```bash
POST /api/v1/bank-data-adapter/transform
Authorization: Bearer {TOKEN}
Content-Type: multipart/form-data

-F "file=@src/test/resources/lista_operacji_20260111.csv"
-F "bankHint=Nest Bank"

→ Save: TRANSFORMATION_ID
→ Check: suggestedStartPeriod should match your startPeriod!
```

**4. Import to Staging:**
```json
POST /api/v1/bank-data-adapter/{TRANSFORMATION_ID}/import
Authorization: Bearer {TOKEN}
{
  "cashFlowId": "{CF_ID}"
}
→ Save: SESSION_ID (stagingSessionId)
```

**5. AI Categorize:**
```bash
POST /api/v1/bank-data-ingestion/cf={CF_ID}/staging/{SESSION_ID}/ai-categorize
Authorization: Bearer {TOKEN}
(no body)
```

**6. Accept AI or Force Uncategorized:**
```bash
# Option A: Accept AI suggestions (complex)
POST .../staging/{SESSION_ID}/accept-ai
Body: { acceptedCategories: [...], acceptedMappings: [...], ... }

# Option B: Force all to Uncategorized (simple, for quick testing)
POST .../staging/{SESSION_ID}/force-uncategorized
(no body)
```

**7. Start Import:**
```json
POST /api/v1/bank-data-ingestion/cf={CF_ID}/import
{
  "stagingSessionId": "{SESSION_ID}"
}
→ Save: JOB_ID
```

**8. Attest (activate CashFlow):**
```json
POST /cash-flow/cf={CF_ID}/attest-historical-import
{
  "confirmedBalance": {"amount": 76047.25, "currency": "PLN"},
  "createAdjustment": false,
  "forceAttestation": false
}
```
Note: `confirmedBalance` should match the last transaction's "Saldo po operacji" from CSV.

### Common Errors and Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `IMPORT_DATE_BEFORE_START` | Transaction date < startPeriod | Use earlier startPeriod |
| `IMPORT_DATE_IN_FUTURE` | Transaction date > today | Check CSV dates |
| `INVALID_BANK_ACCOUNT` | Bad IBAN format | Use valid IBAN: `PL61109010140000071219812874` |
| `CASHFLOW_NOT_FOUND` | Wrong CF_ID format | CF_ID starts with "CF" (e.g., CF10000001) |
| `INGESTION_STAGING_NOT_FOUND` | Session expired or wrong ID | Sessions expire after 24h |
| `CASHFLOW_BALANCE_MISMATCH` | confirmedBalance wrong | Check CSV's last "Saldo po operacji" |

### Flow Diagram (ASCII)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           CSV IMPORT BUSINESS FLOW                              │
└─────────────────────────────────────────────────────────────────────────────────┘

1. AUTHENTICATION
   POST /api/v1/auth/register → { access_token, user_id }

2. CREATE CASHFLOW (with historical periods)
   POST /cash-flow/with-history → CF_ID
   Body: { userId, name, bankAccount, startPeriod, initialBalance }

3. AI TRANSFORM CSV (bank format → canonical format)
   POST /api/v1/bank-data-adapter/transform
   Form: file=@bank.csv, bankHint="Nest Bank"
   → { transformationId, detectedBank, rowCount, suggestedStartPeriod }

4. IMPORT TO STAGING (creates staging session)
   POST /api/v1/bank-data-adapter/{transformationId}/import
   Body: { cashFlowId: "CF_ID" }
   → { stagingSessionId }

   ┌──────────────────────────────────────────────────────────────────────┐
   │ STAGING SESSION STATES:                                              │
   │  - HAS_UNMAPPED_CATEGORIES → needs AI categorization or mappings    │
   │  - AI_SUGGESTIONS_READY → after AI categorization                   │
   │  - READY_FOR_IMPORT → all transactions mapped, can start import     │
   │  - IMPORTING → import in progress                                   │
   │  - COMPLETED → import finished                                      │
   └──────────────────────────────────────────────────────────────────────┘

5. AI CATEGORIZATION (get category suggestions)
   POST /api/v1/bank-data-ingestion/cf={CF_ID}/staging/{SESSION_ID}/ai-categorize
   → { suggestedStructure, patternSuggestions, bankCategorySuggestions }

6. ACCEPT AI SUGGESTIONS (create categories + mappings)
   POST /api/v1/bank-data-ingestion/cf={CF_ID}/staging/{SESSION_ID}/accept-ai
   Body: { acceptedCategories, acceptedMappings, acceptedBankCategoryMappings, saveToCache }
   → { categoriesCreated, mappingsApplied, validationSummary }

   OR FORCE UNCATEGORIZED (skip categorization)
   POST /api/v1/bank-data-ingestion/cf={CF_ID}/staging/{SESSION_ID}/force-uncategorized
   → Maps all unmapped to "Uncategorized" category

7. CHECK STAGING STATUS
   GET /api/v1/bank-data-ingestion/cf={CF_ID}/staging/{SESSION_ID}
   → { status, validationSummary: { readyForImport, unmappedTransactions } }

8. START IMPORT (if readyForImport=true)
   POST /api/v1/bank-data-ingestion/cf={CF_ID}/import
   Body: { stagingSessionId: "SESSION_ID" }
   → { jobId, status }

9. ATTEST HISTORICAL IMPORT (activates CashFlow)
   POST /cash-flow/cf={CF_ID}/attest-historical-import
   Body: { confirmedBalance: { amount, currency }, createAdjustment: false }
   → CashFlow changes from SETUP to ACTIVE mode

10. VERIFY CASHFLOW
    GET /cash-flow/cf={CF_ID}
    → { status, categories, monthlyStatements }

11. VERIFY FORECAST
    GET /cash-flow-forecast/cf={CF_ID}
    → { categorizedOutFlows, categorizedInFlows, balanceEvolution }
```

### Staging Session Lifecycle

```
                     ┌─────────────────────┐
                     │   Upload CSV        │
                     │   or Import from    │
                     │   Transformation    │
                     └──────────┬──────────┘
                                │
                                ▼
               ┌────────────────────────────────┐
               │    HAS_UNMAPPED_CATEGORIES     │ ◄─── Initial state
               │   (transactions need mapping)  │
               └────────────────┬───────────────┘
                                │
            ┌───────────────────┼───────────────────┐
            │                   │                   │
            ▼                   ▼                   ▼
    ┌──────────────┐   ┌───────────────┐   ┌──────────────────┐
    │ AI Categorize│   │ Manual        │   │ Force            │
    │ (ai-categorize)│ │ Mappings      │   │ Uncategorized    │
    └──────┬───────┘   └───────┬───────┘   └────────┬─────────┘
           │                   │                    │
           ▼                   │                    │
    ┌──────────────────┐       │                    │
    │ AI_SUGGESTIONS   │       │                    │
    │ _READY           │       │                    │
    └──────┬───────────┘       │                    │
           │                   │                    │
           ▼                   │                    │
    ┌──────────────────┐       │                    │
    │ Accept AI        │       │                    │
    │ (accept-ai)      │       │                    │
    └──────┬───────────┘       │                    │
           │                   │                    │
           └───────────────────┴────────────────────┘
                                │
                                ▼
                  ┌─────────────────────────┐
                  │   Revalidate Staging    │ ◄─── Applies pattern matching
                  │   (revalidate)          │      Re-categorizes transactions
                  └───────────┬─────────────┘
                              │
                              ▼
                  ┌─────────────────────────┐
                  │   READY_FOR_IMPORT      │
                  │   (readyForImport=true) │
                  └───────────┬─────────────┘
                              │
                              ▼
                  ┌─────────────────────────┐
                  │   Start Import Job      │
                  │   (import)              │
                  └───────────┬─────────────┘
                              │
                              ▼
                  ┌─────────────────────────┐
                  │      COMPLETED          │
                  └─────────────────────────┘
```

### Key Endpoints Summary (CSV Import Flow)

| Step | Method | Endpoint | Purpose |
|------|--------|----------|---------|
| 1 | POST | `/api/v1/auth/register` | Get TOKEN |
| 2 | POST | `/cash-flow/with-history` | Create CashFlow → CF_ID |
| 3 | POST | `/api/v1/bank-data-adapter/transform` | AI transform CSV → transformationId |
| 4 | POST | `/api/v1/bank-data-adapter/{id}/import` | Create staging → sessionId |
| 5 | POST | `.../staging/{id}/ai-categorize` | Get AI suggestions |
| 6 | POST | `.../staging/{id}/accept-ai` | Apply suggestions |
| 7 | GET | `.../staging/{id}` | Check status |
| 8 | POST | `.../import` | Start import job |
| 9 | POST | `/cash-flow/cf={id}/attest-historical-import` | Activate CashFlow |
| 10 | GET | `/cash-flow/cf={id}` | Verify CashFlow |
| 11 | GET | `/cash-flow-forecast/cf={id}` | Verify forecast |

### Alternative: Manual CSV Upload (without AI transform)

```
POST /api/v1/bank-data-ingestion/cf={CF_ID}/upload
Form: file=@canonical.csv

→ Skips AI transformation, CSV must be in canonical format:
  date,amount,currency,type,bankCategory,name,description,merchant
```

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

### Features Backlog (NOT IMPLEMENTED)
Files in `docs/features-backlog/` - these are designs waiting for implementation:

| File | Feature | Priority |
|------|---------|----------|
| `TODO-integration-tests-with-jwt-authentication.md` | Add JWT to all integration tests | High |
| `TODO-kafka-dead-letter-queue.md` | Dead letter queue for failed events | Medium |
| `VID-103-maven-multi-module-migration.md` | Split into Maven modules | Low |
| `AI_CATEGORIZATION_PLAN.md` | AI-powered transaction categorization | Medium |
| `AI_USE_CASES.md` | AI use cases overview | Medium |
| `2026-02-07-intelligent-cashflow-reconciliation.md` | Smart reconciliation | Medium |
| `2026-02-08-canonical-csv-architecture.md` | Unified CSV format | Low |
| `2026-02-08-month-rollover-ongoing-sync-design.md` | Month rollover sync | Medium |
| `2026-02-14-business-analysis-alerts-cashchange-lifecycle.md` | Alerts system | Medium |
| `2026-02-14-recurring-rule-engine-design.md` | Recurring transactions | High |

### Business Analysis
Files in `docs/business-analysis/`:
- Open Banking providers comparison
- Kontomatik cost analysis
- B2B market analysis
- Business model analysis
- Bank integration design
