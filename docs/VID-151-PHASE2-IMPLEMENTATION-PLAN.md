# VID-151: Faza 2 - Plan implementacji zachowania intencji AI

## Cel Fazy 2

Zachowanie oryginalnej intencji AI dotyczącej hierarchii kategorii, aby przyszłe importy mogły tworzyć spójne struktury kategorii.

## Analiza ryzyk

### 1. Migracja danych - NISKIE RYZYKO ✅

```
Istniejące PatternMapping w MongoDB:
┌─────────────────────────────────────────┐
│ pattern: "ZABKA"                        │
│ suggestedCategory: "Zakupy spożywcze"   │
│ // brak intendedParentCategory          │
└─────────────────────────────────────────┘

Po dodaniu pola (nullable):
┌─────────────────────────────────────────┐
│ pattern: "ZABKA"                        │
│ suggestedCategory: "Zakupy spożywcze"   │
│ intendedParentCategory: null            │  ← MongoDB doda jako null
└─────────────────────────────────────────┘

✅ BEZPIECZNE: MongoDB jest schema-less, nowe pole = null dla starych dokumentów
✅ Kod musi obsługiwać null (sprawdzać przed użyciem)
```

**Mitygacja:** Pole nullable, MongoDB schema-less - nie wymaga migracji.

---

### 2. Spójność cache vs rzeczywista struktura - ŚREDNIE RYZYKO ⚠️

```
Import 1:
- AI sugeruje: Żywność → Zakupy spożywcze
- Cache: ZABKA → Zakupy spożywcze (intendedParent: "Żywność")

Użytkownik RĘCZNIE reorganizuje:
- Usuwa "Żywność", zostawia "Zakupy spożywcze" jako root
- LUB przenosi "Zakupy spożywcze" pod "Wydatki codzienne"

Import 2:
- Cache mówi: intendedParent = "Żywność"
- Ale "Żywność" nie istnieje LUB struktura jest inna!

⚠️ PROBLEM: AI może zaproponować przywrócenie "Żywność"
   mimo że użytkownik celowo to zmienił!
```

**Mitygacja:**
1. AI widzi EXISTING CATEGORIES - priorytet nad cache
2. `structureOptimizations` to SUGESTIE - użytkownik akceptuje/odrzuca
3. Dodać w prompcie: "Respect user's current structure, only suggest optimizations if they make logical sense"

---

### 3. Złożoność promptu - ŚREDNIE RYZYKO ⚠️

```
Obecny prompt:
- System prompt: ~1500 znaków
- User prompt: ~3000-5000 znaków (zależy od ilości wzorców)

Z Fazą 2:
- System prompt: ~2000 znaków (+500)
- User prompt: ~4000-7000 znaków (+sekcja CACHED PATTERN INTENTS)

⚠️ RYZYKO:
- Więcej instrukcji = większa szansa że AI pominie coś
- Dłuższy prompt = wyższy koszt (tokens)
```

**Mitygacja:**
1. Sekcja CACHED PATTERN INTENTS tylko gdy są dane (nie zawsze)
2. Grupować intencje per parent (nie listować każdy pattern osobno)
3. Limit: max 10 intendedParents w prompcie

---

### 4. AI może ignorować intencje - NISKIE RYZYKO ✅

```
Scenariusz:
- Cache: ZABKA → Zakupy spożywcze (intendedParent: "Żywność")
- AI ignoruje i proponuje inną strukturę

✅ TO NIE JEST PROBLEM:
- structureOptimizations to sugestie, nie wymóg
- Użytkownik i tak akceptuje/modyfikuje propozycje AI
- Worst case: działa jak bez Fazy 2 (obecne zachowanie)
```

**Mitygacja:** Brak - akceptowalne zachowanie.

---

### 5. Parsowanie odpowiedzi AI - ŚREDNIE RYZYKO ⚠️

```
AI może zwrócić:

a) Poprawnie:
   "structureOptimizations": [{"action": "MOVE_TO_PARENT", ...}]

b) Puste (OK):
   "structureOptimizations": []

c) Brak pola (OK - opcjonalne):
   { "categoryStructure": ..., "patternMappings": ... }

d) Błędny format (PROBLEM):
   "structureOptimizations": "MOVE Zakupy spożywcze to Żywność"
```

**Mitygacja:**
1. Pole opcjonalne - brak = pusta lista
2. Walidacja w parserze - ignoruj błędne wpisy
3. Log warning przy błędnym formacie

---

### 6. Wykonanie structureOptimizations - ŚREDNIE RYZYKO ⚠️

```
Opcja A: Automatycznie w AcceptAiSuggestionsCommandHandler
─────────────────────────────────────────────────────────
⚠️ RYZYKO: Użytkownik nie wie że kategoria została przeniesiona
⚠️ RYZYKO: Może przenieść kategorię z transakcjami bez zgody


Opcja B: Zwrócić jako sugestie do UI (REKOMENDOWANE)
────────────────────────────────────────────────────
✅ UI pokazuje: "AI sugeruje przeniesienie 'Zakupy spożywcze' pod 'Żywność'"
✅ Użytkownik akceptuje lub odrzuca
✅ Pełna kontrola użytkownika


Opcja C: Osobny endpoint do wykonania optymalizacji
───────────────────────────────────────────────────
✅ POST /api/v1/bank-data-ingestion/cf={id}/apply-optimizations
✅ Użytkownik świadomie wywołuje
```

