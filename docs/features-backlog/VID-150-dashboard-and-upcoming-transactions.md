# VID-150: Dashboard & Upcoming Transactions API

## Priority: HIGH

This document analyzes the gap between UI mockups and backend implementation, focusing on Dashboard and Upcoming Transactions features that are critical for user experience.

---

## Mockups Analysis

The web mockups (`docs/design/recurring-rules-web-mockups-en.html`) contain 14 screens:

| # | Screen | Backend Status | Notes |
|---|--------|----------------|-------|
| 1 | **Rules List** | ✅ DONE | `GET /api/v1/recurring-rules/me` |
| 2 | **Empty State** | ✅ DONE | Frontend handles empty list |
| 3 | **Create Rule (Monthly)** | ✅ DONE | `POST /api/v1/recurring-rules` |
| 4 | **Create Quarterly** | ✅ DONE | Pattern QUARTERLY implemented |
| 5 | **Create One-time** | ✅ DONE | Pattern ONCE implemented |
| 6 | **Create Every N Days** | ✅ DONE | Pattern EVERY_N_DAYS implemented |
| 7 | **Edit (Amount Change)** | ✅ DONE | `POST /{ruleId}/amount-changes` |
| 8 | **Rule Details** | ⚠️ PARTIAL | Missing `executionHistory`, `statistics` |
| 9 | **Scheduled Change** | ❌ MISSING | Missing `effectiveDate` in AmountChange |
| 10 | **Mismatch Resolution** | ❌ MISSING | Requires reconciliation flow |
| 11 | **Dashboard** | ❌ MISSING | No dedicated endpoint |
| 12 | **AI Suggestions** | ❌ MISSING | Requires AI integration |
| 13 | **Amount History** | ⚠️ PARTIAL | List exists, missing `createdAt` |
| 14 | **Delete Confirmation** | ✅ DONE | `GET /{ruleId}/impact-preview` |

---

## 1. Dashboard Endpoint (HIGH PRIORITY)

### Mockup Design (Screen 11)

```
┌─────────────────────────────────────────────────────────────────────┐
│                     RECURRING RULES DASHBOARD                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────┐ │
│  │ Active Rules │  │   Monthly    │  │   Monthly    │  │   Net    │ │
│  │      8       │  │  Expenses    │  │   Income     │  │ Balance  │ │
│  │              │  │  -$4,250     │  │  +$8,500     │  │ +$4,250  │ │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────┘ │
│                                                                      │
├─────────────────────────────────────────────────────────────────────┤
│  NEEDS ATTENTION                                                     │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │ ⚠️  1 mismatch to resolve                          [Resolve]   ││
│  │     Netflix: $22.99 (expected $19.99)                          ││
│  └─────────────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │ 💡 3 suggested rules                                [View]     ││
│  │     New patterns detected in your transactions                 ││
│  └─────────────────────────────────────────────────────────────────┘│
│                                                                      │
├─────────────────────────────────────────────────────────────────────┤
│  UPCOMING TRANSACTIONS (7 days)                                      │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │ Feb 28  💰 Salary                                    +$8,500   ││
│  │ Mar 1   🏋️ Gym Membership                            -$50      ││
│  │ Mar 5   👶 Daycare                                   -$800     ││
│  └─────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────┘
```

### Proposed API

```
GET /api/v1/recurring-rules/dashboard
Authorization: Bearer {token}
```

### Response DTO

```java
public record DashboardResponse(
    // Summary statistics
    int activeRulesCount,
    int pausedRulesCount,
    int completedRulesCount,

    // Monthly projections
    MoneyJson monthlyExpenses,
    MoneyJson monthlyIncome,
    MoneyJson netBalance,

    // Needs attention section
    NeedsAttention needsAttention,

    // Upcoming transactions (next 7 days by default)
    List<UpcomingTransaction> upcomingTransactions
) {
    public record NeedsAttention(
        int mismatchCount,
        List<MismatchSummary> mismatches,
        int suggestedRulesCount
        // suggestedRules will be added later with AI integration
    ) {}

    public record MismatchSummary(
        String ruleId,
        String ruleName,
        MoneyJson expected,
        MoneyJson actual,
        LocalDate date
    ) {}

    public record UpcomingTransaction(
        String ruleId,
        String ruleName,
        String cashChangeId,
        LocalDate dueDate,
        MoneyJson amount,
        String type,  // INFLOW or OUTFLOW
        String category,
        String status  // PENDING, CONFIRMED
    ) {}
}
```

