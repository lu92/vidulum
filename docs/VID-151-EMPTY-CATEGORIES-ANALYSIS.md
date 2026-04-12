# VID-151: Analiza problemu pustych kategorii i kategorii z jednym dzieckiem

## Problem

Po imporcie transakcji z AI kategoryzacją, w CashFlow Forecast pojawiają się:
1. **Puste kategorie** - bez transakcji i bez subkategorii
2. **Kategorie-rodzice z jednym dzieckiem** - np. `Żywność → Zakupy spożywcze`
3. **Puste kategorie-rodzice** - mają dzieci, ale same nie mają transakcji

## Przykład problemu

### Dane wejściowe (AI sugestie)

```json
{
  "suggestedStructure": {
    "outflow": [
      {"name": "Żywność", "subCategories": ["Zakupy spożywcze"]},
      {"name": "Zakupy", "subCategories": ["Zakupy przez internet"]},
      {"name": "Rozrywka", "subCategories": ["Hobby"]}
    ]
  },
  "patternSuggestions": [
    {"pattern": "ZABKA", "suggestedCategory": "Zakupy spożywcze", "parentCategory": "Żywność"},
    {"pattern": "BIEDRONKA", "suggestedCategory": "Zakupy spożywcze", "parentCategory": "Żywność"},
    {"pattern": "ALLEGRO", "suggestedCategory": "Zakupy przez internet", "parentCategory": "Zakupy"}
  ]
}
```

### Obecne zachowanie

1. `AiCategorizationResponseParser.flattenSingleChildCategories()` spłaszcza strukturę:
```json
{
  "suggestedStructure": {
    "outflow": [
      {"name": "Zakupy spożywcze", "subCategories": []},
      {"name": "Zakupy przez internet", "subCategories": []},
      {"name": "Hobby", "subCategories": []}
    ]
  }
}
```

2. **ALE** `patternSuggestions` pozostają niezmienione:
```json
{
  "patternSuggestions": [
    {"pattern": "ZABKA", "suggestedCategory": "Zakupy spożywcze", "parentCategory": "Żywność"},
    {"pattern": "BIEDRONKA", "suggestedCategory": "Zakupy spożywcze", "parentCategory": "Żywność"}
  ]
}
```

3. Klient (UI) widzi `parentCategory: "Żywność"` i tworzy obie kategorie
4. Rezultat: `Żywność` istnieje jako pusta kategoria-rodzic z jednym dzieckiem

### Oczekiwane zachowanie

Po spłaszczeniu, `patternSuggestions` powinny być zaktualizowane:
```json
{
  "patternSuggestions": [
    {"pattern": "ZABKA", "suggestedCategory": "Zakupy spożywcze", "parentCategory": null},
    {"pattern": "BIEDRONKA", "suggestedCategory": "Zakupy spożywcze", "parentCategory": null}
  ]
}
```

## Lokalizacja problemu

### Plik: `AiCategorizationResponseParser.java`

**Linie 58-67** - Post-processing:
```java
// Post-processing step 1: Enrich with transaction counts and filter empty categories
AiCategorizationResult.SuggestedStructure enrichedStructure =
    enrichAndFilterCategories(rawStructure, suggestions, bankCategorySuggestions);

// Post-processing step 2: Flatten single-child hierarchies
AiCategorizationResult.SuggestedStructure finalStructure =
    flattenSingleChildCategories(enrichedStructure);

// BUG: suggestions i bankCategorySuggestions NIE są aktualizowane po spłaszczeniu!
return ParseResult.success(finalStructure, suggestions, bankCategorySuggestions, unrecognized);
```

**Linie 131-162** - `flattenSingleChildCategories()`:
- Poprawnie spłaszcza strukturę kategorii
- **NIE aktualizuje** `patternSuggestions` ani `bankCategorySuggestions`

## Proponowane rozwiązania

### Rozwiązanie 1: Aktualizacja sugestii po spłaszczeniu (REKOMENDOWANE)

Zmienić `flattenSingleChildCategories()` aby zwracała także zaktualizowane listy sugestii.

**Zalety:**
- Minimalny zakres zmian (tylko `AiCategorizationResponseParser.java`)
- Spójność danych - struktura i sugestie są zsynchronizowane
- Klient dostaje gotowe, poprawne dane

**Wady:**
- Utrata informacji o oryginalnej intencji AI (akceptowalne)

**Implementacja:**

```java
record FlattenResult(
    SuggestedStructure structure,
    List<PatternSuggestion> patternSuggestions,
    List<BankCategorySuggestion> bankCategorySuggestions
) {}

FlattenResult flattenSingleChildCategories(
        SuggestedStructure structure,
        List<PatternSuggestion> patternSuggestions,
        List<BankCategorySuggestion> bankCategorySuggestions) {

    // 1. Zbuduj mapę spłaszczeń: parentName → childName
    Map<String, String> flattenedParents = new HashMap<>();

    // 2. Spłaszcz strukturę i zbierz informacje o spłaszczeniach
    List<CategoryNode> flattenedOutflow = flattenNodes(structure.outflow(), flattenedParents);
    List<CategoryNode> flattenedInflow = flattenNodes(structure.inflow(), flattenedParents);

    // 3. Zaktualizuj patternSuggestions
    List<PatternSuggestion> updatedPatterns = patternSuggestions.stream()
        .map(ps -> {
            if (ps.parentCategory() != null && flattenedParents.containsKey(ps.parentCategory())) {
                // Parent został spłaszczony - usuń parentCategory
                return ps.withParentCategory(null);
            }
            return ps;
        })
        .toList();

    // 4. Zaktualizuj bankCategorySuggestions analogicznie
    List<BankCategorySuggestion> updatedBankSuggestions = bankCategorySuggestions.stream()
        .map(bcs -> {
            if (bcs.parentCategory() != null && flattenedParents.containsKey(bcs.parentCategory())) {
                return bcs.withParentCategory(null);
            }
            return bcs;
        })
        .toList();

    return new FlattenResult(
        new SuggestedStructure(flattenedOutflow, flattenedInflow),
        updatedPatterns,
        updatedBankSuggestions
    );
}
```

---

### Rozwiązanie 2: Dedykowana lista `categoriesToCreate` w odpowiedzi API

Dodać do `AiCategorizationResult` listę kategorii do utworzenia, obliczoną na podstawie spłaszczonej struktury.

**Zalety:**
- Klient nie musi sam obliczać jakie kategorie utworzyć
- Można dodać dodatkową logikę (np. nie twórz jeśli już istnieje)

**Wady:**
- Większy zakres zmian (DTO, REST controller, testy)
- Duplikacja informacji (struktura + lista)

**Implementacja:**

```java
// W AiCategorizationResult
public record AiCategorizationResult(
    // ... istniejące pola ...
    List<CategoryToCreate> categoriesToCreate  // NOWE
) {
    public record CategoryToCreate(
        String name,
        String parentName,  // null dla root categories
        Type type,
        int transactionCount
    ) {}
}
```

---

### Rozwiązanie 3: Walidacja w `AcceptAiSuggestionsCommandHandler`

Filtrować kategorie przed tworzeniem - nie tworzyć kategorii które:
- Mają 0 transakcji i 0 dzieci
- Są rodzicami z tylko jednym dzieckiem

**Zalety:**
- Nie wymaga zmian w logice AI
- Defensywne podejście

**Wady:**
- Wymaga dostępu do pełnej struktury kategorii
- Może być trudne do zaimplementowania (trzeba znać relacje parent-child)
- Nie rozwiązuje problemu niespójności danych w API response

---

## Rekomendacja

**Rozwiązanie 1** jest najlepsze ponieważ:
1. Rozwiązuje problem u źródła (w parserze)
2. Minimalny zakres zmian
3. API zwraca spójne dane
4. Klient nie musi implementować dodatkowej logiki

## Potencjalne problemy i edge cases

### 1. Utrata informacji o intencji AI - WYMAGA ROZWAŻENIA

**Problem:** Gdy spłaszczamy `Żywność → Zakupy spożywcze`, tracimy informację że AI chciało hierarchię.

**Scenariusz gdzie to ma znaczenie:**