**Mitygacja:** Implementować Opcję B - zwrócić do UI jako sugestie.

---

### 7. Cykliczne zależności intencji - NISKIE RYZYKO ✅

```
Cache:
- ZABKA → Zakupy spożywcze (intendedParent: "Żywność")
- MCDONALDS → Restauracje (intendedParent: "Jedzenie poza domem")

AI może nie wiedzieć że "Żywność" i "Jedzenie poza domem" to podobne koncepty.

✅ TO NIE JEST PROBLEM:
- AI widzi całość i może zaproponować spójną strukturę
- Intencje to hints, nie twarde reguły
```

**Mitygacja:** Brak - AI rozwiązuje naturalnie.

---

## Podsumowanie ryzyk

| Zagrożenie | Ryzyko | Mitygacja |
|------------|--------|-----------|
| Migracja danych | ✅ Niskie | Pole nullable, MongoDB schema-less |
| Cache vs rzeczywista struktura | ⚠️ Średnie | AI priorytetyzuje EXISTING CATEGORIES |
| Złożoność promptu | ⚠️ Średnie | Sekcja tylko gdy są dane, limity |
| AI ignoruje intencje | ✅ Niskie | Sugestie, nie wymóg |
| Parsowanie odpowiedzi | ⚠️ Średnie | Pole opcjonalne, walidacja |
| Wykonanie optimizations | ⚠️ Średnie | Zwrócić do UI jako sugestie |
| Cykliczne zależności | ✅ Niskie | AI widzi całość |

---

## Plan implementacji - krok po kroku

### KROK 1: Model danych - PatternMapping

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/domain/PatternMapping.java`

**Zmiany:**
- Dodać pole `String intendedParentCategory` (nullable)
- Zaktualizować factory methods (`createUser`, `createAi`, `createGlobal`)
- Dodać metodę `withIntendedParentCategory()`

**Testy:** Brak - to POJO/record

---

### KROK 2: Entity - PatternMappingEntity

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/infrastructure/entity/PatternMappingEntity.java`

**Zmiany:**
- Dodać pole `private String intendedParentCategory`
- Zaktualizować `fromDomain()` i `toDomain()`

**Testy:** Brak - entity mapping

---

### KROK 3: Zapis intencji do cache

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/commands/accept_ai_suggestions/AcceptAiSuggestionsCommandHandler.java`

**Zmiany:**
- Przy tworzeniu `PatternMapping` przekazywać `parentCategory` jako `intendedParentCategory`

**Testy:** `AcceptAiSuggestionsCommandHandlerTest` - zweryfikować że intencja jest zapisywana

---

### KROK 4: Nowy typ StructureOptimization

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationResult.java`

**Zmiany:**
- Dodać nowy record `StructureOptimization`
- Dodać pole `List<StructureOptimization> structureOptimizations` do `AiCategorizationResult`

```java
public record StructureOptimization(
    OptimizationAction action,
    String categoryName,
    String newParent,
    String reason
) {
    public enum OptimizationAction {
        MOVE_TO_PARENT,
        CREATE_PARENT
    }
}
```

**Testy:** Brak - to POJO/record

---

### KROK 5: Parsowanie structureOptimizations

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationResponseParser.java`

**Zmiany:**
- Parsować opcjonalne pole `structureOptimizations` z JSON
- Walidować format, ignorować błędne wpisy
- Zwracać pustą listę gdy brak pola

**Testy:** `AiCategorizationResponseParserTest` - dodać testy dla nowego pola

---

### KROK 6: Pobieranie intencji z cache

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationService.java`

**Zmiany:**
- Dodać metodę `getPatternIntentsForPrompt(CashFlowId)`
- Grupować PatternMappings po `intendedParentCategory`
- Limit: max 10 różnych intendedParents

**Testy:** `AiCategorizationServiceTest` - test pobierania intencji

---

### KROK 7: Rozszerzenie promptu - System Prompt

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationPromptBuilder.java`

**Zmiany w `getSystemPrompt()`:**
- Dodać guideline #9 o HIERARCHY CONSISTENCY FROM CACHE

```
9. HIERARCHY CONSISTENCY FROM CACHE: When you see CACHED PATTERN INTENTS section,
   it means previous AI categorization intended certain categories to be under
   a parent. Use this information to maintain consistent hierarchy across imports:
   - If intended parent doesn't exist but NOW makes sense (2+ children) → CREATE IT
   - If only 1 child would be under parent → keep flat (current behavior)
   - If 2+ children would be under parent → create hierarchy and suggest moving existing categories
   - RESPECT user's current structure - only suggest if it makes logical sense
```

**Testy:** `AiCategorizationPromptBuilderTest` - zweryfikować treść promptu

---

### KROK 8: Rozszerzenie promptu - User Prompt

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationPromptBuilder.java`

**Zmiany w `buildUserPrompt()`:**

A) Dodać nowy parametr `Map<String, List<PatternMapping>> patternIntents`

B) Dodać sekcję CACHED PATTERN INTENTS (tylko gdy są dane):
```
CACHED PATTERN INTENTS (hints for hierarchy consistency):
  Intended parent: "Żywność"
    - ZABKA → Zakupy spożywcze
    - BIEDRONKA → Zakupy spożywcze
  Intended parent: "Transport"
    - ORLEN → Paliwo
```