### Implementation Notes

1. **Data sources:**
   - Rules count: `ruleRepository.findByUserId(userId)`
   - Monthly totals: Aggregate from active rules with MONTHLY pattern
   - Upcoming: Query CashFlow for PENDING CashChanges linked to rules

2. **Complexity:** LOW - aggregation of existing data
3. **Estimated time:** 2-3 hours

---

## 2. Upcoming Transactions Endpoint (HIGH PRIORITY)

### Proposed API

```
GET /api/v1/recurring-rules/upcoming?days=7
Authorization: Bearer {token}
```

### Query Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `days` | int | 7 | Number of days to look ahead |
| `limit` | int | 20 | Max transactions to return |

### Response DTO

```java
public record UpcomingTransactionsResponse(
    List<UpcomingTransaction> transactions,
    MoneyJson totalInflow,
    MoneyJson totalOutflow,
    MoneyJson netChange
) {
    public record UpcomingTransaction(
        String ruleId,
        String ruleName,
        String cashChangeId,
        LocalDate dueDate,
        MoneyJson amount,
        String type,
        String category,
        String status,
        int daysUntilDue
    ) {}
}
```

### Implementation Notes

1. **Query strategy:**
   - Get all active rules for user
   - Get `generatedCashChangeIds` from each rule
   - Query CashFlow for those CashChanges where `dueDate` is within range
   - Or: New query `findPendingCashChangesBySourceRuleIds(List<RuleId>, dateRange)`

2. **Complexity:** LOW-MEDIUM
3. **Estimated time:** 1-2 hours

---

## 3. Scheduled Amount Change (MEDIUM PRIORITY)

### Current vs Required

**Current AmountChange:**
```java
public record AmountChange(
    AmountChangeId id,
    Money amount,
    AmountChangeType type,  // PERMANENT, ONE_TIME
    String reason
)
```

**Required AmountChange:**
```java
public record AmountChange(
    AmountChangeId id,
    Money amount,
    AmountChangeType type,
    String reason,
    LocalDate effectiveDate,  // NEW - when change takes effect
    Instant createdAt         // NEW - audit trail
)
```

### API Changes

```
POST /api/v1/recurring-rules/{ruleId}/amount-changes
{
    "amount": {"amount": 2200.00, "currency": "PLN"},
    "type": "PERMANENT",
    "reason": "Rent increase per landlord notice",
    "effectiveDate": "2027-01-01"  // NEW - optional, defaults to now
}
```

### Implementation Notes

1. **Domain changes:**
   - Add `effectiveDate` and `createdAt` to `AmountChange` record
   - Update `calculateEffectiveAmount(LocalDate forDate)` to consider effectiveDate
   - Update MongoDB entity mapping

2. **Backward compatibility:**
   - Existing AmountChanges without effectiveDate = immediate effect
   - Migration: set effectiveDate = createdAt for existing records

3. **Complexity:** MEDIUM
4. **Estimated time:** 3-4 hours

---

## 4. Execution History (MEDIUM PRIORITY)

**Covered in:** `VID-149-execution-history-analysis.md`

Key points:
- `RuleExecution` record exists but is never used
- `recordExecution()` method exists but is never called
- `CashChangeConfirmedEvent` missing `sourceRuleId`
- Need Kafka listener to track confirmations

---

## 5. Mismatch Resolution (LOW PRIORITY)

### Concept

When a CashChange is confirmed with different amount than expected:
- Detect mismatch (expected vs actual)
- Present resolution options:
  1. Update rule to new amount (all future)
  2. Accept this transaction only (one-time exception)
  3. Schedule change from this date

### Implementation Requirements

1. **Detection:**
   - On `confirmCashChange`, compare `confirmedAmount` with `expectedAmount`
   - If difference > threshold (e.g., 1%), create `Mismatch` entity

2. **Storage:**
   - New collection: `mismatches`
   - Fields: ruleId, cashChangeId, expectedAmount, actualAmount, status, resolution

3. **Resolution API:**
   ```
   GET  /api/v1/recurring-rules/mismatches
   POST /api/v1/recurring-rules/mismatches/{id}/resolve
   ```

