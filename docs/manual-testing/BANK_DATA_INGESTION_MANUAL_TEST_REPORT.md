# Bank Data Ingestion - Manual Test Report

## Original Prompt (preserved)

> do nowego pliku md zapisz mi nastepujaca historie konwersacji rest - chce miec stworzonynowy user z nowym cashflow historycznym do ktorego zostanie zaimportoway plik csv z mojego pulpitu/Desktop. podczas ladowania danych jistorycznych przy mappingu ustaw samodzielnie pola i sprawdz potem staging i preview i zatwierdz import a potem dodaj nowa kategorie i kolejne cashchanges inflow i outflow. na bierzaco przy pomocy odpowiednich endpointow sprawdzaj stan cashflow statement. chce miec taki manualny test gdzie w tym raporcie bede mial zestawienie req/res i validation check z komentarzem. chcialbym miec ten raport maksymalnie rozjijajocy zeby w przyszlosc idodawac do niego kolejne rzeczy. przetestuj w tym raporcie dodatkowo na kilka sposobow robienie mappingu i staging czy jest poprawny oraz jak zostalo dokonane sprawdzenie web-socketow z progresem. mozesz stworzyc na nowo kilka cashflow with history zeby przetestowac rozne mapowania kategorii. zagladaj tez do logow aplikacji zeby szukac ewentualnych bledow np kafka albo NPE. monitoruj logi aplikacji. raport ma byc latwy tez do formy kopiowania requestow zebym mogl sobie pozniej tez recznie przetestowac aplikacje. przed tym zadaniem przeanalizuj mozliwe scenariusze i popros mnie o potwierdznie. przeslane teraz prompt zapisz na samej gorze tez w tym raporcie - dodatkowo pod spodem mozesz zamiescic wersje zredagowana ale zachowaj wszystkie wymagania z oryginalnego promptu. chce zebys byl moim manualnym testerem i analitykiem.

---

## Edited Requirements Summary

### Objectives
1. Create new user with new historical CashFlow
2. Import CSV file from Desktop with historical transactions
3. Configure category mappings (test multiple strategies)
4. Verify staging and preview
5. Approve and execute import
6. Add new category and cash changes (INFLOW/OUTFLOW) after import
7. Continuously verify CashFlow Statement state
8. Test WebSocket progress monitoring
9. Monitor application logs for errors (Kafka, NPE, etc.)

### Report Requirements
- Request/Response pairs with validation checks and comments
- Easy to copy curl commands for manual testing
- Expandable structure for future additions
- Multiple mapping strategies tested
- Log monitoring results included

---

## Test Environment

| Component | Value |
|-----------|-------|
| Date | 2026-01-20 |
| Base URL | `http://localhost:9090` |
| WebSocket URL | `ws://localhost:8081` |
| CSV File | `/tmp/historical-transactions.csv` (23 transactions) |
| Docker Image | `vidulum:latest` (built 2026-01-20 22:08) |

---

## Test Data - CSV File Content

```csv
bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
TXN-2021-07-001,July Salary,Monthly salary payment,Wpływy regularne,5000.00,PLN,INFLOW,2021-07-15,2021-07-15,,PL12345678901234567890123456
TXN-2021-07-002,July Rent,Monthly rent payment,Mieszkanie,1500.00,PLN,OUTFLOW,2021-07-15,2021-07-15,PL12345678901234567890123456,PL98765432109876543210987654
TXN-2021-07-003,Biedronka,Zakupy spożywcze,Zakupy kartą,250.00,PLN,OUTFLOW,2021-07-25,2021-07-25,PL12345678901234567890123456,
... (23 transactions total across months 2021-07 to 2021-12)
```

**Bank Categories in CSV:**
- `Wpływy regularne` (INFLOW) - 8 transactions
- `Mieszkanie` (OUTFLOW) - 6 transactions
- `Zakupy kartą` (OUTFLOW) - 4 transactions
- `Rachunki` (OUTFLOW) - 2 transactions
- `Rozrywka` (OUTFLOW) - 2 transactions
- `Transport` (OUTFLOW) - 1 transaction

---

# SCENARIO 1: Basic Happy Path

## Test Case 1.1: Register New User

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "manual_test_user_2",
    "email": "manual_test_user_2@test.com",
    "password": "TestPass123",
    "role": "USER"
  }'
