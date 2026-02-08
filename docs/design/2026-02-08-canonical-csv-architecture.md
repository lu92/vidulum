# Canonical CSV Architecture - Design Document

**Data utworzenia:** 2026-02-08
**Status:** Koncept do implementacji
**Autor:** Claude Code + User
**Powiązane:** `2026-02-08-month-rollover-ongoing-sync-design.md`

---

## Spis treści

1. [Podsumowanie](#1-podsumowanie)
2. [Canonical CSV Format](#2-canonical-csv-format)
3. [Architektura adapterów](#3-architektura-adapterów)
4. [Flow danych](#4-flow-danych)
5. [Implementacje adapterów](#5-implementacje-adapterów)
6. [Open Banking API Sync](#6-open-banking-api-sync)
7. [Integracja z Ongoing Sync](#7-integracja-z-ongoing-sync)
8. [Zalety i kompromisy](#8-zalety-i-kompromisy)
9. [Plan implementacji](#9-plan-implementacji)

---

## 1. Podsumowanie

### Cel

Ujednolicenie wszystkich źródeł danych bankowych przez wspólny format CSV (Canonical CSV). Niezależnie od źródła danych (manual upload, bank CSV, Open Banking API), wszystkie dane przechodzą przez ten sam pipeline importu.

### Źródła danych

| Źródło | Opis | Częstotliwość |
|--------|------|---------------|
| **Manual CSV** | User wgrywa plik ręcznie | Ad-hoc |
| **Bank CSV** | CSV z banku transformowany przez AI | Ad-hoc |
| **Open Banking API** | Automatyczny sync przez API (Kontomatik, Salt Edge) | 4 razy na dobę |

### Kluczowa koncepcja

```
Wszystkie źródła danych → Canonical CSV → Jeden pipeline importu
```

---

## 2. Canonical CSV Format

### Format v1 (obecny)

```csv
date,description,amount,category,type
2026-01-15,Wypłata,8500.00,Salary,INFLOW
2026-01-20,Czynsz,-2000.00,Housing,OUTFLOW
```

### Format v2 (rozszerzony dla API)

```csv
bankTransactionId,date,description,amount,currency,type,bankCategory,merchantName,merchantCategory,accountNumber
TXN-001,2026-01-15,Wypłata z firmy XYZ,8500.00,PLN,INFLOW,Wpływy regularne,XYZ Sp. z o.o.,SALARY,PL61...1234
TXN-002,2026-01-20,Biedronka Warszawa,-45.50,PLN,OUTFLOW,Zakupy,Biedronka,GROCERIES,PL61...1234
```

### Opis pól

| Pole | Wymagane | Źródło | Opis |
|------|----------|--------|------|
| `bankTransactionId` | ✅ | Bank/Generated | Unikalny ID transakcji (do deduplikacji) |
| `date` | ✅ | Bank | Data transakcji (ISO: YYYY-MM-DD) |
| `description` | ✅ | Bank | Opis transakcji |
| `amount` | ✅ | Bank | Kwota (zawsze dodatnia) |
| `currency` | ✅ | Bank | Waluta (ISO 4217: PLN, EUR, USD) |
| `type` | ✅ | Parser/AI | INFLOW lub OUTFLOW |
| `bankCategory` | ❌ | Bank | Kategoria z banku (do mapowania) |
| `merchantName` | ❌ | Bank/API | Nazwa sprzedawcy |
| `merchantCategory` | ❌ | Bank/API | MCC code lub kategoria sprzedawcy |
| `accountNumber` | ❌ | Bank | Numer konta (do walidacji) |

### Zasady formatu

1. **Encoding:** UTF-8
2. **Separator:** przecinek (`,`)
3. **Escape:** podwójne cudzysłowy dla pól zawierających przecinek lub cudzysłów
4. **Nagłówek:** pierwsza linia zawiera nazwy kolumn
5. **Daty:** format ISO 8601 (YYYY-MM-DD lub YYYY-MM-DDTHH:mm:ssZ)
6. **Kwoty:** zawsze dodatnie, typ określa kierunek (INFLOW/OUTFLOW)
7. **bankTransactionId:** unikalny w ramach konta bankowego

### Przykład pełny

```csv
bankTransactionId,date,description,amount,currency,type,bankCategory,merchantName,merchantCategory,accountNumber
"TXN-2026-001","2026-01-15","Przelew przychodzący - XYZ Sp. z o.o. - Wynagrodzenie 01/2026","8500.00","PLN","INFLOW","Wpływy regularne","XYZ Sp. z o.o.","SALARY","PL61109010140000071219812874"
"TXN-2026-002","2026-01-16","Płatność kartą - Biedronka Warszawa ul. Marszałkowska","45.50","PLN","OUTFLOW","Zakupy","Jeronimo Martins Polska","5411","PL61109010140000071219812874"
"TXN-2026-003","2026-01-17","Netflix","49.00","PLN","OUTFLOW","Rozrywka","Netflix International","5815","PL61109010140000071219812874"
"TXN-2026-004","2026-01-20","Przelew wychodzący - Czynsz za mieszkanie","2000.00","PLN","OUTFLOW","Mieszkanie","","","PL61109010140000071219812874"
```

---

## 3. Architektura adapterów

### Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        INGESTION LAYER                                   │
├────────────────────┬────────────────────┬────────────────────────────────┤
│                    │                    │                                │
│  ManualCsvAdapter  │  BankCsvAdapter    │  OpenBankingAdapter            │
│  (user upload)     │  (AI transform)    │  (Kontomatik, Salt Edge)       │
│                    │                    │                                │
│  ┌──────────────┐  │  ┌──────────────┐  │  ┌──────────────────────────┐  │
│  │ CSV file     │  │  │ ING CSV      │  │  │ API: GET /transactions  │  │
│  │ (canonical)  │  │  │ mBank CSV    │  │  │                          │  │
│  │              │  │  │ PKO CSV      │  │  │  Response JSON           │  │
│  └──────┬───────┘  │  └──────┬───────┘  │  └──────────┬───────────────┘  │
│         │          │         │          │             │                  │
│         │          │    AI Transform    │    Transform to Canonical     │
│         │          │    (GPT/Claude)    │    (deterministic)            │
│         │          │         │          │             │                  │
│         ▼          │         ▼          │             ▼                  │
│  ┌──────────────┐  │  ┌──────────────┐  │  ┌──────────────────────────┐  │
│  │ Canonical    │  │  │ Canonical    │  │  │ Canonical CSV            │  │
│  │ CSV          │  │  │ CSV          │  │  │ (in-memory)              │  │
│  └──────┬───────┘  │  └──────┬───────┘  │  └──────────┬───────────────┘  │
│         │          │         │          │             │                  │
└─────────┴──────────┴─────────┴──────────┴─────────────┴──────────────────┘
                     │                    │             │
                     └────────────────────┴─────────────┘
                                          │
                                          ▼
                     ┌─────────────────────────────────────┐
                     │     CanonicalCsvProcessor           │
                     │     (single implementation)         │
                     │                                     │
                     │  1. Parse CSV                       │
                     │  2. Validate format                 │
                     │  3. Stage transactions              │
                     │  4. Apply category mappings         │
                     │  5. Detect duplicates               │
                     │  6. Import to CashFlow              │
                     └─────────────────────────────────────┘
```

### Interface BankDataAdapter

```java
package com.multi.vidulum.bank_data_ingestion.adapter;

import java.util.List;
import java.util.Map;

/**
 * Adapter that transforms bank-specific data to canonical CSV format.
 * All bank integrations implement this interface, ensuring a unified
 * data flow regardless of the source (manual upload, bank CSV, API).
 *
 * This is the "Canonical Data Model" pattern - all inputs are normalized
 * to a single format before processing.
 */
public interface BankDataAdapter {

    /**
     * @return unique identifier for this adapter (e.g., "kontomatik", "ing-csv", "manual")
     */
    String getAdapterId();

    /**
     * @return human-readable name for UI display
     */
    String getDisplayName();

    /**
     * @return list of supported bank identifiers (e.g., "ing_pl", "mbank_pl")
     */
    List<String> getSupportedBanks();

    /**
     * @return true if this adapter fetches data automatically (API), false for manual upload
     */
    boolean isAutomatic();

    /**
     * Transform bank-specific data to canonical CSV format.
     *
     * @param input raw input (CSV bytes for manual, JSON for API response)
     * @param metadata additional context (account number, date range, etc.)
     * @return result containing canonical CSV and metadata
     * @throws TransformationException if input cannot be transformed
     */
    CanonicalCsvResult transform(byte[] input, TransformMetadata metadata);
}

/**
 * Result of transforming bank data to canonical CSV format.
 */
public record CanonicalCsvResult(
    String canonicalCsv,              // The transformed CSV content
    int totalTransactions,            // Number of transactions in CSV
    List<String> warnings,            // Non-fatal issues during transform
    Map<String, Object> sourceMetadata // Original response metadata (for audit)
) {
    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
}

/**
 * Metadata provided to adapter for transformation context.
 */
public record TransformMetadata(
    String accountNumber,             // Expected account number (for validation)
    String currency,                  // Expected currency
    java.time.LocalDate fromDate,     // Date range start (optional)
    java.time.LocalDate toDate,       // Date range end (optional)
    String bankIdentifier,            // Bank identifier (e.g., "ing_pl")
    Map<String, String> extra         // Additional adapter-specific params
) {}
```

---

## 4. Flow danych

### 4.1 Manual CSV Upload (bez zmian)

```
User uploads CSV → ManualCsvAdapter (passthrough) → CanonicalCsvProcessor → Import
```

### 4.2 Bank CSV z AI Transform

```
User uploads ING CSV
       │
       ▼
BankCsvAdapter
       │
       ├─► Detect bank format (ING, mBank, PKO, etc.)
       │
       ├─► AI Transform (Claude/GPT)
       │   - Input: original CSV
       │   - Prompt: "Transform to canonical format"
       │   - Output: canonical CSV
       │
       ▼
Canonical CSV
       │
       ▼
CanonicalCsvProcessor → Import
```

### 4.3 Open Banking API (automatyczny sync)

```
Scheduled Job (4x dziennie: 00:00, 06:00, 12:00, 18:00 UTC)
       │
       ▼
For each CashFlow with linked bank account:
       │
       ├─► OpenBankingAdapter.fetchTransactions(accountId, lastSyncDate)
       │
       ├─► API Response (JSON)
       │   {
       │     "transactions": [
       │       {"id": "TXN-001", "amount": 8500, ...},
       │       {"id": "TXN-002", "amount": -45.50, ...}
       │     ]
       │   }
       │
       ├─► Transform to Canonical CSV (deterministic, no AI)
       │
       ▼
Canonical CSV (in-memory, not saved to disk)
       │
       ▼
CanonicalCsvProcessor
       │
       ├─► Stage transactions
       ├─► Detect duplicates (by bankTransactionId)
       ├─► Skip duplicates (no new transactions between syncs is common)
       ├─► Import new transactions only
       │
       ▼
CashFlow updated (or no changes if all duplicates)
```

### 4.4 Handling "no new transactions"

Przy 4 syncach dziennie, często nie będzie nowych transakcji między odświeżeniami.

```java
// W CanonicalCsvProcessor lub OpenBankingSyncService

SyncResult syncResult = processCanonicalCsv(canonicalCsv, cashFlowId);

if (syncResult.newTransactions() == 0) {
    log.debug("No new transactions for CashFlow [{}] - all {} transactions were duplicates",
        cashFlowId, syncResult.totalProcessed());
    // Update last sync timestamp, but don't trigger import flow
    updateLastSyncTimestamp(cashFlowId);
    return SyncResult.noChanges();
}

log.info("Imported {} new transactions for CashFlow [{}] ({} duplicates skipped)",
    syncResult.newTransactions(), cashFlowId, syncResult.duplicatesSkipped());
```

---

## 5. Implementacje adapterów

### 5.1 ManualCsvAdapter

```java
package com.multi.vidulum.bank_data_ingestion.adapter;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Adapter for manual CSV uploads that are already in canonical format.
 * This is a passthrough adapter - it validates the format but doesn't transform.
 */
@Component
public class ManualCsvAdapter implements BankDataAdapter {

    @Override
    public String getAdapterId() {
        return "manual";
    }

    @Override
    public String getDisplayName() {
        return "Manual CSV Upload";
    }

    @Override
    public List<String> getSupportedBanks() {
        return List.of("*"); // Supports any bank if CSV is in canonical format
    }

    @Override
    public boolean isAutomatic() {
        return false;
    }

    @Override
    public CanonicalCsvResult transform(byte[] input, TransformMetadata metadata) {
        String csv = new String(input, StandardCharsets.UTF_8);

        // Validate canonical format
        List<String> warnings = validateCanonicalFormat(csv);

        int lineCount = countDataLines(csv);

        return new CanonicalCsvResult(
            csv,
            lineCount,
            warnings,
            Map.of("source", "manual_upload")
        );
    }

    private List<String> validateCanonicalFormat(String csv) {
        // TODO: Validate required columns exist
        // TODO: Validate date format
        // TODO: Validate amount format
        return List.of();
    }

    private int countDataLines(String csv) {
        return (int) csv.lines().skip(1).filter(line -> !line.isBlank()).count();
    }
}
```

### 5.2 AiBankCsvAdapter

```java
package com.multi.vidulum.bank_data_ingestion.adapter;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Adapter for bank-specific CSV files that require AI transformation.
 * Uses Claude/GPT to convert bank-specific formats to canonical CSV.
 */
@Slf4j
@Component
@AllArgsConstructor
public class AiBankCsvAdapter implements BankDataAdapter {

    private final AiTransformService aiTransformService;
    private final BankFormatDetector bankFormatDetector;

    @Override
    public String getAdapterId() {
        return "ai-bank-csv";
    }

    @Override
    public String getDisplayName() {
        return "Bank CSV (AI Transform)";
    }

    @Override
    public List<String> getSupportedBanks() {
        return List.of("ing_pl", "mbank_pl", "pko_pl", "santander_pl", "bnp_pl");
    }

    @Override
    public boolean isAutomatic() {
        return false;
    }

    @Override
    public CanonicalCsvResult transform(byte[] input, TransformMetadata metadata) {
        String originalCsv = new String(input, StandardCharsets.UTF_8);

        // Detect bank format if not provided
        String bankId = metadata.bankIdentifier();
        if (bankId == null || bankId.isBlank()) {
            bankId = bankFormatDetector.detect(originalCsv);
            log.info("Auto-detected bank format: {}", bankId);
        }

        // Transform using AI
        String canonicalCsv = aiTransformService.transformToCanonical(originalCsv, bankId);

        int lineCount = countDataLines(canonicalCsv);

        return new CanonicalCsvResult(
            canonicalCsv,
            lineCount,
            List.of(),
            Map.of(
                "source", "ai_transform",
                "originalBankFormat", bankId,
                "originalLineCount", countDataLines(originalCsv)
            )
        );
    }

    private int countDataLines(String csv) {
        return (int) csv.lines().skip(1).filter(line -> !line.isBlank()).count();
    }
}
```

### 5.3 KontomatikAdapter

```java
package com.multi.vidulum.bank_data_ingestion.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adapter for Kontomatik Open Banking API.
 * Transforms JSON API response to canonical CSV format.
 *
 * This is a deterministic transformation (no AI) since the API response
 * format is well-defined and consistent.
 */
@Slf4j
@Component
@AllArgsConstructor
public class KontomatikAdapter implements BankDataAdapter {

    private final ObjectMapper objectMapper;

    @Override
    public String getAdapterId() {
        return "kontomatik";
    }

    @Override
    public String getDisplayName() {
        return "Kontomatik Open Banking";
    }

    @Override
    public List<String> getSupportedBanks() {
        // Kontomatik supports most Polish banks
        return List.of(
            "ing_pl", "mbank_pl", "pko_pl", "santander_pl",
            "bnp_pl", "millennium_pl", "alior_pl", "ing_pl"
        );
    }

    @Override
    public boolean isAutomatic() {
        return true; // API-based, runs on schedule
    }

    @Override
    public CanonicalCsvResult transform(byte[] input, TransformMetadata metadata) {
        try {
            KontomatikResponse response = objectMapper.readValue(input, KontomatikResponse.class);

            StringBuilder csv = new StringBuilder();
            csv.append("bankTransactionId,date,description,amount,currency,type,bankCategory,merchantName,merchantCategory,accountNumber\n");

            List<String> warnings = new ArrayList<>();

            for (KontomatikTransaction txn : response.transactions()) {
                String type = txn.amount().doubleValue() >= 0 ? "INFLOW" : "OUTFLOW";
                double absAmount = Math.abs(txn.amount().doubleValue());

                csv.append(String.format("%s,%s,%s,%.2f,%s,%s,%s,%s,%s,%s\n",
                    escapeCsv(txn.transactionId()),
                    txn.bookingDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                    escapeCsv(txn.description()),
                    absAmount,
                    txn.currency(),
                    type,
                    escapeCsv(txn.category() != null ? txn.category() : ""),
                    escapeCsv(txn.merchantName() != null ? txn.merchantName() : ""),
                    escapeCsv(txn.mcc() != null ? txn.mcc() : ""),
                    escapeCsv(txn.accountNumber())
                ));
            }

            return new CanonicalCsvResult(
                csv.toString(),
                response.transactions().size(),
                warnings,
                Map.of(
                    "source", "kontomatik_api",
                    "accountId", response.accountId(),
                    "fetchedAt", response.fetchedAt().toString()
                )
            );

        } catch (Exception e) {
            throw new TransformationException("Failed to transform Kontomatik response", e);
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // DTOs for Kontomatik API response
    record KontomatikResponse(
        String accountId,
        java.time.ZonedDateTime fetchedAt,
        List<KontomatikTransaction> transactions
    ) {}

    record KontomatikTransaction(
        String transactionId,
        java.time.LocalDate bookingDate,
        String description,
        java.math.BigDecimal amount,
        String currency,
        String category,
        String merchantName,
        String mcc,
        String accountNumber
    ) {}
}
```

### 5.4 SaltEdgeAdapter (analogiczny)

```java
package com.multi.vidulum.bank_data_ingestion.adapter;

/**
 * Adapter for Salt Edge Open Banking API.
 * Similar structure to KontomatikAdapter but with Salt Edge-specific
 * response format and field mappings.
 */
@Slf4j
@Component
@AllArgsConstructor
public class SaltEdgeAdapter implements BankDataAdapter {

    @Override
    public String getAdapterId() {
        return "saltedge";
    }

    @Override
    public String getDisplayName() {
        return "Salt Edge Open Banking";
    }

    @Override
    public boolean isAutomatic() {
        return true;
    }

    // ... implementation similar to KontomatikAdapter
}
```

---

## 6. Open Banking API Sync

### 6.1 Scheduled Job

```java
package com.multi.vidulum.bank_data_ingestion.infrastructure;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Scheduled job that syncs transactions from Open Banking APIs.
 * Runs 4 times per day (every 6 hours).
 *
 * Between syncs, it's common to have no new transactions - this is expected
 * and handled efficiently by duplicate detection.
 */
@Slf4j
@Component
@AllArgsConstructor
public class OpenBankingSyncScheduler {

    private final BankConnectionRepository bankConnectionRepository;
    private final OpenBankingSyncService syncService;
    private final Clock clock;

    /**
     * Runs at 00:00, 06:00, 12:00, 18:00 UTC.
     * Syncs all active bank connections.
     */
    @Scheduled(cron = "${vidulum.openbanking.sync.cron:0 0 0,6,12,18 * * ?}")
    public void syncAllConnections() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        log.info("Starting Open Banking sync at [{}]", now);

        List<BankConnection> activeConnections = bankConnectionRepository.findAllActive();
        log.info("Found [{}] active bank connections to sync", activeConnections.size());

        int successCount = 0;
        int failCount = 0;
        int noChangesCount = 0;

        for (BankConnection connection : activeConnections) {
            try {
                SyncResult result = syncService.syncConnection(connection);

                if (result.hasNewTransactions()) {
                    successCount++;
                    log.info("Synced [{}] new transactions for connection [{}]",
                        result.newTransactions(), connection.id());
                } else {
                    noChangesCount++;
                    log.debug("No new transactions for connection [{}]", connection.id());
                }

            } catch (Exception e) {
                failCount++;
                log.error("Failed to sync connection [{}]: {}", connection.id(), e.getMessage());
            }
        }

        log.info("Open Banking sync completed. Success: {}, No changes: {}, Failed: {}",
            successCount, noChangesCount, failCount);
    }
}
```

### 6.2 Sync Service

```java
package com.multi.vidulum.bank_data_ingestion.infrastructure;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Service that orchestrates syncing transactions from Open Banking APIs.
 */
@Slf4j
@Service
@AllArgsConstructor
public class OpenBankingSyncService {

    private final Map<String, BankDataAdapter> adapters;
    private final OpenBankingApiClient apiClient;
    private final CanonicalCsvProcessor csvProcessor;
    private final BankConnectionRepository connectionRepository;

    public SyncResult syncConnection(BankConnection connection) {
        // Get appropriate adapter
        BankDataAdapter adapter = adapters.get(connection.provider());
        if (adapter == null) {
            throw new UnsupportedProviderException(connection.provider());
        }

        // Fetch transactions from API
        LocalDate fromDate = connection.lastSyncDate() != null
            ? connection.lastSyncDate()
            : connection.createdAt().toLocalDate();

        byte[] apiResponse = apiClient.fetchTransactions(
            connection.provider(),
            connection.accessToken(),
            connection.accountId(),
            fromDate,
            LocalDate.now()
        );

        // Transform to canonical CSV
        TransformMetadata metadata = new TransformMetadata(
            connection.accountNumber(),
            connection.currency(),
            fromDate,
            LocalDate.now(),
            connection.bankIdentifier(),
            Map.of()
        );

        CanonicalCsvResult csvResult = adapter.transform(apiResponse, metadata);

        // Process through standard pipeline
        ImportResult importResult = csvProcessor.processAndImport(
            csvResult.canonicalCsv(),
            connection.cashFlowId()
        );

        // Update last sync timestamp
        connection.setLastSyncDate(LocalDate.now());
        connectionRepository.save(connection);

        return new SyncResult(
            csvResult.totalTransactions(),
            importResult.imported(),
            importResult.duplicatesSkipped(),
            importResult.failed()
        );
    }
}

public record SyncResult(
    int totalProcessed,
    int newTransactions,
    int duplicatesSkipped,
    int failed
) {
    public boolean hasNewTransactions() {
        return newTransactions > 0;
    }
}
```

### 6.3 Handling frequent syncs with no changes

```java
/**
 * Przy 4 syncach dziennie (co 6 godzin), większość synców nie znajdzie nowych transakcji.
 * To jest oczekiwane zachowanie i jest zoptymalizowane:
 *
 * 1. Duplicate detection by bankTransactionId - szybkie, O(1) lookup
 * 2. Early exit jeśli wszystkie transakcje są duplikatami
 * 3. Brak triggerowania pełnego import flow dla pustych wyników
 * 4. Tylko aktualizacja lastSyncDate
 *
 * Typowy dzień:
 * - 00:00 sync: 0 new (user śpi)
 * - 06:00 sync: 0 new (rano, brak zakupów)
 * - 12:00 sync: 2 new (lunch, kawa)
 * - 18:00 sync: 5 new (zakupy po pracy)
 *
 * Czyli ~75% synców to "no changes" - to jest OK.
 */
```

---

## 7. Integracja z Ongoing Sync

### Gdzie Canonical CSV wchodzi do obecnego flow

```
                    ┌─────────────────────────────────────────┐
                    │         CANONICAL CSV                   │
                    │   (from any adapter)                    │
                    └─────────────────────┬───────────────────┘
                                          │
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        EXISTING BANK DATA INGESTION                         │
│                                                                             │
│  ┌─────────────────┐                                                        │
│  │ CsvParserService│◄─── Entry point (już istnieje)                         │
│  └────────┬────────┘                                                        │
│           │                                                                 │
│           ▼                                                                 │
│  ┌─────────────────────────┐                                                │
│  │ StageTransactionsCommand│                                                │
│  │ Handler                 │                                                │
│  └────────┬────────────────┘                                                │
│           │                                                                 │
│           ▼                                                                 │
│  ┌─────────────────────────┐     ┌─────────────────────────┐                │
│  │ StagedTransaction       │────►│ Category Mapping        │                │
│  │ Repository (MongoDB)    │     │ (user configures once)  │                │
│  └────────┬────────────────┘     └─────────────────────────┘                │
│           │                                                                 │
│           ▼                                                                 │
│  ┌─────────────────────────┐                                                │
│  │ StartImportCommand      │                                                │
│  │ (with balance verify)   │                                                │
│  └────────┬────────────────┘                                                │
│           │                                                                 │
│           ▼                                                                 │
│  ┌─────────────────────────┐                                                │
│  │ ImportJob               │                                                │
│  │ (creates CashChanges)   │                                                │
│  └─────────────────────────┘                                                │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Automatyczny sync vs Manual upload

| Aspekt | Manual Upload | Automatic API Sync |
|--------|---------------|-------------------|
| **Trigger** | User action | Scheduled job (4x/day) |
| **Balance verification** | Required (first per month) | Skipped (automatyczny) |
| **Staging preview** | Shown to user | Skipped (automatyczny) |
| **Category mapping** | User confirms | Auto-apply existing mappings |
| **Duplicate handling** | User sees duplicates | Auto-skip silently |
| **Import confirmation** | User clicks "Import" | Automatic |

### Zmiany w StageTransactionsCommandHandler

```java
// Nowy parametr: automaticSync
public record StageTransactionsCommand(
    CashFlowId cashFlowId,
    List<BankTransaction> transactions,
    boolean automaticSync  // NEW: true for API sync, false for manual
) implements Command {}

// W handler:
if (command.automaticSync()) {
    // Skip staging preview - import directly
    // Skip balance verification - automatic
    // Auto-apply existing category mappings
    // Skip duplicates silently
    return directImport(command);
} else {
    // Existing flow - staging preview, user confirmation
    return stageForReview(command);
}
```

---

## 8. Zalety i kompromisy

### Zalety

| Zaleta | Opis |
|--------|------|
| **Single point of entry** | Cała logika importu w jednym miejscu (`CanonicalCsvProcessor`) |
| **Testability** | Łatwo testować - wrzuć CSV, sprawdź wynik |
| **Debuggability** | Można zobaczyć pośredni CSV gdy coś nie działa |
| **AI transform isolation** | AI transformacja jest oddzielona od core logic |
| **Reusability** | Ten sam kod dla manual i automatic imports |
| **Auditability** | Canonical CSV jako audit log (opcjonalnie) |
| **New banks = new adapter** | Dodanie nowego banku = tylko nowy adapter |

### Kompromisy

| Kompromis | Mitygacja |
|-----------|-----------|
| **Serializacja overhead** | Minimalny - CSV jest lekki, in-memory |
| **Utrata metadanych z API** | Canonical format ma `sourceMetadata` |
| **Dodatkowa warstwa abstrakcji** | Prosta warstwa, łatwa do zrozumienia |

### Porównanie: Z i bez ujednolicenia

| Aspekt | Bez ujednolicenia | Z ujednoliceniem przez CSV |
|--------|-------------------|---------------------------|
| **Kod importu** | Duplikacja dla każdego źródła | Jeden `CanonicalCsvProcessor` |
| **Testy** | Osobne testy dla każdego źródła | Jeden zestaw testów + testy adapterów |
| **Nowe banki** | Nowy full pipeline | Tylko nowy adapter |
| **Debugging** | Trudne - różne formaty | Łatwe - canonical CSV jako checkpoint |
| **Maintenance** | N pipelines do utrzymania | 1 pipeline + N prostych adapterów |

---

## 9. Plan implementacji

### Faza 1: Canonical CSV Format (PR-A)

**Pliki do utworzenia:**
- `CanonicalCsvFormat.java` - constants, validation
- `CanonicalCsvResult.java` - result record
- `TransformMetadata.java` - metadata record

**Zmiany:**
- Rozszerzyć `CsvParserService` o walidację canonical format

### Faza 2: Adapter Interface (PR-B)

**Pliki do utworzenia:**
- `BankDataAdapter.java` - interface
- `ManualCsvAdapter.java` - passthrough implementation
- `TransformationException.java` - exception

### Faza 3: AI Bank CSV Adapter (PR-C)

**Pliki do utworzenia:**
- `AiBankCsvAdapter.java`
- `AiTransformService.java`
- `BankFormatDetector.java`

**Zależności:** Integracja z Claude/GPT API

### Faza 4: Open Banking Adapters (PR-D)

**Pliki do utworzenia:**
- `KontomatikAdapter.java`
- `SaltEdgeAdapter.java` (opcjonalnie)
- `OpenBankingApiClient.java`

**Zależności:** Konto developerskie u providera

### Faza 5: Automatic Sync (PR-E)

**Pliki do utworzenia:**
- `OpenBankingSyncScheduler.java`
- `OpenBankingSyncService.java`
- `BankConnection.java` - entity
- `BankConnectionRepository.java`

**Zmiany:**
- `StageTransactionsCommand` - dodać `automaticSync` flag
- `application.properties` - cron config

### Diagram zależności

```
PR-A (Canonical Format)
  │
  └──► PR-B (Adapter Interface)
         │
         ├──► PR-C (AI Bank CSV Adapter)
         │
         └──► PR-D (Open Banking Adapters)
                │
                └──► PR-E (Automatic Sync)
```

### Priorytety

| PR | Priorytet | Zależności zewnętrzne |
|----|-----------|----------------------|
| PR-A | 🔴 Wysoki | Brak |
| PR-B | 🔴 Wysoki | Brak |
| PR-C | 🟡 Średni | Claude/GPT API key |
| PR-D | 🟡 Średni | Kontomatik/Salt Edge konto |
| PR-E | 🟡 Średni | PR-D |

---

## Appendix: Przykładowe canonical CSV dla różnych banków

### ING (po AI transform)

```csv
bankTransactionId,date,description,amount,currency,type,bankCategory,merchantName,merchantCategory,accountNumber
"ING-2026-0001","2026-01-15","Przelew przychodzący WYNAGRODZENIE ZA 01/2026 XYZ SP Z O O","8500.00","PLN","INFLOW","Wpływy","XYZ Sp. z o.o.","","PL61109010140000071219812874"
"ING-2026-0002","2026-01-16","Płatność kartą BIEDRONKA WARSZAWA","45.50","PLN","OUTFLOW","Zakupy i usługi","Biedronka","5411","PL61109010140000071219812874"
```

### mBank (po AI transform)

```csv
bankTransactionId,date,description,amount,currency,type,bankCategory,merchantName,merchantCategory,accountNumber
"MBANK-2026-0001","2026-01-15","PRZELEW PRZYCHODZĄCY OD: XYZ SP. Z O.O. TYTUŁEM: WYNAGRODZENIE","8500.00","PLN","INFLOW","Przelewy przychodzące","XYZ Sp. z o.o.","","PL12114020040000320250000001"
"MBANK-2026-0002","2026-01-16","TRANSAKCJA KARTĄ W: BIEDRONKA","45.50","PLN","OUTFLOW","Płatności kartą","Biedronka","5411","PL12114020040000320250000001"
```

### Kontomatik API (bez AI, deterministyczny transform)

```csv
bankTransactionId,date,description,amount,currency,type,bankCategory,merchantName,merchantCategory,accountNumber
"kontomatik-txn-abc123","2026-01-15","Przelew przychodzący - Wynagrodzenie","8500.00","PLN","INFLOW","salary","XYZ Sp. z o.o.","","PL61109010140000071219812874"
"kontomatik-txn-def456","2026-01-16","Płatność kartą - Biedronka","45.50","PLN","OUTFLOW","groceries","Biedronka","5411","PL61109010140000071219812874"
```

---

*Dokument wygenerowany: 2026-02-08*
