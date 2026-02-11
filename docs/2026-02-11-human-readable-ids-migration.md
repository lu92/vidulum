# Migracja na Human-Readable IDs (UXXXXXXXX, CFXXXXXXXX)

**Data:** 2026-02-11
**Status:** Analiza przed implementacją
**Backwards Compatibility:** NIE WYMAGANA (można skasować dane)

---

## Problem i rozwiązanie

### Przed:

```
userId:     550e8400-e29b-41d4-a716-446655440000
cashFlowId: 6ba7b810-9dad-11d1-80b4-00c04fd430c8

Problemy:
- Nieczytelne logi
- Mylenie userId vs cashFlowId vs MongoDB _id
- Niemożliwe do przekazania przez telefon
- Brzydkie URL
```

### Po:

```
userId:     U10000001
cashFlowId: CF10000001

Korzyści:
- Prefix = typ (U=User, CF=CashFlow)
- Czytelne logi: grep U10000001
- Łatwe dla supportu
- Profesjonalne URL
```

---

## Schemat ID

| Entity | Prefix | Format | Przykład |
|--------|--------|--------|----------|
| **User** | U | U + 8 cyfr | U10000001 |
| **CashFlow** | CF | CF + 8 cyfr | CF10000001 |
| **CashChange** | CC | CC + 10 cyfr | CC1000000001 |
| **Portfolio** | PF | PF + 8 cyfr | PF10000001 |
| **Trade** | TR | TR + 10 cyfr | TR1000000001 |

---

## Pliki do zmiany

### 1. Nowe pliki (4 pliki)

| Plik | Opis |
|------|------|
| `common/InvalidUserIdFormatException.java` | Wyjątek dla nieprawidłowego formatu |
| `cashflow/domain/InvalidCashFlowIdFormatException.java` | Wyjątek dla nieprawidłowego formatu |
| `common/SequenceDocument.java` | MongoDB dokument dla sekwencji |
| `common/BusinessIdGenerator.java` | Generator ID z MongoDB atomic counter |

### 2. Modyfikacje istniejących plików (6 plików)

| Plik | Zmiana |
|------|--------|
| `common/error/ErrorCode.java` | +2 kody: `INVALID_USER_ID_FORMAT`, `INVALID_CASHFLOW_ID_FORMAT` |
| `security/config/ErrorHttpHandler.java` | +2 handlery wyjątków |
| `common/UserId.java` | Walidacja `U\\d{8}` w konstruktorze |
| `cashflow/domain/CashFlowId.java` | Walidacja `CF\\d{8}`, usunąć `generate()` |
| `user/app/commands/create/CreateUserCommandHandler.java` | Użyć `BusinessIdGenerator` |
| `cashflow/app/commands/create/CreateCashFlowCommandHandler.java` | Użyć `BusinessIdGenerator` |
| `cashflow/app/commands/create/CreateCashFlowWithHistoryCommandHandler.java` | Użyć `BusinessIdGenerator` |

### 3. Testy (~25 plików, ~100 zmian)

| Plik | Ilość zmian |
|------|-------------|
| `CashFlowAggregateTest.java` | ~30 (UserId.of, CashFlowId.generate) |
| `CashFlowErrorHandlingTest.java` | ~25 (UUID.randomUUID) |
| `CashFlowForecastStatementRepositoryImplTest.java` | ~10 |
| `CashFlowControllerTest.java` | ~10 |
| `VidulumApplicationTests.java` | ~5 |
| Pozostałe testy | ~20 |

---

## Implementacja krok po kroku

### Krok 1: ErrorCode + Exceptions + Handlers

```java
// ErrorCode.java - dodać:
INVALID_USER_ID_FORMAT(HttpStatus.BAD_REQUEST, "Invalid User ID format. Expected: UXXXXXXXX"),
INVALID_CASHFLOW_ID_FORMAT(HttpStatus.BAD_REQUEST, "Invalid CashFlow ID format. Expected: CFXXXXXXXX"),
```

```java
// InvalidUserIdFormatException.java
public class InvalidUserIdFormatException extends RuntimeException {
    @Getter
    private final String providedId;

    public InvalidUserIdFormatException(String providedId) {
        super("Invalid User ID format: '" + providedId + "'. Expected: UXXXXXXXX (e.g., U10000001)");
        this.providedId = providedId;
    }
}
```

```java
// ErrorHttpHandler.java - dodać:
@ExceptionHandler(InvalidUserIdFormatException.class)
public ResponseEntity<ApiError> handleInvalidUserId(InvalidUserIdFormatException ex) {
    log.debug("Invalid User ID format: {}", ex.getProvidedId());
    ApiError error = ApiError.of(ErrorCode.INVALID_USER_ID_FORMAT, ex.getMessage());
    return ResponseEntity.status(error.httpStatus()).body(error);
}
```

### Krok 2: BusinessIdGenerator

```java
@Document(collection = "sequences")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SequenceDocument {
    @Id
    private String id;
    private long value;
}
```

```java
@Service
@RequiredArgsConstructor
public class BusinessIdGenerator {

    private final MongoTemplate mongoTemplate;
    private static final long INITIAL = 10000000L;

    public UserId generateUserId() {
        long seq = getNextSequence("user_sequence");
        return UserId.of(String.format("U%08d", seq));
    }

    public CashFlowId generateCashFlowId() {
        long seq = getNextSequence("cashflow_sequence");
        return new CashFlowId(String.format("CF%08d", seq));
    }

    private long getNextSequence(String name) {
        Query query = new Query(Criteria.where("_id").is(name));
        Update update = new Update().inc("value", 1);
        FindAndModifyOptions options = FindAndModifyOptions.options()
            .returnNew(true).upsert(true);

        SequenceDocument result = mongoTemplate.findAndModify(query, update, options, SequenceDocument.class);
        return (result == null || result.getValue() < INITIAL) ? INITIAL : result.getValue();
    }
}
```