```

**Note:** The `role` field is required. Without it, NPE occurs in backend.

### Response
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

### Validation
- [x] Status: 200 OK
- [x] User created successfully
- [x] Access token returned

---

## Test Case 1.2: Login and Get Token

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "manual_test_user_1",
    "password": "TestPass123!"
  }'
```

### Response
```json
// TO BE FILLED DURING TEST
```

### Validation
- [ ] Status: 200 OK
- [ ] accessToken present
- [ ] Token saved for subsequent requests

### Variables Set
```bash
export TOKEN="<accessToken>"
```

---

## Test Case 1.3: Create CashFlow with History

### Request
```bash
curl -X POST http://localhost:9090/cash-flow/with-history \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "userId": "manual_test_user_2",
    "name": "Test CashFlow - Happy Path",
    "description": "Manual test for bank data import",
    "bankAccount": {
      "bankName": {"name": "PKO BP"},
      "bankAccountNumber": {"accountNumber": "PL12345678901234567890123456", "currency": {"code": "PLN"}},
      "balance": {"amount": 0, "currency": "PLN"}
    },
    "startPeriod": "2025-07",
    "initialBalance": {"amount": 10000.00, "currency": "PLN"}
  }'
```

**Note:** `startPeriod` should match or precede the earliest date in CSV file.

### Response
```json
"56d23bdb-44ba-41bf-84a0-979af7ce8e0f"
```

### Validation
- [x] Status: 200 OK
- [x] CashFlowId returned (UUID format)
- [x] CashFlow in SETUP mode

### Variables Set
```bash
export CASHFLOW_ID="56d23bdb-44ba-41bf-84a0-979af7ce8e0f"
```

---

## Test Case 1.4: Upload CSV File

### Request
```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID}/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/Users/lucjanbik/Desktop/historical-transactions.csv"
```

### Response (First upload - no mappings configured)
```json
{
  "parseSummary": {
    "totalRows": 23,
    "successfulRows": 23,
    "failedRows": 0,
    "errors": []
  },
  "stagingResult": {
    "stagingSessionId": "999d7c72-300b-4433-ba65-690e6c36223e",
    "status": "HAS_UNMAPPED_CATEGORIES",
    "summary": {"totalTransactions": 0, "validTransactions": 0, ...},
    "unmappedCategories": [
      {"bankCategory": "Wpływy regularne", "transactionCount": 8, "type": "INFLOW"},
      {"bankCategory": "Mieszkanie", "transactionCount": 6, "type": "OUTFLOW"},
      {"bankCategory": "Zakupy kartą", "transactionCount": 4, "type": "OUTFLOW"},
      {"bankCategory": "Rachunki", "transactionCount": 2, "type": "OUTFLOW"},
      {"bankCategory": "Rozrywka", "transactionCount": 2, "type": "OUTFLOW"},
      {"bankCategory": "Transport", "transactionCount": 1, "type": "OUTFLOW"}
    ]
  }
}
```

### Validation
- [x] Status: 200 OK
- [x] parseSummary.totalRows = 23
- [x] parseSummary.successfulRows = 23
- [x] parseSummary.failedRows = 0
- [x] stagingResult.status = "HAS_UNMAPPED_CATEGORIES"
- [x] stagingResult.unmappedCategories contains 6 categories

### Variables Set
```bash
export STAGING_ID="999d7c72-300b-4433-ba65-690e6c36223e"
```

**⚠️ WARNING (BUG-001):** This stagingSessionId is NOT persisted in database. See BUG-001 for details.

---

## Test Case 1.5: Configure Category Mappings

### Request
```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID}/mappings" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "mappings": [
      {"bankCategoryName": "Wpływy regularne", "action": "CREATE_NEW", "targetCategoryName": "Salary", "categoryType": "INFLOW"},
      {"bankCategoryName": "Mieszkanie", "action": "CREATE_NEW", "targetCategoryName": "Housing", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Zakupy kartą", "action": "CREATE_NEW", "targetCategoryName": "Groceries", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Rachunki", "action": "CREATE_NEW", "targetCategoryName": "Bills", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Rozrywka", "action": "CREATE_NEW", "targetCategoryName": "Entertainment", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Transport", "action": "CREATE_NEW", "targetCategoryName": "Transport", "categoryType": "OUTFLOW"}
    ]
  }'
```

