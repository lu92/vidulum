# CashFlow Import Manual Testing Guide

Kompletna instrukcja manualnego testowania przepływu importu transakcji bankowych do Vidulum.

## Wymagania wstępne

1. **Docker Desktop** uruchomiony
2. **Klucz API AI** (OpenAI lub Anthropic) skonfigurowany w zmiennych środowiskowych
3. **Plik CSV** z transakcjami bankowymi (np. `lista_operacji_20260111.csv`)

## Przygotowanie środowiska

### 1. Pełne wyczyszczenie i restart Docker

```bash
# Zatrzymaj kontenery i USUŃ wolumeny (czysta baza)
docker-compose -f docker-compose-final.yml down -v

# Przebuduj aplikację
./mvnw package -DskipTests

# Zbuduj nowy obraz Docker
docker build -t vidulum-app:latest .

# Uruchom kontenery
docker-compose -f docker-compose-final.yml up -d

# Poczekaj na uruchomienie (ok. 20 sekund)
sleep 20

# Sprawdź czy aplikacja działa
docker logs vidulum-app 2>&1 | tail -10
```

**Oczekiwany wynik:** Logi pokazują `partitions assigned` dla Kafka.

---

## Przepływ testowy

### KROK 1: Rejestracja użytkownika

**Endpoint:** `POST /api/v1/auth/register`

```bash
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "testuser@test.com",
    "password": "SecurePassword123!"
  }'
```

**Oczekiwana odpowiedź:**
```json
{
  "user_id": "U10000001",
  "access_token": "eyJhbGciOiJIUzM4NCJ9...",
  "refresh_token": "eyJhbGciOiJIUzM4NCJ9..."
}
```

**Zapisz:**
- `TOKEN` = access_token
- `USER_ID` = user_id

---

### KROK 2: Utworzenie CashFlow z historią

**Endpoint:** `POST /cash-flow/with-history`

Przed utworzeniem CashFlow, sprawdź zakres dat w pliku CSV:
```bash
# Pierwsza transakcja (najstarsza - na końcu pliku)
tail -5 src/test/resources/lista_operacji_20260111.csv

# Ostatnia transakcja (najnowsza - na początku pliku)
head -15 src/test/resources/lista_operacji_20260111.csv
```

**Przykład dla pliku z danymi 01-2023 do 12-2025:**
- `startPeriod`: `2022-12` (miesiąc przed pierwszą transakcją)
- `historicalPeriods`: `39` (pokrywa wszystkie miesiące do bieżącego)
- `initialBalance`: Saldo początkowe z pliku CSV

```bash
curl -X POST "http://localhost:9090/cash-flow/with-history" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "'$USER_ID'",
    "name": "Nest Bank Test",
    "description": "Test import CSV",
    "bankAccount": {
      "bankName": "Nest Bank",
      "bankAccountNumber": {
        "account": "PL93187010452083105656550001",
        "denomination": {"id": "PLN"}
      },
      "balance": {"amount": 0, "currency": "PLN"}
    },
    "startPeriod": "2022-12",
    "historicalPeriods": 39,
    "initialBalance": {"amount": 35369.36, "currency": "PLN"}
  }'
```

**Oczekiwana odpowiedź:** CashFlow ID (np. `CF10000001`)

**Zapisz:** `CF_ID` = odpowiedź

---

### KROK 3: Transformacja CSV przez AI

**Endpoint:** `POST /api/v1/bank-data-adapter/transform`

```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-adapter/transform" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@src/test/resources/lista_operacji_20260111.csv" \
  -F "bankHint=Nest Bank"
```

**Oczekiwana odpowiedź:**
```json
{
  "transformationId": "3e024450-60ae-4620-a3a6-4332c04d7dbe",
  "success": true,
  "detectedBank": "Nest Bank",
  "detectedLanguage": "pl",
  "detectedCountry": "PL",
  "rowCount": 402,
  "warnings": [],
  "importStatus": "PENDING"
}
```

**Zapisz:** `TRANSFORMATION_ID` = transformationId

