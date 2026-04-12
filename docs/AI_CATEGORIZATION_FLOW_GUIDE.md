# AI Categorization Flow Guide

This document describes the complete flow for importing bank CSV data with AI-powered transaction categorization.

## Overview

The AI Categorization feature automatically analyzes your bank transactions and suggests a hierarchical category structure. It uses:

1. **Global Pattern Cache** - Pre-defined mappings for known brands (Biedronka, Lidl, ZUS, etc.)
2. **User Pattern Cache** - Learned mappings specific to each user
3. **AI Suggestions** - OpenAI-powered categorization for new/unknown patterns

## Complete Flow

### Step 1: Register User and Create CashFlow

```bash
# Register user
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "test@example.com",
    "password": "SecurePassword123!"
  }'

# Response includes access_token and user_id
# TOKEN=<access_token>
# USER_ID=<user_id>

# Create CashFlow with historical periods
curl -X POST "http://localhost:9090/cash-flow/with-history" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "'$USER_ID'",
    "name": "Main Bank Account",
    "description": "Primary account",
    "bankAccount": {
      "bankName": "My Bank",
      "bankAccountNumber": {
        "account": "PL61109010140000071219812874",
        "denomination": {"id": "PLN"}
      },
      "balance": {"amount": 0, "currency": "PLN"}
    },
    "startPeriod": "2025-12",
    "initialBalance": {"amount": 5000.00, "currency": "PLN"}
  }'

# Response: {"cashFlowId": "CF10000001", ...}
# CF_ID=CF10000001
```

### Step 2: Transform Bank CSV with AI

Upload your bank's CSV export for AI-powered transformation:

```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-adapter/transform" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/bank_export.csv" \
  -F "bankHint=Nest Bank"

# Response:
{
  "transformationId": "cea06412-2d42-4568-9850-f95f2c3096e2",
  "success": true,
  "detectedBank": "Nest Bank",
  "detectedLanguage": "pl",
  "rowCount": 402,
  "minTransactionDate": "2023-01-13",
  "maxTransactionDate": "2025-12-31",
  "monthsOfData": 36
}

# TRANSFORMATION_ID=cea06412-2d42-4568-9850-f95f2c3096e2
```

### Step 3: Import Transformation to Staging

Import the transformed data into a staging session:

```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-adapter/$TRANSFORMATION_ID/import" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cashFlowId": "'$CF_ID'"}'

# Response:
{
  "transformationId": "cea06412-2d42-4568-9850-f95f2c3096e2",
  "stagingSessionId": "2579c506-5b1b-4a35-b120-a619259018a7",
  "importedRows": 402,
  "message": "Transformation imported successfully"
}

# SESSION_ID=2579c506-5b1b-4a35-b120-a619259018a7
```

### Step 4: Request AI Categorization

Trigger AI analysis of the staged transactions:

```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/cf=$CF_ID/staging/$SESSION_ID/ai-categorize" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json"

# Response:
{
  "sessionId": "2579c506-5b1b-4a35-b120-a619259018a7",
  "status": "AI_SUGGESTIONS_READY",
  "suggestedStructure": {
    "outflow": [
      {
        "name": "Oplatery obowiazkowe",
        "subCategories": ["Podatki", "Ubezpieczenia", "Subskrypcje"],
        "transactionCount": 0,
        "totalAmount": 0.0
      },
      // ... more categories
    ],
    "inflow": [
      {
        "name": "Wynagrodzenie",
        "subCategories": ["Pensja", "Premie"],
        "transactionCount": 0,
        "totalAmount": 0.0
      },
      // ... more categories
    ]
  },
  "patternSuggestions": [
    {
      "pattern": "URZAD SKARBOWY MIELCU",
      "sampleTransaction": "Urzad skarbowy w Mielcu",
      "suggestedCategory": "Podatki",
      "parentCategory": "Oplatery obowiazkowe",
      "type": "OUTFLOW",
      "confidence": 95,
      "source": "AI",
      "transactionCount": 51,
      "totalAmount": 330103.40
    },
    {
      "pattern": "ZUS",
      "sampleTransaction": "ZUS payment",
      "suggestedCategory": "Social Security (ZUS)",
      "parentCategory": "Mandatory Fees",
      "type": "OUTFLOW",
      "confidence": 99,
      "source": "GLOBAL",
      "transactionCount": 37,
      "totalAmount": 88764.68
    }
    // ... more patterns
  ],
  "statistics": {
    "totalTransactions": 402,
    "uniquePatterns": 11,
    "cachedPatternMatches": 37,
    "aiSuggestions": 365,
    "autoAcceptCount": 88,
    "needsReviewCount": 314
  }
}
```