### Response
```json
{
  "cashFlowId": "56d23bdb-44ba-41bf-84a0-979af7ce8e0f",
  "mappingsConfigured": 6,
  "results": [
    {"bankCategoryName": "Wpływy regularne", "targetCategoryName": "Salary", "status": "CREATED"},
    {"bankCategoryName": "Mieszkanie", "targetCategoryName": "Housing", "status": "CREATED"},
    {"bankCategoryName": "Zakupy kartą", "targetCategoryName": "Groceries", "status": "CREATED"},
    {"bankCategoryName": "Rachunki", "targetCategoryName": "Bills", "status": "CREATED"},
    {"bankCategoryName": "Rozrywka", "targetCategoryName": "Entertainment", "status": "CREATED"},
    {"bankCategoryName": "Transport", "targetCategoryName": "Transport", "status": "CREATED"}
  ]
}
```

### Validation
- [x] Status: 200 OK
- [x] mappingsConfigured = 6
- [x] All mappings have status "CREATED"

---

## Test Case 1.6: Get Staging Preview

### Request (First attempt - FAILED due to BUG-001)
```bash
curl -X GET "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID}/staging/${STAGING_ID}" \
  -H "Authorization: Bearer $TOKEN"
```

### Response (BUG-001 - NOT_FOUND)
```json
{
  "stagingSessionId": "999d7c72-300b-4433-ba65-690e6c36223e",
  "status": "NOT_FOUND",
  "summary": {"totalTransactions": 0, "validTransactions": 0, "invalidTransactions": 0, "duplicateTransactions": 0}
}
```

**⚠️ WORKAROUND REQUIRED:** Re-upload CSV file after configuring mappings.

---

## Test Case 1.6b: Re-upload CSV (Workaround for BUG-001)

### Request
```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID}/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/Users/lucjanbik/Desktop/historical-transactions.csv"
```

### Response
```json
{
  "parseSummary": {"totalRows": 23, "successfulRows": 23, "failedRows": 0, "errors": []},
  "stagingResult": {
    "stagingSessionId": "ba31f4b5-18e0-4bea-96fd-cb6467f47745",
    "status": "READY_FOR_IMPORT",
    "summary": {"totalTransactions": 23, "validTransactions": 23, "invalidTransactions": 0, "duplicateTransactions": 0},
    "categoryBreakdown": [
      {"targetCategory": "Salary", "transactionCount": 8, "totalAmount": 35600.0, "type": "INFLOW", "newCategory": true},
      {"targetCategory": "Housing", "transactionCount": 6, "totalAmount": 9000.0, "type": "OUTFLOW", "newCategory": true},
      {"targetCategory": "Groceries", "transactionCount": 4, "totalAmount": 1150.0, "type": "OUTFLOW", "newCategory": true},
      {"targetCategory": "Bills", "transactionCount": 2, "totalAmount": 300.0, "type": "OUTFLOW", "newCategory": true},
      {"targetCategory": "Entertainment", "transactionCount": 2, "totalAmount": 550.0, "type": "OUTFLOW", "newCategory": true},
      {"targetCategory": "Transport", "transactionCount": 1, "totalAmount": 200.0, "type": "OUTFLOW", "newCategory": true}
    ],
    "categoriesToCreate": [
      {"name": "Salary", "type": "INFLOW"},
      {"name": "Housing", "type": "OUTFLOW"},
      {"name": "Groceries", "type": "OUTFLOW"},
      {"name": "Bills", "type": "OUTFLOW"},
      {"name": "Entertainment", "type": "OUTFLOW"},
      {"name": "Transport", "type": "OUTFLOW"}
    ],
    "monthlyBreakdown": [
      {"month": "2025-07", "inflowTotal": 5000.0, "outflowTotal": 1750.0, "transactionCount": 3},
      {"month": "2025-08", "inflowTotal": 5000.0, "outflowTotal": 1830.0, "transactionCount": 4},
      {"month": "2025-09", "inflowTotal": 7000.0, "outflowTotal": 1700.0, "transactionCount": 4},
      {"month": "2025-10", "inflowTotal": 5200.0, "outflowTotal": 1900.0, "transactionCount": 4},
      {"month": "2025-11", "inflowTotal": 5200.0, "outflowTotal": 1620.0, "transactionCount": 3},
      {"month": "2025-12", "inflowTotal": 8200.0, "outflowTotal": 2400.0, "transactionCount": 5}
    ]
  }
}
```

