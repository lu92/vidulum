# VID-152: Enrichment Analysis & Transaction Classification Proposal

**Date**: 2026-04-21
**Status**: Analysis / Design Proposal
**Related**: VID-152 (Enrichment Prompt Design)

## Table of Contents

1. [Current Enrichment Analysis](#current-enrichment-analysis)
2. [Problem Statement](#problem-statement)
3. [Transaction Classification Proposal](#transaction-classification-proposal)
4. [Detailed Analysis by Transaction Type](#detailed-analysis-by-transaction-type)
5. [Impact on Categorize-AI](#impact-on-categorize-ai)
6. [Canonical CSV Schema Changes](#canonical-csv-schema-changes)
7. [Implementation Recommendations](#implementation-recommendations)

---

## Current Enrichment Analysis

### How Enrichment Works (EnrichmentPromptBuilder.java)

The enrichment prompt sends **both `name` AND `description`** to AI for merchant extraction.

#### Algorithm Steps:

| Step | Description | Example |
|------|-------------|---------|
| 1 | If name = bank intermediary (BANK PEKAO, PKO BP) → search merchant in description | `BANK PEKAO` + desc `Badoo help@badoo.com` → `BADOO` |
| 2 | Search for emails in description → use domain | `help@badoo.com` → `BADOO` |
| 3 | Search for known brands in name | `NETFLIX.COM 866-579-7172` → `NETFLIX` |
| 4 | Clean name from suffixes (S.A., SP. Z O.O., addresses, codes) | `SHIVAGO SPOLKA Z OGRAN MIELEC` → `SHIVAGO` |
| 5 | Fallback: first recognizable word, confidence 0.3 | `UL. WOLNOSCI 23B MIELEC` → `UL. WOLNOSCI` (0.3) |

#### Merchant Confidence Scale:

| Confidence | Meaning |
|------------|---------|
| **0.95+** | Known brand (Netflix, Allegro, email domain) |
| **0.8-0.95** | Clear company/person name |
| **0.5-0.8** | Inferred from context |
| **<0.5** | Uncertain, used fallback |

### Test Results (Pekao & Nest Bank)

#### Pekao (791 transactions):
- High confidence (≥0.8): **70.0%**
- Medium confidence (0.5): **12.9%**
- Low confidence (≤0.3): **17.1%** (135 transactions)

#### Nest Bank (402 transactions):
- High confidence (0.95): **26.4%**
- Good confidence (0.8): **66.2%**
- Low confidence (0.3): **7.5%** (30 transactions)

---

## Problem Statement

Currently, enrichment tries to extract `merchant` from **every** transaction, even when:

1. **Bank fees** - no merchant (bank is not a counterparty)
2. **Self transfers** - transfer between own accounts
3. **ATM withdrawals** - terminal is not a merchant
4. **Cash deposits** - no counterparty

This leads to artificial merchants like:
- `PROWIZJA` (from "Prowizja za przelew")
- `UL. WOLNOSCI` (from address "UL. WOLNOSCI 23B MIELEC")
- `00146 2703W250H` (ATM terminal code)
- `PRZELEW` (from "Przelew środków")

### Low Confidence Transactions Analysis

#### Pekao - Low Confidence (≤0.3) Examples:

| Type | Examples | Problem |
|------|----------|---------|
| **Addresses as merchants** | `UL. WOLNOSCI 23B`, `LOPUSZANSKA 22`, `UL. JAGIELLONCZYKA` | Not businesses! |
| **ALLEGRO with 0.1** | `ALLEGRO SP. Z O.O.` + BLIK REF | Known brand should have 0.95 |
| **Shortened names** | `GZGK W MIELCU`, `WARSZAWA SPPN`, `PL BK` | Unknown local entities |
| **Bank fees** | `Opłata za polecenie przelewu`, `POBRANIE ZALEGŁEJ OPŁATY` | Not merchants |
| **Terminal codes** | `00146 2703W250H` | ATM withdrawal |

#### Nest Bank - Low Confidence (≤0.3) Examples:

| Type | Examples | Problem |
|------|----------|---------|
| **Bank fees** (26x) | `Prowizja za przelew natychmiastowy wychodzący KIR` | Not a merchant! |
| **Transfer funds** (2x) | `Przelew środków` | Too generic |
| **Typo** | `zycie` → `ZYDIE` | Typo in name! |

### Comparison with Original CSV

#### Pekao Examples:

| Original CSV | Enriched | Confidence | Assessment |
|--------------|----------|------------|------------|
| `ALLEGRO SP. Z O.O.` + `BLIK REF 92917213065` | `ALLEGRO` | **0.1** | ❌ Too low! |
| `GZGK W MIELCU` + `KTR: 00192037` | `GZGK` | 0.3 | ⚠️ OK for unknown |
| `UL. WOLNOSCI 23B MIELEC` + `*0015010` | `UL. WOLNOSCI` | 0.3 | ❌ Address, not merchant |
| `BANK PEKAO S.A.` + `Badoo help@badoo.com` | `BADOO` | **0.95** | ✅ Correctly extracted from description |

#### Nest Bank Examples:

| Original CSV | Enriched | Confidence | Assessment |
|--------------|----------|------------|------------|
| `"ZUS",29600...,składki ZUS` | `ZUS` | **0.95** | ✅ Excellent |
| `"MINDBOX SPÓŁKA Z OGRANICZONĄ..."` | `MINDBOX` | **0.95** | ✅ Excellent |
| `"Prowizja za przelew natychmiastowy"` | `PROWIZJA` | 0.3 | ⚠️ Not a merchant (bank fee) |

---

## Transaction Classification Proposal

Instead of just `merchant` + `bankCategory`, enrichment should return an additional field:

```
transactionType: "MERCHANT" | "BANK_FEE" | "CASH_WITHDRAWAL" | "CASH_DEPOSIT" | "SELF_TRANSFER" | "SALARY" | "UNKNOWN"
```

### Classification Types:

| Classification | When | Merchant | Business Value |
|----------------|------|----------|----------------|
| `MERCHANT` | Payment to seller | **Required** | Expense grouping |
| `BANK_FEE` | Fees, commissions | `null` or `BANK_FEE` | Auto-category "Bank fees" |
| `CASH_WITHDRAWAL` | ATM, withdrawal | `null` or `ATM` | Auto-category "Cash" |
| `CASH_DEPOSIT` | Cash deposit | `null` | - |
| `SELF_TRANSFER` | Transfer between own accounts | Own name or `null` | Ignored in budget |
| `SALARY` | Salary/wages | Employer | Auto-category "Income" |
| `UNKNOWN` | Cannot determine | Fallback | Requires manual categorization |

---

## Detailed Analysis by Transaction Type

### 1. Bank Fees (`BANK_FEE`)

**Question**: Should we add `isBankFee` boolean to canonical-csv?

**Answer**: YES, but recommend broader approach - `transactionType` instead of single boolean.

**Benefits**:
- **Automatic categorization** - no AI needed in categorize-ai
- **Cleaner data** - no artificial merchants
- **Better reports** - can filter bank fees from expenses
- **UX** - user doesn't need to manually categorize fees

**Merchant handling options**:

| Option | Merchant | Pros | Cons |
|--------|----------|------|------|
| A) `null` | none | Clean data | Requires null handling |
| B) `BANK_FEE` | constant value | Easy grouping | Artificial merchant |

**Recommendation**: Option A with `transactionType: "BANK_FEE"` - merchant can be `null`, but classification is clear.

### 2. Money Transfers

**Problem**: `Lucjan Bik Pekao` → is this a merchant?

**Proposed differentiation**:

| Transfer Type | How to recognize | Merchant | Classification |
|---------------|------------------|----------|----------------|
| **Self transfer** | Same owner, different account | `null` | `SELF_TRANSFER` |
| **Personal transfer** | Different natural person | First and last name | `PERSONAL_TRANSFER` |
| **Company transfer** | Company name | Company name | `MERCHANT` |
| **Salary** | "wynagrodzenie", "pensja" in title | Employer | `SALARY` |

**For `SELF_TRANSFER`**: Can use special merchant `INTERNAL_TRANSFER` or `null`.

### 3. ATM Withdrawals (Terminal Codes)

**Example**: `00146 2703W250H WARSZAWA`

**Proposal**:

```json
{
  "transactionType": "CASH_WITHDRAWAL",
  "merchant": null,
  "atmLocation": "WARSZAWA"
}
```

**Business value**:
- Auto-category "Cash withdrawals"
- Can track how much user withdraws
- Doesn't pollute merchant list

---

## Impact on Categorize-AI

If enrichment returns `transactionType`, then in `categorize-ai`:

1. **Skip transactions with auto-category** - `BANK_FEE`, `CASH_WITHDRAWAL`, `SELF_TRANSFER`
2. **Group only `MERCHANT`** - real purchases
3. **Special handling for `SALARY`** - suggest "Income" category

This will **reduce** the number of transactions to categorize and **improve** quality.

### Current vs Proposed Flow:

```
CURRENT:
CSV → Transform → Enrich (all) → Categorize-AI (all) → Staging

PROPOSED:
CSV → Transform → Enrich (all + classify) → Categorize-AI (only MERCHANT) → Staging
                                           ↓
                              Auto-categorize (BANK_FEE, ATM, etc.)
```

---

## Canonical CSV Schema Changes

### Current Schema:

```csv
bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber,merchant,merchantConfidence,paymentMethod
```

### Proposed Additional Fields:

| Field | Type | Description |
|-------|------|-------------|
| `transactionType` | enum | `MERCHANT`, `BANK_FEE`, `CASH_WITHDRAWAL`, `CASH_DEPOSIT`, `SELF_TRANSFER`, `SALARY`, `UNKNOWN` |
| `autoCategory` | string/null | Suggested category (for auto-types) |
| `autoCategoryReason` | string/null | Why auto-categorized |

### Enrichment Response Changes:

**Current**:
```json
{
  "merchant": "PROWIZJA",
  "merchantConfidence": 0.3,
  "bankCategory": "Opłaty bankowe"
}
```

**Proposed**:
```json
{
  "transactionType": "BANK_FEE",
  "merchant": null,
  "merchantConfidence": null,
  "bankCategory": "Opłaty bankowe",
  "autoCategorizationReason": "Detected bank fee pattern: 'Prowizja za przelew'"
}
```

For merchants:
```json
{
  "transactionType": "MERCHANT",
  "merchant": "ALLEGRO",
  "merchantConfidence": 0.95,
  "bankCategory": "Zakupy przez internet",
  "autoCategorizationReason": null
}
```

For withdrawals:
```json
{
  "transactionType": "CASH_WITHDRAWAL",
  "merchant": null,
  "merchantConfidence": null,
  "bankCategory": "Gotówka",
  "atmLocation": "WARSZAWA",
  "autoCategorizationReason": "Detected ATM withdrawal pattern"
}
```

---

## Implementation Recommendations

### Summary Table:

| Transaction Type | Merchant | Auto-category | In AI categorize? |
|------------------|----------|---------------|-------------------|
| **Purchase at seller** | Company name | - | YES |
| **Bank fee** | `null` | "Opłaty bankowe" | NO |
| **ATM withdrawal** | `null` | "Gotówka" | NO |
| **Self transfer** | `null` | "Przelew wewnętrzny" | NO |
| **Personal transfer** | Person name | - | YES (or "Inne") |
| **Salary** | Employer | "Przychody" | NO |

### User Benefits:

- Less manual categorization
- Cleaner merchant lists (no `PROWIZJA`, `UL.`)
- Automatic recognition of bank fees
- Better financial reports (separation of fees from expenses)

### Known Issues to Fix:

1. **ALLEGRO with confidence 0.1** - known brand should have 0.95
2. **Addresses treated as merchants** - AI fallback uses first word
3. **Bank fees don't have merchant** - "PROWIZJA" is not a company
4. **BANK PEKAO logic works** - correctly extracts BADOO from description

### Detection Patterns for Transaction Types:

| Type | Detection Pattern |
|------|-------------------|
| `BANK_FEE` | "prowizja", "opłata za", "fee", "obsługa karty", "pobranie" |
| `CASH_WITHDRAWAL` | ATM codes, "wypłata", "bankomat", terminal patterns |
| `CASH_DEPOSIT` | "wpłata", "wpływ gotówki" |
| `SELF_TRANSFER` | Same owner name, "przelew własny", same account prefix |
| `SALARY` | "wynagrodzenie", "pensja", "salary", known employer patterns |
| `MERCHANT` | Everything else with valid business name |

---

## Appendix: Sample Data Analysis

### Nest Bank Transformation Stats:
```
transformationId: 2e827839-735f-49d1-8b07-6e6e6280aaf3
detectedBank: Nest Bank
rowCount: 403
processingTimeMs: 50454
enrichmentApplied: true
merchantsExtracted: 403
bankCategoriesInferred: 165
bankCategoriesKept: 0
enrichmentTimeMs: 37220
enrichmentAiCalls: 1
```

### Bug Found: "WAL" Currency

The header row from Nest Bank CSV was incorrectly processed as a transaction:

```
Original header: Data księgowania,Data operacji,Rodzaj operacji,Kwota,Waluta,...
Processed as:    amount=Kwota, currency=WAL (short for "Waluta"), merchant=DANE
```

**Cause**: AI CSV transform did not properly filter out the column header row (row 7 in Nest file).
**Impact**: One transaction with currency="WAL" could be imported to cashflow.

---

*Document generated during VID-152 enrichment analysis session*