C) Dodać CRITICAL RULES #11, #12:
```
11. STRUCTURE OPTIMIZATIONS: When creating a parent category that should include
    existing root-level categories (based on CACHED PATTERN INTENTS), add them
    to "structureOptimizations" array with action "MOVE_TO_PARENT".

12. RESTORING HIERARCHY: If CACHED PATTERN INTENTS shows patterns with
    intendedParent "X" and you're creating new categories that fit under "X",
    suggest moving existing categories under "X" too.
```

D) Rozszerzyć przykład JSON o `structureOptimizations`

**Testy:** `AiCategorizationPromptBuilderTest` - test z intencjami i bez

---

### KROK 9: Integracja w AiCategorizationService

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationService.java`

**Zmiany:**
- Pobrać intencje z cache przed budowaniem promptu
- Przekazać do `buildUserPrompt()`
- Dodać `structureOptimizations` do wyniku

**Testy:** `AiCategorizationServiceTest` - test pełnego flow

---

### KROK 10: Zwrócenie structureOptimizations w API

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/StagingSessionDto.java` (lub odpowiedni DTO)

**Zmiany:**
- Dodać pole `List<StructureOptimizationDto> structureOptimizations` do odpowiedzi API
- UI może wyświetlić sugestie użytkownikowi

**Testy:** Test integracyjny - sprawdzić że API zwraca sugestie

---

### KROK 11: Testy integracyjne

**Plik:** `src/test/java/com/multi/vidulum/bank_data_ingestion/AiCategorizationPhase2IntegrationTest.java` (nowy)

**Scenariusze:**
1. Import 1 → spłaszczenie → cache z intencją
2. Import 2 → AI widzi intencje → proponuje strukturę
3. Import z pustym cache → brak sekcji CACHED PATTERN INTENTS
4. Import po ręcznej reorganizacji → AI respektuje istniejącą strukturę

---

## Kolejność implementacji

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         KOLEJNOŚĆ KROKÓW                                        │
└─────────────────────────────────────────────────────────────────────────────────┘

    WARSTWA DANYCH (bez wpływu na resztę systemu):
    ══════════════════════════════════════════════
    KROK 1: PatternMapping.java           ← dodaj pole
    KROK 2: PatternMappingEntity.java     ← dodaj pole + mapping

    NOWY TYP (bez wpływu na resztę):
    ═════════════════════════════════
    KROK 4: StructureOptimization record  ← nowy typ

    PARSOWANIE (opcjonalne pole):
    ═════════════════════════════
    KROK 5: AiCategorizationResponseParser ← parsuj nowe pole

    ZAPIS INTENCJI:
    ═══════════════
    KROK 3: AcceptAiSuggestionsCommandHandler ← zapisuj intencję

    PROMPT (główna logika):
    ═══════════════════════
    KROK 6: AiCategorizationService       ← pobieranie intencji
    KROK 7: AiCategorizationPromptBuilder ← system prompt
    KROK 8: AiCategorizationPromptBuilder ← user prompt

    INTEGRACJA:
    ═══════════
    KROK 9: AiCategorizationService       ← pełny flow
    KROK 10: DTO/API                      ← zwracanie sugestii

    TESTY:
    ══════
    KROK 11: Testy integracyjne
```

---

## Checklisty per krok

### KROK 1: PatternMapping
- [ ] Dodać pole `String intendedParentCategory`
- [ ] Zaktualizować `createUser()`
- [ ] Zaktualizować `createAi()`
- [ ] Zaktualizować `createGlobal()`
- [ ] Dodać `withIntendedParentCategory()`
- [ ] Kompilacja przechodzi

### KROK 2: PatternMappingEntity
- [ ] Dodać pole `private String intendedParentCategory`
- [ ] Zaktualizować `fromDomain()`
- [ ] Zaktualizować `toDomain()`
- [ ] Kompilacja przechodzi

### KROK 3: AcceptAiSuggestionsCommandHandler
- [ ] Przekazywać `parentCategory` jako `intendedParentCategory`
- [ ] Test: zapisana intencja w MongoDB

### KROK 4: StructureOptimization
- [ ] Utworzyć record `StructureOptimization`
- [ ] Enum `OptimizationAction`
- [ ] Dodać do `AiCategorizationResult`

### KROK 5: AiCategorizationResponseParser
- [ ] Parsować `structureOptimizations` z JSON
- [ ] Obsługa brakującego pola (pusta lista)
- [ ] Walidacja formatu
- [ ] Testy jednostkowe

### KROK 6: Pobieranie intencji
- [ ] Metoda `getPatternIntentsForPrompt()`
- [ ] Grupowanie po `intendedParentCategory`
- [ ] Limit 10 parents
- [ ] Testy

### KROK 7: System Prompt
- [ ] Dodać guideline #9
- [ ] Test treści promptu

### KROK 8: User Prompt
- [ ] Nowy parametr `patternIntents`
- [ ] Sekcja CACHED PATTERN INTENTS
- [ ] CRITICAL RULES #11, #12
- [ ] Rozszerzony przykład JSON
- [ ] Testy

### KROK 9: Integracja AiCategorizationService
- [ ] Pobranie intencji
- [ ] Przekazanie do prompt buildera
- [ ] Dodanie do wyniku

### KROK 10: API/DTO
- [ ] Pole w response DTO
- [ ] Test endpoint

### KROK 11: Testy integracyjne
- [ ] Scenariusz: Import 1 → Import 2
- [ ] Scenariusz: Pusty cache
- [ ] Scenariusz: Po ręcznej reorganizacji

---

## Szacowany zakres zmian

| Komponent | Pliki | LOC (szacunkowo) |
|-----------|-------|------------------|
| Model danych | 2 | ~30 |
| Nowy typ | 1 | ~20 |
| Parser | 1 | ~50 |
| Command handler | 1 | ~10 |
| Prompt builder | 1 | ~80 |
| Service | 1 | ~40 |
| DTO | 1 | ~15 |
| Testy | 3-4 | ~200 |
| **RAZEM** | **11-12** | **~445** |

---

## SZCZEGÓŁOWE ZMIANY W KODZIE

### KROK 1: PatternMapping.java

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/domain/PatternMapping.java`