### Krok 3: Walidacja w Value Objects

```java
// UserId.java - NOWA WERSJA
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserId {

    private static final Pattern PATTERN = Pattern.compile("U\\d{8}");
    String id;

    public static UserId of(String id) {
        if (id == null || !PATTERN.matcher(id).matches()) {
            throw new InvalidUserIdFormatException(id);
        }
        return new UserId(id);
    }
}
```

```java
// CashFlowId.java - NOWA WERSJA
public record CashFlowId(String id) {

    private static final Pattern PATTERN = Pattern.compile("CF\\d{8}");

    public CashFlowId {
        if (id == null || !PATTERN.matcher(id).matches()) {
            throw new InvalidCashFlowIdFormatException(id);
        }
    }

    // USUNĄĆ metodę generate() - teraz używamy BusinessIdGenerator
}
```

### Krok 4: Command Handlers

```java
// CreateUserCommandHandler - zmiana:
// BYŁO:
UserId userId = UserId.of(UUID.randomUUID().toString());

// JEST:
@Autowired BusinessIdGenerator idGenerator;
UserId userId = idGenerator.generateUserId();  // U10000001
```

```java
// CreateCashFlowCommandHandler - zmiana:
// BYŁO:
CashFlowId cashFlowId = CashFlowId.generate();

// JEST:
@Autowired BusinessIdGenerator idGenerator;
CashFlowId cashFlowId = idGenerator.generateCashFlowId();  // CF10000001
```

### Krok 5: Testy - TestIds helper

```java
// src/test/java/com/multi/vidulum/TestIds.java
public class TestIds {
    private static final AtomicLong userCounter = new AtomicLong(10000000);
    private static final AtomicLong cashFlowCounter = new AtomicLong(10000000);

    public static UserId nextUserId() {
        return UserId.of(String.format("U%08d", userCounter.getAndIncrement()));
    }

    public static CashFlowId nextCashFlowId() {
        return new CashFlowId(String.format("CF%08d", cashFlowCounter.getAndIncrement()));
    }

    public static String invalidUserId() {
        return "invalid-user-id";
    }

    public static String nonExistentUserId() {
        return "U99999999";
    }

    public static String nonExistentCashFlowId() {
        return "CF99999999";
    }

    public static void reset() {
        userCounter.set(10000000);
        cashFlowCounter.set(10000000);
    }
}
```

### Krok 6: Aktualizacja testów

```java
// CashFlowAggregateTest.java
// BYŁO:
CashFlowId cashFlowId = CashFlowId.generate();
UserId.of("user")

// JEST:
CashFlowId cashFlowId = TestIds.nextCashFlowId();
TestIds.nextUserId()
```

```java
// CashFlowErrorHandlingTest.java
// BYŁO:
String userId = "errortest_" + UUID.randomUUID().toString().substring(0, 8);

// JEST:
String userId = TestIds.nextUserId().getId();
```

### Krok 7: Nowe testy walidacji

```java
@Test
void shouldReturn400ForInvalidUserIdFormat() {
    ResponseEntity<String> response = restTemplate.getForEntity(
        "/user/invalid-id", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("INVALID_USER_ID_FORMAT");
    assertThat(response.getBody()).contains("UXXXXXXXX");
}

@Test
void shouldReturn400ForInvalidCashFlowIdFormat() {
    ResponseEntity<String> response = restTemplate.getForEntity(
        "/cash-flow/not-valid", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("INVALID_CASHFLOW_ID_FORMAT");
}
```

---

## API Response po zmianach

### Sukces:

```json
{
  "userId": "U10000001",
  "cashFlowId": "CF10000001"
}
```

### Błąd - nieprawidłowy format ID:

```json
{
  "errorCode": "INVALID_USER_ID_FORMAT",
  "message": "Invalid User ID format: 'abc123'. Expected: UXXXXXXXX (e.g., U10000001)",
  "httpStatus": 400
}
```

### Błąd - nie znaleziono:

```json
{
  "errorCode": "USER_NOT_FOUND",
  "message": "User U99999999 not found",
  "httpStatus": 404
}
```

---

## Podsumowanie

### Co zrobić:

| Krok | Pliki | Czas |
|------|-------|------|
| 1. ErrorCode + Exceptions + Handlers | 3 pliki | 1h |
| 2. BusinessIdGenerator | 2 pliki | 1h |
| 3. Walidacja UserId + CashFlowId | 2 pliki | 1h |
| 4. Command Handlers | 3 pliki | 30min |
| 5. TestIds helper | 1 plik | 30min |
| 6. Aktualizacja testów | ~25 plików | 3-4h |
| 7. Nowe testy walidacji | 1 plik | 1h |
| **RAZEM** | **~35 plików** | **~8-10h (1-1.5 dnia)** |

### Przed rozpoczęciem:

```bash
# Skasować dane testowe z MongoDB
mongosh
use testDB
db.users.drop()
db.cashflows.drop()
db.sequences.drop()
# ... inne kolekcje
```

### Kolejność implementacji:

```
1. ErrorCode.java + Exceptions + ErrorHttpHandler (walidacja zadziała)
2. BusinessIdGenerator + SequenceDocument (generowanie zadziała)
3. UserId.java + CashFlowId.java (walidacja + integracja)
4. Command Handlers (nowe ID w tworzeniu)
5. TestIds.java (helper dla testów)
6. Aktualizacja wszystkich testów
7. Uruchomienie testów i naprawienie błędów
```