```
Import 1:
- Transakcje: ZABKA, BIEDRONKA
- AI sugeruje: Żywność → Zakupy spożywcze
- Spłaszczamy: Zakupy spożywcze (bo tylko 1 dziecko)
- Cache: ZABKA → Zakupy spożywcze (BEZ informacji o Żywność)

Import 2 (kilka miesięcy później):
- Nowe transakcje: RESTAURACJA_XYZ
- AI sugerowałoby: Żywność → Restauracje
- ALE cache już ma "Zakupy spożywcze" bez parenta
- Rezultat: Dwie niezwiązane kategorie zamiast Żywność → [Zakupy spożywcze, Restauracje]
```

**Analiza przepływu:**

1. `PatternMapping` NIE przechowuje `parentCategory` (linia 128 w `AiCategorizationResult.java`):
   ```java
   public static PatternSuggestion fromCache(...) {
       return new PatternSuggestion(
           ...
           null,  // parentCategory is looked up dynamically from CashFlow
           ...
       );
   }
   ```

2. Przy kolejnym imporcie, AI widzi istniejącą kategorię `Zakupy spożywcze` jako top-level
3. AI dostosowuje się do istniejącej struktury zamiast proponować spójną hierarchię

**Możliwe rozwiązania:**

#### Rozwiązanie A: Zachowanie intencji AI w PatternMapping (REKOMENDOWANE)

Dodać pole `intendedParentCategory` do `PatternMapping`:

```java
public record PatternMapping(
    PatternMappingId id,
    String normalizedPattern,
    String suggestedCategory,
    String intendedParentCategory,  // NOWE - oryginalna intencja AI
    Type categoryType,
    PatternSource source,
    // ... reszta pól
) {}
```

**Zachowanie:**
- Cache przechowuje oryginalną intencję AI
- Przy kolejnym imporcie, AI ma kontekst: "ZABKA → Zakupy spożywcze (intendedParent: Żywność)"
- AI może zaproponować: "Utwórz Żywność jako parent dla Zakupy spożywcze i Restauracje"

**Zalety:**
- Zachowuje intencję AI dla przyszłych importów
- Pozwala na "inteligentne" grupowanie w przyszłości
- Minimalny wpływ na istniejący kod

**Wady:**
- Dodatkowe pole w cache
- Logika wykorzystania tego pola wymaga implementacji w AiCategorizationService

#### Rozwiązanie B: Nie spłaszczać jeśli saveToCache=true

Nie spłaszczać struktury gdy użytkownik włącza cache - zachować pełną hierarchię.

**Zalety:**
- Najprostsze
- Zachowuje spójność

**Wady:**
- Tworzy puste kategorie-rodzice (powrót do problemu VID-151)
- Użytkownik musi wybrać: cache albo czysta struktura

#### Rozwiązanie C: Odroczony parent

Gdy spłaszczamy, zapisać w cache "potencjalnego parenta" który zostanie użyty gdy pojawi się drugi sibling:

```java
// W cache:
ZABKA → Zakupy spożywcze (deferredParent: Żywność, siblingCount: 1)

// Przy imporcie z RESTAURACJA:
RESTAURACJA → Restauracje (deferredParent: Żywność, siblingCount: 2)

// Gdy siblingCount >= 2, system automatycznie tworzy hierarchię
```

**Zalety:**
- Automatyczne tworzenie hierarchii gdy ma sens

**Wady:**
- Złożona logika
- Wymaga modyfikacji cache i procesu importu

---

**Rekomendacja:** Rozwiązanie A jest najczystsze - zachowuje intencję AI bez zmiany obecnego zachowania.

---

### 2. Cache wzorców (PatternMapping) - NIE MA PROBLEMU

**Problem pierwotny:** Wzorzec zapisany z `parentCategory: null` może kolidować z późniejszą sugestią AI z `parentCategory: "Żywność"`.

**Analiza kodu - KLUCZOWE ODKRYCIE:**

Po przeanalizowaniu `PatternMapping.java` (linie 21-35) stwierdzam, że **problem nie istnieje**:

```java
/**
 * Note: Parent category is NOT stored - it's looked up dynamically from CashFlow
 * to avoid desynchronization when user moves categories.
 */
public record PatternMapping(
    PatternMappingId id,
    String normalizedPattern,        // e.g., "ZUS", "BIEDRONKA"
    String suggestedCategory,        // e.g., "Social Security" - TYLKO NAZWA KATEGORII!
    Type categoryType,               // INFLOW or OUTFLOW
    PatternSource source,            // GLOBAL, USER, or AI
    String userId,
    String cashFlowId,
    int usageCount,
    double confidenceScore,
    Instant createdAt,
    Instant lastUsedAt
) {}
```

**`PatternMapping` NIE przechowuje `parentCategory`** - tylko nazwę kategorii docelowej (`suggestedCategory`).

**Gdzie `parentCategory` jest dynamicznie wyszukiwane:**

W `CashFlowInfo.java` (linie 190-216) istnieje metoda:
```java
public CategoryName findParentCategory(String categoryName, Type type) {
    List<CategoryInfo> categories = type == Type.INFLOW ? inflowCategories : outflowCategories;
    return findParentCategoryRecursive(categoryName.toLowerCase(), categories, null);
}
```

Ta metoda jest używana w:
- `StageTransactionsCommandHandler.java:220` - podczas stagingu
- `StageTransactionsCommandHandler.java:256` - przy dopasowaniu wzorca
- `RevalidateStagingCommandHandler.java:103` - przy direct match
- `RevalidateStagingCommandHandler.java:274` - przy pattern match

**Wniosek:** Cache jest **z założenia odporny na zmianę hierarchii**. Gdy użytkownik przeniesie kategorię pod innego parenta, cache nadal działa poprawnie - `parentCategory` jest wyszukiwane dynamicznie z aktualnej struktury CashFlow.

---

### 3. Cache mapowań kategorii bankowych (CategoryMapping) - WYMAGA UWAGI

**Różnica:** W przeciwieństwie do `PatternMapping`, `CategoryMapping` **PRZECHOWUJE `parentCategoryName`**:

```java
// CategoryMapping.java (linie 24-35)
public record CategoryMapping(
    MappingId mappingId,
    CashFlowId cashFlowId,
    String bankCategoryName,
    CategoryName targetCategoryName,
    CategoryName parentCategoryName,  // <-- PRZECHOWYWANE!
    Type categoryType,
    MappingAction action,
    Integer confidence,
    ZonedDateTime createdAt,
    ZonedDateTime updatedAt
) {}
```

**Potencjalny problem:**
Gdy `CategoryMapping` zostanie utworzone z `parentCategoryName: "Żywność"`, a później użytkownik przeniesie kategorię "Zakupy spożywcze" na poziom root, powstanie niespójność.

**Ale to nie jest problem w kontekście VID-151:**
1. `CategoryMapping` służy do mapowania **kategorii bankowych** (np. "Internet, TV, telefon" → "Opłaty obowiązkowe")
2. Problem VID-151 dotyczy `patternSuggestions` - mapowań **wzorców** (np. "ZABKA" → "Zakupy spożywcze")
3. `CategoryMapping` jest używane przy `CREATE_SUBCATEGORY` action - wymaga parenta z założenia

**Wniosek:** Problem z `CategoryMapping` jest **poza zakresem VID-151**, ale warto rozważyć w przyszłości.

---

### 4. Zmiana zachowania API

### 4. Zmiana zachowania API

**Problem:** Klienci polegający na `parentCategory` mogą być zaskoczeni wartością `null`.

**Ocena:** To jest poprawka błędu. Dotychczasowe zachowanie było niespójne.

### 5. Kilka wzorców wskazuje na tego samego parenta

**Problem:**
```
ZABKA → Zakupy spożywcze (parent: Żywność)
BIEDRONKA → Zakupy spożywcze (parent: Żywność)
```
Oba muszą być zaktualizowane.

**Ocena:** Proste do zaimplementowania - iteracja po wszystkich sugestiach.

### 6. Kategoria z 2+ dziećmi gdzie jedno jest puste

**Problem:**
```
Żywność → [Zakupy spożywcze (10 txn), Restauracje (0 txn)]
```
Po filtracji pustych subkategorii zostaje 1 dziecko.

**Ocena:** Obecna logika to obsługuje - `enrichAndFilterCategories` najpierw filtruje puste subkategorie, potem `flattenSingleChildCategories` spłaszcza single-child.

## Testy do napisania

1. **Test spłaszczania z aktualizacją sugestii**
   - Input: struktura z single-child, sugestie z parentCategory
   - Expected: spłaszczona struktura, sugestie z parentCategory=null