**PRZED:**
```java
public record PatternMapping(
        PatternMappingId id,
        String normalizedPattern,
        String suggestedCategory,
        Type categoryType,
        PatternSource source,
        String userId,
        String cashFlowId,
        int usageCount,
        double confidenceScore,
        Instant createdAt,
        Instant lastUsedAt
) {
```

**PO:**
```java
public record PatternMapping(
        PatternMappingId id,
        String normalizedPattern,
        String suggestedCategory,
        String intendedParentCategory,  // ← NOWE POLE (nullable)
        Type categoryType,
        PatternSource source,
        String userId,
        String cashFlowId,
        int usageCount,
        double confidenceScore,
        Instant createdAt,
        Instant lastUsedAt
) {
```

**Zmiany w factory methods:**

```java
// createGlobal - dodać parametr intendedParentCategory
public static PatternMapping createGlobal(
        String normalizedPattern,
        String suggestedCategory,
        String intendedParentCategory,  // ← NOWY
        Type categoryType,
        double confidenceScore
) {
    return new PatternMapping(
            PatternMappingId.generate(),
            normalizedPattern.toUpperCase().trim(),
            suggestedCategory,
            intendedParentCategory,  // ← NOWY
            categoryType,
            PatternSource.GLOBAL,
            null,
            null,
            0,
            confidenceScore,
            Instant.now(),
            Instant.now()
    );
}

// createUser - dodać parametr intendedParentCategory
public static PatternMapping createUser(
        String normalizedPattern,
        String suggestedCategory,
        String intendedParentCategory,  // ← NOWY
        Type categoryType,
        String userId,
        String cashFlowId,
        double confidenceScore
) {
    return new PatternMapping(
            PatternMappingId.generate(),
            normalizedPattern.toUpperCase().trim(),
            suggestedCategory,
            intendedParentCategory,  // ← NOWY
            categoryType,
            PatternSource.USER,
            userId,
            cashFlowId,
            1,
            confidenceScore,
            Instant.now(),
            Instant.now()
    );
}

// createAi - dodać parametr intendedParentCategory
public static PatternMapping createAi(
        String normalizedPattern,
        String suggestedCategory,
        String intendedParentCategory,  // ← NOWY
        Type categoryType,
        String userId,
        String cashFlowId,
        double confidenceScore
) {
    return new PatternMapping(
            PatternMappingId.generate(),
            normalizedPattern.toUpperCase().trim(),
            suggestedCategory,
            intendedParentCategory,  // ← NOWY
            categoryType,
            PatternSource.AI,
            userId,
            cashFlowId,
            0,
            confidenceScore,
            Instant.now(),
            Instant.now()
    );
}
```

**Zaktualizować metody `recordUsage()` i `updateCategory()`** - dodać `intendedParentCategory` do nowego recordu.

**Usunąć komentarz (linia 21-22):**
```java
// USUNĄĆ:
// Note: Parent category is NOT stored - it's looked up dynamically from CashFlow
// to avoid desynchronization when user moves categories.
```

**Dodać nowy komentarz:**
```java
/**
 * Intended parent category from AI suggestion.
 * This is a HINT for future AI calls - not a hard reference.
 * The actual parent is looked up dynamically from CashFlow to handle user reorganizations.
 *
 * Nullable - old mappings and mappings for root categories have null.
 */
```

---

### KROK 2: PatternMappingEntity.java

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/infrastructure/entity/PatternMappingEntity.java`

**Dodać pole:**
```java
private String intendedParentCategory;  // ← NOWE POLE
```

**Zaktualizować `fromDomain()`:**
```java
public static PatternMappingEntity fromDomain(PatternMapping domain) {
    return new PatternMappingEntity(
            domain.id().id(),
            domain.normalizedPattern(),
            domain.suggestedCategory(),
            domain.intendedParentCategory(),  // ← NOWY
            domain.categoryType(),
            domain.source(),
            domain.userId(),
            domain.cashFlowId(),
            domain.usageCount(),
            domain.confidenceScore(),
            domain.createdAt(),
            domain.lastUsedAt()
    );
}
```

**Zaktualizować `toDomain()`:**
```java
public PatternMapping toDomain() {
    return new PatternMapping(
            PatternMappingId.of(id),
            normalizedPattern,
            suggestedCategory,
            intendedParentCategory,  // ← NOWY
            categoryType,
            source,
            userId,
            cashFlowId,
            usageCount,
            confidenceScore,
            createdAt,
            lastUsedAt
    );
}
```

**Zaktualizować `@AllArgsConstructor`** - kolejność pól musi się zgadzać.

---

### KROK 3: AcceptAiSuggestionsCommand.java

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/commands/accept_ai_suggestions/AcceptAiSuggestionsCommand.java`