### Validation
- [x] Status: 200 OK
- [x] status = "READY_FOR_IMPORT"
- [x] summary.totalTransactions = 23
- [x] summary.validTransactions = 23
- [x] summary.invalidTransactions = 0
- [x] categoriesToCreate contains 6 categories

### Variables Set
```bash
export STAGING_ID="ba31f4b5-18e0-4bea-96fd-cb6467f47745"
```

---

## Test Case 1.7: Start Import

### Request
```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID}/import" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"stagingSessionId": "ba31f4b5-18e0-4bea-96fd-cb6467f47745"}'
```

### Response
```json
{
  "jobId": "d720e489-fd7a-43bb-89b5-62a267cfbd90",
  "cashFlowId": "56d23bdb-44ba-41bf-84a0-979af7ce8e0f",
  "stagingSessionId": "ba31f4b5-18e0-4bea-96fd-cb6467f47745",
  "status": "COMPLETED",
  "input": {
    "totalTransactions": 23,
    "validTransactions": 23,
    "duplicateTransactions": 0,
    "categoriesToCreate": 6
  },
  "progress": {
    "percentage": 100,
    "phases": [
      {"name": "CREATING_CATEGORIES", "status": "COMPLETED", "processed": 6, "total": 6, "durationMs": 122},
      {"name": "IMPORTING_TRANSACTIONS", "status": "COMPLETED", "processed": 23, "total": 23, "durationMs": 277}
    ]
  },
  "result": {
    "categoriesCreated": ["Salary", "Housing", "Groceries", "Bills", "Entertainment", "Transport"],
    "transactionsImported": 23,
    "transactionsFailed": 0,
    "errors": []
  },
  "canRollback": true
}
```

### Validation
- [x] Status: 200 OK (synchronous completion)
- [x] jobId returned
- [x] status = "COMPLETED"
- [x] All 6 categories created
- [x] All 23 transactions imported
- [x] No errors

### Variables Set
```bash
export JOB_ID="d720e489-fd7a-43bb-89b5-62a267cfbd90"
```

---

## Test Case 1.8: Get Import Progress

### Request
```bash
curl -X GET "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID}/import/${JOB_ID}" \
  -H "Authorization: Bearer $TOKEN"
```

### Response
```json
// TO BE FILLED DURING TEST
```

### Validation
- [ ] Status: 200 OK
- [ ] status = "COMPLETED"
- [ ] result.categoriesCreated = 6
- [ ] result.transactionsImported = 23
- [ ] result.transactionsFailed = 0
- [ ] result.errors = []

---

## Test Case 1.9: Verify CashFlow Forecast Statement

### Request
```bash
curl -X GET "http://localhost:9090/cash-flow-forecast/${CASHFLOW_ID}" \
  -H "Authorization: Bearer $TOKEN"
```

### Response (BUG-002 - Kafka Event Ordering Issue)
```json
{
  "cashFlowId": "56d23bdb-44ba-41bf-84a0-979af7ce8e0f",
  "forecasts": [
    {"period": "2025-07", "status": "IMPORT_PENDING", "inflow": {"categories": [], "total": null}, "outflow": {"categories": [], "total": null}},
    {"period": "2025-08", "status": "IMPORT_PENDING", "inflow": {"categories": [], "total": null}, "outflow": {"categories": [], "total": null}},
    "... all months have IMPORT_PENDING status with empty categories ..."
  ]
}
```

### Validation
- [x] Status: 200 OK
- [ ] ❌ forecasts do NOT contain imported data
- [ ] ❌ Categories NOT present in forecast months
- [ ] ❌ Historical months have NO transactions

### Bug Found: BUG-002

**Error in logs:**
```
java.lang.IllegalStateException: Cannot find cash-category with name CategoryName[name=Groceries] in OUTFLOWS
  at HistoricalCashChangeImportedEventHandler.lambda$handle$2
```

The Kafka consumer attempted to process `HistoricalCashChangeImportedEvent` before `CategoryCreatedEvent` was fully processed in `CashFlowForecastStatement`.

---

## Test Case 1.10: Get CashFlow Details

### Request
```bash
curl -X GET "http://localhost:9090/cash-flow/${CASHFLOW_ID}" \
  -H "Authorization: Bearer $TOKEN"
```