2. **Test filtracji pustych kategorii**
   - Input: kategoria bez transakcji i bez dzieci
   - Expected: kategoria usunięta ze struktury

3. **Test zachowania hierarchii z 2+ dziećmi**
   - Input: parent z 2 dziećmi z transakcjami
   - Expected: hierarchia zachowana, parentCategory w sugestiach niezmienione

4. **Test edge case: dziecko bez transakcji**
   - Input: parent z 2 dziećmi, jedno bez transakcji
   - Expected: puste dziecko usunięte, jeśli zostaje 1 → spłaszczenie

## Pliki do modyfikacji

1. `AiCategorizationResponseParser.java` - główna logika
2. `AiCategorizationResult.java` - może wymagać metod `withParentCategory()` w rekordach
3. `AiCategorizationResponseParserTest.java` - nowe testy

---

## Podsumowanie analizy

### Obawy użytkownika i wnioski

| Obawa | Status | Wyjaśnienie |
|-------|--------|-------------|
| **Utrata intencji AI** | ⚠️ Wymaga decyzji | Przy przyszłych importach AI nie będzie wiedzieć o oryginalnej hierarchii |
| **Niespójny cache** | ✅ Nie dotyczy | `PatternMapping` NIE przechowuje `parentCategory` - jest wyszukiwane dynamicznie z CashFlow |
| **CategoryMapping** | ⚠️ Poza zakresem VID-151 | `CategoryMapping` przechowuje `parentCategoryName`, ale dotyczy mapowań kategorii bankowych |

### Kluczowe odkrycia

1. **PatternMapping (cache wzorców) jest z założenia odporny na zmianę hierarchii:**
   - Przechowuje tylko `suggestedCategory` (nazwę kategorii)
   - `parentCategory` jest wyszukiwane dynamicznie przez `CashFlowInfo.findParentCategory()`
   - Gdy użytkownik przeniesie kategorię, cache nadal działa poprawnie

2. **Problem VID-151 dotyczy tylko `patternSuggestions` w odpowiedzi AI:**
   - `parentCategory` w `patternSuggestions` jest używane tylko do tworzenia kategorii w `AcceptAiSuggestionsCommandHandler`
   - Po spłaszczeniu struktury, `parentCategory` w sugestiach staje się nieaktualne
   - Rozwiązanie: zaktualizować `parentCategory` do `null` po spłaszczeniu

3. **Scenariusz przyszłych importów:**
   - Gdy spłaszczymy `Żywność → Zakupy spożywcze` do `Zakupy spożywcze`
   - Cache zapisze `ZABKA → Zakupy spożywcze` bez informacji o `Żywność`
   - Przy przyszłym imporcie z `RESTAURACJA`, AI nie będzie wiedziało że `Zakupy spożywcze` miało być pod `Żywność`
   - Rezultat: dwie niezwiązane kategorie zamiast spójnej hierarchii

### Decyzja do podjęcia

**Opcja A: Prosty fix (bez zachowania intencji AI)**
- Implementacja Rozwiązania 1 (aktualizacja sugestii po spłaszczeniu)
- Akceptacja że przyszłe importy mogą tworzyć niespójne hierarchie
- Użytkownik może ręcznie przeorganizować kategorie

**Opcja B: Pełne rozwiązanie (z zachowaniem intencji AI)**
- Implementacja Rozwiązania 1 + dodanie `intendedParentCategory` do `PatternMapping`
- Cache pamięta oryginalną intencję AI
- AI może wykorzystać tę informację przy przyszłych importach

### Rekomendacja

**Opcja B jest lepsza długoterminowo**, ale wymaga więcej pracy:

1. **Faza 1 (VID-151):** Implementacja Rozwiązania 1 - naprawia natychmiastowy problem
2. **Faza 2 (przyszłość):** Dodanie `intendedParentCategory` do `PatternMapping` - zachowuje intencję AI

Implementacja Fazy 1 wymaga:
1. Zmodyfikować `flattenSingleChildCategories()` aby aktualizowało `patternSuggestions` i `bankCategorySuggestions`
2. Dodać metody `withParentCategory(null)` do rekordów `PatternSuggestion` i `BankCategorySuggestion`
3. Napisać testy jednostkowe

Implementacja Fazy 2 (opcjonalna) wymaga:
1. Dodać pole `intendedParentCategory` do `PatternMapping` i `PatternMappingEntity`
2. Zmodyfikować `AcceptAiSuggestionsCommandHandler` aby zapisywał oryginalne `parentCategory` w cache
3. Zmodyfikować `AiCategorizationPromptBuilder` aby przekazywał `intendedParentCategory` do AI jako kontekst

---

## Faza 2: Szczegółowy opis z przykładami

### Problem który rozwiązuje Faza 2

Faza 1 rozwiązuje **natychmiastowy problem** pustych kategorii, ale nie zachowuje wiedzy AI o intencjonalnej hierarchii. Faza 2 rozwiązuje problem **długoterminowej spójności kategoryzacji**.

### Diagram przepływu - porównanie

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              IMPORT 1 (Styczeń)                                 │
│                         Transakcje: ZABKA, BIEDRONKA                            │
└─────────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
                        ┌──────────────────────────────┐
                        │         AI Response          │
                        │  ┌────────────────────────┐  │
                        │  │      Żywność           │  │
                        │  │         │              │  │
                        │  │         ▼              │  │
                        │  │  Zakupy spożywcze      │  │
                        │  └────────────────────────┘  │
                        └──────────────────────────────┘
                                       │
                                       ▼
                        ┌──────────────────────────────┐
                        │   Spłaszczenie (Faza 1)      │
                        │   (tylko 1 dziecko)          │
                        │  ┌────────────────────────┐  │
                        │  │  Zakupy spożywcze      │  │  ← Top-level (bez parenta)
                        │  └────────────────────────┘  │
                        └──────────────────────────────┘
                                       │
                       ┌───────────────┴───────────────┐
                       │                               │
                       ▼                               ▼
        ┌──────────────────────────┐    ┌──────────────────────────┐
        │   BEZ Fazy 2 (obecne)    │    │      Z Fazą 2            │
        │                          │    │                          │
        │  Cache zapisuje:         │    │  Cache zapisuje:         │
        │  ┌────────────────────┐  │    │  ┌────────────────────┐  │
        │  │ ZABKA              │  │    │  │ ZABKA              │  │
        │  │ → Zakupy spożywcze │  │    │  │ → Zakupy spożywcze │  │
        │  │ (brak info o       │  │    │  │ intendedParent:    │  │
        │  │  intencji AI!)     │  │    │  │ "Żywność" ✓        │  │
        │  └────────────────────┘  │    │  └────────────────────┘  │
        └──────────────────────────┘    └──────────────────────────┘
                       │                               │
                       │                               │
┌──────────────────────┴───────────────────────────────┴──────────────────────────┐
│                              IMPORT 2 (Marzec)                                  │
│                      Nowe transakcje: MCDONALDS, UBER EATS                      │
└─────────────────────────────────────────────────────────────────────────────────┘
                       │                               │
                       ▼                               ▼
        ┌──────────────────────────┐    ┌──────────────────────────┐
        │   AI widzi:              │    │   AI widzi:              │
        │                          │    │                          │
        │   Istniejące kategorie:  │    │   Istniejące kategorie:  │
        │   • Zakupy spożywcze     │    │   • Zakupy spożywcze     │
        │     (top-level)          │    │     (top-level)          │
        │                          │    │                          │
        │   Cache:                 │    │   Cache + intencje:      │
        │   • ZABKA → Zakupy spoż. │    │   • ZABKA → Zakupy spoż. │
        │     (bez kontekstu)      │    │     (intendedParent:     │
        │                          │    │      Żywność) ✓          │
        └──────────────────────────┘    └──────────────────────────┘
                       │                               │
                       ▼                               ▼
        ┌──────────────────────────┐    ┌──────────────────────────┐
        │   AI Response:           │    │   AI Response:           │
        │                          │    │                          │
        │   Nowa struktura:        │    │   Spójna struktura:      │
        │   ┌──────────────────┐   │    │   ┌──────────────────┐   │
        │   │    Żywność       │   │    │   │    Żywność       │   │
        │   │       │          │   │    │   │       │          │   │
        │   │       ▼          │   │    │   │       ▼          │   │
        │   │  ┌─────────┐     │   │    │   │  ┌─────────────┐ │   │
        │   │  │Restaur. │     │   │    │   │  │Zakupy spoż. │ │   │
        │   │  │Dostawa  │     │   │    │   │  │Restauracje  │ │   │
        │   │  └─────────┘     │   │    │   │  │Dostawa      │ │   │
        │   └──────────────────┘   │    │   │  └─────────────┘ │   │
        │                          │    │   └──────────────────┘   │
        │   + Zakupy spożywcze     │    │                          │
        │     (osobno, top-level!) │    │   + optymalizacja:       │
        │                          │    │     "Przenieś Zakupy     │
        │                          │    │      spożywcze pod       │
        │                          │    │      Żywność"            │
        └──────────────────────────┘    └──────────────────────────┘
                       │                               │
                       ▼                               ▼
        ┌──────────────────────────┐    ┌──────────────────────────┐
        │   REZULTAT KOŃCOWY:      │    │   REZULTAT KOŃCOWY:      │
        │                          │    │                          │
        │   ❌ NIESPÓJNE!          │    │   ✅ SPÓJNE!             │
        │                          │    │                          │
        │   CashFlow:              │    │   CashFlow:              │
        │   ├── Zakupy spożywcze   │    │   └── Żywność            │
        │   │   (top-level!)       │    │       ├── Zakupy spoż.   │
        │   └── Żywność            │    │       ├── Restauracje    │
        │       ├── Restauracje    │    │       └── Dostawa        │
        │       └── Dostawa        │    │                          │
        └──────────────────────────┘    └──────────────────────────┘
