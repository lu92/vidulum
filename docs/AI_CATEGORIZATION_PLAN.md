# Plan Implementacji AI do Kategoryzacji Transakcji

## 1. Przegląd

### Cel
Automatyczna kategoryzacja transakcji bankowych przy użyciu modelu AI, gdy:
- Brak kategorii z banku (`bankCategory` jest pusty)
- Kategoria bankowa nie ma skonfigurowanego mapowania
- Użytkownik chce otrzymać sugestię dla nowej transakcji

### Założenia
- **Abstrakcja LLM** - implementacja niezależna od dostawcy (Claude, OpenAI, Ollama, etc.)
- **Batch processing** - grupowanie transakcji dla optymalizacji kosztów
- **Learning loop** - akceptacja sugestii tworzy mapowanie na przyszłość
- **Graceful degradation** - brak AI nie blokuje importu (fallback na "Uncategorized")

---

## 2. Architektura

### 2.1 Diagram przepływu

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        STAGING TRANSACTIONS                              │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
                    ┌───────────────────────────────┐
                    │  Czy istnieje CategoryMapping? │
                    └───────────────────────────────┘
                         │                    │
                        TAK                  NIE
                         │                    │
                         ▼                    ▼
              ┌──────────────────┐  ┌──────────────────────────┐
              │ Użyj istniejącego│  │ AiCategorizationService  │
              │    mapowania     │  │                          │
              └──────────────────┘  │ - Zbierz batch transakcji│
                         │          │ - Wyślij do LLM Provider │
                         │          │ - Otrzymaj sugestie      │
                         │          └──────────────────────────┘
                         │                    │
                         │                    ▼
                         │          ┌──────────────────────────┐
                         │          │   AiCategorySuggestion   │
                         │          │ - categoryName           │
                         │          │ - parentCategoryName     │
                         │          │ - confidence (0-100)     │
                         │          │ - reasoning              │
                         │          └──────────────────────────┘
                         │                    │
                         ▼                    ▼
              ┌─────────────────────────────────────────────┐
              │            StagedTransaction                │
              │ - mappedData (z mapowania LUB z AI)         │
              │ - aiSuggestion (jeśli z AI)                 │
              │ - suggestionSource: MAPPING | AI | FALLBACK │
              └─────────────────────────────────────────────┘
                                    │
                                    ▼
              ┌─────────────────────────────────────────────┐
              │              PREVIEW (UI)                   │
              │ - Pokaż transakcje z sugestiami AI          │
              │ - Użytkownik akceptuje/odrzuca/edytuje      │
              └─────────────────────────────────────────────┘
                                    │
                         ┌──────────┴──────────┐
                         │                     │
                    AKCEPTUJ              ODRZUĆ/EDYTUJ
                         │                     │
                         ▼                     ▼
              ┌──────────────────┐  ┌──────────────────────┐
              │ Utwórz nowe      │  │ Użytkownik wybiera   │
              │ CategoryMapping  │  │ kategorię ręcznie    │
              │ (auto-learning)  │  │ → nowe CategoryMapping│
              └──────────────────┘  └──────────────────────┘
```

### 2.2 Komponenty

```
com.multi.vidulum.ai_categorization/
├── domain/
│   ├── AiCategorySuggestion.java        # Sugestia z AI
│   ├── CategorizationRequest.java       # Request do AI
│   ├── CategorizationContext.java       # Kontekst (kategorie użytkownika)
│   └── SuggestionSource.java            # Enum: MAPPING, AI, FALLBACK
│
├── app/
│   ├── AiCategorizationService.java     # Główny serwis
│   ├── AiCategorizationConfig.java      # Konfiguracja
│   └── commands/
│       └── categorize_transactions/
│           ├── CategorizeTransactionsCommand.java
│           └── CategorizeTransactionsCommandHandler.java
│
├── infrastructure/
│   ├── LlmProvider.java                 # Interfejs abstrakcji LLM
│   ├── LlmProviderFactory.java          # Factory dla providerów
│   ├── providers/
│   │   ├── ClaudeLlmProvider.java       # Implementacja Claude
│   │   ├── OpenAiLlmProvider.java       # Implementacja OpenAI
│   │   ├── OllamaLlmProvider.java       # Implementacja Ollama (lokalne)
│   │   └── MockLlmProvider.java         # Mock do testów
│   └── prompt/
│       └── CategorizationPromptBuilder.java  # Budowanie promptów
```

---

## 3. Szczegóły implementacji

### 3.1 Abstrakcja LLM Provider

```java
// Interfejs - niezależny od dostawcy
public interface LlmProvider {

