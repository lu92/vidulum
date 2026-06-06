# VID-161: Self-Transfer Detection — Design & Analysis

**Date**: 2026-05-27
**Status**: Design proposal
**Priority**: High — affects budget accuracy, forecast, and 111+ transactions in Nest Bank alone

## Problem Statement

Self-transfers (transfers between user's own bank accounts) are not detected by the system. They end up in catch-all categories like "Inne" or "Uncategorized", inflating reported expenses and corrupting budget forecasts.

**Impact on real data:**
- Nest Bank (lu100): 111 self-transfers in "Inne" (50.7% of all transactions)
- Budget inflated by ~345,000 PLN of fake "expenses" (monthly transfers to own Pekao/mBank accounts)
- Forecast predicts ~22,500 PLN/month expenses instead of actual ~13,500 PLN

## Business Context

### What is a self-transfer?

A transfer between **two accounts owned by the same person**. Examples:
- Nest Bank → Pekao (monthly "zycie" transfer, 3,000 PLN)
- Nest Bank → mBank (mortgage overpayment, variable amounts)
- Nest Bank → ING (deposit, one-time)

Self-transfers are NOT real expenses or income — they're internal money movement. In personal finance:
- They should NOT count toward monthly spending
- They should NOT count toward monthly income
- They should NOT appear in budget category breakdowns
- They SHOULD be visible (the transaction exists in bank statement)
- They SHOULD have their own category for tracking

### Why it matters for Vidulum

```
Without self-transfer detection:
  Monthly OUTFLOW report: 22,500 PLN  ← WRONG (includes 9,000 PLN self-transfers)
  Monthly budget:         22,500 PLN  ← MISLEADING
  Forecast:               22,500 PLN/month trend  ← INACCURATE
  "Inne" category:        50.7% of all transactions  ← USELESS (dominated by self-transfers)

With self-transfer detection:
  Monthly OUTFLOW report: 13,500 PLN  ← CORRECT (real expenses only)
  Self-transfers:          9,000 PLN  ← SEPARATE, excluded from budget
  "Inne" category:        ~23% of transactions  ← MEANINGFUL
  Forecast:               13,500 PLN/month trend  ← ACCURATE
```

## Real Data Analysis

### Nest Bank (lu100, CF10000001, 402 transactions)

The user "DEV LUCJAN BIK" has transfers to at least 5 different accounts:

| Destination Account | Counterparty Name | Txns | Description | Self-transfer? |
|---|---|---|---|---|
| PL98124014441111001078171074 | Lucjan Bik Pekao | 74 | "zycie" | ✅ Own Pekao account |
| PL20114020040000370283190287 | Lucjan Bik mbank | 32 | "nadplata mieszkanie kredyt" | ✅ Own mBank account |
| PL74249000050000400005905136 | Lucjan Bik | 3 | "1906" | ❓ Unknown — no bank name hint |
| PL44105014451000009728350316 | Lucjan Bik Ing | 1 | "mieszkanie deposit" | ✅ Own ING account |
| PL68101000712222817219307000 | Lucjan Bik 92101205792 | 1 | "PIT38 Okres rozliczenia" | ❌ Tax office payment |

Current classification by enrichment-prompt:
- 2 transactions detected as SELF_TRANSFER ("Przelew środków" — keyword match)
- 111 classified as UNKNOWN (name "Lucjan Bik Pekao" doesn't match any keyword)
- All 111 UNKNOWN → mapped to "Inne" category

### Pekao (lu101, CF10000002, 791 transactions)

| Counterparty Name | Txns | Type | Bank Category | Detection |
|---|---|---|---|---|
| DEV LUCJAN BIK | 25 | INFLOW | Przelew wewnętrzny | ✅ Bank labeled correctly |
| LUCJAN BIK PEKAO BP | 3 | OUTFLOW | Przelew wewnętrzny | ✅ Bank labeled correctly |
| LUCJAN BIK | 2 | BOTH | Przelew wewnętrzny | ✅ Bank labeled correctly |

Pekao bank correctly labels self-transfers as "Przelew wewnętrzny" — but Nest Bank doesn't provide bank categories at all.

### Edge case: "Lucjan Bik 92101205792" → TAX payment, NOT self-transfer

```
name:        "Lucjan Bik 92101205792"
description: "Identyfikator uzupełniający:N8172193070 Symbol formularza:PIT38 Okres rozliczenia:M042024"
account:     PL68101000712222817219307000

This is a TAX PAYMENT to Urząd Skarbowy (tax office).
The user's name appears because they're the taxpayer, not because it's their account.
Simple name matching would INCORRECTLY flag this as self-transfer.
```

### Edge case: "ARKADIUSZ BIK" → Family member, NOT self-transfer

```
name:        "ARKADIUSZ BIK"
type:        INFLOW
description: "Przelew środków"

Same last name "BIK" but different person (likely family member).
Name matching on last name alone would INCORRECTLY flag this.
```

## Chosen Approach: Multi-Account + Vidulum-Only Detection

### Core Principle

**Self-transfer = transfer where counterparty account is in user's list of owned accounts.**

No guessing. No heuristics for auto-classification. User explicitly declares which accounts are theirs. System matches by IBAN — deterministic, zero false positives, language-agnostic.

### Why not heuristic-based detection?

| Approach | False Positives | Coverage | Works for Firms | Deterministic |
|---|---|---|---|---|
| Name matching only | Yes (tax payments, same-name strangers) | ~96% | No | No |
| Keyword matching only | No | ~2% (only "Przelew środków") | Yes | Yes |
| Account matching (Vidulum) | **No** | **100% of known accounts** | **Yes** | **Yes** |
| Account matching + suggestions | **No** | **~100%** | **Yes** | **Yes** |

## Data Model

### User Financial Profile — separate from User aggregate

Bank accounts are part of the user's **financial identity**, not their auth profile. The User aggregate (`user` collection) handles authentication, roles, and portfolios. Financial metadata lives in a separate document.

**Why separate from User aggregate:**
- Separation of concerns: auth (User) vs finance (Financial Profile)
- User aggregate stays lightweight (no domain creep)
- Financial profile can evolve independently (add tax residency, preferred currency, etc.)
- Different read/write patterns (profile changes rarely, read often during import)

```java
@Document("user_financial_profiles")
public class UserFinancialProfileEntity {
    @Id
    private String userId;               // same as User._id
    
    private List<OwnedBankAccount> ownedBankAccounts;
    
    // Future extensions:
    // private String preferredCurrency;
    // private String taxResidencyCountry;
    // private List<String> taxIdentifiers;  // NIP, PESEL for PL

    public record OwnedBankAccount(
        String iban,                     // "PL98124014441111001078171074"
        String label,                    // "Pekao" — user-friendly name
        String bankName,                 // "Bank Pekao S.A."
        AccountStatus status,            // ACTIVE, CLOSED
        String source,                   // CASHFLOW, MANUAL, SUGGESTION_ACCEPTED
        String linkedCashFlowId,         // non-null if source=CASHFLOW
        ZonedDateTime addedAt,
        ZonedDateTime closedAt           // non-null if status=CLOSED
    ) {}
    
    public enum AccountStatus {
        ACTIVE,    // Account currently in use
        CLOSED     // Account closed but kept for historical transaction matching
    }
}
```

**Account lifecycle:**

```
ACTIVE account:
  - Transactions to/from this account → SELF_TRANSFER
  - Visible in "My Accounts" settings
  - Can be linked to a CashFlow

CLOSED account:
  - Historical transactions to/from this account → still SELF_TRANSFER
    (user transferred to this account in the past, before closing it)
  - Marked in UI as "closed" with closedAt date
  - NOT suggested for new CashFlow creation
  - Can be reopened (user reopens account at bank)

Why keep CLOSED accounts:
  User closed ING account in 2024, but imports Nest Bank CSV from 2021-2025.
  Transfers to ING in 2021-2024 should still be recognized as self-transfers.
  Without CLOSED status, system would lose this knowledge.
```

**Auto-population from CashFlow:**

```
When user creates CashFlow:
  POST /cash-flow/with-history
    bankAccount.iban = "PL14187010452078003019903498"
    bankAccount.bankName = "Nest Bank"

  System checks UserFinancialProfile:
    if IBAN not in ownedBankAccounts:
      → auto-add: {iban, label="Nest Bank", status=ACTIVE, source=CASHFLOW,
                    linkedCashFlowId=CF10000001}
    if IBAN exists but status=CLOSED:
      → reactivate: status = ACTIVE, update linkedCashFlowId
```

### CashChange — add selfTransfer flag

```java
public record CashChange(
    // ... existing fields ...
    boolean selfTransfer          // NEW — excluded from budget calculations
) {}
```

### Forecast — exclude self-transfers from budget

```java
// In CashFlowForecastProcessor:
// When calculating categorized outflows/inflows:
//   SKIP cashChanges where selfTransfer == true
// Show self-transfers in separate section (informational only)
```

## Lifecycle Flow

### Flow 1: First CashFlow — No Other Accounts Known

```
┌──────────────────────────────────────────────────────────────────────────┐
│ USER CREATES FIRST CASHFLOW                                              │
│                                                                          │
│ POST /cash-flow/with-history                                             │
│   bankAccount: "PL14187010452078003019903498" (Nest Bank)               │
│                                                                          │
│ System:                                                                  │
│   1. Creates CashFlow CF10000001                                         │
│   2. Auto-adds IBAN to ownedAccounts:                                    │
│      ownedAccounts = [{PL14187010452078003019903498, "Nest Bank",        │
│                         source=CASHFLOW}]                                │
│                                                                          │
│ Known accounts: 1                                                        │
│ Self-transfer pairs possible: 0 (need ≥2 accounts)                      │
└──────────────────────────────────┬───────────────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ USER IMPORTS NEST BANK CSV (402 transactions)                            │
│                                                                          │
│ During staging/categorization:                                           │
│   For each transaction, check:                                           │
│     counterpartyAccount ∈ ownedAccounts?                                │
│                                                                          │
│   "Lucjan Bik Pekao" → PL98124014441111001078171074                     │
│     → NOT in ownedAccounts (only Nest Bank is known)                    │
│     → Categorized normally (goes to "Inne")                              │
│                                                                          │
│   "Lucjan Bik mbank" → PL20114020040000370283190287                     │
│     → NOT in ownedAccounts                                              │
│     → Categorized normally                                               │
│                                                                          │
│ Result: 0 self-transfers detected (expected — we don't know other accs) │
└──────────────────────────────────┬───────────────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ POST-IMPORT: SYSTEM GENERATES SUGGESTIONS                                │
│                                                                          │
│ Heuristic analysis (NOT for auto-classification — for UI suggestions):  │
│                                                                          │
│ Detected patterns:                                                       │
│   PL98124014441111001078171074 — 74 txns, name contains owner + "Pekao" │
│   PL20114020040000370283190287 — 32 txns, name contains owner + "mbank" │
│   PL44105014451000009728350316 — 1 txn, name contains owner + "Ing"     │
│                                                                          │
│ NOT suggested (filtered out):                                            │
│   PL68101000712222817219307000 — desc contains "PIT38" (tax payment)    │
│   PL74249000050000400005905136 — only 3 txns, no bank hint (ambiguous)  │
│                                                                          │
│ Suggestions stored for user to review in UI                              │
└──────────────────────────────────────────────────────────────────────────┘
```

### Flow 2: User Confirms Suggested Accounts

```
┌──────────────────────────────────────────────────────────────────────────┐
│ UI: "We detected transfers to accounts that may be yours"               │
│                                                                          │
│ ┌────────────────────────────────────────────────────────────────┐       │
│ │ Suggested accounts:                                            │       │
│ │                                                                │       │
│ │ [✓] PL98124014441111001078171074                              │       │
│ │     "Lucjan Bik Pekao" — 74 transfers, 222,000 PLN total     │       │
│ │                                                                │       │
│ │ [✓] PL20114020040000370283190287                              │       │
│ │     "Lucjan Bik mbank" — 32 transfers, 96,000 PLN total      │       │
│ │                                                                │       │
│ │ [✓] PL44105014451000009728350316                              │       │
│ │     "Lucjan Bik Ing" — 1 transfer, 5,000 PLN                 │       │
│ │                                                                │       │
│ │ [Confirm selected]  [Skip for now]                             │       │
│ └────────────────────────────────────────────────────────────────┘       │
└──────────────────────────────────┬───────────────────────────────────────┘
                                   │ User clicks "Confirm selected"
                                   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ RECATEGORIZATION                                                         │
│                                                                          │
│ POST /api/v1/user/owned-accounts/suggestions/accept                     │
│ Body: { accounts: [PL981..., PL201..., PL441...] }                      │
│                                                                          │
│ Step 1: Add accounts to ownedAccounts                                    │
│   ownedAccounts = [                                                      │
│     {PL141... Nest Bank, CASHFLOW},                                      │
│     {PL981... Pekao, SUGGESTION_ACCEPTED},        ← NEW                 │
│     {PL201... mBank, SUGGESTION_ACCEPTED},         ← NEW                │
│     {PL441... ING, SUGGESTION_ACCEPTED}            ← NEW                │
│   ]                                                                      │
│                                                                          │
│ Step 2: Find matching transactions in ALL user's CashFlows               │
│   CF10000001 (Nest Bank):                                                │
│     74 txns → PL981... (Pekao) → SELF_TRANSFER                         │
│     32 txns → PL201... (mBank) → SELF_TRANSFER                         │
│      1 txn  → PL441... (ING)   → SELF_TRANSFER                         │
│                                                                          │
│ Step 3: Update cash changes                                              │
│   For each matched transaction:                                          │
│     categoryName: "Inne" → "Przelewy własne"                           │
│     selfTransfer: false → true                                           │
│                                                                          │
│ Step 4: Recalculate forecast                                             │
│   Remove 107 transactions from budget calculations                       │
│   "Inne": 204 → 97 transactions                                         │
│   New "Przelewy własne" section in forecast (informational)             │
│                                                                          │
│ Result: 107 transactions recategorized, budget corrected                 │
└──────────────────────────────────────────────────────────────────────────┘
```

### Flow 3: User Creates Second CashFlow — Automatic Detection

```
┌──────────────────────────────────────────────────────────────────────────┐
│ USER CREATES SECOND CASHFLOW (Pekao)                                     │
│                                                                          │
│ POST /cash-flow/with-history                                             │
│   bankAccount: "PL98124014441111001078171074" (Pekao)                   │
│                                                                          │
│ System:                                                                  │
│   Checks: is PL981... already in ownedAccounts?                         │
│   → YES (added in Flow 2 via suggestion)                                │
│   → Skip auto-add (already known)                                        │
│                                                                          │
│ ownedAccounts unchanged: [Nest Bank, Pekao, mBank, ING]                 │
└──────────────────────────────────┬───────────────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ IMPORT PEKAO CSV (791 transactions)                                      │
│                                                                          │
│ During staging:                                                          │
│   "DEV LUCJAN BIK" INFLOW from PL14187010452078003019903498 (Nest Bank)│
│     → PL141... IS in ownedAccounts                                      │
│     → AUTO SELF_TRANSFER ✅ (no suggestion needed — certain)            │
│                                                                          │
│   "Urzad skarbowy" OUTFLOW to PL68101000712222817219307000             │
│     → PL681... NOT in ownedAccounts                                     │
│     → Normal transaction (Podatki) ✅                                    │
│                                                                          │
│ Both sides of the transfer now in Vidulum:                               │
│                                                                          │
│   Nest Bank CF:  OUTFLOW -3000 PLN → Przelewy własne (selfTransfer)   │
│   Pekao CF:      INFLOW  +3000 PLN → Przelewy własne (selfTransfer)   │
│   ───────────────────────────────────────────────                        │
│   Net impact on user's total wealth: 0 PLN ✅                           │
└──────────────────────────────────────────────────────────────────────────┘
```

### Flow 4: User Manually Adds Account

```
┌──────────────────────────────────────────────────────────────────────────┐
│ UI: Settings → My Bank Accounts → Add Account                            │
│                                                                          │
│ ┌────────────────────────────────────────────────────────────────┐       │
│ │ IBAN: [PL74249000050000400005905136        ]                   │       │
│ │ Label: [Konto oszczędnościowe               ]                 │       │
│ │                                                                │       │
│ │ [Add account]                                                  │       │
│ └────────────────────────────────────────────────────────────────┘       │
│                                                                          │
│ POST /api/v1/user/owned-accounts                                        │
│ Body: { iban: "PL74249000050000400005905136", label: "Konto oszcz." }   │
│                                                                          │
│ System:                                                                  │
│   1. Validates IBAN format                                               │
│   2. Adds to ownedAccounts (source=MANUAL)                              │
│   3. Scans ALL CashFlows for matching counterpartyAccount               │
│   4. Finds 3 txns "Lucjan Bik" → PL742... in CF10000001                │
│   5. Recategorizes: "Inne" → "Przelewy własne" (selfTransfer=true)     │
│   6. Recalculates forecast                                               │
│                                                                          │
│ UI notification: "3 transactions recategorized as self-transfers"        │
└──────────────────────────────────────────────────────────────────────────┘
```

### Flow 5: User Removes Account (reversal)

```
┌──────────────────────────────────────────────────────────────────────────┐
│ UI: Settings → My Bank Accounts → Remove "mBank"                        │
│                                                                          │
│ DELETE /api/v1/user/owned-accounts/PL20114020040000370283190287          │
│                                                                          │
│ System:                                                                  │
│   1. Removes PL201... from ownedAccounts                                │
│   2. Finds 32 txns marked selfTransfer with that counterpartyAccount    │
│   3. Reverses: "Przelewy własne" → previous category (or "Inne")       │
│   4. selfTransfer: true → false                                          │
│   5. Recalculates forecast                                               │
│                                                                          │
│ Result: 32 transactions back in regular budget                           │
│ UI notification: "32 transactions moved back from self-transfers"        │
└──────────────────────────────────────────────────────────────────────────┘
```

## Self-Transfer Display in CashFlow

### Transaction View

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 31 Dec 2025                                                             │
│                                                                         │
│ 🔄 Lucjan Bik Pekao                                    -3,000.00 PLN  │
│    Przelewy własne · Self-transfer                                     │
│    → PL98 1240 1444 1111 0010 7817 1074 (Pekao)                       │
│    ────────────────────────────────────────                             │
│    Tytuł: zycie                                                         │
│                                                                         │
│ 🔄 Lucjan Bik mbank                                    -3,000.00 PLN  │
│    Przelewy własne · Self-transfer                                     │
│    → PL20 1140 2004 0000 3702 8319 0287 (mBank)                       │
│    ────────────────────────────────────────                             │
│    Tytuł: nadplata mieszkanie kredyt                                    │
│                                                                         │
│    Urzad skarbowy w Mielcu                              -2,837.00 PLN  │
│    Podatki i składki                                                    │
│    → PL68 1010 0071 2222 8172 1930 7000                                │
│    ────────────────────────────────────────                             │
│    Tytuł: PIT28 Okres rozliczenia:M112025                              │
└─────────────────────────────────────────────────────────────────────────┘

Key: 🔄 icon marks self-transfers visually
     "Self-transfer" label distinguishes from regular transactions
     Transaction IS visible (exists in bank statement) but flagged
```

### Budget Summary View

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Styczeń 2025 — Budget Summary                                          │
│                                                                         │
│ EXPENSES (excluding self-transfers):                                    │
│ ┌─────────────────────────────────────────────────────────────────┐    │
│ │ Opłaty obowiązkowe     5,253.90 PLN  ████████████████          │    │
│ │ Żywność                2,847.30 PLN  ██████████                 │    │
│ │ Transport                892.00 PLN  ███                        │    │
│ │ Inne                   1,230.50 PLN  ████                       │    │
│ │ ─────────────────────────────────────                           │    │
│ │ Total expenses:       10,223.70 PLN                             │    │
│ └─────────────────────────────────────────────────────────────────┘    │
│                                                                         │
│ SELF-TRANSFERS (not counted in budget):                                 │
│ ┌─────────────────────────────────────────────────────────────────┐    │
│ │ 🔄 → Pekao             3,000.00 PLN                            │    │
│ │ 🔄 → mBank             3,000.00 PLN                            │    │
│ │ ─────────────────────────────────────                           │    │
│ │ Total self-transfers:  6,000.00 PLN                             │    │
│ └─────────────────────────────────────────────────────────────────┘    │
│                                                                         │
│ INCOME:                                                                 │
│ ┌─────────────────────────────────────────────────────────────────┐    │
│ │ Wynagrodzenie         31,064.44 PLN                             │    │
│ └─────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

### Forecast View

```
┌─────────────────────────────────────────────────────────────────────────┐
│ CashFlow Forecast — Nest Bank                                           │
│                                                                         │
│ Monthly averages (last 12 months):                                      │
│                                                                         │
│   Real expenses:        ~13,500 PLN/month  ← used for forecast          │
│   Self-transfers:        ~9,000 PLN/month  ← excluded from forecast     │
│   Bank statement total: ~22,500 PLN/month  ← what bank shows            │
│                                                                         │
│ Forecast is based on REAL EXPENSES only,                                │
│ not inflated by self-transfers.                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

## Suggestion Heuristic — When to Suggest

The heuristic is used ONLY for generating UI suggestions, never for auto-classification.

```
For each unique counterpartyAccount in imported transactions:

  IF account NOT in ownedAccounts:

    Signal 1: Frequency
      txnCount >= 3                        → frequencyScore = HIGH
      txnCount == 2                        → frequencyScore = MEDIUM
      txnCount == 1                        → frequencyScore = LOW

    Signal 2: Owner name in counterparty name
      ownerParts = extractNameParts(accountOwner)     // ["LUCJAN", "BIK"]
      allPartsMatch = ownerParts.all(part → txnName.contains(part))
      → nameScore = HIGH if allPartsMatch, NONE otherwise

    Signal 3: Bank name hint in counterparty name
      knownBanks = [PEKAO, MBANK, ING, PKO, SANTANDER, MILLENNIUM, ...]
      hasBankHint = knownBanks.any(bank → txnName.contains(bank))
      → bankHintScore = HIGH if present

    Signal 4: Official payment exclusion
      officialPatterns = [PIT, VAT, ZUS, NIP, REGON, mandat, faktura,
                          polisa, składka, Identyfikator]
      isOfficial = officialPatterns.any(p → description.contains(p))
      → IF isOfficial: DO NOT suggest (it's tax/insurance, not own account)

    Decision:
      nameScore=HIGH + bankHintScore=HIGH + !isOfficial
        → SUGGEST with HIGH confidence
        → "Lucjan Bik Pekao" (74 txns) — likely your Pekao account

      nameScore=HIGH + frequencyScore=HIGH + !isOfficial
        → SUGGEST with MEDIUM confidence
        → "Lucjan Bik" (3 txns) — might be your account

      nameScore=NONE OR isOfficial
        → DO NOT suggest
        → "Lucjan Bik 92101205792" (PIT38) — tax payment, skip
        → "ARKADIUSZ BIK" — different person, skip
```

## API Endpoints

```
# Owned accounts management
GET    /api/v1/user/owned-accounts
       → [{ iban, label, source, cashFlowId, addedAt }]

POST   /api/v1/user/owned-accounts
       Body: { iban: "PL...", label: "mBank" }
       → Adds account, triggers recategorization

DELETE /api/v1/user/owned-accounts/{iban}
       → Removes account, reverses recategorization

# Suggestions (generated after CSV import)
GET    /api/v1/user/owned-accounts/suggestions?cashFlowId={cfId}
       → [{ iban, suggestedLabel, confidence, transactionCount, totalAmount,
            sampleTransactions: ["Lucjan Bik Pekao", ...] }]

POST   /api/v1/user/owned-accounts/suggestions/accept
       Body: { ibans: ["PL98...", "PL20..."] }
       → Accepts selected suggestions, adds to ownedAccounts, recategorizes

# Manual recategorization trigger
POST   /api/v1/user/owned-accounts/recategorize
       → Forces recategorization scan across all CashFlows
```

## Implementation Phases

### Phase 1: MVP — Account Registry + Detection

```
New:
  - OwnedBankAccountsEntity (MongoDB document per user)
  - OwnedBankAccountsRepository (CRUD)
  - OwnedAccountService (add/remove/list/recategorize)
  - REST endpoint: /api/v1/user/owned-accounts (GET, POST, DELETE)

Modified:
  - CashFlow creation: auto-add IBAN to owned accounts
  - StageTransactionsCommandHandler: check counterpartyAccount vs ownedAccounts
  - StartImportJobCommandHandler: set selfTransfer flag on matching cash changes
  - CashFlowForecastProcessor: exclude selfTransfer transactions from budget

Domain changes:
  - CashChange: add boolean selfTransfer field
  - CashFlowEntity: add selfTransfer to CashChangeDocument
  - Category "Przelewy własne" under "Zarządzanie kontem" (auto-created)
```

### Phase 2: Suggestions + Recategorization

```
New:
  - SelfTransferSuggestionService (heuristic analysis after import)
  - RecategorizationService (batch update cash changes)
  - REST endpoints: suggestions, accept, recategorize

Modified:
  - Post-import hook: generate suggestions
  - Forecast recalculation after recategorization
```

### Phase 3: UX Integration

```
  - UI: "My Bank Accounts" settings page
  - UI: Post-import suggestion dialog
  - UI: Self-transfer badge on transactions
  - UI: Budget view with self-transfers separated
```

## Estimated Impact

```
After full implementation:

Nest Bank:
  "Inne":          204 (50.7%) → ~93 (23.1%)     — 111 self-transfers removed
  Self-transfers:  0 → 107 (properly categorized)
  Budget accuracy: inflated by ~345k → correct

Pekao:
  Self-transfers:  30 (in Uncategorized) → 30 (properly categorized)
  Budget accuracy: inflated by ~90k → correct

Combined:
  Budget correction: ~435,000 PLN of false "expenses" removed from reports
```

## Refined Business Model: Account-First Architecture (2026-06-04)

After implementing Phase 1 MVP we recognized that the original design conflates two
unrelated concerns under the word "profile". The refined model separates them and
puts **owned bank accounts** at the centre of the user's financial identity, with
CashFlow becoming a *view* over an account rather than the source of truth about it.

### Two distinct operations, one previously-confusing name

| Operation | When | Frequency |
|---|---|---|
| **Create profile** (empty container) | At user registration (`/auth/register`) | Exactly once per user |
| **Claim a bank account** (add IBAN to `ownedAccounts[]`) | Onboarding, manual add, accepted suggestion, CashFlow creation | 0…N per user |

These are different lifecycle events. Method names should reflect this — the original
`onCashFlowCreated` was renamed to `claimAccountFromCashFlow` to drop the misleading
"event handler" prefix and describe the actual semantics.

### Why owned-accounts is the primary entity (not derivable from CashFlows)

A naive design might compute `ownedAccounts` on-the-fly: "every IBAN that has a
CashFlow belongs to the user". That model breaks in three real scenarios:

1. **Accounts without a CashFlow** — user mentions an account during onboarding ("yes,
   I also have an ING savings account, single deposit") but never imports its CSV into
   Vidulum. Account is owned for self-transfer detection but no CashFlow exists.
2. **Closed accounts (Phase 2)** — user closed the ING account at the bank in 2024,
   the CashFlow is no longer used, but historical transactions in their Nest CSV
   from 2021-2024 referencing that IBAN must still be recognized as self-transfers.
3. **Suggestion-accepted accounts** — system suggests "this IBAN you transfer to
   looks like yours", user confirms. No CashFlow is created from that — just the claim.

Hence `owned_bank_accounts` is a first-class entity, and CashFlow is one of several
ways an account becomes claimed.

### Account sources after refinement

```
AccountSource:
  ONBOARDING            ← user added during initial onboarding wizard (NEW)
  MANUAL                ← added later via Settings → My Bank Accounts
  SUGGESTION_ACCEPTED   ← system detected likely self-transfer, user confirmed
  CASHFLOW              ← (legacy/transitional) implicit claim when CashFlow created
                          with previously-unknown IBAN. In the target UI flow this
                          path should be rare because CashFlow creation picks from
                          existing accounts; see "UI flow" section below.
```

`source` is **immutable** after creation (it records *how* the claim happened).
`linkedCashFlowId` is **mutable** (a CashFlow can be created/deleted later for an
already-owned account, regardless of source).

### UI flow: account-first CashFlow creation

The intended end-state UX:

```
┌──────────────────────────────────────────────────────────────────────────┐
│ ONBOARDING (immediately after registration, ~2 min, skippable)           │
│                                                                          │
│ "Tell us about your bank accounts so we can detect transfers between    │
│  them and keep your budget accurate."                                    │
│                                                                          │
│ For each account: [IBAN] [Bank name auto-detected from bank code]       │
│                   [Label, e.g. "Pekao - życie"]                          │
│                                                                          │
│ Submit → POST /api/v1/user/owned-accounts/bulk                          │
│          source = ONBOARDING                                             │
└──────────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ CREATING A CASHFLOW (sometime later)                                     │
│                                                                          │
│ "Which account is this CashFlow for?"                                   │
│                                                                          │
│   [✓] Pekao - życie (PL98 1240 ...)         ← from registry             │
│   [ ] mBank kredyt (PL20 1140 ...)          ← from registry             │
│   [ ] ING oszczędności (PL44 1050 ...)      ← from registry             │
│   [ ] + Add a new bank account              ← inline quick-add          │
│                                                                          │
│ Pick existing → iban/currency/bankName auto-filled, read-only           │
│   User only enters: startPeriod, initialBalance                          │
│   Submit → CashFlow created, OwnedBankAccount.linkedCashFlowId updated  │
│                                                                          │
│ + Add new → modal dialog: same form as onboarding (single account)      │
│   On submit:                                                             │
│     POST /api/v1/user/owned-accounts (source = MANUAL)                  │
│     UI refreshes the dropdown list                                      │
│     UI automatically selects the just-added account                     │
│     User continues with CashFlow creation (no re-typing)                │
└──────────────────────────────────────────────────────────────────────────┘
```

**Important UX contract**: the "+ Add a new bank account" flow inside CashFlow
creation must not lose the user's CashFlow form state. After the inline dialog
closes the new account, the UI refreshes the available-accounts list, selects the
just-created entry, and lets the user continue filling in `startPeriod` and
`initialBalance` without restarting the wizard.

### Backend implications of the account-first model

What is needed beyond Phase 1 MVP to support this UX:

1. **New `AccountSource.ONBOARDING`** — distinguishes onboarding-time entries from
   later manual additions for analytics ("did onboarding cover most accounts?").
2. **`POST /api/v1/user/owned-accounts/bulk`** — array of `AddOwnedAccountRequest`.
   Atomically validates and persists all entries (or none); emits one
   `OwnedBankAccountAddedEvent` per accepted account so downstream consumers can
   react. Onboarding submits the whole list in one call.
3. **`GET /api/v1/user/owned-accounts/available-for-cashflow`** — returns accounts
   where `status == ACTIVE && linkedCashFlowId == null`. Drives the dropdown in
   the CashFlow-creation UI.
4. **Extend CashFlow creation payload** — accept either:
   - `ownedAccountIban: "PL98..."` (pick from registry; backend resolves bankAccount
     details from the profile, attaches `linkedCashFlowId` to the existing entry)
   - or `bankAccount: { ... }` (current shape; backend internally claims as new
     `source=CASHFLOW` entry)
5. **`linkedCashFlowId` becomes mutable** — set on link, cleared when a CashFlow is
   removed (note: today's E6 still applies — *active* CashFlow's linked account
   cannot be removed from the registry).

### Migration of `source=CASHFLOW` (open question)

With the account-first model, `source=CASHFLOW` should only occur when a user creates
a CashFlow with an IBAN they never declared (skip onboarding, never used quick-add).
In the long run this might be folded into `MANUAL` (with `linkedCashFlowId` set on
creation) so `source` records only user-intent acts. Decision deferred — track as
follow-up.

## UI Mockup (Phase 3 reference)

Visual reference for the onboarding flow + CashFlow picker + quick-add dialog is in
[`../design/VID-161-onboarding-bank-accounts-mockup.html`](../design/VID-161-onboarding-bank-accounts-mockup.html).
It includes:

- Annotated visual mockups of the 3 screens (onboarding form, picker dropdown, quick-add modal)
- REST API integration table mapping each UI action to the exact endpoint
- Request/response examples for `POST /owned-accounts/bulk` and CashFlow creation with `ownedAccountIban`
- Error-code → UI handling table
- End-to-end flow diagram (UI events ↔ backend events)

Open the file in a browser to see the rendered design.

## Implementation Decisions — Phase 1c (account-first backend support, 2026-06-04)

Decisions confirmed during implementation of bulk endpoint, available-for-cashflow
endpoint, `ownedAccountIban` field, and Kafka listener:

- **C.4 dropped — `ownedAccountIban` field removed as redundant.** Initially the design
  added an explicit `ownedAccountIban` reference to the CashFlow creation payload to
  signal "this IBAN comes from the registry, not freshly typed". After implementing
  C.5 (Kafka listener with idempotent `claimOrLinkAccountForCashFlow`), the listener
  does the right thing in both cases purely from `bankAccount.iban` — it either links
  to an existing OwnedBankAccount or claims as new. The extra field added complexity
  (strict-match validation, doc surface) without adding any backend behavior, so it
  was removed before deployment. UI keeps the picker UX; it simply pre-fills the
  `bankAccount` payload from the chosen registry entry — no extra field needed.
- **C.5 listener semantics**: single idempotent method
  `claimOrLinkAccountForCashFlow(userId, bankAccount, cashFlowId)`. If IBAN already in
  profile (any source) → update `linkedCashFlowId`. If not in profile → create new
  entry with `source=CASHFLOW`. Replays safe: re-processing the same event sets the
  same `linkedCashFlowId` (no-op when equal).
- **C.5 event coverage**: listener handles both `CashFlowCreatedEvent` and
  `CashFlowWithHistoryCreatedEvent`. Resolves prior inconsistency where only the
  with-history variant triggered registry claim.
- **C.5 consumer group**: `owned_accounts_group` (separate from the forecast processor's
  `group_id7` so both listeners receive the same events on the `cash_flow` topic).
- **C.2 bulk atomicity**: validate-all-before-save. Parse every IBAN through
  `IbanNumber`, detect duplicates within the batch, detect collisions with existing
  registry entries. If any error → throw before any persistence. If all OK → save the
  profile once, emit N `OwnedBankAccountAddedEvent` events. No MongoDB transactions
  required because validation happens entirely before the write.
- **C.3 filter scope**: `GET /owned-accounts/available-for-cashflow` returns all
  accounts where `status=ACTIVE` AND `linkedCashFlowId IS NULL`. No currency filter —
  the UI can filter client-side if needed.
- **CashFlow delete lifecycle**: out-of-scope in this iteration. When a CashFlow is
  removed, the linked OwnedBankAccount currently keeps a dangling `linkedCashFlowId`.
  A future ticket should add a `CashFlowDeletedEvent` and have the listener clear the
  link. Tracked in TODO Phase 2 below.
- **`linkedCashFlowId` protection broadened**: the "cannot remove CashFlow-linked
  account" rule now applies to *any* source (not only `CASHFLOW`). This matters when
  a user manually adds an IBAN during onboarding and later creates a CashFlow for it
  — the manual entry becomes linked and is then protected from removal while active.

## Implementation Status (2026-06-04)

### ✅ DONE — Phase 1 MVP + Phase 1c (account-first backend)

All in `com.multi.vidulum.user_financial_profile`:

**Domain layer**
- `UserFinancialProfile` aggregate (`addAccount`, `removeAccount`, `linkCashFlow`, `ownsAccount`, queries)
- `OwnedBankAccount` value record (immutable IBAN + currency + bankName + label + status + source + linkedCashFlowId)
- `AccountStatus` enum (`ACTIVE`, `CLOSED`)
- `AccountSource` enum (`ONBOARDING`, `CASHFLOW`, `SUGGESTION_ACCEPTED`, `MANUAL`)
- `UserFinancialProfileEvent` sealed interface with 5 record events
- `DomainUserFinancialProfileRepository` interface
- 4 business exceptions (`BankAccountAlreadyOwned`, `OwnedAccountNotFound`, `UserFinancialProfileNotFound`, `CannotRemoveLinkedCashFlowAccount`)

**Infrastructure layer**
- `UserFinancialProfileEntity` (@Document `user_financial_profiles`) with nested `OwnedBankAccountDocument`
- `UserFinancialProfileMongoRepository` (Spring Data) + `DomainUserFinancialProfileRepositoryImpl` (bridge)
- `UserFinancialProfileEventEmitter` → Kafka topic `user_financial_profile`

**App layer**
- `UserFinancialProfileService` — plain service exposing: `createEmptyProfile`, `addAccount`, `addAccounts` (bulk), `claimOrLinkAccountForCashFlow`, `removeAccount`, `listAccounts`, `listAccountsAvailableForCashFlow`, `ownsAccount`
- `UserFinancialProfileRestController` — endpoints below
- `UserFinancialProfileCashFlowListener` — Kafka listener on `cash_flow` topic, handles both `CashFlowCreatedEvent` and `CashFlowWithHistoryCreatedEvent` via idempotent `claimOrLinkAccountForCashFlow`
- DTOs with `@Valid` / `@NotBlank` / `@NotEmpty` field validation

**REST endpoints (all authenticated via JWT, userId resolved from token)**

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/user/owned-accounts` | List all owned accounts for the authenticated user |
| `POST` | `/api/v1/user/owned-accounts` | Add a single account (source = MANUAL) |
| `POST` | `/api/v1/user/owned-accounts/bulk` | Onboarding bulk add (source = ONBOARDING, validate-all-before-save) |
| `GET` | `/api/v1/user/owned-accounts/available-for-cashflow` | List accounts ready to be picked for a new CashFlow (status=ACTIVE, linkedCashFlowId=null) |
| `DELETE` | `/api/v1/user/owned-accounts/{iban}` | Remove an account (hard delete; protected if linked to active CashFlow) |

**Wiring into existing code**
- `RegisterUserCommandHandler` → inline `createEmptyProfile()` after `userRepository.save()` — every registered user has an empty profile document immediately
- `CreateCashFlowJson` + `CreateCashFlowWithHistoryJson` — new optional field `ownedAccountIban`; strict-match validation in controller
- `CashFlowRestController.validateOwnedAccountIbanMatches()` — helper enforcing `ownedAccountIban == bankAccount.iban` if both provided
- `CreateCashFlowWithHistoryCommandHandler` — **no inline call** to profile service; claim/link happens via Kafka listener
- `KafkaTopicConfig` — `NewTopic("user_financial_profile", 1, 1)` + producer/consumer/container factories
- `ErrorCode` — 6 new codes (`OWNED_ACCOUNT_*`)
- `ErrorHttpHandler` — 4 new `@ExceptionHandler` mapping business exceptions to HTTP (400/404/409/422)
- `VidulumApplication.clearData()` — drops `UserFinancialProfileEntity` collection on startup

**Tests**
- `UserFinancialProfileHttpIntegrationTest` (14 scenarios: T1, T2, T3, T5, T6, T7, T9 positive + E1-E8 error) — all green
- `UserFinancialProfileHttpActor` — HTTP encapsulation following project's Actor pattern
- T3, T6 (E6), T7, T9 use `Awaitility.await()` because claim/link is now eventually consistent via Kafka

**End-to-end behaviour available today**
- Empty profile auto-created at user registration (synchronous)
- Single account add via REST (`source=MANUAL`)
- Bulk add via onboarding (`source=ONBOARDING`, all-or-nothing validation)
- IBAN claimed automatically when CashFlow is created (eventual consistency through `cash_flow` Kafka topic + dedicated listener, both `CashFlowCreatedEvent` and `CashFlowWithHistoryCreatedEvent` covered)
- IBAN already in profile when CashFlow is created → existing entry gets `linkedCashFlowId` updated (no duplicate, source preserved)
- Available-for-cashflow query returns accounts not yet linked
- Hard delete via REST; protected if `linkedCashFlowId != null && status == ACTIVE` (regardless of source)
- Cross-user isolation (same IBAN allowed across different users)
- Kafka events emitted for every state transition (`Created`, `Added`, `Removed`)
- All business exceptions mapped to proper HTTP status codes with `ApiError` body
- UI mockup with REST integration guide at `docs/design/VID-161-onboarding-bank-accounts-mockup.html`

### ⏸ TODO — Phase 1b: Self-Transfer Detection Wiring (next)

The profile registry exists but **does not yet influence transaction categorization**. Required to deliver the actual business value:

- **CashChange field** `boolean selfTransfer` — add to:
  - `CashChange` domain record
  - `CashChangeSnapshot`
  - `CashChangeEntity` persistence
  - `CashFlowEvent.HistoricalCashChangeImportedEvent` payload (and any other CashChange-creating event)
  - `CashFlow.apply(...)` methods that construct CashChange instances
- **Detection in staging** — `StageTransactionsCommandHandler.processTransaction(...)`:
  - Add **Priority 0** check before existing bankCategory/pattern/mapping priorities
  - Inject `UserFinancialProfileService`; query `ownsAccount(userId, counterpartyAccount)`
  - On match → set `mappedData.categoryName = "Przelewy własne"`, mark transaction so import phase sets `selfTransfer=true`
- **Detection in import** — `StartImportJobCommandHandler` / `ImportHistoricalCashChangeCommandHandler`:
  - Propagate `selfTransfer` flag through `ImportTransactionRequest` → event → CashChange
- **Auto-create category** "Przelewy własne" under "Zarządzanie kontem" when first self-transfer arrives
- **Forecast exclusion** — modify forecast handlers:
  - `PaidCashChangeAppendedEventHandler` and `ExpectedCashChangeAppendedEventHandler` skip `categorizedInFlows`/`categorizedOutFlows` for `selfTransfer==true`
  - Add separate informational section to `CashFlowMonthlyForecast` (optional but useful for UI)
  - Same treatment for `CashChangeConfirmedEvent`, `CashChangeEditedEvent` paths
- **Decision still open**: what to do with existing heuristic SELF_TRANSFER in `bank_data_adapter` (AI enrichment) — keep as pre-suggestion vs. let IBAN-match be sole source of truth. Documented in original design Section "Suggestion Heuristic".

### ⏸ TODO — CashFlow deletion lifecycle (cross-cutting)

When a CashFlow is deleted, the OwnedBankAccount currently keeps a dangling
`linkedCashFlowId` pointing to a non-existent CashFlow. Required follow-up:

- Emit `CashFlowDeletedEvent` on the `cash_flow` topic when a CashFlow is removed
- Extend `UserFinancialProfileCashFlowListener` to clear `linkedCashFlowId` on the
  matching OwnedBankAccount (account itself stays — user still owns the IBAN, just
  no longer has a CashFlow for it)
- Update the "cannot remove linked account" protection: this stays as-is (an account
  with `linkedCashFlowId == null` can be removed regardless of past history)

### ⏸ TODO — Phase 2: Suggestions + Recategorization

- **`SelfTransferSuggestionService`** — after each import, analyze counterparty accounts using heuristics from Section "Suggestion Heuristic — When to Suggest" (frequency, owner-name match, bank-name hint, official-payment exclusion). Persist suggestions for user review.
- **REST endpoints**:
  - `GET    /api/v1/user/owned-accounts/suggestions?cashFlowId={cfId}`
  - `POST   /api/v1/user/owned-accounts/suggestions/accept` body `{ ibans: [...] }`
  - `POST   /api/v1/user/owned-accounts/recategorize` (manual trigger)
- **`RecategorizationListener`** — Kafka consumer on topic `user_financial_profile`:
  - On `OwnedBankAccountAddedEvent`: find all CashChanges across user's CashFlows with matching `counterpartyAccount`, recategorize to "Przelewy własne", set `selfTransfer=true`, trigger forecast recalculation
  - On `OwnedBankAccountRemovedEvent`: reverse — `selfTransfer: true→false`, restore previous category (or "Inne")
- **New domain event** `CashChangeRecategorizedEvent` on the `cash_flow` topic so the forecast read-model picks up changes

### ⏸ TODO — Phase 3: UX Integration (account-first model)

- **Onboarding wizard** (immediately post-registration, ~2 min, skippable)
  - Free-form list of bank accounts: IBAN + auto-detected bank name + user label
  - Uses `POST /api/v1/user/owned-accounts/bulk` with `source=ONBOARDING`
  - Motivation copy: "we use this to detect transfers between your accounts so
    your budget shows real spending"
- **CashFlow creation picker** (driven by `GET /owned-accounts/available-for-cashflow`)
  - Dropdown shows existing ACTIVE accounts not yet linked to a CashFlow
  - Pick existing → iban/currency/bankName auto-filled, read-only
  - "+ Add new bank account" → inline dialog (same shape as onboarding entry)
  - **Critical UX requirement**: after inline add, UI must refresh the dropdown,
    auto-select the newly added account, and let the user continue with the
    CashFlow form without restarting (no state loss)
- **"My Bank Accounts" settings page** — list/add/remove with `linkedCashFlowId`
  badge and source indicator (ONBOARDING / MANUAL / SUGGESTION_ACCEPTED / CASHFLOW)
- **Post-import suggestion dialog** — show suggested IBANs with confidence and
  sample transactions (powered by Phase 2 suggestion service)
- 🔄 self-transfer badge on transaction rows
- Budget view with self-transfers in separate section (excluded from main expense total)
- Forecast view annotated with "Real expenses" vs "Self-transfers" averages

### ✅ DONE — Backend support for account-first UX (Phase 1c, 2026-06-04)

- ✅ **`AccountSource.ONBOARDING`** enum value added
- ✅ **`POST /api/v1/user/owned-accounts/bulk`** — validate-all-before-save, emits one
  `OwnedBankAccountAddedEvent` per added account on success
- ✅ **`GET /api/v1/user/owned-accounts/available-for-cashflow`** — filters
  `status=ACTIVE && linkedCashFlowId IS NULL`
- ❌ **`ownedAccountIban` field** — dropped as redundant after C.5 made the listener
  idempotent (see decision note above). CashFlow creation payload unchanged from MVP.
- ✅ **`claimOrLinkAccountForCashFlow`** Kafka listener (`UserFinancialProfileCashFlowListener`)
  on the `cash_flow` topic — handles both `CashFlowCreatedEvent` and
  `CashFlowWithHistoryCreatedEvent`. Single idempotent path replaces the previous
  inline call.
- ⏸ **`source=CASHFLOW` migration decision** — deferred; revisit after Phase 3 UI
  is deployed and we can measure how often source=CASHFLOW still occurs.

### ⏸ TODO — Lifecycle (skipped in MVP)

- `AccountStatus.CLOSED` state — user closes account at bank but historical transactions still need detection
- `closeAccount(iban)` / `reactivateAccount(iban)` service methods
- Auto-close when linked CashFlow is closed (via `linkedCashFlowId`)
- UI to display/manage closed accounts

### ⏸ TODO — Optional improvements

- IBAN → BankName resolver from Polish bank code (top 15 PL banks) so `bankName` can default sensibly when user doesn't provide
- Currency detection from IBAN country code (PL→PLN, DE→EUR) as default
- Pagination on `GET /owned-accounts` if a user accumulates many accounts
- Migration script for existing users without profiles (not needed today — no production users, `clearData()` wipes on startup)

## Manual End-to-End Verification Results (2026-06-06)

Real-data manual test run against fresh Docker stack with Anthropic AI key, using both
CSV samples from `~/Pulpit/bank-csv-samples/`.

### Test data

| CSV | Bank | Transactions | Months | AI SELF_TRANSFER detected |
|---|---|---|---|---|
| `nestbank_lista_operacji_20260111.csv` | Nest Bank | 402 | 36 (2023-01 → 2025-12) | **2** (keyword "Przelew środków") |
| `pekao_sa_Lista_operacji_20260111_013400.csv` | Pekao S.A. | 791 | 13 (2025-01 → 2026-01) | **30** (bank category "Przelew wewnętrzny") |

### User setup tested

- Onboarding: bulk-added both IBANs (Nest + Pekao) with `source=ONBOARDING`
- Created 2 CashFlows — listener LINKED both existing entries (not duplicate CASHFLOW source)
- Final profile state: both IBANs with `linkedCashFlowId` set, `source` immutable as `ONBOARDING`

### Pipeline that worked

CSV upload → AI transform → staging → force-uncategorized → import job (402/402 in 11s) → attestation → forecast (53 months, 37 with PAID data, totals ≈ 1.21M PLN both sides).

### Critical finding: Phase 1 foundation works, but problem NOT solved yet

In Nest CashFlow forecast (Uncategorized bucket) there are **106 transactions whose
counterparty name contains "Pekao" or "mBank"**. These transactions:

- Have `counterpartyAccount = PL98...` (Pekao IBAN) on the original CSV row
- The Pekao IBAN IS in the user's `UserFinancialProfile.ownedAccounts`
- Therefore deterministic IBAN-match detection would catch them as self-transfers
- **But the staging/import pipeline does NOT do this cross-reference today**
- All 106 are counted as real expenses in the budget, contributing to the original
  "50.7% Inne pollution" problem documented in [VID-159](VID-159-THREE-SIGNAL-RESULTS-2026-04-26.md)

### Gap map — registry exists, but detection wiring is missing

```
DATA IN:                                         CODE PATH:
─────────────────                                ───────────────────────────────────
CSV Nest, transaction:                           ✅ BankCsvRow.counterpartyAccount() extracts IBAN
  counterpartyAccount = PL98... (Pekao)
       │
       ▼
StagedTransaction.originalData                   ✅ Persists counterpartyAccount in Mongo
       │
       ▼
StageTransactionsCommandHandler                  ❌ DOES NOT call profile.ownsAccount(counterpartyAccount)
.processTransaction()                                MISSING: priority-0 IBAN cross-reference
       │
       ▼
ImportHistoricalCashChangeCommand                ❌ Does not carry selfTransfer flag
       │
       ▼
CashChange (domain record)                       ❌ Has no `selfTransfer` field
       │
       ▼
HistoricalCashChangeImportedEvent                ❌ Event payload has no flag
       │
       ▼
PaidCashChangeAppendedEventHandler               ❌ Does not filter by selfTransfer
       │
       ▼
Forecast.categorizedOutFlows[Uncategorized]      ❌ 106 self-transfers inflate the budget
       │
       ▼
"Total spending: 1.21M PLN" inflated             ❌ Original 50.7% Inne problem persists
```

### Implementation status table (post-manual-test)

| Component | Status | Evidence |
|---|---|---|
| UserFinancialProfile registry | ✅ DONE | Both IBANs in DB with correct source/linkedCashFlowId |
| Auto-add at CashFlow creation | ✅ DONE | Kafka listener LINKED existing onboarding entries |
| Bulk onboarding endpoint | ✅ DONE | 2 accounts added atomically + 2 Kafka events |
| available-for-cashflow filter | ✅ DONE | Returns empty after both CashFlows linked |
| Kafka listener idempotency | ✅ DONE | Pre-existing ONBOARDING entry linked, not duplicated as CASHFLOW |
| Bulk validate-all-before-save | ✅ DONE | Both 409 (duplicate) and 400 (invalid IBAN) leave profile unchanged |
| 422 protection for linked accounts | ✅ DONE | Cannot delete Nest IBAN while linked to CF10000001 |
| **`CashChange.selfTransfer` flag** | **❌ TODO Phase 1b** | Field doesn't exist on the record |
| **Detection in `StageTransactionsCommandHandler`** | **❌ TODO Phase 1b** | No call to `profile.ownsAccount(...)` |
| **`selfTransfer` propagation through events** | **❌ TODO Phase 1b** | No event carries the flag |
| **Forecast exclusion of self-transfers** | **❌ TODO Phase 1b** | Handlers add all CashChanges to categorizedFlows |
| Auto-create "Przelewy własne" category | ❌ TODO Phase 1b | Not implemented |
| `SelfTransferSuggestionService` | ❌ TODO Phase 2 | Suggestions for IBANs outside profile |
| UI (onboarding wizard, picker, badge) | ❌ TODO Phase 3 | Only mockup exists |

### Verdict: NOT solved — Phase 1b required

Phase 1 (MVP foundation) and Phase 1c (account-first backend support) are complete and
verified, but **the registry isn't yet consulted during staging/import**. The actual
business problem — 50.7% "Inne" pollution and inflated budget — persists in the
real-data forecast we generated.

### Estimated impact of Phase 1b (based on actual data in CF10000001)

- 106 transactions in Nest CF Uncategorized have counterparty matching profile IBAN
- Average per month over 36 months ≈ 9–12k PLN of fake "expenses"
- Total budget correction ≈ **350–430k PLN** false outflows would be removed
- "Inne"/Uncategorized expected to drop from 50.7% to ~23% (matching original VID-159 prediction)

### What Phase 1b requires concretely (already in the TODO section above)

1. Add `boolean selfTransfer` to `CashChange`, `CashChangeSnapshot`, `CashChangeEntity`
2. Add the flag to relevant `CashFlowEvent` payloads (at minimum `HistoricalCashChangeImportedEvent`)
3. **Priority-0 check** in `StageTransactionsCommandHandler.processTransaction()`:
   inject `UserFinancialProfileService`, call `ownsAccount(userId, counterpartyAccount)`,
   short-circuit to `categoryName = "Przelewy własne"` + `selfTransfer = true`
4. Auto-create the "Przelewy własne" category (parent "Zarządzanie kontem") on first match
5. Propagate the flag through `ImportTransactionRequest` → `ImportHistoricalCashChangeCommandHandler`
6. Modify `PaidCashChangeAppendedEventHandler` / `ExpectedCashChangeAppendedEventHandler` /
   `CashChangeConfirmedEventHandler` / `CashChangeEditedEventHandler` to skip CashChanges
   where `selfTransfer == true` when populating `categorizedInFlows` / `categorizedOutFlows`
   (or place them in a separate informational section)
7. Re-run the manual flow with the same CSV; expect ~106 transactions in Nest CF to move
   from Uncategorized to "Przelewy własne" with `selfTransfer=true` and budget totals
   to drop by the corresponding amount.

## Related Documents

- [VID-158-TROUBLESHOOTING-ENRICHMENT-QUALITY.md](VID-158-TROUBLESHOOTING-ENRICHMENT-QUALITY.md) — Original self-transfer problem discovery
- [VID-159-THREE-SIGNAL-RESULTS-2026-04-26.md](VID-159-THREE-SIGNAL-RESULTS-2026-04-26.md) — Baseline metrics showing 50.7% "Inne" in Nest Bank
- [VID-157-TROUBLESHOOTING-BANK-CATEGORIES.md](VID-157-TROUBLESHOOTING-BANK-CATEGORIES.md) — Fix priorities including self-transfer