**Dodać pole do `MappingToApply`:**
```java
public record MappingToApply(
        String pattern,
        String bankCategory,
        String targetCategory,
        String parentCategory,  // ← NOWE POLE (dla intendedParentCategory)
        Type type,
        int confidence
) {}
```

---

### KROK 4: AcceptAiSuggestionsCommandHandler.java

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/commands/accept_ai_suggestions/AcceptAiSuggestionsCommandHandler.java`

**Zmienić linię 165-172 (Step 3: Save to pattern cache):**

**PRZED:**
```java
PatternMapping patternMapping = PatternMapping.createUser(
        mapping.pattern().toUpperCase(),
        mapping.targetCategory(),
        mapping.type(),
        command.userId(),
        cashFlowIdStr,
        (double) mapping.confidence() / 100.0
);
```

**PO:**
```java
PatternMapping patternMapping = PatternMapping.createUser(
        mapping.pattern().toUpperCase(),
        mapping.targetCategory(),
        mapping.parentCategory(),  // ← NOWY parametr - zapisujemy intencję AI
        mapping.type(),
        command.userId(),
        cashFlowIdStr,
        (double) mapping.confidence() / 100.0
);
```

---

### KROK 5: AiCategorizationResult.java - StructureOptimization

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/domain/AiCategorizationResult.java`

**Dodać nowy record po `AiCost`:**
```java
/**
 * A suggested structure optimization.
 * These are hints to reorganize existing categories for better consistency.
 * User must explicitly accept these - they are NOT auto-applied.
 */
public record StructureOptimization(
        OptimizationAction action,
        String categoryName,
        String newParent,
        Type categoryType,
        String reason
) {
    public enum OptimizationAction {
        /** Move existing root category under a new/existing parent */
        MOVE_TO_PARENT,
        /** Create a new parent category and move children under it */
        CREATE_PARENT
    }
}
```

**Dodać pole do głównego recordu `AiCategorizationResult`:**
```java
public record AiCategorizationResult(
        StagingSessionId sessionId,
        String status,
        SuggestedStructure suggestedStructure,
        List<PatternSuggestion> patternSuggestions,
        List<BankCategorySuggestion> bankCategorySuggestions,
        List<UnrecognizedPattern> unrecognizedPatterns,
        List<StructureOptimization> structureOptimizations,  // ← NOWE
        CategorizationStats stats,
        AiCost cost
) {
```

**Zaktualizować factory methods `success()`, `noPatterns()`, `error()`** - dodać `List.of()` dla `structureOptimizations`.

---