    /**
     * Kategoryzuje batch transakcji
     * @param requests lista transakcji do kategoryzacji
     * @param context kontekst (dostępne kategorie, przykłady)
     * @return lista sugestii
     */
    List<AiCategorySuggestion> categorize(
        List<CategorizationRequest> requests,
        CategorizationContext context
    );

    /**
     * Sprawdza dostępność providera
     */
    boolean isAvailable();

    /**
     * Nazwa providera (do logów)
     */
    String getProviderName();
}
```

### 3.2 Model sugestii

```java
public record AiCategorySuggestion(
    String transactionId,           // ID transakcji której dotyczy
    CategoryName suggestedCategory, // Sugerowana kategoria
    CategoryName parentCategory,    // Kategoria nadrzędna (nullable)
    int confidence,                 // 0-100%
    String reasoning,               // Wyjaśnienie decyzji AI
    MappingAction suggestedAction   // CREATE_NEW, MAP_TO_EXISTING, etc.
) {}
```

### 3.3 Kontekst kategoryzacji

```java
public record CategorizationContext(
    List<CategoryInfo> availableCategories,  // Dostępne kategorie użytkownika
    List<ExampleMapping> examples,           // Przykłady z istniejących mapowań
    String userLocale,                       // pl_PL - dla kontekstu językowego
    String currency                          // PLN - waluta domyślna
) {}

public record CategoryInfo(
    String name,
    String parentName,              // null dla top-level
    Type type,                      // INFLOW/OUTFLOW
    List<String> keywords           // Opcjonalne słowa kluczowe
) {}

public record ExampleMapping(
    String transactionName,         // np. "ŻABKA WARSZAWA"
    String categoryName,            // np. "Zakupy spożywcze"
    Type type
) {}
```

### 3.4 Request kategoryzacji

```java
public record CategorizationRequest(
    String transactionId,
    String name,                    // Tytuł transakcji
    String description,             // Opis (nullable)
    String bankCategory,            // Kategoria z banku (nullable)
    BigDecimal amount,
    String currency,
    Type type,                      // INFLOW/OUTFLOW
    LocalDate date
) {}
```

---

## 4. Konfiguracja

### 4.1 application.yml

```yaml
vidulum:
  ai-categorization:
    enabled: true
    provider: claude                    # claude | openai | ollama | mock

    # Parametry batch processing
    batch-size: 20                      # Max transakcji w jednym request
    timeout-seconds: 30                 # Timeout per request

    # Progi confidence
    auto-accept-threshold: 90           # >= 90% - auto-akceptacja
    suggest-threshold: 50               # >= 50% - pokaż jako sugestię
    # < 50% - fallback na "Uncategorized"

    # Retry policy
    max-retries: 3
    retry-delay-ms: 1000

    # Rate limiting
    requests-per-minute: 60

    # Provider-specific
    claude:
      api-key: ${CLAUDE_API_KEY}
      model: claude-3-haiku-20240307    # Tańszy model do kategoryzacji

    openai:
      api-key: ${OPENAI_API_KEY}
      model: gpt-4o-mini

    ollama:
      base-url: http://localhost:11434
      model: llama3.1:8b
```

### 4.2 Konfiguracja Java

```java
@Configuration
@ConfigurationProperties(prefix = "vidulum.ai-categorization")
public class AiCategorizationConfig {
    private boolean enabled = false;
    private String provider = "mock";
    private int batchSize = 20;
    private int timeoutSeconds = 30;
    private int autoAcceptThreshold = 90;
    private int suggestThreshold = 50;
    private int maxRetries = 3;
    private int retryDelayMs = 1000;
    private int requestsPerMinute = 60;

    // Nested configs for providers
    private ClaudeConfig claude = new ClaudeConfig();
    private OpenAiConfig openai = new OpenAiConfig();
    private OllamaConfig ollama = new OllamaConfig();