```

### Diagram przepływu danych w systemie

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           ARCHITEKTURA FAZY 2                                   │
└─────────────────────────────────────────────────────────────────────────────────┘

                              ┌─────────────────┐
                              │  CSV z banku    │
                              │  (transakcje)   │
                              └────────┬────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        AiCategorizationService                                  │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │  1. Deduplikacja wzorców                                                │   │
│  │     402 transakcji → 45 unikalnych wzorców                              │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│                                       │                                         │
│                                       ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │  2. Sprawdzenie cache (PatternMappingRepository)                        │   │
│  │                                                                         │   │
│  │     ┌──────────────────────────────────────────────────────────────┐   │   │
│  │     │  PatternMapping (MongoDB)                                    │   │   │
│  │     │  ┌────────────────────────────────────────────────────────┐  │   │   │
│  │     │  │ pattern: "ZABKA"                                       │  │   │   │
│  │     │  │ suggestedCategory: "Zakupy spożywcze"                  │  │   │   │
│  │     │  │ intendedParentCategory: "Żywność"  ← NOWE POLE (Faza 2)│  │   │   │
│  │     │  │ categoryType: OUTFLOW                                  │  │   │   │
│  │     │  │ source: USER                                           │  │   │   │
│  │     │  │ cashFlowId: "CF10000003"                               │  │   │   │
│  │     │  └────────────────────────────────────────────────────────┘  │   │   │
│  │     └──────────────────────────────────────────────────────────────┘   │   │
│  │                                                                         │   │
│  │     Cache hit? ──────────┬─────────────────────────────────────────────│   │
│  │                    TAK   │                                       NIE   │   │
│  │                          ▼                                         │   │   │
│  │              ┌─────────────────────┐                               │   │   │
│  │              │ Użyj z cache (FREE) │                               │   │   │
│  │              │ + intendedParent    │                               │   │   │
│  │              └─────────────────────┘                               │   │   │
│  │                                                                    │   │   │
│  └────────────────────────────────────────────────────────────────────│───┘   │
│                                                                       │       │
│                                                                       ▼       │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │  3. Budowanie promptu dla AI (AiCategorizationPromptBuilder)            │   │
│  │                                                                         │   │
│  │     EXISTING CATEGORIES:                                                │   │
│  │       OUTFLOW:                                                          │   │
│  │         - Zakupy spożywcze (top-level)                                  │   │
│  │                                                                         │   │
│  │     CACHED PATTERN INTENTS:  ← NOWA SEKCJA (Faza 2)                     │   │
│  │       Intended parent: Żywność                                          │   │
│  │         - ZABKA → Zakupy spożywcze                                      │   │
│  │         - BIEDRONKA → Zakupy spożywcze                                  │   │
│  │                                                                         │   │
│  │     PATTERNS TO CATEGORIZE:                                             │   │
│  │       - MCDONALDS [5 txns, 225 PLN]                                     │   │
│  │       - UBER EATS [3 txns, 180 PLN]                                     │   │
│  │                                                                         │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│                                       │                                         │
│                                       ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │  4. Wywołanie AI (OpenAI/Anthropic)                                     │   │
│  │                                                                         │   │
│  │     AI widzi intencje z cache i może:                                   │   │
│  │     • Zaproponować spójną hierarchię                                    │   │
│  │     • Zasugerować przeniesienie istniejących kategorii                  │   │
│  │                                                                         │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│                                       │                                         │
│                                       ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │  5. Parsowanie odpowiedzi (AiCategorizationResponseParser)              │   │
│  │                                                                         │   │
│  │     • patternSuggestions (z parentCategory)                             │   │
│  │     • suggestedStructure (hierarchia)                                   │   │
│  │     • structureOptimizations (sugestie przeniesienia) ← NOWE (Faza 2)   │   │
│  │                                                                         │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                     AcceptAiSuggestionsCommandHandler                           │
│                                                                                 │
│  1. Tworzenie kategorii w CashFlow                                              │
│  2. Tworzenie mapowań                                                           │
│  3. Zapisywanie do cache Z intendedParentCategory ← ZMIANA (Faza 2)             │
│  4. Opcjonalnie: wykonanie structureOptimizations                               │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Diagram modelu danych

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        PORÓWNANIE MODELU DANYCH                                 │
└─────────────────────────────────────────────────────────────────────────────────┘

     OBECNY MODEL (bez Fazy 2)              MODEL Z FAZĄ 2
     ─────────────────────────              ─────────────────────────────────

     PatternMapping                         PatternMapping
     ┌─────────────────────────┐            ┌─────────────────────────────────┐
     │ id                      │            │ id                              │
     │ normalizedPattern       │            │ normalizedPattern               │
     │ suggestedCategory       │            │ suggestedCategory               │
     │                         │            │ intendedParentCategory ← NOWE   │
     │ categoryType            │            │ categoryType                    │
     │ source                  │            │ source                          │
     │ userId                  │            │ userId                          │
     │ cashFlowId              │            │ cashFlowId                      │
     │ usageCount              │            │ usageCount                      │
     │ confidenceScore         │            │ confidenceScore                 │
     │ createdAt               │            │ createdAt                       │
     │ lastUsedAt              │            │ lastUsedAt                      │
     └─────────────────────────┘            └─────────────────────────────────┘

     Przykład rekordu:                      Przykład rekordu:
     ┌─────────────────────────┐            ┌─────────────────────────────────┐
     │ pattern: "ZABKA"        │            │ pattern: "ZABKA"                │
     │ category: "Zakupy spoż."│            │ category: "Zakupy spożywcze"    │
     │ type: OUTFLOW           │            │ intendedParent: "Żywność" ✓     │
     │ source: USER            │            │ type: OUTFLOW                   │
     │ cashFlowId: CF10000003  │            │ source: USER                    │
     │                         │            │ cashFlowId: CF10000003          │
     │ ❌ Brak info o intencji │            │ ✅ Pełna informacja             │
     └─────────────────────────┘            └─────────────────────────────────┘


     PatternSuggestion (API response)       PatternSuggestion (API response)
     ┌─────────────────────────┐            ┌─────────────────────────────────┐
     │ pattern                 │            │ pattern                         │
     │ suggestedCategory       │            │ suggestedCategory               │
     │ parentCategory (z AI)   │            │ parentCategory (z AI)           │
     │ type                    │            │ type                            │
     │ confidence              │            │ confidence                      │
     │ source                  │            │ source                          │
     │                         │            │ intendedParentFromCache ← NOWE  │
     └─────────────────────────┘            └─────────────────────────────────┘

                                            StructureOptimization ← NOWY REKORD
                                            ┌─────────────────────────────────┐
                                            │ action: MOVE_TO_PARENT          │
                                            │ categoryName: "Zakupy spożywcze"│
                                            │ newParent: "Żywność"            │
                                            │ reason: "Restoring AI intent"   │
                                            └─────────────────────────────────┘
```

### Scenariusz szczegółowy

#### Import 1 (Styczeń 2026)