### KROK 6: AiCategorizationResponseParser.java

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationResponseParser.java`

**A) Dodać DTO dla structureOptimizations:**
```java
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public static class StructureOptimizationDto {
    private String action;       // "MOVE_TO_PARENT" or "CREATE_PARENT"
    private String categoryName;
    private String newParent;
    private String categoryType; // "INFLOW" or "OUTFLOW"
    private String reason;
}
```

**B) Dodać pole do AiResponseDto:**
```java
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public static class AiResponseDto {
    private CategoryStructureDto categoryStructure;
    private List<PatternMappingDto> patternMappings;
    private List<BankCategoryMappingDto> bankCategoryMappings;
    private List<UnrecognizedPatternDto> unrecognizedPatterns;
    private List<StructureOptimizationDto> structureOptimizations;  // ← NOWE
}
```

**C) Dodać metodę konwersji:**
```java
private List<AiCategorizationResult.StructureOptimization> convertStructureOptimizations(
        List<StructureOptimizationDto> optimizations) {

    if (optimizations == null || optimizations.isEmpty()) {
        return List.of();
    }

    List<AiCategorizationResult.StructureOptimization> result = new ArrayList<>();

    for (StructureOptimizationDto dto : optimizations) {
        try {
            AiCategorizationResult.StructureOptimization.OptimizationAction action =
                    AiCategorizationResult.StructureOptimization.OptimizationAction.valueOf(dto.action);
            Type type = Type.valueOf(dto.categoryType);

            result.add(new AiCategorizationResult.StructureOptimization(
                    action,
                    dto.categoryName,
                    dto.newParent,
                    type,
                    dto.reason
            ));
        } catch (Exception e) {
            log.warn("Skipping invalid structure optimization: {}", dto, e);
        }
    }

    return result;
}
```

**D) Zaktualizować metodę `parse()`** - dodać wywołanie `convertStructureOptimizations()`.

**E) Zaktualizować `ParseResult`:**
```java
public record ParseResult(
        boolean success,
        AiCategorizationResult.SuggestedStructure structure,
        List<AiCategorizationResult.PatternSuggestion> suggestions,
        List<AiCategorizationResult.BankCategorySuggestion> bankCategorySuggestions,
        List<AiCategorizationResult.UnrecognizedPattern> unrecognizedPatterns,
        List<AiCategorizationResult.StructureOptimization> structureOptimizations,  // ← NOWE
        String errorMessage
) {
```

---

### KROK 7: AiCategorizationPromptBuilder.java - getSystemPrompt()

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationPromptBuilder.java`

**Dodać do Guidelines (po punkcie 8):**
```java
9. HIERARCHY CONSISTENCY FROM CACHE: When you see CACHED PATTERN INTENTS section,
   it shows what parent categories were intended for patterns in previous imports.
   Use this to maintain consistent hierarchy:
   - If intended parent doesn't exist but NOW makes sense (2+ children would be under it) → suggest creating it
   - If only 1 child would be under parent → keep flat (current behavior)
   - If 2+ children would benefit from grouping under intended parent → suggest moving existing categories
   - RESPECT user's current structure - only suggest if it makes logical sense
```

---

### KROK 8: AiCategorizationPromptBuilder.java - buildUserPrompt()

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationPromptBuilder.java`

**A) Zmienić sygnaturę metody:**
```java
public String buildUserPrompt(
        List<PatternDeduplicator.PatternGroup> patternGroups,
        ExistingCategoryStructure categoryStructure,
        Map<String, List<PatternIntentInfo>> patternIntentsByParent  // ← NOWY parametr
) {
```

**B) Dodać nowy record:**
```java
/**
 * Info about pattern intent from cache.
 */
public record PatternIntentInfo(
        String pattern,
        String suggestedCategory,
        String intendedParentCategory
) {}
```

**C) Dodać sekcję CACHED PATTERN INTENTS (po EXISTING CATEGORIES):**
```java
// Add cached pattern intents (if any)
if (patternIntentsByParent != null && !patternIntentsByParent.isEmpty()) {
    sb.append("CACHED PATTERN INTENTS (hints from previous imports for hierarchy consistency):\n");

    int parentCount = 0;
    for (var entry : patternIntentsByParent.entrySet()) {
        if (parentCount >= 10) {
            sb.append("  ... (").append(patternIntentsByParent.size() - 10).append(" more intended parents)\n");
            break;
        }

        String intendedParent = entry.getKey();
        List<PatternIntentInfo> patterns = entry.getValue();

        sb.append("  Intended parent: \"").append(intendedParent).append("\"\n");
        for (PatternIntentInfo info : patterns) {
            sb.append("    - ").append(info.pattern())
              .append(" → ").append(info.suggestedCategory()).append("\n");
        }
        parentCount++;
    }
    sb.append("\n");
}
```

**D) Dodać CRITICAL RULES #11, #12:**
```java
11. STRUCTURE OPTIMIZATIONS: When you see CACHED PATTERN INTENTS with an intended parent
    that doesn't exist as a category, but NOW there are 2+ categories that would fit under it,
    suggest creating the parent and moving categories. Add to "structureOptimizations" array.

12. MOVE_TO_PARENT OPTIMIZATION: If a root-level category matches an intended parent from cache
    and there are other categories that should also be under that parent, suggest restructuring.
    Only suggest if it improves logical grouping.
```

**E) Rozszerzyć przykład JSON response:**
```java
"structureOptimizations": [
  {
    "action": "MOVE_TO_PARENT",
    "categoryName": "Zakupy spożywcze",
    "newParent": "Żywność",
    "categoryType": "OUTFLOW",
    "reason": "Restoring intended hierarchy from previous import - groups related food categories"
  }
]
```

---

### KROK 9: AiCategorizationService.java

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationService.java`

**A) Dodać metodę do pobierania intencji z cache:**
```java
/**
 * Gets pattern intents grouped by intended parent for use in AI prompt.
 * Only returns patterns that have intendedParentCategory set.
 */
private Map<String, List<AiCategorizationPromptBuilder.PatternIntentInfo>> getPatternIntentsForPrompt(
        CashFlowId cashFlowId) {

    List<PatternMapping> allMappings = patternMappingRepository
            .findByCashFlowId(cashFlowId.id());

    return allMappings.stream()
            .filter(pm -> pm.intendedParentCategory() != null && !pm.intendedParentCategory().isBlank())
            .map(pm -> new AiCategorizationPromptBuilder.PatternIntentInfo(
                    pm.normalizedPattern(),
                    pm.suggestedCategory(),
                    pm.intendedParentCategory()
            ))
            .collect(Collectors.groupingBy(
                    AiCategorizationPromptBuilder.PatternIntentInfo::intendedParentCategory
            ));
}
```

**B) Zaktualizować wywołanie `buildUserPrompt()`:**
```java
// Pobierz intencje z cache
Map<String, List<AiCategorizationPromptBuilder.PatternIntentInfo>> patternIntents =
        getPatternIntentsForPrompt(cashFlowId);

// Zbuduj prompt z intencjami
String userPrompt = promptBuilder.buildUserPrompt(
        patternGroups,
        existingCategories,
        patternIntents  // ← NOWY parametr
);
```

---

## TEST CASES DO DODANIA

### Test 1: PatternMapping z intendedParentCategory

**Plik:** `src/test/java/com/multi/vidulum/bank_data_ingestion/domain/PatternMappingTest.java` (nowy)