**Czas wykonania:**
- **Bez cache (pierwsze wywołanie):** ~10-15 sekund (wywołanie AI API)
- **Z cache (kolejne wywołania):** ~50ms

**Sprawdź logi:**
```bash
docker logs vidulum-app 2>&1 | grep -i "cache"
# ❌ Cache MISS - calling AI for mapping rules (pierwsze wywołanie)
# ✅ Cache HIT for bank: Nest Bank (kolejne wywołania)
```

---

### KROK 4: Pobranie przetransformowanego CSV

**Endpoint:** `GET /api/v1/bank-data-adapter/{transformationId}/download`

```bash
curl -s "http://localhost:9090/api/v1/bank-data-adapter/$TRANSFORMATION_ID/download" \
  -H "Authorization: Bearer $TOKEN" \
  -o /tmp/transformed.csv

head -5 /tmp/transformed.csv
```

**Oczekiwany format CSV:**
```csv
bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
TXN-1815220586,,Prowizja za przelew...,Opłaty i prowizje,-10,,OUTFLOW,2025-12-31,,,
```

---

### KROK 5: Utworzenie Staging Session

**Endpoint:** `POST /api/v1/bank-data-ingestion/cf={cashFlowId}/staging`

Wymaga konwersji CSV na JSON. Użyj skryptu Python:

```python
import csv
import json
import requests
from datetime import datetime

TOKEN = "your_token"
CF_ID = "CF10000001"
CSV_FILE = "/tmp/transformed.csv"

transactions = []
with open(CSV_FILE, 'r', encoding='utf-8') as f:
    reader = csv.DictReader(f)
    for row in reader:
        op_date = row['operationDate']
        formatted_date = datetime.strptime(op_date, '%Y-%m-%d').strftime('%Y-%m-%dT00:00:00Z')

        amount = float(row['amount'].replace(',', '.'))

        tx = {
            "bankTransactionId": row['bankTransactionId'],
            "name": row['name'] or "",
            "description": row['description'] or "",
            "bankCategory": row['bankCategory'] or "",
            "amount": abs(amount),
            "currency": row['currency'] or "PLN",
            "type": row['type'],
            "operationDate": formatted_date,
            "bookingDate": formatted_date,
            "paidDate": formatted_date,  # WYMAGANE!
            "sourceAccountNumber": row.get('sourceAccountNumber', ''),
            "targetAccountNumber": row.get('targetAccountNumber', '')
        }
        transactions.append(tx)

response = requests.post(
    f"http://localhost:9090/api/v1/bank-data-ingestion/cf={CF_ID}/staging",
    json={"transactions": transactions},
    headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"}
)

print(response.json())
```

**Oczekiwana odpowiedź:**
```json
{
  "stagingSessionId": "0bf301da-bbb5-49f3-acd5-b9c8964e9ff2",
  "cashFlowId": "CF10000001",
  "status": "HAS_UNMAPPED_CATEGORIES",
  "summary": {
    "totalTransactions": 402,
    "validTransactions": 0,
    "invalidTransactions": 0,
    "duplicateTransactions": 0
  },
  "unmappedCategories": [
    {"bankCategory": "Przelewy przychodzące", "count": 37, "type": "INFLOW"},
    {"bankCategory": "Przelewy wychodzące", "count": 334, "type": "OUTFLOW"},
    {"bankCategory": "Opłaty i prowizje", "count": 30, "type": "OUTFLOW"},
    {"bankCategory": "Płatności kartą", "count": 1, "type": "OUTFLOW"}
  ]
}
```

**Zapisz:** `SESSION_ID` = stagingSessionId

---

### KROK 6: Konfiguracja mapowań kategorii

**Endpoint:** `POST /api/v1/bank-data-ingestion/cf={cashFlowId}/mappings`

**Dostępne akcje (MappingAction):**
- `CREATE_NEW` - tworzy nową kategorię
- `CREATE_SUBCATEGORY` - tworzy podkategorię
- `MAP_TO_UNCATEGORIZED` - mapuje do "Uncategorized"