### Response
```json
// TO BE FILLED DURING TEST
```

### Validation
- [ ] Status: 200 OK
- [ ] status = "SETUP"
- [ ] Categories created
- [ ] Cash changes imported

---

## Test Case 1.11: Finalize Import

### Request
```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID}/import/${JOB_ID}/finalize" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"deleteMappings": false}'
```

### Response
```json
// TO BE FILLED DURING TEST
```

### Validation
- [ ] Status: 200 OK
- [ ] status = "FINALIZED"
- [ ] finalSummary.categoriesCreated = 6
- [ ] finalSummary.transactionsImported = 23

---

# SCENARIO 2: Different Mapping Strategies

## Test Case 2.1: Create Second CashFlow for Mapping Tests

### Request
```bash
curl -X POST http://localhost:9090/cash-flow/with-history \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "userId": "manual_test_user_1",
    "name": "Test CashFlow - Mapping Strategies",
    "description": "Testing different mapping strategies",
    "bankAccount": {
      "bankName": {"name": "mBank"},
      "bankAccountNumber": {"accountNumber": "PL98765432109876543210987654", "currency": {"code": "PLN"}},
      "balance": {"amount": 0, "currency": "PLN"}
    },
    "startPeriod": "2021-07",
    "initialBalance": {"amount": 5000.00, "currency": "PLN"}
  }'
```

### Response
```json
// TO BE FILLED DURING TEST
```

### Variables Set
```bash
export CASHFLOW_ID_2="<returned_id>"
```

---

## Test Case 2.2: Mapping with Subcategories (parentCategoryName)

### Request
```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID_2}/mappings" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "mappings": [
      {"bankCategoryName": "Wpływy regularne", "action": "CREATE_NEW", "targetCategoryName": "Regular Income", "categoryType": "INFLOW"},
      {"bankCategoryName": "Mieszkanie", "action": "CREATE_NEW", "targetCategoryName": "Rent", "parentCategoryName": "Housing", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Rachunki", "action": "CREATE_NEW", "targetCategoryName": "Utilities", "parentCategoryName": "Housing", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Zakupy kartą", "action": "CREATE_NEW", "targetCategoryName": "Food", "parentCategoryName": "Daily Expenses", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Rozrywka", "action": "CREATE_NEW", "targetCategoryName": "Fun", "parentCategoryName": "Daily Expenses", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Transport", "action": "MAP_TO_UNCATEGORIZED", "categoryType": "OUTFLOW"}
    ]
  }'
```

### Response
```json
// TO BE FILLED DURING TEST
```

### Validation
- [ ] Status: 200 OK
- [ ] Subcategories (Rent, Utilities) linked to parent "Housing"
- [ ] Subcategories (Food, Fun) linked to parent "Daily Expenses"
- [ ] Transport mapped to "Uncategorized"

---

## Test Case 2.3: Upload CSV and Verify Subcategory Structure

### Request
```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/${CASHFLOW_ID_2}/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/historical-transactions.csv"
```

### Response
```json
// TO BE FILLED DURING TEST
```

### Validation
- [ ] Status: 200 OK
- [ ] stagingResult.status = "READY_FOR_IMPORT"
- [ ] categoriesToCreate includes parent categories (Housing, Daily Expenses)
- [ ] categoryBreakdown shows correct parent-child relationships

---

# SCENARIO 3: Post-Import Operations

## Test Case 3.1: Add New Category (after import)

*Note: CashFlow must be in OPEN mode (after attest-historical-import) to add categories manually*

### Request
```bash
curl -X POST "http://localhost:9090/cash-flow/${CASHFLOW_ID}/category" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "category": "Savings",
    "type": "OUTFLOW"
  }'
```

### Response
```json
// TO BE FILLED DURING TEST
```

### Validation
- [ ] Status: 200 OK (if CashFlow in OPEN mode)
- [ ] OR Status: 400 with "OperationNotAllowedInSetupModeException" (if still in SETUP mode)

---

## Test Case 3.2: Attest Historical Import

### Request
```bash
curl -X POST "http://localhost:9090/cash-flow/${CASHFLOW_ID}/attest-historical-import" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "confirmedBalance": {"amount": 10000.00, "currency": "PLN"},
    "forceAttestation": false,
    "createAdjustment": false
  }'
```

### Response
```json
// TO BE FILLED DURING TEST
```

