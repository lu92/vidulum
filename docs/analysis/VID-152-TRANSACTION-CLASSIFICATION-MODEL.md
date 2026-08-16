# VID-152: Transaction Classification Model Design

**Date**: 2026-04-21
**Status**: Design Proposal

## Java Model

### TransactionClassification Enum

```java
package com.multi.vidulum.bank_data_adapter.domain;

/**
 * Classification of transaction type determined by AI enrichment.
 *
 * This classification helps:
 * 1. Determine if merchant extraction makes sense
 * 2. Enable auto-categorization for non-merchant transactions
 * 3. Improve AI categorization by filtering out non-categorizable transactions
 */
public enum TransactionClassification {

    /**
     * Payment to a merchant/business/person.
     * Merchant extraction is meaningful.
     * Should be categorized by AI categorization.
     *
     * Examples:
     * - "ŻABKA POLSKA 4521 WARSZAWA" → merchant: ŻABKA
     * - "NETFLIX.COM" → merchant: NETFLIX
     * - "Jan Kowalski" (personal transfer) → merchant: JAN KOWALSKI
     */
    MERCHANT,

    /**
     * Bank fee, commission, or service charge.
     * No meaningful merchant (the bank itself is not a merchant).
     * Auto-category: "Bank fees" / "Opłaty bankowe"
     *
     * Examples:
     * - "Prowizja za przelew natychmiastowy wychodzący KIR"
     * - "Opłata za obsługę karty MasterCard"
     * - "Monthly account fee"
     * - "Wire transfer commission"
     * - "Gebühr für Überweisung" (German)
     */
    BANK_FEE,

    /**
     * Cash withdrawal from ATM or bank branch.
     * No merchant (ATM terminal is not a merchant).
     * Auto-category: "Cash" / "Gotówka"
     *
     * Examples:
     * - "00146 2703W250H WARSZAWA" (ATM terminal code)
     * - "Wypłata z bankomatu"
     * - "ATM withdrawal"
     * - "EURONET 12345"
     */
    CASH_WITHDRAWAL,

    /**
     * Cash deposit at ATM or bank branch.
     * No merchant.
     * Auto-category: "Cash" / "Gotówka"
     *
     * Examples:
     * - "Wpłata gotówkowa"
     * - "Cash deposit"
     * - "Wpłata we wpłatomacie"
     */
    CASH_DEPOSIT,

    /**
     * Transfer between own accounts (same owner).
     * No merchant (self-transfer).
     * Auto-category: "Internal transfer" / "Przelew wewnętrzny"
     * Should be excluded from budget calculations.
     *
     * Examples:
     * - Transfer from checking to savings
     * - "Przelew własny"
     * - Same name appears as sender and in account owner
     */
    SELF_TRANSFER,

    /**
     * Interest payment (credit or debit).
     * No merchant.
     * Auto-category: "Interest" / "Odsetki"
     *
     * Examples:
     * - "Odsetki od lokaty"
     * - "Interest payment"
     * - "Kapitalizacja odsetek"
     */
    INTEREST,

    /**
     * Cannot determine transaction type.
     * Fallback - try to extract merchant anyway.
     * Should be categorized by AI categorization.
     *
     * Examples:
     * - Ambiguous transaction descriptions
     * - Unknown patterns
     */
    UNKNOWN;

    /**
     * Whether this classification type has a meaningful merchant.
     */
    public boolean hasMerchant() {
        return this == MERCHANT || this == UNKNOWN;
    }

    /**
     * Whether this classification should be auto-categorized
     * (skipped in AI categorization phase).
     */
    public boolean isAutoCategorizeable() {
        return this == BANK_FEE ||
               this == CASH_WITHDRAWAL ||
               this == CASH_DEPOSIT ||
               this == SELF_TRANSFER ||
               this == INTEREST;
    }

    /**
     * Whether this classification should be included in budget/expense analysis.
     * Self-transfers should be excluded as they don't represent real income/expense.
     */
    public boolean includeInBudget() {
        return this != SELF_TRANSFER;
    }
}
```

### EnrichedTransactionData (extended BankCsvRow fields)

