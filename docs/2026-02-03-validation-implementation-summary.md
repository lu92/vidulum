# Podsumowanie implementacji walidacji CashFlow - 2026-02-03

## Kontekst problemu

W `CategoryCreatedEventHandler` (linia 38) występował NPE:
```java
statement.getBankAccountNumber().denomination().getId()
```
Gdy `getBankAccountNumber()` zwracało `null`, wywoływanie `.denomination()` powodowało NPE, co blokowało przetwarzanie eventów Kafka.

## Co zostało zrobione

### 1. Utworzono dedykowane DTO z walidacją Jakarta Bean Validation

W pliku `CashFlowDto.java` dodano:

- **`BankAccountJson`** - DTO dla konta bankowego
  - `bankName` (String, opcjonalne)
  - `bankAccountNumber` (`@NotNull @Valid`)
  - `balance` (opcjonalne)
  - Metody konwersji: `toDomain()`, `from()`

- **`BankAccountNumberJson`** - DTO dla numeru konta
  - `account` (`@NotBlank`)
  - `denomination` (`@NotNull @Valid`)
  - Metody konwersji: `toDomain()`, `from()`

- **`CurrencyJson`** - DTO dla waluty
  - `id` (`@NotBlank`)
  - Metody konwersji: `toDomain()`, `from()`

- **`MoneyJson`** - DTO dla kwoty
  - `amount` (`@NotNull`)
  - `currency` (`@NotBlank`)
  - Metody konwersji: `toDomain()`, `from()`

### 2. Zaktualizowano istniejące DTO

- **`CreateCashFlowJson`**:
  - `userId` - `@NotBlank`
  - `name` - `@NotBlank`
  - `description` - opcjonalne
  - `bankAccount` - `@NotNull @Valid` (zmieniono typ na `BankAccountJson`)

- **`CreateCashFlowWithHistoryJson`**:
  - `userId` - `@NotBlank`
  - `name` - `@NotBlank`
  - `description` - opcjonalne
  - `bankAccount` - `@NotNull @Valid` (zmieniono typ na `BankAccountJson`)
  - `startPeriod` - `@NotBlank`
  - `initialBalance` - `@NotNull @Valid` (zmieniono typ na `MoneyJson`)

### 3. Dodano `@Valid` do endpointów kontrolera

W `CashFlowRestController.java`:
- `createCashFlow()` - linia 51: `@Valid @RequestBody`
- `createCashFlowWithHistory()` - linia 69: `@Valid @RequestBody`

### 4. Naprawiono błędy kompilacji w testach

Zaktualizowano pliki testowe używające starej struktury DTO:
- `CashFlowControllerTest.java` (~80 zmian)
- `CashFlowForecastControllerTest.java`
- `BankDataIngestionControllerTest.java`
- `DualCashflowStatementGeneratorWithHistory.java`
- `CashflowStatementViaAIGenerator.java`
- `DualCashflowStatementGenerator.java`
- `CashFlowForecastStatementGenerator.java`
- `CashFlowHttpActor.java`
- `BankDataIngestionHttpActor.java`

## Co działa

### Testy jednostkowe/integracyjne
- `CashFlowControllerTest` (77 testów) - **PASS**
- `CashFlowForecastControllerTest` (10 testów) - **PASS**
- `BankDataIngestionControllerTest` (32 testów) - **PASS**

### Walidacja (potwierdzona manualnie na Docker)
- Brakujący `bankAccountNumber` zwraca:
  ```json
  {"status":400,"code":"VALIDATION_ERROR","fieldErrors":[{"field":"bankAccount.bankAccountNumber","message":"bankAccount.bankAccountNumber is required"}]}
  ```
- Brakujący `denomination` zwraca:
  ```json
  {"status":400,"code":"VALIDATION_ERROR","fieldErrors":[{"field":"bankAccount.bankAccountNumber.denomination","message":"bankAccount.bankAccountNumber.denomination is required"}]}
  ```

## Co nie działa / wymaga sprawdzenia