```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/cf=$CF_ID/mappings" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "mappings": [
      {"bankCategoryName": "Przelewy przychodzące", "action": "CREATE_NEW", "targetCategoryName": "Income", "categoryType": "INFLOW"},
      {"bankCategoryName": "Przelewy wychodzące", "action": "CREATE_NEW", "targetCategoryName": "Transfers", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Opłaty i prowizje", "action": "CREATE_NEW", "targetCategoryName": "Bank Fees", "categoryType": "OUTFLOW"},
      {"bankCategoryName": "Płatności kartą", "action": "CREATE_NEW", "targetCategoryName": "Card Payments", "categoryType": "OUTFLOW"}
    ]
  }'
```

**Oczekiwana odpowiedź:**
```json
{
  "cashFlowId": "CF10000001",
  "mappingsConfigured": 4,
  "mappings": [
    {"bankCategoryName": "Przelewy przychodzące", "targetCategoryName": "Income", "action": "CREATE_NEW", "status": "CREATED"},
    ...
  ]
}
```

---

### KROK 7: Rewalidacja Staging Session

**Endpoint:** `POST /api/v1/bank-data-ingestion/cf={cashFlowId}/staging/{sessionId}/revalidate`

```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/cf=$CF_ID/staging/$SESSION_ID/revalidate" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json"
```

**Oczekiwana odpowiedź:**
```json
{
  "stagingSessionId": "0bf301da-bbb5-49f3-acd5-b9c8964e9ff2",
  "cashFlowId": "CF10000001",
  "status": "SUCCESS",
  "summary": {
    "totalTransactions": 402,
    "revalidatedCount": 402,
    "stillPendingCount": 0,
    "validCount": 402,
    "invalidCount": 0
  },
  "stillUnmappedCategories": []
}
```

---

### KROK 8: Uruchomienie importu

**Endpoint:** `POST /api/v1/bank-data-ingestion/cf={cashFlowId}/import`

```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/cf=$CF_ID/import" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"stagingSessionId": "'$SESSION_ID'"}'
```

**Oczekiwana odpowiedź:**
```json
{
  "jobId": "a9e0fd42-3f64-4ba4-9b71-719b7422d81c",
  "cashFlowId": "CF10000001",
  "status": "COMPLETED",
  "input": {
    "totalTransactions": 402,
    "validTransactions": 402,
    "categoriesToCreate": 4
  },
  "progress": {
    "percentage": 100,
    "phases": [
      {"name": "CREATING_CATEGORIES", "status": "COMPLETED", "processed": 4, "total": 4},
      {"name": "IMPORTING_TRANSACTIONS", "status": "COMPLETED", "processed": 402, "total": 402}
    ]
  },
  "result": {
    "categoriesCreated": ["Income", "Transfers", "Bank Fees", "Card Payments"],
    "transactionsImported": 402,
    "transactionsFailed": 0
  },
  "canRollback": true
}
```

---

### KROK 9: Atestacja importu historycznego

**Endpoint:** `POST /cash-flow/cf={cashFlowId}/attest-historical-import`

Wymagane po imporcie danych historycznych. Podaj rzeczywiste saldo końcowe z wyciągu bankowego:

```bash
curl -X POST "http://localhost:9090/cash-flow/cf=$CF_ID/attest-historical-import" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "confirmedBalance": {"amount": 79057.25, "currency": "PLN"},
    "createAdjustment": true,
    "forceAttestation": false
  }'
```

**Parametry:**
- `confirmedBalance` - rzeczywiste saldo z banku
- `createAdjustment` - `true` tworzy transakcję korygującą przy różnicy
- `forceAttestation` - `true` wymusza atestację bez korekty

**Oczekiwana odpowiedź:**
```json
{
  "cashFlowId": "CF10000001",
  "confirmedBalance": {"amount": 79057.25, "currency": "PLN"},
  "calculatedBalance": {"amount": 110553.67, "currency": "PLN"},
  "difference": {"amount": -31496.42, "currency": "PLN"},
  "adjustmentCreated": true,
  "adjustmentCashChangeId": "CC1000000403",
  "status": "OPEN"
}
```

---

### KROK 10: Weryfikacja CashFlow

