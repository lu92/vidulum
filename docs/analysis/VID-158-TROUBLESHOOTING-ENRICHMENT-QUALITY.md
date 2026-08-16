# VID-158: Troubleshooting — Enrichment Quality for CSV Without Bank Categories

**Date**: 2026-04-25
**Context**: Import of Nest Bank CSV (`lista_operacji_20260111.csv`, 402 transactions, user lu101/U10000004)
**Result**: 111/402 self-transfers (27.6%) misclassified as UNKNOWN, header row parsed as transaction

## Problem Summary

The Nest Bank CSV has no `bankCategory` column — enrichment must infer everything from transaction name and description.
Enrichment correctly categorized ~64% of transactions but failed critically on self-transfers (111 transactions to user's own Pekao account), classifying them as UNKNOWN instead of SELF_TRANSFER.

## Input Data

CSV columns (no bankCategory):
```
Data księgowania, Data operacji, Rodzaj operacji, Kwota, Waluta, Dane kontrahenta,
Numer rachunku kontrahenta, Tytuł operacji, Saldo po operacji
```

Account owner: `DEV LUCJAN BIK`, account: `93187010452083105656550001` (Nest Bank)

## Timeline

| Time (UTC) | Action | Result |
|------------|--------|--------|
| 21:48:38 | Enrichment applied | 39.7s, 1 AI call, 375 merchants extracted |
| 21:49:36 | Staging session created | 402 transactions, all PENDING_MAPPING |
| 21:49:39 | AI categorize started | |
| 21:50:01 | AI categorize completed | 5426 tokens, ~$0.05 |
| 21:50:23 | Accept AI + force-uncategorized | 19 category mappings created |
| 21:50:27 | Import started | |
| 21:50:34 | Import completed | 402 cash changes |

## Enrichment Analysis

### What Worked Well

| Metric | Value | Assessment |
|--------|-------|------------|
| Merchants extracted | 375/402 (93%) | Good |
| MERCHANT classification | 259 (64%) | Correct |
| BANK_FEE classification | 26 (6.5%) | Correct |
| High confidence | 258 (64%) | Good |
| bankCategories inferred | 285 new | Reasonable |

**Well-recognized patterns:**

| Transaction Name | Merchant | bankCategory | Confidence | Classification |
|-----------------|----------|-------------|------------|----------------|
| Urzad skarbowy w Mielcu | URZAD SKARBOWY | Podatki i składki | 0.95 | MERCHANT |
| ZUS | ZUS | Podatki i składki | 0.95 | MERCHANT |
| Silva Silva, Warszawa | SILVA SILVA | Mieszkanie | 0.8 | MERCHANT |
| MINDBOX SPÓŁKA Z O.O. | MINDBOX | Inne | 0.8 | MERCHANT |
| IKANO | IKANO | Spłata kredytu | 0.95 | MERCHANT |
| IFIRMA SA | IFIRMA | Inne | 0.8 | MERCHANT |
| Prowizja za przelew... | (null) | Opłaty bankowe | — | BANK_FEE |

### Problem #1: Self-Transfers Misclassified (111 transactions = 27.6%)

**Symptom**: 111 transactions "Lucjan Bik Pekao" with description "zycie" to account `PL98124014441111001078171074` classified as `UNKNOWN` with confidence `0.3`.

These are monthly transfers from Nest Bank to user's own Pekao account. They should be `SELF_TRANSFER`.

**Enrichment output for these transactions:**
```csv
TXN-1922942631,Lucjan Bik Pekao,zycie,,3000,PLN,OUTFLOW,2025-12-31,...,LUCJAN BIK,0.3,,UNKNOWN,Unclear transaction related to Lucjan Bik,
```

**Root cause**: `TransactionForEnrichment` class only passes `name` and `description` to the AI prompt — **NOT account numbers**.

The enrichment prompt (`EnrichmentPromptBuilder.java:66-71`) detects SELF_TRANSFER using:
- Keywords: "przelew własny", "przelew wewnętrzny", "own account", "between accounts"
- Same person name appearing as sender AND receiver

But "Lucjan Bik Pekao" + "zycie" doesn't match any keyword pattern. The AI has no way to know:
- Source account owner = "DEV LUCJAN BIK" (Nest Bank)
- Target account = PL98124014441111001078171074 (user's Pekao account)
- Transaction name contains account owner's name → likely self-transfer

**Contrast**: Only 2 transactions ("Przelew środków" / "Przelew środków") were correctly classified as SELF_TRANSFER — because the name explicitly says "Przelew" (transfer).

**Fix needed**: Pass `sourceAccountNumber`, `targetAccountNumber`, and `accountOwnerName` to `TransactionForEnrichment`, so the AI prompt can compare names and detect self-transfers based on account holder matching.

### Problem #2: CSV Header Parsed as Transaction

**Symptom**: `outputRowCount: 403` vs `inputRowCount: 402` — one extra row.

First data row in transformed CSV:
```csv
TXN-145131181,Dane kontrahenta,Tytuł operacji,,Kwota,WAL,INFLOW,Data operacji,,DATAKSIGOWANIA,NUMERRACHUNKUKONTRAHENTA,DANE KONTRAHENTA,0.3,,UNKNOWN,Unclear transaction with generic title,
```

This is the **original CSV header row** (`Dane kontrahenta`, `Tytuł operacji`, `Kwota` are column names!) processed as a transaction. The AI transformation didn't skip it because the Nest Bank CSV has metadata rows before the header:

```
Line 1: Numer rachunku: 93187010452083105656550001,
Line 2: Właściciel: DEV LUCJAN BIK,
...
Line 7: Data księgowania,Data operacji,Rodzaj operacji,Kwota,Waluta,Dane kontrahenta,...
Line 8: 31-12-2025,31-12-2025,Opłaty i prowizje,-10,PLN,,,...
```

The AI likely treated line 7 (the actual header) as data because it looks like a regular comma-separated row.

### Problem #3: "Inne wydatki" Catch-All (166 transactions = 41%)

**Symptom**: 166 transactions mapped to "Inne wydatki" — the largest category by far.

**Breakdown:**
- 111 are LUCJAN BIK self-transfers (misclassified as UNKNOWN → mapped to "Inne wydatki" by AI)
- ~55 are genuine "Inne" (miscellaneous) transactions from enrichment

The AI category mapping created: `LUCJAN BIK → Inne wydatki (confidence: 50)` — a low-confidence catch-all for unrecognized transactions.

## Classification Distribution

| Classification | Count | Assessment |
|---------------|-------|------------|
| MERCHANT | 259 | Correct |
| UNKNOWN | 115 | **~111 should be SELF_TRANSFER** |
| BANK_FEE | 26 | Correct |
| SELF_TRANSFER | 2 | **Should be ~113** |

## Final Category Distribution (after import)

| Category | Count | % | Quality |
|----------|-------|---|---------|
| Inne wydatki | 166 | 41% | Poor — contains 111 misclassified self-transfers |
| Podatki | 88 | 22% | Good |
| Spłata kredytu | 55 | 14% | Good |
| Inne przychody | 36 | 9% | Good |
| Uncategorized | 34 | 8.5% | Expected — unmapped remainder |
| Czynsz | 15 | 3.7% | Good |
| Opłaty urzędowe | 6 | 1.5% | Good |
| Ubezpieczenia | 2 | 0.5% | Good |

## Comparison with lu100 (Pekao CSV)

| Metric | lu100 (Pekao) | lu101 (Nest Bank) |
|--------|---------------|-------------------|
| Transactions | 791 | 402 |
| Has bankCategory in CSV | Yes (35 categories) | No |
| Uncategorized | 694 (87.7%) | 34 (8.5%) |
| Correctly categorized | 97 (12.3%) | 368 (91.5%) |
| Key problem | Bank categories ignored by staging | Self-transfers misclassified |

Paradoxically, the CSV **without** bank categories (Nest Bank) got better categorization than the one **with** rich bank categories (Pekao). This is because the Nest Bank flow relied entirely on AI, which created the category structure from scratch, while Pekao's existing categories had no pathway to be adopted into the CashFlow.

## Affected Code

| Component | File | Issue |
|-----------|------|-------|
| TransactionForEnrichment | `bank_data_adapter/app/enrichment/TransactionForEnrichment.java` | Missing account numbers for SELF_TRANSFER detection |
| EnrichmentPromptBuilder | `bank_data_adapter/app/enrichment/EnrichmentPromptBuilder.java:66-71` | Cannot detect self-transfers without account context |
| AI CSV Transformation | `bank_data_adapter/` | Header row not filtered out for multi-line-header CSVs |

## Additional Finding: AI Enrichment vs Bank Categories — Quality Comparison

Comparison with Pekao (bank-provided categories) reveals complementary strengths:

```
                          PEKAO (bank)    NEST BANK (AI)
                          ────────────    ──────────────
Precision (specific):     91.1%           63.6%           Bank wins
Coverage (non-generic):   66.5%           77.4%           AI wins
Wrong specific category:  2.4%            0.2%            AI wins
Channel confusion:        1.3%            0%              AI wins
Inconsistent taxonomy:    2.3%            0%              AI wins
Classification error:     0%              27.6%           Bank wins
Generic overuse:          33.5%           22.6%           AI wins
```

**Key insight**: Bank errors are **structural and unfixable** (driven by MCC codes). AI errors are **data-driven and fixable** (missing account numbers). The optimal approach combines both: bank categories verified and corrected by AI.

See [VID-160-BANK-VS-AI-CATEGORY-QUALITY-COMPARISON.md](VID-160-BANK-VS-AI-CATEGORY-QUALITY-COMPARISON.md) for the full analysis.

## SELF_TRANSFER Detection — Critical Fix Needed

The self-transfer misclassification (111/402 = 27.6%) is the single largest error source in the entire pipeline across both datasets. It must be fixed.

### Current State

`TransactionForEnrichment` passes only `name` and `description` to the enrichment-prompt. Self-transfer detection relies solely on keywords like "przelew własny", "przelew wewnętrzny". The transaction "Lucjan Bik Pekao" with description "zycie" doesn't match any keyword.

### What the AI Needs But Doesn't Have

```
Available now:                    Missing (needed for SELF_TRANSFER):
  name: "Lucjan Bik Pekao"        sourceAccountOwner: "DEV LUCJAN BIK"
  description: "zycie"             sourceAccountNumber: "93187010452083..."
                                   targetAccountNumber: "PL98124014441..."
```

With owner name "DEV LUCJAN BIK" and transaction name "Lucjan Bik Pekao", the AI could match the name and classify as SELF_TRANSFER with high confidence.

### Proposed Fix: Two-Layer Self-Transfer Detection

**Layer 1: Rule-based (pre-AI, no tokens needed)**

```
For each transaction BEFORE sending to enrichment-prompt:
  ownerName = normalize(cashFlow.accountOwner)       // "LUCJAN BIK"
  txnName = normalize(transaction.name)              // "LUCJAN BIK PEKAO"
  
  if txnName.contains(ownerName) AND transaction.type == OUTFLOW:
    → classification = SELF_TRANSFER
    → merchant = null
    → bankCategory = "Przelewy własne"
    → confidence = 0.9
    → skip AI enrichment for this transaction
```

This would catch 111/111 (100%) of the Lucjan Bik self-transfers without any AI call.

**Layer 2: AI-assisted (for ambiguous cases)**

Pass account context to `TransactionForEnrichment` so the AI can detect self-transfers that don't match by name:

```java
// Current TransactionForEnrichment
public record TransactionForEnrichment(
    int rowIndex,
    String name,
    String description,
    String bankCategory
) {}

// Proposed: add account context
public record TransactionForEnrichment(
    int rowIndex,
    String name,
    String description,
    String bankCategory,
    String sourceAccountOwner,        // NEW
    String targetAccountNumber        // NEW
) {}
```

### Impact Estimate

```
                          BEFORE FIX      AFTER FIX
SELF_TRANSFER detected:   2/113 (1.8%)    ~113/113 (100%)
UNKNOWN classification:   115             ~4
Uncategorized (final):    34 (8.5%)       ~34 (8.5%)  ← same (these are other issues)
"Inne wydatki" (final):   166 (41.3%)     ~55 (13.7%) ← 111 self-transfers removed

Overall correctly categorized:
  Before: 194/402 (48.3%)
  After:  305/402 (75.9%)  ← +27.6pp
```

### Affected Code

| Component | File | Change |
|-----------|------|--------|
| `TransactionForEnrichment` | `bank_data_adapter/app/enrichment/TransactionForEnrichment.java` | Add `sourceAccountOwner`, `targetAccountNumber` fields |
| `EnrichmentPromptBuilder` | `bank_data_adapter/app/enrichment/EnrichmentPromptBuilder.java` | Include account context in prompt, add self-transfer detection rule |
| `AiCsvTransformationService` | `bank_data_adapter/app/AiCsvTransformationService.java` | Pass account owner from CashFlow/CSV header to enrichment |
| Pre-enrichment filter | New logic | Rule-based name matching before AI call |

## Recommendations

1. **Fix SELF_TRANSFER detection (PRIORITY: HIGH)**: Add rule-based pre-filter matching account owner name against transaction name. This alone fixes 111 transactions (27.6%) with zero AI cost. Add account context to `TransactionForEnrichment` for AI-assisted detection of edge cases.

2. **Add account context to enrichment prompt**: Pass `sourceAccountNumber`, `targetAccountNumber`, and `accountOwnerName` to `TransactionForEnrichment` so AI can detect self-transfers by matching owner name in transaction name against account holder.

3. **Post-processing self-transfer detection**: Add a rule-based fallback after AI enrichment: if transaction name contains account owner's name AND it's an OUTFLOW to a different bank account → classify as SELF_TRANSFER.

4. **Header row filtering**: Improve CSV header detection to skip rows that look like column names (contain words like "Data", "Kwota", "Waluta", "Kontrahent").