**Transakcje:**
```
ZABKA ZC525 WARSZAWA        | -15.50 PLN | Żywność (bank)
JMP S.A. BIEDRONKA 5900     | -87.30 PLN | Żywność (bank)
ZABKA ZC123 KRAKOW          | -22.10 PLN | Żywność (bank)
```

**AI Response:**
```json
{
  "categoryStructure": {
    "outflow": [
      {"name": "Żywność", "subCategories": ["Zakupy spożywcze"]}
    ]
  },
  "patternMappings": [
    {"pattern": "ZABKA", "suggestedCategory": "Zakupy spożywcze", "parentCategory": "Żywność", "confidence": 95},
    {"pattern": "JMP S.A. BIEDRONKA", "suggestedCategory": "Zakupy spożywcze", "parentCategory": "Żywność", "confidence": 95}
  ]
}
```

**Po spłaszczeniu (Faza 1):**
- Struktura: `Zakupy spożywcze` (top-level, bez parenta)
- patternSuggestions: `parentCategory: null`

**Cache (OBECNE zachowanie - bez Fazy 2):**
```
PatternMapping {
  pattern: "ZABKA",
  suggestedCategory: "Zakupy spożywcze",
  // parentCategory NIE ISTNIEJE w modelu!
}
```

**Cache (Z Fazą 2):**
```
PatternMapping {
  pattern: "ZABKA",
  suggestedCategory: "Zakupy spożywcze",
  intendedParentCategory: "Żywność"  // NOWE POLE
}
```

---

#### Import 2 (Marzec 2026) - kilka miesięcy później

**Nowe transakcje:**
```
MCDONALDS WARSZAWA          | -45.00 PLN | Restauracje (bank)
UBER EATS                   | -67.50 PLN | Jedzenie (bank)
PYSZNE.PL                   | -89.00 PLN | Restauracje (bank)
```

##### BEZ Fazy 2 (obecne zachowanie):

**AI widzi istniejące kategorie:**
```
EXISTING CATEGORIES:
  OUTFLOW:
    - Zakupy spożywcze  (top-level!)
```

**AI Response:**
```json
{
  "categoryStructure": {
    "outflow": [
      {"name": "Żywność", "subCategories": ["Restauracje", "Dostawa jedzenia"]}
    ]
  },
  "patternMappings": [
    {"pattern": "MCDONALDS", "suggestedCategory": "Restauracje", "parentCategory": "Żywność"},
    {"pattern": "UBER EATS", "suggestedCategory": "Dostawa jedzenia", "parentCategory": "Żywność"},
    {"pattern": "PYSZNE.PL", "suggestedCategory": "Dostawa jedzenia", "parentCategory": "Żywność"}
  ]
}
```

**Rezultat końcowy (NIESPÓJNY!):**
```
CashFlow Categories:
├── Zakupy spożywcze (top-level, bez parenta!)
└── Żywność
    ├── Restauracje
    └── Dostawa jedzenia
```

**Problem:** `Zakupy spożywcze` powinno być pod `Żywność`, ale jest osobno bo zostało spłaszczone w Imporcie 1.

---

##### Z Fazą 2:

**AI widzi istniejące kategorie + intencje z cache:**
```
EXISTING CATEGORIES:
  OUTFLOW:
    - Zakupy spożywcze  (top-level)

CACHED PATTERN INTENTS:
  - ZABKA → Zakupy spożywcze (intendedParent: Żywność)
  - JMP S.A. BIEDRONKA → Zakupy spożywcze (intendedParent: Żywność)
```

**AI Response (inteligentne):**
```json
{
  "categoryStructure": {
    "outflow": [
      {
        "name": "Żywność",
        "subCategories": ["Zakupy spożywcze", "Restauracje", "Dostawa jedzenia"]
      }
    ]
  },
  "patternMappings": [
    {"pattern": "MCDONALDS", "suggestedCategory": "Restauracje", "parentCategory": "Żywność"},
    {"pattern": "UBER EATS", "suggestedCategory": "Dostawa jedzenia", "parentCategory": "Żywność"},
    {"pattern": "PYSZNE.PL", "suggestedCategory": "Dostawa jedzenia", "parentCategory": "Żywność"}
  ],
  "structureOptimizations": [
    {
      "action": "MOVE_TO_PARENT",
      "category": "Zakupy spożywcze",
      "newParent": "Żywność",
      "reason": "Restoring intended hierarchy based on cached AI intent"
    }
  ]
}
```

**Rezultat końcowy (SPÓJNY!):**
```
CashFlow Categories:
└── Żywność
    ├── Zakupy spożywcze
    ├── Restauracje
    └── Dostawa jedzenia
```

---

### Diagram sekwencji - Import 2 z Fazą 2

```
┌────────┐     ┌─────────────────┐     ┌───────────────┐     ┌─────────┐     ┌──────────────┐
│  User  │     │IngestionController│    │AiCategService │     │  Cache  │     │   OpenAI     │
└───┬────┘     └────────┬─────────┘     └───────┬───────┘     └────┬────┘     └──────┬───────┘
    │                   │                       │                  │                  │
    │  POST /ai-categorize                      │                  │                  │
    │──────────────────>│                       │                  │                  │
    │                   │                       │                  │                  │
    │                   │  categorize(txns)     │                  │                  │
    │                   │──────────────────────>│                  │                  │
    │                   │                       │                  │                  │
    │                   │                       │  findByPattern   │                  │
    │                   │                       │  ("MCDONALDS")   │                  │
    │                   │                       │─────────────────>│                  │
    │                   │                       │                  │                  │
    │                   │                       │  null (not found)│                  │
    │                   │                       │<─────────────────│                  │
    │                   │                       │                  │                  │
    │                   │                       │  findAllForCashFlow                 │
    │                   │                       │  (get intents)   │                  │
    │                   │                       │─────────────────>│                  │
    │                   │                       │                  │                  │
    │                   │                       │  [ZABKA→Zakupy   │                  │
    │                   │                       │   spożywcze,     │                  │
    │                   │                       │   intendedParent:│                  │
    │                   │                       │   Żywność]       │                  │
    │                   │                       │<─────────────────│                  │
    │                   │                       │                  │                  │
    │                   │                       │                  │                  │
    │                   │                       │  ┌──────────────────────────────┐   │
    │                   │                       │  │ Budowanie promptu z:         │   │
    │                   │                       │  │ • Istniejące kategorie       │   │
    │                   │                       │  │ • CACHED PATTERN INTENTS ✓   │   │
    │                   │                       │  │ • Wzorce do kategoryzacji    │   │
    │                   │                       │  └──────────────────────────────┘   │
    │                   │                       │                  │                  │
    │                   │                       │  prompt z intencjami               │
    │                   │                       │────────────────────────────────────>│
    │                   │                       │                  │                  │
    │                   │                       │                  │     ┌────────────┴───────────┐
    │                   │                       │                  │     │ AI widzi:              │
    │                   │                       │                  │     │ "ZABKA intendedParent: │
    │                   │                       │                  │     │  Żywność"              │
    │                   │                       │                  │     │                        │
    │                   │                       │                  │     │ AI decyduje:           │
    │                   │                       │                  │     │ "Utwórz Żywność z      │
    │                   │                       │                  │     │  wszystkimi dziećmi"   │
    │                   │                       │                  │     └────────────┬───────────┘
    │                   │                       │                  │                  │
    │                   │                       │  {categoryStructure:               │
    │                   │                       │   Żywność→[Zakupy spoż.,           │
    │                   │                       │            Restauracje,            │
    │                   │                       │            Dostawa],               │
    │                   │                       │   structureOptimizations:          │
    │                   │                       │   [MOVE Zakupy spoż.→Żywność]}     │
    │                   │                       │<────────────────────────────────────│
    │                   │                       │                  │                  │
    │                   │  AiCategorizationResult                  │                  │
    │                   │<──────────────────────│                  │                  │
    │                   │                       │                  │                  │
    │  {suggestedStructure,                     │                  │                  │
    │   patternSuggestions,                     │                  │                  │
    │   structureOptimizations}                 │                  │                  │
    │<──────────────────│                       │                  │                  │
    │                   │                       │                  │                  │
```