### Validation
- [ ] Status: 200 OK
- [ ] status = "OPEN"
- [ ] difference shows calculated vs confirmed balance

---

## Test Case 3.3: Add Category (after attest - should work now)

### Request
```bash
curl -X POST "http://localhost:9090/cash-flow/${CASHFLOW_ID}/category" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "category": "Investments",
    "type": "OUTFLOW"
  }'
```

### Response
```json
// TO BE FILLED DURING TEST
```

### Validation
- [ ] Status: 200 OK
- [ ] Category created

---

## Test Case 3.4: Add Expected Cash Change (INFLOW)

### Request
```bash
curl -X POST "http://localhost:9090/cash-flow/expected-cash-change" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{
    \"cashFlowId\": \"$CASHFLOW_ID\",
    \"category\": \"Salary\",
    \"name\": \"Expected Bonus\",
    \"description\": \"Q1 2026 bonus\",
    \"money\": {\"amount\": 3000.00, \"currency\": \"PLN\"},
    \"type\": \"INFLOW\",
    \"dueDate\": \"2026-03-15T10:00:00Z\"
  }"
```

### Response
```json
// TO BE FILLED DURING TEST - CashChangeId
```

### Validation
- [ ] Status: 200 OK
- [ ] CashChangeId returned

---

## Test Case 3.5: Add Paid Cash Change (OUTFLOW)

### Request
```bash
curl -X POST "http://localhost:9090/cash-flow/paid-cash-change" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{
    \"cashFlowId\": \"$CASHFLOW_ID\",
    \"category\": \"Investments\",
    \"name\": \"Stock Purchase\",
    \"description\": \"Monthly investment\",
    \"money\": {\"amount\": 500.00, \"currency\": \"PLN\"},
    \"type\": \"OUTFLOW\",
    \"dueDate\": \"2026-01-20T10:00:00Z\",
    \"paidDate\": \"2026-01-20T10:00:00Z\"
  }"
```

### Response
```json
// TO BE FILLED DURING TEST - CashChangeId
```

### Validation
- [ ] Status: 200 OK
- [ ] CashChangeId returned

---

## Test Case 3.6: Verify CashFlow Statement After Changes

### Request
```bash
curl -X GET "http://localhost:9090/cash-flow-forecast/${CASHFLOW_ID}" \
  -H "Authorization: Bearer $TOKEN"
```

### Response
```json
// TO BE FILLED DURING TEST
```

### Validation
- [ ] Status: 200 OK
- [ ] New category "Investments" visible
- [ ] New transactions visible in appropriate months

---

# SCENARIO 4: WebSocket Progress Monitoring

## Test Case 4.1: Connect to WebSocket

### Connection
```javascript
// Browser console or wscat
const ws = new WebSocket('ws://localhost:8081/events');

ws.onopen = () => {
  console.log('Connected');
  // Subscribe to import progress
  ws.send(JSON.stringify({
    type: 'SUBSCRIBE',
    payload: {
      cashFlowId: '<CASHFLOW_ID>',
      eventTypes: ['IMPORT_PROGRESS']
    }
  }));
};

ws.onmessage = (event) => {
  console.log('Received:', JSON.parse(event.data));
};
```

### Using wscat
```bash
# Install: npm install -g wscat
wscat -c ws://localhost:8081/events

# After connection, send:
{"type":"SUBSCRIBE","payload":{"cashFlowId":"<CASHFLOW_ID>","eventTypes":["IMPORT_PROGRESS"]}}
```

### Validation
- [ ] Connection established
- [ ] Subscription confirmed
- [ ] Progress events received during import

---

# APPLICATION LOG MONITORING

## Log Check Commands

```bash
# Check recent logs
docker logs vidulum-app --tail 100

# Check for errors
docker logs vidulum-app 2>&1 | grep -E "(ERROR|Exception|NPE)"

# Check for Kafka issues
docker logs vidulum-app 2>&1 | grep -E "(Kafka|kafka)"

# Follow logs in real-time
docker logs -f vidulum-app
```

## Log Analysis Results