**Endpoint:** `GET /cash-flow/cf={cashFlowId}`

```bash
curl -s "http://localhost:9090/cash-flow/cf=$CF_ID" \
  -H "Authorization: Bearer $TOKEN" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(f'Status: {d[\"status\"]}')
print(f'Name: {d[\"name\"]}')
print(f'Start Period: {d[\"startPeriod\"]}')
print(f'Active Period: {d[\"activePeriod\"]}')
print(f'Inflow Categories: {[c[\"categoryName\"][\"name\"] for c in d[\"inflowCategories\"]]}')
print(f'Outflow Categories: {[c[\"categoryName\"][\"name\"] for c in d[\"outflowCategories\"]]}')
total_tx = sum(len(v) for v in d[\"cashChanges\"].values())
print(f'Total CashChanges: {total_tx}')
"
```

**Oczekiwany wynik:**
```
Status: OPEN
Name: Nest Bank Test
Start Period: 2022-12
Active Period: 2026-03
Inflow Categories: ['Uncategorized', 'Income']
Outflow Categories: ['Uncategorized', 'Bank Fees', 'Transfers', 'Card Payments']
Total CashChanges: 4433
```

---

### KROK 11: Weryfikacja Forecast

**Endpoint:** `GET /cash-flow-forecast/cf={cashFlowId}`

```bash
curl -s "http://localhost:9090/cash-flow-forecast/cf=$CF_ID" \
  -H "Authorization: Bearer $TOKEN" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(f'CashFlow ID: {d[\"cashFlowId\"]}')
forecasts = d.get('forecasts', {})
print(f'Total periods: {len(forecasts)}')

status_counts = {}
for period, data in forecasts.items():
    status = data.get('status', 'UNKNOWN')
    status_counts[status] = status_counts.get(status, 0) + 1
print(f'Status breakdown: {status_counts}')
"
```

**Oczekiwany wynik:**
```
CashFlow ID: CF10000001
Total periods: 51
Status breakdown: {'IMPORTED': 39, 'ACTIVE': 1, 'FORECASTED': 11}
```

---

## Statusy miesięcy w Forecast

| Status | Opis |
|--------|------|
| `IMPORTED` | Dane historyczne zaimportowane z CSV |
| `ACTIVE` | Bieżący miesiąc (aktywny) |
| `FORECASTED` | Przyszłe miesiące (prognoza) |

---

## Rozwiązywanie problemów

### Błąd: "OpenAI API key must be set"
```bash
# Sprawdź zmienne środowiskowe w docker-compose-final.yml
# OPENAI_API_KEY lub ANTHROPIC_API_KEY musi być ustawiony
```

### Błąd: "paidDate is null"
Pole `paidDate` jest wymagane w transakcjach. Ustaw je na tę samą wartość co `operationDate`.

### Błąd: "HAS_UNMAPPED_CATEGORIES"
Musisz skonfigurować mapowania (KROK 6) przed importem.

### Dane z cache (poprzednich testów)
```bash
# Usuń wolumeny i uruchom od nowa
docker-compose -f docker-compose-final.yml down -v
docker-compose -f docker-compose-final.yml up -d
```

### Sprawdzanie logów AI
```bash
docker logs vidulum-app 2>&1 | grep -i "cache\|transform\|AI"
```

---

## Automatyczny skrypt testowy

Pełny skrypt testowy znajduje się w: `/tmp/full_test.sh`

```bash
# Uruchomienie
chmod +x /tmp/full_test.sh
bash /tmp/full_test.sh
```

---

## Podsumowanie przepływu

```
1. Register User → TOKEN, USER_ID
2. Create CashFlow → CF_ID
3. AI Transform CSV → TRANSFORMATION_ID
4. Download Transformed CSV
5. Create Staging Session → SESSION_ID
6. Configure Category Mappings
7. Revalidate Staging
8. Start Import → JOB_ID
9. Attest Historical Import
10. Verify CashFlow
11. Verify Forecast
```

**Czas całego przepływu:** ~30-60 sekund (zależnie od wielkości pliku i cache AI)