### Step 5: Accept AI Suggestions

Accept the AI suggestions to create categories and mappings:

```bash
curl -X POST "http://localhost:9090/api/v1/bank-data-ingestion/cf=$CF_ID/staging/$SESSION_ID/accept-ai" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "acceptedCategories": [
      {"name": "Oplatery obowiazkowe", "parentName": null, "type": "OUTFLOW"},
      {"name": "Podatki", "parentName": "Oplatery obowiazkowe", "type": "OUTFLOW"},
      {"name": "Ubezpieczenia", "parentName": "Oplatery obowiazkowe", "type": "OUTFLOW"},
      // ... more categories
    ],
    "acceptedMappings": [
      {
        "pattern": "URZAD SKARBOWY MIELCU",
        "bankCategory": "Urzad skarbowy w Mielcu",
        "targetCategory": "Podatki",
        "parentCategory": "Oplatery obowiazkowe",
        "type": "OUTFLOW",
        "confidence": 95
      },
      // ... more mappings
    ],
    "saveToCache": true
  }'

# Response:
{
  "cashFlowId": "CF10000033",
  "sessionId": "2579c506-5b1b-4a35-b120-a619259018a7",
  "status": "SUCCESS",
  "categoriesCreated": 40,
  "mappingsApplied": 11,
  "patternsCached": 11,
  "warnings": [],
  "validationSummary": {
    "totalTransactions": 402,
    "validTransactions": 0,
    "invalidTransactions": 0,
    "duplicateTransactions": 0,
    "readyForImport": false
  }
}
```

### Step 6: Verify CashFlow Categories

Check that categories were created in CashFlow:

```bash
curl "http://localhost:9090/cash-flow/cf=$CF_ID" \
  -H "Authorization: Bearer $TOKEN"

# Response shows nested category structure:
{
  "outflowCategories": [
    {
      "categoryName": {"name": "Oplatery obowiazkowe"},
      "subCategories": [
        {"categoryName": {"name": "Podatki"}},
        {"categoryName": {"name": "Ubezpieczenia"}},
        {"categoryName": {"name": "Subskrypcje"}}
      ]
    },
    // ... more categories
  ],
  "inflowCategories": [
    {
      "categoryName": {"name": "Wynagrodzenie"},
      "subCategories": [
        {"categoryName": {"name": "Pensja"}},
        {"categoryName": {"name": "Premie"}}
      ]
    },
    // ... more categories
  ]
}
```

## Pattern Sources and Confidence Levels

| Source | Confidence | Description |
|--------|------------|-------------|
| GLOBAL | 95-100% | Pre-defined patterns for known brands (ZUS, Biedronka, etc.) |
| USER | 90-100% | Patterns learned from user's previous categorizations |
| AI | 50-95% | New patterns suggested by AI |

### Confidence Thresholds

- **90-100%** - AUTO_ACCEPT: Automatically applied without user review
- **50-89%** - SUGGESTED: Requires user confirmation
- **< 50%** - MANUAL: Needs manual categorization

## Pattern Deduplication

The system optimizes AI costs by deduplicating transactions:

- 402 transactions -> 11 unique patterns
- Only unique patterns are sent to AI
- Results are applied to all matching transactions

