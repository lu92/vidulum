# 12. BatchTracker i Wzorce Przetwarzania Wsadowego

## Spis treści
1. [Czym jest BatchTracker?](#1-czym-jest-batchtracker)
2. [Główne funkcjonalności](#2-główne-funkcjonalności)
3. [Maszyna stanów](#3-maszyna-stanów)
4. [Struktura danych MongoDB](#4-struktura-danych-mongodb)
5. [Implementacja BatchTracker](#5-implementacja-batchtracker)
6. [Wzorce sekwencyjnego przetwarzania](#6-wzorce-sekwencyjnego-przetwarzania)
7. [Porównanie wzorców](#7-porównanie-wzorców)
8. [Rekomendacja dla Recurring Rules](#8-rekomendacja-dla-recurring-rules)

---

## 1. Czym jest BatchTracker?

**BatchTracker** (lub **BatchCompletionTracker**) to komponent odpowiedzialny za śledzenie postępu przetwarzania wsadowego w architekturze Event-Driven.

### Analogia: Kontroler ruchu lotniczego

```
                    ✈️ Event 1 (Rule A)
                   /
🛫 Batch Start ----✈️ Event 2 (Rule B)---- 🛬 Batch Complete
                   \
                    ✈️ Event 3 (Rule C)

BatchTracker = Kontroler ruchu lotniczego
- Wie ile "samolotów" (eventów) wystartowało
- Śledzi które wylądowały (sukces/porażka)
- Informuje gdy wszystkie dotarły do celu
```

### Problem który rozwiązuje

W architekturze EDA, gdy wysyłamy wiele eventów równolegle:
1. **Brak natychmiastowej odpowiedzi** - eventy są asynchroniczne
2. **Trudność w określeniu końca** - nie wiadomo kiedy wszystkie zostały przetworzone
3. **Rozproszony stan** - wyniki przychodzą w różnej kolejności
4. **Obsługa błędów** - niektóre mogą się nie udać

BatchTracker agreguje te informacje i dostarcza jednolity widok na postęp.

---

## 2. Główne funkcjonalności

### 2.1 `startBatch(correlationId, expectedCount)`

Inicjuje nowy batch i rejestruje ile eventów oczekujemy.

```java
/**
 * Rozpoczyna nowy batch processing
 * @param correlationId Unikalny identyfikator batcha
 * @param expectedCount Ile eventów zostanie wysłanych
 * @param metadata Dodatkowe informacje (userId, trigger, etc.)
 */
public void startBatch(String correlationId, int expectedCount, BatchMetadata metadata) {
    BatchExecution batch = BatchExecution.builder()
        .correlationId(correlationId)
        .expectedCount(expectedCount)
        .completedCount(0)
        .failedCount(0)
        .status(BatchStatus.PENDING)
        .startedAt(Instant.now())
        .metadata(metadata)
        .correlationStates(new HashMap<>())
        .build();

    batchRepository.save(batch);
    log.info("Batch {} started, expecting {} events", correlationId, expectedCount);
}
```

### 2.2 `recordSuccess(correlationId, ruleId, result)`

Rejestruje sukces przetworzenia pojedynczego eventu.

```java
/**
 * Zapisuje sukces przetworzenia reguły
 * @param correlationId ID batcha
 * @param ruleId ID przetworzonej reguły
 * @param result Wynik przetworzenia (np. utworzone transakcje)
 */
public void recordSuccess(String correlationId, String ruleId, RuleExecutionResult result) {
    BatchExecution batch = batchRepository.findByCorrelationId(correlationId)
        .orElseThrow(() -> new BatchNotFoundException(correlationId));

    batch.getCorrelationStates().put(ruleId, CorrelationState.builder()
        .status(CorrelationStatus.COMPLETED)
        .completedAt(Instant.now())
        .result(result)
        .build());

    batch.incrementCompletedCount();
    updateBatchStatus(batch);
    batchRepository.save(batch);

    checkAndNotifyCompletion(batch);
}
```

### 2.3 `recordFailure(correlationId, ruleId, error)`

Rejestruje porażkę przetworzenia eventu.

```java
/**
 * Zapisuje błąd przetworzenia reguły
 * @param correlationId ID batcha
 * @param ruleId ID reguły która się nie powiodła
 * @param error Informacje o błędzie
 * @param retryable Czy można powtórzyć
 */
public void recordFailure(String correlationId, String ruleId,
                          ErrorDetails error, boolean retryable) {
    BatchExecution batch = batchRepository.findByCorrelationId(correlationId)
        .orElseThrow(() -> new BatchNotFoundException(correlationId));

    batch.getCorrelationStates().put(ruleId, CorrelationState.builder()
        .status(CorrelationStatus.FAILED)
        .failedAt(Instant.now())
        .error(error)
        .retryable(retryable)
        .retryCount(0)
        .build());

    batch.incrementFailedCount();
    updateBatchStatus(batch);
    batchRepository.save(batch);

    checkAndNotifyCompletion(batch);
}
```

### 2.4 `recordRetryScheduled(correlationId, ruleId, retryAt)`

Rejestruje zaplanowanie ponownej próby.

```java
/**
 * Zapisuje informację o zaplanowanym retry
 * @param correlationId ID batcha
 * @param ruleId ID reguły do ponowienia
 * @param retryAt Kiedy nastąpi ponowienie
 * @param attempt Numer próby
 */
public void recordRetryScheduled(String correlationId, String ruleId,
                                 Instant retryAt, int attempt) {
    BatchExecution batch = batchRepository.findByCorrelationId(correlationId)
        .orElseThrow(() -> new BatchNotFoundException(correlationId));

    CorrelationState state = batch.getCorrelationStates().get(ruleId);
    state.setStatus(CorrelationStatus.RETRYING);
    state.setRetryCount(attempt);
    state.setNextRetryAt(retryAt);

    // Cofnij licznik failed, bo będzie retry
    batch.decrementFailedCount();
    batch.setStatus(BatchStatus.IN_PROGRESS);

    batchRepository.save(batch);

    log.info("Retry scheduled for rule {} in batch {}, attempt {}, at {}",
             ruleId, correlationId, attempt, retryAt);
}
```

### 2.5 `getStatus(correlationId)`

Zwraca aktualny status batcha.

```java
/**
 * Pobiera status batcha
 * @param correlationId ID batcha
 * @return Szczegółowy status z postępem
 */
public BatchStatusResponse getStatus(String correlationId) {
    BatchExecution batch = batchRepository.findByCorrelationId(correlationId)
        .orElseThrow(() -> new BatchNotFoundException(correlationId));

    return BatchStatusResponse.builder()
        .correlationId(correlationId)
        .status(batch.getStatus())
        .progress(calculateProgress(batch))
        .expectedCount(batch.getExpectedCount())
        .completedCount(batch.getCompletedCount())
        .failedCount(batch.getFailedCount())
        .retryingCount(countRetrying(batch))
        .startedAt(batch.getStartedAt())
        .completedAt(batch.getCompletedAt())
        .details(mapToDetails(batch.getCorrelationStates()))
        .build();
}

private double calculateProgress(BatchExecution batch) {
    int processed = batch.getCompletedCount() + batch.getFailedCount();
    return (double) processed / batch.getExpectedCount() * 100;
}
```

### 2.6 `waitForCompletion(correlationId, timeout)`

Czeka na zakończenie batcha (używane przez API long-polling).

```java
/**
 * Czeka na zakończenie batcha (blocking)
 * @param correlationId ID batcha
 * @param timeout Maksymalny czas oczekiwania
 * @return Końcowy status lub timeout
 */
public CompletableFuture<BatchStatusResponse> waitForCompletion(
        String correlationId, Duration timeout) {

    CompletableFuture<BatchStatusResponse> future = new CompletableFuture<>();

    // Zarejestruj callback
    completionCallbacks.put(correlationId, future);

    // Ustaw timeout
    scheduler.schedule(() -> {
        if (!future.isDone()) {
            future.complete(getStatus(correlationId)); // Zwróć aktualny stan
            completionCallbacks.remove(correlationId);
        }
    }, timeout.toMillis(), TimeUnit.MILLISECONDS);

    // Sprawdź czy już nie skończone
    BatchStatusResponse current = getStatus(correlationId);
    if (current.getStatus().isTerminal()) {
        future.complete(current);
        completionCallbacks.remove(correlationId);
    }

    return future;
}

/**
 * Wywoływane gdy batch się zakończy
 */
private void checkAndNotifyCompletion(BatchExecution batch) {
    if (batch.getStatus().isTerminal()) {
        CompletableFuture<BatchStatusResponse> callback =
            completionCallbacks.remove(batch.getCorrelationId());

        if (callback != null) {
            callback.complete(mapToResponse(batch));
        }

        // Powiadom przez WebSocket
        webSocketNotifier.notifyBatchComplete(batch);
    }
}
```

---

## 3. Maszyna stanów

### 3.1 Stany batcha (BatchStatus)

```
                    ┌─────────────────────────────────────────┐
                    │                                         │
                    ▼                                         │
┌─────────┐   ┌───────────┐   ┌───────────┐   ┌───────────┐  │
│ PENDING │──▶│IN_PROGRESS│──▶│ COMPLETED │   │  FAILED   │  │
└─────────┘   └───────────┘   └───────────┘   └───────────┘  │
     │              │                               ▲         │
     │              │         ┌──────────────────┐  │         │
     │              └────────▶│PARTIALLY_FAILED  │──┘         │
     │                        └──────────────────┘            │
     │                               │                        │
     └───────────────────────────────┴────────────────────────┘
                         (retry scheduled)
```

### 3.2 Stany korelacji (CorrelationStatus)

```
┌─────────┐   ┌───────────┐   ┌───────────┐
│ PENDING │──▶│IN_PROGRESS│──▶│ COMPLETED │
└─────────┘   └───────────┘   └───────────┘
                    │
                    │ error
                    ▼
              ┌──────────┐
              │  FAILED  │◀──────────────┐
              └──────────┘               │
                    │                    │
                    │ retryable          │ max retries
                    ▼                    │ exceeded
              ┌──────────┐               │
              │ RETRYING │───────────────┘
              └──────────┘
                    │
                    │ retry success
                    ▼
              ┌───────────┐
              │ COMPLETED │
              └───────────┘
```

### 3.3 Przejścia stanów

```java
public enum BatchStatus {
    PENDING,           // Batch utworzony, eventy nie wysłane
    IN_PROGRESS,       // Co najmniej 1 event w trakcie
    COMPLETED,         // Wszystkie sukces
    PARTIALLY_FAILED,  // Niektóre sukces, niektóre fail (po wyczerpaniu retries)
    FAILED;            // Wszystkie fail

    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIALLY_FAILED || this == FAILED;
    }
}

public enum CorrelationStatus {
    PENDING,      // Event wysłany, czeka na przetworzenie
    IN_PROGRESS,  // Event właśnie przetwarzany
    COMPLETED,    // Sukces
    FAILED,       // Porażka (po wyczerpaniu retries)
    RETRYING;     // Zaplanowano retry

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
```

---

## 4. Struktura danych MongoDB

### 4.1 Dokument BatchExecution

```javascript
// Kolekcja: batch_executions
{
  "_id": ObjectId("..."),
  "correlationId": "batch-2026-02-01-abc123",

  // Liczniki
  "expectedCount": 5,
  "completedCount": 4,
  "failedCount": 1,

  // Status
  "status": "PARTIALLY_FAILED",

  // Czasy
  "startedAt": ISODate("2026-02-01T00:00:05.000Z"),
  "completedAt": ISODate("2026-02-01T00:00:12.500Z"),

  // Metadata
  "metadata": {
    "userId": "U10000001",
    "trigger": "SCHEDULER",
    "scheduledFor": ISODate("2026-02-01T00:00:00.000Z")
  },

  // Szczegóły per reguła
  "correlationStates": {
    "RULE-001": {
      "status": "COMPLETED",
      "startedAt": ISODate("2026-02-01T00:00:05.100Z"),
      "completedAt": ISODate("2026-02-01T00:00:05.800Z"),
      "result": {
        "transactionsCreated": 1,
        "cashChangeId": "CC-123456"
      }
    },
    "RULE-002": {
      "status": "COMPLETED",
      "startedAt": ISODate("2026-02-01T00:00:05.150Z"),
      "completedAt": ISODate("2026-02-01T00:00:06.200Z"),
      "result": {
        "transactionsCreated": 1,
        "cashChangeId": "CC-123457"
      }
    },
    "RULE-003": {
      "status": "FAILED",
      "startedAt": ISODate("2026-02-01T00:00:05.200Z"),
      "failedAt": ISODate("2026-02-01T00:00:12.500Z"),
      "error": {
        "type": "DATABASE_TIMEOUT",
        "message": "Connection timeout after 5000ms",
        "stackTrace": "..."
      },
      "retryable": true,
      "retryCount": 3,
      "retryHistory": [
        {
          "attempt": 1,
          "at": ISODate("2026-02-01T00:00:07.200Z"),
          "error": "DATABASE_TIMEOUT"
        },
        {
          "attempt": 2,
          "at": ISODate("2026-02-01T00:00:09.200Z"),
          "error": "DATABASE_TIMEOUT"
        },
        {
          "attempt": 3,
          "at": ISODate("2026-02-01T00:00:12.500Z"),
          "error": "DATABASE_TIMEOUT"
        }
      ]
    },
    "RULE-004": {
      "status": "COMPLETED",
      "startedAt": ISODate("2026-02-01T00:00:05.250Z"),
      "completedAt": ISODate("2026-02-01T00:00:05.900Z"),
      "result": {
        "transactionsCreated": 1,
        "cashChangeId": "CC-123458"
      }
    },
    "RULE-005": {
      "status": "COMPLETED",
      "startedAt": ISODate("2026-02-01T00:00:05.300Z"),
      "completedAt": ISODate("2026-02-01T00:00:06.100Z"),
      "result": {
        "transactionsCreated": 1,
        "cashChangeId": "CC-123459"
      }
    }
  },

  // Indeksy TTL
  "expiresAt": ISODate("2026-02-08T00:00:12.500Z")  // 7 dni retencji
}
```

### 4.2 Indeksy MongoDB

```javascript
// Szybkie wyszukiwanie po correlationId
db.batch_executions.createIndex({ "correlationId": 1 }, { unique: true });

// Wyszukiwanie aktywnych batchy
db.batch_executions.createIndex({ "status": 1, "startedAt": -1 });

// Wyszukiwanie batchy użytkownika
db.batch_executions.createIndex({ "metadata.userId": 1, "startedAt": -1 });

// TTL - automatyczne usuwanie starych rekordów
db.batch_executions.createIndex({ "expiresAt": 1 }, { expireAfterSeconds: 0 });

// Wyszukiwanie batchy do retry
db.batch_executions.createIndex({
  "correlationStates.status": 1,
  "correlationStates.nextRetryAt": 1
});
```

---

## 5. Implementacja BatchTracker

### 5.1 Pełna klasa BatchCompletionTracker

```java
package com.multi.vidulum.recurring_rules.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchCompletionTracker {

    private final BatchExecutionRepository batchRepository;
    private final ThreadPoolTaskScheduler scheduler;
    private final WebSocketNotifier webSocketNotifier;

    // Callbacks czekające na zakończenie
    private final Map<String, CompletableFuture<BatchStatusResponse>> completionCallbacks
        = new ConcurrentHashMap<>();

    /**
     * Rozpoczyna nowy batch
     */
    public void startBatch(String correlationId, int expectedCount, BatchMetadata metadata) {
        log.info("Starting batch {} with {} expected events", correlationId, expectedCount);

        BatchExecution batch = BatchExecution.builder()
            .correlationId(correlationId)
            .expectedCount(expectedCount)
            .completedCount(0)
            .failedCount(0)
            .status(BatchStatus.PENDING)
            .startedAt(Instant.now())
            .metadata(metadata)
            .correlationStates(new ConcurrentHashMap<>())
            .expiresAt(Instant.now().plus(Duration.ofDays(7)))
            .build();

        batchRepository.save(batch);
    }

    /**
     * Oznacza event jako rozpoczęty
     */
    public void recordStarted(String correlationId, String ruleId) {
        BatchExecution batch = getBatch(correlationId);

        batch.getCorrelationStates().put(ruleId, CorrelationState.builder()
            .status(CorrelationStatus.IN_PROGRESS)
            .startedAt(Instant.now())
            .build());

        if (batch.getStatus() == BatchStatus.PENDING) {
            batch.setStatus(BatchStatus.IN_PROGRESS);
        }

        batchRepository.save(batch);
    }

    /**
     * Zapisuje sukces
     */
    public void recordSuccess(String correlationId, String ruleId, RuleExecutionResult result) {
        log.debug("Recording success for rule {} in batch {}", ruleId, correlationId);

        BatchExecution batch = getBatch(correlationId);

        CorrelationState state = batch.getCorrelationStates()
            .getOrDefault(ruleId, new CorrelationState());
        state.setStatus(CorrelationStatus.COMPLETED);
        state.setCompletedAt(Instant.now());
        state.setResult(result);
        batch.getCorrelationStates().put(ruleId, state);

        batch.setCompletedCount(batch.getCompletedCount() + 1);
        updateBatchStatus(batch);
        batchRepository.save(batch);

        checkAndNotifyCompletion(batch);
    }

    /**
     * Zapisuje porażkę
     */
    public void recordFailure(String correlationId, String ruleId,
                              ErrorDetails error, boolean retryable) {
        log.warn("Recording failure for rule {} in batch {}: {}",
                 ruleId, correlationId, error.getMessage());

        BatchExecution batch = getBatch(correlationId);

        CorrelationState state = batch.getCorrelationStates()
            .getOrDefault(ruleId, new CorrelationState());
        state.setStatus(CorrelationStatus.FAILED);
        state.setFailedAt(Instant.now());
        state.setError(error);
        state.setRetryable(retryable);

        // Dodaj do historii retry jeśli to była próba
        if (state.getRetryCount() > 0) {
            state.getRetryHistory().add(RetryAttempt.builder()
                .attempt(state.getRetryCount())
                .at(Instant.now())
                .error(error.getType())
                .build());
        }

        batch.getCorrelationStates().put(ruleId, state);
        batch.setFailedCount(batch.getFailedCount() + 1);
        updateBatchStatus(batch);
        batchRepository.save(batch);

        checkAndNotifyCompletion(batch);
    }

    /**
     * Zapisuje zaplanowany retry
     */
    public void recordRetryScheduled(String correlationId, String ruleId,
                                     Instant retryAt, int attempt) {
        log.info("Retry scheduled for rule {} in batch {}, attempt {} at {}",
                 ruleId, correlationId, attempt, retryAt);

        BatchExecution batch = getBatch(correlationId);

        CorrelationState state = batch.getCorrelationStates().get(ruleId);
        if (state != null && state.getStatus() == CorrelationStatus.FAILED) {
            state.setStatus(CorrelationStatus.RETRYING);
            state.setRetryCount(attempt);
            state.setNextRetryAt(retryAt);

            // Cofnij licznik failed
            batch.setFailedCount(Math.max(0, batch.getFailedCount() - 1));
            batch.setStatus(BatchStatus.IN_PROGRESS);

            batchRepository.save(batch);
        }
    }

    /**
     * Pobiera status batcha
     */
    public BatchStatusResponse getStatus(String correlationId) {
        BatchExecution batch = getBatch(correlationId);
        return mapToResponse(batch);
    }

    /**
     * Czeka na zakończenie batcha
     */
    public CompletableFuture<BatchStatusResponse> waitForCompletion(
            String correlationId, Duration timeout) {

        CompletableFuture<BatchStatusResponse> future = new CompletableFuture<>();

        // Sprawdź czy już nie skończone
        BatchStatusResponse current = getStatus(correlationId);
        if (current.getStatus().isTerminal()) {
            future.complete(current);
            return future;
        }

        // Zarejestruj callback
        completionCallbacks.put(correlationId, future);

        // Ustaw timeout
        scheduler.schedule(() -> {
            if (!future.isDone()) {
                log.debug("Timeout waiting for batch {}", correlationId);
                future.complete(getStatus(correlationId));
                completionCallbacks.remove(correlationId);
            }
        }, Instant.now().plus(timeout));

        return future;
    }

    /**
     * Sprawdza czy batch jest w stanie terminalnym
     */
    public boolean isComplete(String correlationId) {
        return getStatus(correlationId).getStatus().isTerminal();
    }

    // === Private helpers ===

    private BatchExecution getBatch(String correlationId) {
        return batchRepository.findByCorrelationId(correlationId)
            .orElseThrow(() -> new BatchNotFoundException(correlationId));
    }

    private void updateBatchStatus(BatchExecution batch) {
        int total = batch.getExpectedCount();
        int completed = batch.getCompletedCount();
        int failed = batch.getFailedCount();
        int retrying = countRetrying(batch);
        int processed = completed + failed;

        if (processed < total || retrying > 0) {
            batch.setStatus(BatchStatus.IN_PROGRESS);
        } else if (failed == 0) {
            batch.setStatus(BatchStatus.COMPLETED);
            batch.setCompletedAt(Instant.now());
        } else if (completed == 0) {
            batch.setStatus(BatchStatus.FAILED);
            batch.setCompletedAt(Instant.now());
        } else {
            batch.setStatus(BatchStatus.PARTIALLY_FAILED);
            batch.setCompletedAt(Instant.now());
        }
    }

    private void checkAndNotifyCompletion(BatchExecution batch) {
        if (batch.getStatus().isTerminal()) {
            log.info("Batch {} completed with status {}",
                     batch.getCorrelationId(), batch.getStatus());

            // Powiadom callbacki
            CompletableFuture<BatchStatusResponse> callback =
                completionCallbacks.remove(batch.getCorrelationId());
            if (callback != null) {
                callback.complete(mapToResponse(batch));
            }

            // Powiadom WebSocket
            webSocketNotifier.notifyBatchComplete(
                batch.getMetadata().getUserId(),
                mapToResponse(batch)
            );
        }
    }

    private int countRetrying(BatchExecution batch) {
        return (int) batch.getCorrelationStates().values().stream()
            .filter(s -> s.getStatus() == CorrelationStatus.RETRYING)
            .count();
    }

    private BatchStatusResponse mapToResponse(BatchExecution batch) {
        double progress = batch.getExpectedCount() > 0
            ? (double)(batch.getCompletedCount() + batch.getFailedCount())
              / batch.getExpectedCount() * 100
            : 0;

        return BatchStatusResponse.builder()
            .correlationId(batch.getCorrelationId())
            .status(batch.getStatus())
            .progress(progress)
            .expectedCount(batch.getExpectedCount())
            .completedCount(batch.getCompletedCount())
            .failedCount(batch.getFailedCount())
            .retryingCount(countRetrying(batch))
            .startedAt(batch.getStartedAt())
            .completedAt(batch.getCompletedAt())
            .details(batch.getCorrelationStates().entrySet().stream()
                .map(e -> RuleExecutionDetail.builder()
                    .ruleId(e.getKey())
                    .status(e.getValue().getStatus())
                    .result(e.getValue().getResult())
                    .error(e.getValue().getError())
                    .retryCount(e.getValue().getRetryCount())
                    .build())
                .toList())
            .build();
    }
}
```

### 5.2 Modele danych

```java
// BatchExecution.java
@Document(collection = "batch_executions")
@Data
@Builder
public class BatchExecution {
    @Id
    private String id;

    @Indexed(unique = true)
    private String correlationId;

    private int expectedCount;
    private int completedCount;
    private int failedCount;

    private BatchStatus status;

    private Instant startedAt;
    private Instant completedAt;
    private Instant expiresAt;

    private BatchMetadata metadata;
    private Map<String, CorrelationState> correlationStates;
}

// CorrelationState.java
@Data
@Builder
public class CorrelationState {
    private CorrelationStatus status;
    private Instant startedAt;
    private Instant completedAt;
    private Instant failedAt;
    private Instant nextRetryAt;

    private RuleExecutionResult result;
    private ErrorDetails error;

    private boolean retryable;
    private int retryCount;

    @Builder.Default
    private List<RetryAttempt> retryHistory = new ArrayList<>();
}

// BatchMetadata.java
@Data
@Builder
public class BatchMetadata {
    private String userId;
    private String trigger; // SCHEDULER, MANUAL, CATCHUP
    private Instant scheduledFor;
    private Map<String, Object> additionalData;
}

// BatchStatusResponse.java
@Data
@Builder
public class BatchStatusResponse {
    private String correlationId;
    private BatchStatus status;
    private double progress;
    private int expectedCount;
    private int completedCount;
    private int failedCount;
    private int retryingCount;
    private Instant startedAt;
    private Instant completedAt;
    private List<RuleExecutionDetail> details;
}
```

---

## 6. Wzorce sekwencyjnego przetwarzania

Gdy potrzebujemy wykonać kilka batchy **sekwencyjnie** (jeden po drugim) aby utrzymać spójność danych, mamy do wyboru kilka wzorców.

### 6.1 Batch Chain (najprostszy)

**Opis**: Każdy batch czeka na zakończenie poprzedniego przed rozpoczęciem.

```
Batch 1 ────────────▶ Batch 2 ────────────▶ Batch 3
[Execute Rules]       [Calculate Totals]   [Generate Report]
```

**Implementacja**:

```java
@Service
@RequiredArgsConstructor
public class BatchChainExecutor {

    private final BatchCompletionTracker tracker;
    private final RuleBatchExecutor ruleBatchExecutor;
    private final TotalsBatchExecutor totalsBatchExecutor;
    private final ReportBatchExecutor reportBatchExecutor;

    /**
     * Wykonuje łańcuch batchy sekwencyjnie
     */
    public CompletableFuture<ChainResult> executeChain(String userId, YearMonth period) {
        String chainId = UUID.randomUUID().toString();

        return CompletableFuture.supplyAsync(() -> {
            ChainResult result = new ChainResult(chainId);

            // === BATCH 1: Execute Rules ===
            log.info("[Chain {}] Starting Batch 1: Execute Rules", chainId);
            String batch1Id = ruleBatchExecutor.execute(userId, period);
            BatchStatusResponse batch1Result = tracker
                .waitForCompletion(batch1Id, Duration.ofMinutes(5))
                .join();

            if (batch1Result.getStatus() == BatchStatus.FAILED) {
                result.setStatus(ChainStatus.FAILED);
                result.setFailedAt("BATCH_1_EXECUTE_RULES");
                return result;
            }
            result.addBatchResult("executeRules", batch1Result);

            // === BATCH 2: Calculate Totals ===
            log.info("[Chain {}] Starting Batch 2: Calculate Totals", chainId);
            String batch2Id = totalsBatchExecutor.execute(userId, period);
            BatchStatusResponse batch2Result = tracker
                .waitForCompletion(batch2Id, Duration.ofMinutes(2))
                .join();

            if (batch2Result.getStatus() == BatchStatus.FAILED) {
                result.setStatus(ChainStatus.FAILED);
                result.setFailedAt("BATCH_2_CALCULATE_TOTALS");
                return result;
            }
            result.addBatchResult("calculateTotals", batch2Result);

            // === BATCH 3: Generate Report ===
            log.info("[Chain {}] Starting Batch 3: Generate Report", chainId);
            String batch3Id = reportBatchExecutor.execute(userId, period);
            BatchStatusResponse batch3Result = tracker
                .waitForCompletion(batch3Id, Duration.ofMinutes(1))
                .join();

            result.addBatchResult("generateReport", batch3Result);
            result.setStatus(batch3Result.getStatus() == BatchStatus.COMPLETED
                ? ChainStatus.COMPLETED
                : ChainStatus.PARTIALLY_COMPLETED);

            log.info("[Chain {}] Completed with status {}", chainId, result.getStatus());
            return result;
        });
    }
}
```

**Diagram czasowy**:

```
Czas    0s        5s        10s       15s       20s
        │         │         │         │         │
Batch 1 ├─────────┤
        │ Execute │
        │  Rules  │
        │         │
Batch 2           ├─────────┤
                  │Calculate│
                  │ Totals  │
                  │         │
Batch 3                     ├─────────┤
                            │Generate │
                            │ Report  │
```

### 6.2 Pipeline Pattern

**Opis**: Bardziej elastyczny wzorzec z explicite zdefiniowanymi zależnościami między etapami.

```
           ┌──────────────┐
           │ Extract Data │ Stage 1
           └──────┬───────┘
                  │
        ┌─────────┴─────────┐
        ▼                   ▼
┌───────────────┐   ┌───────────────┐
│ Validate Rules│   │ Load History  │ Stage 2 (parallel)
└───────┬───────┘   └───────┬───────┘
        │                   │
        └─────────┬─────────┘
                  ▼
         ┌───────────────┐
         │ Execute Rules │ Stage 3
         └───────┬───────┘
                  │
                  ▼
         ┌───────────────┐
         │ Update Totals │ Stage 4
         └───────────────┘
```

**Implementacja**:

```java
@Service
@RequiredArgsConstructor
public class BatchPipelineExecutor {

    private final BatchCompletionTracker tracker;
    private final Map<String, BatchStageExecutor> stageExecutors;

    /**
     * Definiuje i wykonuje pipeline
     */
    public CompletableFuture<PipelineResult> executePipeline(PipelineDefinition pipeline) {
        String pipelineId = UUID.randomUUID().toString();
        PipelineState state = new PipelineState(pipelineId, pipeline);

        return executeStage(state, pipeline.getFirstStage())
            .thenApply(result -> {
                state.complete();
                return state.getResult();
            });
    }

    private CompletableFuture<Void> executeStage(PipelineState state, PipelineStage stage) {
        // Sprawdź czy wszystkie zależności są spełnione
        if (!state.areDependenciesMet(stage)) {
            log.debug("Stage {} waiting for dependencies", stage.getName());
            return CompletableFuture.completedFuture(null);
        }

        log.info("[Pipeline {}] Executing stage: {}", state.getPipelineId(), stage.getName());

        // Wykonaj batch dla tego stage'a
        BatchStageExecutor executor = stageExecutors.get(stage.getExecutorType());
        String batchId = executor.execute(state.getContext(), stage.getConfig());

        return tracker.waitForCompletion(batchId, stage.getTimeout())
            .thenCompose(batchResult -> {
                state.recordStageCompletion(stage, batchResult);

                if (batchResult.getStatus() == BatchStatus.FAILED && stage.isRequired()) {
                    return CompletableFuture.failedFuture(
                        new StageFailedException(stage.getName(), batchResult));
                }

                // Uruchom następne stage'e których zależności są teraz spełnione
                List<CompletableFuture<Void>> nextStages = stage.getDependents().stream()
                    .filter(s -> state.areDependenciesMet(s))
                    .map(s -> executeStage(state, s))
                    .toList();

                return CompletableFuture.allOf(nextStages.toArray(new CompletableFuture[0]));
            });
    }
}

// Definicja pipeline'u
@Data
@Builder
public class PipelineDefinition {
    private String name;
    private List<PipelineStage> stages;

    public PipelineStage getFirstStage() {
        return stages.stream()
            .filter(s -> s.getDependencies().isEmpty())
            .findFirst()
            .orElseThrow();
    }
}

@Data
@Builder
public class PipelineStage {
    private String name;
    private String executorType;
    private Map<String, Object> config;
    private Duration timeout;
    private boolean required;
    private List<String> dependencies;  // Nazwy stage'ów od których zależy
    private List<PipelineStage> dependents; // Stage'e które od nas zależą
}
```

**Przykład użycia**:

```java
PipelineDefinition pipeline = PipelineDefinition.builder()
    .name("RecurringRulesMonthlyPipeline")
    .stages(List.of(
        PipelineStage.builder()
            .name("extract")
            .executorType("EXTRACT_DATA")
            .timeout(Duration.ofMinutes(2))
            .required(true)
            .dependencies(List.of())
            .build(),
        PipelineStage.builder()
            .name("validateRules")
            .executorType("VALIDATE_RULES")
            .timeout(Duration.ofMinutes(1))
            .required(true)
            .dependencies(List.of("extract"))
            .build(),
        PipelineStage.builder()
            .name("loadHistory")
            .executorType("LOAD_HISTORY")
            .timeout(Duration.ofMinutes(2))
            .required(false) // Optional
            .dependencies(List.of("extract"))
            .build(),
        PipelineStage.builder()
            .name("executeRules")
            .executorType("EXECUTE_RULES")
            .timeout(Duration.ofMinutes(5))
            .required(true)
            .dependencies(List.of("validateRules", "loadHistory"))
            .build(),
        PipelineStage.builder()
            .name("updateTotals")
            .executorType("UPDATE_TOTALS")
            .timeout(Duration.ofMinutes(1))
            .required(true)
            .dependencies(List.of("executeRules"))
            .build()
    ))
    .build();

pipelineExecutor.executePipeline(pipeline);
```

### 6.3 Saga Pattern (z kompensacją)

**Opis**: Każdy krok ma zdefiniowaną kompensację (rollback) na wypadek błędu. Zapewnia spójność przez cofanie zmian.

```
Forward Flow:
Step 1 ──▶ Step 2 ──▶ Step 3 ──▶ Step 4
  ✓          ✓          ✗
                        │
Compensation Flow:      │
                        ▼
              Comp 2 ◀── Comp 1
                ✓          ✓
```

**Implementacja**:

```java
@Service
@RequiredArgsConstructor
public class SagaOrchestrator {

    private final BatchCompletionTracker tracker;
    private final SagaStateRepository sagaRepository;

    /**
     * Wykonuje sagę z automatyczną kompensacją przy błędzie
     */
    public CompletableFuture<SagaResult> executeSaga(SagaDefinition saga) {
        String sagaId = UUID.randomUUID().toString();
        SagaState state = SagaState.create(sagaId, saga);
        sagaRepository.save(state);

        return executeNextStep(state)
            .exceptionally(error -> {
                log.error("[Saga {}] Failed, starting compensation", sagaId, error);
                return compensate(state).join();
            });
    }

    private CompletableFuture<SagaResult> executeNextStep(SagaState state) {
        SagaStep currentStep = state.getCurrentStep();
        if (currentStep == null) {
            // Wszystkie kroki wykonane
            state.complete();
            sagaRepository.save(state);
            return CompletableFuture.completedFuture(state.getResult());
        }

        log.info("[Saga {}] Executing step: {}", state.getSagaId(), currentStep.getName());

        // Wykonaj krok
        String batchId = currentStep.getExecutor().execute(state.getContext());

        return tracker.waitForCompletion(batchId, currentStep.getTimeout())
            .thenCompose(batchResult -> {
                if (batchResult.getStatus() == BatchStatus.FAILED) {
                    throw new SagaStepFailedException(currentStep.getName(), batchResult);
                }

                // Zapisz wynik kroku (potrzebne do ewentualnej kompensacji)
                state.recordStepSuccess(currentStep, batchResult);
                sagaRepository.save(state);

                // Przejdź do następnego kroku
                state.moveToNextStep();
                return executeNextStep(state);
            });
    }

    /**
     * Wykonuje kompensację (rollback) dla wszystkich ukończonych kroków
     */
    private CompletableFuture<SagaResult> compensate(SagaState state) {
        state.startCompensation();
        sagaRepository.save(state);

        // Kompensuj w odwrotnej kolejności
        List<SagaStep> completedSteps = state.getCompletedSteps();
        Collections.reverse(completedSteps);

        return executeCompensationChain(state, completedSteps.iterator());
    }

    private CompletableFuture<SagaResult> executeCompensationChain(
            SagaState state, Iterator<SagaStep> steps) {

        if (!steps.hasNext()) {
            state.compensationComplete();
            sagaRepository.save(state);
            return CompletableFuture.completedFuture(state.getResult());
        }

        SagaStep step = steps.next();
        CompensationAction compensation = step.getCompensation();

        if (compensation == null) {
            log.info("[Saga {}] No compensation for step: {}",
                     state.getSagaId(), step.getName());
            return executeCompensationChain(state, steps);
        }

        log.info("[Saga {}] Compensating step: {}", state.getSagaId(), step.getName());

        // Pobierz wynik oryginalnego kroku (potrzebne do cofnięcia)
        StepResult originalResult = state.getStepResult(step);
        String batchId = compensation.execute(state.getContext(), originalResult);

        return tracker.waitForCompletion(batchId, compensation.getTimeout())
            .thenCompose(result -> {
                state.recordCompensation(step, result);
                sagaRepository.save(state);
                return executeCompensationChain(state, steps);
            })
            .exceptionally(error -> {
                // Kompensacja się nie powiodła - krytyczny błąd
                log.error("[Saga {}] CRITICAL: Compensation failed for step {}",
                          state.getSagaId(), step.getName(), error);
                state.compensationFailed(step, error);
                sagaRepository.save(state);
                // Może wymagać manualnej interwencji
                alertService.sendCriticalAlert(state);
                return state.getResult();
            });
    }
}

// Definicja sagi
@Data
@Builder
public class SagaDefinition {
    private String name;
    private List<SagaStep> steps;
}

@Data
@Builder
public class SagaStep {
    private String name;
    private StepExecutor executor;
    private CompensationAction compensation; // Może być null jeśli nie wymaga rollbacku
    private Duration timeout;
}

// Przykład kompensacji
@Component
public class CreateTransactionsCompensation implements CompensationAction {

    private final CashFlowService cashFlowService;

    @Override
    public String execute(SagaContext context, StepResult originalResult) {
        // Pobierz ID utworzonych transakcji
        List<String> createdTransactionIds = originalResult.getData("transactionIds");

        // Usuń je
        String batchId = UUID.randomUUID().toString();
        for (String txId : createdTransactionIds) {
            cashFlowService.deleteTransaction(txId);
        }

        return batchId;
    }
}
```

**Przykład użycia Saga**:

```java
SagaDefinition saga = SagaDefinition.builder()
    .name("RecurringRulesExecution")
    .steps(List.of(
        SagaStep.builder()
            .name("executeRules")
            .executor(new ExecuteRulesExecutor())
            .compensation(new DeleteCreatedTransactionsCompensation())
            .timeout(Duration.ofMinutes(5))
            .build(),
        SagaStep.builder()
            .name("recalculateTotals")
            .executor(new RecalculateTotalsExecutor())
            .compensation(new RestorePreviousTotalsCompensation())
            .timeout(Duration.ofMinutes(2))
            .build(),
        SagaStep.builder()
            .name("sendNotifications")
            .executor(new SendNotificationsExecutor())
            .compensation(null) // Notyfikacje nie wymagają rollbacku
            .timeout(Duration.ofMinutes(1))
            .build()
    ))
    .build();

sagaOrchestrator.executeSaga(saga)
    .thenAccept(result -> {
        if (result.getStatus() == SagaStatus.COMPLETED) {
            log.info("Saga completed successfully");
        } else if (result.getStatus() == SagaStatus.COMPENSATED) {
            log.warn("Saga failed but was successfully compensated");
        } else {
            log.error("Saga failed and compensation also failed!");
        }
    });
```

---

## 7. Porównanie wzorców

| Aspekt | Batch Chain | Pipeline | Saga |
|--------|-------------|----------|------|
| **Złożoność** | Niska | Średnia | Wysoka |
| **Elastyczność** | Niska | Wysoka | Średnia |
| **Równoległość** | Brak | Tak (w ramach stage'u) | Brak |
| **Rollback** | Brak | Brak | Pełny |
| **Zależności** | Liniowe | Dowolne (DAG) | Liniowe |
| **Monitorowanie** | Proste | Złożone | Średnie |
| **Przypadki użycia** | Proste sekwencje | Złożone ETL | Transakcje rozproszone |

### Kiedy użyć którego?

**Batch Chain**:
- Proste sekwencje 2-4 kroków
- Nie potrzeba rollbacku
- Każdy krok zależy od poprzedniego

**Pipeline**:
- Złożone przepływy z wieloma zależnościami
- Możliwość równoległego wykonania niektórych kroków
- Procesy ETL, data processing

**Saga**:
- Wymagana spójność transakcyjna
- Możliwość i konieczność rollbacku
- Operacje na wielu agregatach/serwisach

---

## 8. Rekomendacja dla Recurring Rules

Dla funkcjonalności Recurring Rules **rekomendowany jest Batch Chain** z następujących powodów:

### Dlaczego Batch Chain?

1. **Prostota**: Mamy jasną sekwencję:
   - Wykonaj reguły → Przelicz sumy → (opcjonalnie) Wyślij powiadomienia

2. **Wystarczająca funkcjonalność**:
   - Nie potrzebujemy złożonych zależności (Pipeline)
   - Nie potrzebujemy pełnego rollbacku (Saga)

3. **Łatwe testowanie**:
   - Każdy batch można testować osobno
   - Prosta ścieżka wykonania

4. **Idempotentność zamiast rollbacku**:
   - Reguły są idempotentne (sprawdzają czy transakcja już istnieje)
   - Przy błędzie można po prostu ponowić cały proces

### Proponowana implementacja

```java
@Service
@RequiredArgsConstructor
public class RecurringRulesMonthlyBatch {

    private final BatchCompletionTracker tracker;
    private final RuleExecutionService ruleExecutionService;
    private final TotalsRecalculationService totalsService;
    private final NotificationService notificationService;

    /**
     * Główny entry point - wywoływany przez scheduler
     */
    @Scheduled(cron = "0 1 0 1 * *") // 00:01 pierwszego dnia miesiąca
    public void executeMonthlyBatch() {
        YearMonth currentMonth = YearMonth.now();
        YearMonth previousMonth = currentMonth.minusMonths(1);

        List<String> userIds = getUsersWithActiveRules();

        for (String userId : userIds) {
            executeChainForUser(userId, previousMonth)
                .exceptionally(error -> {
                    log.error("Failed to execute chain for user {}", userId, error);
                    alertService.notifyBatchFailure(userId, previousMonth, error);
                    return null;
                });
        }
    }

    public CompletableFuture<ChainResult> executeChainForUser(String userId, YearMonth period) {
        String chainId = generateChainId(userId, period);

        return CompletableFuture.supplyAsync(() -> {
            ChainResult result = new ChainResult(chainId);

            // BATCH 1: Execute Rules
            String batch1Id = ruleExecutionService.executeAllRules(userId, period);
            BatchStatusResponse batch1 = tracker
                .waitForCompletion(batch1Id, Duration.ofMinutes(5))
                .join();

            result.addBatchResult("executeRules", batch1);

            if (batch1.getStatus() == BatchStatus.FAILED) {
                result.setStatus(ChainStatus.FAILED);
                return result;
            }

            // BATCH 2: Recalculate Totals
            String batch2Id = totalsService.recalculate(userId, period);
            BatchStatusResponse batch2 = tracker
                .waitForCompletion(batch2Id, Duration.ofMinutes(2))
                .join();

            result.addBatchResult("recalculateTotals", batch2);

            // BATCH 3: Send Notifications (nie blokujemy na wyniku)
            notificationService.sendMonthlyReportAsync(userId, period, result);

            result.setStatus(batch2.getStatus() == BatchStatus.COMPLETED
                ? ChainStatus.COMPLETED
                : ChainStatus.PARTIALLY_COMPLETED);

            return result;
        });
    }
}
```

### Rozszerzenie w przyszłości

Jeśli w przyszłości pojawią się wymagania dotyczące:
- **Równoległego przetwarzania** → Migracja do Pipeline
- **Transakcyjności i rollbacku** → Migracja do Saga
- **Bardziej złożonych zależności** → Migracja do Pipeline

Struktura kodu pozwala na łatwą migrację, ponieważ:
1. BatchTracker pozostaje ten sam
2. Logika biznesowa (executory) pozostaje ta sama
3. Zmienia się tylko orkiestracja (Chain → Pipeline/Saga)

---

## Podsumowanie

| Komponent | Odpowiedzialność |
|-----------|------------------|
| **BatchCompletionTracker** | Śledzenie postępu, agregacja wyników, powiadomienia |
| **Batch Chain** | Sekwencyjne wykonanie batchy (rekomendowane) |
| **Pipeline** | Złożone zależności, równoległość (na przyszłość) |
| **Saga** | Transakcyjność z kompensacją (jeśli potrzeba) |

Kluczowe decyzje:
1. **Batch Chain** wystarczy dla Recurring Rules
2. **BatchTracker** jako centralne miejsce monitorowania
3. **Idempotentność** zamiast rollbacku
4. **Możliwość migracji** do bardziej złożonych wzorców
