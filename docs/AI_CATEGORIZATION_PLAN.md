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

---

## 13. Szczegółowa analiza kosztów API

### 13.1 Co to jest token?

Token to jednostka tekstu używana przez modele AI:
- **Angielski**: ~1 token ≈ 4 znaki
- **Polski**: ~1 token ≈ 2-3 znaki (dłuższe słowa, znaki diakrytyczne)

**Przykład transakcji (~30-40 tokenów input):**
```
Nazwa: "ŻABKA Z5432 WARSZAWA UL. MARSZAŁKOWSKA"
Kwota: -45.99 PLN
Data: 2024-01-15
Typ: OUTFLOW
```

### 13.2 Batch vs pojedyncze requesty - KRYTYCZNA RÓŻNICA

#### Scenariusz A: 100 osobnych requestów (1 transakcja = 1 request)

```
Request 1:
  - System prompt: ~500 tokenów (lista kategorii, instrukcje)
  - User prompt: ~50 tokenów (1 transakcja)
  - Output: ~30 tokenów (1 sugestia)

  RAZEM: 580 tokenów × 100 requestów = 58,000 tokenów
```

**Problem:** System prompt powtarza się 100 razy!

#### Scenariusz B: 1 request z 100 transakcjami (batch)

```
Request 1:
  - System prompt: ~500 tokenów (raz!)
  - User prompt: ~5,000 tokenów (100 transakcji × 50)
  - Output: ~3,000 tokenów (100 sugestii × 30)

  RAZEM: 8,500 tokenów
```

**Oszczędność: ~7x taniej przy batch processing!**

### 13.3 Tabela kosztów dla 100 transakcji

#### Claude (Anthropic)

| Model | Input (/1M) | Output (/1M) | 100 trans. (batch) | 100 trans. (osobno) |
|-------|-------------|--------------|---------------------|---------------------|
| **Claude 3.5 Haiku** | $0.80 | $4.00 | **~$0.02** (8 gr) | ~$0.07 (28 gr) |
| **Claude 3.5 Sonnet** | $3.00 | $15.00 | **~$0.06** (24 gr) | ~$0.25 (1 zł) |
| **Claude Opus 4** | $15.00 | $75.00 | **~$0.30** (1.20 zł) | ~$1.20 (4.80 zł) |

#### OpenAI

| Model | Input (/1M) | Output (/1M) | 100 trans. (batch) | 100 trans. (osobno) |
|-------|-------------|--------------|---------------------|---------------------|
| **GPT-4o-mini** | $0.15 | $0.60 | **~$0.003** (1.2 gr) | ~$0.01 (4 gr) |
| **GPT-4o** | $2.50 | $10.00 | **~$0.04** (16 gr) | ~$0.18 (72 gr) |
| **GPT-4 Turbo** | $10.00 | $30.00 | **~$0.12** (48 gr) | ~$0.50 (2 zł) |

#### Ollama (lokalne)

| Model | Koszt API | 100 transakcji |
|-------|-----------|----------------|
| **Llama 3.1 8B** | $0 | **$0** (tylko prąd/sprzęt) |
| **Mistral 7B** | $0 | **$0** |

### 13.4 Przykładowe scenariusze użytkownika (miesięcznie)

#### Użytkownik "Oszczędny" - 50 transakcji/miesiąc

| Model | Koszt/miesiąc | Koszt/rok |
|-------|---------------|-----------|
| GPT-4o-mini | ~2 gr | ~24 gr |
| Claude Haiku | ~4 gr | ~48 gr |
| Ollama | 0 zł | 0 zł |

#### Użytkownik "Przeciętny" - 200 transakcji/miesiąc

| Model | Koszt/miesiąc | Koszt/rok |
|-------|---------------|-----------|
| GPT-4o-mini | ~6 gr | ~72 gr |
| Claude Haiku | ~16 gr | ~1.92 zł |
| Ollama | 0 zł | 0 zł |

#### Użytkownik "Aktywny" - 500 transakcji/miesiąc

| Model | Koszt/miesiąc | Koszt/rok |
|-------|---------------|-----------|
| GPT-4o-mini | ~15 gr | ~1.80 zł |
| Claude Haiku | ~40 gr | ~4.80 zł |
| Claude Sonnet | ~1.50 zł | ~18 zł |

### 13.5 Ukryte koszty małych requestów

#### Rate limiting
- OpenAI: 500-10,000 requests/minutę (zależnie od tier)
- Claude: 50-4,000 requests/minutę
- **100 osobnych requestów może uderzyć w limity**

#### Latencja
- 1 request: ~500ms-2s
- 100 osobnych requestów: **50-200 sekund** (sekwencyjnie) lub 2-5s (równolegle, ale ryzyko rate limit)
- 1 batch request: **2-5 sekund**

#### Overhead HTTP
- Każdy request to narzut: DNS, TCP handshake, TLS, headers
- 100 requestów = 100× overhead

### 13.6 Optymalna strategia kosztowa

```
Nowe transakcje (100)
       ↓
[Filtruj przez istniejące CategoryMapping]
       ↓
90 transakcji MA mapping → użyj go (koszt: 0 zł)
10 transakcji BEZ mappingu → wyślij do AI
       ↓
1 batch request (10 transakcji)
       ↓
Koszt: ~0.3 gr (GPT-4o-mini)
       ↓
Zapisz nowe mappingi → następnym razem koszt = 0
```

**Efekt uczenia się:** Po kilku miesiącach używania, 95%+ transakcji będzie miało mapping i AI będzie używane tylko dla nowych sklepów/usług.

### 13.7 Rekomendacja wyboru modelu

| Przypadek użycia | Rekomendowany model | Powód |
|------------------|---------------------|-------|
| **Minimalne koszty** | GPT-4o-mini | Najtańszy, wystarczający |
| **Lepsza jakość po polsku** | Claude 3.5 Haiku | Lepsze rozumienie polskiego kontekstu |
| **Maksymalna prywatność** | Ollama (Llama 3.1) | Dane nie opuszczają serwera |
| **Najlepsza jakość** | Claude 3.5 Sonnet | Najlepsze wyniki, droższy |

### 13.8 Podsumowanie kosztów

| Pytanie | Odpowiedź |
|---------|-----------|
| Czy małe requesty się sumują? | **Tak, i są ~7x droższe** (system prompt powtarzany) |
| Ile kosztuje 100 transakcji? | **1-40 gr** (zależnie od modelu i strategii batch) |
| Najlepszy stosunek cena/jakość? | **GPT-4o-mini** (najtańszy) lub **Claude Haiku** (lepszy po polsku) |
| Jak zoptymalizować koszty? | **Batch processing + caching mappingów** |
| Czy warto używać lokalnych modeli? | **Tak, dla prywatności i 0 kosztów API** (wymaga GPU/mocnego CPU) |
