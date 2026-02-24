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
14. [Otwarte pytania](#14-otwarte-pytania-do-rozstrzygnięcia-przed-implementacją)
15. [Manual Rollover Endpoint](#15-manual-rollover-endpoint)
16. [Analiza: Częściowy import historii + późniejsze uzupełnienie](#16-analiza-częściowy-import-historii--późniejsze-uzupełnienie)
17. [Analiza: Weryfikacja salda w kontekście historii](#17-analiza-weryfikacja-salda-w-kontekście-historii)
18. [Zaktualizowany diagram stanów (final)](#18-zaktualizowany-diagram-stanów-final)
19. [Przykład: Pełny lifecycle z lukami i uzupełnieniami](#19-przykład-pełny-lifecycle-z-lukami-i-uzupełnieniami)
20. [Analiza: Wielokrotne dogrywanie historii po aktywacji](#20-analiza-wielokrotne-dogrywanie-historii-po-aktywacji)
    - 20.7 [Statusy CashFlow vs statusy miesięcy - pełna macierz](#207-statusy-cashflow-vs-statusy-miesięcy---pełna-macierz)
21. [Category Mappings - trwałość i reużycie](#21-category-mappings---trwałość-i-reużycie)
22. [Podsumowanie potwierdzonego designu](#22-podsumowanie-potwierdzonego-designu)
    - 22.3 [Zaktualizowany plan PR-ów (po analizie)](#223-zaktualizowany-plan-pr-ów-po-analizie)
    - 22.4 [Analiza testów do zmiany](#224-analiza-testów-do-zmiany)
    - 22.5 [Potwierdzenie: Nowy test jest wykonalny](#225-potwierdzenie-nowy-test-jest-wykonalny)
23. [Przewodnik integracji UI (Frontend Integration Guide)](#23-przewodnik-integracji-ui-frontend-integration-guide)
    - 23.1 [Podsumowanie zmian dla aplikacji UI](#231-podsumowanie-zmian-dla-aplikacji-ui)
    - 23.2 [Statusy miesięcy - co UI powinien wyświetlać](#232-statusy-miesięcy---co-ui-powinien-wyświetlać)
    - 23.3 [Flow importu CSV - kompletny przewodnik](#233-flow-importu-csv---kompletny-przewodnik)
    - 23.4 [Tabela: Kiedy wymagana jest balance verification](#234-tabela-kiedy-wymagana-jest-balance-verification)
    - 23.5 [Reużycie mappingów - przykłady](#235-reużycie-mappingów---przykłady)
    - 23.6 [Przypadki testowe dla UI](#236-przypadki-testowe-dla-ui)
    - 23.7 [Kody błędów i obsługa](#237-kody-błędów-i-obsługa)
    - 23.8 [Checklist migracji UI](#238-checklist-migracji-ui)

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

## 15. Manual Rollover Endpoint

### 15.1 Cel

Endpoint do ręcznego wywołania rollover miesiąca. Przydatny do:
1. **Testowania** - nie trzeba czekać na scheduled job (1-go o 02:00 UTC)
2. **Development** - szybkie przesuwanie miesięcy podczas developmentu
3. **Edge cases** - gdy scheduled job nie zadziałał (downtime)
4. **Power users** - jeśli user chce zamknąć miesiąc przed końcem kalendarzowym

### 15.2 REST API

**Endpoint:** `POST /api/v1/cash-flow/{cashFlowId}/rollover`

**Request:** (opcjonalny body)
```json
{
  "force": false,           // true = skip validation (e.g., activePeriod == current month)
  "targetPeriod": "2026-02" // optional: which period should become ACTIVE (default: current calendar month)
}
```

**Response (success):**
```json
{
  "cashFlowId": "cf-456",
  "previousActivePeriod": "2026-01",
  "newActivePeriod": "2026-02",
  "rolledOverPeriod": "2026-01",
  "closingBalance": { "amount": 66121.00, "currency": "PLN" },
  "rolledOverAt": "2026-02-08T14:30:00Z",
  "rolloverType": "MANUAL"
}
```

**Response (error - already current):**
```json
{
  "error": "ROLLOVER_NOT_NEEDED",
  "message": "CashFlow activePeriod is already current month",
  "details": {
    "activePeriod": "2026-02",
    "currentCalendarMonth": "2026-02"
  }
}
```

### 15.3 Logika

```java
@PostMapping("/{cashFlowId}/rollover")
public ResponseEntity<RolloverResponse> triggerManualRollover(
        @PathVariable UUID cashFlowId,
        @RequestBody(required = false) ManualRolloverRequest request) {

    CashFlow cashFlow = repository.findById(CashFlowId.of(cashFlowId))
            .orElseThrow(() -> new CashFlowNotFoundException(cashFlowId));

    // Validation
    if (cashFlow.getStatus() != CashFlowStatus.OPEN) {
        throw new InvalidOperationException("Rollover only allowed for OPEN CashFlows");
    }

    YearMonth currentMonth = YearMonth.now(clock);
    YearMonth activePeriod = cashFlow.getActivePeriod();

    // Check if rollover needed
    if (!request.isForce() && !activePeriod.isBefore(currentMonth)) {
        return ResponseEntity.badRequest().body(
            RolloverResponse.notNeeded(activePeriod, currentMonth));
    }

    // Determine how many months to roll over
    // (if activePeriod is 2025-10 and currentMonth is 2026-02, we need to roll over 4 times)
    YearMonth targetPeriod = request.getTargetPeriod() != null
        ? request.getTargetPeriod()
        : currentMonth;

    List<RolloverResult> results = new ArrayList<>();
    while (activePeriod.isBefore(targetPeriod)) {
        RolloverMonthCommand command = new RolloverMonthCommand(
            cashFlow.getCashFlowId(),
            ZonedDateTime.now(clock),
            RolloverType.MANUAL
        );
        commandGateway.send(command);
        results.add(new RolloverResult(activePeriod, activePeriod.plusMonths(1)));
        activePeriod = activePeriod.plusMonths(1);
    }

    return ResponseEntity.ok(RolloverResponse.success(results));
}
```

### 15.4 Wielokrotny rollover (catch-up)

Jeśli CashFlow ma `activePeriod = 2025-10` a jest luty 2026, endpoint może wykonać **4 rollover-y** jeden po drugim:
- 2025-10 → 2025-11 (ROLLED_OVER)
- 2025-11 → 2025-12 (ROLLED_OVER)
- 2025-12 → 2026-01 (ROLLED_OVER)
- 2026-01 → 2026-02 (ACTIVE)

**Każdy rollover:**
1. Tworzy `MonthRolledOverEvent`
2. Przenosi EXPECTED transakcje do następnego miesiąca
3. Dodaje nowy FORECASTED na końcu

### 15.5 Rozszerzenie RolloverMonthCommand

```java
public record RolloverMonthCommand(
    CashFlowId cashFlowId,
    ZonedDateTime triggeredAt,
    RolloverType type  // NEW: SCHEDULED, MANUAL
) {}

public enum RolloverType {
    SCHEDULED,  // triggered by MonthlyRolloverScheduler
    MANUAL      // triggered by REST endpoint
}
```

### 15.6 Dodanie do PR-3

**Rozszerzenie PR-3:**
- `CashFlowRestController.java` - nowy endpoint
- `ManualRolloverRequest.java` - request DTO
- `RolloverResponse.java` - response DTO
- `RolloverType.java` - enum

---

## 16. Analiza: Częściowy import historii + późniejsze uzupełnienie

### 16.1 Scenariusz

User tworzy CashFlow z historią od **stycznia 2025**, ale podczas SETUP mode importuje dane tylko do **października 2025**. Następnie aktywuje CashFlow (attestHistoricalImport).

**Pytanie:** Czy można później (w OPEN mode) dograć brakujące miesiące (listopad 2025, grudzień 2025)?

### 16.2 Analiza obecnego designu

**Po attestHistoricalImport:**
```
CashFlow status: OPEN

Timeline:
  2025-01  │ IMPORTED  │ ← dane zaimportowane
  2025-02  │ IMPORTED  │ ← dane zaimportowane
  ...
  2025-10  │ IMPORTED  │ ← dane zaimportowane
  2025-11  │ IMPORTED  │ ← BRAK DANYCH (puste, ale status IMPORTED)
  2025-12  │ IMPORTED  │ ← BRAK DANYCH (puste, ale status IMPORTED)
  2026-01  │ ACTIVE    │ ← bieżący miesiąc
  2026-02  │ FORECASTED│
  ...
```

**Problem:** Status `IMPORTED` oznacza "historyczny, read-only, zaatestowany". Nie pozwala na gap filling.

### 16.3 Propozycja rozwiązania

**Zmiana semantyki:** Rozróżnić miesiące:
1. **IMPORTED** - historyczny, z danymi, zaatestowany (read-only)
2. **IMPORTED_EMPTY** - historyczny, bez danych, zaatestowany (allows gap filling)

**Alternatywnie (prostsze):** Pozwolić na gap filling do IMPORTED, ale z flagą `historicalGapFill = true`.

### 16.4 Rekomendacja

**Zachować prostotę:**
- `IMPORTED` = historyczny, ale **pozwala na gap filling** (jak ROLLED_OVER)
- `ATTESTED` = legacy, read-only (deprecated)

**Zmiana w design:**

| Status | Gap filling | Kiedy tworzony |
|--------|-------------|----------------|
| `IMPORT_PENDING` | TAK (historical backfill) | createCashFlowWithHistory |
| `IMPORTED` | **TAK** (zmiana!) | attestHistoricalImport |
| `ATTESTED` | NIE (legacy) | stary MakeMonthlyAttestationCommand |
| `ROLLED_OVER` | TAK | scheduled job / manual rollover |
| `ACTIVE` | TAK (ongoing sync) | current month |
| `FORECASTED` | NIE (przyszłość) | auto-generated |

### 16.5 Zaktualizowany timeline po aktywacji

```
Scenariusz: CashFlow startPeriod=2025-01, aktywacja w lutym 2026, import tylko do 2025-10

Po attestHistoricalImport:
  2025-01  │ IMPORTED  │ dane ✓
  2025-02  │ IMPORTED  │ dane ✓
  ...
  2025-10  │ IMPORTED  │ dane ✓
  2025-11  │ IMPORTED  │ brak danych (gap - do uzupełnienia)
  2025-12  │ IMPORTED  │ brak danych (gap - do uzupełnienia)
  2026-01  │ ROLLED_OVER │ brak danych (gap - do uzupełnienia) ← został ACTIVE, potem auto-rollover
  2026-02  │ ACTIVE    │ bieżący miesiąc
  2026-03  │ FORECASTED│
  ...
  2027-01  │ FORECASTED│

Po rollover (1-go lutego 2026):
  2025-01  │ IMPORTED    │ dane ✓
  ...
  2025-10  │ IMPORTED    │ dane ✓
  2025-11  │ IMPORTED    │ gap ← można dograć
  2025-12  │ IMPORTED    │ gap ← można dograć
  2026-01  │ ROLLED_OVER │ gap ← można dograć
  2026-02  │ ACTIVE      │ bieżący
  2026-03  │ FORECASTED  │
  ...
```

### 16.6 Wnioski

1. **TAK** - możliwe jest utworzenie timeline z mieszanką IMPORTED + ROLLED_OVER + ACTIVE + FORECASTED
2. **TAK** - gap filling działa dla IMPORTED i ROLLED_OVER
3. **NIE** - nie można importować do FORECASTED (przyszłość)
4. **Zmiana w design:** IMPORTED też pozwala na gap filling (nie tylko ROLLED_OVER)

---

## 17. Analiza: Weryfikacja salda w kontekście historii

### 17.1 Problem

User ma tylko **aktualne saldo** z banku. Nie zna salda historycznego (np. z października 2025).

**Pytania:**
1. Czy weryfikacja salda jest wymagana dla historical backfill?
2. Jak weryfikować saldo przy gap filling do przeszłych miesięcy?
3. Czy wykorzystać "running balance" z wyciągów bankowych?

### 17.2 Obecny flow weryfikacji

**attestHistoricalImport:**
- User podaje `confirmedBalance` = aktualne saldo z banku
- System porównuje z `calculatedBalance` = obliczone z importowanych transakcji
- Jeśli różnica → adjustment transaction lub force

**Ongoing Sync (first import per month):**
- Wymaga `confirmedBalance`
- Weryfikacja raz na miesiąc

### 17.3 Running Balance z banku

Wiele banków w wyciągach podaje **saldo po transakcji**:

```csv
date,description,amount,balance_after
2025-10-15,Wypłata,8500.00,45000.00
2025-10-16,Biedronka,-150.00,44850.00
2025-10-20,Czynsz,-2000.00,42850.00
```

**Pole `balance_after`** = saldo na koncie po wykonaniu tej transakcji.

### 17.4 Propozycja: Balance Checkpoints

Rozszerzyć Canonical CSV o opcjonalne `balance_after`:

```csv
bankTransactionId,date,description,amount,currency,type,bankCategory,balance_after
TXN-001,2025-10-15,Wypłata,8500.00,PLN,INFLOW,Salary,45000.00
TXN-002,2025-10-16,Biedronka,150.00,PLN,OUTFLOW,Groceries,44850.00
TXN-003,2025-10-20,Czynsz,2000.00,PLN,OUTFLOW,Housing,42850.00
```

**Wykorzystanie:**
1. Przy staging - porównać `balance_after` ostatniej transakcji w miesiącu z obliczonym saldem
2. Auto-detect discrepancies bez pytania usera
3. Tworzyć adjustment automatycznie

### 17.5 Warianty weryfikacji salda

| Scenariusz | Dane dostępne | Weryfikacja |
|------------|---------------|-------------|
| Historical Backfill | CSV z balance_after | Auto-verify per transaction |
| Historical Backfill | CSV bez balance_after | Skip verification lub na koniec (attestation) |
| Ongoing Sync | Aktualne saldo | Raz na miesiąc (first import) |
| Gap Filling | CSV z balance_after | Auto-verify |
| Gap Filling | CSV bez balance_after | Recalculate, no verification |

### 17.6 Rozszerzone Canonical CSV v3

```csv
bankTransactionId,date,description,amount,currency,type,bankCategory,merchantName,merchantCategory,accountNumber,balance_after
```

**Nowe pole:**
| Pole | Wymagane | Opis |
|------|----------|------|
| `balance_after` | ❌ | Saldo po transakcji (opcjonalne, do weryfikacji) |

### 17.7 BalanceCheckpoint entity

```java
/**
 * A verified balance point in time.
 * Can come from: bank statement, user confirmation, transaction balance_after.
 */
public record BalanceCheckpoint(
    ZonedDateTime timestamp,          // When this balance was recorded
    Money balance,                    // The verified balance
    BalanceCheckpointSource source,   // Where this balance came from
    String transactionRef             // Optional: bankTransactionId if from transaction
) {}

public enum BalanceCheckpointSource {
    USER_CONFIRMED,       // User manually confirmed (attestation, monthly verification)
    TRANSACTION_BALANCE,  // From balance_after field in transaction
    API_BALANCE,          // From Open Banking API account balance
    CALCULATED            // Calculated from transactions (not verified)
}
```

### 17.8 Użycie w CashFlowMonthlyForecast

```java
@Data
public class CashFlowMonthlyForecast {
    // ... existing fields ...

    /**
     * Balance checkpoints for this month.
     * Ordered by timestamp. Used for verification and discrepancy detection.
     */
    private List<BalanceCheckpoint> balanceCheckpoints;

    /**
     * @return Last verified balance checkpoint, or null if none
     */
    public Optional<BalanceCheckpoint> getLastVerifiedBalance() {
        return balanceCheckpoints.stream()
            .filter(cp -> cp.source() != BalanceCheckpointSource.CALCULATED)
            .reduce((first, second) -> second); // get last
    }
}
```

### 17.9 Scenariusze użycia balance_after

**Scenariusz 1: CSV z balance_after (idealne)**
```
Import 3 transakcji:
  TXN-001: +8500, balance_after=45000  ← checkpoint
  TXN-002: -150,  balance_after=44850  ← checkpoint
  TXN-003: -2000, balance_after=42850  ← checkpoint

System:
  1. Oblicz expected balance po każdej transakcji
  2. Porównaj z balance_after
  3. Jeśli mismatch → warn user, suggest adjustment
  4. Store checkpoints for audit
```

**Scenariusz 2: CSV bez balance_after (częste)**
```
Import 3 transakcji (no balance_after):
  TXN-001: +8500
  TXN-002: -150
  TXN-003: -2000

System:
  1. Oblicz running balance
  2. Nie ma weryfikacji (no checkpoints from file)
  3. Checkpoint może być dodany później przez:
     - User confirmation
     - Next import with balance_after
     - Monthly verification
```

**Scenariusz 3: Mieszanka**
```
Import z częściowym balance_after:
  TXN-001: +8500, balance_after=45000  ← checkpoint
  TXN-002: -150                         ← no checkpoint
  TXN-003: -2000, balance_after=42850  ← checkpoint

System weryfikuje TXN-001 i TXN-003, TXN-002 obliczone
```

### 17.10 Wnioski i rekomendacje

1. **Nie wymuszać balance verification dla historii** - user może nie mieć danych
2. **Wykorzystać balance_after jeśli dostępne** - automatyczna weryfikacja
3. **Weryfikacja wymagana tylko przy:**
   - attestHistoricalImport (końcowe saldo)
   - First import per month (Ongoing Sync)
4. **Gap filling bez weryfikacji** - recalculate balances, trust the data

### 17.11 Decyzja do potwierdzenia

| # | Kwestia | Propozycja | Status |
|---|---------|------------|--------|
| 1 | Dodać `balance_after` do Canonical CSV? | TAK - opcjonalne pole | Do potwierdzenia |
| 2 | Auto-weryfikacja przy dostępnym balance_after? | TAK - checkpoints | Do potwierdzenia |
| 3 | Wymuszać weryfikację przy historical backfill? | NIE - tylko attestation | Do potwierdzenia |
| 4 | Wymuszać weryfikację przy gap filling? | NIE - recalculate | Do potwierdzenia |

---

## 18. Zaktualizowany diagram stanów (final)

```
                    ┌─────────────────┐
                    │  IMPORT_PENDING │ (Historical Backfill - SETUP mode)
                    └────────┬────────┘
                             │ attestHistoricalImport
                             ▼
                    ┌─────────────────┐
                    │    IMPORTED     │ (Historical - allows gap filling)
                    └─────────────────┘
                             │
                             │ gap filling allowed
                             ▼
                    [transactions can be added later]


                    ┌─────────────────┐
     ┌──────────────│   FORECASTED    │◄─────────────────┐
     │              └────────┬────────┘                  │
     │                       │ rollover (scheduled/manual)│
     │                       ▼                           │
     │              ┌─────────────────┐                  │
     │              │     ACTIVE      │                  │
     │              │ (ongoing sync)  │                  │
     │              └────────┬────────┘                  │
     │                       │                           │
     │                       │ rollover                  │
     │                       ▼                           │
     │              ┌─────────────────┐                  │
     │              │  ROLLED_OVER    │                  │
     │              │ (allows gap fill)│                  │
     │              └────────┬────────┘                  │
     │                       │                           │
     │                       │ gap filling allowed       │
     │                       ▼                           │
     │              [transactions can be added]          │
     │                       │                           │
     │                       │ recalculate balances      │
     └───────────────────────┴───────────────────────────┘


     ┌─────────────┐
     │  ATTESTED   │  ← DEPRECATED/LEGACY (read-only, no gap filling)
     └─────────────┘
```

### Podsumowanie statusów (final)

| Status | Gap Filling | Balance Verification | Użycie |
|--------|-------------|---------------------|--------|
| `IMPORT_PENDING` | TAK | Nie (jeszcze SETUP) | Historical months przed attestation |
| `IMPORTED` | **TAK** | Przy attestation | Historical months po attestation |
| `ROLLED_OVER` | TAK | First import per month | Auto-closed months |
| `ACTIVE` | TAK | First import per month | Current month |
| `FORECASTED` | NIE | N/A | Future months |
| `ATTESTED` | NIE (legacy) | Było wymagane | Deprecated |

---

## 19. Przykład: Pełny lifecycle z lukami i uzupełnieniami

### Scenariusz

1. User tworzy CashFlow z historią od **2025-01**
2. Importuje dane tylko za **2025-01 do 2025-06** (6 miesięcy)
3. Aktywuje CashFlow w **2025-08**
4. Miesiące 2025-07, 2025-08 są puste
5. Po miesiącu (wrzesień 2025) dogrywane są brakujące dane

### Timeline

```
=== KROK 1: createCashFlowWithHistory (August 2025) ===
startPeriod: 2025-01
activePeriod: 2025-08

  2025-01  │ IMPORT_PENDING │ czeka na import
  2025-02  │ IMPORT_PENDING │ czeka na import
  2025-03  │ IMPORT_PENDING │ czeka na import
  2025-04  │ IMPORT_PENDING │ czeka na import
  2025-05  │ IMPORT_PENDING │ czeka na import
  2025-06  │ IMPORT_PENDING │ czeka na import
  2025-07  │ IMPORT_PENDING │ czeka na import
  2025-08  │ ACTIVE         │ bieżący
  2025-09  │ FORECASTED     │
  ...
  2026-07  │ FORECASTED     │ (12 months ahead)


=== KROK 2: Historical Backfill (import 2025-01 to 2025-06) ===

  2025-01  │ IMPORT_PENDING │ 15 transactions ✓
  2025-02  │ IMPORT_PENDING │ 12 transactions ✓
  2025-03  │ IMPORT_PENDING │ 18 transactions ✓
  2025-04  │ IMPORT_PENDING │ 14 transactions ✓
  2025-05  │ IMPORT_PENDING │ 16 transactions ✓
  2025-06  │ IMPORT_PENDING │ 11 transactions ✓
  2025-07  │ IMPORT_PENDING │ empty (no import)
  2025-08  │ ACTIVE         │ bieżący
  ...


=== KROK 3: attestHistoricalImport ===
confirmedBalance: 45000 PLN

  2025-01  │ IMPORTED       │ 15 txns, balance OK
  2025-02  │ IMPORTED       │ 12 txns
  2025-03  │ IMPORTED       │ 18 txns
  2025-04  │ IMPORTED       │ 14 txns
  2025-05  │ IMPORTED       │ 16 txns
  2025-06  │ IMPORTED       │ 11 txns
  2025-07  │ IMPORTED       │ empty ← GAP (no data, but IMPORTED)
  2025-08  │ ACTIVE         │ bieżący
  ...

CashFlow status: OPEN


=== KROK 4: Rollover (September 1st, 02:00 UTC) ===

  2025-01  │ IMPORTED       │ historical
  ...
  2025-06  │ IMPORTED       │ historical
  2025-07  │ IMPORTED       │ GAP
  2025-08  │ ROLLED_OVER    │ zamknięty auto
  2025-09  │ ACTIVE         │ bieżący
  2025-10  │ FORECASTED     │
  ...
  2026-08  │ FORECASTED     │ (new month added)


=== KROK 5: Gap Filling (September 2025) ===
User wgrywa CSV z danymi za 2025-07:

Staging:
  - 14 transactions for 2025-07 (GAP)
  - 3 transactions for 2025-08 (ROLLED_OVER)
  - 5 transactions for 2025-09 (ACTIVE)

All valid - import proceeds.

  2025-01  │ IMPORTED       │ historical
  ...
  2025-06  │ IMPORTED       │ historical
  2025-07  │ IMPORTED       │ 14 txns ← GAP FILLED
  2025-08  │ ROLLED_OVER    │ 3 txns  ← GAP FILLED
  2025-09  │ ACTIVE         │ 5 txns  ← ongoing sync
  ...

Balances recalculated from 2025-07 onwards.


=== FINAL STATE ===

  2025-01  │ IMPORTED    │ historical, data ✓
  2025-02  │ IMPORTED    │ historical, data ✓
  2025-03  │ IMPORTED    │ historical, data ✓
  2025-04  │ IMPORTED    │ historical, data ✓
  2025-05  │ IMPORTED    │ historical, data ✓
  2025-06  │ IMPORTED    │ historical, data ✓
  2025-07  │ IMPORTED    │ gap filled ✓
  2025-08  │ ROLLED_OVER │ gap filled ✓
  2025-09  │ ACTIVE      │ ongoing sync ✓
  2025-10  │ FORECASTED  │
  ...
  2026-08  │ FORECASTED  │ (always 11 months ahead)
```

---

## 20. Analiza: Wielokrotne dogrywanie historii po aktywacji

### 20.1 Scenariusz

User tworzy CashFlow w **lutym 2026** z historią od **2025-01**, ale:
1. Importuje dane tylko do **2025-10** (3 miesiące temu)
2. Aktywuje CashFlow (attestHistoricalImport)
3. Później dogrywa dane za **2025-11**, **2025-12** (2 pliki)
4. Potem dogrywa **2026-01**
5. Na końcu importuje bieżący miesiąc **2026-02**

**Pytanie:** Jakie statusy mają miesiące na każdym etapie?

### 20.2 Krok po kroku

```
=== KROK 1: createCashFlowWithHistory (Luty 2026) ===
startPeriod: 2025-01
activePeriod: 2026-02 (bieżący miesiąc kalendarzowy)
CashFlow status: SETUP

  2025-01  │ IMPORT_PENDING │
  2025-02  │ IMPORT_PENDING │
  ...
  2025-10  │ IMPORT_PENDING │
  2025-11  │ IMPORT_PENDING │
  2025-12  │ IMPORT_PENDING │
  2026-01  │ IMPORT_PENDING │  ← to też jest "historia" (przed activePeriod)
  2026-02  │ ACTIVE         │  ← bieżący miesiąc
  2026-03  │ FORECASTED     │
  ...
  2027-01  │ FORECASTED     │


=== KROK 2: Import #1 - dane tylko do 2025-10 ===
(wciąż SETUP mode)

  2025-01  │ IMPORT_PENDING │ 15 txns ✓
  ...
  2025-10  │ IMPORT_PENDING │ 12 txns ✓
  2025-11  │ IMPORT_PENDING │ empty
  2025-12  │ IMPORT_PENDING │ empty
  2026-01  │ IMPORT_PENDING │ empty
  2026-02  │ ACTIVE         │
  ...


=== KROK 3: attestHistoricalImport ===
confirmedBalance: 35000 PLN
CashFlow status: SETUP → OPEN

WSZYSTKIE IMPORT_PENDING → IMPORTED (niezależnie czy mają dane!)

  2025-01  │ IMPORTED │ 15 txns ✓
  ...
  2025-10  │ IMPORTED │ 12 txns ✓
  2025-11  │ IMPORTED │ 0 txns  ← empty, but IMPORTED (gap)
  2025-12  │ IMPORTED │ 0 txns  ← empty, but IMPORTED (gap)
  2026-01  │ IMPORTED │ 0 txns  ← empty, but IMPORTED (gap)
  2026-02  │ ACTIVE   │ 0 txns
  ...


=== KROK 4: Import #2 - dane za 2025-11 i 2025-12 ===
(Ongoing Sync - gap filling do IMPORTED)

Upload CSV z transakcjami:
  - 8 txns for 2025-11
  - 10 txns for 2025-12

Walidacja:
  ✓ CashFlow jest OPEN
  ✓ Miesiące 2025-11, 2025-12 są IMPORTED
  ✓ Gap filling do IMPORTED dozwolony
  ✓ Balance verification NIE wymagana (gap filling)

Po imporcie (STATUS NIE ZMIENIA SIĘ!):
  2025-01  │ IMPORTED │ 15 txns
  ...
  2025-10  │ IMPORTED │ 12 txns
  2025-11  │ IMPORTED │ 8 txns  ← gap filled, status unchanged
  2025-12  │ IMPORTED │ 10 txns ← gap filled, status unchanged
  2026-01  │ IMPORTED │ 0 txns  ← still gap
  2026-02  │ ACTIVE   │ 0 txns
  ...

→ Recalculate balances from 2025-11 onwards


=== KROK 5: Import #3 - dane za 2026-01 ===
(Ongoing Sync - gap filling do IMPORTED)

Upload CSV:
  - 12 txns for 2026-01

Walidacja:
  ✓ Gap filling do IMPORTED dozwolony
  ✓ Balance verification NIE wymagana

Po imporcie:
  ...
  2025-12  │ IMPORTED │ 10 txns
  2026-01  │ IMPORTED │ 12 txns ← gap filled, status unchanged
  2026-02  │ ACTIVE   │ 0 txns
  ...

→ Recalculate balances from 2026-01 onwards


=== KROK 6: Import #4 - dane za 2026-02 (bieżący) ===
(Ongoing Sync - normal import to ACTIVE)

⚠️ First import this month → BALANCE VERIFICATION REQUIRED!

Upload CSV:
  - 5 txns for 2026-02

Request:
{
  "stagingSessionId": "...",
  "confirmedBalance": { "amount": 45000, "currency": "PLN" }  ← REQUIRED
}

Po imporcie:
  ...
  2026-01  │ IMPORTED │ 12 txns
  2026-02  │ ACTIVE   │ 5 txns  ← ongoing sync, balance verified ✓
  2026-03  │ FORECASTED │
  ...


=== STAN KOŃCOWY ===

  2025-01  │ IMPORTED   │ 15 txns │ historical (original import)
  2025-02  │ IMPORTED   │ 14 txns │ historical (original import)
  ...
  2025-10  │ IMPORTED   │ 12 txns │ historical (original import)
  2025-11  │ IMPORTED   │ 8 txns  │ historical (gap filled later)
  2025-12  │ IMPORTED   │ 10 txns │ historical (gap filled later)
  2026-01  │ IMPORTED   │ 12 txns │ historical (gap filled later)
  2026-02  │ ACTIVE     │ 5 txns  │ current month, balance verified
  2026-03  │ FORECASTED │ 0 txns  │
  ...
  2027-01  │ FORECASTED │ 0 txns  │
```

### 20.3 Kluczowe zasady

#### Zasada 1: Status nie zmienia się przy gap filling

| Przed | Po gap filling | Zmiana statusu? |
|-------|----------------|-----------------|
| IMPORTED | IMPORTED | ❌ NIE |
| ROLLED_OVER | ROLLED_OVER | ❌ NIE |
| ACTIVE | ACTIVE | ❌ NIE |

Status opisuje **jak miesiąc został zamknięty**, nie **ile ma danych**.

#### Zasada 2: Puste miesiące też dostają IMPORTED

Po `attestHistoricalImport`:
- **Wszystkie** `IMPORT_PENDING` → `IMPORTED`
- Niezależnie czy mają transakcje czy nie
- Pozwala to na późniejsze gap filling

#### Zasada 3: Różnica między IMPORTED a ROLLED_OVER

| Status | Jak powstał | Gap filling | Typowe użycie |
|--------|-------------|-------------|---------------|
| `IMPORTED` | attestHistoricalImport | ✓ TAK | Miesiące przed aktywacją |
| `ROLLED_OVER` | Scheduled job / manual rollover | ✓ TAK | Miesiące po aktywacji, auto-zamknięte |

Oba pozwalają na gap filling, ale mają różne pochodzenie.

#### Zasada 4: Balance verification matrix

| Operacja | Status miesiąca | Balance verification |
|----------|-----------------|---------------------|
| Historical import (SETUP) | IMPORT_PENDING | ❌ NIE |
| attestHistoricalImport | IMPORT_PENDING→IMPORTED | ✅ TAK (końcowe saldo) |
| Gap fill | IMPORTED | ❌ NIE |
| Gap fill | ROLLED_OVER | ❌ NIE |
| Ongoing sync (first/month) | ACTIVE | ✅ TAK |
| Ongoing sync (kolejne) | ACTIVE | ❌ NIE |
| Rollover | ACTIVE→ROLLED_OVER | ❌ NIE |

### 20.4 Scenariusz alternatywny: Co jeśli rollover nastąpi w trakcie?

```
Kontynuacja scenariusza...

Po Kroku 5 (2026-01 gap filled), jest 1 marca 2026.
Scheduled job wykonuje rollover:

PRZED rollover:
  2026-01  │ IMPORTED │ 12 txns
  2026-02  │ ACTIVE   │ 0 txns  ← bieżący, ale pusty
  2026-03  │ FORECASTED │
  ...

PO rollover (1 marca 2026, 02:00 UTC):
  2026-01  │ IMPORTED    │ 12 txns
  2026-02  │ ROLLED_OVER │ 0 txns  ← auto-zamknięty, pusty, gap filling OK
  2026-03  │ ACTIVE      │ 0 txns  ← nowy bieżący
  2026-04  │ FORECASTED  │
  ...
  2027-02  │ FORECASTED  │ ← nowy miesiąc dodany

Teraz user wgrywa dane za luty:

Import do ROLLED_OVER (gap filling):
  - 5 txns for 2026-02

Po imporcie:
  2026-02  │ ROLLED_OVER │ 5 txns ← gap filled, status unchanged
  2026-03  │ ACTIVE      │ 0 txns

→ Recalculate balances from 2026-02 onwards
```

### 20.5 Diagram: Przejścia statusów

```
                     ┌─────────────────────────────────────────┐
                     │                                         │
                     │           IMPORT_PENDING                │
                     │         (SETUP mode only)               │
                     │                                         │
                     └────────────────┬────────────────────────┘
                                      │
                                      │ attestHistoricalImport
                                      │ (all IMPORT_PENDING → IMPORTED)
                                      ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                         │
│                              IMPORTED                                   │
│                    (historical, gap filling OK)                         │
│                                                                         │
│  • Created by: attestHistoricalImport                                   │
│  • Gap filling: ✓ allowed, no balance verification                      │
│  • Status never changes after creation                                  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────────┐
│                                                                         │
│                               ACTIVE                                    │
│                         (current month)                                 │
│                                                                         │
│  • Only ONE month can be ACTIVE                                         │
│  • Ongoing sync: ✓ allowed                                              │
│  • Balance verification: required for first import per month            │
│                                                                         │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
                                 │ rollover (scheduled or manual)
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                         │
│                            ROLLED_OVER                                  │
│                   (auto-closed, gap filling OK)                         │
│                                                                         │
│  • Created by: scheduled job or manual rollover                         │
│  • Gap filling: ✓ allowed, no balance verification                      │
│  • Status never changes after creation                                  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────────┐
│                                                                         │
│                             FORECASTED                                  │
│                          (future months)                                │
│                                                                         │
│  • Import: ✗ NOT allowed (future)                                       │
│  • Becomes ACTIVE when calendar reaches this month                      │
│  • Always 11 months ahead maintained                                    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────────┐
│                                                                         │
│                              ATTESTED                                   │
│                        (DEPRECATED/LEGACY)                              │
│                                                                         │
│  • Created by: old MakeMonthlyAttestationCommand (removed)              │
│  • Gap filling: ✗ NOT allowed (read-only)                               │
│  • Only exists for backward compatibility with old data                 │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 20.6 Podsumowanie: Co się dzieje przy imporcie

| Import do miesiąca | Status miesiąca | Dozwolone? | Balance verification | Status po imporcie |
|--------------------|-----------------|------------|---------------------|-------------------|
| IMPORT_PENDING | SETUP mode | ✅ TAK | ❌ NIE | IMPORT_PENDING |
| IMPORTED | OPEN mode | ✅ TAK (gap fill) | ❌ NIE | IMPORTED (bez zmian) |
| ROLLED_OVER | OPEN mode | ✅ TAK (gap fill) | ❌ NIE | ROLLED_OVER (bez zmian) |
| ACTIVE | OPEN mode | ✅ TAK (ongoing) | ✅ TAK (first/month) | ACTIVE (bez zmian) |
| FORECASTED | OPEN mode | ❌ NIE | N/A | N/A |
| ATTESTED | OPEN mode | ❌ NIE (legacy) | N/A | N/A |

---

## 20.7 Statusy CashFlow vs statusy miesięcy - pełna macierz

### CashFlow Status

```java
public enum CashFlowStatus {
    SETUP,   // Historical Backfill mode - initial setup, only historical imports
    OPEN,    // Active CashFlow - ongoing sync, gap filling, rollover
    CLOSED   // Archived - read only, no operations allowed
}
```

### Macierz: Operacje i zmiany statusów

| Operacja | CashFlow PRZED | CashFlow PO | Miesiąc PRZED | Miesiąc PO |
|----------|----------------|-------------|---------------|------------|
| `createCashFlow` | - | **SETUP** | - | ACTIVE + FORECASTED |
| `createCashFlowWithHistory` | - | **SETUP** | - | IMPORT_PENDING + ACTIVE + FORECASTED |
| Historical import (staging + import) | SETUP | SETUP | IMPORT_PENDING | IMPORT_PENDING |
| `attestHistoricalImport` | **SETUP** | **OPEN** | IMPORT_PENDING | **IMPORTED** |
| Gap filling (to IMPORTED) | OPEN | OPEN | IMPORTED | IMPORTED |
| Gap filling (to ROLLED_OVER) | OPEN | OPEN | ROLLED_OVER | ROLLED_OVER |
| Ongoing sync (to ACTIVE) | OPEN | OPEN | ACTIVE | ACTIVE |
| Rollover (scheduled/manual) | OPEN | OPEN | ACTIVE → | **ROLLED_OVER**, next FORECASTED → **ACTIVE** |
| `closeCashFlow` (future) | OPEN | **CLOSED** | any | unchanged |

### Diagram: CashFlow Status transitions

```
     ┌─────────────────────────────────────────────────────────────────────┐
     │                                                                     │
     │                          ┌─────────┐                                │
     │    createCashFlow        │         │    createCashFlowWithHistory   │
     │    ─────────────────────►│  SETUP  │◄───────────────────────────    │
     │                          │         │                                │
     │                          └────┬────┘                                │
     │                               │                                     │
     │                               │ attestHistoricalImport              │
     │                               │ (or first import for simple CF)     │
     │                               ▼                                     │
     │                          ┌─────────┐                                │
     │                          │         │                                │
     │                          │  OPEN   │◄─── ongoing sync, gap filling  │
     │                          │         │     rollover (no status change)│
     │                          └────┬────┘                                │
     │                               │                                     │
     │                               │ closeCashFlow (future feature)      │
     │                               ▼                                     │
     │                          ┌─────────┐                                │
     │                          │         │                                │
     │                          │ CLOSED  │                                │
     │                          │         │                                │
     │                          └─────────┘                                │
     │                                                                     │
     └─────────────────────────────────────────────────────────────────────┘
```

### Scenariusz z oboma statusami - krok po kroku

```
=== createCashFlowWithHistory ===
CashFlow: - → SETUP
Months:
  2025-01 → 2026-01: - → IMPORT_PENDING
  2026-02:           - → ACTIVE
  2026-03 → 2027-01: - → FORECASTED

=== Historical import (multiple files) ===
CashFlow: SETUP → SETUP (no change)
Months:   IMPORT_PENDING → IMPORT_PENDING (no change, just data added)

=== attestHistoricalImport ===
CashFlow: SETUP → OPEN  ⬅️ ZMIANA!
Months:
  ALL IMPORT_PENDING → IMPORTED  ⬅️ ZMIANA!
  ACTIVE stays ACTIVE
  FORECASTED stays FORECASTED

=== Ongoing sync (gap filling to IMPORTED) ===
CashFlow: OPEN → OPEN (no change)
Months:   IMPORTED → IMPORTED (no change, just data added)

=== Ongoing sync (to ACTIVE, first per month) ===
CashFlow: OPEN → OPEN (no change)
Months:   ACTIVE → ACTIVE (no change, balance verified)

=== Rollover (scheduled or manual) ===
CashFlow: OPEN → OPEN (no change)
Months:
  ACTIVE → ROLLED_OVER  ⬅️ ZMIANA!
  next FORECASTED → ACTIVE  ⬅️ ZMIANA!
  new FORECASTED added at end

=== Ongoing sync (gap filling to ROLLED_OVER) ===
CashFlow: OPEN → OPEN (no change)
Months:   ROLLED_OVER → ROLLED_OVER (no change, just data added)
```

### Tabela: Które operacje zmieniają status CashFlow

| Operacja | Zmienia CashFlow status? | Z | Na |
|----------|--------------------------|---|-----|
| createCashFlow | ✅ TAK | - | SETUP |
| createCashFlowWithHistory | ✅ TAK | - | SETUP |
| Historical import | ❌ NIE | SETUP | SETUP |
| attestHistoricalImport | ✅ TAK | SETUP | **OPEN** |
| Gap filling | ❌ NIE | OPEN | OPEN |
| Ongoing sync | ❌ NIE | OPEN | OPEN |
| Rollover | ❌ NIE | OPEN | OPEN |
| closeCashFlow | ✅ TAK | OPEN | **CLOSED** |

### Tabela: Które operacje zmieniają status miesiąca

| Operacja | Zmienia status miesiąca? | Z | Na |
|----------|--------------------------|---|-----|
| createCashFlow | ✅ TAK | - | ACTIVE, FORECASTED |
| createCashFlowWithHistory | ✅ TAK | - | IMPORT_PENDING, ACTIVE, FORECASTED |
| Historical import | ❌ NIE | IMPORT_PENDING | IMPORT_PENDING |
| attestHistoricalImport | ✅ TAK | IMPORT_PENDING | **IMPORTED** |
| Gap filling (IMPORTED) | ❌ NIE | IMPORTED | IMPORTED |
| Gap filling (ROLLED_OVER) | ❌ NIE | ROLLED_OVER | ROLLED_OVER |
| Ongoing sync (ACTIVE) | ❌ NIE | ACTIVE | ACTIVE |
| Rollover | ✅ TAK | ACTIVE | **ROLLED_OVER**, FORECASTED | **ACTIVE** |

### Dozwolone operacje per CashFlow status

| CashFlow Status | Dozwolone operacje |
|-----------------|-------------------|
| **SETUP** | `importHistoricalCashChange`, `attestHistoricalImport`, `createCategory`, `editCashChange` |
| **OPEN** | Ongoing sync, Gap filling, Rollover, `appendCashChange`, `confirmCashChange`, `editCashChange`, `createCategory` |
| **CLOSED** | Tylko odczyt (read-only) |

### Dozwolone operacje per Month status

| Month Status | Import allowed? | Edit allowed? | Notes |
|--------------|-----------------|---------------|-------|
| **IMPORT_PENDING** | ✅ Historical only | ✅ | Only in SETUP mode |
| **IMPORTED** | ✅ Gap filling | ✅ | After attestation |
| **ROLLED_OVER** | ✅ Gap filling | ✅ | After rollover |
| **ACTIVE** | ✅ Ongoing sync | ✅ | Current month |
| **FORECASTED** | ❌ | ✅ (expected txns) | Future planning only |
| **ATTESTED** | ❌ Legacy | ❌ Legacy | Deprecated, read-only |

---

## 21. Category Mappings - trwałość i reużycie

### 21.1 Obecna implementacja

**CategoryMapping jest TRWAŁY (persistent):**

```java
@Document("category_mappings")
@CompoundIndex(name = "cashflow_bank_category_type_idx",
               def = "{'cashFlowId': 1, 'bankCategoryName': 1, 'categoryType': 1}",
               unique = true)
public class CategoryMappingEntity {
    private String mappingId;
    private String cashFlowId;      // ← powiązanie z CashFlow
    private String bankCategoryName; // np. "Biedronka"
    private String targetCategoryName; // np. "Groceries"
    private Type categoryType;       // INFLOW / OUTFLOW
    private MappingAction action;
    // ... timestamps
}
```

| Aspekt | Wartość |
|--------|---------|
| **Przechowywanie** | MongoDB, kolekcja `category_mappings` |
| **Trwałość** | ✅ Mappingi są trwałe (nie znikają!) |
| **TTL** | ❌ Brak - mappingi nie wygasają |
| **Powiązanie** | Per CashFlow (`cashFlowId`) |
| **Unikalność** | `(cashFlowId, bankCategoryName, categoryType)` - unique index |
| **Reużycie** | ✅ TAK - automatycznie przy kolejnych importach |
| **Scope** | Tylko dla danego CashFlow (nie globalne między CashFlows) |

### 21.2 Flow przy importach CSV

```
=== IMPORT #1: Pierwszy plik CSV ===

User wgrywa CSV z kategoriami: "Biedronka", "Żabka", "Netflix"

1. StageTransactionsCommandHandler:
   mappings = categoryMappingRepository.findByCashFlowId(cashFlowId)
   → Wynik: [] (puste - brak mappingów)

2. Dla każdej transakcji:
   mapping = mappingMap.get("Biedronka", OUTFLOW)
   → null (brak mappingu)
   → Status: PENDING_MAPPING

3. Response: HAS_UNMAPPED_CATEGORIES
   unmappedCategories: ["Biedronka", "Żabka", "Netflix"]

4. User konfiguruje mappingi (endpoint POST /mappings):
   "Biedronka" → "Groceries" (MAP_TO_EXISTING)
   "Żabka"     → "Groceries" (MAP_TO_EXISTING)
   "Netflix"   → "Entertainment" (CREATE_NEW)

5. Mappingi ZAPISANE w MongoDB (trwale!)

6. Revalidate + Import → sukces


=== IMPORT #2: Kolejny plik CSV (np. miesiąc później) ===

User wgrywa CSV z kategoriami: "Biedronka", "Allegro", "Spotify"

1. StageTransactionsCommandHandler:
   mappings = categoryMappingRepository.findByCashFlowId(cashFlowId)
   → Wynik: [Biedronka→Groceries, Żabka→Groceries, Netflix→Entertainment]

2. Dla transakcji "Biedronka":
   mapping = mappingMap.get("Biedronka", OUTFLOW)
   → ✅ ZNALEZIONO! → Groceries
   → Status: VALID (automatycznie zmapowana)

3. Dla transakcji "Allegro" (nowa):
   mapping = mappingMap.get("Allegro", OUTFLOW)
   → null (brak mappingu)
   → Status: PENDING_MAPPING

4. Dla transakcji "Spotify" (nowa):
   → Status: PENDING_MAPPING

5. Response: HAS_UNMAPPED_CATEGORIES
   - "Biedronka" już zmapowana automatycznie ✅
   - unmappedCategories: ["Allegro", "Spotify"]

6. User konfiguruje tylko NOWE mappingi:
   "Allegro" → "Shopping" (CREATE_NEW)
   "Spotify" → "Entertainment" (MAP_TO_EXISTING)

7. Revalidate + Import → sukces


=== IMPORT #3, #4, ... ===

Każdy kolejny import:
- Znane kategorie → automatycznie zmapowane ✅
- Nowe kategorie → wymagają jednorazowej konfiguracji
- Mappingi kumulują się z czasem
```

### 21.3 Diagram: Akumulacja mappingów

```
Import #1:                    Import #2:                    Import #3:
┌─────────────────────┐      ┌─────────────────────┐      ┌─────────────────────┐
│ Biedronka → ?       │      │ Biedronka → Groceries ✓│   │ Biedronka → Groceries ✓│
│ Żabka → ?           │      │ Żabka → Groceries ✓    │   │ Żabka → Groceries ✓    │
│ Netflix → ?         │      │ Netflix → Entertainment✓│   │ Netflix → Entertainment✓│
└─────────────────────┘      │ Allegro → ?            │   │ Allegro → Shopping ✓   │
         │                   │ Spotify → ?            │   │ Spotify → Entertainment✓│
         │ configure         └─────────────────────┘   │ Lidl → ?              │
         ▼                            │                   └─────────────────────┘
┌─────────────────────┐              │ configure                  │
│ Biedronka→Groceries │              ▼                            │ configure
│ Żabka→Groceries     │      ┌─────────────────────┐              ▼
│ Netflix→Entertainment│      │ + Allegro→Shopping  │      ┌─────────────────────┐
└─────────────────────┘      │ + Spotify→Entertain.│      │ + Lidl→Groceries    │
                              └─────────────────────┘      └─────────────────────┘

Mappings in DB:              Mappings in DB:              Mappings in DB:
3 mappings                   5 mappings                   6 mappings
```

### 21.4 Co to oznacza dla usera

| Import | Akcja usera | Automatycznie zmapowane |
|--------|-------------|------------------------|
| **Pierwszy** | Skonfigurować wszystkie kategorie | 0% |
| **Drugi** | Tylko nowe kategorie | ~70-90% (znane) |
| **Trzeci+** | Tylko nowe kategorie | ~90-99% (większość znana) |

**Wniosek:** Im więcej importów, tym mniej pracy dla usera. System "uczy się" kategorii.

### 21.5 Zarządzanie mappingami

**Dostępne operacje:**

| Endpoint | Opis |
|----------|------|
| `GET /mappings` | Pobranie wszystkich mappingów dla CashFlow |
| `POST /mappings` | Konfiguracja mappingów (batch) |
| `DELETE /mappings/{mappingId}` | Usunięcie pojedynczego mappingu |
| `DELETE /mappings` | Usunięcie wszystkich mappingów dla CashFlow |

**Kiedy usunąć mapping?**
- Gdy kategoria bankowa zmieniła znaczenie
- Gdy chcesz zmienić target category
- Przy rollback importu (opcjonalnie)

### 21.6 Ograniczenia obecnej implementacji

| Ograniczenie | Opis | Przyszłe rozwiązanie |
|--------------|------|---------------------|
| **Per CashFlow** | Mappingi nie są dzielone między CashFlows | Global/shared mappings (sekcja Smart Categorization w canonical-csv-architecture.md) |
| **Exact match** | "Biedronka Kraków" ≠ "Biedronka Warszawa" | Fuzzy matching (Smart Categorization) |
| **Brak MCC** | Nie wykorzystuje Merchant Category Code | MCC-based suggestions (przyszła faza) |

### 21.7 Powiązanie z Ongoing Sync

Mappingi działają identycznie dla:
- **Historical Backfill** (SETUP mode)
- **Ongoing Sync** (OPEN mode)
- **Gap Filling** (OPEN mode, do IMPORTED/ROLLED_OVER)

Nie ma różnicy w logice mappingów - zawsze są reużywane.

---

## 22. Podsumowanie potwierdzonego designu

### 22.1 Potwierdzone decyzje

| # | Decyzja | Status |
|---|---------|--------|
| 1 | Nowy status `ROLLED_OVER` z dozwolonym gap filling | ✅ Potwierdzone |
| 2 | Scheduled job o 02:00 UTC 1-go dnia miesiąca | ✅ Potwierdzone |
| 3 | Manual rollover endpoint `POST /cash-flow/{id}/rollover` | ✅ Potwierdzone |
| 4 | `IMPORTED` pozwala na gap filling (jak `ROLLED_OVER`) | ✅ Potwierdzone |
| 5 | Gap filling nie zmienia statusu miesiąca | ✅ Potwierdzone |
| 6 | Gap filling nie wymaga balance verification | ✅ Potwierdzone |
| 7 | Balance verification tylko: attestation + first import to ACTIVE per month | ✅ Potwierdzone |
| 8 | Wielokrotny rollover (catch-up) w jednym request | ✅ Potwierdzone |
| 9 | Puste historyczne miesiące po attestation mają status IMPORTED | ✅ Potwierdzone |
| 10 | `balance_after` w CSV - do przyszłej fazy (nie implementować teraz) | ✅ Potwierdzone |
| 11 | Usunięcie Month Attestation (deprecated) | ✅ Potwierdzone |

### 22.2 Zaktualizowany plan PR-ów

```
PR-1 (Domain Events & Status)
  │
  ├──► PR-2 (Rollover Command & Handler)
  │      │
  │      └──► PR-3 (Scheduled Job + Manual Endpoint)  ← rozszerzony o manual rollover
  │
  └──► PR-4 (Ongoing Sync Validation)  ← IMPORTED też pozwala gap filling
         │
         └──► PR-5 (Balance Verification)  ← tylko attestation + first/month ACTIVE
                │
                └──► PR-6 (Gap Filling & Recalculation)
                       │
                       └──► PR-7 (Remove Month Attestation)
```

### 22.3 Zaktualizowany plan PR-ów (po analizie)

Po dogłębnej analizie kodu i testów, oto zaktualizowany plan implementacji:

#### PR-1: Domain - MonthRolledOverEvent & Status ROLLED_OVER

| Plik | Zmiana | Rozmiar |
|------|--------|---------|
| `CashFlowMonthlyForecast.java` | Dodać `ROLLED_OVER` do enum `Status` | ~5 linii |
| `CashFlowEvent.java` | Dodać `MonthRolledOverEvent` do sealed interface | ~20 linii |
| `CashFlow.java` | Dodać `apply(MonthRolledOverEvent)` | ~10 linii |
| `CashFlowAggregateProjector.java` | Obsłużyć `MonthRolledOverEvent` | ~10 linii |
| `CashFlowEventListener.java` | Kafka mapping dla `MonthRolledOverEvent` | ~15 linii |

**Testy do aktualizacji (PR-1):**
- `CashFlowAggregateTest.java` - dodać test dla `MonthRolledOverEvent`
- `CashFlowForecastProcessorTest.java` - dodać test przetwarzania eventu

---

#### PR-2: Rollover Command & Handler + Event Handler

| Plik | Akcja | Rozmiar |
|------|-------|---------|
| `RolloverMonthCommand.java` | **CREATE** | ~15 linii |
| `RolloverMonthCommandHandler.java` | **CREATE** | ~50 linii |
| `MonthRolledOverEventHandler.java` | **CREATE** (wzór: `MonthAttestedEventHandler.java`) | ~80 linii |
| `CashFlowForecastProcessor.java` | Zarejestrować handler | ~5 linii |

**Logika `MonthRolledOverEventHandler`:**
```java
// Bazowane na MonthAttestedEventHandler.java:26-60
1. ACTIVE → ROLLED_OVER (zmiana statusu)
2. FORECASTED → ACTIVE (następny miesiąc)
3. moveExpectedCashChangesToNextMonth() (przenieś nierozliczone)
4. addNextForecastAtTheTop() (dodaj nowy FORECASTED)
5. updateStats()
```

**Testy do utworzenia (PR-2):**
- `RolloverMonthCommandHandlerTest.java` - unit test
- `MonthRolledOverEventHandlerTest.java` - unit test
- Test integracyjny w `CashFlowForecastProcessorTest.java`

---

#### PR-3: Scheduled Job + Manual Rollover Endpoint

| Plik | Akcja | Rozmiar |
|------|-------|---------|
| `MonthlyRolloverScheduler.java` | **CREATE** | ~60 linii |
| `DomainCashFlowRepository.java` | Dodać `findOpenCashFlowsNeedingRollover()` | ~5 linii |
| `CashFlowMongoRepository.java` | Implementacja query | ~10 linii |
| `CashFlowRestController.java` | `POST /{id}/rollover` endpoint | ~40 linii |
| `CashFlowDto.java` | `ManualRolloverRequest`, `RolloverResponse` | ~30 linii |
| `application.properties` | `vidulum.rollover.cron` | ~2 linie |

**Testy do utworzenia (PR-3):**
- `MonthlyRolloverSchedulerTest.java` - test scheduled job (mockować repo)
- Test endpointu `/rollover` w `CashFlowControllerTest.java`

---

#### PR-4: Ongoing Sync - Zmiana walidacji w StageTransactionsCommandHandler

| Plik | Zmiana | Rozmiar |
|------|--------|---------|
| `StageTransactionsCommandHandler.java:190-218` | Nowa logika walidacji | ~50 linii |
| `CashFlowInfo.java` | Dodać `isInOpenMode()`, `getMonthStatus(YearMonth)` | ~20 linii |
| `CashFlowServiceClient.java` | Rozszerzyć o month statuses | ~15 linii |
| `TestCashFlowServiceClient.java` | Zaktualizować | ~20 linii |

**Obecna walidacja (do zmiany):**
```java
// StageTransactionsCommandHandler.java:191-193
if (!cashFlowInfo.isInSetupMode()) {
    errors.add("CashFlow is not in SETUP mode");  // ← USUNĄĆ
}
```

**Nowa walidacja:**
```java
// SETUP mode → tylko przed activePeriod (IMPORT_PENDING)
// OPEN mode → ACTIVE, ROLLED_OVER, IMPORTED - dozwolone
//           → FORECASTED - zabronione
// CLOSED mode → wszystko zabronione
```

**Testy do aktualizacji (PR-4):**
- `BankDataIngestionHttpIntegrationTest.java` - dodać test Ongoing Sync
- `StageTransactionsCommandHandlerTest.java` - jeśli istnieje

---

#### PR-5: Balance Verification (raz na miesiąc)

| Plik | Zmiana | Rozmiar |
|------|--------|---------|
| `BankDataIngestionDto.java` | Rozszerzyć request/response | ~30 linii |
| `StartImportJobCommandHandler.java` | Logika weryfikacji salda | ~40 linii |
| `BalanceVerificationRequiredException.java` | **CREATE** | ~15 linii |
| `CashFlowMonthlyForecast.java` | Dodać `balanceVerifiedAt`, `verifiedBalance` | ~10 linii |

**Testy do utworzenia (PR-5):**
- Test balance verification w `BankDataIngestionHttpIntegrationTest.java`

---

#### PR-6: Gap Filling & Recalculation

| Plik | Zmiana | Rozmiar |
|------|--------|---------|
| `CashFlowForecastStatement.java` | `recalculateBalancesFrom(YearMonth)` | ~30 linii |
| `HistoricalCashChangeImportedEventHandler.java` | Obsłużyć ROLLED_OVER | ~20 linii |
| Import handlers | Wywołać recalculation | ~10 linii |

**Testy do utworzenia (PR-6):**
- Test gap filling w `CashFlowForecastProcessorTest.java`
- Test recalculation

---

#### PR-7: Remove Month Attestation (deprecation)

| Plik | Akcja | Rozmiar |
|------|-------|---------|
| `MakeMonthlyAttestationCommand.java` | **DELETE** | - |
| `MakeMonthlyAttestationCommandHandler.java` | **DELETE** | - |
| `MonthAttestedEventHandler.java` | **DELETE** (ale ZOSTAWIĆ jako deprecated) | - |
| `CashFlowRestController.java` | Usunąć/deprecate endpoint | ~10 linii |

**⚠️ WAŻNE:** Zachować wsteczną kompatybilność:
- `CashFlowMonthlyForecast.Status.ATTESTED` - zachować dla istniejących danych
- `MonthAttestedEvent` w sealed interface - zachować dla deserializacji
- Event handler może być deprecated ale musi działać dla starych eventów

**Testy do aktualizacji (PR-7):**
- Testy używające `attestMonth()` trzeba zaktualizować lub oznaczyć jako legacy

---

### 22.4 Analiza testów do zmiany

#### Testy używające `attestMonth` / `ATTESTED`:

| Plik | Lokalizacja | Zmiana |
|------|-------------|--------|
| `DualCashflowStatementGeneratorWithHistory.java` | linie 219-220 | Zmienić na `rolloverMonth()` |
| `DualCashflowStatementGenerator.java` | używa `attestMonth` | Zmienić na `rolloverMonth()` |
| `CashFlowForecastStatementGenerator.java` | używa `attestMonth` | Zmienić na `rolloverMonth()` |
| `CashflowStatementViaAIGenerator.java` | używa `attestMonth` | Zmienić na `rolloverMonth()` |
| `CashFlowAggregateTest.java` | linie 885, 1281 | Zachować jako legacy test |
| `CashFlowForecastProcessorTest.java` | `attestation_processing.json` | Zachować jako legacy test |
| `CashFlowForecastMapperTest.java` | sprawdza ATTESTED | Zachować + dodać ROLLED_OVER test |

#### Nowy test: `DualCashflowStatementGeneratorWithRolledOver.java`

**Cel:** Wygenerować CashFlow z miesiącami w statusie `ROLLED_OVER` (nie `ATTESTED`)

**Bazowany na:** `DualCashflowStatementGeneratorWithHistory.java` (1703 linie)

**Różnice:**
1. Zamiast `actor.attestMonth()` użyć `actor.rolloverMonth()` (nowa metoda)
2. Sprawdzać status `ROLLED_OVER` zamiast `ATTESTED`
3. Dodać scenariusz gap filling do `ROLLED_OVER`
4. Przetestować manual rollover endpoint

**Struktura nowego testu:**
```java
@Test
public void generateDualCashflowsWithRolledOver() {
    // PHASE 1: Create with history, import historical data
    // PHASE 2: Attest historical import (IMPORTED status)
    // PHASE 3: Generate transactions for current + future months
    // PHASE 4: Rollover months (ROLLED_OVER status) - NOWE!
    // PHASE 5: Gap filling to ROLLED_OVER months - NOWE!
    // PHASE 6: Verify timeline: IMPORTED → ROLLED_OVER → ACTIVE → FORECASTED
}
```

---

### 22.5 Potwierdzenie: Nowy test jest wykonalny

**TAK, mogę utworzyć `DualCashflowStatementGeneratorWithRolledOver.java`:**

1. ✅ Bazowy test `DualCashflowStatementGeneratorWithHistory.java` jest dostępny (1703 linie)
2. ✅ Struktura testów jest dobrze zdefiniowana (PHASE 1-4)
3. ✅ `DualBudgetActor` można rozszerzyć o `rolloverMonth()` metodę
4. ✅ Test może weryfikować mieszany timeline:
   - `IMPORTED` (historical months)
   - `ROLLED_OVER` (auto-closed months)
   - `ACTIVE` (current month)
   - `FORECASTED` (future months)

**Plan:**
1. Skopiować `DualCashflowStatementGeneratorWithHistory.java`
2. Zmienić nazwę na `DualCashflowStatementGeneratorWithRolledOver.java`
3. Dodać metodę `rolloverMonth()` do `DualBudgetActor`
4. Zamienić wywołania `attestMonth()` na `rolloverMonth()`
5. Dodać PHASE dla gap filling
6. Zaktualizować asercje (ROLLED_OVER zamiast ATTESTED)

---

---

## 23. Przewodnik integracji UI (Frontend Integration Guide)

### 23.1 Podsumowanie zmian dla aplikacji UI

#### Co się zmienia

| Aspekt | Było (stare) | Jest (nowe) |
|--------|--------------|-------------|
| **Zamykanie miesiąca** | Manual `attestMonth()` | Automatic rollover (scheduled job) |
| **Status zamkniętego miesiąca** | `ATTESTED` | `ROLLED_OVER` |
| **Import po aktywacji** | ❌ Zablokowany | ✅ Dozwolony (Ongoing Sync) |
| **Gap filling** | ❌ Niedostępny | ✅ Do IMPORTED i ROLLED_OVER |
| **Balance verification** | Przy każdej attestation | Raz na miesiąc (first import to ACTIVE) |
| **Manual rollover** | Nie istniał | ✅ Nowy endpoint |

#### Nowe endpointy

| Endpoint | Metoda | Opis |
|----------|--------|------|
| `/api/v1/cash-flow/{id}/rollover` | POST | Manual rollover miesiąca |

#### Deprecated endpointy (do usunięcia w przyszłości)

| Endpoint | Metoda | Status |
|----------|--------|--------|
| `/api/v1/cash-flow/{id}/attest-month` | POST | ⚠️ DEPRECATED - używać rollover |

---

### 23.2 Statusy miesięcy - co UI powinien wyświetlać

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         TIMELINE CASHFLOW                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  IMPORTED        IMPORTED      ROLLED_OVER    ACTIVE       FORECASTED      │
│  ┌─────────┐    ┌─────────┐    ┌─────────┐   ┌─────────┐   ┌─────────┐     │
│  │ 2025-10 │    │ 2025-11 │    │ 2025-12 │   │ 2026-01 │   │ 2026-02 │     │
│  │         │    │         │    │         │   │         │   │         │     │
│  │ 📊 45txn│    │ 📊 38txn│    │ 📊 42txn│   │ 📊 12txn│   │ 📊 0txn │     │
│  │ ✅ Done │    │ ✅ Done │    │ ✅ Done │   │ 🔵 Active│   │ ⏳ Future│     │
│  │ +upload │    │ +upload │    │ +upload │   │ +upload │   │ ❌ locked│     │
│  └─────────┘    └─────────┘    └─────────┘   └─────────┘   └─────────┘     │
│                                                                             │
│  Historia (gap filling OK)                 Bieżący       Przyszłość        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Legenda statusów dla UI

| Status | Ikona | Kolor | Label | Akcje dostępne |
|--------|-------|-------|-------|----------------|
| `IMPORT_PENDING` | ⏳ | żółty | "Awaiting import" | Upload CSV |
| `IMPORTED` | ✅ | niebieski | "Historical" | Upload CSV (gap fill) |
| `ROLLED_OVER` | ✅ | zielony | "Completed" | Upload CSV (gap fill) |
| `ACTIVE` | 🔵 | niebieski | "Current month" | Upload CSV |
| `FORECASTED` | ⏳ | szary | "Future" | View only |
| `ATTESTED` | ✅ | szary | "Legacy" | View only |

---

### 23.3 Flow importu CSV - kompletny przewodnik

#### Scenariusz A: Pierwszy import (SETUP mode)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    FLOW: HISTORICAL BACKFILL (SETUP MODE)                   │
└─────────────────────────────────────────────────────────────────────────────┘

KROK 1: Utwórz CashFlow z historią
═══════════════════════════════════

POST /api/v1/cash-flow/with-history
{
  "userId": "user123",
  "name": "Home Budget",
  "description": "Personal finances",
  "bankAccount": {
    "bankName": "PKO BP",
    "bankAccountNumber": {
      "account": "PL12345678901234567890123456",
      "denomination": { "id": "PLN" }
    },
    "balance": { "amount": 15000.00, "currency": "PLN" }
  },
  "startPeriod": "2025-07",
  "initialBalance": { "amount": 10000.00, "currency": "PLN" }
}

Response (201 Created):
{
  "cashFlowId": "cf-123-456"
}

Stan po utworzeniu:
  CashFlow status: SETUP
  Miesiące:
    2025-07: IMPORT_PENDING
    2025-08: IMPORT_PENDING
    ...
    2025-12: IMPORT_PENDING
    2026-01: ACTIVE
    2026-02 - 2026-12: FORECASTED


KROK 2: Wgraj plik CSV (stage transactions)
═══════════════════════════════════════════

POST /api/v1/bank-data-ingestion/{cashFlowId}/stage
{
  "transactions": [
    {
      "bankTransactionId": "PKO-2025-07-001",
      "name": "Wypłata",
      "description": "Przelew z firmy XYZ",
      "bankCategory": "Wpływy regularne",
      "amount": 8500.00,
      "currency": "PLN",
      "type": "INFLOW",
      "paidDate": "2025-07-10T10:00:00Z"
    },
    {
      "bankTransactionId": "PKO-2025-07-002",
      "name": "Biedronka Warszawa",
      "description": "Zakupy spożywcze",
      "bankCategory": "Zakupy kartą",
      "amount": 245.50,
      "currency": "PLN",
      "type": "OUTFLOW",
      "paidDate": "2025-07-12T15:30:00Z"
    },
    // ... więcej transakcji
  ]
}

Response (200 OK) - PIERWSZY IMPORT, brak mappingów:
{
  "stagingSessionId": "stage-789",
  "cashFlowId": "cf-123-456",
  "status": "HAS_UNMAPPED_CATEGORIES",      // ← Wymaga konfiguracji mappingów
  "expiresAt": "2026-01-02T10:00:00Z",
  "summary": {
    "totalTransactions": 45,
    "validTransactions": 0,
    "invalidTransactions": 0,
    "duplicateTransactions": 0
  },
  "unmappedCategories": [
    { "bankCategory": "Wpływy regularne", "count": 6, "type": "INFLOW" },
    { "bankCategory": "Zakupy kartą", "count": 28, "type": "OUTFLOW" },
    { "bankCategory": "Przelewy wychodzące", "count": 8, "type": "OUTFLOW" },
    { "bankCategory": "Opłaty i prowizje", "count": 3, "type": "OUTFLOW" }
  ]
}


KROK 3: Skonfiguruj mappingi kategorii
══════════════════════════════════════

POST /api/v1/bank-data-ingestion/{cashFlowId}/mappings
{
  "mappings": [
    {
      "bankCategoryName": "Wpływy regularne",
      "action": "CREATE_NEW",
      "targetCategoryName": "Salary",
      "categoryType": "INFLOW"
    },
    {
      "bankCategoryName": "Zakupy kartą",
      "action": "CREATE_NEW",
      "targetCategoryName": "Groceries",
      "categoryType": "OUTFLOW"
    },
    {
      "bankCategoryName": "Przelewy wychodzące",
      "action": "CREATE_NEW",
      "targetCategoryName": "Bills",
      "categoryType": "OUTFLOW"
    },
    {
      "bankCategoryName": "Opłaty i prowizje",
      "action": "CREATE_NEW",
      "targetCategoryName": "Bank Fees",
      "categoryType": "OUTFLOW"
    }
  ]
}

Response (200 OK):
{
  "cashFlowId": "cf-123-456",
  "mappingsConfigured": 4,
  "mappings": [
    {
      "mappingId": "map-001",
      "bankCategoryName": "Wpływy regularne",
      "targetCategoryName": "Salary",
      "categoryType": "INFLOW",
      "action": "CREATE_NEW",
      "status": "CREATED"
    },
    // ... pozostałe mappingi
  ]
}

⚠️ WAŻNE: Mappingi są TRWAŁE i będą reużywane przy kolejnych importach!


KROK 4: Revalidate staging (opcjonalnie sprawdź preview)
═══════════════════════════════════════════════════════

POST /api/v1/bank-data-ingestion/{cashFlowId}/staging/{stagingSessionId}/revalidate

Response (200 OK):
{
  "stagingSessionId": "stage-789",
  "cashFlowId": "cf-123-456",
  "status": "READY_FOR_IMPORT",              // ← Teraz gotowe do importu!
  "summary": {
    "totalTransactions": 45,
    "validTransactions": 45,
    "invalidTransactions": 0,
    "duplicateTransactions": 0
  },
  "categoryBreakdown": [
    {
      "targetCategory": "Salary",
      "transactionCount": 6,
      "totalAmount": 51000.00,
      "currency": "PLN",
      "type": "INFLOW",
      "isNewCategory": true
    },
    {
      "targetCategory": "Groceries",
      "transactionCount": 28,
      "totalAmount": 3420.50,
      "currency": "PLN",
      "type": "OUTFLOW",
      "isNewCategory": true
    }
    // ...
  ],
  "monthlyBreakdown": [
    { "month": "2025-07", "inflowTotal": 8500.00, "outflowTotal": 2150.00, "transactionCount": 12 },
    { "month": "2025-08", "inflowTotal": 8500.00, "outflowTotal": 1890.00, "transactionCount": 11 },
    // ...
  ],
  "categoriesToCreate": [
    { "name": "Salary", "parent": null, "type": "INFLOW" },
    { "name": "Groceries", "parent": null, "type": "OUTFLOW" },
    { "name": "Bills", "parent": null, "type": "OUTFLOW" },
    { "name": "Bank Fees", "parent": null, "type": "OUTFLOW" }
  ]
}


KROK 5: Rozpocznij import
═════════════════════════

POST /api/v1/bank-data-ingestion/{cashFlowId}/import
{
  "stagingSessionId": "stage-789"
}

Response (202 Accepted):
{
  "jobId": "job-456",
  "cashFlowId": "cf-123-456",
  "stagingSessionId": "stage-789",
  "status": "IN_PROGRESS",
  "pollUrl": "/api/v1/bank-data-ingestion/cf-123-456/import/job-456"
}


KROK 6: Sprawdź status importu (polling)
════════════════════════════════════════

GET /api/v1/bank-data-ingestion/{cashFlowId}/import/{jobId}

Response (200 OK) - w trakcie:
{
  "jobId": "job-456",
  "status": "IN_PROGRESS",
  "progress": {
    "percentage": 65,
    "currentPhase": "IMPORTING_TRANSACTIONS",
    "phases": [
      { "name": "CREATING_CATEGORIES", "status": "COMPLETED", "processed": 4, "total": 4 },
      { "name": "IMPORTING_TRANSACTIONS", "status": "IN_PROGRESS", "processed": 29, "total": 45 }
    ]
  }
}

Response (200 OK) - zakończone:
{
  "jobId": "job-456",
  "status": "COMPLETED",
  "progress": { "percentage": 100 },
  "result": {
    "categoriesCreated": ["Salary", "Groceries", "Bills", "Bank Fees"],
    "transactionsImported": 45,
    "transactionsFailed": 0,
    "errors": []
  }
}


KROK 7: Powtórz dla pozostałych plików CSV
══════════════════════════════════════════

Wgraj kolejne pliki z innymi miesiącami.
⚡ Mappingi "Wpływy regularne" → "Salary" itd. będą AUTOMATYCZNIE użyte!


KROK 8: Aktywuj CashFlow (attest historical import)
═══════════════════════════════════════════════════

POST /api/v1/cash-flow/{cashFlowId}/attest-historical-import
{
  "confirmedBalance": { "amount": 15000.00, "currency": "PLN" },
  "forceAttestation": false
}

Response (200 OK):
{
  "cashFlowId": "cf-123-456",
  "status": "OPEN",                          // ← CashFlow aktywowany!
  "calculatedBalance": { "amount": 15000.00, "currency": "PLN" },
  "confirmedBalance": { "amount": 15000.00, "currency": "PLN" },
  "balanceDifference": { "amount": 0.00, "currency": "PLN" },
  "adjustmentCreated": false
}

Stan po aktywacji:
  CashFlow status: OPEN
  Miesiące:
    2025-07 - 2025-12: IMPORTED    ← wszystkie historyczne
    2026-01: ACTIVE                ← bieżący
    2026-02 - 2026-12: FORECASTED  ← przyszłe
```

---

#### Scenariusz B: Kolejny import (OPEN mode, Ongoing Sync)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    FLOW: ONGOING SYNC (OPEN MODE)                           │
└─────────────────────────────────────────────────────────────────────────────┘

Stan przed importem:
  CashFlow status: OPEN
  Miesiące:
    2025-07 - 2025-12: IMPORTED
    2026-01: ACTIVE (bieżący miesiąc, balance not yet verified this month)
    2026-02 - 2026-12: FORECASTED


KROK 1: Wgraj nowy plik CSV
═══════════════════════════

POST /api/v1/bank-data-ingestion/{cashFlowId}/stage
{
  "transactions": [
    {
      "bankTransactionId": "PKO-2026-01-001",
      "name": "Wypłata styczeń",
      "bankCategory": "Wpływy regularne",
      "amount": 8700.00,
      "currency": "PLN",
      "type": "INFLOW",
      "paidDate": "2026-01-10T10:00:00Z"
    },
    {
      "bankTransactionId": "PKO-2026-01-002",
      "name": "Żabka Kraków",
      "bankCategory": "Zakupy kartą",
      "amount": 45.00,
      "currency": "PLN",
      "type": "OUTFLOW",
      "paidDate": "2026-01-11T08:15:00Z"
    },
    {
      "bankTransactionId": "PKO-2026-01-003",
      "name": "Allegro",
      "bankCategory": "Zakupy internetowe",    // ← NOWA kategoria!
      "amount": 299.00,
      "currency": "PLN",
      "type": "OUTFLOW",
      "paidDate": "2026-01-15T14:00:00Z"
    }
  ]
}

Response (200 OK):
{
  "stagingSessionId": "stage-999",
  "cashFlowId": "cf-123-456",
  "status": "HAS_UNMAPPED_CATEGORIES",
  "summary": {
    "totalTransactions": 3,
    "validTransactions": 2,                   // ← "Wpływy regularne" i "Zakupy kartą" już zmapowane!
    "invalidTransactions": 0,
    "duplicateTransactions": 0
  },
  "unmappedCategories": [
    { "bankCategory": "Zakupy internetowe", "count": 1, "type": "OUTFLOW" }  // ← tylko nowa
  ],
  "categoryBreakdown": [
    {
      "targetCategory": "Salary",
      "transactionCount": 1,
      "isNewCategory": false                  // ← kategoria już istnieje
    },
    {
      "targetCategory": "Groceries",
      "transactionCount": 1,
      "isNewCategory": false
    }
  ]
}

⚡ Zauważ: "Wpływy regularne" → "Salary" i "Zakupy kartą" → "Groceries"
   zostały AUTOMATYCZNIE zmapowane dzięki zapisanym mappingom!


KROK 2: Dodaj mapping tylko dla nowej kategorii
═══════════════════════════════════════════════

POST /api/v1/bank-data-ingestion/{cashFlowId}/mappings
{
  "mappings": [
    {
      "bankCategoryName": "Zakupy internetowe",
      "action": "CREATE_NEW",
      "targetCategoryName": "Online Shopping",
      "categoryType": "OUTFLOW"
    }
  ]
}


KROK 3: Revalidate i import
═══════════════════════════

POST /api/v1/bank-data-ingestion/{cashFlowId}/staging/{stagingSessionId}/revalidate
→ status: "READY_FOR_IMPORT"

POST /api/v1/bank-data-ingestion/{cashFlowId}/import
{
  "stagingSessionId": "stage-999",
  "confirmedBalance": { "amount": 16355.00, "currency": "PLN" }  // ← WYMAGANE dla first import to ACTIVE
}

⚠️ WAŻNE: Pierwszy import w danym miesiącu do ACTIVE wymaga balance verification!

Response (202 Accepted):
{
  "jobId": "job-789",
  "status": "IN_PROGRESS"
}

Po zakończeniu:
{
  "status": "COMPLETED",
  "result": {
    "categoriesCreated": ["Online Shopping"],
    "transactionsImported": 3,
    "balanceVerified": true,
    "verifiedBalance": { "amount": 16355.00, "currency": "PLN" }
  }
}
```

---

#### Scenariusz C: Gap Filling (uzupełnienie luk w historii)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    FLOW: GAP FILLING                                        │
└─────────────────────────────────────────────────────────────────────────────┘

Sytuacja: Po aktywacji CashFlow okazuje się, że brakuje transakcji
za grudzień 2025 (miesiąc jest IMPORTED ale pusty).

Stan:
  CashFlow status: OPEN
  Miesiące:
    2025-11: IMPORTED (45 transakcji)
    2025-12: IMPORTED (0 transakcji) ← GAP!
    2026-01: ROLLED_OVER (12 transakcji)
    2026-02: ACTIVE (5 transakcji)
    2026-03+: FORECASTED


KROK 1: Wgraj plik CSV z brakującymi danymi
═══════════════════════════════════════════

POST /api/v1/bank-data-ingestion/{cashFlowId}/stage
{
  "transactions": [
    {
      "bankTransactionId": "PKO-2025-12-001",
      "name": "Wypłata grudzień",
      "bankCategory": "Wpływy regularne",
      "amount": 8500.00,
      "currency": "PLN",
      "type": "INFLOW",
      "paidDate": "2025-12-10T10:00:00Z"      // ← grudzień 2025
    },
    {
      "bankTransactionId": "PKO-2025-12-002",
      "name": "Prezenty świąteczne",
      "bankCategory": "Zakupy kartą",
      "amount": 1200.00,
      "currency": "PLN",
      "type": "OUTFLOW",
      "paidDate": "2025-12-20T14:00:00Z"
    },
    // Można też dorzucić transakcje za styczeń (ROLLED_OVER)
    {
      "bankTransactionId": "PKO-2026-01-050",
      "name": "Zaległe zakupy",
      "bankCategory": "Zakupy kartą",
      "amount": 150.00,
      "currency": "PLN",
      "type": "OUTFLOW",
      "paidDate": "2026-01-25T16:00:00Z"      // ← styczeń 2026 (ROLLED_OVER)
    }
  ]
}

Response (200 OK):
{
  "stagingSessionId": "stage-gap",
  "status": "READY_FOR_IMPORT",               // ← Mappingi już istnieją!
  "summary": {
    "totalTransactions": 3,
    "validTransactions": 3,                   // ← Wszystkie valid
    "invalidTransactions": 0,
    "duplicateTransactions": 0
  },
  "monthlyBreakdown": [
    { "month": "2025-12", "transactionCount": 2 },  // ← Gap filling do IMPORTED
    { "month": "2026-01", "transactionCount": 1 }   // ← Gap filling do ROLLED_OVER
  ]
}

⚠️ UWAGA: Gap filling NIE wymaga balance verification!


KROK 2: Import (bez balance verification)
═════════════════════════════════════════

POST /api/v1/bank-data-ingestion/{cashFlowId}/import
{
  "stagingSessionId": "stage-gap"
  // Brak confirmedBalance - nie wymagane dla gap filling!
}

Response:
{
  "status": "COMPLETED",
  "result": {
    "transactionsImported": 3,
    "balanceVerified": false,
    "monthsAffected": ["2025-12", "2026-01"],
    "balancesRecalculated": true              // ← System przeliczył salda od 2025-12
  }
}

Stan po gap filling:
  2025-11: IMPORTED (45 transakcji)
  2025-12: IMPORTED (2 transakcji) ← Gap filled!
  2026-01: ROLLED_OVER (13 transakcji) ← +1 transakcja
  2026-02: ACTIVE (5 transakcji)

  Salda przeliczone od 2025-12 w górę.
```

---

#### Scenariusz D: Manual Rollover

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    FLOW: MANUAL ROLLOVER                                    │
└─────────────────────────────────────────────────────────────────────────────┘

Sytuacja: Jest 15 lutego 2026, ale CashFlow wciąż ma activePeriod = 2026-01.
Scheduled job nie zadziałał (downtime) lub user chce ręcznie zamknąć miesiąc.


KROK 1: Wywołaj manual rollover
═══════════════════════════════

POST /api/v1/cash-flow/{cashFlowId}/rollover
{
  "force": false,
  "targetPeriod": "2026-02"    // opcjonalnie, domyślnie = current calendar month
}

Response (200 OK):
{
  "cashFlowId": "cf-123-456",
  "previousActivePeriod": "2026-01",
  "newActivePeriod": "2026-02",
  "rolledOverPeriods": ["2026-01"],
  "closingBalance": { "amount": 16355.00, "currency": "PLN" },
  "rolledOverAt": "2026-02-15T10:30:00Z",
  "rolloverType": "MANUAL"
}

Stan po rollover:
  2025-12: IMPORTED
  2026-01: ROLLED_OVER ← było ACTIVE
  2026-02: ACTIVE      ← było FORECASTED
  2026-03: FORECASTED
  ...
  2027-02: FORECASTED  ← nowy miesiąc dodany


KROK 2 (opcjonalnie): Catch-up rollover (wiele miesięcy naraz)
══════════════════════════════════════════════════════════════

Sytuacja: Jest kwiecień 2026, ale activePeriod = 2026-01 (3 miesiące opóźnienia)

POST /api/v1/cash-flow/{cashFlowId}/rollover
{
  "targetPeriod": "2026-04"
}

Response (200 OK):
{
  "previousActivePeriod": "2026-01",
  "newActivePeriod": "2026-04",
  "rolledOverPeriods": ["2026-01", "2026-02", "2026-03"],  // ← 3 rollovery naraz
  "rolloverType": "MANUAL"
}
```

---

#### Scenariusz E: Import z duplikatami

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    FLOW: HANDLING DUPLICATES                                │
└─────────────────────────────────────────────────────────────────────────────┘

Sytuacja: User wgrywa plik CSV który częściowo pokrywa się z poprzednim importem.


POST /api/v1/bank-data-ingestion/{cashFlowId}/stage
{
  "transactions": [
    {
      "bankTransactionId": "PKO-2026-01-001",   // ← już istnieje w systemie!
      "name": "Wypłata styczeń",
      "amount": 8700.00,
      ...
    },
    {
      "bankTransactionId": "PKO-2026-01-099",   // ← nowa transakcja
      "name": "Nowa transakcja",
      "amount": 50.00,
      ...
    }
  ]
}

Response (200 OK):
{
  "stagingSessionId": "stage-dup",
  "status": "READY_FOR_IMPORT",
  "summary": {
    "totalTransactions": 2,
    "validTransactions": 1,
    "invalidTransactions": 0,
    "duplicateTransactions": 1                 // ← 1 duplikat
  },
  "duplicates": [
    {
      "bankTransactionId": "PKO-2026-01-001",
      "name": "Wypłata styczeń",
      "duplicateOf": "existing-txn-id-123"     // ← ID istniejącej transakcji
    }
  ]
}

Import zaimportuje tylko nowe transakcje (duplikaty są pomijane).
```

---

### 23.4 Tabela: Kiedy wymagana jest balance verification

| Tryb | Status miesiąca | Import # w miesiącu | Balance required? |
|------|-----------------|---------------------|-------------------|
| SETUP | IMPORT_PENDING | any | ❌ NIE |
| OPEN | IMPORTED | any | ❌ NIE (gap fill) |
| OPEN | ROLLED_OVER | any | ❌ NIE (gap fill) |
| OPEN | ACTIVE | 1st | ✅ TAK |
| OPEN | ACTIVE | 2nd+ | ❌ NIE |
| OPEN | FORECASTED | - | ❌ Zablokowane |
| - | ATTESTED | - | ❌ Zablokowane (legacy) |

---

### 23.5 Reużycie mappingów - przykłady

#### Jak mappingi się kumulują

```
Import #1 (lipiec 2025):
  CSV zawiera: "Wpływy regularne", "Zakupy kartą", "Opłaty"
  User konfiguruje 3 mappingi

  Mappingi w DB: 3

Import #2 (sierpień 2025):
  CSV zawiera: "Wpływy regularne", "Zakupy kartą", "Netflix"

  "Wpływy regularne" → auto-mapped ✓
  "Zakupy kartą" → auto-mapped ✓
  "Netflix" → ❌ unmapped → user konfiguruje 1 nowy mapping

  Mappingi w DB: 4

Import #3 (wrzesień 2025):
  CSV zawiera: "Wpływy regularne", "Zakupy kartą", "Netflix", "Spotify"

  "Wpływy regularne" → auto-mapped ✓
  "Zakupy kartą" → auto-mapped ✓
  "Netflix" → auto-mapped ✓
  "Spotify" → ❌ unmapped → user konfiguruje 1 nowy mapping

  Mappingi w DB: 5

Import #10:
  Większość kategorii już zmapowana automatycznie!
  User konfiguruje tylko nowe, nieznane kategorie.
```

#### Sprawdzenie istniejących mappingów

```
GET /api/v1/bank-data-ingestion/{cashFlowId}/mappings

Response:
{
  "cashFlowId": "cf-123-456",
  "mappingsCount": 5,
  "mappings": [
    {
      "mappingId": "map-001",
      "bankCategoryName": "Wpływy regularne",
      "targetCategoryName": "Salary",
      "categoryType": "INFLOW",
      "action": "CREATE_NEW",
      "createdAt": "2025-07-15T10:00:00Z"
    },
    {
      "mappingId": "map-002",
      "bankCategoryName": "Zakupy kartą",
      "targetCategoryName": "Groceries",
      "categoryType": "OUTFLOW",
      "action": "CREATE_NEW",
      "createdAt": "2025-07-15T10:00:00Z"
    },
    // ...
  ]
}
```

#### Aktualizacja mappingu

```
POST /api/v1/bank-data-ingestion/{cashFlowId}/mappings
{
  "mappings": [
    {
      "bankCategoryName": "Zakupy kartą",          // ← istniejący mapping
      "action": "MAP_TO_EXISTING",
      "targetCategoryName": "Shopping",            // ← zmiana target
      "categoryType": "OUTFLOW"
    }
  ]
}

Response:
{
  "mappings": [
    {
      "mappingId": "map-002",
      "bankCategoryName": "Zakupy kartą",
      "targetCategoryName": "Shopping",
      "status": "UPDATED"                          // ← zaktualizowany
    }
  ]
}
```

---

### 23.6 Przypadki testowe dla UI

#### Test Case 1: Pełny flow Historical Backfill

```
Preconditions: Brak CashFlow dla usera

Steps:
1. POST /cash-flow/with-history (startPeriod: 6 miesięcy temu)
2. POST /bank-data-ingestion/{id}/stage (plik z 3 miesiącami)
3. Verify: status = HAS_UNMAPPED_CATEGORIES
4. POST /bank-data-ingestion/{id}/mappings (skonfiguruj 5 mappingów)
5. POST /bank-data-ingestion/{id}/staging/{session}/revalidate
6. Verify: status = READY_FOR_IMPORT
7. POST /bank-data-ingestion/{id}/import
8. Poll until COMPLETED
9. POST /bank-data-ingestion/{id}/stage (kolejne 3 miesiące)
10. Verify: status = READY_FOR_IMPORT (mappingi reużyte!)
11. POST /bank-data-ingestion/{id}/import
12. POST /cash-flow/{id}/attest-historical-import
13. GET /cash-flow/{id}
14. Verify: status = OPEN, all historical months = IMPORTED

Expected Results:
- CashFlow status transitions: SETUP → OPEN
- Month statuses: IMPORT_PENDING → IMPORTED
- Mappings reused in step 10
- Balance calculated correctly
```

#### Test Case 2: Ongoing Sync with new category

```
Preconditions: CashFlow in OPEN status, current month = January 2026

Steps:
1. POST /bank-data-ingestion/{id}/stage (transactions for January)
   - Include known categories (auto-mapped)
   - Include 1 new category
2. Verify: status = HAS_UNMAPPED_CATEGORIES, 1 unmapped
3. POST /bank-data-ingestion/{id}/mappings (tylko nowa kategoria)
4. POST /bank-data-ingestion/{id}/staging/{session}/revalidate
5. POST /bank-data-ingestion/{id}/import { confirmedBalance: ... }
6. Verify: balanceVerified = true

Expected Results:
- Known categories auto-mapped
- Only new category needed configuration
- Balance verification required (first import to ACTIVE)
```

#### Test Case 3: Gap Filling to ROLLED_OVER

```
Preconditions:
- CashFlow OPEN
- January 2026 = ROLLED_OVER (empty)
- February 2026 = ACTIVE

Steps:
1. POST /bank-data-ingestion/{id}/stage (transactions for January 2026)
2. Verify: status = READY_FOR_IMPORT, monthlyBreakdown shows January
3. POST /bank-data-ingestion/{id}/import (WITHOUT confirmedBalance)
4. Verify: balanceVerified = false, balancesRecalculated = true
5. GET /cash-flow-forecast/{id}
6. Verify: January ROLLED_OVER has transactions, balances updated

Expected Results:
- Import to ROLLED_OVER succeeds without balance verification
- Balances recalculated from affected month
- January status remains ROLLED_OVER (unchanged)
```

#### Test Case 4: Manual Rollover (catch-up)

```
Preconditions:
- CashFlow OPEN
- activePeriod = 2026-01
- Current calendar = 2026-04 (3 months behind)

Steps:
1. POST /cash-flow/{id}/rollover { targetPeriod: "2026-04" }
2. Verify: 3 periods rolled over
3. GET /cash-flow-forecast/{id}
4. Verify timeline:
   - 2026-01: ROLLED_OVER
   - 2026-02: ROLLED_OVER
   - 2026-03: ROLLED_OVER
   - 2026-04: ACTIVE
   - 2026-05+: FORECASTED

Expected Results:
- Multiple rollovers in single request
- New FORECASTED months added
- Expected transactions moved forward
```

#### Test Case 5: Duplicate handling

```
Preconditions: CashFlow with imported transactions

Steps:
1. POST /bank-data-ingestion/{id}/stage
   - 5 transactions, 2 with existing bankTransactionId
2. Verify: duplicateTransactions = 2
3. POST /bank-data-ingestion/{id}/import
4. Verify: transactionsImported = 3 (only new ones)

Expected Results:
- Duplicates detected by bankTransactionId
- Duplicates skipped during import
- No errors, import succeeds
```

#### Test Case 6: Validation errors (import to FORECASTED blocked)

```
Preconditions: CashFlow OPEN, February 2026 = ACTIVE

Steps:
1. POST /bank-data-ingestion/{id}/stage
   - Transaction with paidDate = "2026-04-15" (FORECASTED month)
2. Verify: invalidTransactions = 1
3. Verify: validation error = "Cannot import to FORECASTED month"

Expected Results:
- Import to future months blocked
- Clear error message
```

---

### 23.7 Kody błędów i obsługa

| Błąd | HTTP Status | Kod | Opis | Akcja UI |
|------|-------------|-----|------|----------|
| Brak mappingów | 200 | `HAS_UNMAPPED_CATEGORIES` | Niektóre kategorie nie zmapowane | Pokaż ekran konfiguracji mappingów |
| Balance mismatch | 400 | `BALANCE_MISMATCH` | Saldo nie zgadza się | Pokaż dialog: force/adjustment/cancel |
| Balance required | 400 | `BALANCE_VERIFICATION_REQUIRED` | Pierwszy import do ACTIVE wymaga salda | Pokaż pole na saldo |
| Import to FORECASTED | 400 | `IMPORT_TO_FORECASTED_NOT_ALLOWED` | Próba importu do przyszłego miesiąca | Pokaż błąd, usuń transakcje z przyszłości |
| CashFlow CLOSED | 400 | `CASHFLOW_CLOSED` | CashFlow jest zamknięty | Pokaż info, zablokuj import |
| Session expired | 404 | `STAGING_SESSION_EXPIRED` | Sesja stagingowa wygasła | Rozpocznij staging od nowa |

---

### 23.8 Checklist migracji UI

#### Zmiany wymagane

- [ ] Usunąć przycisk "Attest Month" (deprecated)
- [ ] Dodać przycisk "Rollover Month" (opcjonalnie, dla power users)
- [ ] Zaktualizować wyświetlanie statusu miesiąca (dodać `ROLLED_OVER`)
- [ ] Obsłużyć `BALANCE_VERIFICATION_REQUIRED` przy imporcie do ACTIVE
- [ ] Pokazywać reużywane mappingi w UI stagingu
- [ ] Umożliwić gap filling do IMPORTED i ROLLED_OVER
- [ ] Zaktualizować timeline view (nowe statusy, ikony)

#### Bez zmian (backward compatible)

- [ ] Endpoint `/stage` - bez zmian w API
- [ ] Endpoint `/mappings` - bez zmian w API
- [ ] Endpoint `/import` - rozszerzony o `confirmedBalance` (opcjonalne)
- [ ] Statusy `IMPORTED`, `ACTIVE`, `FORECASTED` - bez zmian

---

### 22.6 Odłożone na przyszłość

| Feature | Powód odłożenia |
|---------|-----------------|
| `balance_after` w Canonical CSV | Czeka na potwierdzenie danych bankowych |
| BalanceCheckpoint entity | Związane z balance_after |
| Auto-verify per transaction | Związane z balance_after |

---

*Dokument wygenerowany: 2026-02-08*
*Ostatnia aktualizacja: 2026-02-08 - dodano sekcje 20-22 (wielokrotne dogrywanie historii, category mappings, zaktualizowany plan PR-ów z analizą testów)*