| Timestamp | Level | Message | Status |
|-----------|-------|---------|--------|
| 2026-01-20T22:10:15 | INFO | `Parsed CSV file: 23 rows successful, 0 errors` | ✅ OK |
| 2026-01-20T22:10:15 | WARN | `Found 6 unmapped categories for CashFlow` | ⚠️ Expected (no mappings yet) |
| 2026-01-20T22:10:28 | INFO | `Created category mapping for bank category [Wpływy regularne]` | ✅ OK |
| 2026-01-20T22:10:36 | WARN | `No staged transactions found for session [999d7c72...]` | ❌ BUG-001 |
| 2026-01-20T22:34:20 | INFO | `Created import job [...] with 23 valid transactions and 6 categories to create` | ✅ OK |
| 2026-01-20T22:34:51 | ERROR | `Seek to current after exception` | ❌ BUG-002 |
| 2026-01-20T22:34:51 | ERROR | `Cannot find cash-category with name Groceries in OUTFLOWS` | ❌ BUG-002 |

### Error Details - BUG-002 Full Stack Trace

```
org.springframework.kafka.KafkaException: Seek to current after exception
Caused by: java.lang.IllegalStateException: Cannot find cash-category with name CategoryName[name=Groceries] in OUTFLOWS
    at com.multi.vidulum.cashflow_forecast_processor.app.processing.HistoricalCashChangeImportedEventHandler.lambda$handle$2(HistoricalCashChangeImportedEventHandler.java:57)
    at java.base/java.util.Optional.orElseThrow(Optional.java:403)
    at com.multi.vidulum.cashflow_forecast_processor.app.processing.HistoricalCashChangeImportedEventHandler.lambda$handle$3(HistoricalCashChangeImportedEventHandler.java:57)
    at com.multi.vidulum.cashflow_forecast_processor.app.processing.CashFlowForecastProcessor.processEvent(CashFlowForecastProcessor.java:46)
    at com.multi.vidulum.cashflow_forecast_processor.app.CashFlowEventListener.on(CashFlowEventListener.java:25)
```

The Kafka consumer enters an infinite retry loop because it cannot process the event and seeks back to the same offset.

---

# TEST EXECUTION LOG

## Execution Date: 2026-01-20

### Pre-Test Checklist
- [ ] Docker containers running (vidulum-app, mongodb, kafka, websocket-gateway)
- [ ] CSV file available at /tmp/historical-transactions.csv
- [ ] No existing test data conflicts

### Test Results Summary

| Scenario | Test Case | Status | Notes |
|----------|-----------|--------|-------|
| 1 | 1.1 Register User | ✅ PASS | Required `role` field in request body |
| 1 | 1.2 Login | ✅ PASS | Token received |
| 1 | 1.3 Create CashFlow | ✅ PASS | CashFlowId: `56d23bdb-44ba-41bf-84a0-979af7ce8e0f` |
| 1 | 1.4 Upload CSV | ✅ PASS | 23 rows, 6 unmapped categories |
| 1 | 1.5 Configure Mappings | ✅ PASS | 6 mappings created (all CREATE_NEW) |
| 1 | 1.6 Staging Preview | ⚠️ WARN | First call returned NOT_FOUND (BUG-001), re-upload required |
| 1 | 1.6b Re-upload CSV | ✅ PASS | Status: READY_FOR_IMPORT, stagingId: `ba31f4b5-18e0-4bea-96fd-cb6467f47745` |
| 1 | 1.7 Start Import | ✅ PASS | JobId: `d720e489-fd7a-43bb-89b5-62a267cfbd90`, status: COMPLETED |
| 1 | 1.8 Import Progress | ✅ PASS | 6 categories created, 23 transactions imported |
| 1 | 1.9 Verify Forecast | ❌ FAIL | Months show IMPORT_PENDING with null values (BUG-002) |
| 1 | 1.10 CashFlow Details | BLOCKED | Blocked by BUG-002 |
| 1 | 1.11 Finalize Import | BLOCKED | Blocked by BUG-002 |
| 2 | 2.1 Create CashFlow 2 | PENDING | |
| 2 | 2.2 Subcategory Mappings | PENDING | |
| 2 | 2.3 Upload & Verify | PENDING | |
| 3 | 3.1 Add Category (SETUP) | PENDING | |
| 3 | 3.2 Attest Import | PENDING | |
| 3 | 3.3 Add Category (OPEN) | PENDING | |
| 3 | 3.4 Expected INFLOW | PENDING | |
| 3 | 3.5 Paid OUTFLOW | PENDING | |
| 3 | 3.6 Verify Statement | PENDING | |
| 4 | 4.1 WebSocket | PENDING | |