4. **Complexity:** HIGH
5. **Estimated time:** 1-2 days

---

## 6. AI Suggestions (LOW PRIORITY)

### Concept

Analyze transaction history to detect patterns and suggest new rules.

### Requirements

1. **Pattern detection:**
   - Find recurring merchants (same name, similar amounts)
   - Detect frequency (monthly, weekly, etc.)
   - Calculate confidence score

2. **Storage:**
   - New collection: `suggested_rules`
   - User can accept (create rule) or dismiss

3. **Complexity:** HIGH
4. **Estimated time:** 3-5 days

---

## Implementation Priority

| Priority | Task | Complexity | Time | Depends On |
|----------|------|------------|------|------------|
| 🔴 **1** | Dashboard endpoint | LOW | 2-3h | - |
| 🔴 **2** | Upcoming transactions | LOW | 1-2h | - |
| 🟡 **3** | effectiveDate in AmountChange | MEDIUM | 3-4h | - |
| 🟡 **4** | Execution History (VID-149) | MEDIUM | 3-4h | CashChangeConfirmedEvent change |
| 🟢 **5** | Mismatch Resolution | HIGH | 1-2d | VID-149 |
| 🟢 **6** | AI Suggestions | HIGH | 3-5d | Transaction history analysis |

---

## Recommended Next Steps

### Day 1: Dashboard + Upcoming

1. Create `DashboardResponse` DTO
2. Create `UpcomingTransactionsResponse` DTO
3. Add `getDashboard()` method to `RecurringRuleService`
4. Add `getUpcomingTransactions()` method
5. Add endpoints to `RecurringRulesController`
6. Write integration tests

### Day 2: effectiveDate in AmountChange

1. Update `AmountChange` record with new fields
2. Update `AmountChangeEmbedded` entity
3. Update `calculateEffectiveAmount()` logic
4. Update API request/response DTOs
5. Add migration for existing data
6. Write tests

---

## Files to Create/Modify

### New Files
- `src/main/java/com/multi/vidulum/recurring_rules/app/dto/DashboardResponse.java`
- `src/main/java/com/multi/vidulum/recurring_rules/app/dto/UpcomingTransactionsResponse.java`
- `src/main/java/com/multi/vidulum/recurring_rules/app/queries/GetDashboardQuery.java`
- `src/main/java/com/multi/vidulum/recurring_rules/app/queries/GetUpcomingTransactionsQuery.java`

### Modify
- `RecurringRulesController.java` - add new endpoints
- `RecurringRuleService.java` - add query handlers
- `AmountChange.java` - add effectiveDate, createdAt
- `AmountChangeEmbedded.java` - add new fields
- `RecurringRule.java` - update calculateEffectiveAmount()
- `AddAmountChangeRequest.java` - add effectiveDate field

---

## Acceptance Criteria

### Dashboard
- [ ] `GET /api/v1/recurring-rules/dashboard` returns summary stats
- [ ] Active/paused/completed rule counts are accurate
- [ ] Monthly expense/income projections calculated correctly
- [ ] Upcoming transactions for next 7 days included
- [ ] Empty state handled gracefully

### Upcoming Transactions
- [ ] `GET /api/v1/recurring-rules/upcoming?days=N` works
- [ ] Transactions sorted by dueDate ascending
- [ ] Total inflow/outflow/net calculated
- [ ] Status (PENDING/CONFIRMED) included
- [ ] `daysUntilDue` calculated correctly

### Scheduled Amount Change
- [ ] `effectiveDate` can be set when adding amount change
- [ ] Changes with future effectiveDate don't affect current calculations
- [ ] Changes become active on effectiveDate
- [ ] `createdAt` tracked for audit
- [ ] Backward compatible with existing data
- [ ] Impact preview endpoint works correctly
- [ ] Partial regeneration (only affected CashChanges)

---

## Appendix A: Scheduled Amount Change - Detailed Business Flow

### Real-World Scenario: Rent Increase

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                           USER JOURNEY: SCHEDULED AMOUNT CHANGE                              │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

