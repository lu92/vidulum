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

## Implementation Status (2026-06-04)

### ✅ DONE — Phase 1 MVP (UserFinancialProfile foundation)

Implemented in `com.multi.vidulum.user_financial_profile`:

- **Domain**: `UserFinancialProfile` aggregate, `OwnedBankAccount` value object, `AccountStatus`/`AccountSource` enums, sealed `UserFinancialProfileEvent` hierarchy with 5 records (`Created`, `Added`, `Closed`, `Reactivated`, `Removed`), domain repository interface, 4 business exceptions.
- **Infrastructure**: `UserFinancialProfileEntity` (@Document `user_financial_profiles`) with nested `OwnedBankAccountDocument`, MongoDB repository + domain repo bridge, `UserFinancialProfileEventEmitter` publishing to Kafka topic `user_financial_profile`.
- **App**: `UserFinancialProfileService` (plain service, no CQRS), `UserFinancialProfileRestController` with `GET/POST/DELETE /api/v1/user/owned-accounts`, DTOs with `@Valid` request validation.
- **Wiring**:
  - `RegisterUserCommandHandler` → inline `createEmptyProfile()` so every newly-registered user has a non-null profile document immediately
  - `CreateCashFlowWithHistoryCommandHandler` → `onCashFlowCreated()` auto-adds the CashFlow's IBAN with `source=CASHFLOW`, `linkedCashFlowId` set
  - `KafkaTopicConfig` — new `NewTopic("user_financial_profile", 1, 1)` + producer/consumer/container factories
  - `ErrorCode` — 6 new codes (`OWNED_ACCOUNT_*`)
  - `ErrorHttpHandler` — 4 new `@ExceptionHandler` mapping business exceptions to proper HTTP status codes (400/404/409/422)
  - `VidulumApplication.clearData()` — drops the new collection on startup
- **Tests**: `UserFinancialProfileHttpIntegrationTest` (14 scenarios, T1–T9 positive + E1–E8 errors) passing, plus `UserFinancialProfileHttpActor` for HTTP encapsulation. Manual end-to-end HTTP testing also passed with DB + Kafka verification.

**Behavior currently working**:
- Empty profile auto-created at user registration
- IBAN auto-added at CashFlow creation (source=CASHFLOW)
- Manual add/list/delete via REST with full JWT auth
- Hard delete (no soft-delete preserved record)
- Account linked to active CashFlow cannot be deleted (422)
- Cross-user isolation (same IBAN allowed across different users)
- Kafka events emitted for every state transition

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

### ⏸ TODO — Phase 3: UX Integration

- "My Bank Accounts" settings page (list/add/remove, see `linkedCashFlowId`)
- Post-import suggestion dialog (show suggested IBANs with confidence, accept/skip)
- 🔄 self-transfer badge on transaction rows
- Budget view with self-transfers in separate section (excluded from main expense total)
- Forecast view annotated with "Real expenses" vs "Self-transfers" averages

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

## Related Documents

- [VID-158-TROUBLESHOOTING-ENRICHMENT-QUALITY.md](VID-158-TROUBLESHOOTING-ENRICHMENT-QUALITY.md) — Original self-transfer problem discovery
- [VID-159-THREE-SIGNAL-RESULTS-2026-04-26.md](VID-159-THREE-SIGNAL-RESULTS-2026-04-26.md) — Baseline metrics showing 50.7% "Inne" in Nest Bank
- [VID-157-TROUBLESHOOTING-BANK-CATEGORIES.md](VID-157-TROUBLESHOOTING-BANK-CATEGORIES.md) — Fix priorities including self-transfer