### Diagram decyzyjny AI

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    LOGIKA DECYZYJNA AI (z Fazą 2)                               │
└─────────────────────────────────────────────────────────────────────────────────┘

                    ┌────────────────────────────┐
                    │  Nowy wzorzec do           │
                    │  kategoryzacji             │
                    │  (np. MCDONALDS)           │
                    └─────────────┬──────────────┘
                                  │
                                  ▼
                    ┌────────────────────────────┐
                    │  Czy istnieje cached       │
                    │  intent dla podobnej       │
                    │  kategorii?                │
                    └─────────────┬──────────────┘
                                  │
                    ┌─────────────┴─────────────┐
                    │                           │
                    ▼                           ▼
              ┌─────────┐                 ┌─────────┐
              │   TAK   │                 │   NIE   │
              └────┬────┘                 └────┬────┘
                   │                           │
                   ▼                           ▼
    ┌──────────────────────────┐    ┌──────────────────────────┐
    │ Sprawdź intendedParent   │    │ Zaproponuj nową          │
    │ z cache                  │    │ kategorię według         │
    │                          │    │ standardowych reguł      │
    │ Np. ZABKA → Zakupy spoż. │    │                          │
    │     intendedParent:      │    │                          │
    │     "Żywność"            │    │                          │
    └───────────┬──────────────┘    └──────────────────────────┘
                │
                ▼
    ┌──────────────────────────┐
    │ Czy nowy wzorzec         │
    │ pasuje do tego samego    │
    │ parenta logicznie?       │
    │                          │
    │ MCDONALDS → Restauracje  │
    │ → pasuje do "Żywność"?   │
    │                          │
    │ TAK! (jedzenie)          │
    └───────────┬──────────────┘
                │
                ▼
    ┌──────────────────────────┐
    │ Ile dzieci będzie miał   │
    │ parent "Żywność"?        │
    └───────────┬──────────────┘
                │
    ┌───────────┴───────────┐
    │                       │
    ▼                       ▼
┌─────────┐           ┌─────────┐
│  1      │           │  2+     │
└────┬────┘           └────┬────┘
     │                     │
     ▼                     ▼
┌────────────────┐   ┌────────────────────────────────────┐
│ Nie twórz      │   │ Utwórz hierarchię:                 │
│ parenta        │   │                                    │
│ (spłaszcz)     │   │ Żywność                            │
│                │   │ ├── Zakupy spożywcze (istniejąca)  │
└────────────────┘   │ ├── Restauracje (nowa)             │
                     │ └── Dostawa (nowa)                 │
                     │                                    │
                     │ + structureOptimization:           │
                     │   MOVE "Zakupy spożywcze"          │
                     │   → pod "Żywność"                  │
                     └────────────────────────────────────┘
```

### Implementacja techniczna Fazy 2

#### 1. Zmiana modelu `PatternMapping`

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/domain/PatternMapping.java`

```java
public record PatternMapping(
    PatternMappingId id,
    String normalizedPattern,
    String suggestedCategory,
    String intendedParentCategory,  // NOWE POLE
    Type categoryType,
    PatternSource source,
    String userId,
    String cashFlowId,
    int usageCount,
    double confidenceScore,
    Instant createdAt,
    Instant lastUsedAt
) {
    // Factory methods zaktualizowane...

    public static PatternMapping createUser(
            String normalizedPattern,
            String suggestedCategory,
            String intendedParentCategory,  // NOWY PARAMETR
            Type categoryType,
            String userId,
            String cashFlowId,
            double confidenceScore
    ) {
        // ...
    }
}
```

#### 2. Zmiana entity `PatternMappingEntity`

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/infrastructure/entity/PatternMappingEntity.java`

```java
@Document("pattern_mappings")
public class PatternMappingEntity {
    // ... existing fields ...

    private String intendedParentCategory;  // NOWE POLE (nullable)

    // ... fromDomain/toDomain zaktualizowane ...
}
```

#### 3. Zmiana `AcceptAiSuggestionsCommandHandler`

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/commands/accept_ai_suggestions/AcceptAiSuggestionsCommandHandler.java`

```java
// Step 3: Save to pattern cache (if requested)
if (command.saveToCache() && command.acceptedMappings() != null) {
    for (AcceptAiSuggestionsCommand.MappingToApply mapping : command.acceptedMappings()) {
        PatternMapping patternMapping = PatternMapping.createUser(
                mapping.pattern().toUpperCase(),
                mapping.targetCategory(),
                mapping.parentCategory(),  // NOWE - zapisz oryginalną intencję AI
                mapping.type(),
                command.userId(),
                cashFlowIdStr,
                (double) mapping.confidence() / 100.0
        );
        patternMappingRepository.save(patternMapping);
    }
}
```

#### 4. Zmiana `AiCategorizationPromptBuilder`

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationPromptBuilder.java`

Dodać sekcję w prompcie:

```java
// Dodać do buildUserPrompt():

// Add cached pattern intents
List<PatternMapping> cachedPatterns = getCachedPatternsWithIntents(cashFlowId);
if (!cachedPatterns.isEmpty()) {
    sb.append("CACHED PATTERN INTENTS (use for hierarchy consistency):\n");

    // Group by intendedParentCategory
    Map<String, List<PatternMapping>> byIntendedParent = cachedPatterns.stream()
        .filter(pm -> pm.intendedParentCategory() != null)
        .collect(Collectors.groupingBy(PatternMapping::intendedParentCategory));

    for (Map.Entry<String, List<PatternMapping>> entry : byIntendedParent.entrySet()) {
        sb.append("  Intended parent: ").append(entry.getKey()).append("\n");
        for (PatternMapping pm : entry.getValue()) {
            sb.append("    - ").append(pm.normalizedPattern())
              .append(" → ").append(pm.suggestedCategory()).append("\n");
        }
    }
    sb.append("\n");
}
```

Dodać do system prompt:

```java
// Dodać do getSystemPrompt():

"""
HIERARCHY CONSISTENCY:
When you see CACHED PATTERN INTENTS, it means previous AI suggested these categories
should be under a parent category. If you're now creating new categories that would
logically fit under the same parent, consider:

1. If the intended parent doesn't exist yet but makes sense with new categories → CREATE IT
2. If only 1 child would be under parent → keep flat (no single-child hierarchies)
3. If 2+ children would be under parent → create the hierarchy

Example:
- Cached: ZABKA → "Zakupy spożywcze" (intendedParent: "Żywność")
- New pattern: MCDONALDS → should map to "Restauracje"
- Decision: Create "Żywność" with children ["Zakupy spożywcze", "Restauracje"]
- Also suggest moving existing "Zakupy spożywcze" under "Żywność" via structureOptimizations
"""
```

#### 5. Nowy typ odpowiedzi: `structureOptimizations`

```java
// W AiCategorizationResult.java:

public record StructureOptimization(
    OptimizationAction action,
    String categoryName,
    String newParent,
    String reason
) {
    public enum OptimizationAction {
        MOVE_TO_PARENT,      // Przenieś kategorię pod nowego parenta
        CREATE_PARENT,       // Utwórz parenta i przenieś dzieci
        MERGE_CATEGORIES     // Połącz podobne kategorie
    }
}
```

---

### Korzyści Fazy 2

| Aspekt | Bez Fazy 2 | Z Fazą 2 |
|--------|------------|----------|
| Spójność hierarchii | ❌ Kategorie mogą być rozrzucone | ✅ AI grupuje logicznie powiązane kategorie |
| Pamięć intencji | ❌ Utracona po spłaszczeniu | ✅ Zachowana w cache |
| Kolejne importy | ❌ AI nie wie o poprzedniej intencji | ✅ AI ma kontekst z poprzednich importów |
| Ręczna praca | ❌ Użytkownik musi reorganizować | ✅ AI proponuje optymalizacje |

### Ryzyka i mitygacje

| Ryzyko | Prawdopodobieństwo | Mitygacja |
|--------|-------------------|-----------|
| AI ignoruje intencje | Średnie | Jasne instrukcje w system prompt |
| Konflikty intencji | Niskie | AI rozwiązuje przez głosowanie (najczęstsza intencja) |
| Migracja danych | Średnie | Pole nullable, stare dane działają bez zmian |
| Złożoność promptu | Niskie | Sekcja dodawana tylko gdy są intencje |

### Kiedy wdrożyć Fazę 2?

**Rekomendacja:** Po wdrożeniu Fazy 1 i obserwacji jak użytkownicy korzystają z kategoryzacji.

**Sygnały że Faza 2 jest potrzebna:**
- Użytkownicy często ręcznie reorganizują kategorie
- Wiele CashFlow ma niespójne hierarchie
- Użytkownicy zgłaszają że "AI nie pamięta" poprzednich decyzji

---

## Analiza pakietu CashFlow - operacje na kategoriach

### Kluczowe odkrycie: Jak transakcje są powiązane z kategoriami

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    MODEL POWIĄZANIA TRANSAKCJA ↔ KATEGORIA                      │
└─────────────────────────────────────────────────────────────────────────────────┘

    CashChange (transakcja)                    Category (w CashFlow)
    ┌────────────────────────┐                ┌────────────────────────────────┐
    │ cashChangeId           │                │ Żywność (parent)               │
    │ name: "ZABKA..."       │                │   ├── Zakupy spożywcze (child) │
    │ money: -15.50 PLN      │                │   └── Restauracje (child)      │
    │ categoryName: ─────────┼───────────────▶│                                │
    │   "Zakupy spożywcze"   │   TYLKO NAZWA! │ Zakupy (parent)                │
    │                        │   (nie ID,     │   └── Allegro (child)          │
    │ (brak parentCategory!) │    nie parent) │                                │
    └────────────────────────┘                └────────────────────────────────┘

    ✅ KLUCZOWA OBSERWACJA:
    CashChange przechowuje TYLKO categoryName (String).
    NIE przechowuje:
    - parentCategory
    - categoryId
    - hierarchii

    Oznacza to że:
    1. Przeniesienie kategorii NIE wymaga aktualizacji transakcji
    2. Transakcja "podąża" za swoją kategorią automatycznie
    3. Hierarchia jest obliczana dynamicznie przy wyświetlaniu
```

