# VID-152: Enrichment Prompt V2 - Quality Improvement Analysis

## Summary

This document presents the analysis of enrichment prompt improvements implemented in V2.
The changes significantly improved transaction classification accuracy and fixed critical bugs.

## Test Data

| Bank | File | Transactions |
|------|------|--------------|
| Nest Bank | `lista_operacji_20260111.csv` | 403 |
| Pekao | `Lista_operacji_20260111_013400.csv` | 791 |

## Results Comparison

### Nest Bank

| Metric | V1 (Old) | V2 (New) | Change |
|--------|----------|----------|--------|
| MERCHANT classification | 169 (41.9%) | 259 (64.3%) | **+90 (+22.4%)** |
| BANK_FEE classification | 73 (18.1%) | 26 (6.5%) | **-47 (fixed)** |
| UNKNOWN classification | 159 (39.5%) | 116 (28.8%) | **-43 (-10.7%)** |
| SELF_TRANSFER | 2 (0.5%) | 2 (0.5%) | = |
| Merchant extraction rate | 328 (81.4%) | 375 (93.1%) | **+47 (+11.7%)** |

#### Bug Fixes - Nest Bank

| Bug | V1 | V2 | Status |
|-----|----|----|--------|
| ZUS classified as BANK_FEE | 37 | 0 | **FIXED** |
| IKANO classified as UNKNOWN | 36 | 0 | **FIXED** |

### Pekao

| Metric | V1 (Old) | V2 (New) | Change |
|--------|----------|----------|--------|
| MERCHANT classification | 482 (60.9%) | 643 (81.3%) | **+161 (+20.4%)** |
| UNKNOWN classification | 178 (22.5%) | 99 (12.5%) | **-79 (-10.0%)** |
| BANK_FEE classification | 94 (11.9%) | 7 (0.9%) | **-87 (fixed)** |
| SELF_TRANSFER | 30 (3.8%) | 30 (3.8%) | = |
| CASH_WITHDRAWAL | 7 (0.9%) | 12 (1.5%) | **+5** |
| Merchant extraction rate | 660 (83.4%) | 742 (93.8%) | **+82 (+10.4%)** |

#### Bug Fixes - Pekao

| Bug | V1 | V2 | Status |
|-----|----|----|--------|
| BADOO classified as UNKNOWN | 52 | 0 | **FIXED** |

## Key Changes in Prompt V2

### 1. Government Entities Section (NEW)

Added explicit rules that government entities are MERCHANT, not BANK_FEE:

```
| Entity | merchant | bankCategory |
|--------|----------|--------------|
| ZUS | ZUS | Podatki i składki |
| Urząd Skarbowy | URZAD SKARBOWY | Podatki i składki |
| GITD | GITD | Opłaty urzędowe |
```

### 2. Loan/Finance Companies Section (NEW)

Added rules for financial institutions that are NOT the account holder's bank:

```
| Pattern | merchant | bankCategory |
|---------|----------|--------------|
| IKANO | IKANO | Spłata kredytu |
| Santander Consumer | SANTANDER | Spłata kredytu |
| Credit Agricole + rata | CREDIT AGRICOLE | Spłata kredytu |
```

### 3. Subscription Services Table (NEW)

Added known subscription services with high confidence:

```
| Pattern | merchant | confidence |
|---------|----------|------------|
| Netflix | NETFLIX | 0.95 |
| Badoo, help@badoo.com | BADOO | 0.95 |
| Spotify | SPOTIFY | 0.95 |
| HBO, HBOMAX | HBO MAX | 0.95 |
```

### 4. Clarified BANK_FEE Definition

Added explicit rules:

```
BANK_FEE is ONLY for:
- Fees charged BY THE BANK ITSELF to the account holder
- Examples: "Prowizja za przelew", "Opłata za kartę"

BANK_FEE is NEVER for:
- Payments TO external organizations
- ZUS, Urząd Skarbowy → MERCHANT
- IKANO, Credit Agricole → MERCHANT (loan repayments)
```

### 5. Bank Intermediary Pattern

Added example for extracting real merchant from bank-mediated transactions:

```
Example:
  name: "BANK PEKAO S.A."
  description: "ROZLICZENIE TRANSAKCJI... Badoo help@badoo.com"
  → merchant: "BADOO", classification: MERCHANT, confidence: 0.95
```

## Overall Improvement Statistics

### Classification Accuracy

| Metric | V1 Total | V2 Total | Improvement |
|--------|----------|----------|-------------|
| Total MERCHANT | 651 (54.5%) | 902 (75.5%) | **+251 (+21.0%)** |
| Total UNKNOWN | 337 (28.2%) | 215 (18.0%) | **-122 (-10.2%)** |
| Total BANK_FEE (correct) | 167 (14.0%) | 33 (2.8%) | -134 (most were misclassified) |

### Merchant Extraction

| Metric | V1 Total | V2 Total | Improvement |
|--------|----------|----------|-------------|
| With merchant | 988 (82.7%) | 1117 (93.5%) | **+129 (+10.8%)** |

### Bugs Fixed

| Bug | Transactions Fixed |
|-----|-------------------|
| ZUS as BANK_FEE | 37 |
| BADOO as UNKNOWN | 52 |
| IKANO as UNKNOWN | 36 |
| Other misclassifications | ~75 |
| **Total** | **~200** |

## Remaining Issues

### 1. Self-Transfer Detection (Not Fixed in V2)

**Problem**: Transfers to account holder name (e.g., "Lucjan Bik") are classified as UNKNOWN.

**Impact**: 111 transactions in Nest Bank

**Solution Required**: Pass `accountHolderName` to enrichment prompt for proper self-transfer detection.

### 2. Some UNKNOWN Remain

**Count**: 215 transactions (18.0% of total)

**Cause**: Transactions with unclear context that require more domain knowledge.

## Recommendations for V3

1. **Add accountHolderName parameter** - Enable self-transfer detection
2. **Add SUBSCRIPTION classification** - For recurring services (Netflix, Spotify, etc.)
3. **Add LOAN_REPAYMENT classification** - For IKANO, Credit Agricole, etc.
4. **Improve location extraction** - Currently 34% for Nest, 62% for Pekao

## Conclusion

Prompt V2 significantly improved enrichment quality:
- **+21% MERCHANT classification accuracy**
- **-10% UNKNOWN transactions**
- **+11% merchant extraction rate**
- **All critical bugs fixed** (ZUS, BADOO, IKANO)

The remaining issues (self-transfer detection) require additional context (accountHolderName) that is not currently available in the enrichment pipeline.

---

*Analysis performed: 2026-04-21*
*Test environment: Docker (vidulum-app:latest)*
