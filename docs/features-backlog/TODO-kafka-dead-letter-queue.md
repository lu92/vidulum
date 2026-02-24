# TODO: Kafka Dead Letter Queue (DLQ) Implementation

## Problem

### Opis
W `HistoricalCashChangeImportedEventHandler` (oraz potencjalnie innych handlerach Kafka) istnieje problem z obsługą "poison messages" - wiadomości które nie mogą być przetworzone.

### Obecne zachowanie
1. Handler otrzymuje event `HistoricalCashChangeImportedEvent`
2. Próbuje znaleźć `CashFlowForecastStatement` w MongoDB
3. Jeśli nie znajdzie - retry z exponential backoff (10 prób, ~13 sekund łącznie)
4. Po wyczerpaniu prób - **rzuca wyjątek**
5. Kafka consumer nie commituje offsetu
6. Consumer próbuje przetworzyć tę samą wiadomość ponownie
7. **INFINITE LOOP** - cały consumer jest zablokowany

### Konsekwencje
- Jeden uszkodzony event blokuje przetwarzanie wszystkich kolejnych eventów
- Brak widoczności problemu (poza logami WARN)
- System przestaje reagować na nowe eventy

### Kiedy to może wystąpić
1. **Testy z shared Testcontainers**: Kafka zachowuje wiadomości między uruchomieniami testów, ale MongoDB może mieć inne dane
2. **Produkcja - race condition**: Event wysłany zanim CashFlow został w pełni zapisany
3. **Produkcja - usunięcie danych**: CashFlow usunięty, ale eventy pozostały w Kafka
4. **Produkcja - błąd replikacji**: MongoDB replica lag

## Tymczasowe rozwiązanie (zaimplementowane)

W `HistoricalCashChangeImportedEventHandler.handleWithRetry()`:
- Po wyczerpaniu prób dla `CashFlowDoesNotExistsException` - logujemy ERROR i skipujemy wiadomość
- Consumer może kontynuować przetwarzanie kolejnych wiadomości
- Błąd jest widoczny w logach

**Wady tego rozwiązania:**
- Wiadomość jest tracona bezpowrotnie
- Brak możliwości późniejszego przetworzenia
- Wymaga ręcznej interwencji na podstawie logów

## Docelowe rozwiązanie: Dead Letter Queue

### Architektura

```
                                    ┌─────────────────┐
                                    │   DLQ Topic     │
                                    │ (cash_flow_dlq) │
                                    └────────┬────────┘
                                             │
                                             ▼
┌──────────┐    ┌─────────────┐    ┌─────────────────┐    ┌──────────────┐
│  Kafka   │───▶│  Consumer   │───▶│    Handler      │───▶│   MongoDB    │
│  Topic   │    │ (group_id7) │    │ (processing)    │    │  (success)   │
└──────────┘    └─────────────┘    └────────┬────────┘    └──────────────┘
                                            │
                                            │ (failure after N retries)
                                            ▼
                                   ┌─────────────────┐
                                   │  DLQ Producer   │
                                   │ (send to DLQ)   │
                                   └─────────────────┘
```

### Implementacja

#### 1. Konfiguracja DLQ topic
```java
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic cashFlowDlqTopic() {
        return TopicBuilder.name("cash_flow_dlq")
                .partitions(1)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "604800000") // 7 dni
                .build();
    }
}
```

#### 2. DLQ Message format
```java
public record DlqMessage(
    String originalTopic,
    String originalKey,
    String originalPayload,
    String errorMessage,
    String exceptionClass,
    int retryCount,
    ZonedDateTime failedAt,
    Map<String, String> metadata
) {}
```

#### 3. Error Handler z DLQ
```java
@Component
@AllArgsConstructor
public class KafkaDlqErrorHandler {

    private final KafkaTemplate<String, DlqMessage> dlqTemplate;

    public void sendToDlq(ConsumerRecord<?, ?> record, Exception exception, int retryCount) {
        DlqMessage dlqMessage = new DlqMessage(
            record.topic(),
            record.key() != null ? record.key().toString() : null,
            record.value().toString(),
            exception.getMessage(),
            exception.getClass().getName(),
            retryCount,
            ZonedDateTime.now(),
            Map.of(
                "partition", String.valueOf(record.partition()),
                "offset", String.valueOf(record.offset())
            )
        );

        dlqTemplate.send("cash_flow_dlq", dlqMessage);
        log.error("Message sent to DLQ: topic={}, offset={}, error={}",
            record.topic(), record.offset(), exception.getMessage());
    }
}
```

#### 4. Modyfikacja handlera
```java
// W HistoricalCashChangeImportedEventHandler
private final KafkaDlqErrorHandler dlqErrorHandler;

private void handleWithRetry(CashFlowEvent.HistoricalCashChangeImportedEvent event,
                             ConsumerRecord<?, ?> record) {
    // ... retry logic ...

    // After retries exhausted:
    dlqErrorHandler.sendToDlq(record, lastException, MAX_RETRIES);
    // Don't throw - let consumer commit and move on
}
```

### Monitoring i Alerting

#### Metryki do monitorowania
- `kafka.dlq.messages.total` - liczba wiadomości w DLQ
- `kafka.dlq.messages.by_error_type` - podział po typie błędu
- `kafka.consumer.lag` - opóźnienie consumera

#### Alerty
- **CRITICAL**: DLQ ma więcej niż 10 wiadomości w ciągu godziny
- **WARNING**: DLQ ma jakiekolwiek wiadomości
- **INFO**: Consumer lag > 1000

### Narzędzia do obsługi DLQ

#### 1. DLQ Viewer (Admin endpoint)
```java
@RestController
@RequestMapping("/admin/dlq")
public class DlqAdminController {

    @GetMapping("/messages")
    public List<DlqMessage> getMessages(@RequestParam(defaultValue = "100") int limit);

    @PostMapping("/replay/{messageId}")
    public void replayMessage(@PathVariable String messageId);

    @DeleteMapping("/{messageId}")
    public void deleteMessage(@PathVariable String messageId);
}
```

#### 2. Automatyczny replay (opcjonalnie)
- Scheduled job próbujący przetworzyć wiadomości z DLQ
- Exponential backoff między próbami
- Max retry count per message

## Plan wdrożenia

### Faza 1 (zrobione)
- [x] Tymczasowe rozwiązanie: skip poison messages z ERROR logiem

### Faza 2 (do zrobienia)
- [ ] Utworzenie DLQ topic
- [ ] Implementacja `KafkaDlqErrorHandler`
- [ ] Modyfikacja wszystkich handlerów do używania DLQ
- [ ] Dodanie metryk Micrometer

### Faza 3 (przyszłość)
- [ ] Admin UI do przeglądania DLQ
- [ ] Automatyczny replay z backoff
- [ ] Integracja z systemem alertingu

## Powiązane pliki

- `src/main/java/com/multi/vidulum/cashflow_forecast_processor/app/processing/HistoricalCashChangeImportedEventHandler.java`
- `src/main/java/com/multi/vidulum/cashflow_forecast_processor/app/CashFlowEventListener.java`
- `src/main/java/com/multi/vidulum/config/KafkaConsumerConfig.java`

## Referencje

- [Spring Kafka Error Handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)
- [Kafka Dead Letter Queue Pattern](https://www.confluent.io/blog/kafka-connect-deep-dive-error-handling-dead-letter-queues/)