### 1. Testy HTTP z poprawnymi requestami zwracają `VALIDATION_INVALID_JSON`
- Testy jednostkowe przechodzą (używają bezpośrednio kontrolera)
- Testy manualne curl na Docker zwracają błąd parsowania JSON dla poprawnych requestów
- Prawdopodobna przyczyna: konfiguracja Jackson ObjectMapper w środowisku produkcyjnym
- **Do zbadania**: różnice w konfiguracji Jackson między testami a produkcją

### 2. `CashFlowErrorHandlingTest` - niektóre testy nie przechodzą
- Testy walidacji dla `CreateCashFlow` i `CreateCashFlowWithHistory` zostały usunięte z powodu problemów z serializacją null w JSON
- Istniejące testy dla `/confirm`, `/edit`, `/reject` działają poprawnie

### 3. Edycja CashChange - do przetestowania manualnie
- Funkcjonalność edycji została zaimplementowana wcześniej
- Wymaga manualnego potwierdzenia po naprawieniu NPE

## Do zaimplementowania / sprawdzenia

1. **Zbadać problem `VALIDATION_INVALID_JSON`** dla poprawnych requestów HTTP
2. **Przetestować manualnie edycję CashChange** po naprawieniu NPE
3. **Rozważyć dodanie testów integracyjnych** dla walidacji używając raw JSON (Map) zamiast builderów
4. **Sprawdzić czy NPE w CategoryCreatedEventHandler** już nie występuje przy tworzeniu nowych CashFlow

## Pliki zmienione

```
src/main/java/com/multi/vidulum/cashflow/app/CashFlowDto.java
src/main/java/com/multi/vidulum/cashflow/app/CashFlowRestController.java
src/test/java/com/multi/vidulum/cashflow/CashFlowControllerTest.java
src/test/java/com/multi/vidulum/cashflow_forecast_processor/CashFlowForecastControllerTest.java
src/test/java/com/multi/vidulum/bank_data_ingestion/app/BankDataIngestionControllerTest.java
src/test/java/com/multi/vidulum/bank_data_ingestion/app/BankDataIngestionHttpActor.java
src/test/java/com/multi/vidulum/cashflow/app/CashFlowHttpActor.java
src/test/java/com/multi/vidulum/cashflow/app/CashFlowErrorHandlingTest.java
src/test/java/com/multi/vidulum/DualCashflowStatementGeneratorWithHistory.java
src/test/java/com/multi/vidulum/CashflowStatementViaAIGenerator.java
src/test/java/com/multi/vidulum/DualCashflowStatementGenerator.java
src/test/java/com/multi/vidulum/CashFlowForecastStatementGenerator.java
```

---

## Prompt do kontynuacji pracy

```
Kontekst: Pracujemy nad walidacją endpointów tworzenia i edycji CashFlow.

Problem do rozwiązania:
1. NPE w CategoryCreatedEventHandler - walidacja bankAccountNumber i denomination została dodana,
   ale trzeba zweryfikować że NPE już nie występuje przy tworzeniu nowych CashFlow przez API.

2. Testy HTTP curl zwracają VALIDATION_INVALID_JSON dla poprawnych requestów JSON,
   mimo że testy jednostkowe przechodzą. Trzeba zbadać konfigurację Jackson ObjectMapper.

3. Funkcjonalność edycji CashChange została zaimplementowana ale nie została potwierdzona manualnie.

Do zrobienia:
1. Sprawdź logi Docker po utworzeniu CashFlow - czy nie ma NPE w CategoryCreatedEventHandler
2. Zbadaj dlaczego curl zwraca VALIDATION_INVALID_JSON (porównaj konfigurację Jackson)
3. Przetestuj manualnie edycję CashChange przez API
4. Rozważ dodanie testów integracyjnych HTTP dla walidacji CreateCashFlow

Pliki do przejrzenia:
- CashFlowDto.java (nowe DTO: BankAccountJson, BankAccountNumberJson, CurrencyJson, MoneyJson)
- CashFlowRestController.java (endpointy z @Valid)
- CategoryCreatedEventHandler.java (linia 38 - miejsce NPE)
- GlobalExceptionHandler lub ErrorHttpHandler (mapowanie wyjątków na HTTP)
```