TIMELINE:
═════════════════════════════════════════════════════════════════════════════════════════════

  Nov 15, 2026          Dec 2026           Jan 1, 2027         Jan 10, 2027
       │                   │                    │                    │
       ▼                   ▼                    ▼                    ▼
  ┌─────────┐         ┌─────────┐          ┌─────────┐          ┌─────────┐
  │ Landlord│         │ System  │          │ Change  │          │ Payment │
  │ sends   │         │ shows   │          │ becomes │          │ due     │
  │ notice  │         │ both    │          │ active  │          │ 2200 PLN│
  │ +200 PLN│         │ amounts │          │         │          │         │
  └─────────┘         └─────────┘          └─────────┘          └─────────┘
       │                   │                    │                    │
       ▼                   ▼                    ▼                    ▼
  User adds           Forecast shows:      System auto-        User pays
  scheduled           Nov: 2000 PLN        applies change      new amount
  change              Dec: 2000 PLN        to all future
  effective           Jan: 2200 PLN ←      transactions
  2027-01-01          Feb: 2200 PLN
```

### Step-by-Step Flow

#### Step 1: User receives notice (Nov 15)
```
User gets letter from landlord:
"Starting January 1, 2027, rent increases from 2000 PLN to 2200 PLN"
```

#### Step 2: User schedules change in app
```
POST /api/v1/recurring-rules/{ruleId}/amount-changes
{
    "amount": {"amount": 2200.00, "currency": "PLN"},
    "type": "PERMANENT",
    "reason": "Rent increase per landlord notice dated Nov 15, 2026",
    "effectiveDate": "2027-01-01"
}
```

#### Step 3: System state after adding scheduled change
```
RecurringRule state:
{
  "ruleId": "RR001",
  "name": "Rent",
  "baseAmount": 2000 PLN,           // ← original amount
  "amountChanges": [
    {
      "id": "AC001",
      "amount": 2200 PLN,
      "type": "PERMANENT",
      "effectiveDate": "2027-01-01", // ← future date
      "createdAt": "2026-11-15T10:00:00Z",
      "reason": "Rent increase..."
    }
  ]
}
```

### Key Question: When to Regenerate CashChanges?

#### Option A: Eager Regeneration (Recommended)
```
User adds scheduled change
         │
         ▼
┌─────────────────────────────────┐
│ clearGeneratedCashChangesFrom() │  ← delete only affected
│ generateExpectedCashChangesFrom()│  ← generate with new amount
└─────────────────────────────────┘
         │
         ▼
CashChanges after regeneration:
  CC_2026_11 (2000 PLN) ← unchanged
  CC_2026_12 (2000 PLN) ← unchanged
  CC_2027_01 (2200 PLN) ← NEW amount
  CC_2027_02 (2200 PLN) ← NEW amount
```

**Pros:** Forecast immediately shows future change
**Cons:** More HTTP operations when adding change

#### Option B: Lazy Regeneration
```
User adds scheduled change
         │
         ▼
┌─────────────────────────────────┐
│ Only save AmountChange in rule  │
│ (no regeneration)               │
└─────────────────────────────────┘
         │
         ▼
CashChanges remain:
  CC_2027_01 (2000 PLN) ← old amount (will be mismatch!)

Nightly scheduler or GET /dashboard:
  - Detects scheduled changes
  - Regenerates CashChanges