```java
@Test
void shouldCreateUserPatternWithIntendedParentCategory() {
    // given
    String pattern = "BIEDRONKA";
    String category = "Zakupy spożywcze";
    String intendedParent = "Żywność";

    // when
    PatternMapping mapping = PatternMapping.createUser(
            pattern, category, intendedParent, Type.OUTFLOW, "user1", "CF1", 0.95
    );

    // then
    assertThat(mapping.normalizedPattern()).isEqualTo("BIEDRONKA");
    assertThat(mapping.suggestedCategory()).isEqualTo("Zakupy spożywcze");
    assertThat(mapping.intendedParentCategory()).isEqualTo("Żywność");
}

@Test
void shouldCreateUserPatternWithNullIntendedParent() {
    // given - root category has no parent

    // when
    PatternMapping mapping = PatternMapping.createUser(
            "PENSJA", "Wynagrodzenie", null, Type.INFLOW, "user1", "CF1", 0.95
    );

    // then
    assertThat(mapping.intendedParentCategory()).isNull();
}
```

### Test 2: Parser - structureOptimizations

**Plik:** `src/test/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationResponseParserTest.java`

```java
@Test
@DisplayName("Should parse structureOptimizations from AI response")
void shouldParseStructureOptimizations() {
    // given
    String aiResponse = """
            {
              "categoryStructure": {"outflow": [], "inflow": []},
              "patternMappings": [],
              "structureOptimizations": [
                {
                  "action": "MOVE_TO_PARENT",
                  "categoryName": "Zakupy spożywcze",
                  "newParent": "Żywność",
                  "categoryType": "OUTFLOW",
                  "reason": "Restoring intended hierarchy"
                }
              ]
            }
            """;

    // when
    ParseResult result = parser.parse(aiResponse, List.of());

    // then
    assertThat(result.success()).isTrue();
    assertThat(result.structureOptimizations()).hasSize(1);

    var optimization = result.structureOptimizations().get(0);
    assertThat(optimization.action()).isEqualTo(OptimizationAction.MOVE_TO_PARENT);
    assertThat(optimization.categoryName()).isEqualTo("Zakupy spożywcze");
    assertThat(optimization.newParent()).isEqualTo("Żywność");
    assertThat(optimization.categoryType()).isEqualTo(Type.OUTFLOW);
}

@Test
@DisplayName("Should handle missing structureOptimizations field")
void shouldHandleMissingStructureOptimizations() {
    // given
    String aiResponse = """
            {
              "categoryStructure": {"outflow": [], "inflow": []},
              "patternMappings": []
            }
            """;

    // when
    ParseResult result = parser.parse(aiResponse, List.of());

    // then
    assertThat(result.success()).isTrue();
    assertThat(result.structureOptimizations()).isEmpty();
}

@Test
@DisplayName("Should skip invalid structureOptimizations entries")
void shouldSkipInvalidStructureOptimizations() {
    // given
    String aiResponse = """
            {
              "categoryStructure": {"outflow": [], "inflow": []},
              "patternMappings": [],
              "structureOptimizations": [
                {
                  "action": "INVALID_ACTION",
                  "categoryName": "Test"
                },
                {
                  "action": "MOVE_TO_PARENT",
                  "categoryName": "Valid",
                  "newParent": "Parent",
                  "categoryType": "OUTFLOW",
                  "reason": "Valid entry"
                }
              ]
            }
            """;

    // when
    ParseResult result = parser.parse(aiResponse, List.of());

    // then
    assertThat(result.success()).isTrue();
    assertThat(result.structureOptimizations()).hasSize(1);
    assertThat(result.structureOptimizations().get(0).categoryName()).isEqualTo("Valid");
}
```

### Test 3: AcceptAiSuggestionsCommandHandler - zapisuje intencję

**Plik:** `src/test/java/com/multi/vidulum/bank_data_ingestion/app/commands/accept_ai_suggestions/AcceptAiSuggestionsCommandHandlerTest.java`

```java
@Test
void shouldSaveIntendedParentCategoryToPatternCache() {
    // given
    AcceptAiSuggestionsCommand command = new AcceptAiSuggestionsCommand(
            cashFlowId,
            sessionId,
            "user1",
            List.of(),  // no categories to create
            List.of(new MappingToApply(
                    "BIEDRONKA",
                    null,
                    "Zakupy spożywcze",
                    "Żywność",  // ← parentCategory
                    Type.OUTFLOW,
                    95
            )),
            List.of(),
            true  // saveToCache = true
    );

    // when
    handler.handle(command);

    // then
    Optional<PatternMapping> saved = patternMappingRepository
            .findUserByNormalizedPatternAndTypeAndCashFlowId("BIEDRONKA", Type.OUTFLOW, cashFlowId.id());

    assertThat(saved).isPresent();
    assertThat(saved.get().intendedParentCategory()).isEqualTo("Żywność");
}
```

### Test 4: PromptBuilder - CACHED PATTERN INTENTS

**Plik:** `src/test/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationPromptBuilderTest.java`