### Dostępne operacje na kategoriach w CashFlow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                      OPERACJE NA KATEGORIACH W VIDULUM                          │
└─────────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────┬──────────────────────────────────────────────────────────┐
│ Operacja             │ Opis                                                     │
├──────────────────────┼──────────────────────────────────────────────────────────┤
│ CREATE CATEGORY      │ Tworzy nową kategorię (root lub child)                   │
│                      │ POST /cash-flow/cf={id}/category                         │
│                      │ { "categoryName": "X", "parentCategoryName": "Y" }       │
├──────────────────────┼──────────────────────────────────────────────────────────┤
│ MOVE CATEGORY        │ Przenosi kategorię do innego parenta lub na root         │
│                      │ POST /cash-flow/cf={id}/category/move                    │
│                      │ { "categoryName": "X", "newParentCategoryName": "Y" }    │
│                      │                                                          │
│                      │ ✅ Subcategories przenoszone razem z kategorią           │
│                      │ ✅ Transakcje pozostają NIEZMIENIONE                     │
│                      │ ❌ Nie można przenieść SYSTEM categories (Uncategorized) │
│                      │ ❌ Nie można utworzyć circular dependency                 │
├──────────────────────┼──────────────────────────────────────────────────────────┤
│ ARCHIVE CATEGORY     │ Ukrywa kategorię (nie można dodawać nowych transakcji)   │
│                      │ POST /cash-flow/cf={id}/category/archive                 │
│                      │                                                          │
│                      │ ✅ Historyczne transakcje pozostają widoczne             │
│                      │ ✅ Można odarchiwizować (unarchive)                      │
├──────────────────────┼──────────────────────────────────────────────────────────┤
│ UNARCHIVE CATEGORY   │ Przywraca zarchiwizowaną kategorię                       │
│                      │ POST /cash-flow/cf={id}/category/unarchive               │
├──────────────────────┼──────────────────────────────────────────────────────────┤
│ ❌ RENAME CATEGORY   │ NIE ZAIMPLEMENTOWANE                                     │
│                      │ (Wymagałoby aktualizacji wszystkich transakcji!)         │
├──────────────────────┼──────────────────────────────────────────────────────────┤
│ ❌ DELETE CATEGORY   │ NIE ZAIMPLEMENTOWANE                                     │
│                      │ (Co zrobić z transakcjami? → użyj ARCHIVE)               │
├──────────────────────┼──────────────────────────────────────────────────────────┤
│ ❌ MERGE CATEGORIES  │ NIE ZAIMPLEMENTOWANE                                     │
│                      │ (Wymagałoby aktualizacji transakcji z kategorii A→B)     │
└──────────────────────┴──────────────────────────────────────────────────────────┘
```

### Scenariusz: Tworzenie parenta "Żywność" i przenoszenie "Zakupy spożywcze"

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    SCENARIUSZ: FAZA 2 - TWORZENIE HIERARCHII                    │
└─────────────────────────────────────────────────────────────────────────────────┘

    STAN POCZĄTKOWY (po Imporcie 1 - spłaszczone):
    ─────────────────────────────────────────────
    CashFlow Categories:                   Transakcje:
    ├── Zakupy spożywcze (root)            ├── ZABKA → Zakupy spożywcze (53 txn)
    └── Uncategorized                      └── BIEDRONKA → Zakupy spożywcze (20 txn)


    KROK 1: AI sugeruje utworzenie parenta "Żywność"
    ────────────────────────────────────────────────
    structureOptimizations: [
      {
        "action": "CREATE_PARENT_AND_MOVE",
        "newParent": "Żywność",
        "childrenToMove": ["Zakupy spożywcze"],
        "type": "OUTFLOW"
      }
    ]


    KROK 2: System wykonuje operacje
    ─────────────────────────────────

    2a. POST /cash-flow/cf={id}/category
        { "categoryName": "Żywność", "type": "OUTFLOW" }

        CashFlow Categories:
        ├── Żywność (NOWY - pusty)        ← utworzono
        ├── Zakupy spożywcze (root)
        └── Uncategorized

    2b. POST /cash-flow/cf={id}/category/move
        { "categoryName": "Zakupy spożywcze",
          "newParentCategoryName": "Żywność",
          "categoryType": "OUTFLOW" }

        CashFlow Categories:
        ├── Żywność                        ← parent
        │   └── Zakupy spożywcze           ← przeniesiono (było root)
        └── Uncategorized

        ✅ TRANSAKCJE SĄ NIEZMIENIONE!
        Nadal mają categoryName: "Zakupy spożywcze"
        Ale teraz ta kategoria jest pod "Żywność"


    KROK 3: Import 2 - AI dodaje nowe kategorie
    ────────────────────────────────────────────
    patternMappings: [
      { "pattern": "MCDONALDS", "category": "Restauracje", "parent": "Żywność" },
      { "pattern": "UBER EATS", "category": "Dostawa", "parent": "Żywność" }
    ]

        CashFlow Categories:
        ├── Żywność
        │   ├── Zakupy spożywcze (73 txn)  ← stare transakcje
        │   ├── Restauracje (5 txn)        ← NOWE
        │   └── Dostawa (3 txn)            ← NOWE
        └── Uncategorized

    ✅ WYNIK: Spójna hierarchia bez ręcznej pracy użytkownika!
```

### Scenariusz problematyczny: Ingestion w trakcie reorganizacji

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│            SCENARIUSZ: RÓWNOLEGŁE OPERACJE (Ingestion + Move)                   │
└─────────────────────────────────────────────────────────────────────────────────┘

    STAN POCZĄTKOWY:
    ─────────────────
    CashFlow Categories:
    ├── Zakupy spożywcze (root)

    Staging Session (Ingestion 2):
    ├── Transakcja A → Zakupy spożywcze (mapowana)
    ├── Transakcja B → Zakupy spożywcze (mapowana)


    SCENARIUSZ 1: Move PRZED Import
    ────────────────────────────────

    T1: Użytkownik tworzy "Żywność" i przenosi "Zakupy spożywcze"
    T2: Import wykonuje się

    Staging Session widzi:
    ├── Transakcja A → Zakupy spożywcze ✅ (kategoria istnieje, jest pod Żywność)
    ├── Transakcja B → Zakupy spożywcze ✅ (kategoria istnieje, jest pod Żywność)

    ✅ DZIAŁA! Import używa tylko categoryName, nie sprawdza hierarchii.


    SCENARIUSZ 2: Move W TRAKCIE Import (race condition)
    ─────────────────────────────────────────────────────

    T1: Import startuje, waliduje kategorie
    T2: Użytkownik tworzy "Żywność" i przenosi "Zakupy spożywcze"
    T3: Import tworzy transakcje

    ✅ DZIAŁA! Transakcje mają categoryName: "Zakupy spożywcze"
    Nieważne gdzie kategoria jest w hierarchii - transakcja "podąża" za nią.


    SCENARIUSZ 3: Import → Move → Re-import (ten sam CashFlow)
    ───────────────────────────────────────────────────────────

    T1: Import 1 - tworzy "Zakupy spożywcze" (root), 73 transakcje
    T2: Użytkownik tworzy "Żywność", przenosi "Zakupy spożywcze" pod nią
    T3: Import 2 - nowe transakcje

    Import 2 widzi:
    - Istniejąca kategoria: "Zakupy spożywcze" (pod "Żywność")
    - AI może zasugerować nowe kategorie pod "Żywność"

    ✅ DZIAŁA! Stare i nowe transakcje są w tej samej kategorii.