```java
package com.multi.vidulum.bank_data_adapter.domain;

import lombok.Builder;
import lombok.Value;

/**
 * Enrichment result for a single transaction.
 * Added to BankCsvRow after enrichment phase.
 */
@Value
@Builder
public class EnrichedTransactionData {

    /**
     * Classification of transaction type.
     * Determined by AI based on transaction content.
     */
    TransactionClassification classification;

    /**
     * Extracted merchant name (normalized, uppercase).
     * Null if classification.hasMerchant() == false.
     *
     * Examples:
     * - "ŻABKA" (from "ŻABKA POLSKA 4521 WARSZAWA")
     * - "NETFLIX" (from "NETFLIX.COM 866-579-7172")
     * - null (for BANK_FEE, CASH_WITHDRAWAL, etc.)
     */
    String merchant;

    /**
     * Confidence score for merchant extraction (0.0 - 1.0).
     * Null if merchant is null.
     *
     * - 0.95+ = exact brand match
     * - 0.8-0.95 = clear company name
     * - 0.5-0.8 = inferred from context
     * - <0.5 = uncertain
     */
    Double merchantConfidence;

    /**
     * Bank category from original data or AI-inferred.
     * Used in categorization phase.
     */
    String bankCategory;

    /**
     * Source of bankCategory value.
     */
    BankCategorySource bankCategorySource;

    /**
     * Reason why this classification was chosen.
     * Useful for debugging and UI display.
     *
     * Examples:
     * - "Detected bank fee pattern in description"
     * - "ATM terminal code detected in name"
     * - "Known merchant brand: NETFLIX"
     */
    String classificationReason;

    /**
     * Additional location info (for ATM withdrawals).
     * Extracted from transaction data if available.
     */
    String location;

    public enum BankCategorySource {
        ORIGINAL,      // From bank CSV (kept unchanged)
        AI_INFERRED,   // AI determined category
        AI_FALLBACK    // AI couldn't determine, used "Inne"
    }
}
```

### Updated BankCsvRow

```java
package com.multi.vidulum.bank_data_adapter.domain;

// Existing fields...
public class BankCsvRow {
    // ... existing fields ...

    // === ENRICHMENT FIELDS (Phase 2) ===

    /**
     * Transaction classification (MERCHANT, BANK_FEE, CASH_WITHDRAWAL, etc.)
     * Determined by AI enrichment.
     */
    private TransactionClassification classification;

    /**
     * Extracted merchant name (null for non-merchant transactions).
     */
    private String merchant;

    /**
     * Merchant extraction confidence (null if no merchant).
     */
    private Double merchantConfidence;

    /**
     * Reason for classification choice.
     */
    private String classificationReason;

    /**
     * Location info (for ATM, etc.)
     */
    private String location;
}
```

---

## Enrichment Prompt Changes

### Current System Prompt (excerpt):

```
For each transaction, extract:
1. merchant - normalized business/person name
2. bankCategory - only if original is empty
```

### New System Prompt:

```
For each transaction, determine:

1. **transactionType** - classify the transaction:
   - MERCHANT: Payment to business/person (extract merchant name)
   - BANK_FEE: Bank fee, commission, service charge (no merchant)
   - CASH_WITHDRAWAL: ATM or bank withdrawal (no merchant)
   - CASH_DEPOSIT: Cash deposit (no merchant)
   - SELF_TRANSFER: Transfer between own accounts (no merchant)
   - INTEREST: Interest payment (no merchant)
   - UNKNOWN: Cannot determine (try to extract merchant anyway)

2. **merchant** - only if transactionType is MERCHANT or UNKNOWN:
   - Extract clean, normalized business/person name
   - UPPERCASE for consistency
   - Remove legal suffixes (S.A., SP. Z O.O., etc.)
   - Return null for non-merchant transactions

3. **merchantConfidence** - only if merchant is provided

4. **bankCategory** - only if original is empty

5. **classificationReason** - brief explanation of why this type was chosen

## CLASSIFICATION RULES

### BANK_FEE indicators (language-agnostic concepts):
- Transaction is a fee/commission/charge from the bank itself
- No external counterparty
- Amount is typically small and negative
- Often periodic (monthly, per-transaction)

### CASH_WITHDRAWAL indicators:
- ATM terminal codes (numeric patterns like "00146 2703W250H")
- Withdrawal-related context
- No merchant name, just location/terminal info

### CASH_DEPOSIT indicators:
- Deposit-related context
- Positive amount
- No external sender

### SELF_TRANSFER indicators:
- Sender and recipient appear to be same person/entity
- Transfer between accounts
- Often has "own transfer" context

### MERCHANT (default for payments):
- Payment to external business or person
- Has identifiable counterparty name
- Most card transactions, online payments, purchases
```

---

## Examples

### Example 1: Bank Fee (Pekao)

**Input:**
```json
{
  "name": "Opłata za obsługę kartyMasterCard Debit*63020299 za miesiąc 05.2025",
  "description": "Opłata za obsługę kartyMasterCard Debit*63020299 za miesiąc 05.2025",
  "bankCategory": "Opłaty bankowe"
}
```

**Current enrichment output:**
```json
{
  "merchant": "Opłata za obsługę karty",
  "merchantConfidence": 0.3,
  "bankCategory": "Opłaty bankowe"
}
```

