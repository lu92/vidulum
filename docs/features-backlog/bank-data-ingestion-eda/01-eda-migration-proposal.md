# Bank Data Ingestion - Propozycja Migracji do EDA

## Spis treści
1. [Analiza obecnego rozwiązania](#1-analiza-obecnego-rozwiązania)
2. [Scenariusze użycia](#2-scenariusze-użycia)
3. [Propozycja architektury EDA](#3-propozycja-architektury-eda)
4. [Sekwencyjność plików vs równoległość rekordów](#4-sekwencyjność-plików-vs-równoległość-rekordów)
5. [Analiza: Event per Record vs Batch Event](#5-analiza-event-per-record-vs-batch-event)
6. [Rekomendacja](#6-rekomendacja)
7. [Plan migracji](#7-plan-migracji)

---

## 1. Analiza obecnego rozwiązania

### 1.1 Aktualny flow (synchroniczny HTTP)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        OBECNA ARCHITEKTURA                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  User ──POST /upload──▶ Controller ──▶ CommandHandler ──▶ MongoDB       │
│    │                         │                               │           │
│    │                         │ (synchronicznie)              │           │
│    │                         ▼                               │           │
│    │                  CsvParserService                       │           │
│    │                         │                               │           │
│    │                         ▼                               │           │
│    │              StageTransactionsCommandHandler            │           │
│    │                         │                               │           │
│    │                         │ (walidacja każdego rekordu)   │           │
│    │                         │                               │           │
│    │                         ▼                               │           │
│    │              StagedTransaction.saveAll()                │           │
│    │                         │                               │           │
│    ◀──────────Response───────┘                               │           │
│                                                                          │
│  User ──POST /import──▶ Controller ──▶ StartImportJobCommandHandler     │
│    │                                           │                         │
│    │                                           │ (synchronicznie)        │
│    │                                           ▼                         │
│    │                                   FOR EACH category:                │
│    │                                     CashFlowServiceClient           │
│    │                                       .createCategory()             │
│    │                                           │                         │
│    │                                           ▼                         │
│    │                                   FOR EACH transaction:             │
│    │                                     CashFlowServiceClient           │
│    │                                       .importTransaction()          │
│    │                                           │                         │
│    ◀──────────Response─────────────────────────┘                         │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Problemy obecnego rozwiązania

| Problem | Opis | Wpływ |
|---------|------|-------|
| **Timeout HTTP** | Duże pliki (1000+ rekordów) mogą przekroczyć timeout | Błąd importu |
| **Blokowanie wątku** | Jeden request = jeden wątek przez cały czas | Słaba skalowalność |
| **Brak retry** | Błąd jednego rekordu = ręczne powtórzenie | UX |
| **Brak progressu** | User nie widzi postępu w czasie rzeczywistym | UX |
| **Sekwencyjność** | Każdy rekord po kolei, brak równoległości | Wydajność |

### 1.3 Co działa dobrze (zachować!)

- ✅ **Staging Session** - elegancki mechanizm z TTL
- ✅ **Category Mapping** - dobrze zaprojektowany
- ✅ **Walidacja** - kompletna (duplikaty, daty, status miesiąca)
- ✅ **ImportJob state machine** - gotowa do EDA
- ✅ **CashFlowServiceClient** - abstrakcja umożliwia łatwą zmianę
- ✅ **Idempotentność** - bankTransactionId jako klucz

---

## 2. Scenariusze użycia

### 2.1 Scenariusz: Jeden plik CSV

```
User wgrywa plik z 500 transakcjami za styczeń 2026.

OBECNE:
1. POST /upload (500 rekordów) → 2-5 sekund
2. GET /staging/{id} → preview
3. POST /mappings → konfiguracja
4. POST /import → 30-60 sekund (blokujące!)
5. Response z wynikiem

OCZEKIWANE (EDA):
1. POST /upload → natychmiastowa odpowiedź z stagingSessionId
2. Kafka przetwarza w tle
3. WebSocket/SSE → progress updates
4. GET /staging/{id} → preview gdy ready
5. POST /import → natychmiastowa odpowiedź z importJobId
6. Kafka przetwarza w tle (równolegle!)
7. WebSocket → completion notification
```

### 2.2 Scenariusz: Dwa pliki sekwencyjnie

```
User wgrywa:
  - Plik A: 200 transakcji za styczeń
  - Plik B: 300 transakcji za luty

WYMAGANIE: Plik B musi być przetworzony PO pliku A!
(Bo balance miesiąca zależy od poprzedniego)

OCZEKIWANE:
1. POST /upload (Plik A) → stagingSessionId_A
2. POST /upload (Plik B) → stagingSessionId_B (kolejkowany)
3. Plik A przetwarzany
4. Plik A zakończony → Plik B rozpoczyna przetwarzanie
5. User widzi progress obu plików
```

### 2.3 Scenariusz: Równoległe rekordy w ramach batcha

```
W ramach jednego pliku (jednego stagingSessionId):
  - Rekordy mogą być przetwarzane równolegle
  - Ale każdy rekord trafia do tego samego CashFlow
  - Musi być zachowana spójność

OCZEKIWANE:
  - 500 rekordów → 10 partycji Kafka
  - 10 consumerów przetwarza równolegle
  - BatchTracker śledzi postęp
  - Gdy wszystkie → ImportJobCompletedEvent
```

### 2.4 Scenariusz: Błąd jednego rekordu

```
Plik z 500 rekordami, rekord #347 ma błąd (np. zły format daty)

OBECNE:
  - Rekord oznaczony jako INVALID
  - Import kontynuuje (pomija invalid)
  - Na końcu raport

OCZEKIWANE (EDA):
  - Event TransactionImportFailedEvent dla #347
  - BatchTracker zapisuje failure
  - Pozostałe eventy przetwarzane normalnie
  - Na końcu: 499 sukces, 1 failure
  - Możliwość retry dla #347 (osobny endpoint)
```

---

## 3. Propozycja architektury EDA

### 3.1 Nowe eventy

```java
// === UPLOAD PHASE ===
sealed interface BankDataIngestionEvent {

    // Plik wgrany, rozpoczyna się staging
    record CsvUploadedEvent(
        String correlationId,
        String cashFlowId,
        String stagingSessionId,
        int totalRecords,
        Instant timestamp
    ) implements BankDataIngestionEvent {}

    // Pojedynczy rekord do przetworzenia (staging)
    record TransactionStagingCommand(
        String correlationId,
        String stagingSessionId,
        int recordIndex,
        BankCsvRow data
    ) implements BankDataIngestionEvent {}

    // Wynik stagingu pojedynczego rekordu
    record TransactionStagedEvent(
        String correlationId,
        String stagingSessionId,
        String stagedTransactionId,
        ValidationStatus status
    ) implements BankDataIngestionEvent {}

    // Staging batcha zakończony
    record StagingCompletedEvent(
        String correlationId,
        String stagingSessionId,
        int totalStaged,
        int valid,
        int invalid,
        int pendingMapping,
        StagingStatus overallStatus
    ) implements BankDataIngestionEvent {}

    // === IMPORT PHASE ===

    // Rozpoczęcie importu
    record ImportStartedEvent(
        String correlationId,
        String importJobId,
        String stagingSessionId,
        int totalToImport
    ) implements BankDataIngestionEvent {}

    // Polecenie importu pojedynczej transakcji
    record TransactionImportCommand(
        String correlationId,
        String importJobId,
        String stagedTransactionId,
        MappedTransactionData data
    ) implements BankDataIngestionEvent {}

    // Wynik importu pojedynczej transakcji
    record TransactionImportedEvent(
        String correlationId,
        String importJobId,
        String stagedTransactionId,
        String cashChangeId // ID utworzonej transakcji w CashFlow
    ) implements BankDataIngestionEvent {}

    // Błąd importu pojedynczej transakcji
    record TransactionImportFailedEvent(
        String correlationId,
        String importJobId,
        String stagedTransactionId,
        String errorType,
        String errorMessage,
        boolean retryable
    ) implements BankDataIngestionEvent {}

    // Import zakończony
    record ImportCompletedEvent(
        String correlationId,
        String importJobId,
        int imported,
        int failed,
        ImportSummary summary
    ) implements BankDataIngestionEvent {}
}
```

### 3.2 Topologia Kafka

```
                              TOPICS
┌─────────────────────────────────────────────────────────────────────┐
│                                                                      │
│  bank_data_ingestion_commands                                        │
│  ├── partition 0: CashFlow CF001                                     │
│  ├── partition 1: CashFlow CF002                                     │
│  └── partition N: CashFlow CFXXX                                     │
│                                                                      │
│  Partition Key: cashFlowId                                           │
│  → Gwarantuje kolejność dla danego CashFlow!                         │
│                                                                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  bank_data_ingestion_results                                         │
│  ├── TransactionStagedEvent                                          │
│  ├── TransactionImportedEvent                                        │
│  ├── TransactionImportFailedEvent                                    │
│  └── ...                                                             │
│                                                                      │
│  Partition Key: correlationId                                        │
│  → BatchTracker agreguje wyniki                                      │
│                                                                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  bank_data_ingestion_dlq (Dead Letter Queue)                         │
│  ├── Failed events after max retries                                 │
│  └── Manual intervention required                                    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.3 Flow diagram - EDA

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        PROPONOWANA ARCHITEKTURA EDA                      │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────┐    ┌─────────────┐    ┌──────────────────┐                │
│  │   User   │───▶│  REST API   │───▶│  BatchTracker    │                │
│  └──────────┘    └─────────────┘    │  .startBatch()   │                │
│       │               │              └────────┬─────────┘                │
│       │               │                       │                          │
│       │               ▼                       │                          │
│       │         ┌───────────┐                 │                          │
│       │         │   Kafka   │◀────────────────┘                          │
│       │         │  Producer │                                            │
│       │         └─────┬─────┘                                            │
│       │               │                                                  │
│       │               │ CsvUploadedEvent                                 │
│       │               │ + N x TransactionStagingCommand                  │
│       │               ▼                                                  │
│       │    ┌──────────────────────┐                                      │
│       │    │ bank_data_ingestion  │                                      │
│       │    │ _commands            │                                      │
│       │    │                      │                                      │
│       │    │ [partition by        │                                      │
│       │    │  cashFlowId]         │                                      │
│       │    └──────────┬───────────┘                                      │
│       │               │                                                  │
│       │    ┌──────────┴───────────┐                                      │
│       │    │                      │                                      │
│       │    ▼                      ▼                                      │
│       │  ┌────────────┐    ┌────────────┐                               │
│       │  │ Consumer 1 │    │ Consumer 2 │  ... (równolegle!)            │
│       │  │ (CF001)    │    │ (CF002)    │                               │
│       │  └─────┬──────┘    └─────┬──────┘                               │
│       │        │                 │                                       │
│       │        │ Process         │ Process                               │
│       │        │ transaction     │ transaction                           │
│       │        ▼                 ▼                                       │
│       │  ┌───────────────────────────────┐                               │
│       │  │         MongoDB               │                               │
│       │  │  - StagedTransaction          │                               │
│       │  │  - ImportJob                  │                               │
│       │  └───────────────────────────────┘                               │
│       │        │                 │                                       │
│       │        │ Emit result     │ Emit result                           │
│       │        ▼                 ▼                                       │
│       │  ┌──────────────────────────────┐                                │
│       │  │ bank_data_ingestion_results  │                                │
│       │  └─────────────┬────────────────┘                                │
│       │                │                                                 │
│       │                ▼                                                 │
│       │  ┌──────────────────────────────┐                                │
│       │  │     BatchTracker             │                                │
│       │  │   .recordSuccess()           │                                │
│       │  │   .recordFailure()           │                                │
│       │  └─────────────┬────────────────┘                                │
│       │                │                                                 │
│       │                │ All done?                                       │
│       │                ▼                                                 │
│       │  ┌──────────────────────────────┐                                │
│       │  │     WebSocket Notifier       │                                │
│       │  └─────────────┬────────────────┘                                │
│       │                │                                                 │
│       ◀────────────────┘                                                 │
│       (Real-time progress updates)                                       │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Sekwencyjność plików vs równoległość rekordów

### 4.1 Problem

```
Użytkownik wgrywa dwa pliki:
  - Plik A: styczeń 2026 (200 rekordów)
  - Plik B: luty 2026 (300 rekordów)

Wymaganie: Plik B NIE MOŻE być przetworzony przed Plikiem A!
  - Balance lutego zależy od końcowego balance'u stycznia
  - Jeśli B przetworzymy przed A, balance będzie nieprawidłowy
```

### 4.2 Rozwiązanie: FileQueue per CashFlow

```java
@Document(collection = "file_queues")
public class FileQueue {
    private String cashFlowId;
    private List<QueuedFile> queue;
    private String currentlyProcessing; // stagingSessionId

    @Data
    public static class QueuedFile {
        private String stagingSessionId;
        private int position;
        private FileQueueStatus status; // QUEUED, PROCESSING, COMPLETED, FAILED
        private Instant queuedAt;
    }
}
```

### 4.3 Algorytm kolejkowania

```
1. POST /upload (Plik A) dla CashFlow CF001
   ├── Sprawdź FileQueue dla CF001
   ├── Queue jest pusta → position = 0, status = PROCESSING
   ├── Rozpocznij przetwarzanie natychmiast
   └── Return: stagingSessionId_A, position: 0, status: PROCESSING

2. POST /upload (Plik B) dla CashFlow CF001 (gdy A jeszcze trwa)
   ├── Sprawdź FileQueue dla CF001
   ├── currentlyProcessing = stagingSessionId_A
   ├── Queue ma element → position = 1, status = QUEUED
   ├── NIE rozpoczynaj przetwarzania
   └── Return: stagingSessionId_B, position: 1, status: QUEUED

3. Plik A zakończony (StagingCompletedEvent)
   ├── BatchTracker wykrywa completion
   ├── FileQueueService.onFileCompleted(CF001, stagingSessionId_A)
   ├── Pobierz następny z kolejki (stagingSessionId_B)
   ├── Zmień status B na PROCESSING
   └── Wyemituj eventy dla Pliku B
```

### 4.4 Sekwencyjność vs Równoległość - podsumowanie

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                      │
│  POZIOM 1: Pliki (SEKWENCYJNIE per CashFlow)                        │
│  ────────────────────────────────────────────                        │
│                                                                      │
│    CF001:  [Plik A] ──────▶ [Plik B] ──────▶ [Plik C]               │
│                 │               │               │                    │
│                 │               │               │                    │
│  POZIOM 2: Rekordy (RÓWNOLEGLE w ramach pliku)                      │
│  ─────────────────────────────────────────────                       │
│                 │               │               │                    │
│            ┌────┴────┐     ┌────┴────┐     ┌────┴────┐              │
│            ▼    ▼    ▼     ▼    ▼    ▼     ▼    ▼    ▼              │
│           R1   R2   R3    R1   R2   R3    R1   R2   R3              │
│            │    │    │     │    │    │     │    │    │              │
│           (równolegle)    (równolegle)    (równolegle)              │
│                                                                      │
│  POZIOM 3: CashFlow (RÓWNOLEGLE między CashFlows)                   │
│  ────────────────────────────────────────────────                    │
│                                                                      │
│    CF001: [Plik A] ────▶ [Plik B]                                   │
│    CF002: [Plik X] ────▶ [Plik Y] ────▶ [Plik Z]    ← równolegle!  │
│    CF003: [Plik M]                                                   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 5. Analiza: Event per Record vs Batch Event

### 5.1 Opcja A: Event per Record

```
Jeden plik z 500 rekordami:
  - 1 x CsvUploadedEvent
  - 500 x TransactionStagingCommand
  - 500 x TransactionStagedEvent
  - 1 x StagingCompletedEvent

  RAZEM: ~1002 eventy
```

**Zalety:**
- ✅ Maksymalna równoległość (500 consumerów może przetwarzać)
- ✅ Granularny progress (widzimy każdy rekord)
- ✅ Izolacja błędów (jeden błędny rekord nie blokuje innych)
- ✅ Łatwy retry pojedynczego rekordu

**Wady:**
- ❌ Duży narzut Kafka (dużo małych wiadomości)
- ❌ Złożoność BatchTrackera (śledzenie 500 korelacji)
- ❌ Potencjalny bottleneck na BatchTracker
- ❌ Więcej zapytań do MongoDB (500 zapisów vs 1 bulk)

### 5.2 Opcja B: Batch Event (chunks)

```
Jeden plik z 500 rekordami, chunk size = 50:
  - 1 x CsvUploadedEvent
  - 10 x TransactionBatchStagingCommand (każdy z 50 rekordami)
  - 10 x TransactionBatchStagedEvent
  - 1 x StagingCompletedEvent

  RAZEM: ~22 eventy
```

**Zalety:**
- ✅ Mniej eventów (mniejszy narzut Kafka)
- ✅ Bulk operations na MongoDB (wydajniej)
- ✅ Prostszy BatchTracker (10 korelacji zamiast 500)
- ✅ Mniej round-tripów sieciowych

**Wady:**
- ❌ Mniejsza równoległość (max 10 consumerów na plik)
- ❌ Błąd jednego rekordu w batchu = retry całego batcha
- ❌ Progress mniej granularny (co 50 rekordów)

### 5.3 Opcja C: Hybrydowa (rekomendowana)

```
Staging: Batch Event (chunks po 50)
  - Szybkie przetworzenie całego pliku
  - Bulk insert do MongoDB

Import: Event per Record
  - Każda transakcja osobno do CashFlow
  - Bo każda wymaga HTTP call do CashFlowService
  - Łatwy retry pojedynczej transakcji
```

```
Jeden plik z 500 rekordami:

STAGING PHASE (Batch):
  - 1 x CsvUploadedEvent
  - 10 x TransactionBatchStagingCommand (50 rekordów każdy)
  - 10 x TransactionBatchStagedEvent
  - 1 x StagingCompletedEvent
  RAZEM: ~22 eventy

IMPORT PHASE (Per Record):
  - 1 x ImportStartedEvent
  - 500 x TransactionImportCommand
  - 500 x TransactionImportedEvent (lub Failed)
  - 1 x ImportCompletedEvent
  RAZEM: ~1002 eventy

ŁĄCZNIE: ~1024 eventy
```

**Uzasadnienie:**
1. **Staging = CPU-bound** (parsing, walidacja) → batch jest efektywny
2. **Import = I/O-bound** (HTTP calls) → per-record pozwala na równoległość
3. **Retry** - częściej failuje import niż staging → per-record retry dla importu

---

## 6. Rekomendacja

### 6.1 Podsumowanie decyzji

| Aspekt | Decyzja | Uzasadnienie |
|--------|---------|--------------|
| **Pliki** | Sekwencyjnie per CashFlow | Spójność balance'u |
| **CashFlows** | Równolegle | Niezależne agregaty |
| **Staging** | Batch (chunks 50) | Wydajność, bulk MongoDB |
| **Import** | Per Record | Równoległość HTTP, granularny retry |
| **Kolejkowanie** | FileQueue per CashFlow | Proste, efektywne |

### 6.2 Czy migrować do EDA?

**TAK, ale z zastrzeżeniami:**

| Pro | Contra |
|-----|--------|
| ✅ Skalowalność (duże pliki) | ❌ Złożoność implementacji |
| ✅ Retry bez blokowania | ❌ Debugging trudniejszy |
| ✅ Real-time progress | ❌ Eventual consistency |
| ✅ Nie blokuje HTTP | ❌ Więcej ruchomych części |
| ✅ Równoległość importu | ❌ Wymaga BatchTracker |

### 6.3 Kiedy migrować?

**Teraz NIE jest konieczne** jeśli:
- Pliki mają < 500 rekordów
- Import trwa < 30 sekund
- Nie ma problemów z timeoutami

**Warto migrować** gdy:
- Pliki mają > 1000 rekordów
- Użytkownicy raportują timeouty
- Potrzebny real-time progress
- Potrzebna lepsza skalowalność

### 6.4 Alternatywa: Async bez EDA

Prostsze rozwiązanie bez pełnej migracji do Kafka:

```java
@PostMapping("/upload")
public ResponseEntity<UploadResponse> upload(MultipartFile file) {
    String stagingSessionId = generateId();

    // Natychmiastowa odpowiedź
    asyncExecutor.submit(() -> processFileAsync(stagingSessionId, file));

    return ResponseEntity.accepted()
        .body(new UploadResponse(stagingSessionId, "PROCESSING"));
}

// Polling endpoint
@GetMapping("/staging/{id}/status")
public ResponseEntity<StagingStatus> getStatus(@PathVariable String id) {
    return ResponseEntity.ok(stagingService.getStatus(id));
}
```

**Zalety:**
- Prostsze niż EDA
- Nie wymaga zmian w Kafka
- Nadal asynchroniczne

**Wady:**
- Brak automatycznego retry
- Brak równoległości (jeden wątek per plik)
- Trudniejsze skalowanie

---

## 7. Plan migracji

### 7.1 Faza 1: Przygotowanie (bez zmian w flow)

1. **Dodanie FileQueue**
   - Nowy dokument MongoDB
   - Kolejkowanie plików per CashFlow
   - Endpoint do sprawdzenia pozycji w kolejce

2. **Dodanie BatchTracker**
   - Reuse z Recurring Rules
   - Integracja z istniejącymi eventami

3. **WebSocket dla progress**
   - Real-time updates bez zmiany flow

### 7.2 Faza 2: Async Staging

1. **Nowy endpoint `/upload-async`**
   - Natychmiastowa odpowiedź
   - Staging w tle (async executor lub Kafka)

2. **Batch processing**
   - Chunks po 50 rekordów
   - Bulk insert do MongoDB

3. **Status polling**
   - `GET /staging/{id}/status`
   - Lub WebSocket push

### 7.3 Faza 3: EDA Import

1. **Event-driven import**
   - `TransactionImportCommand` per rekord
   - Równoległe przetwarzanie

2. **BatchTracker integration**
   - Śledzenie postępu
   - Agregacja wyników

3. **DLQ dla failed imports**
   - Retry logic
   - Manual intervention endpoint

### 7.4 Faza 4: Pełna migracja

1. **Deprecate sync endpoints**
2. **Migrate existing code**
3. **Performance tuning**

---

## Appendix A: Przykładowa implementacja FileQueue

```java
@Service
@RequiredArgsConstructor
public class FileQueueService {

    private final FileQueueRepository repository;
    private final KafkaTemplate<String, Object> kafka;

    /**
     * Dodaje plik do kolejki dla danego CashFlow
     */
    public QueuePosition enqueue(String cashFlowId, String stagingSessionId) {
        FileQueue queue = repository.findByCashFlowId(cashFlowId)
            .orElseGet(() -> FileQueue.create(cashFlowId));

        int position = queue.getQueue().size();
        boolean startImmediately = queue.getCurrentlyProcessing() == null;

        queue.getQueue().add(QueuedFile.builder()
            .stagingSessionId(stagingSessionId)
            .position(position)
            .status(startImmediately ? FileQueueStatus.PROCESSING : FileQueueStatus.QUEUED)
            .queuedAt(Instant.now())
            .build());

        if (startImmediately) {
            queue.setCurrentlyProcessing(stagingSessionId);
        }

        repository.save(queue);

        if (startImmediately) {
            // Emit events to start processing
            emitStagingEvents(cashFlowId, stagingSessionId);
        }

        return new QueuePosition(position, startImmediately);
    }

    /**
     * Wywoływane gdy plik zakończy przetwarzanie
     */
    @EventListener
    public void onStagingCompleted(StagingCompletedEvent event) {
        FileQueue queue = repository.findByCashFlowId(event.cashFlowId())
            .orElseThrow();

        // Mark current as completed
        queue.getQueue().stream()
            .filter(f -> f.getStagingSessionId().equals(event.stagingSessionId()))
            .findFirst()
            .ifPresent(f -> f.setStatus(FileQueueStatus.COMPLETED));

        queue.setCurrentlyProcessing(null);

        // Start next in queue
        queue.getQueue().stream()
            .filter(f -> f.getStatus() == FileQueueStatus.QUEUED)
            .findFirst()
            .ifPresent(next -> {
                next.setStatus(FileQueueStatus.PROCESSING);
                queue.setCurrentlyProcessing(next.getStagingSessionId());
                emitStagingEvents(event.cashFlowId(), next.getStagingSessionId());
            });

        repository.save(queue);
    }
}
```

## Appendix B: Porównanie z Recurring Rules EDA

| Aspekt | Recurring Rules | Bank Data Ingestion |
|--------|-----------------|---------------------|
| **Trigger** | Scheduler (cron) | User action (upload) |
| **Batch size** | Małe (5-20 reguł) | Duże (100-1000+ rekordów) |
| **Równoległość** | Per reguła | Per rekord (import) / batch (staging) |
| **Sekwencyjność** | Brak | Wymagana per CashFlow |
| **Retry** | Exponential backoff | Per rekord + DLQ |
| **Progress** | BatchTracker | BatchTracker + WebSocket |
| **Kompensacja** | Nie (idempotentne) | Rollback window |