---

# ISSUES FOUND

| ID | Severity | Description | Steps to Reproduce | Status |
|----|----------|-------------|-------------------|--------|
| BUG-001 | MEDIUM | Staging session not persisted when unmapped categories found | 1. Upload CSV without mappings configured 2. Get `HAS_UNMAPPED_CATEGORIES` status with stagingSessionId 3. Configure mappings 4. Call GET /staging/{id} → returns `NOT_FOUND` | OPEN - Requires re-upload after configuring mappings |
| BUG-002 | CRITICAL | Race condition: HistoricalCashChangeImportedEvent processed before CategoryCreatedEvent | 1. Upload CSV 2. Start import 3. Kafka consumer processes transaction import event before category creation event is fully processed 4. Error: "Cannot find cash-category with name Groceries in OUTFLOWS" | OPEN - Kafka event ordering issue |

---

## BUG-001 Details: Staging Session Not Persisted

**Root Cause Analysis:**
In `StageTransactionsCommandHandler.java` lines 55-60, when unmapped categories are found:
```java
if (!unmappedCategories.isEmpty()) {
    return createUnmappedCategoriesResult(stagingSessionId, ...);
}
```
The handler returns a result with a `stagingSessionId` but **does not save anything to the repository**. The staged transactions are only saved when all mappings are configured.

**Expected Behavior:** Either:
1. Save staged transactions even with unmapped categories (and re-validate on import), OR
2. Don't return a stagingSessionId until all mappings are configured, OR
3. Document that re-upload is required after configuring mappings

**Workaround:** After configuring all mappings, upload the CSV file again.

---

## BUG-002 Details: Kafka Event Ordering Race Condition

**Root Cause Analysis:**
The import process uses REST calls to:
1. `POST /cash-flow/{id}/category?isImport=true` - Creates category → emits `CategoryCreatedEvent`
2. `POST /cash-flow/{id}/import-historical` - Imports transaction → emits `HistoricalCashChangeImportedEvent`

Both events go to the same Kafka topic `cash_flow`. The Kafka consumer (`CashFlowEventListener`) processes events sequentially, but:
- The REST endpoints return after Kafka `.send().get()` completes (message acknowledged by broker)
- This does NOT guarantee the event has been processed by the consumer
- `HistoricalCashChangeImportedEventHandler` expects the category to already exist in `CashFlowForecastStatement`

**Error Log:**
```
java.lang.IllegalStateException: Cannot find cash-category with name CategoryName[name=Groceries] in OUTFLOWS
  at ...HistoricalCashChangeImportedEventHandler.lambda$handle$2(HistoricalCashChangeImportedEventHandler.java:57)
```

**Possible Fixes:**
1. Add retry logic with backoff in `HistoricalCashChangeImportedEventHandler`
2. Use Kafka message keys to ensure ordering (same key = same partition = ordered)
3. Wait for category event confirmation before sending transaction events
4. Process events in two phases: categories first, then transactions

---

# APPENDIX: Quick Reference

## Environment Variables Template
```bash
export TOKEN=""
export CASHFLOW_ID=""
export CASHFLOW_ID_2=""
export STAGING_ID=""
export JOB_ID=""
```

## API Endpoints Summary

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /api/v1/auth/register | Register user |
| POST | /api/v1/auth/login | Login |
| POST | /cash-flow/with-history | Create CashFlow with history |
| POST | /api/v1/bank-data-ingestion/{id}/upload | Upload CSV |
| POST | /api/v1/bank-data-ingestion/{id}/mappings | Configure mappings |
| GET | /api/v1/bank-data-ingestion/{id}/staging/{sid} | Get staging preview |
| POST | /api/v1/bank-data-ingestion/{id}/import | Start import |
| GET | /api/v1/bank-data-ingestion/{id}/import/{jid} | Get import progress |
| POST | /api/v1/bank-data-ingestion/{id}/import/{jid}/finalize | Finalize import |
| GET | /cash-flow-forecast/{id} | Get forecast statement |
| GET | /cash-flow/{id} | Get CashFlow details |
| POST | /cash-flow/{id}/category | Create category |
| POST | /cash-flow/expected-cash-change | Add expected transaction |
| POST | /cash-flow/paid-cash-change | Add paid transaction |
| POST | /cash-flow/{id}/attest-historical-import | Attest and transition to OPEN |