```

**Pros:** Faster adding of change
**Cons:** Temporary inconsistency, requires scheduler

### Recommendation: Option A with Smart Regeneration

```java
public void addAmountChange(AmountChange change, String authToken) {
    rule.addAmountChange(change, clock);
    ruleRepository.save(rule);

    if (rule.isActive()) {
        // Regenerate only CashChanges from effectiveDate
        LocalDate regenerateFrom = change.effectiveDate() != null
            ? change.effectiveDate()
            : LocalDate.now(clock);

        clearGeneratedCashChangesFrom(rule, regenerateFrom, authToken);
        generateExpectedCashChangesFrom(rule, regenerateFrom, authToken);
    }
}
```

### Impact Preview UI (Mockup Screen 9)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    SCHEDULE AMOUNT CHANGE                            │
├─────────────────────────────────────────────────────────────────────┤
│  Rule: Rent                                                          │
│  Current amount: 2,000 PLN                                           │
│                                                                      │
│  New Amount:     [2,200.00] PLN                                      │
│  Effective From: [2027-01-01]                                        │
│  Reason:         [Rent increase per landlord notice___________]     │
│                                                                      │
├─────────────────────────────────────────────────────────────────────┤
│  📊 IMPACT PREVIEW                                                   │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │ Transactions affected: 12 (Jan-Dec 2027)                        ││
│  │                                                                  ││
│  │ Amount change: 2,000 PLN → 2,200 PLN (+200 / +10%)              ││
│  │                                                                  ││
│  │ Monthly impact:  +200 PLN                                        ││
│  │ Annual impact:   +2,400 PLN                                      ││
│  │                                                                  ││
│  │ ┌────────────────────────────────────────────────────────────┐  ││
│  │ │ Before (2026)          │ After (2027)                      │  ││
│  │ │ Nov: 2,000 PLN         │ Jan: 2,200 PLN ← NEW             │  ││
│  │ │ Dec: 2,000 PLN         │ Feb: 2,200 PLN                   │  ││
│  │ │                        │ Mar: 2,200 PLN                   │  ││
│  │ └────────────────────────────────────────────────────────────┘  ││
│  └─────────────────────────────────────────────────────────────────┘│
│                                                                      │
│                              [Cancel]  [Schedule Change]             │
└─────────────────────────────────────────────────────────────────────┘
```

### Impact Preview API

```
GET /api/v1/recurring-rules/{ruleId}/amount-change-preview
    ?newAmount=2200.00
    &effectiveDate=2027-01-01

Response:
{
  "currentAmount": {"amount": 2000.00, "currency": "PLN"},
  "newAmount": {"amount": 2200.00, "currency": "PLN"},
  "effectiveDate": "2027-01-01",
  "change": {
    "absolute": {"amount": 200.00, "currency": "PLN"},
    "percentage": 10.0
  },
  "impact": {
    "transactionsAffected": 12,
    "firstAffectedDate": "2027-01-10",
    "lastAffectedDate": "2027-12-10",
    "monthlyImpact": {"amount": 200.00, "currency": "PLN"},
    "annualImpact": {"amount": 2400.00, "currency": "PLN"}
  },
  "preview": [
    {"date": "2026-11-10", "amount": 2000.00, "affected": false},
    {"date": "2026-12-10", "amount": 2000.00, "affected": false},
    {"date": "2027-01-10", "amount": 2200.00, "affected": true},
    {"date": "2027-02-10", "amount": 2200.00, "affected": true}
  ]
}
```

### Implementation Complexity

| Component | Time | Notes |
|-----------|------|-------|
| effectiveDate in AmountChange | 1h | Domain + entity |
| calculateEffectiveAmount() update | 30min | Logic |
| Partial regeneration (clearFrom/generateFrom) | 1.5h | New methods |
| Impact preview endpoint | 1h | New endpoint |
| Tests | 1.5h | Unit + integration |
| **TOTAL** | **5-6h** | |

### Files to Create/Modify

**New:**
- `AmountChangePreviewResponse.java` - DTO for impact preview

**Modify:**
- `AmountChange.java` - add effectiveDate, createdAt
- `AmountChangeEmbedded.java` - add new fields
- `AddAmountChangeRequest.java` - add effectiveDate field
- `RecurringRule.java` - update calculateEffectiveAmount()
- `RecurringRuleService.java` - add clearFrom/generateFrom methods
- `RecurringRulesController.java` - add preview endpoint

---

## Appendix B: Multiple Amount Changes Timeline

### Complex Scenario: Multiple scheduled changes

```
User has rule: "Subscription" - 50 PLN/month

Timeline of changes:
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                              │
│  Jan 2027        Mar 2027         Jun 2027         Sep 2027         Dec 2027               │
│     │               │                │                │                │                    │
│  50 PLN         60 PLN           60 PLN           75 PLN           75 PLN                  │
│     │               │                │                │                │                    │
│     └───────────────┼────────────────┼────────────────┼────────────────┘                    │
│                     │                │                │                                      │
│              AC001: +10 PLN    (no change)     AC002: +15 PLN                               │
│              effective Mar 1    stays 60       effective Sep 1                              │
│                                                                                              │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

AmountChanges list:
[
  {id: "AC001", amount: 60 PLN, type: PERMANENT, effectiveDate: "2027-03-01"},
  {id: "AC002", amount: 75 PLN, type: PERMANENT, effectiveDate: "2027-09-01"}
]

calculateEffectiveAmount(forDate) logic:
  forDate = 2027-02-15 → returns 50 PLN (base, no change active yet)
  forDate = 2027-03-01 → returns 60 PLN (AC001 active)
  forDate = 2027-06-15 → returns 60 PLN (AC001 still active)
  forDate = 2027-09-01 → returns 75 PLN (AC002 active, overrides AC001)
  forDate = 2027-12-15 → returns 75 PLN (AC002 still active)
```