```java
@Test
void shouldIncludeCachedPatternIntentsInPrompt() {
    // given
    Map<String, List<PatternIntentInfo>> intents = Map.of(
            "Żywność", List.of(
                    new PatternIntentInfo("BIEDRONKA", "Zakupy spożywcze", "Żywność"),
                    new PatternIntentInfo("LIDL", "Zakupy spożywcze", "Żywność")
            ),
            "Transport", List.of(
                    new PatternIntentInfo("ORLEN", "Paliwo", "Transport")
            )
    );

    // when
    String prompt = builder.buildUserPrompt(List.of(), null, intents);

    // then
    assertThat(prompt).contains("CACHED PATTERN INTENTS");
    assertThat(prompt).contains("Intended parent: \"Żywność\"");
    assertThat(prompt).contains("BIEDRONKA → Zakupy spożywcze");
    assertThat(prompt).contains("Intended parent: \"Transport\"");
}

@Test
void shouldNotIncludeCachedPatternIntentsSectionWhenEmpty() {
    // when
    String prompt = builder.buildUserPrompt(List.of(), null, Map.of());

    // then
    assertThat(prompt).doesNotContain("CACHED PATTERN INTENTS");
}

@Test
void shouldLimitCachedPatternIntentsTo10Parents() {
    // given - 15 different intended parents
    Map<String, List<PatternIntentInfo>> intents = new LinkedHashMap<>();
    for (int i = 1; i <= 15; i++) {
        intents.put("Parent" + i, List.of(
                new PatternIntentInfo("PATTERN" + i, "Category" + i, "Parent" + i)
        ));
    }

    // when
    String prompt = builder.buildUserPrompt(List.of(), null, intents);

    // then
    assertThat(prompt).contains("Intended parent: \"Parent1\"");
    assertThat(prompt).contains("Intended parent: \"Parent10\"");
    assertThat(prompt).doesNotContain("Intended parent: \"Parent11\"");
    assertThat(prompt).contains("... (5 more intended parents)");
}
```

### Test 5: Integracyjny - pełny flow Import 1 → Import 2

**Plik:** `src/test/java/com/multi/vidulum/bank_data_ingestion/AiCategorizationPhase2IntegrationTest.java` (nowy)

```java
@Test
@DisplayName("Import 2 should see pattern intents from Import 1 and suggest hierarchy restoration")
void shouldRestoreHierarchyFromCachedIntentsInSecondImport() {
    // === IMPORT 1 ===
    // AI suggests: Żywność → Zakupy spożywcze (single child → flattened)
    // Cache saves: BIEDRONKA → Zakupy spożywcze (intendedParent: Żywność)

    // given - first import
    givenStagedTransactions(List.of(
            transaction("BIEDRONKA", "Biedronka Warszawa", Type.OUTFLOW)
    ));

    // when - AI categorizes
    AiCategorizationResult result1 = aiCategorizationService.categorize(cashFlowId, sessionId);

    // then - structure is flat (single child flattened)
    assertThat(result1.suggestedStructure().outflow())
            .extracting(CategoryNode::name)
            .containsExactly("Zakupy spożywcze");  // NO parent "Żywność"

    // when - user accepts
    acceptSuggestions(result1, saveToCache: true);

    // then - cache has intendedParentCategory
    PatternMapping cached = patternMappingRepository
            .findUserByNormalizedPatternAndTypeAndCashFlowId("BIEDRONKA", Type.OUTFLOW, cashFlowId.id())
            .orElseThrow();
    assertThat(cached.intendedParentCategory()).isEqualTo("Żywność");


    // === IMPORT 2 ===
    // New transactions: MCDONALDS, UBER EATS
    // AI should see cached intent and suggest creating "Żywność" parent

    // given - second import with new patterns
    StagingSessionId sessionId2 = createNewStagingSession();
    givenStagedTransactions(sessionId2, List.of(
            transaction("MCDONALDS", "McDonald's Warszawa", Type.OUTFLOW),
            transaction("UBER EATS", "Uber Eats delivery", Type.OUTFLOW)
    ));

    // when - AI categorizes (should see cached intents in prompt)
    AiCategorizationResult result2 = aiCategorizationService.categorize(cashFlowId, sessionId2);

    // then - AI suggests creating parent "Żywność" with multiple children
    assertThat(result2.suggestedStructure().outflow())
            .extracting(CategoryNode::name)
            .contains("Żywność");

    var zywnosc = result2.suggestedStructure().outflow().stream()
            .filter(n -> n.name().equals("Żywność"))
            .findFirst()
            .orElseThrow();
    assertThat(zywnosc.subCategories()).hasSizeGreaterThanOrEqualTo(2);

    // and - AI suggests moving existing "Zakupy spożywcze" under "Żywność"
    assertThat(result2.structureOptimizations())
            .extracting(StructureOptimization::categoryName)
            .contains("Zakupy spożywcze");
}
```

---

## KOLEJNOŚĆ WYKONANIA

```
1. PatternMapping.java         (kompilacja się zepsuje)
2. PatternMappingEntity.java   (kompilacja ok)
3. AcceptAiSuggestionsCommand.java (kompilacja się zepsuje)
4. AcceptAiSuggestionsCommandHandler.java (kompilacja ok)
5. AiCategorizationResult.java (dodaj StructureOptimization)
6. AiCategorizationResponseParser.java (parsowanie)
7. AiCategorizationPromptBuilder.java (prompt)
8. AiCategorizationService.java (integracja)
9. Testy jednostkowe
10. Testy integracyjne
11. ./mvnw test
```
