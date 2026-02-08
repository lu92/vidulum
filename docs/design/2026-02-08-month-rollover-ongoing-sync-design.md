# Month Rollover & Ongoing Sync - Design Document

**Data utworzenia:** 2026-02-08
**Status:** Do implementacji
**Autor:** Claude Code + User

---

## Spis treści

1. [Podsumowanie](#1-podsumowanie)
2. [Pojęcia biznesowe](#2-pojęcia-biznesowe)
3. [Zmiany w domenie](#3-zmiany-w-domenie)
4. [Nowe komponenty](#4-nowe-komponenty)
5. [Zmiany w walidacji](#5-zmiany-w-walidacji)
6. [Weryfikacja salda](#6-weryfikacja-salda)
7. [Gap Filling & Recalculation](#7-gap-filling--recalculation)
8. [REST API - zmiany](#8-rest-api---zmiany)
9. [Przykład pełnego flow](#9-przykład-pełnego-flow)
10. [Plan implementacji (PR-y)](#10-plan-implementacji-pr-y)
11. [Wpływ na testy](#11-wpływ-na-testy)
12. [Decyzje projektowe](#12-decyzje-projektowe)
13. [Usunięcie Month Attestation (deprecation)](#13-usunięcie-month-attestation-deprecation)

---

## 1. Podsumowanie

### Cel zmian

Obecnie system pozwala tylko na jednorazowy import historycznych danych CSV podczas trybu SETUP. Po aktywacji CashFlow (przejście do OPEN) nie ma możliwości wgrywania kolejnych plików CSV.

**Nowe możliwości:**
- Import CSV po aktywacji CashFlow (Ongoing Sync)
- Automatyczne przejście miesiąca bez manualnej atestacji (Month Rollover)
- Uzupełnianie brakujących transakcji w przeszłych miesiącach (Gap Filling)
- Weryfikacja salda raz na miesiąc (przy pierwszym imporcie)

### Główne zmiany

| Obszar | Obecny stan | Nowy stan |
|--------|-------------|-----------|
| Import CSV | Tylko w SETUP mode | SETUP (Historical Backfill) + OPEN (Ongoing Sync) |
| Przejście miesiąca | Manualna atestacja (`MakeMonthlyAttestationCommand`) | Automatyczny rollover (scheduled job) |
| Status zamkniętego miesiąca | `ATTESTED` (manualny) | `ROLLED_OVER` (automatyczny) lub `ATTESTED` (manualny) |
| Import do przeszłych miesięcy | Niemożliwy | Możliwy (Gap Filling) |
| Weryfikacja salda | Przy każdej atestacji | Raz na miesiąc (pierwszy import) |

---

## 2. Pojęcia biznesowe

### Dwa tryby wgrywania danych

| Tryb | Angielska nazwa | Polski opis | Kiedy używany |
|------|-----------------|-------------|---------------|
| **Import historyczny** | **Historical Backfill** | Wgrywanie danych za przeszłe miesiące | Podczas SETUP mode, przed aktywacją CashFlow |
| **Bieżące uzupełnianie** | **Ongoing Sync** | Wgrywanie nowych transakcji na bieżąco | Po aktywacji CashFlow (OPEN mode) |

### Dokumentacja w kodzie

```java
/**
 * Bank Data Ingestion supports two modes:
 *
 * 1. HISTORICAL BACKFILL (SETUP mode):
 *    - Initial import of past transactions when setting up CashFlow
 *    - Transactions from startPeriod to activePeriod-1
 *    - Requires attestation with balance verification to transition to OPEN mode
 *
 * 2. ONGOING SYNC (OPEN mode):
 *    - Regular import of new transactions after CashFlow activation
 *    - Supports importing to current (ACTIVE) and past (ROLLED_OVER) months
 *    - Balance verification required once per month (first import after rollover)
 *    - Duplicates are detected and skipped automatically
 *    - Gap filling: importing missed transactions to closed months
 */
```

---

## 3. Zmiany w domenie

### 3.1 Nowy status miesiąca: `ROLLED_OVER`

**Plik:** `CashFlowMonthlyForecast.java`

```java
public enum Status {
    IMPORT_PENDING,  // Historical month waiting for backfill
    IMPORTED,        // Historical month with finalized data (after attestHistoricalImport)
    ATTESTED,        // Manually attested with balance confirmation
    ROLLED_OVER,     // NEW: Auto-closed by scheduled job, allows gap filling
    ACTIVE,          // Current month
    FORECASTED       // Future month
}
```

**Różnice między ATTESTED a ROLLED_OVER:**

| Aspekt | ATTESTED | ROLLED_OVER |
|--------|----------|-------------|
| Trigger | Manualna atestacja | Scheduled job |
| Balance confirmation | Wymagane | Automatyczne (z systemu) |
| Gap filling | Nie dozwolone | Dozwolone |
| Użycie | Historical Backfill | Month Rollover |

### 3.2 Nowy event: `MonthRolledOverEvent`

**Plik:** `CashFlowEvent.java`

```java
/**
 * Event emitted when a month is automatically rolled over by the scheduled job.
 * This happens at the beginning of each calendar month for all OPEN CashFlows.
 *
 * Unlike MonthAttestedEvent (manual), this is triggered automatically.
 * The rolled over month allows gap filling (importing missed transactions).
 *
 * @param cashFlowId        the CashFlow being rolled over
 * @param rolledOverPeriod  the month that is being closed (e.g., 2026-01)
 * @param newActivePeriod   the new active month (e.g., 2026-02)
 * @param closingBalance    the calculated balance at end of rolled over month
 * @param rolledOverAt      timestamp when rollover occurred
 */
record MonthRolledOverEvent(
    CashFlowId cashFlowId,
    YearMonth rolledOverPeriod,
    YearMonth newActivePeriod,
    Money closingBalance,
    ZonedDateTime rolledOverAt
) implements CashFlowEvent {
    @Override
    public ZonedDateTime occurredAt() {
        return rolledOverAt;
    }
}
```

### 3.3 Statusy CashFlow (bez zmian)

```java
public enum CashFlowStatus {
    SETUP,   // Historical Backfill mode - only historical imports allowed
    OPEN,    // Active CashFlow - Ongoing Sync allowed
    CLOSED   // Archived - read only
}
```

---

## 4. Nowe komponenty

### 4.1 RolloverMonthCommand

**Plik:** `src/main/java/com/multi/vidulum/cashflow/app/commands/rollover/RolloverMonthCommand.java`

```java
public record RolloverMonthCommand(
    CashFlowId cashFlowId,
    ZonedDateTime triggeredAt
) {}
```

### 4.2 RolloverMonthCommandHandler

**Plik:** `src/main/java/com/multi/vidulum/cashflow/app/commands/rollover/RolloverMonthCommandHandler.java`

**Logika:**
1. Załaduj CashFlow
2. Sprawdź czy jest w OPEN mode
3. Sprawdź czy activePeriod < bieżący miesiąc kalendarzowy
4. Oblicz closingBalance dla zamykanego miesiąca
5. Emit `MonthRolledOverEvent`
6. Zapisz CashFlow

### 4.3 MonthRolledOverEventHandler

**Plik:** `src/main/java/com/multi/vidulum/cashflow_forecast_processor/app/processing/MonthRolledOverEventHandler.java`

**Logika (analogiczna do MonthAttestedEventHandler):**
1. Zmień status ACTIVE miesiąca na `ROLLED_OVER`
2. Zmień status następnego FORECASTED na `ACTIVE`
3. Przenieś nieopłacone (EXPECTED) transakcje do nowego ACTIVE
4. Dodaj nowy FORECASTED miesiąc na koniec (utrzymanie 12-miesięcznego horyzontu)
5. Przelicz balansy (`statement.updateStats()`)
6. Zapisz statement

### 4.4 MonthlyRolloverScheduler

**Plik:** `src/main/java/com/multi/vidulum/cashflow/infrastructure/MonthlyRolloverScheduler.java`

```java
@Component
@Slf4j
@AllArgsConstructor
public class MonthlyRolloverScheduler {

    private final DomainCashFlowRepository repository;
    private final CommandGateway commandGateway;
    private final Clock clock;

    /**
     * Runs on the 1st day of each month at 02:00 UTC.
     * Triggers rollover for all OPEN CashFlows where activePeriod < current month.
     */
    @Scheduled(cron = "${vidulum.rollover.cron:0 0 2 1 * ?}")
    public void triggerMonthlyRollover() {
        YearMonth currentMonth = YearMonth.now(clock);
        ZonedDateTime now = ZonedDateTime.now(clock);

        log.info("Starting monthly rollover job for period [{}]", currentMonth);

        List<CashFlow> cashFlowsToRollover = repository.findOpenCashFlowsNeedingRollover(currentMonth);

        log.info("Found [{}] CashFlows requiring rollover", cashFlowsToRollover.size());

        for (CashFlow cashFlow : cashFlowsToRollover) {
            try {
                commandGateway.send(new RolloverMonthCommand(cashFlow.getCashFlowId(), now));
                log.info("Rollover triggered for CashFlow [{}]", cashFlow.getCashFlowId().id());
            } catch (Exception e) {
                log.error("Failed to rollover CashFlow [{}]: {}", cashFlow.getCashFlowId().id(), e.getMessage());
            }
        }

        log.info("Monthly rollover job completed");
    }
}
```

**Konfiguracja:**
```properties
# application.properties
vidulum.rollover.cron=0 0 2 1 * ?
vidulum.rollover.enabled=true
```

---

## 5. Zmiany w walidacji

### 5.1 Obecna walidacja (StageTransactionsCommandHandler)

```java
// Linie 190-208 - BLOKUJĄCE dla Ongoing Sync
if (!cashFlowInfo.isInSetupMode()) {
    errors.add("CashFlow is not in SETUP mode");
}

if (!paidPeriod.isBefore(activePeriod)) {
    errors.add("paidDate is not before activePeriod");
}
```

### 5.2 Nowa walidacja

```java
private TransactionValidation validateTransaction(
        StageTransactionsCommand.BankTransaction txn,
        CashFlowInfo cashFlowInfo,
        Set<String> existingBankTransactionIds,
        ZonedDateTime now) {

    List<String> errors = new ArrayList<>();

    // Duplicate check (unchanged)
    if (existingBankTransactionIds.contains(txn.bankTransactionId())) {
        return TransactionValidation.duplicate(txn.bankTransactionId());
    }

    YearMonth paidPeriod = YearMonth.from(txn.paidDate());
    YearMonth activePeriod = cashFlowInfo.activePeriod();
    YearMonth startPeriod = cashFlowInfo.startPeriod();

    if (cashFlowInfo.isInSetupMode()) {
        // === HISTORICAL BACKFILL ===
        // Only transactions before activePeriod allowed
        if (!paidPeriod.isBefore(activePeriod)) {
            errors.add(String.format(
                "Historical Backfill: paidDate %s must be before activePeriod %s",
                paidPeriod, activePeriod));
        }
    } else if (cashFlowInfo.isInOpenMode()) {
        // === ONGOING SYNC ===
        // Current month (ACTIVE) and past months (ROLLED_OVER, IMPORTED) allowed
        // Future months (FORECASTED) NOT allowed
        if (paidPeriod.isAfter(activePeriod)) {
            errors.add(String.format(
                "Ongoing Sync: cannot import future transactions. paidDate %s is after activePeriod %s",
                paidPeriod, activePeriod));
        }
    } else {
        // CLOSED CashFlow
        errors.add("CashFlow is CLOSED - no imports allowed");
    }

    // Common validation: not before startPeriod
    if (paidPeriod.isBefore(startPeriod)) {
        errors.add(String.format("paidDate %s is before startPeriod %s",
                paidPeriod, startPeriod));
    }

    // Common validation: not in future
    if (txn.paidDate().isAfter(now)) {
        errors.add("paidDate cannot be in the future");
    }

    if (!errors.isEmpty()) {
        return TransactionValidation.invalid(errors);
    }

    return TransactionValidation.valid();
}
```

### 5.3 Zmiany w CashFlowInfo

```java
public record CashFlowInfo(
    CashFlowId cashFlowId,
    CashFlow.CashFlowStatus status,
    YearMonth startPeriod,
    YearMonth activePeriod,
    Set<String> existingTransactionIds,
    Set<String> allCategoryNames,
    boolean balanceVerifiedThisMonth  // NEW: czy saldo było już weryfikowane w tym miesiącu
) {
    public boolean isInSetupMode() {
        return status == CashFlow.CashFlowStatus.SETUP;
    }

    public boolean isInOpenMode() {
        return status == CashFlow.CashFlowStatus.OPEN;
    }

    public boolean isInClosedMode() {
        return status == CashFlow.CashFlowStatus.CLOSED;
    }
}
```

---

## 6. Weryfikacja salda

### 6.1 Zasada: raz na miesiąc

- **Pierwszy import po rollover:** Weryfikacja salda WYMAGANA
- **Kolejne importy w tym samym miesiącu:** Weryfikacja OPCJONALNA

### 6.2 Nowe pole w CashFlowMonthlyForecast

```java
@Data
public class CashFlowMonthlyForecast {
    // ... existing fields ...

    /**
     * Timestamp when balance was verified for this month.
     * Set during first Ongoing Sync import after rollover.
     * null = balance not yet verified this month.
     */
    private ZonedDateTime balanceVerifiedAt;

    /**
     * The confirmed balance from bank at verification time.
     */
    private Money verifiedBalance;
}
```

### 6.3 Zmiana w StartImportRequest

```java
@Data
public static class StartImportRequest {
    private String stagingSessionId;

    // Balance verification (required for first import after rollover)
    private Money confirmedBalance;      // Current balance from bank
    private boolean forceImport;         // Ignore mismatch
    private boolean createAdjustment;    // Create adjustment transaction for difference
}
```

### 6.4 Zmiana w GetStagingPreviewResponse

```java
@Data
public static class GetStagingPreviewResponse {
    // ... existing fields ...

    /**
     * True if this is the first import after month rollover.
     * User must provide confirmedBalance in StartImportRequest.
     */
    private boolean balanceVerificationRequired;

    /**
     * Predicted balance after import (for user to compare with bank).
     */
    private Money predictedBalanceAfterImport;
}
```

### 6.5 Logika weryfikacji

```java
// W StartImportCommandHandler

boolean verificationRequired = !isBalanceVerifiedThisMonth(cashFlowId, activePeriod);

if (verificationRequired) {
    if (request.getConfirmedBalance() == null) {
        throw new BalanceVerificationRequiredException(
            "First import after rollover requires balance verification. " +
            "Please provide confirmedBalance.");
    }

    Money calculatedBalance = calculateBalanceAfterImport(stagingSession);
    Money difference = request.getConfirmedBalance().minus(calculatedBalance);

    if (!difference.isZero()) {
        if (!request.isForceImport() && !request.isCreateAdjustment()) {
            throw new BalanceMismatchException(
                cashFlowId, calculatedBalance, request.getConfirmedBalance(), difference);
        }

        if (request.isCreateAdjustment()) {
            createAdjustmentTransaction(cashFlowId, difference);
        }
    }

    markBalanceVerified(cashFlowId, activePeriod, request.getConfirmedBalance());
}

// Proceed with import...
```

---

## 7. Gap Filling & Recalculation

### 7.1 Definicja Gap Filling

Import transakcji do miesiąca który już został zamknięty (ROLLED_OVER).

**Scenariusz:**
- User nie używał aplikacji przez 2 miesiące (listopad, grudzień)
- Jest styczeń, user wgrywa CSV z transakcjami z listopada i grudnia
- System pozwala na import do ROLLED_OVER miesięcy

### 7.2 Recalculation balansów

Po imporcie do przeszłego miesiąca, wszystkie kolejne miesiące muszą mieć przeliczone balansy.

**Nowa metoda w CashFlowForecastStatement:**

```java
/**
 * Recalculates balances for all months starting from given period.
 * Must be called after gap filling to maintain balance consistency.
 *
 * @param fromPeriod the first month to recalculate (typically the month where gap fill occurred)
 */
public void recalculateBalancesFrom(YearMonth fromPeriod) {
    // Get all months from fromPeriod onwards, sorted chronologically
    List<Map.Entry<YearMonth, CashFlowMonthlyForecast>> monthsToRecalculate = forecasts.entrySet().stream()
        .filter(e -> !e.getKey().isBefore(fromPeriod))
        .sorted(Map.Entry.comparingByKey())
        .toList();

    if (monthsToRecalculate.isEmpty()) {
        return;
    }

    // Get starting balance from previous month (or initial balance if first month)
    YearMonth previousPeriod = fromPeriod.minusMonths(1);
    Money runningBalance = forecasts.containsKey(previousPeriod)
        ? forecasts.get(previousPeriod).getCashFlowStats().getEnd()
        : getInitialBalance();

    // Recalculate each month
    for (Map.Entry<YearMonth, CashFlowMonthlyForecast> entry : monthsToRecalculate) {
        CashFlowMonthlyForecast forecast = entry.getValue();

        Money netChange = forecast.calcNetChange();
        Money endBalance = runningBalance.plus(netChange);

        CashFlowStats updatedStats = new CashFlowStats(
            runningBalance,  // start
            endBalance,      // end
            netChange,
            forecast.getCashFlowStats().getInflowStats(),
            forecast.getCashFlowStats().getOutflowStats()
        );

        forecast.setCashFlowStats(updatedStats);
        runningBalance = endBalance;
    }
}
```

### 7.3 Wywołanie recalculation

W `ImportTransactionsCommandHandler` (lub odpowiednim event handler):

```java
// After importing transactions
YearMonth earliestAffectedMonth = findEarliestAffectedMonth(importedTransactions);

if (earliestAffectedMonth.isBefore(activePeriod)) {
    // Gap filling occurred - recalculate balances
    statement.recalculateBalancesFrom(earliestAffectedMonth);
    log.info("Recalculated balances from [{}] due to gap filling", earliestAffectedMonth);
}
```

---

## 8. REST API - zmiany

### 8.1 Staging Preview - nowe pola

**Endpoint:** `GET /api/v1/bank-data-ingestion/{cashFlowId}/staging/{stagingSessionId}`

**Response (rozszerzony):**
```json
{
  "stagingSessionId": "abc-123",
  "cashFlowId": "cf-456",
  "status": "READY_FOR_IMPORT",
  "expiresAt": "2026-02-09T12:00:00Z",
  "summary": {
    "totalTransactions": 25,
    "validTransactions": 23,
    "invalidTransactions": 0,
    "duplicateTransactions": 2
  },
  "balanceVerificationRequired": true,
  "currentBalance": {
    "amount": 4850.00,
    "currency": "PLN"
  },
  "predictedBalanceAfterImport": {
    "amount": 5180.00,
    "currency": "PLN"
  },
  "transactions": [...],
  "categoryBreakdown": [...],
  "monthlyBreakdown": [...]
}
```

### 8.2 Start Import - rozszerzony request

**Endpoint:** `POST /api/v1/bank-data-ingestion/{cashFlowId}/import`

**Request (rozszerzony):**
```json
{
  "stagingSessionId": "abc-123",
  "confirmedBalance": {
    "amount": 5180.00,
    "currency": "PLN"
  },
  "forceImport": false,
  "createAdjustment": false
}
```

**Response (sukces z weryfikacją):**
```json
{
  "jobId": "job-789",
  "cashFlowId": "cf-456",
  "status": "COMPLETED",
  "balanceVerification": {
    "verified": true,
    "confirmedBalance": { "amount": 5180.00, "currency": "PLN" },
    "calculatedBalance": { "amount": 5180.00, "currency": "PLN" },
    "difference": { "amount": 0.00, "currency": "PLN" },
    "adjustmentCreated": false
  },
  "result": {
    "transactionsImported": 23,
    "transactionsFailed": 0,
    "categoriesCreated": 2
  }
}
```

**Response (mismatch bez force):**
```json
{
  "error": "BALANCE_MISMATCH",
  "message": "Balance mismatch detected",
  "details": {
    "confirmedBalance": { "amount": 5180.00, "currency": "PLN" },
    "calculatedBalance": { "amount": 5130.00, "currency": "PLN" },
    "difference": { "amount": 50.00, "currency": "PLN" }
  },
  "hint": "Use forceImport=true to ignore or createAdjustment=true to create adjustment transaction"
}
```

### 8.3 Nowy endpoint: Manual Rollover (opcjonalny)

**Endpoint:** `POST /api/v1/cash-flow/{cashFlowId}/rollover`

**Opis:** Manualny trigger rollover (dla testów lub gdy user chce wymusić)

**Response:**
```json
{
  "cashFlowId": "cf-456",
  "rolledOverPeriod": "2026-01",
  "newActivePeriod": "2026-02",
  "closingBalance": { "amount": 5180.00, "currency": "PLN" }
}
```

---

## 9. Przykład pełnego flow

### Scenariusz

User "Jan" zakłada CashFlow z historią od czerwca 2025, wgrywa dane historyczne, aktywuje, a potem przez kolejne miesiące wgrywa pliki CSV.

### Timeline

```
Czerwiec 2025      - startPeriod
...
Styczeń 2026       - activePeriod (moment tworzenia)
Luty 2026          - teraźniejszość (user wykonuje operacje)
```

---

### KROK 1: Utworzenie CashFlow z historią

**Request:** `POST /cash-flow/with-history`
```json
{
  "name": "Konto główne ING",
  "description": "Główne konto osobiste",
  "bankAccount": {
    "number": "PL61109010140000071219812874",
    "denomination": "PLN"
  },
  "startPeriod": "2025-06",
  "initialBalance": { "amount": 10000.00, "currency": "PLN" }
}
```

**Stan CashFlow po utworzeniu:**
```
CashFlow:
  id: cf-001
  status: SETUP
  startPeriod: 2025-06
  activePeriod: 2026-01

CashFlowForecastStatement:
  ┌─────────────┬─────────────────┬──────────────┬──────────────┐
  │ Miesiąc     │ Status          │ Start Balance│ End Balance  │
  ├─────────────┼─────────────────┼──────────────┼──────────────┤
  │ 2025-06     │ IMPORT_PENDING  │ 10,000 PLN   │ 10,000 PLN   │
  │ 2025-07     │ IMPORT_PENDING  │ 10,000 PLN   │ 10,000 PLN   │
  │ 2025-08     │ IMPORT_PENDING  │ 10,000 PLN   │ 10,000 PLN   │
  │ 2025-09     │ IMPORT_PENDING  │ 10,000 PLN   │ 10,000 PLN   │
  │ 2025-10     │ IMPORT_PENDING  │ 10,000 PLN   │ 10,000 PLN   │
  │ 2025-11     │ IMPORT_PENDING  │ 10,000 PLN   │ 10,000 PLN   │
  │ 2025-12     │ IMPORT_PENDING  │ 10,000 PLN   │ 10,000 PLN   │
  │ 2026-01     │ ACTIVE          │ 10,000 PLN   │ 10,000 PLN   │
  │ 2026-02     │ FORECASTED      │ 10,000 PLN   │ 10,000 PLN   │
  │ ...         │ FORECASTED      │ ...          │ ...          │
  │ 2026-12     │ FORECASTED      │ 10,000 PLN   │ 10,000 PLN   │
  └─────────────┴─────────────────┴──────────────┴──────────────┘
```

---

### KROK 2: Historical Backfill - Wgranie CSV z danymi historycznymi

**CSV plik** (historia czerwiec 2025 - grudzień 2025):
```csv
date,description,amount,category,type
2025-06-15,Wypłata,8500.00,Salary,INFLOW
2025-06-20,Czynsz,-2000.00,Housing,OUTFLOW
2025-07-15,Wypłata,8500.00,Salary,INFLOW
2025-07-18,Zakupy Biedronka,-450.00,Groceries,OUTFLOW
...
2025-12-15,Wypłata,8500.00,Salary,INFLOW
2025-12-24,Prezenty świąteczne,-1200.00,Gifts,OUTFLOW
```

**Request:** `POST /api/v1/bank-data-ingestion/cf-001/upload`

**Stan po imporcie historii:**
```
CashFlowForecastStatement:
  ┌─────────────┬─────────────────┬──────────────┬──────────────┐
  │ Miesiąc     │ Status          │ Start Balance│ End Balance  │
  ├─────────────┼─────────────────┼──────────────┼──────────────┤
  │ 2025-06     │ IMPORT_PENDING  │ 10,000 PLN   │ 16,500 PLN   │  ← +8500 -2000
  │ 2025-07     │ IMPORT_PENDING  │ 16,500 PLN   │ 24,550 PLN   │
  │ 2025-08     │ IMPORT_PENDING  │ 24,550 PLN   │ 31,200 PLN   │
  │ 2025-09     │ IMPORT_PENDING  │ 31,200 PLN   │ 38,100 PLN   │
  │ 2025-10     │ IMPORT_PENDING  │ 38,100 PLN   │ 44,800 PLN   │
  │ 2025-11     │ IMPORT_PENDING  │ 44,800 PLN   │ 51,300 PLN   │
  │ 2025-12     │ IMPORT_PENDING  │ 51,300 PLN   │ 58,600 PLN   │  ← +8500 -1200
  │ 2026-01     │ ACTIVE          │ 58,600 PLN   │ 58,600 PLN   │
  │ ...         │ FORECASTED      │ ...          │ ...          │
  └─────────────┴─────────────────┴──────────────┴──────────────┘
```

---

### KROK 3: Attestation - Aktywacja CashFlow

**Request:** `POST /cash-flow/cf-001/attest-historical-import`
```json
{
  "confirmedBalance": { "amount": 58600.00, "currency": "PLN" }
}
```

**Stan po aktywacji:**
```
CashFlow:
  status: OPEN  ← zmiana z SETUP

CashFlowForecastStatement:
  ┌─────────────┬─────────────────┬──────────────┬──────────────┐
  │ Miesiąc     │ Status          │ Start Balance│ End Balance  │
  ├─────────────┼─────────────────┼──────────────┼──────────────┤
  │ 2025-06     │ IMPORTED        │ 10,000 PLN   │ 16,500 PLN   │  ← zmiana statusu
  │ 2025-07     │ IMPORTED        │ 16,500 PLN   │ 24,550 PLN   │
  │ 2025-08     │ IMPORTED        │ 24,550 PLN   │ 31,200 PLN   │
  │ 2025-09     │ IMPORTED        │ 31,200 PLN   │ 38,100 PLN   │
  │ 2025-10     │ IMPORTED        │ 38,100 PLN   │ 44,800 PLN   │
  │ 2025-11     │ IMPORTED        │ 44,800 PLN   │ 51,300 PLN   │
  │ 2025-12     │ IMPORTED        │ 51,300 PLN   │ 58,600 PLN   │
  │ 2026-01     │ ACTIVE          │ 58,600 PLN   │ 58,600 PLN   │
  │ ...         │ FORECASTED      │ ...          │ ...          │
  └─────────────┴─────────────────┴──────────────┴──────────────┘
```

---

### KROK 4: Ongoing Sync - Pierwszy CSV po aktywacji (styczeń)

**Data:** 25 stycznia 2026
**CSV plik** (transakcje styczniowe):
```csv
date,description,amount,category,type
2026-01-02,Zwrot podatku,1500.00,Tax Refund,INFLOW
2026-01-10,Netflix,-49.00,Entertainment,OUTFLOW
2026-01-15,Wypłata,8500.00,Salary,INFLOW
2026-01-20,Czynsz,-2000.00,Housing,OUTFLOW
```

**Request:** `POST /api/v1/bank-data-ingestion/cf-001/upload`

**Staging Preview Response:**
```json
{
  "stagingSessionId": "stg-101",
  "status": "READY_FOR_IMPORT",
  "balanceVerificationRequired": true,
  "currentBalance": { "amount": 58600.00, "currency": "PLN" },
  "predictedBalanceAfterImport": { "amount": 66551.00, "currency": "PLN" },
  "summary": {
    "totalTransactions": 4,
    "validTransactions": 4,
    "duplicateTransactions": 0
  }
}
```

**Import Request:** `POST /api/v1/bank-data-ingestion/cf-001/import`
```json
{
  "stagingSessionId": "stg-101",
  "confirmedBalance": { "amount": 66551.00, "currency": "PLN" }
}
```

**Stan po imporcie:**
```
CashFlowForecastStatement:
  ┌─────────────┬─────────────────┬──────────────┬──────────────┬──────────────────┐
  │ Miesiąc     │ Status          │ Start Balance│ End Balance  │ Balance Verified │
  ├─────────────┼─────────────────┼──────────────┼──────────────┼──────────────────┤
  │ 2025-06     │ IMPORTED        │ 10,000 PLN   │ 16,500 PLN   │ -                │
  │ ...         │ IMPORTED        │ ...          │ ...          │ -                │
  │ 2025-12     │ IMPORTED        │ 51,300 PLN   │ 58,600 PLN   │ -                │
  │ 2026-01     │ ACTIVE          │ 58,600 PLN   │ 66,551 PLN   │ 2026-01-25 ✓     │
  │ 2026-02     │ FORECASTED      │ 66,551 PLN   │ 66,551 PLN   │ -                │
  │ ...         │ FORECASTED      │ ...          │ ...          │ -                │
  └─────────────┴─────────────────┴──────────────┴──────────────┴──────────────────┘
```

---

### KROK 5: Ongoing Sync - Drugi CSV w styczniu (bez weryfikacji)

**Data:** 28 stycznia 2026
**CSV plik** (kolejne transakcje styczniowe):
```csv
date,description,amount,category,type
2026-01-26,Tankowanie,-250.00,Transport,OUTFLOW
2026-01-27,Allegro zakupy,-180.00,Shopping,OUTFLOW
```

**Staging Preview Response:**
```json
{
  "stagingSessionId": "stg-102",
  "status": "READY_FOR_IMPORT",
  "balanceVerificationRequired": false,
  "currentBalance": { "amount": 66551.00, "currency": "PLN" },
  "predictedBalanceAfterImport": { "amount": 66121.00, "currency": "PLN" },
  "summary": {
    "totalTransactions": 2,
    "validTransactions": 2,
    "duplicateTransactions": 0
  }
}
```

**Import Request:** (confirmedBalance opcjonalne)
```json
{
  "stagingSessionId": "stg-102"
}
```

**Stan po imporcie:**
```
  │ 2026-01     │ ACTIVE          │ 58,600 PLN   │ 66,121 PLN   │ 2026-01-25 ✓     │
```

---

### KROK 6: Month Rollover - Automatyczne przejście miesiąca

**Data:** 1 lutego 2026, 02:00 UTC
**Trigger:** `MonthlyRolloverScheduler`

**Event emitowany:** `MonthRolledOverEvent`
```json
{
  "cashFlowId": "cf-001",
  "rolledOverPeriod": "2026-01",
  "newActivePeriod": "2026-02",
  "closingBalance": { "amount": 66121.00, "currency": "PLN" },
  "rolledOverAt": "2026-02-01T02:00:00Z"
}
```

**Stan po rollover:**
```
CashFlowForecastStatement:
  ┌─────────────┬─────────────────┬──────────────┬──────────────┬──────────────────┐
  │ Miesiąc     │ Status          │ Start Balance│ End Balance  │ Balance Verified │
  ├─────────────┼─────────────────┼──────────────┼──────────────┼──────────────────┤
  │ 2025-06     │ IMPORTED        │ 10,000 PLN   │ 16,500 PLN   │ -                │
  │ ...         │ IMPORTED        │ ...          │ ...          │ -                │
  │ 2025-12     │ IMPORTED        │ 51,300 PLN   │ 58,600 PLN   │ -                │
  │ 2026-01     │ ROLLED_OVER     │ 58,600 PLN   │ 66,121 PLN   │ 2026-01-25       │  ← zmiana statusu
  │ 2026-02     │ ACTIVE          │ 66,121 PLN   │ 66,121 PLN   │ -                │  ← nowy ACTIVE
  │ 2026-03     │ FORECASTED      │ 66,121 PLN   │ 66,121 PLN   │ -                │
  │ ...         │ FORECASTED      │ ...          │ ...          │ -                │
  │ 2027-01     │ FORECASTED      │ 66,121 PLN   │ 66,121 PLN   │ -                │  ← nowy miesiąc
  └─────────────┴─────────────────┴──────────────┴──────────────┴──────────────────┘
```

---

### KROK 7: Ongoing Sync - Luty + Gap Filling

**Data:** 10 lutego 2026
**CSV plik** (transakcje lutowe + zapomniana ze stycznia):
```csv
date,description,amount,category,type
2026-01-30,Apteka,-85.00,Health,OUTFLOW
2026-02-01,Zwrot za bilety,120.00,Entertainment,INFLOW
2026-02-05,Spotify,-29.00,Entertainment,OUTFLOW
```

**Staging Preview Response:**
```json
{
  "stagingSessionId": "stg-103",
  "status": "READY_FOR_IMPORT",
  "balanceVerificationRequired": true,
  "currentBalance": { "amount": 66121.00, "currency": "PLN" },
  "predictedBalanceAfterImport": { "amount": 66127.00, "currency": "PLN" },
  "summary": {
    "totalTransactions": 3,
    "validTransactions": 3,
    "duplicateTransactions": 0
  },
  "monthlyBreakdown": [
    { "month": "2026-01", "outflowTotal": -85.00, "transactionCount": 1 },
    { "month": "2026-02", "inflowTotal": 120.00, "outflowTotal": -29.00, "transactionCount": 2 }
  ]
}
```

**Import z weryfikacją salda:**
```json
{
  "stagingSessionId": "stg-103",
  "confirmedBalance": { "amount": 66127.00, "currency": "PLN" }
}
```

**Stan po imporcie (z gap filling i recalculation):**
```
CashFlowForecastStatement:
  ┌─────────────┬─────────────────┬──────────────┬──────────────┬──────────────────┐
  │ Miesiąc     │ Status          │ Start Balance│ End Balance  │ Balance Verified │
  ├─────────────┼─────────────────┼──────────────┼──────────────┼──────────────────┤
  │ ...         │ ...             │ ...          │ ...          │ ...              │
  │ 2026-01     │ ROLLED_OVER     │ 58,600 PLN   │ 66,036 PLN   │ 2026-01-25       │  ← -85 PLN (gap fill)
  │ 2026-02     │ ACTIVE          │ 66,036 PLN   │ 66,127 PLN   │ 2026-02-10 ✓     │  ← recalculated + verified
  │ 2026-03     │ FORECASTED      │ 66,127 PLN   │ 66,127 PLN   │ -                │  ← recalculated
  │ ...         │ FORECASTED      │ ...          │ ...          │ -                │
  └─────────────┴─────────────────┴──────────────┴──────────────┴──────────────────┘

Recalculation wykonany dla: 2026-01 → 2026-02 → 2026-03 → ... → 2027-01
```

---

### KROK 8: Ongoing Sync - Trzeci CSV (kolejny w lutym, bez weryfikacji)

**Data:** 20 lutego 2026
**CSV plik:**
```csv
date,description,amount,category,type
2026-02-15,Wypłata,8500.00,Salary,INFLOW
2026-02-18,Restauracja,-150.00,Food,OUTFLOW
```

**Staging Preview Response:**
```json
{
  "stagingSessionId": "stg-104",
  "status": "READY_FOR_IMPORT",
  "balanceVerificationRequired": false,
  ...
}
```

**Import (bez weryfikacji - już zweryfikowano 10 lutego):**
```json
{
  "stagingSessionId": "stg-104"
}
```

**Stan końcowy:**
```
  │ 2026-02     │ ACTIVE          │ 66,036 PLN   │ 74,477 PLN   │ 2026-02-10       │
```

---

## 10. Plan implementacji (PR-y)

### Diagram zależności

```
PR-1 (Domain Events & Status)
  │
  ├──► PR-2 (Rollover Command & Handler)
  │      │
  │      └──► PR-3 (Scheduled Job)
  │
  └──► PR-4 (Ongoing Sync Validation)
         │
         └──► PR-5 (Balance Verification)
                │
                └──► PR-6 (Gap Filling & Recalculation)
                       │
                       └──► PR-7 (Remove Month Attestation)
```

---

### PR-1: Domain - MonthRolledOverEvent & Status

**Branch:** `feature/VID-XXX-month-rollover-domain`

**Pliki do zmiany:**
| Plik | Zmiana |
|------|--------|
| `CashFlowMonthlyForecast.java` | Dodać `ROLLED_OVER` do enum `Status`, pola `balanceVerifiedAt`, `verifiedBalance` |
| `CashFlowEvent.java` | Dodać `MonthRolledOverEvent` do sealed interface |
| `CashFlow.java` | Dodać `apply(MonthRolledOverEvent)` |
| `CashFlowAggregateProjector.java` | Obsłużyć nowy event |
| `CashFlowEventListener.java` | Dodać mapping dla nowego eventu |

**Testy:**
- `CashFlowAggregateTest` - test apply dla MonthRolledOverEvent
- `CashFlowMonthlyForecastTest` - test nowego statusu

**Szacowany rozmiar:** ~150-200 linii

---

### PR-2: Rollover Command & Event Handler

**Branch:** `feature/VID-XXX-rollover-command-handler`

**Pliki do utworzenia:**
| Plik | Opis |
|------|------|
| `RolloverMonthCommand.java` | Command record |
| `RolloverMonthCommandHandler.java` | Handler - walidacja + emit event |
| `MonthRolledOverEventHandler.java` | Handler w forecast processor |

**Pliki do zmiany:**
| Plik | Zmiana |
|------|--------|
| `CashFlowForecastProcessor.java` | Zarejestrować nowy handler |
| `CashFlowForecastStatement.java` | Metody pomocnicze do rollover |

**Logika MonthRolledOverEventHandler:**
1. Zmień ACTIVE → ROLLED_OVER
2. Zmień FORECASTED → ACTIVE (następny miesiąc)
3. Przenieś EXPECTED transakcje
4. Dodaj nowy FORECASTED na koniec
5. `updateStats()`

**Testy:**
- `RolloverMonthCommandHandlerTest`
- `MonthRolledOverEventHandlerTest`
- Integration test: pełny flow

**Szacowany rozmiar:** ~300-400 linii

---

### PR-3: Scheduled Job - MonthlyRolloverScheduler

**Branch:** `feature/VID-XXX-rollover-scheduler`

**Pliki do utworzenia:**
| Plik | Opis |
|------|------|
| `MonthlyRolloverScheduler.java` | Scheduled job |

**Pliki do zmiany:**
| Plik | Zmiana |
|------|--------|
| `DomainCashFlowRepository.java` | Dodać `findOpenCashFlowsNeedingRollover()` |
| `CashFlowMongoRepository.java` | Implementacja query |
| `application.properties` | Config cron |

**Query:**
```java
// Znajdź CashFlow gdzie:
// - status = OPEN
// - activePeriod < currentMonth
List<CashFlow> findOpenCashFlowsNeedingRollover(YearMonth currentMonth);
```

**Testy:**
- `MonthlyRolloverSchedulerTest`
- Integration test z mockiem Clock

**Szacowany rozmiar:** ~150-200 linii

---

### PR-4: Ongoing Sync - Validation Changes

**Branch:** `feature/VID-XXX-ongoing-sync-validation`

**Pliki do zmiany:**
| Plik | Zmiana |
|------|--------|
| `StageTransactionsCommandHandler.java` | Nowa logika walidacji |
| `CashFlowInfo.java` | Dodać `isInOpenMode()`, `balanceVerifiedThisMonth` |
| `CashFlowServiceClient.java` | Rozszerzyć response |
| `HttpCashFlowServiceClient.java` | Implementacja |

**Testy:**
- Testy walidacji dla SETUP mode (regresja)
- Testy walidacji dla OPEN mode (nowe)
- Integration test: import do ACTIVE
- Integration test: blokada FORECASTED

**Szacowany rozmiar:** ~200-250 linii

---

### PR-5: Balance Verification - Once Per Month

**Branch:** `feature/VID-XXX-balance-verification`

**Pliki do zmiany:**
| Plik | Zmiana |
|------|--------|
| `BankDataIngestionDto.java` | Rozszerzyć `StartImportRequest`, `GetStagingPreviewResponse` |
| `StartImportCommandHandler.java` | Logika weryfikacji |
| `BankDataIngestionRestController.java` | Walidacja request |

**Pliki do utworzenia:**
| Plik | Opis |
|------|------|
| `BalanceVerificationRequiredException.java` | Nowy exception |

**Testy:**
- Weryfikacja wymagana przy pierwszym imporcie
- Weryfikacja opcjonalna przy kolejnych
- Balance mismatch handling (force, adjustment)

**Szacowany rozmiar:** ~300-350 linii

---

### PR-6: Gap Filling & Balance Recalculation

**Branch:** `feature/VID-XXX-gap-filling-recalculation`

**Pliki do zmiany:**
| Plik | Zmiana |
|------|--------|
| `CashFlowForecastStatement.java` | Dodać `recalculateBalancesFrom(YearMonth)` |
| `HistoricalCashChangeImportedEventHandler.java` | Obsłużyć import do ROLLED_OVER |
| Import handler | Wywołać recalculation |

**Testy:**
- Unit test: `recalculateBalancesFrom()`
- Integration test: gap filling do ROLLED_OVER
- Integration test: weryfikacja balansów po gap fill

**Szacowany rozmiar:** ~200-250 linii

---

### PR-7: Remove Month Attestation (Deprecation)

**Branch:** `feature/VID-XXX-remove-month-attestation`

**Kiedy wykonać:** Na końcu, po PR-1 do PR-6 (gdy rollover działa poprawnie)

**Pliki do usunięcia:**
| Plik | Opis |
|------|------|
| `MakeMonthlyAttestationCommand.java` | Command - DELETE |
| `MakeMonthlyAttestationCommandHandler.java` | Handler - DELETE |
| `MonthAttestedEventHandler.java` | Event handler w forecast processor - DELETE |

**Pliki do modyfikacji:**
| Plik | Zmiana |
|------|--------|
| `CashFlowEvent.java` | Usunąć `MonthAttestedEvent` z sealed interface |
| `CashFlow.java` | Usunąć `apply(MonthAttestedEvent)` |
| `CashFlowAggregateProjector.java` | Usunąć case |
| `CashFlowEventListener.java` | Usunąć mapping |
| `CashFlowForecastProcessor.java` | Usunąć rejestrację handlera |

**Zachować (wsteczna kompatybilność):**
- `Attestation.java`
- `CashFlowMonthlyForecast.Status.ATTESTED`

**Testy do modyfikacji:**
- `CashFlowForecastProcessorTest`
- `CashFlowAggregateTest`
- `DualCashflowStatementGenerator`
- `DualCashflowStatementGeneratorWithHistory`
- `CashFlowForecastStatementGenerator`
- `CashflowStatementViaAIGenerator`

**Szacowany rozmiar:** ~200-300 linii usunięć, ~50-100 linii modyfikacji

---

## 11. Wpływ na testy

### Testy wymagające modyfikacji

| Test | Zmiana |
|------|--------|
| `BankDataIngestionControllerTest` | Dodać testy Ongoing Sync |
| `BankDataIngestionHttpIntegrationTest` | Dodać testy gap filling, balance verification |
| `TestCashFlowServiceClient` | Rozszerzyć mock o nowe pola |
| `HttpTestCashFlowServiceClient` | j.w. |
| `CashFlowForecastProcessorTest` | Dodać test MonthRolledOverEventHandler |

### Nowe testy do napisania

| Test | PR |
|------|-----|
| `RolloverMonthCommandHandlerTest` | PR-2 |
| `MonthRolledOverEventHandlerTest` | PR-2 |
| `MonthlyRolloverSchedulerTest` | PR-3 |
| `OngoingSyncValidationTest` | PR-4 |
| `BalanceVerificationTest` | PR-5 |
| `GapFillingIntegrationTest` | PR-6 |
| `BalanceRecalculationTest` | PR-6 |

---

## 12. Decyzje projektowe

### Potwierdzone decyzje

| # | Decyzja | Status |
|---|---------|--------|
| 1 | Nowy status `ROLLED_OVER` z dozwolonym gap filling | ✅ |
| 2 | Scheduled job o 02:00 UTC 1-go dnia miesiąca | ✅ |
| 3 | Event: `MonthRolledOverEvent` | ✅ |
| 4 | Command: `RolloverMonthCommand` | ✅ |
| 5 | Gap filling dozwolony dla ROLLED_OVER miesięcy | ✅ |
| 6 | Auto-recalculation balansów po gap filling | ✅ |
| 7 | Weryfikacja salda raz na miesiąc (pierwszy import po rollover) | ✅ |
| 8 | Nazewnictwo: "Historical Backfill" + "Ongoing Sync" | ✅ |
| 9 | Usunięcie `MakeMonthlyAttestationCommand` (deprecated) | ✅ |
| 10 | Usunięcie `MonthAttestedEvent` z sealed interface | ✅ |
| 11 | Zachowanie statusu `ATTESTED` dla wstecznej kompatybilności | ✅ |
| 12 | `ATTESTED` = read only (legacy), `ROLLED_OVER` = allows gap filling | ✅ |

### Otwarte kwestie (do przyszłej implementacji)

| Kwestia | Decyzja |
|---------|---------|
| Timezone użytkownika | Na MVP: UTC, w przyszłości: pole timezone w CashFlow |
| "Quick Sync" bez weryfikacji | Nie implementujemy na MVP |
| Manual rollover endpoint | Opcjonalny, do decyzji |
| Migracja ATTESTED → ROLLED_OVER | Nie - różna semantyka, pozostawiamy bez zmian |

---

## Appendix: Diagram stanów miesiąca

```
                    ┌─────────────────┐
                    │  IMPORT_PENDING │ (Historical Backfill)
                    └────────┬────────┘
                             │ attestHistoricalImport
                             ▼
                    ┌─────────────────┐
                    │    IMPORTED     │ (Historical - read only)
                    └─────────────────┘


                    ┌─────────────────┐
     ┌──────────────│   FORECASTED    │◄─────────────────┐
     │              └────────┬────────┘                  │
     │                       │ rollover (next month)     │
     │                       ▼                           │
     │              ┌─────────────────┐                  │
     │              │     ACTIVE      │                  │
     │              └────────┬────────┘                  │
     │                       │                           │
     │         ┌─────────────┴─────────────┐             │
     │         │                           │             │
     │         ▼                           ▼             │
     │  ┌─────────────┐           ┌─────────────────┐    │
     │  │  ATTESTED   │           │  ROLLED_OVER    │    │
     │  │  (manual)   │           │  (auto job)     │    │
     │  └─────────────┘           └────────┬────────┘    │
     │                                     │             │
     │                                     │ allows      │
     │                                     │ gap filling │
     │                                     ▼             │
     │                            [transactions added]   │
     │                                     │             │
     │                                     │ recalculate │
     └─────────────────────────────────────┴─────────────┘
                              (balances cascade forward)
```

---

## 13. Usunięcie Month Attestation (deprecation)

### 13.1 Kontekst

Po wdrożeniu automatycznego Month Rollover, manualna atestacja miesiąca (`MakeMonthlyAttestationCommand`) staje się zbędna. Docelowo należy ją usunąć z codebase.

### 13.2 Komponenty do usunięcia

| Komponent | Plik | Akcja |
|-----------|------|-------|
| `MakeMonthlyAttestationCommand` | `commands/attest/MakeMonthlyAttestationCommand.java` | **DELETE** |
| `MakeMonthlyAttestationCommandHandler` | `commands/attest/MakeMonthlyAttestationCommandHandler.java` | **DELETE** |
| `MonthAttestedEvent` | `CashFlowEvent.java` | **DELETE** z sealed interface |
| `MonthAttestedEventHandler` | `processing/MonthAttestedEventHandler.java` | **DELETE** |
| `CashFlow.apply(MonthAttestedEvent)` | `CashFlow.java` | **DELETE** metoda |
| Event mapping | `CashFlowEventListener.java` | **DELETE** case |
| Projector case | `CashFlowAggregateProjector.java` | **DELETE** case |

### 13.3 Komponenty do zachowania (wsteczna kompatybilność)

| Komponent | Plik | Powód zachowania |
|-----------|------|------------------|
| Status `ATTESTED` | `CashFlowMonthlyForecast.Status` | Istniejące dane mogą mieć ten status |
| `Attestation` record | `Attestation.java` | Używany przez inne komponenty |

### 13.4 Status ATTESTED vs ROLLED_OVER

**Pytanie:** Czy zachować status `ATTESTED` czy go usunąć?

**Odpowiedź:** Zachować dla wstecznej kompatybilności.

| Status | Znaczenie po zmianach |
|--------|----------------------|
| `ATTESTED` | Legacy - miesiąc zamknięty manualnie (stare dane, deprecated) |
| `ROLLED_OVER` | Miesiąc zamknięty automatycznie przez scheduled job |

**Alternatywa rozważana (odrzucona):**
- Rename `ATTESTED` → `MANUALLY_CLOSED` - breaking change dla istniejących danych

### 13.5 Obiekt Attestation - zmiany

Obecna definicja:
```java
public record Attestation(
    Money bankAccountBalance,
    Type type,          // MANUAL, AUTO
    ZonedDateTime dateTime
) {
    public enum Type {
        MANUAL, AUTO
    }
}
```

**Propozycja rozszerzenia:**
```java
public record Attestation(
    Money bankAccountBalance,
    Type type,
    ZonedDateTime dateTime
) {
    public enum Type {
        MANUAL,        // Legacy - manualna atestacja (deprecated)
        AUTO,          // Automatyczny rollover
        SYNC_VERIFIED  // Weryfikacja salda przy Ongoing Sync
    }
}
```

### 13.6 Wpływ biznesowy usunięcia atestacji

| Aspekt | Przed (z atestacją) | Po (bez atestacji) |
|--------|---------------------|-------------------|
| **Kontrola usera** | User decyduje kiedy zamknąć miesiąc | System zamyka automatycznie 1-go |
| **Weryfikacja salda** | Wymagana przy każdym zamknięciu | Raz na miesiąc przy pierwszym imporcie |
| **Elastyczność** | User może opóźnić zamknięcie | Brak - kalendarz rządzi |
| **Gap filling** | Niemożliwy po ATTESTED | Możliwy po ROLLED_OVER |
| **Dokładność danych** | Wyższa (wymuszana weryfikacja) | Zależna od częstotliwości importów |

### 13.7 Potencjalne problemy po usunięciu

#### Problem 1: User chce ręcznie zamknąć miesiąc wcześniej

**Scenariusz:** Jest 15 stycznia, user wie że nie będzie więcej transakcji i chce "zamknąć" styczeń.

**Rozwiązanie:** User nie musi nic robić - miesiąc zamknie się automatycznie 1 lutego. Status ROLLED_OVER pozwala na gap filling gdyby się pomylił.

#### Problem 2: Brak wymuszenia weryfikacji salda na koniec miesiąca

**Scenariusz:** User wgrywa CSV raz na miesiąc, ale nie przy rollover. System zamyka miesiąc bez weryfikacji.

**Rozwiązanie:** Weryfikacja przy pierwszym imporcie NASTĘPNEGO miesiąca de facto weryfikuje poprzedni miesiąc (saldo startowe nowego miesiąca = saldo końcowe poprzedniego).

#### Problem 3: Utrata "audit trail" zamknięć

**Rozwiązanie:** `MonthRolledOverEvent` zawiera timestamp - mamy audit trail automatycznych zamknięć.

### 13.8 Wpływ na testy

| Test | Akcja |
|------|-------|
| `CashFlowForecastProcessorTest` | Usunąć testy `MonthAttestedEvent`, dodać `MonthRolledOverEvent` |
| `CashFlowAggregateTest` | Usunąć testy `apply(MonthAttestedEvent)` |
| `CashFlowControllerTest` | Usunąć testy attest-month endpoint (jeśli istnieje) |
| `DualCashflowStatementGenerator` | Zmienić z atestacji na rollover |
| `DualCashflowStatementGeneratorWithHistory` | j.w. |
| `CashFlowForecastStatementGenerator` | j.w. |
| `CashflowStatementViaAIGenerator` | j.w. |

### 13.9 PR-7: Remove Month Attestation

**Branch:** `feature/VID-XXX-remove-month-attestation`

**Kiedy wykonać:** Po PR-1 do PR-6 (na końcu, gdy rollover działa poprawnie)

**Pliki do usunięcia:**
```
src/main/java/com/multi/vidulum/cashflow/app/commands/attest/MakeMonthlyAttestationCommand.java
src/main/java/com/multi/vidulum/cashflow/app/commands/attest/MakeMonthlyAttestationCommandHandler.java
src/main/java/com/multi/vidulum/cashflow_forecast_processor/app/processing/MonthAttestedEventHandler.java
```

**Pliki do modyfikacji:**

| Plik | Zmiana |
|------|--------|
| `CashFlowEvent.java` | Usunąć `MonthAttestedEvent` z sealed interface |
| `CashFlow.java` | Usunąć `apply(MonthAttestedEvent)` |
| `CashFlowAggregateProjector.java` | Usunąć case dla `MonthAttestedEvent` |
| `CashFlowEventListener.java` | Usunąć mapping dla `MonthAttestedEvent` |
| `CashFlowForecastProcessor.java` | Usunąć rejestrację `MonthAttestedEventHandler` |
| `CashFlowRestController.java` | Usunąć endpoint atestacji (jeśli istnieje) |
| `CashFlowDto.java` | Usunąć DTOs dla atestacji miesiąca (jeśli istnieją) |

**Zachować (wsteczna kompatybilność):**
- `Attestation.java` - używany przez forecast
- `CashFlowMonthlyForecast.Status.ATTESTED` - dla istniejących danych

**Testy do modyfikacji:** ~10-15 plików (patrz sekcja 13.8)

**Szacowany rozmiar:** ~200-300 linii usunięć, ~50-100 linii modyfikacji

### 13.10 Zaktualizowany diagram zależności PR-ów

```
PR-1 (Domain Events & Status)
  │
  ├──► PR-2 (Rollover Command & Handler)
  │      │
  │      └──► PR-3 (Scheduled Job)
  │
  └──► PR-4 (Ongoing Sync Validation)
         │
         └──► PR-5 (Balance Verification)
                │
                └──► PR-6 (Gap Filling & Recalculation)
                       │
                       └──► PR-7 (Remove Month Attestation) ◄── NOWY, NA KOŃCU
```

### 13.11 Zaktualizowany diagram stanów miesiąca (po usunięciu atestacji)

```
                    ┌─────────────────┐
                    │  IMPORT_PENDING │ (Historical Backfill)
                    └────────┬────────┘
                             │ attestHistoricalImport
                             ▼
                    ┌─────────────────┐
                    │    IMPORTED     │ (Historical - read only)
                    └─────────────────┘


                    ┌─────────────────┐
     ┌──────────────│   FORECASTED    │◄─────────────────┐
     │              └────────┬────────┘                  │
     │                       │ rollover (scheduled job)  │
     │                       ▼                           │
     │              ┌─────────────────┐                  │
     │              │     ACTIVE      │                  │
     │              └────────┬────────┘                  │
     │                       │                           │
     │                       │ rollover (1st of month)   │
     │                       ▼                           │
     │              ┌─────────────────┐                  │
     │              │  ROLLED_OVER    │                  │
     │              │  (auto job)     │                  │
     │              └────────┬────────┘                  │
     │                       │                           │
     │                       │ allows gap filling        │
     │                       ▼                           │
     │              [transactions added]                 │
     │                       │                           │
     │                       │ recalculate balances      │
     └───────────────────────┴───────────────────────────┘
                    (balances cascade forward)


     ┌─────────────┐
     │  ATTESTED   │  ← DEPRECATED/LEGACY
     │  (manual)   │    Tylko dla wstecznej kompatybilności
     └─────────────┘    Nie używany w nowym kodzie
```

---

## 14. Otwarte pytania (do rozstrzygnięcia przed implementacją)

### Pytania biznesowe

| # | Pytanie | Propozycja | Status |
|---|---------|------------|--------|
| 1 | Czy user powinien móc ręcznie wymusić rollover przed końcem miesiąca? | Opcjonalny endpoint `/rollover` dla power users | Do decyzji |
| 2 | Co jeśli user nie wgrał żadnych transakcji przez cały miesiąc? | Rollover i tak następuje, miesiąc będzie pusty | Do potwierdzenia |
| 3 | Czy pokazywać userowi powiadomienie o nadchodzącym rollover? | UI feature, poza scope MVP | Odłożone |

### Pytania techniczne

| # | Pytanie | Propozycja | Status |
|---|---------|------------|--------|
| 4 | Co jeśli scheduled job nie uruchomi się (downtime)? | Rollover przy następnym starcie aplikacji lub następnym job run | Do decyzji |
| 5 | Czy rollover powinien być idempotentny? | Tak - sprawdzić czy activePeriod już jest aktualny | Do implementacji |
| 6 | Jak obsłużyć timezone przy gap filling? | Transakcje w UTC, konwersja po stronie UI | Do potwierdzenia |
| 7 | Czy `ATTESTED` i `ROLLED_OVER` powinny być traktowane identycznie przy gap filling? | Nie - ATTESTED = read only (legacy), ROLLED_OVER = allows gap filling | Potwierdzone |

### Pytania dotyczące migracji

| # | Pytanie | Propozycja | Status |
|---|---------|------------|--------|
| 8 | Co z istniejącymi CashFlows które mają miesiące w statusie ATTESTED? | Pozostają bez zmian, read only | Do potwierdzenia |
| 9 | Czy migrować ATTESTED → ROLLED_OVER? | Nie - różna semantyka (ATTESTED = weryfikacja salda, ROLLED_OVER = auto) | Do potwierdzenia |

---

*Dokument wygenerowany: 2026-02-08*
*Ostatnia aktualizacja: 2026-02-08 - dodano sekcję 13 (usunięcie Month Attestation) i sekcję 14 (otwarte pytania)*