### Algorithm for calculateEffectiveAmount()

```java
public Money calculateEffectiveAmount(LocalDate forDate) {
    Money effective = baseAmount;

    // Sort changes by effectiveDate ascending
    List<AmountChange> sortedChanges = amountChanges.stream()
        .filter(c -> c.type() == AmountChangeType.PERMANENT)
        .filter(c -> c.effectiveDate() == null || !forDate.isBefore(c.effectiveDate()))
        .sorted(Comparator.comparing(
            c -> c.effectiveDate() != null ? c.effectiveDate() : LocalDate.MIN
        ))
        .toList();

    // Last applicable change wins
    if (!sortedChanges.isEmpty()) {
        effective = sortedChanges.getLast().amount();
    }

    return effective;
}
```

---

## Appendix C: Amount Change History View

### UI for viewing all amount changes

```
┌─────────────────────────────────────────────────────────────────────┐
│                    AMOUNT CHANGE HISTORY                             │
│  Rule: Netflix Subscription                                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  📊 Current effective amount: 75 PLN                                 │
│  📅 Base amount (original): 50 PLN                                   │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │ CHANGE TIMELINE                                                  ││
│  │                                                                  ││
│  │  ●─────────────────●─────────────────●                          ││
│  │  │                 │                 │                          ││
│  │  Jan 2027         Mar 2027          Sep 2027                    ││
│  │  50 PLN           60 PLN            75 PLN                      ││
│  │  (base)           (+20%)            (+25%)                      ││
│  │                                                                  ││
│  └─────────────────────────────────────────────────────────────────┘│
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │ #  │ Date Added  │ Effective From │ Amount  │ Change │ Reason  ││
│  │────┼─────────────┼────────────────┼─────────┼────────┼─────────││
│  │ 1  │ 2027-02-15  │ 2027-03-01     │ 60 PLN  │ +10    │ Price   ││
│  │    │             │                │         │ (+20%) │ increase││
│  │────┼─────────────┼────────────────┼─────────┼────────┼─────────││
│  │ 2  │ 2027-08-20  │ 2027-09-01     │ 75 PLN  │ +15    │ Premium ││
│  │    │             │ ✓ ACTIVE       │         │ (+25%) │ tier    ││
│  └─────────────────────────────────────────────────────────────────┘│
│                                                                      │
│  [+ Add New Change]                                                  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### API for Amount Change History

```
GET /api/v1/recurring-rules/{ruleId}/amount-changes

Response:
{
  "ruleId": "RR001",
  "ruleName": "Netflix Subscription",
  "baseAmount": {"amount": 50.00, "currency": "PLN"},
  "currentEffectiveAmount": {"amount": 75.00, "currency": "PLN"},
  "changes": [
    {
      "id": "AC001",
      "amount": {"amount": 60.00, "currency": "PLN"},
      "type": "PERMANENT",
      "effectiveDate": "2027-03-01",
      "createdAt": "2027-02-15T10:00:00Z",
      "reason": "Price increase",
      "change": {
        "fromAmount": {"amount": 50.00, "currency": "PLN"},
        "absolute": {"amount": 10.00, "currency": "PLN"},
        "percentage": 20.0
      },
      "status": "SUPERSEDED"  // replaced by AC002
    },
    {
      "id": "AC002",
      "amount": {"amount": 75.00, "currency": "PLN"},
      "type": "PERMANENT",
      "effectiveDate": "2027-09-01",
      "createdAt": "2027-08-20T14:30:00Z",
      "reason": "Premium tier upgrade",
      "change": {
        "fromAmount": {"amount": 60.00, "currency": "PLN"},
        "absolute": {"amount": 15.00, "currency": "PLN"},
        "percentage": 25.0
      },
      "status": "ACTIVE"
    }
  ]
}
```