**New enrichment output:**
```json
{
  "classification": "BANK_FEE",
  "merchant": null,
  "merchantConfidence": null,
  "bankCategory": "Opłaty bankowe",
  "classificationReason": "Card service fee - bank internal charge"
}
```

### Example 2: Bank Fee (Nest Bank)

**Input:**
```json
{
  "name": "Prowizja za przelew natychmiastowy wychodzący KIR",
  "description": "Prowizja za przelew natychmiastowy wychodzący KIR",
  "bankCategory": ""
}
```

**Current enrichment output:**
```json
{
  "merchant": "PROWIZJA",
  "merchantConfidence": 0.3,
  "bankCategory": "Opłaty bankowe"
}
```

**New enrichment output:**
```json
{
  "classification": "BANK_FEE",
  "merchant": null,
  "merchantConfidence": null,
  "bankCategory": "Opłaty bankowe",
  "classificationReason": "Express transfer commission - bank internal charge"
}
```

### Example 3: ATM Withdrawal

**Input:**
```json
{
  "name": "00146 2703W250H        WARSZAWA",
  "description": "*********0015010",
  "bankCategory": "Wypłata z bankomatu"
}
```

**Current enrichment output:**
```json
{
  "merchant": "WYPŁATA",
  "merchantConfidence": 0.3,
  "bankCategory": "Wypłata z bankomatu"
}
```

**New enrichment output:**
```json
{
  "classification": "CASH_WITHDRAWAL",
  "merchant": null,
  "merchantConfidence": null,
  "bankCategory": "Wypłata z bankomatu",
  "location": "WARSZAWA",
  "classificationReason": "ATM terminal code pattern detected (00146 2703W250H)"
}
```

### Example 4: Address without merchant name

**Input:**
```json
{
  "name": "UL. WOLNOSCI 23B       MIELEC",
  "description": "*********0015010",
  "bankCategory": "Opieka medyczna"
}
```

**Current enrichment output:**
```json
{
  "merchant": "UL. WOLNOSCI",
  "merchantConfidence": 0.3,
  "bankCategory": "Opieka medyczna"
}
```

**New enrichment output:**
```json
{
  "classification": "MERCHANT",
  "merchant": "UNKNOWN_LOCATION_MERCHANT",
  "merchantConfidence": 0.2,
  "bankCategory": "Opieka medyczna",
  "location": "MIELEC, UL. WOLNOSCI 23B",
  "classificationReason": "Address detected but no business name - likely card payment at physical location"
}
```

**Alternative approach** - AI could try harder:
```json
{
  "classification": "MERCHANT",
  "merchant": "GABINET MEDYCZNY",  // inferred from bankCategory "Opieka medyczna"
  "merchantConfidence": 0.4,
  "bankCategory": "Opieka medyczna",
  "location": "MIELEC, UL. WOLNOSCI 23B",
  "classificationReason": "Medical service at address - merchant name not visible, inferred from category"
}
```

### Example 5: Real Merchant (Netflix)

**Input:**
```json
{
  "name": "NETFLIX.COM            AMSTERDAM",
  "description": "*********0015010",
  "bankCategory": "Internet, TV, telefon"
}
```

**Current enrichment output:**
```json
{
  "merchant": "NETFLIX",
  "merchantConfidence": 0.95,
  "bankCategory": "Internet, TV, telefon"
}
```

**New enrichment output:**
```json
{
  "classification": "MERCHANT",
  "merchant": "NETFLIX",
  "merchantConfidence": 0.95,
  "bankCategory": "Internet, TV, telefon",
  "classificationReason": "Known global brand: Netflix streaming service"
}
```

### Example 6: Personal Transfer

**Input:**
```json
{
  "name": "Lucjan Bik Pekao",
  "description": "zycie",
  "bankCategory": ""
}
```

**New enrichment output:**
```json
{
  "classification": "MERCHANT",  // or SELF_TRANSFER if detected as own account
  "merchant": "LUCJAN BIK",
  "merchantConfidence": 0.8,
  "bankCategory": "Inne",
  "classificationReason": "Personal transfer to individual"
}
```

---

## Auto-Categorization Flow

### How it works (NO hardcoded strings):

```
1. Enrichment-prompt (AI) analyzes transaction
   ↓
2. AI returns: classification + merchant + bankCategory
   ↓
3. If classification.isAutoCategorizeable():
   - Use bankCategory from AI (already determined)
   - Skip AI categorization phase for this transaction
   ↓
4. If classification == MERCHANT:
   - Include in AI categorization phase
   - Group by merchant for pattern detection
```

### Key insight:

**The AI in enrichment-prompt determines BOTH the classification AND the appropriate bankCategory.**