    // getters, setters...
}
```

---

## 5. Prompt Template

### 5.1 System Prompt

```
Jesteś asystentem do kategoryzacji transakcji bankowych.
Twoim zadaniem jest przypisanie każdej transakcji do odpowiedniej kategorii.

ZASADY:
1. Analizuj nazwę transakcji, opis, kwotę i typ (wpływ/wydatek)
2. Przypisuj do ISTNIEJĄCYCH kategorii gdy to możliwe
3. Sugeruj nową kategorię tylko gdy żadna istniejąca nie pasuje
4. Podaj confidence (0-100) i krótkie uzasadnienie
5. Dla polskich transakcji rozpoznawaj typowe nazwy sklepów/usług

DOSTĘPNE KATEGORIE:
{{categories}}

PRZYKŁADY MAPOWAŃ:
{{examples}}
```

### 5.2 User Prompt (per batch)

```
Skategoryzuj poniższe transakcje:

{{#each transactions}}
[{{index}}]
- Nazwa: {{name}}
- Opis: {{description}}
- Kwota: {{amount}} {{currency}}
- Typ: {{type}}
- Data: {{date}}
- Kategoria bankowa: {{bankCategory}}

{{/each}}

Odpowiedz w formacie JSON:
{
  "suggestions": [
    {
      "index": 1,
      "category": "nazwa kategorii",
      "parentCategory": "kategoria nadrzędna lub null",
      "action": "MAP_TO_EXISTING | CREATE_NEW | CREATE_SUBCATEGORY",
      "confidence": 85,
      "reasoning": "krótkie uzasadnienie"
    }
  ]
}
```

---

## 6. Integracja z istniejącym kodem

### 6.1 Modyfikacja StagedTransaction

Dodać nowe pola:

```java
public record StagedTransaction(
    // ... istniejące pola ...

    // NOWE POLA:
    AiCategorySuggestion aiSuggestion,      // Sugestia AI (nullable)
    SuggestionSource suggestionSource        // MAPPING | AI | FALLBACK
) {}
```

### 6.2 Modyfikacja StageTransactionsCommandHandler

```java
// Pseudo-kod zmian
public StageTransactionsResult handle(StageTransactionsCommand command) {
    // 1. Załaduj mapowania (bez zmian)
    Map<String, CategoryMapping> mappings = loadMappings(command.cashFlowId());

    // 2. Podziel transakcje na: z mapowaniem i bez
    var partitioned = transactions.stream()
        .collect(partitioningBy(t -> mappings.containsKey(key(t))));

    var withMapping = partitioned.get(true);
    var withoutMapping = partitioned.get(false);

    // 3. Dla transakcji z mapowaniem - użyj istniejącej logiki
    var mappedTransactions = withMapping.stream()
        .map(t -> createStagedTransaction(t, mappings.get(key(t)), MAPPING))
        .toList();

    // 4. NOWE: Dla transakcji bez mapowania - użyj AI
    var aiSuggestions = aiCategorizationService.categorize(
        withoutMapping,
        buildContext(command.cashFlowId())
    );

    var aiTransactions = withoutMapping.stream()
        .map(t -> createStagedTransactionWithAi(t, aiSuggestions.get(t.id())))
        .toList();

    // 5. Połącz i zwróć
    return new StageTransactionsResult(
        concat(mappedTransactions, aiTransactions),
        buildSummary()
    );
}
```

### 6.3 Nowy endpoint do akceptacji sugestii AI

```java
// POST /api/v1/bank-data-ingestion/{cashFlowId}/staging/{sessionId}/accept-suggestions
@PostMapping("/{cashFlowId}/staging/{stagingSessionId}/accept-suggestions")
public ResponseEntity<AcceptSuggestionsResponse> acceptAiSuggestions(
    @PathVariable String cashFlowId,
    @PathVariable String stagingSessionId,
    @RequestBody AcceptSuggestionsRequest request
) {
    // request zawiera listę transactionId do akceptacji
    // Dla każdej zaakceptowanej sugestii:
    // 1. Utwórz CategoryMapping (auto-learning)
    // 2. Zaktualizuj StagedTransaction.suggestionSource = MAPPING
}
```

---

## 7. Etapy implementacji

### Etap 1: Fundament (podstawa)
- [ ] Utworzenie struktury pakietów `ai_categorization`
- [ ] Implementacja `LlmProvider` interface
- [ ] Implementacja `MockLlmProvider` (do testów)
- [ ] Implementacja `AiCategorizationConfig`
- [ ] Testy jednostkowe

### Etap 2: Pierwszy provider
- [ ] Implementacja `ClaudeLlmProvider` LUB `OpenAiLlmProvider`
- [ ] Implementacja `CategorizationPromptBuilder`
- [ ] Testy integracyjne z prawdziwym API

### Etap 3: Integracja ze stagingiem
- [ ] Modyfikacja `StagedTransaction` - nowe pola
- [ ] Modyfikacja `StageTransactionsCommandHandler`
- [ ] Implementacja `AiCategorizationService`
- [ ] Testy integracyjne

### Etap 4: Auto-learning
- [ ] Endpoint akceptacji sugestii
- [ ] Automatyczne tworzenie `CategoryMapping`
- [ ] Testy E2E

### Etap 5: Dodatkowi providerzy (opcjonalnie)
- [ ] `OllamaLlmProvider` (lokalne modele)
- [ ] `OpenAiLlmProvider` (jeśli nie był pierwszy)
- [ ] Fallback chain między providerami

---

## 8. Estymacja kosztów API

### Claude (claude-3-haiku)
- Input: ~$0.25 / 1M tokens
- Output: ~$1.25 / 1M tokens
- Średnia transakcja: ~100 tokens input, ~50 tokens output
- **Koszt per transakcja: ~$0.0001 (0.01 gr)**
- **1000 transakcji: ~$0.10 (40 gr)**

### OpenAI (gpt-4o-mini)
- Input: ~$0.15 / 1M tokens
- Output: ~$0.60 / 1M tokens
- **Koszt per transakcja: ~$0.00005**
- **1000 transakcji: ~$0.05 (20 gr)**

### Ollama (lokalne)
- Koszt API: $0
- Koszt infrastruktury: zależny od sprzętu

---

## 9. Metryki i monitoring

### Metryki do śledzenia
- `ai_categorization_requests_total` - liczba requestów do AI
- `ai_categorization_latency_seconds` - czas odpowiedzi
- `ai_categorization_confidence_histogram` - rozkład confidence
- `ai_categorization_acceptance_rate` - % zaakceptowanych sugestii
- `ai_categorization_errors_total` - błędy API

### Logi
- INFO: Batch wysłany, odpowiedź otrzymana
- WARN: Niski confidence, fallback
- ERROR: Błąd API, timeout

---

## 10. Testy

### Testy jednostkowe
- `CategorizationPromptBuilderTest` - budowanie promptów
- `AiCategorizationServiceTest` - logika serwisu (z mock provider)

### Testy integracyjne
- `ClaudeLlmProviderIntegrationTest` - prawdziwe API (opcjonalnie, CI skip)
- `StageTransactionsWithAiTest` - pełny flow stagingu z AI

### Testy E2E
- Import transakcji → AI kategoryzacja → akceptacja → weryfikacja mappingu

---

## 11. Bezpieczeństwo

### Dane wysyłane do AI
- Tylko: nazwa, opis, kwota, data transakcji
- NIE wysyłamy: numery kont, dane osobowe, pełne numery kart

### API Keys
- Przechowywane w zmiennych środowiskowych
- Nigdy w kodzie ani w repozytorium

### Rate limiting
- Ochrona przed nadmiernym zużyciem API
- Configurable per provider

---

## 12. Fallback strategy

```
1. Sprawdź CategoryMapping
   └── Znaleziono → użyj mapowania
   └── Nie znaleziono → idź do 2

2. Sprawdź czy AI jest włączone
   └── Nie → idź do 4
   └── Tak → idź do 3

3. Wywołaj AI Provider
   └── Sukces + confidence >= threshold → użyj sugestii AI
   └── Sukces + confidence < threshold → idź do 4
   └── Błąd/timeout → idź do 4

4. Fallback: MAP_TO_UNCATEGORIZED
   └── Oznacz jako "Uncategorized"
   └── Ustaw suggestionSource = FALLBACK
```