```

### Co system MOŻE a czego NIE MOŻE zrobić

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    MOŻLIWOŚCI vs OGRANICZENIA                                   │
└─────────────────────────────────────────────────────────────────────────────────┘

    ✅ SYSTEM MOŻE:
    ────────────────
    1. Tworzyć nowe kategorie (root lub child)
    2. Przenosić istniejące kategorie między parentami
    3. Przenosić kategorie na poziom root
    4. Archiwizować/odarchiwizować kategorie
    5. Zachowywać transakcje przy przenoszeniu kategorii

    ❌ SYSTEM NIE MOŻE (brak implementacji):
    ────────────────────────────────────────
    1. RENAME kategoria
       → Wymagałoby: UPDATE wszystkich transakcji z categoryName: "old" → "new"
       → Ryzyko: Duża liczba transakcji, spójność danych

    2. DELETE kategoria
       → Pytanie: Co z transakcjami? Przenieść do Uncategorized? Usunąć?
       → Obecne rozwiązanie: ARCHIVE (ukrywa, nie usuwa)

    3. MERGE kategorie (A + B → A)
       → Wymagałoby: UPDATE wszystkich transakcji z categoryName: "B" → "A"
       → + Usunięcie kategorii B

    4. Automatyczne wykrywanie duplikatów kategorii
       → "Zakupy spożywcze" vs "Zakupy Spożywcze" vs "zakupy spożywcze"


    ⚠️ IMPLIKACJE DLA FAZY 2:
    ──────────────────────────
    Faza 2 może używać:
    ✅ CREATE CATEGORY - tworzenie parenta "Żywność"
    ✅ MOVE CATEGORY - przenoszenie "Zakupy spożywcze" pod "Żywność"

    Faza 2 NIE wymaga:
    ❌ RENAME - nie zmieniamy nazw kategorii
    ❌ DELETE - nie usuwamy kategorii
    ❌ MERGE - nie łączymy kategorii
```

### Wpływ na CashFlow Forecast

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    PRZEPŁYW EVENTÓW PRZY MOVE CATEGORY                          │
└─────────────────────────────────────────────────────────────────────────────────┘

    CashFlow Domain                          Kafka                    Forecast
    ┌─────────────────┐                  ┌─────────────┐          ┌─────────────┐
    │ MoveCategoryCmd │                  │             │          │             │
    │        │        │                  │             │          │             │
    │        ▼        │                  │             │          │             │
    │ CategoryMoved   │ ────emit────▶   │ cash_flow   │ ───────▶ │CategoryMoved│
    │ Event           │                  │ topic       │          │EventHandler │
    │                 │                  │             │          │             │
    │ Zmienia tylko:  │                  │             │          │ Zmienia:    │
    │ - hierarchię    │                  │             │          │ - Category  │
    │   kategorii     │                  │             │          │   Structure │
    │                 │                  │             │          │ - Monthly   │
    │ NIE zmienia:    │                  │             │          │   Forecasts │
    │ - transakcji    │                  │             │          │             │
    │ - categoryName  │                  │             │          │ NIE zmienia:│
    │   w CashChange  │                  │             │          │ - transakcji│
    └─────────────────┘                  └─────────────┘          └─────────────┘

    CategoryMovedEventHandler (linie 111-148):
    ┌─────────────────────────────────────────────────────────────────────────┐
    │ 1. Znajduje CashCategory w hierarchii (stary parent)                   │
    │ 2. Usuwa z starej lokalizacji                                          │
    │ 3. Dodaje do nowej lokalizacji (nowy parent)                           │
    │                                                                         │
    │ ✅ groupedTransactions (lista transakcji) przenoszone RAZEM z kategorią│
    │ ✅ Wszystkie subCategories przenoszone RAZEM                           │
    │ ✅ totalPaidValue, budgeting - zachowane                               │
    └─────────────────────────────────────────────────────────────────────────┘
```

### Wnioski dla implementacji Fazy 2

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    WNIOSKI DLA IMPLEMENTACJI                                    │
└─────────────────────────────────────────────────────────────────────────────────┘

    1. MOVE CATEGORY jest BEZPIECZNE
       ─────────────────────────────
       - Transakcje nie wymagają aktualizacji
       - Forecast aktualizuje się automatycznie via Kafka event
       - Subcategories przenoszone razem

    2. IMPLEMENTACJA structureOptimizations
       ─────────────────────────────────────
       AcceptAiSuggestionsCommandHandler może:

       a) CREATE_PARENT_AND_MOVE:
          1. POST /category { name: "Żywność" }
          2. POST /category/move { name: "Zakupy spożywcze", newParent: "Żywność" }

       b) MOVE_TO_EXISTING_PARENT:
          1. POST /category/move { name: "X", newParent: "Y" }

    3. KOLEJNOŚĆ OPERACJI
       ───────────────────
       WAŻNE! Przy CREATE_PARENT_AND_MOVE:
       1. NAJPIERW utwórz parent
       2. POTEM przenoś children
       (inaczej: CategoryNotFoundException)

    4. RACE CONDITIONS - NIE STANOWIĄ PROBLEMU
       ───────────────────────────────────────
       - Transakcje mają tylko categoryName
       - Move nie zmienia categoryName
       - Import może działać równolegle z Move

    5. CO Z PENDING STAGING SESSIONS?
       ───────────────────────────────
       Jeśli staging session mapuje do kategorii która jest przenoszona:
       - categoryName pozostaje ten sam
       - Revalidate znajdzie kategorię (w nowej lokalizacji)
       ✅ NIE MA PROBLEMU
```

---

## Podsumowanie całej analizy

### Problem VID-151

**Natychmiastowy problem:** Po AI kategoryzacji i spłaszczeniu single-child hierarchii, `patternSuggestions` zawierają nieaktualne `parentCategory`, co prowadzi do tworzenia pustych kategorii-rodziców.

### Rozwiązanie dwufazowe

| Faza | Cel | Zakres zmian | Priorytet |
|------|-----|--------------|-----------|
| **Faza 1** | Naprawić `patternSuggestions` po spłaszczeniu | `AiCategorizationResponseParser.java` | 🔴 Wysoki |
| **Faza 2** | Zachować intencję AI dla przyszłych importów | `PatternMapping`, `AiCategorizationPromptBuilder` | 🟡 Średni |

### Kluczowe odkrycia techniczne

1. **CashChange przechowuje tylko `categoryName`** - nie parent, nie ID
2. **MOVE CATEGORY jest bezpieczne** - transakcje nie wymagają aktualizacji
3. **PatternMapping nie przechowuje `parentCategory`** - z założenia odporne na reorganizację
4. **CategoryMapping przechowuje `parentCategoryName`** - poza zakresem VID-151

### Pliki do modyfikacji

**Faza 1:**
- `AiCategorizationResponseParser.java` - aktualizacja `patternSuggestions` po spłaszczeniu
- `AiCategorizationResult.java` - metody `withParentCategory()`
- `AiCategorizationResponseParserTest.java` - nowe testy

**Faza 2 (przyszłość):**
- `PatternMapping.java` + `PatternMappingEntity.java` - pole `intendedParentCategory`
- `AcceptAiSuggestionsCommandHandler.java` - zapis intencji do cache
- `AiCategorizationPromptBuilder.java` - sekcja CACHED PATTERN INTENTS w prompcie
- `AiCategorizationResult.java` - nowy typ `StructureOptimization`

### Następne kroki

1. ✅ Analiza problemu (dokument)
2. ⬜ Implementacja Fazy 1
3. ⬜ Testy jednostkowe
4. ⬜ Testy integracyjne
5. ⬜ Code review
6. ⬜ (Opcjonalnie) Implementacja Fazy 2
