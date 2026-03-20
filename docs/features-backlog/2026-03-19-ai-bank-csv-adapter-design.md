# AI Bank CSV Adapter - Design Document

**Data utworzenia:** 2026-03-19
**Status:** Do implementacji
**Autor:** Claude Code + User
**Powiązane:** `2026-02-08-canonical-csv-architecture.md`

---

## Spis treści

1. [Podsumowanie](#1-podsumowanie)
2. [BankCsvRow Format (docelowy)](#2-bankcsvrow-format-docelowy)
3. [Struktura pakietu](#3-struktura-pakietu)
4. [Analiza rzeczywistego CSV (Nest Bank)](#4-analiza-rzeczywistego-csv-nest-bank)
5. [Prompt do Claude API](#5-prompt-do-claude-api)
6. [Obsługa błędów](#6-obsługa-błędów)
7. [Implementacja adaptera](#7-implementacja-adaptera)
8. [Strategia testowania](#8-strategia-testowania)
9. [Koszty API](#9-koszty-api)
10. [Audyt i przechowywanie transformacji (MongoDB)](#10-audyt-i-przechowywanie-transformacji-mongodb)
11. [Wielojęzyczność (CSV w innych językach)](#11-wielojęzyczność-csv-w-innych-językach)
12. [Komunikacja między modułami (Microservices-ready)](#12-komunikacja-między-modułami-microservices-ready)
13. [Wyjątki biznesowe i HTTP Status Codes](#13-wyjątki-biznesowe-i-http-status-codes)
14. [Strategia testowania - szczegóły](#14-strategia-testowania---szczegóły)
15. [Plan implementacji](#15-plan-implementacji)
16. [Ochrona danych - Anonimizacja przed wysłaniem do AI (TODO)](#16-ochrona-danych---anonimizacja-przed-wysłaniem-do-ai-todo)
    - [16.1 Szybka walidacja pliku](#161-szybka-walidacja-pliku-przed-anonimizacją)
    - [16.2 Cache reguł mapowania](#162-cache-reguł-mapowania-mongodb)
    - [16.3 Granulacja cache: Bank + Waluta (globalne reguły)](#163-granulacja-cache-bank--waluta-globalne-reguły)

---

## 1. Podsumowanie

### Cel

Stworzenie adaptera AI, który transformuje CSV z dowolnego polskiego banku **bezpośrednio** na format `BankCsvRow` - bez pośrednich formatów. Dzięki temu wykorzystujemy istniejący pipeline `bank-data-ingestion` bez żadnych zmian.

### Architektura (uproszczona)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           AI BANK CSV ADAPTER                                │
│                                                                              │
│   Bank CSV            Claude Haiku           BankCsvRow CSV                  │
│   (Nest, mBank...)  ────────────────►  (format istniejący w systemie)       │
│                                                                              │
└─────────────────────────────────────────┬───────────────────────────────────┘
                                          │
                                          │  ZERO KONWERSJI!
                                          │  Bezpośrednio do istniejącego parsera
                                          │
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ISTNIEJĄCY MODUŁ: bank-data-ingestion                     │
│                           (BEZ ŻADNYCH ZMIAN!)                               │
│                                                                              │
│  CsvParserService.parse()  →  BankCsvRow  →  Staging  →  Import  →  Done   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Kluczowa decyzja: Brak pośredniego formatu

| Aspekt | Poprzedni design | Nowy design |
|--------|------------------|-------------|
| Format wyjściowy AI | `CanonicalTransaction` | `BankCsvRow` (istniejący!) |
| Dodatkowe klasy | CanonicalTransaction, mapper | **ZERO** |
| Zmiany w istniejącym kodzie | Żadne | **ZERO** |
| Złożoność | Średnia | **Niska** |

### Wspierane banki (initial)

| Bank | Format | Separator | Data | Specyfika |
|------|--------|-----------|------|-----------|
| Nest Bank | CSV | `,` | DD-MM-YYYY | 6 linii nagłówka metadata |
| mBank | CSV | `;` | DD.MM.YYYY | BOM UTF-8 |
| ING | CSV | `;` | YYYY-MM-DD | Wiele typów eksportu |
| PKO BP | CSV | `;` | DD.MM.YYYY | iPKO vs IKO różnice |
| Santander | CSV | `;` | DD.MM.YYYY | - |
| Millennium | CSV | `;` | DD-MM-YYYY | - |

---

## 2. BankCsvRow Format (docelowy)

### Istniejący format w systemie

AI generuje CSV **dokładnie** w formacie akceptowanym przez `CsvParserService`:

```csv
bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
```

### Opis pól (z BankCsvRow.java)

| Pole | Wymagane | Typ | Opis |
|------|----------|-----|------|
| `bankTransactionId` | ❌ | String | Unikalny ID (generowany jeśli brak) |
| `name` | ✅ | String | Tytuł transakcji |
| `description` | ❌ | String | Pełny opis (może być = name) |
| `bankCategory` | ❌ | String | Kategoria z banku |
| `amount` | ✅ | Decimal | Kwota (zawsze dodatnia!) |
| `currency` | ✅ | String | PLN, EUR, USD |
| `type` | ✅ | Enum | `INFLOW` lub `OUTFLOW` |
| `operationDate` | ✅ | Date | Data operacji (YYYY-MM-DD) |
| `bookingDate` | ❌ | Date | Data księgowania (YYYY-MM-DD) |
| `sourceAccountNumber` | ❌ | String | Konto źródłowe |
| `targetAccountNumber` | ❌ | String | Konto docelowe |

### Przykład wyjścia AI

```csv
bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
NEST_2025-12-31_001,Prowizja za przelew natychmiastowy wychodzący KIR,Prowizja za przelew natychmiastowy wychodzący KIR,Opłaty i prowizje,10.00,PLN,OUTFLOW,2025-12-31,2025-12-31,,
NEST_2025-12-31_002,zycie,Przelew do: Lucjan Bik Pekao,Przelewy wychodzące,3000.00,PLN,OUTFLOW,2025-12-31,2025-12-31,,PL98124014441111001078171074
NEST_2025-12-11_003,PCB9QO25 11/2025,Przelew od: MINDBOX SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ,Przelewy przychodzące,31064.44,PLN,INFLOW,2025-12-11,2025-12-11,82109018830000000109874194,
```

---

## 3. Struktura pakietu

```
com.multi.vidulum.bank_data_adapter/
├── app/
│   └── AiBankCsvTransformService.java    # Główny serwis
├── infrastructure/
│   ├── AiCsvTransformer.java             # Wywołanie Claude API
│   ├── AiPromptBuilder.java              # Budowanie promptów
│   └── AiResponseProcessor.java          # Przetwarzanie odpowiedzi
└── rest/
    └── AiBankCsvController.java          # REST endpoints
```

**Uwaga:** Brak pakietu `domain/` - używamy istniejącego `BankCsvRow`!

---

## 4. Analiza rzeczywistego CSV (Nest Bank)

### Przykładowy plik: `lista_operacji_20260111.csv`

#### Struktura nagłówka (linie 1-6) - METADANE DO POMINIĘCIA

```csv
Numer rachunku: 93187010452083105656550001,
Właściciel: DEV LUCJAN BIK,
Historia operacji za okres od 01.01.2023 do 11.01.2026,
Liczba operacji: 402,
Suma uznań: 1217096.34 PLN,
Suma obciążeń: -1141912.03 PLN,
```

#### Nagłówki kolumn banku (linia 7)

```csv
Data księgowania,Data operacji,Rodzaj operacji,Kwota,Waluta,Dane kontrahenta,Numer rachunku kontrahenta,Tytuł operacji,Saldo po operacji,
```

#### Przykładowe dane (od linii 8)

```csv
31-12-2025,31-12-2025,Opłaty i prowizje,-10,PLN,,,"Prowizja za przelew natychmiastowy wychodzący KIR",76047.25,
31-12-2025,31-12-2025,Przelewy wychodzące,-3000,PLN,"Lucjan Bik Pekao",PL98124014441111001078171074,"zycie",76057.25,
```

### Mapowanie Nest Bank → BankCsvRow

| Nest Bank | BankCsvRow | Transformacja |
|-----------|------------|---------------|
| - | `bankTransactionId` | Generować: `NEST_{YYYY-MM-DD}_{row}` |
| `Tytuł operacji` | `name` | Bezpośrednio |
| `Tytuł operacji` + `Dane kontrahenta` | `description` | Połączyć |
| `Rodzaj operacji` | `bankCategory` | Bezpośrednio |
| `Kwota` | `amount` | `abs(value)` |
| `Waluta` | `currency` | Bezpośrednio |
| `Kwota < 0` | `type` | `OUTFLOW`, else `INFLOW` |
| `Data operacji` | `operationDate` | `DD-MM-YYYY` → `YYYY-MM-DD` |
| `Data księgowania` | `bookingDate` | `DD-MM-YYYY` → `YYYY-MM-DD` |
| - | `sourceAccountNumber` | Dla INFLOW: `Numer rachunku kontrahenta` |
| `Numer rachunku kontrahenta` | `targetAccountNumber` | Dla OUTFLOW |

---

## 5. Prompt do Claude API

### System Prompt

```
You are a bank CSV parser. Transform bank exports to BankCsvRow format.
Return ONLY valid CSV - no markdown, no explanations, no code blocks.
First line must be the header row.
```

### User Prompt Template

```
Transform this bank CSV to BankCsvRow format.

## INPUT CSV:
{raw_csv_content}

## OUTPUT FORMAT (CSV with exact columns):
bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber

## COLUMN RULES:
- bankTransactionId: generate as {BANK}_{YYYY-MM-DD}_{row_number} (e.g., NEST_2025-12-31_001)
- name: transaction title (REQUIRED, from "Tytuł operacji" or similar)
- description: full description including merchant/counterparty info
- bankCategory: original category from bank (e.g., "Przelewy wychodzące")
- amount: ALWAYS POSITIVE decimal number (use absolute value)
- currency: PLN, EUR, USD (3-letter code)
- type: INFLOW (positive/credit) or OUTFLOW (negative/debit)
- operationDate: YYYY-MM-DD format (REQUIRED)
- bookingDate: YYYY-MM-DD format (same as operationDate if not available)
- sourceAccountNumber: sender's account for INFLOW, empty for OUTFLOW
- targetAccountNumber: recipient's account for OUTFLOW, empty for INFLOW

## TRANSFORMATION RULES:
1. SKIP metadata header lines (account info, date ranges, totals, summaries)
2. Find actual column headers row (contains "Data", "Kwota", etc.)
3. Convert dates: DD-MM-YYYY or DD.MM.YYYY → YYYY-MM-DD
4. Replace "|" characters with spaces (used as line breaks in Polish banks)
5. Negative amount = OUTFLOW, positive = INFLOW
6. Escape fields containing commas with double quotes
7. Handle Polish characters (ąćęłńóśźż) correctly

## OUTPUT:
Return ONLY the CSV content starting with header row. No explanations.
```

### Prompt dla błędów

Jeśli AI nie może przetworzyć pliku, zwraca:

```
ERROR: {error_code}
MESSAGE: {human_readable_message}
SAMPLE: {first 3 problematic lines}
```

Kody błędów: `UNRECOGNIZED_FORMAT`, `MISSING_REQUIRED_COLUMN`, `DATE_PARSE_ERROR`, `EMPTY_FILE`

---

## 6. Obsługa błędów

### Wykrywanie błędów w odpowiedzi AI

```java
public class AiResponseProcessor {

    public AiTransformResult process(String aiResponse) {
        // Sprawdź czy odpowiedź zaczyna się od ERROR:
        if (aiResponse.trim().startsWith("ERROR:")) {
            return parseErrorResponse(aiResponse);
        }

        // Sprawdź czy odpowiedź zaczyna się od nagłówka CSV
        if (!aiResponse.trim().startsWith("bankTransactionId,")) {
            return AiTransformResult.error(
                AiErrorCode.INVALID_RESPONSE,
                "AI response is not valid CSV"
            );
        }

        // Waliduj CSV
        return validateAndParseCsv(aiResponse);
    }
}
```

### Kody błędów

```java
public enum AiErrorCode {
    UNRECOGNIZED_FORMAT,      // Nie rozpoznano struktury
    MISSING_REQUIRED_COLUMN,  // Brak wymaganej kolumny
    DATE_PARSE_ERROR,         // Problem z datami
    EMPTY_FILE,               // Pusty plik
    AI_SERVICE_ERROR,         // Błąd Claude API
    INVALID_RESPONSE,         // Nieprawidłowa odpowiedź AI
    RATE_LIMIT_EXCEEDED       // Przekroczony limit API
}
```

---

## 7. Implementacja adaptera

### AiBankCsvTransformService

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class AiBankCsvTransformService {

    private final ChatClient chatClient;
    private final AiPromptBuilder promptBuilder;
    private final AiResponseProcessor responseProcessor;

    private static final int MAX_RETRIES = 2;

    public AiTransformResult transform(byte[] csvContent, String bankHint) {
        String csvString = decodeWithFallback(csvContent);

        log.info("Starting AI transform, size={} chars, bankHint={}",
            csvString.length(), bankHint);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String prompt = promptBuilder.build(csvString, bankHint);

                String aiResponse = chatClient.prompt()
                    .system(promptBuilder.getSystemPrompt())
                    .user(prompt)
                    .call()
                    .content();

                AiTransformResult result = responseProcessor.process(aiResponse);

                if (result.success() || attempt == MAX_RETRIES) {
                    return result;
                }

                log.warn("Attempt {} failed, retrying...", attempt);

            } catch (Exception e) {
                log.error("AI transform error on attempt {}", attempt, e);
                if (attempt == MAX_RETRIES) {
                    return AiTransformResult.error(
                        AiErrorCode.AI_SERVICE_ERROR,
                        e.getMessage()
                    );
                }
            }
        }

        throw new IllegalStateException("Should not reach here");
    }

    private String decodeWithFallback(byte[] input) {
        // Remove BOM if present
        if (input.length >= 3 && input[0] == (byte)0xEF &&
            input[1] == (byte)0xBB && input[2] == (byte)0xBF) {
            input = Arrays.copyOfRange(input, 3, input.length);
        }

        String content = new String(input, StandardCharsets.UTF_8);

        // Check for encoding issues
        if (content.contains("\uFFFD")) {
            content = new String(input, Charset.forName("CP1250"));
        }

        return content;
    }
}
```

### AiTransformResult

```java
public record AiTransformResult(
    boolean success,
    String csvContent,           // Gotowy CSV do przekazania do CsvParserService
    String detectedBank,
    int rowCount,
    List<String> warnings,
    AiError error
) {
    public static AiTransformResult success(String csv, String bank, int rows) {
        return new AiTransformResult(true, csv, bank, rows, List.of(), null);
    }

    public static AiTransformResult error(AiErrorCode code, String message) {
        return new AiTransformResult(false, null, null, 0, List.of(),
            new AiError(code, message));
    }
}

public record AiError(AiErrorCode code, String message) {}
```

---

## 8. Strategia testowania

### A) Unit testy z mockiem ChatClient

```java
@ExtendWith(MockitoExtension.class)
class AiBankCsvTransformServiceTest {

    @Mock
    private ChatClient chatClient;

    @InjectMocks
    private AiBankCsvTransformService service;

    @Test
    void shouldTransformNestBankCsv() {
        // Given
        String mockAiResponse = """
            bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
            NEST_2025-12-31_001,Prowizja,Prowizja za przelew,Opłaty,10.00,PLN,OUTFLOW,2025-12-31,2025-12-31,,
            """;

        when(chatClient.prompt()).thenReturn(/* mock chain */);

        // When
        AiTransformResult result = service.transform(nestBankCsv, "Nest");

        // Then
        assertThat(result.success()).isTrue();
        assertThat(result.csvContent()).startsWith("bankTransactionId,");
    }
}
```

### B) Integration test - pełny flow

```java
@SpringBootTest
@Tag("integration")
class AiBankCsvFullFlowTest {

    @Autowired
    private AiBankCsvTransformService aiService;

    @Autowired
    private CsvParserService csvParser;

    @Test
    void shouldTransformAndParseNestBankCsv() {
        // Given - prawdziwy plik Nest Bank
        byte[] nestCsv = loadResource("nest-bank/lista_operacji_sample.csv");

        // When - AI transform
        AiTransformResult aiResult = aiService.transform(nestCsv, "Nest");

        // Then - AI succeeded
        assertThat(aiResult.success()).isTrue();

        // When - Parse with existing CsvParserService
        MockMultipartFile file = new MockMultipartFile(
            "file", "test.csv", "text/csv",
            aiResult.csvContent().getBytes()
        );
        CsvParseResult parseResult = csvParser.parse(file);

        // Then - Parser succeeded
        assertThat(parseResult.hasErrors()).isFalse();
        assertThat(parseResult.rows()).isNotEmpty();

        // Validate rows
        BankCsvRow firstRow = parseResult.rows().get(0);
        assertThat(firstRow.name()).isNotBlank();
        assertThat(firstRow.amount()).isPositive();
        assertThat(firstRow.type()).isIn(Type.INFLOW, Type.OUTFLOW);
    }
}
```

---

## 9. Koszty API

### Cennik Anthropic (marzec 2026)

| Model | Input (1M tokens) | Output (1M tokens) | Szybkość |
|-------|-------------------|-------------------|----------|
| **claude-haiku-3-5** | $0.80 | $4.00 | Najszybszy |
| **claude-sonnet-4** | $3.00 | $15.00 | Szybki |
| **claude-opus-4** | $15.00 | $75.00 | Wolniejszy |

### Szacowanie tokenów dla CSV

Typowa transakcja bankowa w CSV:
- **Input:** ~100-150 tokenów per wiersz (bank CSV)
- **Output:** ~80-100 tokenów per wiersz (BankCsvRow CSV)
- **Prompt/instrukcje:** ~500 tokenów (stałe)

### Kalkulacja kosztów

#### 100 transakcji

| Model | Input tokens | Output tokens | Koszt Input | Koszt Output | **RAZEM** |
|-------|-------------|---------------|-------------|--------------|-----------|
| Haiku | ~15,500 | ~10,000 | $0.012 | $0.040 | **$0.05** |
| Sonnet | ~15,500 | ~10,000 | $0.047 | $0.150 | **$0.20** |
| Opus | ~15,500 | ~10,000 | $0.233 | $0.750 | **$0.98** |

#### 500 transakcji

| Model | Input tokens | Output tokens | Koszt Input | Koszt Output | **RAZEM** |
|-------|-------------|---------------|-------------|--------------|-----------|
| Haiku | ~75,500 | ~50,000 | $0.060 | $0.200 | **$0.26** |
| Sonnet | ~75,500 | ~50,000 | $0.227 | $0.750 | **$0.98** |
| Opus | ~75,500 | ~50,000 | $1.133 | $3.750 | **$4.88** |

#### 1000 transakcji

| Model | Input tokens | Output tokens | Koszt Input | Koszt Output | **RAZEM** |
|-------|-------------|---------------|-------------|--------------|-----------|
| Haiku | ~150,500 | ~100,000 | $0.120 | $0.400 | **$0.52** |
| Sonnet | ~150,500 | ~100,000 | $0.452 | $1.500 | **$1.95** |
| Opus | ~150,500 | ~100,000 | $2.258 | $7.500 | **$9.76** |

### Podsumowanie kosztów

| Rozmiar pliku | Haiku | Sonnet | Opus |
|---------------|-------|--------|------|
| 100 transakcji | **$0.05** | $0.20 | $0.98 |
| 500 transakcji | **$0.26** | $0.98 | $4.88 |
| 1000 transakcji | **$0.52** | $1.95 | $9.76 |

### Rekomendacja

**Używaj Haiku** - jest:
- **~4x tańszy** niż Sonnet
- **~19x tańszy** niż Opus
- **Wystarczająco dobry** do parsowania strukturyzowanych danych CSV

Dla Twojego pliku (402 transakcje): **~$0.20-0.25 z Haiku**

### Miesięczne koszty (szacunkowo)

| Scenariusz | Plików/miesiąc | Transakcji/plik | Koszt Haiku |
|------------|----------------|-----------------|-------------|
| Osobiste użycie | 1-2 | 400 | **$0.40-0.50** |
| Power user | 4-5 | 500 | **$1.30-1.50** |
| Mały biznes | 10-20 | 500 | **$2.60-5.20** |

---

## 10. Audyt i przechowywanie transformacji (MongoDB)

### Kolekcja: `ai_csv_transformations`

Każda transformacja AI jest zapisywana z pełnymi metadanymi:

```java
@Document(collection = "ai_csv_transformations")
public record AiCsvTransformationDocument(

    // ========== IDENTYFIKATORY ==========
    @Id
    String id,                              // UUID
    String userId,                          // Kto zlecił transformację
    String cashFlowId,                      // Opcjonalnie - jeśli od razu importujemy

    // ========== PLIK WEJŚCIOWY ==========
    String originalFileName,                // "lista_operacji_20260111.csv"
    int originalFileSizeBytes,              // Rozmiar w bajtach
    String originalFileHash,                // SHA-256 dla deduplikacji
    String originalCsvContent,              // Pełna zawartość oryginalnego CSV
    String detectedEncoding,                // "UTF-8", "CP1250"
    String detectedLanguage,                // "pl", "en", "de" (wykryty przez AI)

    // ========== WYKRYCIE BANKU ==========
    String bankHint,                        // Podpowiedź od użytkownika (opcjonalna)
    String detectedBank,                    // Wykryty przez AI: "Nest", "mBank", "ING", "unknown"
    String detectedCountry,                 // "PL", "DE", "UK"

    // ========== WYNIK TRANSFORMACJI ==========
    boolean success,                        // Czy transformacja się udała
    String transformedCsvContent,           // Wynik: BankCsvRow CSV (null jeśli błąd)
    int inputRowCount,                      // Liczba wierszy wejściowych
    int outputRowCount,                     // Liczba wierszy wyjściowych
    int skippedRowCount,                    // Pominięte wiersze
    List<String> warnings,                  // Ostrzeżenia z AI

    // ========== BŁĘDY ==========
    String errorCode,                       // Kod błędu (null jeśli sukces)
    String errorMessage,                    // Treść błędu

    // ========== METRYKI AI ==========
    String aiModel,                         // "claude-haiku-3-5-20241022"
    int inputTokens,                        // Tokeny wejściowe
    int outputTokens,                       // Tokeny wyjściowe
    BigDecimal estimatedCostUsd,            // Szacowany koszt w USD
    long processingTimeMs,                  // Czas przetwarzania
    int retryCount,                         // Ile razy retry (0 = sukces za pierwszym)

    // ========== AUDIT ==========
    ZonedDateTime createdAt,                // Kiedy utworzono
    String createdBy,                       // User ID

    // ========== STATUS IMPORTU ==========
    ImportStatus importStatus,              // PENDING, IMPORTED, SKIPPED, FAILED
    String stagingSessionId,                // ID sesji staging (jeśli zaimportowano)
    ZonedDateTime importedAt                // Kiedy zaimportowano

) {
    public enum ImportStatus {
        PENDING,        // Transformacja OK, czeka na import
        IMPORTED,       // Zaimportowano do CashFlow
        SKIPPED,        // Użytkownik pominął import
        FAILED          // Import się nie powiódł
    }
}
```

### Indeksy MongoDB

```javascript
// Szybkie wyszukiwanie po użytkowniku i dacie
db.ai_csv_transformations.createIndex({ "userId": 1, "createdAt": -1 })

// Deduplikacja - ten sam plik nie powinien być przetwarzany dwa razy
db.ai_csv_transformations.createIndex({ "originalFileHash": 1, "userId": 1 })

// Wyszukiwanie po banku
db.ai_csv_transformations.createIndex({ "detectedBank": 1 })

// Status importu
db.ai_csv_transformations.createIndex({ "importStatus": 1 })
```

### Repository

```java
public interface AiCsvTransformationRepository extends MongoRepository<AiCsvTransformationDocument, String> {

    // Historia transformacji użytkownika
    List<AiCsvTransformationDocument> findByUserIdOrderByCreatedAtDesc(String userId);

    // Sprawdzenie czy plik już był przetwarzany
    Optional<AiCsvTransformationDocument> findByOriginalFileHashAndUserId(String hash, String userId);

    // Transformacje czekające na import
    List<AiCsvTransformationDocument> findByUserIdAndImportStatus(String userId, ImportStatus status);

    // Statystyki kosztów
    @Aggregation(pipeline = {
        "{ $match: { userId: ?0 } }",
        "{ $group: { _id: null, totalCost: { $sum: '$estimatedCostUsd' }, count: { $sum: 1 } } }"
    })
    CostSummary getCostSummaryByUserId(String userId);
}

public record CostSummary(BigDecimal totalCost, int count) {}
```

### Przykładowy dokument w MongoDB

```json
{
  "_id": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "U10000001",
  "cashFlowId": null,

  "originalFileName": "lista_operacji_20260111.csv",
  "originalFileSizeBytes": 45230,
  "originalFileHash": "sha256:a1b2c3d4e5f6...",
  "originalCsvContent": "Numer rachunku: 93187010452083105656550001,\nWłaściciel: ...",
  "detectedEncoding": "UTF-8",
  "detectedLanguage": "pl",

  "bankHint": "Nest",
  "detectedBank": "Nest",
  "detectedCountry": "PL",

  "success": true,
  "transformedCsvContent": "bankTransactionId,name,description,...\nNEST_2025-12-31_001,...",
  "inputRowCount": 402,
  "outputRowCount": 402,
  "skippedRowCount": 0,
  "warnings": [],

  "errorCode": null,
  "errorMessage": null,

  "aiModel": "claude-haiku-3-5-20241022",
  "inputTokens": 62500,
  "outputTokens": 41000,
  "estimatedCostUsd": 0.214,
  "processingTimeMs": 3420,
  "retryCount": 0,

  "createdAt": "2026-03-19T15:30:00Z",
  "createdBy": "U10000001",

  "importStatus": "IMPORTED",
  "stagingSessionId": "staging-123-456",
  "importedAt": "2026-03-19T15:35:00Z"
}
```

---

## 11. Wielojęzyczność (CSV w innych językach)

### Czy to problem?

**NIE** - Claude świetnie radzi sobie z wieloma językami. To jedna z jego głównych zalet.

### Wspierane języki (bez zmian w kodzie)

| Kraj | Język | Przykładowe kolumny | Status |
|------|-------|---------------------|--------|
| 🇵🇱 Polska | Polski | Data, Kwota, Tytuł operacji | ✅ Testowany |
| 🇩🇪 Niemcy | Niemiecki | Datum, Betrag, Verwendungszweck | ✅ Wspierany |
| 🇬🇧 UK | Angielski | Date, Amount, Description | ✅ Wspierany |
| 🇫🇷 Francja | Francuski | Date, Montant, Libellé | ✅ Wspierany |
| 🇪🇸 Hiszpania | Hiszpański | Fecha, Importe, Concepto | ✅ Wspierany |
| 🇮🇹 Włochy | Włoski | Data, Importo, Descrizione | ✅ Wspierany |
| 🇳🇱 Holandia | Holenderski | Datum, Bedrag, Omschrijving | ✅ Wspierany |

### Jak AI radzi sobie z językami?

```
INPUT (niemiecki CSV z Deutsche Bank):
Buchungstag;Wertstellung;Buchungstext;Verwendungszweck;Betrag;Währung
15.03.2026;15.03.2026;LASTSCHRIFT;NETFLIX MONTHLY;-12,99;EUR
16.03.2026;16.03.2026;GEHALT;LOHN MAERZ 2026;3500,00;EUR

OUTPUT (BankCsvRow - zawsze angielski format):
bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
DB_2026-03-15_001,NETFLIX MONTHLY,LASTSCHRIFT: NETFLIX MONTHLY,LASTSCHRIFT,12.99,EUR,OUTFLOW,2026-03-15,2026-03-15,,
DB_2026-03-16_002,LOHN MAERZ 2026,GEHALT: LOHN MAERZ 2026,GEHALT,3500.00,EUR,INFLOW,2026-03-16,2026-03-16,,
```

### Aktualizacja promptu dla wielojęzyczności

Dodajemy do promptu:

```
## LANGUAGE HANDLING:
- Input CSV may be in ANY language (Polish, German, English, French, etc.)
- Detect the language and country automatically
- Column names vary by language - use semantic understanding:
  - Date columns: "Data", "Datum", "Date", "Fecha", "Data operacji"
  - Amount columns: "Kwota", "Betrag", "Amount", "Montant", "Importo"
  - Description columns: "Opis", "Verwendungszweck", "Description", "Tytuł"
- Output CSV is ALWAYS in English format (BankCsvRow columns)
- Keep original transaction descriptions in their original language
- Detect country from: bank name, IBAN format, currency, language
```

### Wykrywanie języka i kraju

AI automatycznie wykrywa i zapisuje w metadanych:

```java
// W AiCsvTransformationDocument
String detectedLanguage,    // "pl", "de", "en", "fr"
String detectedCountry,     // "PL", "DE", "UK", "FR"
String detectedBank,        // "Nest", "Deutsche Bank", "Barclays"
```

### Specyfika formatów per kraj

| Kraj | Separator CSV | Separator dziesiętny | Format daty |
|------|---------------|---------------------|-------------|
| 🇵🇱 PL | `,` lub `;` | `,` (1234,56) | DD-MM-YYYY, DD.MM.YYYY |
| 🇩🇪 DE | `;` | `,` (1234,56) | DD.MM.YYYY |
| 🇬🇧 UK | `,` | `.` (1234.56) | DD/MM/YYYY |
| 🇺🇸 US | `,` | `.` (1234.56) | MM/DD/YYYY |
| 🇫🇷 FR | `;` | `,` (1234,56) | DD/MM/YYYY |

AI automatycznie rozpoznaje te różnice i konwertuje do standardowego formatu.

---

## 12. Komunikacja między modułami (Microservices-ready)

### Obecna architektura (monolith)

W obecnym monolicie moduły komunikują się bezpośrednio przez wywołania metod:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         VIDULUM MONOLITH                                     │
│                                                                              │
│  ┌─────────────────────┐    direct call     ┌─────────────────────────┐    │
│  │  bank-data-adapter  │ ──────────────────► │  bank-data-ingestion   │    │
│  │                     │                     │                         │    │
│  │  AiBankCsvTransform │    returns CSV     │  CsvParserService       │    │
│  │  Service            │ ◄────────────────── │  StagingService         │    │
│  └─────────────────────┘                     └─────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Docelowa architektura (microservices)

W przyszłości moduły będą osobnymi serwisami komunikującymi się przez **Kafka**:

```
┌──────────────────────────┐         Kafka Topic:          ┌──────────────────────────┐
│  BANK-DATA-ADAPTER       │      csv_transformed          │  BANK-DATA-INGESTION     │
│  SERVICE                 │ ────────────────────────────► │  SERVICE                 │
│                          │                                │                          │
│  • AI CSV Transform      │      CsvTransformedEvent      │  • CSV Parser            │
│  • Prompt Builder        │      {                        │  • Staging Service       │
│  • Cost Calculator       │        transformationId,      │  • Category Mapping      │
│  • Audit Storage         │        userId,                │  • Import Service        │
│                          │        csvContent (base64),   │                          │
│  MongoDB:                │        bankHint,              │  MongoDB:                │
│  ai_csv_transformations  │        detectedBank,          │  import_jobs             │
│                          │        rowCount               │  staged_transactions     │
│                          │      }                        │  category_mappings       │
└──────────────────────────┘                                └──────────────────────────┘
         ▲                                                           │
         │                                                           │
         │              Kafka Topic:                                 │
         │           transformation_requested                        │
         └───────────────────────────────────────────────────────────┘
                      TransformRequestedEvent
                      {
                        requestId,
                        userId,
                        cashFlowId,
                        originalCsvContent (base64),
                        bankHint (optional)
                      }
```

### Strategia przygotowania kodu (teraz)

Aby ułatwić przyszłą migrację, stosujemy **wzorzec portów i adapterów**:

#### 1. Interfejs komunikacyjny (port)

```java
// Wspólny interfejs - używany przez oba moduły
package com.multi.vidulum.bank_data_adapter.port;

public interface BankCsvTransformPort {
    /**
     * Transforms bank CSV to BankCsvRow format.
     * In monolith: direct method call
     * In microservices: Kafka event + response
     */
    TransformResult transform(TransformRequest request);
}

public record TransformRequest(
    String userId,
    String cashFlowId,           // opcjonalne - może być null
    byte[] csvContent,
    String bankHint,             // opcjonalne
    String originalFileName
) {}

public record TransformResult(
    String transformationId,     // ID w MongoDB (audit)
    boolean success,
    String csvContent,           // BankCsvRow CSV
    String detectedBank,
    int rowCount,
    List<String> warnings,
    TransformError error
) {}
```

#### 2. Adapter dla monolitu (teraz)

```java
@Component
@Profile("monolith")  // lub brak profilu = domyślnie
@RequiredArgsConstructor
public class DirectBankCsvTransformAdapter implements BankCsvTransformPort {

    private final AiBankCsvTransformService aiService;

    @Override
    public TransformResult transform(TransformRequest request) {
        // Bezpośrednie wywołanie w tym samym JVM
        AiTransformResult result = aiService.transform(
            request.csvContent(),
            request.bankHint()
        );

        return mapToTransformResult(result);
    }
}
```

#### 3. Adapter dla mikroserwisów (przyszłość)

```java
@Component
@Profile("microservices")
@RequiredArgsConstructor
public class KafkaBankCsvTransformAdapter implements BankCsvTransformPort {

    private final KafkaTemplate<String, TransformRequestedEvent> kafkaTemplate;
    private final TransformResponseCache responseCache;  // Redis lub in-memory

    @Override
    public TransformResult transform(TransformRequest request) {
        String correlationId = UUID.randomUUID().toString();

        // Wyślij request przez Kafka
        TransformRequestedEvent event = new TransformRequestedEvent(
            correlationId,
            request.userId(),
            request.cashFlowId(),
            Base64.encode(request.csvContent()),
            request.bankHint()
        );

        kafkaTemplate.send("transformation_requested", correlationId, event);

        // Czekaj na odpowiedź (z timeout)
        return responseCache.waitForResponse(correlationId, Duration.ofMinutes(2));
    }
}
```

### Eventy Kafka (przyszłość)

#### Topic: `transformation_requested`

```java
public record TransformRequestedEvent(
    String correlationId,
    String userId,
    String cashFlowId,
    String originalCsvBase64,      // Base64 encoded
    String bankHint,
    String originalFileName,
    ZonedDateTime requestedAt
) {}
```

#### Topic: `csv_transformed`

```java
public record CsvTransformedEvent(
    String correlationId,          // Powiązanie z requestem
    String transformationId,       // ID w MongoDB (audit)
    boolean success,

    // Sukces
    String transformedCsvBase64,   // Base64 encoded BankCsvRow CSV
    String detectedBank,
    String detectedLanguage,
    String detectedCountry,
    int inputRowCount,
    int outputRowCount,
    List<String> warnings,

    // Metryki AI
    String aiModel,
    int inputTokens,
    int outputTokens,
    BigDecimal estimatedCostUsd,

    // Błąd (jeśli success=false)
    String errorCode,
    String errorMessage,

    ZonedDateTime completedAt
) {}
```

### Dlaczego Kafka a nie REST?

| Aspekt | REST | Kafka |
|--------|------|-------|
| **Coupling** | Tight - adapter musi znać URL ingestion | Loose - tylko topic name |
| **Resilience** | Retry logic potrzebny | Built-in retry, DLQ |
| **Ordering** | Brak gwarancji | Gwarancja per partition (cashFlowId) |
| **Audit trail** | Trzeba osobno logować | Eventy = audit |
| **Scaling** | Load balancer | Consumer groups |
| **Async by default** | Nie | Tak |

### Diagram sekwencji (microservices)

```
User        Ingestion Service       Kafka             Adapter Service       Claude API
  │               │                   │                     │                   │
  │ Upload CSV    │                   │                     │                   │
  │──────────────►│                   │                     │                   │
  │               │ TransformRequest  │                     │                   │
  │               │──────────────────►│                     │                   │
  │               │                   │ TransformRequest    │                   │
  │               │                   │────────────────────►│                   │
  │               │                   │                     │ Transform CSV     │
  │               │                   │                     │──────────────────►│
  │               │                   │                     │ BankCsvRow CSV    │
  │               │                   │                     │◄──────────────────│
  │               │                   │                     │                   │
  │               │                   │                     │ Save to MongoDB   │
  │               │                   │                     │ (audit)           │
  │               │                   │                     │                   │
  │               │                   │ CsvTransformed      │                   │
  │               │                   │◄────────────────────│                   │
  │               │ CsvTransformed    │                     │                   │
  │               │◄──────────────────│                     │                   │
  │               │                   │                     │                   │
  │               │ Parse & Stage     │                     │                   │
  │               │ (existing flow)   │                     │                   │
  │               │                   │                     │                   │
  │  Staging ID   │                   │                     │                   │
  │◄──────────────│                   │                     │                   │
```

### Podsumowanie strategii

1. **Teraz (monolith)**:
   - Interfejs `BankCsvTransformPort` jako abstrakcja
   - `DirectBankCsvTransformAdapter` - bezpośrednie wywołanie
   - Moduły w osobnych pakietach, współdzielą tylko port interface

2. **Przyszłość (microservices)**:
   - `KafkaBankCsvTransformAdapter` - komunikacja przez Kafka
   - Osobne repozytoria Git, osobne obrazy Docker
   - Topic `transformation_requested` i `csv_transformed`
   - Redis/In-memory cache dla request-response pattern

3. **Zero zmian w logice biznesowej** - tylko swap adaptera przez Spring Profile

---

## 13. Wyjątki biznesowe i HTTP Status Codes

### Nowe ErrorCodes (do dodania w ErrorCode.java)

```java
// ============ AI Bank CSV Adapter ============

// Validation Errors (400)
AI_ADAPTER_EMPTY_FILE(HttpStatus.BAD_REQUEST, "Uploaded file is empty"),
AI_ADAPTER_FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "File exceeds maximum size limit"),
AI_ADAPTER_INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "Invalid file type. Expected CSV"),
AI_ADAPTER_UNRECOGNIZED_FORMAT(HttpStatus.BAD_REQUEST, "AI could not recognize bank CSV format"),
AI_ADAPTER_MISSING_REQUIRED_COLUMNS(HttpStatus.BAD_REQUEST, "CSV is missing required columns"),
AI_ADAPTER_INVALID_TRANSFORMATION_ID(HttpStatus.BAD_REQUEST, "Invalid transformation ID format"),

// Resources Not Found (404)
AI_ADAPTER_TRANSFORMATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Transformation not found"),

// Conflicts (409)
AI_ADAPTER_DUPLICATE_FILE(HttpStatus.CONFLICT, "This file has already been processed"),
AI_ADAPTER_ALREADY_IMPORTED(HttpStatus.CONFLICT, "This transformation has already been imported"),

// External Service Errors (502/503)
AI_ADAPTER_AI_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "AI service returned an error"),
AI_ADAPTER_AI_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI service is temporarily unavailable"),
AI_ADAPTER_RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "AI API rate limit exceeded"),
AI_ADAPTER_INGESTION_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "Bank data ingestion service returned an error"),
AI_ADAPTER_INGESTION_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Bank data ingestion service is unavailable"),
```

### Nowe klasy wyjątków

```
com.multi.vidulum.bank_data_adapter/
└── domain/
    └── exceptions/
        ├── EmptyFileException.java
        ├── FileTooLargeException.java
        ├── InvalidFileTypeException.java
        ├── UnrecognizedCsvFormatException.java
        ├── MissingRequiredColumnsException.java
        ├── InvalidTransformationIdFormatException.java
        ├── TransformationNotFoundException.java
        ├── DuplicateFileException.java
        ├── TransformationAlreadyImportedException.java
        ├── AiServiceException.java
        ├── AiServiceUnavailableException.java
        ├── AiRateLimitExceededException.java
        ├── IngestionServiceException.java
        └── IngestionServiceUnavailableException.java
```

### Tabela wyjątków z HTTP Status

| Wyjątek | HTTP Status | ErrorCode | Kiedy występuje |
|---------|-------------|-----------|-----------------|
| `EmptyFileException` | 400 | `AI_ADAPTER_EMPTY_FILE` | Pusty plik CSV |
| `FileTooLargeException` | 400 | `AI_ADAPTER_FILE_TOO_LARGE` | Plik > 5MB (konfigurowalny) |
| `InvalidFileTypeException` | 400 | `AI_ADAPTER_INVALID_FILE_TYPE` | Nie-CSV (np. PDF, XLS) |
| `UnrecognizedCsvFormatException` | 400 | `AI_ADAPTER_UNRECOGNIZED_FORMAT` | AI nie rozpoznaje struktury |
| `MissingRequiredColumnsException` | 400 | `AI_ADAPTER_MISSING_REQUIRED_COLUMNS` | Brak kluczowych kolumn |
| `InvalidTransformationIdFormatException` | 400 | `AI_ADAPTER_INVALID_TRANSFORMATION_ID` | Zły format ID |
| `TransformationNotFoundException` | 404 | `AI_ADAPTER_TRANSFORMATION_NOT_FOUND` | Nie ma takiej transformacji |
| `DuplicateFileException` | 409 | `AI_ADAPTER_DUPLICATE_FILE` | Ten sam hash pliku |
| `TransformationAlreadyImportedException` | 409 | `AI_ADAPTER_ALREADY_IMPORTED` | Już zaimportowano |
| `AiServiceException` | 502 | `AI_ADAPTER_AI_SERVICE_ERROR` | Claude zwrócił błąd |
| `AiServiceUnavailableException` | 503 | `AI_ADAPTER_AI_SERVICE_UNAVAILABLE` | Claude niedostępny |
| `AiRateLimitExceededException` | 429 | `AI_ADAPTER_RATE_LIMIT_EXCEEDED` | Przekroczony limit API |
| `IngestionServiceException` | 502 | `AI_ADAPTER_INGESTION_SERVICE_ERROR` | Ingestion zwrócił błąd |
| `IngestionServiceUnavailableException` | 503 | `AI_ADAPTER_INGESTION_SERVICE_UNAVAILABLE` | Ingestion niedostępny |

### Przykładowe wyjątki

```java
@Getter
public class UnrecognizedCsvFormatException extends RuntimeException {
    private final String detectedHeaders;
    private final String sampleContent;

    public UnrecognizedCsvFormatException(String detectedHeaders, String sampleContent) {
        super(String.format("Could not recognize bank CSV format. Detected headers: [%s]", detectedHeaders));
        this.detectedHeaders = detectedHeaders;
        this.sampleContent = sampleContent;
    }
}

@Getter
public class DuplicateFileException extends RuntimeException {
    private final String fileHash;
    private final String existingTransformationId;

    public DuplicateFileException(String fileHash, String existingTransformationId) {
        super(String.format("File with hash [%s] already processed. Transformation ID: [%s]",
            fileHash, existingTransformationId));
        this.fileHash = fileHash;
        this.existingTransformationId = existingTransformationId;
    }
}

@Getter
public class AiServiceException extends RuntimeException {
    private final String aiErrorCode;
    private final String aiErrorMessage;
    private final int retryCount;

    public AiServiceException(String aiErrorCode, String aiErrorMessage, int retryCount) {
        super(String.format("AI service error [%s]: %s (after %d retries)",
            aiErrorCode, aiErrorMessage, retryCount));
        this.aiErrorCode = aiErrorCode;
        this.aiErrorMessage = aiErrorMessage;
        this.retryCount = retryCount;
    }
}
```

### Handlery w ErrorHttpHandler.java

```java
// ============ AI Bank CSV Adapter - Validation Errors (400) ============

@ExceptionHandler(EmptyFileException.class)
public ResponseEntity<ApiError> handleEmptyFile(EmptyFileException ex) {
    log.debug("Empty file uploaded");
    ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_EMPTY_FILE, ex.getMessage());
    return ResponseEntity.status(error.httpStatus()).body(error);
}

@ExceptionHandler(FileTooLargeException.class)
public ResponseEntity<ApiError> handleFileTooLarge(FileTooLargeException ex) {
    log.debug("File too large: {} bytes, max: {} bytes", ex.getFileSize(), ex.getMaxSize());
    ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_FILE_TOO_LARGE, ex.getMessage());
    return ResponseEntity.status(error.httpStatus()).body(error);
}

@ExceptionHandler(UnrecognizedCsvFormatException.class)
public ResponseEntity<ApiError> handleUnrecognizedFormat(UnrecognizedCsvFormatException ex) {
    log.warn("Unrecognized CSV format. Headers: {}", ex.getDetectedHeaders());
    ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_UNRECOGNIZED_FORMAT, ex.getMessage());
    return ResponseEntity.status(error.httpStatus()).body(error);
}

// ============ AI Bank CSV Adapter - Not Found (404) ============

@ExceptionHandler(TransformationNotFoundException.class)
public ResponseEntity<ApiError> handleTransformationNotFound(TransformationNotFoundException ex) {
    log.debug("Transformation not found: {}", ex.getTransformationId());
    ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_TRANSFORMATION_NOT_FOUND, ex.getMessage());
    return ResponseEntity.status(error.httpStatus()).body(error);
}

// ============ AI Bank CSV Adapter - Conflicts (409) ============

@ExceptionHandler(DuplicateFileException.class)
public ResponseEntity<ApiError> handleDuplicateFile(DuplicateFileException ex) {
    log.debug("Duplicate file detected. Hash: {}, existing: {}",
        ex.getFileHash(), ex.getExistingTransformationId());
    ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_DUPLICATE_FILE, ex.getMessage());
    return ResponseEntity.status(error.httpStatus()).body(error);
}

@ExceptionHandler(TransformationAlreadyImportedException.class)
public ResponseEntity<ApiError> handleAlreadyImported(TransformationAlreadyImportedException ex) {
    log.debug("Transformation already imported: {}", ex.getTransformationId());
    ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_ALREADY_IMPORTED, ex.getMessage());
    return ResponseEntity.status(error.httpStatus()).body(error);
}

// ============ AI Bank CSV Adapter - External Service Errors ============

@ExceptionHandler(AiServiceException.class)
public ResponseEntity<ApiError> handleAiServiceError(AiServiceException ex) {
    log.error("AI service error [{}]: {} (retries: {})",
        ex.getAiErrorCode(), ex.getAiErrorMessage(), ex.getRetryCount());
    ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_AI_SERVICE_ERROR, ex.getMessage());
    return ResponseEntity.status(error.httpStatus()).body(error);
}

@ExceptionHandler(AiServiceUnavailableException.class)
public ResponseEntity<ApiError> handleAiUnavailable(AiServiceUnavailableException ex) {
    log.error("AI service unavailable", ex);
    ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_AI_SERVICE_UNAVAILABLE, ex.getMessage());
    return ResponseEntity.status(error.httpStatus()).body(error);
}

@ExceptionHandler(AiRateLimitExceededException.class)
public ResponseEntity<ApiError> handleRateLimitExceeded(AiRateLimitExceededException ex) {
    log.warn("AI rate limit exceeded. Retry after: {}", ex.getRetryAfterSeconds());
    ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_RATE_LIMIT_EXCEEDED, ex.getMessage());
    return ResponseEntity.status(error.httpStatus()).body(error);
}

@ExceptionHandler(IngestionServiceException.class)
public ResponseEntity<ApiError> handleIngestionServiceError(IngestionServiceException ex) {
    log.error("Ingestion service error: {}", ex.getMessage());
    ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_INGESTION_SERVICE_ERROR, ex.getMessage());
    return ResponseEntity.status(error.httpStatus()).body(error);
}

@ExceptionHandler(IngestionServiceUnavailableException.class)
public ResponseEntity<ApiError> handleIngestionUnavailable(IngestionServiceUnavailableException ex) {
    log.error("Ingestion service unavailable", ex);
    ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_INGESTION_SERVICE_UNAVAILABLE, ex.getMessage());
    return ResponseEntity.status(error.httpStatus()).body(error);
}
```

---

## 14. Strategia testowania - szczegóły

### Poziomy testów

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              PIRAMIDA TESTÓW                                         │
└─────────────────────────────────────────────────────────────────────────────────────┘

                           ┌───────────────┐
                           │   E2E Tests   │  ← 2-3 testy (pełny flow z prawdziwym AI)
                           │   (manual)    │
                         ┌─┴───────────────┴─┐
                         │ Integration Tests │  ← 10-15 testów (REST + MockAI + MongoDB)
                       ┌─┴───────────────────┴─┐
                       │     Unit Tests        │  ← 30-40 testów (logika biznesowa)
                       └───────────────────────┘
```

### A) Unit Tests (bez Spring Context)

| Klasa testowa | Co testuje | Liczba testów |
|---------------|------------|---------------|
| `AiPromptBuilderTest` | Budowanie promptów | 5-8 |
| `AiResponseProcessorTest` | Parsowanie odpowiedzi AI | 10-15 |
| `CsvValidatorTest` | Walidacja CSV output | 8-10 |
| `FileHashCalculatorTest` | Obliczanie SHA-256 | 3-4 |
| `EncodingDetectorTest` | Wykrywanie UTF-8/CP1250 | 4-5 |

```java
@ExtendWith(MockitoExtension.class)
class AiResponseProcessorTest {

    @InjectMocks
    private AiResponseProcessor processor;

    @Test
    void shouldParseValidCsvResponse() {
        // Given
        String aiResponse = """
            bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
            NEST_2025-12-31_001,Prowizja,Opis,Opłaty,10.00,PLN,OUTFLOW,2025-12-31,2025-12-31,,
            """;

        // When
        AiTransformResult result = processor.process(aiResponse);

        // Then
        assertThat(result.success()).isTrue();
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.csvContent()).startsWith("bankTransactionId,");
    }

    @Test
    void shouldDetectErrorResponse() {
        // Given
        String aiResponse = """
            ERROR: UNRECOGNIZED_FORMAT
            MESSAGE: Could not identify columns
            SAMPLE: garbage,data
            """;

        // When
        AiTransformResult result = processor.process(aiResponse);

        // Then
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo(AiErrorCode.UNRECOGNIZED_FORMAT);
    }

    @Test
    void shouldRejectResponseWithoutHeader() {
        // Given
        String aiResponse = "Some random text without CSV header";

        // When
        AiTransformResult result = processor.process(aiResponse);

        // Then
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo(AiErrorCode.INVALID_RESPONSE);
    }

    @Test
    void shouldHandleEmptyResponse() {
        // Given
        String aiResponse = "";

        // When
        AiTransformResult result = processor.process(aiResponse);

        // Then
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo(AiErrorCode.EMPTY_RESPONSE);
    }

    @Test
    void shouldValidateRequiredColumns() {
        // Given - brak kolumny 'type'
        String aiResponse = """
            bankTransactionId,name,amount,currency,operationDate
            NEST_001,Test,100,PLN,2025-12-31
            """;

        // When
        AiTransformResult result = processor.process(aiResponse);

        // Then
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo(AiErrorCode.MISSING_REQUIRED_COLUMN);
    }
}
```

### B) Integration Tests (z MockServer dla AI)

| Klasa testowa | Co testuje | Liczba testów |
|---------------|------------|---------------|
| `AiBankCsvTransformServiceIntegrationTest` | Pełny flow transformacji | 5-6 |
| `AiBankCsvControllerTest` | REST endpoints | 8-10 |
| `TransformationAuditRepositoryTest` | Zapis/odczyt z MongoDB | 4-5 |
| `BankDataIngestionClientTest` | Komunikacja REST z ingestion | 3-4 |

```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AiBankCsvControllerTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiCsvTransformationRepository transformationRepository;

    @MockBean
    private ChatClient chatClient;  // Mock AI

    private String authToken;

    @BeforeEach
    void setUp() {
        authToken = createUserAndGetToken("testuser");
        transformationRepository.deleteAll();
    }

    // ============ Happy Path ============

    @Test
    void shouldTransformNestBankCsv() throws Exception {
        // Given
        byte[] csvContent = loadResource("nest-bank/sample.csv");
        String mockAiResponse = loadResource("nest-bank/expected-output.csv");

        when(chatClient.prompt()).thenReturn(mockPromptBuilder(mockAiResponse));

        // When
        MvcResult result = mockMvc.perform(multipart("/api/v1/bank-data-adapter/transform")
                .file(new MockMultipartFile("file", "nest.csv", "text/csv", csvContent))
                .param("bankHint", "Nest")
                .header("Authorization", "Bearer " + authToken))
            .andExpect(status().isOk())
            .andReturn();

        // Then
        TransformResponse response = parseResponse(result, TransformResponse.class);
        assertThat(response.success()).isTrue();
        assertThat(response.detectedBank()).isEqualTo("Nest");
        assertThat(response.rowCount()).isEqualTo(5);

        // Verify audit saved
        Optional<AiCsvTransformationDocument> audit =
            transformationRepository.findById(response.transformationId());
        assertThat(audit).isPresent();
        assertThat(audit.get().importStatus()).isEqualTo(ImportStatus.PENDING);
    }

    @Test
    void shouldReturnPreviewRows() throws Exception {
        // Given
        String transformationId = createSuccessfulTransformation();

        // When
        MvcResult result = mockMvc.perform(get("/api/v1/bank-data-adapter/{id}/preview", transformationId)
                .header("Authorization", "Bearer " + authToken))
            .andExpect(status().isOk())
            .andReturn();

        // Then
        PreviewResponse response = parseResponse(result, PreviewResponse.class);
        assertThat(response.previewRows()).hasSize(5);  // max 5 preview rows
        assertThat(response.totalRows()).isGreaterThanOrEqualTo(5);
    }

    // ============ Validation Errors (400) ============

    @Test
    void shouldRejectEmptyFile() throws Exception {
        // Given
        byte[] emptyContent = new byte[0];

        // When/Then
        mockMvc.perform(multipart("/api/v1/bank-data-adapter/transform")
                .file(new MockMultipartFile("file", "empty.csv", "text/csv", emptyContent))
                .header("Authorization", "Bearer " + authToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("AI_ADAPTER_EMPTY_FILE"));
    }

    @Test
    void shouldRejectFileTooLarge() throws Exception {
        // Given - 6MB file (limit is 5MB)
        byte[] largeContent = new byte[6 * 1024 * 1024];

        // When/Then
        mockMvc.perform(multipart("/api/v1/bank-data-adapter/transform")
                .file(new MockMultipartFile("file", "large.csv", "text/csv", largeContent))
                .header("Authorization", "Bearer " + authToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("AI_ADAPTER_FILE_TOO_LARGE"));
    }

    @Test
    void shouldRejectNonCsvFile() throws Exception {
        // Given
        byte[] pdfContent = loadResource("invalid/sample.pdf");

        // When/Then
        mockMvc.perform(multipart("/api/v1/bank-data-adapter/transform")
                .file(new MockMultipartFile("file", "report.pdf", "application/pdf", pdfContent))
                .header("Authorization", "Bearer " + authToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("AI_ADAPTER_INVALID_FILE_TYPE"));
    }

    @Test
    void shouldRejectInvalidTransformationIdFormat() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/v1/bank-data-adapter/{id}", "INVALID-ID")
                .header("Authorization", "Bearer " + authToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("AI_ADAPTER_INVALID_TRANSFORMATION_ID"));
    }

    // ============ Not Found (404) ============

    @Test
    void shouldReturn404ForNonExistentTransformation() throws Exception {
        // Given
        String nonExistentId = UUID.randomUUID().toString();

        // When/Then
        mockMvc.perform(get("/api/v1/bank-data-adapter/{id}", nonExistentId)
                .header("Authorization", "Bearer " + authToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("AI_ADAPTER_TRANSFORMATION_NOT_FOUND"));
    }

    // ============ Conflicts (409) ============

    @Test
    void shouldRejectDuplicateFile() throws Exception {
        // Given - first upload
        byte[] csvContent = loadResource("nest-bank/sample.csv");
        when(chatClient.prompt()).thenReturn(mockPromptBuilder(loadResource("nest-bank/expected-output.csv")));

        mockMvc.perform(multipart("/api/v1/bank-data-adapter/transform")
                .file(new MockMultipartFile("file", "nest.csv", "text/csv", csvContent))
                .header("Authorization", "Bearer " + authToken))
            .andExpect(status().isOk());

        // When - second upload with same content
        mockMvc.perform(multipart("/api/v1/bank-data-adapter/transform")
                .file(new MockMultipartFile("file", "nest_copy.csv", "text/csv", csvContent))
                .header("Authorization", "Bearer " + authToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("AI_ADAPTER_DUPLICATE_FILE"));
    }

    @Test
    void shouldRejectImportOfAlreadyImportedTransformation() throws Exception {
        // Given
        String transformationId = createSuccessfulTransformation();
        markAsImported(transformationId);

        // When/Then
        mockMvc.perform(post("/api/v1/bank-data-adapter/{id}/import", transformationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cashFlowId\": \"CF10000001\"}")
                .header("Authorization", "Bearer " + authToken))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("AI_ADAPTER_ALREADY_IMPORTED"));
    }

    // ============ AI Service Errors (502/503) ============

    @Test
    void shouldHandle502WhenAiReturnsError() throws Exception {
        // Given
        byte[] csvContent = loadResource("nest-bank/sample.csv");
        when(chatClient.prompt()).thenThrow(new RuntimeException("AI Error: Invalid request"));

        // When/Then
        mockMvc.perform(multipart("/api/v1/bank-data-adapter/transform")
                .file(new MockMultipartFile("file", "nest.csv", "text/csv", csvContent))
                .header("Authorization", "Bearer " + authToken))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.errorCode").value("AI_ADAPTER_AI_SERVICE_ERROR"));
    }

    @Test
    void shouldHandle429WhenRateLimited() throws Exception {
        // Given
        byte[] csvContent = loadResource("nest-bank/sample.csv");
        when(chatClient.prompt()).thenThrow(new RateLimitException("Rate limit exceeded", 60));

        // When/Then
        mockMvc.perform(multipart("/api/v1/bank-data-adapter/transform")
                .file(new MockMultipartFile("file", "nest.csv", "text/csv", csvContent))
                .header("Authorization", "Bearer " + authToken))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.errorCode").value("AI_ADAPTER_RATE_LIMIT_EXCEEDED"));
    }

    // ============ Authorization ============

    @Test
    void shouldReturn401WithoutToken() throws Exception {
        // When/Then
        mockMvc.perform(multipart("/api/v1/bank-data-adapter/transform")
                .file(new MockMultipartFile("file", "test.csv", "text/csv", "data".getBytes())))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldNotAllowAccessToOtherUsersTransformation() throws Exception {
        // Given - user1 creates transformation
        String user1Token = createUserAndGetToken("user1");
        String transformationId = createTransformationAsUser(user1Token);

        // When - user2 tries to access
        String user2Token = createUserAndGetToken("user2");

        // Then
        mockMvc.perform(get("/api/v1/bank-data-adapter/{id}", transformationId)
                .header("Authorization", "Bearer " + user2Token))
            .andExpect(status().isNotFound());  // 404 not 403 (security through obscurity)
    }
}
```

### C) Integration Test - komunikacja REST z ingestion

```java
@SpringBootTest
@Testcontainers
class BankDataIngestionClientIntegrationTest extends IntegrationTest {

    @Autowired
    private BankDataIngestionClient ingestionClient;

    @Autowired
    private AiCsvTransformationRepository transformationRepository;

    private String authToken;
    private String cashFlowId;

    @BeforeEach
    void setUp() {
        authToken = createUserAndGetToken("testuser");
        cashFlowId = createCashFlowForUser(authToken);
    }

    @Test
    void shouldSendTransformedCsvToIngestionAndCreateStagingSession() {
        // Given
        String transformedCsv = """
            bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
            NEST_2025-12-31_001,Prowizja,Opis,Opłaty,10.00,PLN,OUTFLOW,2025-12-31,2025-12-31,,
            NEST_2025-12-30_002,Przelew,Opis2,Przelewy,500.00,PLN,OUTFLOW,2025-12-30,2025-12-30,,PL123
            """;

        // When
        UploadCsvResponse response = ingestionClient.sendToIngestion(
            cashFlowId,
            transformedCsv,
            "transformed_nest.csv"
        );

        // Then
        assertThat(response.stagingSessionId()).isNotNull();
        assertThat(response.parseSummary().totalRows()).isEqualTo(2);
        assertThat(response.parseSummary().successfulRows()).isEqualTo(2);
        assertThat(response.parseSummary().failedRows()).isEqualTo(0);
    }

    @Test
    void shouldHandleIngestionServiceError() {
        // Given - invalid cashFlowId
        String invalidCashFlowId = "CF99999999";
        String transformedCsv = "bankTransactionId,name,...";

        // When/Then
        assertThatThrownBy(() ->
            ingestionClient.sendToIngestion(invalidCashFlowId, transformedCsv, "test.csv"))
            .isInstanceOf(IngestionServiceException.class)
            .hasMessageContaining("CashFlow not found");
    }
}
```

### D) Golden File Tests (dla AI response)

```java
class AiResponseGoldenFileTest {

    @ParameterizedTest
    @MethodSource("bankCsvSamples")
    void shouldCorrectlyTransformBankCsv(String bankName, String inputFile, String expectedOutputFile) {
        // Given
        String input = loadResource("golden/" + inputFile);
        String expectedOutput = loadResource("golden/" + expectedOutputFile);

        // When - symulacja odpowiedzi AI (w prawdziwym teście byłby mock)
        String actualOutput = simulateAiTransform(input);

        // Then
        assertThat(normalizecsv(actualOutput))
            .isEqualTo(normalizeCsv(expectedOutput));
    }

    static Stream<Arguments> bankCsvSamples() {
        return Stream.of(
            Arguments.of("Nest", "nest-input.csv", "nest-expected.csv"),
            Arguments.of("mBank", "mbank-input.csv", "mbank-expected.csv"),
            Arguments.of("ING", "ing-input.csv", "ing-expected.csv"),
            Arguments.of("Deutsche Bank", "deutsche-input.csv", "deutsche-expected.csv")
        );
    }
}
```

### E) Testy E2E (manualne, z prawdziwym AI)

```java
@SpringBootTest
@Tag("e2e")
@Tag("manual")
@Disabled("Run manually - uses real AI API")
class AiBankCsvE2ETest extends IntegrationTest {

    @Autowired
    private AiBankCsvTransformService transformService;

    @Test
    void shouldTransformRealNestBankCsv() {
        // Given - prawdziwy plik z Nest Bank (zanonimizowany)
        byte[] realCsv = loadResource("real-samples/nest-bank-anonymized.csv");

        // When - prawdziwe wywołanie Claude API
        AiTransformResult result = transformService.transform(realCsv, "Nest");

        // Then
        assertThat(result.success()).isTrue();
        assertThat(result.detectedBank()).isEqualTo("Nest");
        assertThat(result.rowCount()).isGreaterThan(100);

        // Verify CSV is valid
        List<BankCsvRow> rows = csvParser.parseFromString(result.csvContent());
        assertThat(rows).isNotEmpty();
        rows.forEach(row -> {
            assertThat(row.name()).isNotBlank();
            assertThat(row.amount()).isPositive();
            assertThat(row.type()).isIn(Type.INFLOW, Type.OUTFLOW);
            assertThat(row.operationDate()).isNotNull();
        });
    }
}
```

### Podsumowanie testów

| Typ testu | Liczba | Czas wykonania | Kiedy uruchamiać |
|-----------|--------|----------------|------------------|
| **Unit Tests** | ~40 | ~5 sek | Każdy commit |
| **Integration Tests** | ~25 | ~60 sek | Każdy PR |
| **Golden File Tests** | ~10 | ~2 sek | Każdy commit |
| **E2E Tests (manual)** | ~3 | ~30 sek | Przed release |
| **RAZEM** | ~78 | ~2 min | - |

### Struktura katalogów testowych

```
src/test/
├── java/com/multi/vidulum/bank_data_adapter/
│   ├── app/
│   │   └── AiBankCsvTransformServiceTest.java
│   ├── infrastructure/
│   │   ├── AiPromptBuilderTest.java
│   │   ├── AiResponseProcessorTest.java
│   │   └── BankDataIngestionClientTest.java
│   ├── rest/
│   │   └── AiBankCsvControllerTest.java
│   ├── domain/
│   │   └── AiCsvTransformationRepositoryTest.java
│   └── e2e/
│       └── AiBankCsvE2ETest.java
└── resources/
    └── bank_data_adapter/
        ├── nest-bank/
        │   ├── sample.csv
        │   └── expected-output.csv
        ├── mbank/
        │   ├── sample.csv
        │   └── expected-output.csv
        ├── golden/
        │   ├── nest-input.csv
        │   ├── nest-expected.csv
        │   └── ...
        ├── invalid/
        │   ├── sample.pdf
        │   └── malformed.csv
        └── real-samples/
            └── nest-bank-anonymized.csv
```

---

## 15. Plan implementacji

### Faza 1: Port Interface + Core Service + Audyt (PR-1)

**Pliki do utworzenia:**
- `bank_data_adapter/port/BankCsvTransformPort.java` ← **NOWE (interface)**
- `bank_data_adapter/port/TransformRequest.java` ← **NOWE (DTO)**
- `bank_data_adapter/port/TransformResult.java` ← **NOWE (DTO)**
- `bank_data_adapter/app/AiBankCsvTransformService.java`
- `bank_data_adapter/app/DirectBankCsvTransformAdapter.java` ← **NOWE (adapter)**
- `bank_data_adapter/infrastructure/AiPromptBuilder.java`
- `bank_data_adapter/infrastructure/AiResponseProcessor.java`
- `bank_data_adapter/infrastructure/AiTransformResult.java`
- `bank_data_adapter/infrastructure/AiErrorCode.java`
- `bank_data_adapter/domain/AiCsvTransformationDocument.java` ← **NOWE (audyt)**
- `bank_data_adapter/domain/AiCsvTransformationRepository.java` ← **NOWE (audyt)**

**Zmiany w pom.xml:**
- Dodać Spring AI BOM
- Dodać `spring-ai-starter-model-anthropic`

**Konfiguracja:**
- `application.properties` - Spring AI Anthropic config
- `.env` - ANTHROPIC_API_KEY

### Faza 2: REST Controller (PR-2)

**Pliki do utworzenia:**
- `bank_data_adapter/rest/AiBankCsvController.java`
- `bank_data_adapter/rest/TransformResponse.java`
- `bank_data_adapter/rest/TransformationHistoryResponse.java` ← **NOWE (historia)**

**Endpoints:**
- `POST /api/v1/bank-data-adapter/transform` - transformacja CSV
- `POST /api/v1/bank-data-adapter/transform-and-parse` - transformacja + parsowanie
- `GET /api/v1/bank-data-adapter/history` - historia transformacji użytkownika ← **NOWE**
- `GET /api/v1/bank-data-adapter/{id}/download` - pobranie wygenerowanego CSV ← **NOWE**

### Faza 3: Integracja z bank-data-ingestion (PR-3)

**Zmiany:**
- `bank-data-ingestion` używa `BankCsvTransformPort` (nie bezpośrednio serwis)
- Dodać opcję w `BankDataIngestionRestController` do użycia AI transform
- Aktualizacja statusu `importStatus` po imporcie

### Faza 4: Testy i dokumentacja (PR-4)

**Pliki:**
- Testy jednostkowe i integracyjne
- Testy wielojęzyczności (DE, EN, FR samples)
- Zanonimizowane sample CSV
- Aktualizacja tego dokumentu

### Faza 5: (PRZYSZŁOŚĆ) Kafka Adapter dla Microservices

**Pliki do utworzenia (gdy będziemy wydzielać mikroserwisy):**
- `bank_data_adapter/app/KafkaBankCsvTransformAdapter.java`
- `bank_data_adapter/events/TransformRequestedEvent.java`
- `bank_data_adapter/events/CsvTransformedEvent.java`
- Konfiguracja nowych topiców Kafka

### Diagram zależności

```
PR-1 (Port Interface + Core Service + Audyt MongoDB)
  │
  └──► PR-2 (REST Controller + Historia)
         │
         └──► PR-3 (Integracja z ingestion przez Port)
                │
                └──► PR-4 (Testy + Wielojęzyczność)
                       │
                       └──► PR-5 (PRZYSZŁOŚĆ: Kafka Adapter)
```

---

## Appendix A: Konfiguracja Spring AI

### pom.xml

```xml
<properties>
    <spring-ai.version>2.0.0-M3</spring-ai.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-anthropic</artifactId>
    </dependency>
</dependencies>
```

### application.properties

```properties
# Spring AI - Anthropic / Claude
spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY:}
spring.ai.anthropic.chat.options.model=claude-haiku-3-5-20241022
spring.ai.anthropic.chat.options.temperature=0.1
spring.ai.anthropic.chat.options.max-tokens=16384
```

### .env

```env
ANTHROPIC_API_KEY=sk-ant-api03-xxxxx
ANTHROPIC_ENABLED=true
```

---

## Appendix B: Przykładowa odpowiedź AI

### Sukces

```csv
bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
NEST_2025-12-31_001,Prowizja za przelew natychmiastowy wychodzący KIR,Prowizja za przelew natychmiastowy wychodzący KIR,Opłaty i prowizje,10.00,PLN,OUTFLOW,2025-12-31,2025-12-31,,
NEST_2025-12-31_002,zycie,Przelew do: Lucjan Bik Pekao,Przelewy wychodzące,3000.00,PLN,OUTFLOW,2025-12-31,2025-12-31,,PL98124014441111001078171074
NEST_2025-12-23_003,czynsz Lokal: 00-070 -020 (Lokal mieszkalny) Przy Torach 3/20,Przelew do: Silva Silva Warszawa,Przelewy wychodzące,1689.50,PLN,OUTFLOW,2025-12-23,2025-12-23,,22102049002879287900000091
NEST_2025-12-11_004,PCB9QO25 11/2025,Przelew od: MINDBOX SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ UL. ZŁOTA 59 00-120 WARSZAWA,Przelewy przychodzące,31064.44,PLN,INFLOW,2025-12-11,2025-12-11,82109018830000000109874194,
```

### Błąd

```
ERROR: UNRECOGNIZED_FORMAT
MESSAGE: Could not identify transaction columns. Expected columns like 'Data', 'Kwota', 'Tytuł' not found.
SAMPLE: random;garbage;data
xyz;123;???
abc;456;!!!
```

---

## 16. Ochrona danych - Anonimizacja przed wysłaniem do AI (TODO)

**Status:** Do implementacji (follow-up)
**Priorytet:** Wysoki (prywatność danych)

### Problem

Obecna implementacja wysyła **cały oryginalny plik CSV** do Claude AI, co oznacza że wrażliwe dane finansowe (kwoty, nazwy kontrahentów, numery IBAN, opisy transakcji) trafiają do zewnętrznego API.

### Propozycja rozwiązania: Lokalna transformacja z AI-wygenerowanymi regułami

Zamiast wysyłać pełne dane do AI, wysyłamy tylko **zanonimizowany sample (5-10 wierszy)** aby AI wykryło strukturę i zwróciło **reguły mapowania**, które następnie stosujemy **lokalnie** na pełnych danych.

### Architektura

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Oryginalny CSV │────▶│  Zanonimizowany  │────▶│   Claude AI     │
│  (wrażliwe dane)│     │  CSV (sample)    │     │   (mapowanie)   │
└─────────────────┘     └──────────────────┘     └─────────────────┘
        │                                                 │
        │                                                 ▼
        │               ┌──────────────────┐     ┌─────────────────┐
        │               │  Lokalna         │◀────│  Reguły         │
        │               │  transformacja   │     │  mapowania JSON │
        │               └──────────────────┘     └─────────────────┘
        │                        │
        ▼                        ▼
┌─────────────────┐     ┌──────────────────┐
│  Dane oryginalne│────▶│  Canonical CSV   │
│  (nie wysyłane) │     │  (pełne dane)    │
└─────────────────┘     └──────────────────┘
```

### Co AI dostaje (sample 5-10 wierszy, zanonimizowany)

```csv
# ORYGINAŁ:
31-12-2025,31-12-2025,Przelewy wychodzące,-3000,PLN,"Jan Kowalski",PL98124014441111001078171074,"czynsz za grudzień"

# DO AI (zanonimizowany):
31-12-2025,31-12-2025,Przelewy wychodzące,-1234.56,PLN,"PERSON_1",IBAN_1,"TEXT_1"
```

### Co AI zwraca (reguły mapowania JSON)

```json
{
  "detectedBank": "Nest Bank",
  "detectedLanguage": "pl",
  "detectedCountry": "PL",
  "headerRowIndex": 6,
  "skipRows": [0, 1, 2, 3, 4, 5],
  "columnMapping": {
    "bookingDate": {
      "sourceColumn": 0,
      "format": "dd-MM-yyyy",
      "outputFormat": "yyyy-MM-dd"
    },
    "operationDate": {
      "sourceColumn": 1,
      "format": "dd-MM-yyyy",
      "outputFormat": "yyyy-MM-dd"
    },
    "bankCategory": {
      "sourceColumn": 2
    },
    "amount": {
      "sourceColumn": 3,
      "transformation": "ABS",
      "decimalSeparator": ","
    },
    "currency": {
      "sourceColumn": 4
    },
    "counterpartyName": {
      "sourceColumn": 5
    },
    "counterpartyAccount": {
      "sourceColumn": 6
    },
    "description": {
      "sourceColumn": 7
    },
    "type": {
      "derivedFrom": "amount",
      "rule": "NEGATIVE_IS_OUTFLOW"
    },
    "bankTransactionId": {
      "generated": true,
      "pattern": "{BANK}_{DATE}_{ROW}"
    }
  },
  "typeMapping": {
    "Przelewy wychodzące": "OUTFLOW",
    "Przelewy przychodzące": "INFLOW",
    "Opłaty i prowizje": "OUTFLOW"
  }
}
```

### Nowe komponenty do implementacji

```
com.multi.vidulum.bank_data_adapter/
├── infrastructure/
│   ├── CsvAnonymizer.java           # Anonimizuje dane przed wysłaniem
│   ├── MappingRulesExtractor.java   # Parsuje JSON z regułami od AI
│   ├── LocalCsvTransformer.java     # Stosuje reguły lokalnie
│   └── MappingRulesCache.java       # Cache reguł per bank (opcjonalnie)
└── domain/
    └── MappingRules.java            # Model reguł mapowania
```

### CsvAnonymizer - strategie anonimizacji

| Typ danych | Oryginał | Zanonimizowany | Strategia |
|------------|----------|----------------|-----------|
| Kwota | `-3000.50` | `-1234.56` | Losowa kwota (zachowuje znak) |
| IBAN | `PL98124014441111001078171074` | `IBAN_1` | Placeholder z indeksem |
| Nazwa osoby | `Jan Kowalski` | `PERSON_1` | Placeholder z indeksem |
| Nazwa firmy | `MINDBOX SP. Z O.O.` | `COMPANY_1` | Placeholder z indeksem |
| Opis | `czynsz za grudzień` | `TEXT_1` | Placeholder z indeksem |
| Data | `31-12-2025` | `31-12-2025` | **Bez zmian** (potrzebne do wykrycia formatu) |
| Kategoria | `Przelewy wychodzące` | `Przelewy wychodzące` | **Bez zmian** (potrzebne do mapowania typu) |
| Waluta | `PLN` | `PLN` | **Bez zmian** |

### Zalety podejścia

| Aspekt | Obecne rozwiązanie | Z anonimizacją |
|--------|-------------------|----------------|
| **Prywatność** | ❌ Pełne dane do AI | ✅ Tylko sample + placeholdery |
| **Rozmiar payloadu** | 402 wiersze (~50KB) | 5-10 wierszy (~2KB) |
| **Koszt API** | ~$0.20-0.25 | **~$0.01-0.02** (10-20x taniej!) |
| **Szybkość** | ~3-5 sek | **~0.5-1 sek** |
| **Cache-owalność** | ❌ Każdy plik osobno | ✅ Reguły per bank |

### Potencjalne cache'owanie reguł

Po wykryciu banku (np. "Nest Bank"), reguły mapowania mogą być cache'owane:

```java
@Component
public class MappingRulesCache {

    private final Map<String, MappingRules> cache = new ConcurrentHashMap<>();

    public Optional<MappingRules> get(String bankName) {
        return Optional.ofNullable(cache.get(bankName.toLowerCase()));
    }

    public void put(String bankName, MappingRules rules) {
        cache.put(bankName.toLowerCase(), rules);
    }
}
```

Dzięki temu dla kolejnych plików z tego samego banku **nie trzeba w ogóle wywoływać AI** - wystarczy zastosować zcache'owane reguły.

### 16.1 Szybka walidacja pliku (przed anonimizacją)

**Cel:** Odrzucić oczywiście nieprawidłowe pliki ZANIM rozpoczniemy kosztowne operacje (anonimizację, AI call).

#### Walidatory (kolejność wykonania)

```java
@Component
@RequiredArgsConstructor
public class QuickCsvValidator {

    private static final Set<String> VALID_EXTENSIONS = Set.of(".csv", ".txt");
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;  // 5MB
    private static final int MIN_ROWS = 2;  // header + 1 data row
    private static final int MIN_COLUMNS = 3;  // minimum: date, amount, description

    // Magic bytes for common non-CSV files
    private static final byte[] PDF_MAGIC = new byte[]{0x25, 0x50, 0x44, 0x46};  // %PDF
    private static final byte[] ZIP_MAGIC = new byte[]{0x50, 0x4B, 0x03, 0x04};  // PK..
    private static final byte[] XLS_MAGIC = new byte[]{(byte)0xD0, (byte)0xCF, 0x11, (byte)0xE0};  // OLE compound

    public QuickValidationResult validate(byte[] content, String fileName) {
        // 1. Extension check (fastest)
        if (!hasValidExtension(fileName)) {
            return QuickValidationResult.invalid("INVALID_EXTENSION",
                "Expected .csv or .txt file, got: " + getExtension(fileName));
        }

        // 2. Size check
        if (content.length == 0) {
            return QuickValidationResult.invalid("EMPTY_FILE", "File is empty");
        }
        if (content.length > MAX_FILE_SIZE) {
            return QuickValidationResult.invalid("FILE_TOO_LARGE",
                "File size " + content.length + " exceeds limit " + MAX_FILE_SIZE);
        }

        // 3. Magic bytes check (detect binary files disguised as CSV)
        if (isBinaryFile(content)) {
            return QuickValidationResult.invalid("BINARY_FILE_DETECTED",
                "File appears to be binary (PDF, ZIP, XLS), not CSV");
        }

        // 4. Encoding check (must be valid text)
        String text = tryDecode(content);
        if (text == null) {
            return QuickValidationResult.invalid("INVALID_ENCODING",
                "Could not decode file as UTF-8 or CP1250");
        }

        // 5. Structure check (has rows and columns)
        StructureInfo structure = analyzeStructure(text);
        if (structure.rowCount() < MIN_ROWS) {
            return QuickValidationResult.invalid("INSUFFICIENT_ROWS",
                "File has only " + structure.rowCount() + " rows, need at least " + MIN_ROWS);
        }
        if (structure.columnCount() < MIN_COLUMNS) {
            return QuickValidationResult.invalid("INSUFFICIENT_COLUMNS",
                "File has only " + structure.columnCount() + " columns, need at least " + MIN_COLUMNS);
        }

        // 6. Bank CSV heuristics (optional - quick pattern matching)
        if (!looksLikeBankCsv(text, structure)) {
            return QuickValidationResult.warning("UNUSUAL_FORMAT",
                "File structure doesn't match typical bank CSV patterns");
        }

        return QuickValidationResult.valid(structure);
    }

    private boolean isBinaryFile(byte[] content) {
        if (content.length < 4) return false;
        return startsWith(content, PDF_MAGIC)
            || startsWith(content, ZIP_MAGIC)
            || startsWith(content, XLS_MAGIC);
    }

    private boolean looksLikeBankCsv(String text, StructureInfo structure) {
        // Quick heuristics - bank CSVs typically have:
        // - Date-like columns (pattern: dd-mm-yyyy, dd.mm.yyyy, yyyy-mm-dd)
        // - Amount-like columns (numbers with decimal, possibly negative)
        // - At least one text column (description/title)

        String firstDataRow = structure.firstDataRow();
        return containsDatePattern(firstDataRow)
            && containsAmountPattern(firstDataRow);
    }

    private boolean containsDatePattern(String row) {
        // Matches: 31-12-2025, 31.12.2025, 2025-12-31, 12/31/2025
        return row.matches(".*\\d{2}[-./]\\d{2}[-./]\\d{4}.*")
            || row.matches(".*\\d{4}[-./]\\d{2}[-./]\\d{2}.*");
    }

    private boolean containsAmountPattern(String row) {
        // Matches: -3000, 3000.50, -3000,50, 3 000,50
        return row.matches(".*-?\\d[\\d\\s]*[.,]?\\d*.*");
    }
}
```

#### Wynik walidacji

```java
public record QuickValidationResult(
    boolean valid,
    boolean warning,
    String errorCode,
    String message,
    StructureInfo structure  // null if invalid
) {
    public static QuickValidationResult valid(StructureInfo structure) {
        return new QuickValidationResult(true, false, null, null, structure);
    }

    public static QuickValidationResult warning(String code, String message) {
        return new QuickValidationResult(true, true, code, message, null);
    }

    public static QuickValidationResult invalid(String code, String message) {
        return new QuickValidationResult(false, false, code, message, null);
    }
}

public record StructureInfo(
    int rowCount,
    int columnCount,
    String detectedDelimiter,
    String firstDataRow,
    int estimatedHeaderRow
) {}
```

#### Kolejność walidacji (fail-fast)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  QUICK VALIDATION PIPELINE (< 10ms dla 5MB pliku)                           │
└─────────────────────────────────────────────────────────────────────────────┘

  Upload          Extension       Size            Magic Bytes      Encoding
    │               Check          Check            Check           Check
    ▼               │              │                │               │
 ┌──────┐      ┌────┴────┐    ┌────┴────┐      ┌────┴────┐     ┌────┴────┐
 │ File │──────│ .csv?   │────│ <5MB?   │──────│ Not     │─────│ UTF-8/  │
 │      │      │ .txt?   │    │ >0?     │      │ binary? │     │ CP1250? │
 └──────┘      └────┬────┘    └────┬────┘      └────┬────┘     └────┬────┘
                    │              │                │               │
                   ✗ 400          ✗ 400            ✗ 400           ✗ 400
                                                                    │
                                                                    ▼
                                                            ┌───────────────┐
                                                            │  Structure    │
                                                            │  Analysis     │
                                                            │  (rows/cols)  │
                                                            └───────┬───────┘
                                                                    │
                                                   ✓ valid          │           ✗ 400
                                              ┌─────────────────────┴────────────────┐
                                              │                                      │
                                              ▼                                      │
                                        ┌───────────┐                                │
                                        │  Continue │                                │
                                        │  to       │                                │
                                        │  Anonymize│                                │
                                        └───────────┘                                │
                                                                                     ▼
                                                                              ┌────────────┐
                                                                              │ 400 Error  │
                                                                              │ with code  │
                                                                              └────────────┘
```

#### Koszty walidacji vs koszty AI

| Operacja | Czas | Koszt |
|----------|------|-------|
| Quick validation | ~5-10ms | $0.00 |
| Anonimizacja | ~50-100ms | $0.00 |
| AI call | ~500-2000ms | ~$0.02 |

**Wniosek:** Odrzucenie pliku na etapie quick validation oszczędza ~500-2000ms i ~$0.02 na **każdym** nieprawidłowym pliku.

### 16.2 Cache reguł mapowania (MongoDB)

**Cel:** Po wykryciu banku i uzyskaniu reguł mapowania od AI, cache'ujemy je w MongoDB. Kolejne pliki z tego samego banku **nie wymagają wywołania AI** - koszt $0.00.

#### Model MongoDB

```java
@Document(collection = "bank_mapping_rules")
public class BankMappingRulesDocument {

    @Id
    private String id;  // UUID

    // Identyfikacja banku
    private String bankName;           // "Nest Bank", "mBank", "PKO BP"
    private String bankCode;           // Kod z IBAN: "1870" (Nest), "1140" (mBank)
    private String detectedLanguage;   // "pl", "de", "en"
    private String detectedCountry;    // "PL", "DE", "UK"

    // Hash struktury CSV (do wykrywania zmian formatu)
    private String structureHash;      // SHA-256 z nagłówków + delimiter + skipRows

    // Reguły parsowania
    private int headerRowIndex;        // np. 6 dla Nest Bank
    private List<Integer> skipRows;    // [0,1,2,3,4,5] - metadata rows
    private String delimiter;          // "," lub ";"

    // Mapowanie kolumn
    private Map<String, ColumnMapping> columnMappings;

    // Mapowanie kategorii → typ transakcji
    private Map<String, TransactionType> categoryToTypeMapping;

    // Statystyki użycia
    private int timesUsedSuccessfully;
    private int timesUsedWithErrors;
    private double successRate;

    // Wersjonowanie
    private int version;               // Inkrementowane gdy AI zwróci nowe reguły
    private String aiModel;            // "claude-3-haiku-20240307"
    private String aiPromptHash;       // Hash promptu (do invalidacji przy zmianach)

    // Audit
    private String createdByUserId;
    private ZonedDateTime createdAt;
    private ZonedDateTime lastUsedAt;
    private ZonedDateTime lastModifiedAt;
}

@Data
public class ColumnMapping {
    private int sourceColumnIndex;
    private String sourceColumnName;     // Nazwa z banku: "Kwota", "Betrag", "Amount"
    private String targetField;          // Pole w BankCsvRow: "amount", "operationDate"
    private String transformation;       // "ABS", "NEGATE", "DATE_FORMAT", null
    private String inputFormat;          // "dd-MM-yyyy", "dd.MM.yyyy"
    private String outputFormat;         // "yyyy-MM-dd"
    private String decimalSeparator;     // "," lub "."
    private String thousandsSeparator;   // " " lub "." lub null
    private String defaultValue;         // Wartość domyślna jeśli puste
}
```

#### BankIdentifierService

```java
@Service
@Slf4j
public class BankIdentifierService {

    // Polish bank codes (from IBAN)
    private static final Map<String, String> POLISH_BANK_CODES = Map.ofEntries(
        entry("1870", "NEST_BANK"),
        entry("1140", "MBANK"),
        entry("1020", "PKO_BP"),
        entry("1050", "ING_BANK"),
        entry("1090", "SANTANDER"),
        entry("1160", "MILLENNIUM"),
        entry("1240", "PEKAO"),
        entry("1130", "BGK"),
        entry("1030", "CITI_HANDLOWY"),
        entry("1060", "BNP_PARIBAS")
    );

    // Keywords per bank (in headers or metadata)
    private static final Map<String, List<String>> BANK_KEYWORDS = Map.of(
        "NEST_BANK", List.of("nest", "nest bank", "187010"),
        "MBANK", List.of("mbank", "m bank", "bre bank", "114010"),
        "PKO_BP", List.of("pko", "ipko", "iko", "powszechna kasa", "102010"),
        "ING_BANK", List.of("ing", "ing bank", "105010"),
        "SANTANDER", List.of("santander", "bzwbk", "109010"),
        "MILLENNIUM", List.of("millennium", "bank millennium", "116010")
    );

    public BankIdentificationResult identify(String csvContent) {
        // 1. Try IBAN detection (most reliable)
        Optional<String> ibanBank = detectFromIban(csvContent);
        if (ibanBank.isPresent()) {
            return BankIdentificationResult.confident(ibanBank.get(), "IBAN");
        }

        // 2. Try keyword detection
        Optional<String> keywordBank = detectFromKeywords(csvContent);
        if (keywordBank.isPresent()) {
            return BankIdentificationResult.probable(keywordBank.get(), "KEYWORD");
        }

        // 3. Unknown - will need AI
        return BankIdentificationResult.unknown();
    }

    private Optional<String> detectFromIban(String csvContent) {
        // Find Polish IBAN: PL + 2 check digits + 4 bank code + rest
        Pattern ibanPattern = Pattern.compile("PL\\d{2}(\\d{4})\\d{16,20}");
        Matcher matcher = ibanPattern.matcher(csvContent);

        if (matcher.find()) {
            String bankCode = matcher.group(1);
            return Optional.ofNullable(POLISH_BANK_CODES.get(bankCode));
        }
        return Optional.empty();
    }

    public String calculateStructureHash(String csvContent) {
        // Hash tylko struktury, nie danych:
        // - Nagłówki kolumn
        // - Wykryty delimiter
        // - Liczba wierszy metadata

        StructureInfo structure = analyzeStructure(csvContent);
        String hashInput = String.join("|",
            structure.headerRow(),
            structure.delimiter(),
            String.valueOf(structure.metadataRowCount())
        );

        return DigestUtils.sha256Hex(hashInput);
    }
}

public record BankIdentificationResult(
    String bankName,
    String detectionMethod,   // "IBAN", "KEYWORD", "AI"
    double confidence         // 1.0 for IBAN, 0.7 for keyword, 0.9 for AI
) {
    public static BankIdentificationResult confident(String bank, String method) {
        return new BankIdentificationResult(bank, method, 1.0);
    }
    public static BankIdentificationResult probable(String bank, String method) {
        return new BankIdentificationResult(bank, method, 0.7);
    }
    public static BankIdentificationResult unknown() {
        return new BankIdentificationResult(null, null, 0.0);
    }
}
```

#### MappingRulesCacheService

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class MappingRulesCacheService {

    private final BankMappingRulesRepository repository;
    private final BankIdentifierService bankIdentifier;

    // L1 cache (memory) - szybki dostęp
    private final Map<String, BankMappingRulesDocument> memoryCache = new ConcurrentHashMap<>();

    /**
     * Próbuje znaleźć reguły mapowania dla danego CSV.
     *
     * @return Optional z regułami jeśli znaleziono i pasują
     */
    public Optional<BankMappingRulesDocument> findRules(String csvContent) {
        // 1. Identyfikuj bank
        BankIdentificationResult bankId = bankIdentifier.identify(csvContent);
        if (bankId.bankName() == null) {
            log.debug("Could not identify bank - cache miss");
            return Optional.empty();
        }

        // 2. Oblicz hash struktury
        String structureHash = bankIdentifier.calculateStructureHash(csvContent);
        String cacheKey = bankId.bankName() + "_" + structureHash;

        // 3. Sprawdź L1 cache (memory)
        BankMappingRulesDocument cached = memoryCache.get(cacheKey);
        if (cached != null) {
            log.debug("L1 cache hit for bank={}, hash={}", bankId.bankName(), structureHash);
            return Optional.of(cached);
        }

        // 4. Sprawdź L2 cache (MongoDB)
        Optional<BankMappingRulesDocument> mongoRules = repository
            .findByBankNameAndStructureHash(bankId.bankName(), structureHash);

        if (mongoRules.isPresent()) {
            log.debug("L2 cache hit for bank={}, hash={}", bankId.bankName(), structureHash);
            memoryCache.put(cacheKey, mongoRules.get());  // Populate L1
            return mongoRules;
        }

        log.debug("Cache miss for bank={}, hash={}", bankId.bankName(), structureHash);
        return Optional.empty();
    }

    /**
     * Zapisuje nowe reguły mapowania (po uzyskaniu od AI).
     */
    public BankMappingRulesDocument saveRules(BankMappingRulesDocument rules) {
        // Zapisz do MongoDB
        BankMappingRulesDocument saved = repository.save(rules);

        // Dodaj do L1 cache
        String cacheKey = rules.getBankName() + "_" + rules.getStructureHash();
        memoryCache.put(cacheKey, saved);

        log.info("Saved mapping rules for bank={}, hash={}, id={}",
            rules.getBankName(), rules.getStructureHash(), saved.getId());

        return saved;
    }

    /**
     * Aktualizuje statystyki po użyciu reguł.
     */
    public void recordUsage(String rulesId, boolean success) {
        repository.findById(rulesId).ifPresent(rules -> {
            if (success) {
                rules.setTimesUsedSuccessfully(rules.getTimesUsedSuccessfully() + 1);
            } else {
                rules.setTimesUsedWithErrors(rules.getTimesUsedWithErrors() + 1);
            }

            int total = rules.getTimesUsedSuccessfully() + rules.getTimesUsedWithErrors();
            rules.setSuccessRate((double) rules.getTimesUsedSuccessfully() / total);
            rules.setLastUsedAt(ZonedDateTime.now());

            repository.save(rules);
        });
    }

    /**
     * Invaliduje cache dla danego banku (np. gdy wykryto błędy).
     */
    public void invalidateForBank(String bankName) {
        memoryCache.entrySet().removeIf(e -> e.getKey().startsWith(bankName + "_"));
        log.info("Invalidated L1 cache for bank={}", bankName);
    }
}
```

#### LocalCsvTransformer

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class LocalCsvTransformer {

    /**
     * Transformuje CSV używając lokalnych reguł (bez AI).
     */
    public LocalTransformResult transform(String csvContent, BankMappingRulesDocument rules) {
        List<String> lines = csvContent.lines().toList();
        List<String> warnings = new ArrayList<>();

        // Skip metadata rows
        int dataStartRow = rules.getHeaderRowIndex() + 1;

        StringBuilder output = new StringBuilder();

        // Output header
        output.append("bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber\n");

        int rowNumber = 0;
        int successCount = 0;
        int errorCount = 0;

        for (int i = dataStartRow; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;

            rowNumber++;

            try {
                String[] sourceColumns = parseCsvLine(line, rules.getDelimiter());
                String transformedRow = transformRow(sourceColumns, rules, rowNumber);
                output.append(transformedRow).append("\n");
                successCount++;
            } catch (Exception e) {
                log.warn("Error transforming row {}: {}", rowNumber, e.getMessage());
                warnings.add("Row " + rowNumber + ": " + e.getMessage());
                errorCount++;
            }
        }

        return new LocalTransformResult(
            true,
            output.toString(),
            rules.getBankName(),
            successCount,
            errorCount,
            warnings
        );
    }

    private String transformRow(String[] sourceColumns, BankMappingRulesDocument rules, int rowNumber) {
        Map<String, ColumnMapping> mappings = rules.getColumnMappings();

        // Extract values using mappings
        String bankTransactionId = generateTransactionId(rules.getBankName(), sourceColumns, mappings, rowNumber);
        String name = extractField(sourceColumns, mappings.get("name"));
        String description = extractField(sourceColumns, mappings.get("description"));
        String bankCategory = extractField(sourceColumns, mappings.get("bankCategory"));
        String amount = transformAmount(sourceColumns, mappings.get("amount"));
        String currency = extractField(sourceColumns, mappings.get("currency"));
        String type = determineType(sourceColumns, rules);
        String operationDate = transformDate(sourceColumns, mappings.get("operationDate"));
        String bookingDate = transformDate(sourceColumns, mappings.get("bookingDate"));
        String sourceAccount = extractField(sourceColumns, mappings.get("sourceAccountNumber"));
        String targetAccount = extractField(sourceColumns, mappings.get("targetAccountNumber"));

        return String.join(",",
            escapeCsv(bankTransactionId),
            escapeCsv(name),
            escapeCsv(description),
            escapeCsv(bankCategory),
            amount,
            currency,
            type,
            operationDate,
            bookingDate,
            escapeCsv(sourceAccount),
            escapeCsv(targetAccount)
        );
    }

    private String transformAmount(String[] sourceColumns, ColumnMapping mapping) {
        String raw = sourceColumns[mapping.getSourceColumnIndex()];

        // Handle decimal/thousands separators
        String normalized = raw
            .replace(mapping.getThousandsSeparator() != null ? mapping.getThousandsSeparator() : "", "")
            .replace(mapping.getDecimalSeparator(), ".");

        BigDecimal value = new BigDecimal(normalized.trim());

        // Apply transformation (e.g., ABS)
        if ("ABS".equals(mapping.getTransformation())) {
            value = value.abs();
        }

        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String transformDate(String[] sourceColumns, ColumnMapping mapping) {
        if (mapping == null) return "";

        String raw = sourceColumns[mapping.getSourceColumnIndex()];

        DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern(mapping.getInputFormat());
        DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern(mapping.getOutputFormat());

        LocalDate date = LocalDate.parse(raw.trim(), inputFormatter);
        return date.format(outputFormatter);
    }

    private String determineType(String[] sourceColumns, BankMappingRulesDocument rules) {
        // Option 1: From category mapping
        String category = extractField(sourceColumns, rules.getColumnMappings().get("bankCategory"));
        TransactionType mappedType = rules.getCategoryToTypeMapping().get(category);
        if (mappedType != null) {
            return mappedType.name();
        }

        // Option 2: From amount sign
        ColumnMapping amountMapping = rules.getColumnMappings().get("amount");
        String rawAmount = sourceColumns[amountMapping.getSourceColumnIndex()];
        return rawAmount.trim().startsWith("-") ? "OUTFLOW" : "INFLOW";
    }
}

public record LocalTransformResult(
    boolean success,
    String csvContent,
    String bankName,
    int successfulRows,
    int failedRows,
    List<String> warnings
) {}
```

#### Pełny flow z cache

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                        FLOW Z CACHE'OWANIEM REGUŁ                                    │
└─────────────────────────────────────────────────────────────────────────────────────┘

  Upload CSV
       │
       ▼
┌──────────────────┐
│ 1. Quick         │ ── FAIL ──► 400 Invalid File
│    Validation    │
└────────┬─────────┘
         │ PASS
         ▼
┌──────────────────┐     ┌─────────────────┐
│ 2. Identify Bank │────▶│ BankIdentifier  │
│    + Calc Hash   │     │ Service         │
└────────┬─────────┘     └─────────────────┘
         │
         ▼
┌──────────────────┐     ┌─────────────────┐     ┌───────────────┐
│ 3. Check Cache   │────▶│ L1 Memory Cache │────▶│ L2 MongoDB    │
│    (rules)       │     │ (ConcurrentMap) │     │ (bank_mapping │
└────────┬─────────┘     └─────────────────┘     │  _rules)      │
         │                                        └───────────────┘
         │
    ┌────┴────┐
    │         │
 HIT ▼      MISS ▼
    │         │
    │    ┌────┴─────────────┐
    │    │ 4. Anonymize CSV │  (tylko przy MISS!)
    │    │    (5-10 rows)   │
    │    └────────┬─────────┘
    │             │
    │    ┌────────┴─────────┐
    │    │ 5. Call AI       │  (koszt ~$0.02)
    │    │    (get rules)   │
    │    └────────┬─────────┘
    │             │
    │    ┌────────┴─────────┐
    │    │ 6. Save Rules    │
    │    │    to Cache      │
    │    └────────┬─────────┘
    │             │
    └──────┬──────┘
           │
           ▼
┌──────────────────┐
│ 7. Local         │  (zawsze - bez AI)
│    Transform     │
│    (full CSV)    │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ 8. Validate      │
│    Output        │
└────────┬─────────┘
         │
         ▼
   Return BankCsvRow CSV
```

#### Porównanie kosztów

| Scenariusz | AI calls | Koszt | Czas |
|------------|----------|-------|------|
| **Pierwszy plik z Nest Bank** | 1 | ~$0.02 | ~1-2s |
| **Drugi plik z Nest Bank** | 0 | **$0.00** | ~100ms |
| **Trzeci plik z Nest Bank** | 0 | **$0.00** | ~100ms |
| **Pierwszy plik z mBank** | 1 | ~$0.02 | ~1-2s |
| **Drugi plik z mBank** | 0 | **$0.00** | ~100ms |

**100 plików (5 banków):** 5 × $0.02 = **$0.10** zamiast 100 × $0.02 = **$2.00** (20× oszczędność!)

### 16.3 Granulacja cache: Bank + Waluta (globalne reguły)

**Pytanie:** Czy zamiast cache'ować reguły per CashFlow/User, warto cache'ować globalnie per Bank + Waluta?

#### Analiza

Obecnie w sekcji 16.2 cache jest powiązany z `structureHash` który jest unikalny dla struktury CSV:
- Ten sam bank (np. Nest Bank) generuje **identyczny format CSV** dla wszystkich użytkowników
- Format może się różnić tylko po **walucie** (PLN vs EUR mają różne kolumny w niektórych bankach)
- Format może się zmienić po **aktualizacji systemu bankowego** (rzadko, ~1-2x rocznie)

#### Porównanie podejść

| Aspekt | Per CashFlow | Per User | **Per Bank + Currency (GLOBALNE)** |
|--------|--------------|----------|-----------------------------------|
| **Klucz cache** | `{cashFlowId}_{structureHash}` | `{userId}_{bankName}_{structureHash}` | **`{bankName}_{currency}_{structureHash}`** |
| **AI calls (100 userów, 1 bank)** | 100 × N cashflows | 100 | **1** |
| **Koszt (100 userów, Nest Bank PLN)** | ~$2.00+ | ~$2.00 | **~$0.02** |
| **Izolacja** | ✅ Pełna | ✅ Per user | ⚠️ Współdzielone |
| **Prywatność** | ✅ | ✅ | ✅ (reguły nie zawierają danych) |
| **Cache hit rate** | Niska | Średnia | **Bardzo wysoka** |

#### Rekomendacja: GLOBALNE cache per Bank + Currency + StructureHash

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                     GLOBALNE CACHE - WSPÓŁDZIELONE PRZEZ WSZYSTKICH USERÓW           │
└─────────────────────────────────────────────────────────────────────────────────────┘

  User A (Nest Bank PLN)         User B (Nest Bank PLN)         User C (Nest Bank PLN)
         │                              │                              │
         │                              │                              │
         ▼                              ▼                              ▼
  ┌──────────────┐               ┌──────────────┐               ┌──────────────┐
  │ Upload CSV   │               │ Upload CSV   │               │ Upload CSV   │
  │ (1st ever)   │               │ (2nd user)   │               │ (3rd user)   │
  └──────┬───────┘               └──────┬───────┘               └──────┬───────┘
         │                              │                              │
         ▼                              ▼                              ▼
  ┌──────────────┐               ┌──────────────┐               ┌──────────────┐
  │ Identify:    │               │ Identify:    │               │ Identify:    │
  │ NEST_BANK    │               │ NEST_BANK    │               │ NEST_BANK    │
  │ PLN          │               │ PLN          │               │ PLN          │
  │ hash: abc123 │               │ hash: abc123 │               │ hash: abc123 │
  └──────┬───────┘               └──────┬───────┘               └──────┬───────┘
         │                              │                              │
         ▼                              ▼                              ▼
  ┌────────────────────────────────────────────────────────────────────────────┐
  │                     GLOBAL CACHE: bank_mapping_rules                        │
  │                                                                             │
  │   Key: "NEST_BANK_PLN_abc123"                                              │
  │                                                                             │
  │   ┌─────────────────────────────────────────────────────────────────────┐  │
  │   │ BankMappingRulesDocument                                            │  │
  │   │   bankName: "NEST_BANK"                                             │  │
  │   │   currency: "PLN"                                                   │  │
  │   │   structureHash: "abc123"                                           │  │
  │   │   columnMappings: {...}                                             │  │
  │   │   timesUsed: 3                                                      │  │
  │   │   usedByUsers: ["userA", "userB", "userC"]  // for stats only      │  │
  │   └─────────────────────────────────────────────────────────────────────┘  │
  └────────────────────────────────────────────────────────────────────────────┘
         │                              │                              │
    MISS (AI call)                  HIT ($0)                       HIT ($0)
         │                              │                              │
         ▼                              ▼                              ▼
  Cost: ~$0.02                    Cost: $0.00                    Cost: $0.00
```

#### Zaktualizowany model MongoDB

```java
@Document(collection = "bank_mapping_rules")
public class BankMappingRulesDocument {

    @Id
    private String id;  // UUID

    // === KLUCZ GLOBALNY (per bank + currency + structure) ===
    @Indexed
    private String bankName;           // "NEST_BANK", "MBANK"
    @Indexed
    private String currency;           // "PLN", "EUR", "USD"
    @Indexed(unique = true)
    private String cacheKey;           // "NEST_BANK_PLN_abc123" (computed)
    private String structureHash;      // SHA-256 z nagłówków + delimiter

    // === Reguły mapowania (GLOBALNE - identyczne dla wszystkich userów) ===
    private int headerRowIndex;
    private List<Integer> skipRows;
    private String delimiter;
    private Map<String, ColumnMapping> columnMappings;
    private Map<String, TransactionType> categoryToTypeMapping;

    // === Statystyki GLOBALNE ===
    private int totalTimesUsed;              // Ile razy użyte (wszyscy userzy)
    private int totalSuccessful;
    private int totalFailed;
    private double globalSuccessRate;
    private Set<String> distinctUsersUsed;   // Unikalny userzy (do statystyk)

    // === Metadata ===
    private String detectedLanguage;
    private String detectedCountry;
    private int version;
    private String aiModel;

    // === Audit (kto pierwszy stworzył) ===
    private String createdByUserId;          // Tylko dla audytu
    private ZonedDateTime createdAt;
    private ZonedDateTime lastUsedAt;

    // === Computed field ===
    public String computeCacheKey() {
        return bankName + "_" + currency + "_" + structureHash;
    }
}
```

#### Zaktualizowany MappingRulesCacheService

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class GlobalMappingRulesCacheService {

    private final BankMappingRulesRepository repository;
    private final BankIdentifierService bankIdentifier;

    // L1 cache - GLOBALNY (współdzielony przez wszystkich userów w JVM)
    private final Map<String, BankMappingRulesDocument> globalCache = new ConcurrentHashMap<>();

    public Optional<BankMappingRulesDocument> findRules(String csvContent) {
        // 1. Identify bank + currency
        BankIdentificationResult bankId = bankIdentifier.identify(csvContent);
        if (bankId.bankName() == null) {
            return Optional.empty();
        }

        String currency = bankIdentifier.detectCurrency(csvContent);  // "PLN", "EUR"
        String structureHash = bankIdentifier.calculateStructureHash(csvContent);

        // 2. Build GLOBAL cache key
        String cacheKey = bankId.bankName() + "_" + currency + "_" + structureHash;

        // 3. Check L1 (memory) - GLOBALNY
        BankMappingRulesDocument cached = globalCache.get(cacheKey);
        if (cached != null) {
            log.debug("Global L1 cache HIT: {}", cacheKey);
            return Optional.of(cached);
        }

        // 4. Check L2 (MongoDB) - GLOBALNY
        Optional<BankMappingRulesDocument> mongoRules = repository.findByCacheKey(cacheKey);
        if (mongoRules.isPresent()) {
            log.debug("Global L2 cache HIT: {}", cacheKey);
            globalCache.put(cacheKey, mongoRules.get());
            return mongoRules;
        }

        log.debug("Global cache MISS: {}", cacheKey);
        return Optional.empty();
    }

    /**
     * Zapisuje reguły GLOBALNIE - dostępne dla wszystkich userów.
     */
    public BankMappingRulesDocument saveGlobalRules(
            BankMappingRulesDocument rules,
            String creatingUserId) {

        rules.setCacheKey(rules.computeCacheKey());
        rules.setCreatedByUserId(creatingUserId);
        rules.setDistinctUsersUsed(Set.of(creatingUserId));
        rules.setTotalTimesUsed(1);

        BankMappingRulesDocument saved = repository.save(rules);
        globalCache.put(rules.getCacheKey(), saved);

        log.info("Saved GLOBAL mapping rules: {} (created by user={})",
            rules.getCacheKey(), creatingUserId);

        return saved;
    }

    /**
     * Rejestruje użycie reguł przez usera.
     */
    public void recordUsage(String cacheKey, String userId, boolean success) {
        repository.findByCacheKey(cacheKey).ifPresent(rules -> {
            rules.setTotalTimesUsed(rules.getTotalTimesUsed() + 1);
            if (success) {
                rules.setTotalSuccessful(rules.getTotalSuccessful() + 1);
            } else {
                rules.setTotalFailed(rules.getTotalFailed() + 1);
            }

            // Track distinct users
            rules.getDistinctUsersUsed().add(userId);

            // Recalculate success rate
            int total = rules.getTotalSuccessful() + rules.getTotalFailed();
            rules.setGlobalSuccessRate((double) rules.getTotalSuccessful() / total);
            rules.setLastUsedAt(ZonedDateTime.now());

            repository.save(rules);

            // Update L1 cache
            globalCache.put(cacheKey, rules);
        });
    }
}
```

#### Wykrywanie waluty

```java
@Service
public class BankIdentifierService {

    // ... existing code ...

    public String detectCurrency(String csvContent) {
        // 1. Explicit currency column
        if (csvContent.contains(",PLN,") || csvContent.contains(";PLN;")) {
            return "PLN";
        }
        if (csvContent.contains(",EUR,") || csvContent.contains(";EUR;")) {
            return "EUR";
        }
        if (csvContent.contains(",USD,") || csvContent.contains(";USD;")) {
            return "USD";
        }

        // 2. From IBAN country code
        if (csvContent.contains("PL") && csvContent.matches(".*PL\\d{26}.*")) {
            return "PLN";  // Polish IBAN = likely PLN
        }
        if (csvContent.matches(".*DE\\d{20}.*")) {
            return "EUR";  // German IBAN = likely EUR
        }

        // 3. Default based on detected country
        return "UNKNOWN";
    }
}
```

#### Bezpieczeństwo i prywatność

| Aspekt | Analiza |
|--------|---------|
| **Co jest w cache?** | Tylko reguły mapowania (indeksy kolumn, formaty dat) |
| **Czy zawiera dane użytkownika?** | ❌ NIE - tylko struktura, nie treść |
| **Czy user A widzi dane user B?** | ❌ NIE - reguły są abstrakcyjne |
| **Czy to narusza GDPR?** | ❌ NIE - brak PII w regułach |

**Przykład co jest w cache:**
```json
{
  "columnMappings": {
    "amount": {"sourceColumnIndex": 3, "transformation": "ABS"},
    "operationDate": {"sourceColumnIndex": 1, "inputFormat": "dd-MM-yyyy"}
  }
}
```
To są tylko **metadane o strukturze** - nie ma żadnych danych finansowych.

#### Scenariusze edge-case

| Scenariusz | Rozwiązanie |
|------------|-------------|
| **Bank zmienia format CSV** | Nowy `structureHash` → nowy wpis w cache |
| **Bank ma różne formaty dla PLN/EUR** | Osobne klucze: `NEST_BANK_PLN_xxx`, `NEST_BANK_EUR_yyy` |
| **Bank ma różne formaty iPKO vs IKO** | Różne `structureHash` → osobne wpisy |
| **Reguły mają błędy** | `globalSuccessRate` < 0.9 → invalidate + retry AI |
| **Nowa wersja AI/promptu** | `aiPromptHash` w kluczu lub `version` bump |

#### Koszt globalnego cache (przykład)

| Scenariusz | Users | Banks | AI Calls | Koszt |
|------------|-------|-------|----------|-------|
| **Per CashFlow** | 1000 | 5 | 5000+ | ~$100+ |
| **Per User** | 1000 | 5 | 1000 | ~$20 |
| **GLOBALNY (Bank+Currency)** | 1000 | 5 | **5** | **~$0.10** |

**Oszczędność: 99.9%** przy dużej skali!

#### Rekomendacja końcowa

✅ **TAK - warto użyć globalnego cache per Bank + Currency + StructureHash**

**Argumenty ZA:**
1. **Ogromna oszczędność** - jeden AI call dla wszystkich userów danego banku
2. **Brak ryzyka prywatności** - reguły nie zawierają danych
3. **Prostsza architektura** - jeden globalny cache zamiast per-user
4. **Lepszy cache hit rate** - więcej userów = szybciej "rozgrzany" cache
5. **Automatyczne uczenie** - `globalSuccessRate` pokazuje jakość reguł

**Kiedy NIE używać globalnego cache:**
- Gdyby reguły zawierały dane specyficzne dla usera (ale nie zawierają)
- Gdyby banki generowały różne formaty per konto (nie generują)

### Plan implementacji (follow-up)

1. **PR-5a: QuickCsvValidator**
   - Magic bytes detection (PDF, ZIP, XLS)
   - Extension validation
   - Structure analysis (rows, columns, delimiter)
   - Bank CSV heuristics

2. **PR-5b: CsvAnonymizer**
   - Wykrywanie typów danych (IBAN, kwota, nazwa, etc.)
   - Generowanie placeholderów
   - Zachowanie struktury CSV

3. **PR-5c: BankIdentifierService + MappingRulesCache**
   - IBAN-based bank detection
   - Keyword-based fallback
   - L1 (memory) + L2 (MongoDB) cache
   - Structure hash calculation

4. **PR-5d: LocalCsvTransformer**
   - Apply mapping rules locally
   - Date/amount transformations
   - Type derivation

5. **PR-5e: MappingRules model + AI prompt**
   - BankMappingRulesDocument model
   - New prompt returning JSON rules
   - Rules validation

6. **PR-5f: Integration + fallback**
   - Wire everything together
   - Fallback to full AI if rules fail
   - Usage statistics tracking

### Uwagi implementacyjne

- **Fallback:** Jeśli lokalna transformacja nie działa (np. nieoczekiwany format), można wrócić do obecnego flow z pełnym CSV
- **Weryfikacja:** Po lokalnej transformacji warto zwalidować kilka losowych wierszy
- **Wersjonowanie reguł:** Różne wersje eksportu z tego samego banku mogą wymagać różnych reguł

---

*Dokument wygenerowany: 2026-03-19*
*Ostatnia aktualizacja: 2026-03-20 (dodano sekcję 16.3 - Globalny cache per Bank + Currency)*
