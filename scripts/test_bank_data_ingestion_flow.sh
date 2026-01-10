#!/bin/bash
# Full Bank Data Ingestion Flow Test
# Tests: mappings -> staging -> import -> finalize
#
# Prerequisites:
#   - Docker containers running (docker-compose -f docker-compose-final.yml up -d)
#   - vidulum-app container must be started
#
# Usage: ./scripts/test_bank_data_ingestion_flow.sh

set -e

BASE_URL="http://localhost:9090"

echo "========================================"
echo "FULL BANK DATA INGESTION FLOW TEST"
echo "========================================"

# Get Admin token from Docker logs
echo ""
echo "=== Getting Admin token from vidulum-app logs ==="
TOKEN=$(docker logs vidulum-app 2>&1 | grep -i "Admin token:" | tail -1 | sed 's/.*Admin token: //')

if [ -z "$TOKEN" ]; then
  echo "ERROR: Could not find Admin token in vidulum-app logs!"
  echo "Make sure the vidulum-app container is running."
  exit 1
fi

echo "Token: ${TOKEN:0:50}..."

echo ""
echo "=== STEP 1: Create CashFlow with History (SETUP mode) ==="
CF_ID=$(curl -s -X POST "$BASE_URL/cash-flow/with-history" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"userId": "Admin", "name": "Full Flow Test", "description": "Testing complete import flow", "bankAccount": {"currency": "PLN"}, "startPeriod": "2024-10", "initialBalance": {"amount": 10000, "currency": "PLN"}}' | tr -d '"')

echo "CashFlow ID: $CF_ID"

echo ""
echo "=== STEP 2: Configure Mappings ==="
curl -s -X POST "$BASE_URL/api/v1/bank-data-ingestion/$CF_ID/mappings" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"mappings": [{"bankCategoryName": "PRZELEW_IN", "action": "CREATE_NEW", "targetCategoryName": "Salary", "categoryType": "INFLOW"}, {"bankCategoryName": "KARTA", "action": "CREATE_NEW", "targetCategoryName": "Shopping", "categoryType": "OUTFLOW"}, {"bankCategoryName": "PRZELEW_OUT", "action": "CREATE_NEW", "targetCategoryName": "Bills", "categoryType": "OUTFLOW"}]}'

echo ""
echo ""
echo "=== STEP 3: Get Mappings (verify) ==="
curl -s "$BASE_URL/api/v1/bank-data-ingestion/$CF_ID/mappings" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

echo ""
echo "=== STEP 4: Stage Transactions ==="
STAGE_RESULT=$(curl -s -X POST "$BASE_URL/api/v1/bank-data-ingestion/$CF_ID/staging" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "transactions": [
      {"bankTransactionId": "TXN001", "name": "Wyplata za listopad", "description": "Przelew przychodzacy", "bankCategory": "PRZELEW_IN", "amount": 5000.00, "currency": "PLN", "type": "INFLOW", "paidDate": "2024-11-15T10:00:00Z"},
      {"bankTransactionId": "TXN002", "name": "Zakupy Biedronka", "description": "Platnosc karta", "bankCategory": "KARTA", "amount": 150.00, "currency": "PLN", "type": "OUTFLOW", "paidDate": "2024-11-16T14:30:00Z"},
      {"bankTransactionId": "TXN003", "name": "Czynsz", "description": "Przelew wychodzacy", "bankCategory": "PRZELEW_OUT", "amount": 2000.00, "currency": "PLN", "type": "OUTFLOW", "paidDate": "2024-11-20T09:00:00Z"}
    ]
  }')

echo "$STAGE_RESULT" | python3 -m json.tool
SESSION_ID=$(echo "$STAGE_RESULT" | python3 -c "import sys, json; print(json.load(sys.stdin).get('stagingSessionId', ''))")
echo "Staging Session ID: $SESSION_ID"

echo ""
echo "=== STEP 5: Get Staging Preview ==="
curl -s "$BASE_URL/api/v1/bank-data-ingestion/$CF_ID/staging/$SESSION_ID" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

echo ""
echo "=== STEP 6: Start Import Job ==="
IMPORT_RESULT=$(curl -s -X POST "$BASE_URL/api/v1/bank-data-ingestion/$CF_ID/import" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"stagingSessionId\": \"$SESSION_ID\"}")

echo "$IMPORT_RESULT" | python3 -m json.tool
JOB_ID=$(echo "$IMPORT_RESULT" | python3 -c "import sys, json; print(json.load(sys.stdin).get('jobId', ''))")
echo "Import Job ID: $JOB_ID"

echo ""
echo "=== STEP 7: Get Import Progress ==="
sleep 2
curl -s "$BASE_URL/api/v1/bank-data-ingestion/$CF_ID/import/$JOB_ID" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

echo ""
echo "=== STEP 8: Finalize Import ==="
FINALIZE_RESULT=$(curl -s -X POST "$BASE_URL/api/v1/bank-data-ingestion/$CF_ID/import/$JOB_ID/finalize" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"deleteMappings": false}')
echo "$FINALIZE_RESULT" | python3 -m json.tool

echo ""
echo "=== STEP 9: Verify CashFlow has imported transactions ==="
CF_DATA=$(curl -s "$BASE_URL/cash-flow/$CF_ID" \
  -H "Authorization: Bearer $TOKEN")

STATUS=$(echo "$CF_DATA" | python3 -c "import sys, json; print(json.load(sys.stdin).get('status', ''))")
CHANGES=$(echo "$CF_DATA" | python3 -c "import sys, json; print(len(json.load(sys.stdin).get('cashChanges', {})))")

echo "CashFlow Status: $STATUS"
echo "Cash Changes Count: $CHANGES"

echo ""
echo "========================================"
if [ "$CHANGES" -ge 3 ]; then
  echo "SUCCESS! Full import flow completed!"
  echo "All 3 transactions were imported."
else
  echo "WARNING: Expected 3 transactions, got $CHANGES"
fi
echo "========================================"