Example deduplication:
```
Original: "LUCJAN BIK PEKAO" (74 transactions, 329,233.77 PLN)
Pattern: "LUCJAN BIK PEKAO"
-> Single AI call covers all 74 transactions
```

## Supported Banks

Currently supported (with AI auto-detection):
- Nest Bank (Poland)
- mBank (Poland)
- ING Bank (Poland)
- PKO BP (Poland)
- Santander (Poland)
- Pekao (Poland)

## Error Handling

| Error Code | HTTP | Description | Solution |
|------------|------|-------------|----------|
| INVALID_CASHFLOW_ID_FORMAT | 400 | CashFlow ID format invalid | Use format CFXXXXXXXX |
| CASHFLOW_NOT_FOUND | 404 | CashFlow doesn't exist | Verify CF_ID |
| INGESTION_STAGING_NOT_FOUND | 404 | Staging session not found | Create new staging session |
| AI_ADAPTER_TRANSFORMATION_NOT_FOUND | 404 | Transformation ID invalid | Check transformation ID |
| VALIDATION_INVALID_JSON | 400 | Request body malformed | Fix JSON format |
| (no response) | 403 | Missing or invalid token | Check Authorization header |

### Error Scenarios Tested (2026-03-29)

| Test | Input | Expected | Actual | Status |
|------|-------|----------|--------|--------|
| Invalid CashFlow format | CF_INVALID | 400 INVALID_CASHFLOW_ID_FORMAT | 400 INVALID_CASHFLOW_ID_FORMAT | PASS |
| Non-existent CashFlow | CF99999999 | 404 CASHFLOW_NOT_FOUND | 404 CASHFLOW_NOT_FOUND | PASS |
| Non-existent session | 00000000-... | 404 INGESTION_STAGING_NOT_FOUND | 404 INGESTION_STAGING_NOT_FOUND | PASS |
| Empty request body | {} | 400 VALIDATION_INVALID_JSON | 400 VALIDATION_INVALID_JSON | PASS |
| No authentication | - | 403 Forbidden | 403 (empty body) | PASS |
| Invalid token | invalid_token | 403 Forbidden | 403 (empty body) | PASS |
| Invalid category type | INVALID_TYPE | 400 VALIDATION_INVALID_JSON | 400 VALIDATION_INVALID_JSON | PASS |
| Duplicate category | Podatki (exists) | SUCCESS (skip) | SUCCESS, categoriesCreated=0 | PASS |
| Empty CSV import | 0 rows | stagingSessionId=null | stagingSessionId=null | PASS |
| Non-existent transformation | 00000000-... | 404 | 404 AI_ADAPTER_TRANSFORMATION_NOT_FOUND | PASS |

## Configuration

### Environment Variables

```yaml
# AI Configuration
vidulum.ai.provider: openai  # or anthropic
vidulum.ai.openai.api-key: ${OPENAI_API_KEY}
vidulum.ai.openai.model: gpt-4o-mini

# Categorization Settings
vidulum.ai.categorization.auto-accept-threshold: 90
vidulum.ai.categorization.max-patterns-per-request: 50
```

## Test Evidence

Manual testing performed on 2026-03-29:

| Step | Status | Details |
|------|--------|---------|
| User Registration | PASS | User U10000064 created |
| CashFlow Creation | PASS | CF10000033 created |
| CSV Transformation | PASS | 402 rows transformed |
| Staging Import | PASS | Session 2579c506... created |
| AI Categorization | PASS | 11 patterns, 402 transactions analyzed |
| Accept Suggestions | PASS | 40 categories, 11 mappings created |
| CashFlow Verification | PASS | Nested category structure confirmed |

### Error Scenarios Summary

| Test Category | Tests Run | Passed | Failed |
|--------------|-----------|--------|--------|
| Authentication errors | 2 | 2 | 0 |
| Validation errors | 3 | 3 | 0 |
| Not Found errors | 4 | 4 | 0 |
| Edge cases | 1 | 1 | 0 |
| **Total** | **10** | **10** | **0** |