We don't need hardcoded category names because:
- For `BANK_FEE`: AI returns `bankCategory: "Opłaty bankowe"` (or equivalent in bank's language)
- For `CASH_WITHDRAWAL`: AI returns `bankCategory: "Gotówka"` or keeps original from bank
- The bank itself often provides the category!

### When bank provides category:

```
Input: { name: "ATM EURONET", bankCategory: "Wypłata z bankomatu" }
                                            ↑ bank already categorized!

Output: {
  classification: "CASH_WITHDRAWAL",
  bankCategory: "Wypłata z bankomatu",  // kept from bank
  bankCategorySource: "ORIGINAL"
}
```

### When bank doesn't provide category:

```
Input: { name: "Prowizja za przelew", bankCategory: "" }
                                       ↑ empty!

Output: {
  classification: "BANK_FEE",
  bankCategory: "Opłaty bankowe",  // AI inferred
  bankCategorySource: "AI_INFERRED"
}
```

---

## Summary

### What changes:

1. **Enrichment-prompt** - extended to return `classification` field
2. **BankCsvRow** - adds `classification`, `classificationReason`, `location` fields
3. **Categorization flow** - skips auto-categorizable transactions

### What stays the same:

1. **No hardcoded patterns** - AI determines everything
2. **Language-agnostic** - works for any bank/country
3. **Bank categories respected** - if bank provides category, we keep it

### Benefits:

1. Cleaner merchant data (no fake merchants like "PROWIZJA")
2. Automatic handling of bank fees, ATM, etc.
3. Better AI categorization (fewer transactions to categorize)
4. Richer data for reporting (classification + location)

---

## Q&A / Design Decisions

### Q1: Czy opłaty bankowe będą kategoryzowane bez enrichment-prompt?

**NIE.** Enrichment-prompt (AI) klasyfikuje typ transakcji. Nie ma hardcoded patterns.

Flow:
```
CSV → Transform → Enrichment-prompt (AI) → classification + bankCategory
                        ↓
              AI decyduje: "To jest BANK_FEE bo widzę pattern opłaty"
                        ↓
              Jeśli BANK_FEE → merchant = null, bankCategory z AI lub banku
```

### Q2: Skąd atmLocation i classificationReason?

**Z enrichment-prompt!** AI analizuje transakcję i wyciąga:
- `location` - z name/description (np. "WARSZAWA" z "00146 2703W250H WARSZAWA")
- `classificationReason` - AI tłumaczy dlaczego wybrał ten typ

### Q3: Jak działa auto-kategoryzacja bez hardcoded strings?

**AI sam wybiera kategorię.** Nie potrzebujemy hardcoded "Opłaty bankowe" bo:
1. Bank często sam podaje kategorię (np. Pekao daje "Wypłata z bankomatu")
2. Jeśli bank nie podaje → AI inferuje odpowiednią nazwę

```
Input:  { name: "Prowizja za przelew", bankCategory: "" }
Output: { classification: "BANK_FEE", bankCategory: "Opłaty bankowe" }  // AI inferred
```

### Q4: Co z SALARY?

**Na razie pomijamy.** Fokus na:
- `MERCHANT` - normalne płatności
- `BANK_FEE` - opłaty bankowe
- `CASH_WITHDRAWAL` - bankomat
- `CASH_DEPOSIT` - wpłata
- `SELF_TRANSFER` - przelew własny
- `INTEREST` - odsetki
- `UNKNOWN` - fallback

### Q5: Czy zmieniamy enrichment-prompt?

**TAK.** Rozszerzamy prompt o:
- `transactionType` / `classification` - enum
- `classificationReason` - wyjaśnienie
- `location` - dla ATM, adresów

### Q6: Case PROWIZJA, UL. WOLNOSCI, 00146 2703W250H

| Transakcja | Obecny merchant | Nowy classification | Nowy merchant |
|------------|-----------------|---------------------|---------------|
| `Prowizja za przelew KIR` | `PROWIZJA` (0.3) | `BANK_FEE` | `null` |
| `UL. WOLNOSCI 23B MIELEC` | `UL. WOLNOSCI` (0.3) | `MERCHANT` | `UNKNOWN_LOCATION_MERCHANT` (0.2) |
| `00146 2703W250H WARSZAWA` | `WYPŁATA` (0.3) | `CASH_WITHDRAWAL` | `null`, location: "WARSZAWA" |

**Klucz**: AI rozpoznaje semantykę transakcji, nie szuka merchantów tam gdzie ich nie ma.

---

## Implementation Priority

1. **Phase 1**: Add `TransactionClassification` enum
2. **Phase 2**: Update enrichment-prompt to return `classification`
3. **Phase 3**: Update `BankCsvRow` / canonical CSV schema
4. **Phase 4**: Update categorize-ai to skip auto-categorizable transactions
5. **Phase 5**: Update UI to display classification info

---

*Last updated: 2026-04-21*
