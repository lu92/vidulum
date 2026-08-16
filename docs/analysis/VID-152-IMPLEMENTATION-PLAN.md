# VID-152: Transaction Classification - Implementation Plan

**Date**: 2026-04-21
**Status**: Ready for Review
**Related**: VID-152-ENRICHMENT-ANALYSIS.md, VID-152-TRANSACTION-CLASSIFICATION-MODEL.md

---

## Table of Contents

1. [Summary](#summary)
2. [Current State Analysis](#current-state-analysis)
3. [Implementation Phases](#implementation-phases)
4. [Data Model Changes](#data-model-changes)
5. [Updated Enrichment Prompt](#updated-enrichment-prompt)
6. [Test Cases Analysis](#test-cases-analysis)
7. [Files to Modify](#files-to-modify)
8. [Migration Strategy](#migration-strategy)

---

## Summary

### Goal
Add `TransactionClassification` enum to enrichment phase to:
1. Identify non-merchant transactions (bank fees, ATM, cash deposits, etc.)
2. Enable auto-categorization for non-merchant transactions
3. Improve merchant extraction quality (no fake merchants like "PROWIZJA")

### Key Principle
**No hardcoded patterns** - AI determines classification based on transaction semantics.

---

## Current State Analysis

### Current Enrichment Output

```java
// EnrichedTransaction.java (current)
public class EnrichedTransaction {
    private int rowIndex;
    private String merchant;              // Always extracted (even fake ones)
    private double merchantConfidence;    // 0.0-1.0
    private String bankCategory;          // Original or AI-inferred
    private BankCategorySource bankCategorySource;  // ORIGINAL, AI_INFERRED, AI_FALLBACK
}
```

### Current BankCsvRow Fields (record)

```java
// BankCsvRow.java (current - 14 fields)
public record BankCsvRow(
    String bankTransactionId,
    String name,
    String description,
    String bankCategory,
    BigDecimal amount,
    String currency,
    Type type,
    LocalDate operationDate,
    LocalDate bookingDate,
    String sourceAccountNumber,
    String targetAccountNumber,
    String merchant,              // Enrichment field
    Double merchantConfidence,    // Enrichment field
    PaymentMethod paymentMethod
) { ... }
```

### Current Test Coverage

| Test Class | Tests | Coverage |
|------------|-------|----------|
| `EnrichmentPromptBuilderTest` | 8 tests | Prompt building, null handling, Polish chars |
| `EnrichmentResponseProcessorTest` | 10 tests | JSON parsing, fallback, partial results |
| `TransactionGroupTest` | - | Transaction grouping |
| `TransactionEnrichmentServiceGroupingTest` | - | Batch grouping |

**Missing coverage**:
- TransactionClassification enum (new)
- Classification-based filtering
- Auto-categorization flow

---

## Implementation Phases

### Phase 1: Add TransactionClassification Enum

**Files to create**:
```
src/main/java/com/multi/vidulum/bank_data_adapter/domain/TransactionClassification.java
```

**Enum definition**:
```java
public enum TransactionClassification {
    MERCHANT,         // Payment to business/person
    BANK_FEE,         // Bank fee, commission
    CASH_WITHDRAWAL,  // ATM withdrawal
    CASH_DEPOSIT,     // Cash deposit
    SELF_TRANSFER,    // Transfer between own accounts
    INTEREST,         // Interest payment
    UNKNOWN;          // Cannot determine

    public boolean hasMerchant() { ... }
    public boolean isAutoCategorizeable() { ... }
    public boolean includeInBudget() { ... }
}
```

**Tests to add**:
```
src/test/java/com/multi/vidulum/bank_data_adapter/domain/TransactionClassificationTest.java
```

| Test Case | Description |
|-----------|-------------|
| `shouldReturnTrueForHasMerchantWhenMerchant` | MERCHANT.hasMerchant() == true |
| `shouldReturnTrueForHasMerchantWhenUnknown` | UNKNOWN.hasMerchant() == true |
| `shouldReturnFalseForHasMerchantWhenBankFee` | BANK_FEE.hasMerchant() == false |
| `shouldReturnTrueForIsAutoCategorizeable` | BANK_FEE, CASH_WITHDRAWAL, etc. |
| `shouldReturnFalseForIncludeInBudgetWhenSelfTransfer` | SELF_TRANSFER.includeInBudget() == false |

---

### Phase 2: Update EnrichedTransaction

**Files to modify**:
```
src/main/java/com/multi/vidulum/bank_data_adapter/app/enrichment/EnrichedTransaction.java
```

**New fields**:
```java
public class EnrichedTransaction {
    // Existing fields
    private int rowIndex;
    private String merchant;
    private double merchantConfidence;
    private String bankCategory;
    private BankCategorySource bankCategorySource;

    // NEW FIELDS
    private TransactionClassification classification;  // NEW
    private String classificationReason;               // NEW
    private String location;                           // NEW (for ATM, addresses)
}
```

**Tests to update**:
```
src/test/java/com/multi/vidulum/bank_data_adapter/app/enrichment/EnrichmentResponseProcessorTest.java
```

| Test Case | Description |
|-----------|-------------|
| `shouldParseClassificationFromAiResponse` | Parse classification field |
| `shouldParseClassificationReason` | Parse reason field |
| `shouldParseLocation` | Parse location field |
| `shouldDefaultToMerchantClassificationWhenMissing` | Backward compatibility |

---

### Phase 3: Update Enrichment Prompt

**Files to modify**:
```
src/main/java/com/multi/vidulum/bank_data_adapter/app/enrichment/EnrichmentPromptBuilder.java
```

**Key changes**:
1. Add classification rules to system prompt
2. Update output format to include new fields
3. Keep backward compatibility with existing flow

**Tests to update**:
```
src/test/java/com/multi/vidulum/bank_data_adapter/app/enrichment/EnrichmentPromptBuilderTest.java
```

| Test Case | Description |
|-----------|-------------|
| `shouldContainClassificationRulesInSystemPrompt` | Check for BANK_FEE, CASH_WITHDRAWAL rules |
| `shouldContainOutputFormatWithClassification` | Check for classification in output format |
| `shouldContainClassificationExamples` | Check for example cases |

---

### Phase 4: Update BankCsvRow

**Files to modify**:
```
src/main/java/com/multi/vidulum/bank_data_ingestion/domain/BankCsvRow.java
```

**New fields (add to record)**:
```java
public record BankCsvRow(
    // ... existing 14 fields ...

    // NEW ENRICHMENT FIELDS
    TransactionClassification classification,  // NEW (15)
    String classificationReason,               // NEW (16)
    String location                            // NEW (17)
) {
    // NEW helper methods
    public boolean hasClassification() { ... }
    public boolean isAutoCategorizeable() { ... }
}
```

**IMPORTANT**: Since BankCsvRow is a `record`, adding fields changes constructor signature.

**Files that use BankCsvRow constructor (need update)**:
1. `AiBankCsvTransformService.java` - parses AI response
2. `BankDataIngestionService.java` - creates staging transactions
3. `CanonicalCsvDetector.java` - detects canonical CSV
4. Tests that create BankCsvRow instances

**Tests to update**:
```
src/test/java/com/multi/vidulum/bank_data_adapter/app/CanonicalCsvDetectionTest.java
src/test/java/com/multi/vidulum/bank_data_adapter/app/DateRangeExtractionTest.java
src/test/java/com/multi/vidulum/bank_data_adapter/infrastructure/LocalCsvTransformerTest.java
```

---

### Phase 5: Update Canonical CSV Schema

**Current canonical CSV header (14 columns)**:
```csv
bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber,merchant,merchantConfidence,paymentMethod
```

**New canonical CSV header (17 columns)**:
```csv
bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber,merchant,merchantConfidence,paymentMethod,classification,classificationReason,location
```

**Files to modify**:
1. `AiBankCsvTransformService.java` - CSV generation
2. `CanonicalCsvParser.java` - CSV parsing
3. `AiMappingRulesPromptBuilder.java` - AI transform prompt

---

### Phase 6: Update Categorization Flow

**Files to modify**:
```
src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationService.java
```

**Logic change**:
```java
// Before sending to AI categorization:
List<StagedTransaction> toCategorizeBySai = stagedTransactions.stream()
    .filter(tx -> !tx.getClassification().isAutoCategorizeable())
    .toList();

// Auto-categorized transactions:
List<StagedTransaction> autoCategorizeable = stagedTransactions.stream()
    .filter(tx -> tx.getClassification().isAutoCategorizeable())
    .toList();
// These already have bankCategory from enrichment, skip AI categorization
```

---

## Data Model Changes

### 1. TransactionClassification Enum (NEW)

```java
package com.multi.vidulum.bank_data_adapter.domain;

public enum TransactionClassification {
    MERCHANT,
    BANK_FEE,
    CASH_WITHDRAWAL,
    CASH_DEPOSIT,
    SELF_TRANSFER,
    INTEREST,
    UNKNOWN;

    public boolean hasMerchant() {
        return this == MERCHANT || this == UNKNOWN;
    }

    public boolean isAutoCategorizeable() {
        return this == BANK_FEE || this == CASH_WITHDRAWAL ||
               this == CASH_DEPOSIT || this == SELF_TRANSFER || this == INTEREST;
    }

    public boolean includeInBudget() {
        return this != SELF_TRANSFER;
    }
}
```

### 2. EnrichedTransaction Changes

| Field | Type | Old | New | Notes |
|-------|------|-----|-----|-------|
| `classification` | TransactionClassification | - | YES | NEW |
| `classificationReason` | String | - | YES | NEW |
| `location` | String | - | YES | NEW (for ATM) |

### 3. BankCsvRow Changes

| Field | Type | Position | Notes |
|-------|------|----------|-------|
| `classification` | TransactionClassification | 15 | NEW |
| `classificationReason` | String | 16 | NEW |
| `location` | String | 17 | NEW |

### 4. Canonical CSV Changes

| Column | Position | Notes |
|--------|----------|-------|
| `classification` | 15 | MERCHANT, BANK_FEE, etc. |
| `classificationReason` | 16 | AI explanation |
| `location` | 17 | For ATM, addresses |

---

## Updated Enrichment Prompt

### New System Prompt

```text
You are a transaction data enrichment specialist for bank statements.

## YOUR TASK

For each transaction in the input, determine:

1. **classification** - transaction type (REQUIRED):
   - MERCHANT: Payment to business/person (extract merchant name)
   - BANK_FEE: Bank fee, commission, service charge (no merchant)
   - CASH_WITHDRAWAL: ATM or bank withdrawal (no merchant)
   - CASH_DEPOSIT: Cash deposit (no merchant)
   - SELF_TRANSFER: Transfer between own accounts (no merchant)
   - INTEREST: Interest payment (no merchant)
   - UNKNOWN: Cannot determine (try to extract merchant anyway)

2. **merchant** - only if classification is MERCHANT or UNKNOWN:
   - Extract clean, normalized business/person name
   - UPPERCASE for consistency
   - Remove legal suffixes (S.A., SP. Z O.O., etc.)
   - Return null for non-merchant transactions

3. **merchantConfidence** - only if merchant is provided (0.0 to 1.0)

4. **bankCategory** - only if original is empty, otherwise keep original

5. **classificationReason** - brief explanation of classification choice

6. **location** - extracted location info (for ATM, physical locations)

## CLASSIFICATION RULES

### BANK_FEE (language-agnostic indicators):
- Transaction is a fee/commission/charge from the bank itself
- No external counterparty
- Keywords indicating bank service charges
- Amount is typically small and negative

### CASH_WITHDRAWAL:
- ATM terminal codes (numeric patterns like "00146 2703W250H")
- Withdrawal-related context
- No merchant name, just location/terminal info

### CASH_DEPOSIT:
- Deposit-related context
- Positive amount
- No external sender

### SELF_TRANSFER:
- Sender and recipient appear to be same person/entity
- Transfer between accounts
- Often has "own transfer" context

### INTEREST:
- Interest payment context
- From bank to account holder

### MERCHANT (default for payments):
- Payment to external business or person
- Has identifiable counterparty name
- Most card transactions, online payments, purchases

## MERCHANT EXTRACTION RULES (for MERCHANT and UNKNOWN only)

| Input | Output merchant |
|-------|-----------------|
| "ŻABKA POLSKA 4521 WARSZAWA" | "ŻABKA" |
| "NETFLIX.COM 866-579-7172" | "NETFLIX" |
| "BANK PEKAO S.A." + desc: "Badoo help@badoo.com" | "BADOO" |
| "ALLEGRO.PL SP. Z O.O." | "ALLEGRO" |

Rules:
- UPPERCASE for consistency
- Remove: S.A., SP. Z O.O., SPÓŁKA, addresses, terminal IDs
- If name is bank intermediary, extract real merchant from description
- For email domains, use company name

## BANK_CATEGORY RULES

**CRITICAL**: Only infer bankCategory if original is EMPTY string.
If original bankCategory exists (non-empty), KEEP IT UNCHANGED.

When inferring:
- Use context from transaction name/description
- Match to common Polish bank categories
- If cannot determine, use "Inne"

## OUTPUT FORMAT

Return ONLY valid JSON. No markdown, no code blocks.

{
  "success": true,
  "enrichedTransactions": [
    {
      "rowIndex": 0,
      "classification": "MERCHANT",
      "merchant": "ŻABKA",
      "merchantConfidence": 0.95,
      "bankCategory": "Zakupy spożywcze",
      "bankCategorySource": "AI_INFERRED",
      "classificationReason": "Card payment at grocery store",
      "location": null
    },
    {
      "rowIndex": 1,
      "classification": "BANK_FEE",
      "merchant": null,
      "merchantConfidence": null,
      "bankCategory": "Opłaty bankowe",
      "bankCategorySource": "AI_INFERRED",
      "classificationReason": "Express transfer commission - bank internal charge",
      "location": null
    },
    {
      "rowIndex": 2,
      "classification": "CASH_WITHDRAWAL",
      "merchant": null,
      "merchantConfidence": null,
      "bankCategory": "Wypłata z bankomatu",
      "bankCategorySource": "ORIGINAL",
      "classificationReason": "ATM terminal code pattern detected",
      "location": "WARSZAWA"
    }
  ],
  "processingNotes": "Processed 3 transactions"
}

## ERROR HANDLING

- If cannot determine classification: use UNKNOWN
- If cannot determine merchant (for MERCHANT/UNKNOWN): use first recognizable word, confidence 0.3
- If cannot determine bankCategory (and original is empty): use "Inne"
- NEVER return null for classification - always choose a type
- ALWAYS include all transactions from input in output
```

---

## Test Cases Analysis

### Current Test Coverage

#### EnrichmentPromptBuilderTest.java (8 tests)

| Test | Status | Notes |
|------|--------|-------|
| shouldBuildSystemPrompt | UPDATE | Add classification checks |
| shouldBuildUserPromptWithBatchInfo | OK | No changes needed |
| shouldHandleEmptyDescriptions | OK | No changes needed |
| shouldHandlePolishCharacters | OK | No changes needed |
| shouldNotIncludeAmountAndType | OK | No changes needed |
| shouldHandleNullBankNameAndLanguage | OK | No changes needed |
| systemPromptShouldContainJsonOutputInstructions | UPDATE | Add classification |
| systemPromptShouldContainFallbackInstructions | UPDATE | Add UNKNOWN fallback |

**New tests needed**:
| Test | Description |
|------|-------------|
| `shouldContainClassificationInSystemPrompt` | Check for classification rules |
| `shouldContainAllClassificationTypes` | BANK_FEE, CASH_WITHDRAWAL, etc. |
| `shouldContainClassificationExamples` | Check for example outputs |

#### EnrichmentResponseProcessorTest.java (10 tests)

| Test | Status | Notes |
|------|--------|-------|
| shouldParseValidJsonResponse | UPDATE | Add classification field |
| shouldExtractJsonFromMarkdownCodeBlock | UPDATE | Add classification |
| shouldUseFallbackForEmptyResponse | UPDATE | Default to UNKNOWN |
| shouldUseFallbackForInvalidJson | UPDATE | Default to UNKNOWN |
| shouldRepairPartialResult | UPDATE | Add classification |
| shouldKeepOriginalBankCategoryWhenMarkedAsOriginal | OK | No changes |
| shouldConvertToDomainObjects | UPDATE | Add classification |
| shouldHandleNullResponse | UPDATE | Default to UNKNOWN |

**New tests needed**:
| Test | Description |
|------|-------------|
| `shouldParseClassificationFromResponse` | Parse BANK_FEE, MERCHANT, etc. |
| `shouldParseClassificationReason` | Parse reason field |
| `shouldParseLocation` | Parse location for ATM |
| `shouldDefaultToUnknownWhenClassificationMissing` | Backward compatibility |
| `shouldSetNullMerchantForBankFee` | Verify null merchant |
| `shouldSetNullMerchantForCashWithdrawal` | Verify null merchant |

### New Test Classes Needed

#### TransactionClassificationTest.java

| Test | Description |
|------|-------------|
| `merchantShouldHaveMerchant` | MERCHANT.hasMerchant() == true |
| `unknownShouldHaveMerchant` | UNKNOWN.hasMerchant() == true |
| `bankFeeShouldNotHaveMerchant` | BANK_FEE.hasMerchant() == false |
| `cashWithdrawalShouldNotHaveMerchant` | CASH_WITHDRAWAL.hasMerchant() == false |
| `bankFeeShouldBeAutoCategorizeable` | BANK_FEE.isAutoCategorizeable() == true |
| `merchantShouldNotBeAutoCategorizeable` | MERCHANT.isAutoCategorizeable() == false |
| `selfTransferShouldNotIncludeInBudget` | SELF_TRANSFER.includeInBudget() == false |
| `merchantShouldIncludeInBudget` | MERCHANT.includeInBudget() == true |

### Test Data (Real Examples)

#### From Pekao (791 transactions)

| Original | Expected Classification | Expected Merchant |
|----------|------------------------|-------------------|
| `Prowizja za przelew` | BANK_FEE | null |
| `00146 2703W250H WARSZAWA` | CASH_WITHDRAWAL | null, location: WARSZAWA |
| `UL. WOLNOSCI 23B MIELEC` | MERCHANT | UNKNOWN (conf: 0.2) |
| `NETFLIX.COM AMSTERDAM` | MERCHANT | NETFLIX (conf: 0.95) |
| `ALLEGRO SP. Z O.O.` | MERCHANT | ALLEGRO (conf: 0.95) |
| `BANK PEKAO S.A.` + `Badoo help@badoo.com` | MERCHANT | BADOO (conf: 0.95) |

#### From Nest Bank (402 transactions)

| Original | Expected Classification | Expected Merchant |
|----------|------------------------|-------------------|
| `Prowizja za przelew natychmiastowy KIR` | BANK_FEE | null |
| `ZUS składki` | MERCHANT | ZUS (conf: 0.95) |
| `MINDBOX SP Z O O` | MERCHANT | MINDBOX (conf: 0.95) |
| `Przelew środków` | UNKNOWN | PRZELEW (conf: 0.3) |
| `Odsetki od lokaty` | INTEREST | null |

---

## Files to Modify

### Phase 1: Enum
```
+ src/main/java/com/multi/vidulum/bank_data_adapter/domain/TransactionClassification.java (NEW)
+ src/test/java/com/multi/vidulum/bank_data_adapter/domain/TransactionClassificationTest.java (NEW)
```

### Phase 2: EnrichedTransaction
```
M src/main/java/com/multi/vidulum/bank_data_adapter/app/enrichment/EnrichedTransaction.java
M src/test/java/com/multi/vidulum/bank_data_adapter/app/enrichment/EnrichmentResponseProcessorTest.java
```

### Phase 3: Enrichment Prompt
```
M src/main/java/com/multi/vidulum/bank_data_adapter/app/enrichment/EnrichmentPromptBuilder.java
M src/test/java/com/multi/vidulum/bank_data_adapter/app/enrichment/EnrichmentPromptBuilderTest.java
```

### Phase 4: BankCsvRow
```
M src/main/java/com/multi/vidulum/bank_data_ingestion/domain/BankCsvRow.java
M src/main/java/com/multi/vidulum/bank_data_adapter/app/AiBankCsvTransformService.java
M src/test/java/com/multi/vidulum/bank_data_adapter/app/CanonicalCsvDetectionTest.java
M src/test/java/com/multi/vidulum/bank_data_adapter/app/DateRangeExtractionTest.java
M src/test/java/com/multi/vidulum/bank_data_adapter/infrastructure/LocalCsvTransformerTest.java
```

### Phase 5: Canonical CSV
```
M src/main/java/com/multi/vidulum/bank_data_adapter/infrastructure/AiMappingRulesPromptBuilder.java
M src/main/java/com/multi/vidulum/bank_data_ingestion/infrastructure/CanonicalCsvParser.java (if exists)
```

### Phase 6: Categorization Flow
```
M src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationService.java
```

---

## Migration Strategy

### No Data Migration Needed

Per CLAUDE.md:
> **This application is under development.** There are no production users yet.
> - **No data migrations required** - we can freely change schemas
> - **No backwards compatibility concerns** - breaking changes acceptable

### Steps

1. Implement all phases
2. Clear all MongoDB data (`ai_csv_transformations` collection)
3. Re-run transformations with new enrichment prompt
4. Test with Pekao and Nest Bank CSV files

### Docker Rebuild Command

```bash
./mvnw package -DskipTests
docker build --no-cache -t vidulum-app:latest .
docker-compose -f docker-compose-final.yml down -v
docker-compose -f docker-compose-final.yml up -d
```

---

## Estimated Scope

| Phase | Files | Tests | Complexity |
|-------|-------|-------|------------|
| 1. Enum | 2 | 8 | Low |
| 2. EnrichedTransaction | 2 | 6 | Low |
| 3. Enrichment Prompt | 2 | 3 | Medium |
| 4. BankCsvRow | 5 | Update existing | Medium |
| 5. Canonical CSV | 2 | - | Low |
| 6. Categorization | 1 | - | Low |
| **Total** | ~14 files | ~17 new tests | Medium |

---

*Document created: 2026-04-21*
*Status: Ready for user review*
