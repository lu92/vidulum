# VID-151: Szczegolowe Strategie Naprawy Kategoryzacji AI

**Data:** 2026-04-11
**Problem:** 68% transakcji (539/791) wpadlo do Uncategorized

---

## ARCHITEKTURA - GDZIE CO SIE DZIEJE

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                    FLOW KATEGORYZACJI                                            │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘

                                    CSV FILE
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ ETAP 1: Bank Data Adapter                                                                        │
│ Plik: BankDataAdapterRestController.java                                                        │
│                                                                                                  │
│ Mapowanie kolumn CSV → standard format:                                                         │
│   - "Nadawca/Odbiorca" → name                                                                   │
│   - "Typ operacji" → bankCategory                                                               │
│   - "Tytulem" → description                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ ETAP 2: Staging Session (Create)                                                                 │
│ Plik: CreateStagingSessionCommandHandler.java                                                   │
│                                                                                                  │
│ Tworzy StagedTransaction z:                                                                     │
│   - originalData (name, description, bankCategory, type, amount)                                │
│   - mappedData (categoryName = "Uncategorized" jesli brak mapowania)                            │
│   - validation (PENDING_MAPPING jesli brak mapowania)                                           │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ ETAP 3: AI Categorization                                                                        │
│ Plik: AiCategorizationService.java (linie 68-236)                                               │
│                                                                                                  │
│ Step 1: PatternDeduplicator.deduplicate()  ← STRATEGIA A (rozszerz normalizer)                  │
│         - Normalizuje nazwy transakcji                                                          │
│         - Grupuje po (normalizedPattern, Type)                                                  │
│         - 402 transakcji → 45 patterns                                                          │
│                                                                                                  │
│ Step 2: Cache lookup (per CashFlow)                                                             │
│         - Sprawdza czy pattern jest w cache                                                     │
│         - Tylko 4 patterns w cache → 0 cache hits                                               │
│                                                                                                  │
│ Step 3: AI call                                                                                  │
│         - AiCategorizationPromptBuilder.buildUserPrompt()  ← STRATEGIA B (ulepsz prompt)       │
│         - Wysyla patterns + existing categories + cache hints                                   │
│         - AI zwraca JSON z patternMappings, bankCategoryMappings, etc.                          │
│                                                                                                  │
│ Step 4: AiCategorizationResponseParser.parse()  ← STRATEGIA C (walidacja mappings)              │
│         - Parsuje JSON od AI                                                                     │
│         - Tworzy PatternSuggestion, BankCategorySuggestion                                      │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ ETAP 4: Accept AI Suggestions                                                                    │
│ Plik: AcceptAiSuggestionsCommandHandler.java (linie 45-225)                                     │
│                                                                                                  │
│ Step 1: Tworzy kategorie w CashFlow                                                             │
│         - cashFlowServiceClient.createCategory()                                                │
│                                                                                                  │
│ Step 2: Tworzy category_mappings (bankCategory → targetCategory)                                │
│         - ConfigureCategoryMappingCommandHandler.handle()                                       │
│         ⚠️ PROBLEM: AI stworzyl mappings gdzie bankCategoryName = "ZABKA"                       │
│                     ale w danych bankCategory = "TRANSAKCJA KARTA PLATNICZA"                    │
│                                                                                                  │
│ Step 3: Zapisuje pattern_mappings do cache                                                      │
│         - patternMappingRepository.save()                                                       │
│         - Tylko 4 patterns zapisane (bo AI zwrocil tylko 4)                                     │
│                                                                                                  │
│ Step 4: Revalidate staging session                                                              │
│         - RevalidateStagingCommandHandler.handle()                                               │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│ ETAP 5: Revalidate Staging                                                                       │
│ Plik: RevalidateStagingCommandHandler.java (linie 40-183)                                       │
│                                                                                                  │
│ DLA KAZDEJ transakcji (isPendingMapping):                                                        │
│                                                                                                  │
│   Priority 0: Direct bankCategory match (linie 98-114)  ← STRATEGIA D (description parsing)    │
│               - cashFlowInfo.findCategoryNameIgnoreCase(bankCategory, type)                     │
│               - "TRANSAKCJA KARTA PLATNICZA" → NIE ZNALEZIONO                                   │
│                                                                                                  │
│   Priority 1: Pattern matching (linie 117-131)  ← STRATEGIA E (fallback description)           │
│               - findMatchingPattern(name, type, patternMappings)                                │
│               - normalizedName.contains(pattern.normalizedPattern())                            │
│               ⚠️ PROBLEM: Tylko 4 patterns w cache                                              │
│               - "NETFLIX.COM AMSTERDAM" nie pasuje do zadnego pattern                           │
│                                                                                                  │
│   Priority 2: Category mapping (linie 134-146)                                                   │
│               - mappingMap.get(bankCategory + type)                                             │
│               - "TRANSAKCJA KARTA PLATNICZA" → NIE ZNALEZIONO                                   │
│                                                                                                  │
│   WYNIK: Transakcja zostaje PENDING_MAPPING → Uncategorized                                     │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## STRATEGIA A: Rozszerzenie TransactionNameNormalizer

### Problem
`TransactionNameNormalizer` nie rozpoznaje wielu popularnych wzorcow jak XTREME, ZDROFIT, CLAUDE.

### Przyklad problemu

**Transakcja wejsciowa:**
```
name: "XTREME FITNESS GYMS MI MIELEC"
```

**Normalizacja (obecna):**
```java
// TransactionNameNormalizer.normalize() - linie 102-168

1. normalized = "XTREME FITNESS GYMS MI MIELEC" (toUpperCase)

2. Check KNOWN_SINGLE_WORD_PATTERNS:
   - "XTREME" NOT in Set → skip

3. Check KNOWN_TWO_WORD_PATTERNS:
   - "XTREME FITNESS" NOT in Set → skip

4. Remove noise:
   - CITY_PATTERN: "MIELEC" → removed
   - Result: "XTREME FITNESS GYMS MI"

5. Take first 3 words:
   - Result: "XTREME FITNESS GYMS"  ← NIEROZPOZNANY PATTERN!
```

**Normalizacja (po naprawie):**
```java
// Dodaj do KNOWN_TWO_WORD_PATTERNS:
KNOWN_TWO_WORD_PATTERNS.addAll(Set.of("XTREME FITNESS", "ZDROFIT OCHOTA"));

// Wynik normalizacji:
"XTREME FITNESS GYMS MI MIELEC" → "XTREME FITNESS"  ← ROZPOZNANY!
```

### Gdzie w kodzie

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/TransactionNameNormalizer.java`

**Zmiana (linie 20-52):**
```java
// PRZED:
private static final Set<String> KNOWN_SINGLE_WORD_PATTERNS = Set.of(
    "BIEDRONKA", "LIDL", "ŻABKA", "ZABKA", "KAUFLAND", ...
    // Brakuje: XTREME, ZDROFIT, CLAUDE, JUNONA, SHIVAGO, MOL, TRADINGVIEW
);

// PO:
private static final Set<String> KNOWN_SINGLE_WORD_PATTERNS = Set.of(
    // Existing
    "BIEDRONKA", "LIDL", "ŻABKA", "ZABKA", "KAUFLAND", ...
    // NEW - Fitness
    "XTREME", "ZDROFIT",
    // NEW - Software/Subscriptions
    "CLAUDE", "TRADINGVIEW", "CHATGPT",
    // NEW - Medical/Health
    "JUNONA", "SHIVAGO",
    // NEW - Fuel
    "MOL",
    // NEW - Stores
    "INTERMARCHE", "FRESHPOINT"
);

// PRZED:
private static final Set<String> KNOWN_TWO_WORD_PATTERNS = Set.of(
    "MEDIA EXPERT", "MEDIA MARKT", ...
);

// PO:
private static final Set<String> KNOWN_TWO_WORD_PATTERNS = Set.of(
    // Existing
    "MEDIA EXPERT", "MEDIA MARKT", ...
    // NEW - Fitness
    "XTREME FITNESS", "FITNESS GYMS",
    // NEW - Software
    "CLAUDE.AI", "CHAT GPT",
    // NEW - Food
    "SALAM KEBAB", "AM AM"
);
```

### Dlaczego to nie zostalo zrobione wczesniej

1. **Normalizer byl tworzony dla standardowych polskich sklepow** - BIEDRONKA, LIDL, ORLEN
2. **Nowe wzorce pojawiaja sie z czasem** - CLAUDE.AI, TRADINGVIEW to nowe uslugi
3. **Sieci fitness byly pominiete** - XTREME, ZDROFIT to lokalne sieci
4. **Brak automatycznego uczenia** - system nie aktualizuje listy na podstawie danych

### Impact

| Przed | Po |
|-------|-----|
| 38 transakcji FITNESS w Uncategorized | 0 |
| 2 transakcje CLAUDE w Uncategorized | 0 |
| 2 transakcje MOL w Uncategorized | 0 |

**Effort:** 1h
**Impact:** ~50 transakcji

---

## STRATEGIA B: Ulepszenie Prompt AI

### Problem
AI zwraca za malo pattern mappings - tylko 4 dla 45 unikalnych wzorcow.

### Przyklad problemu

**Prompt (fragment) - linie 189-261 w AiCategorizationPromptBuilder.java:**
```
OUTFLOW PATTERNS (45 unique):
  [12 txns, 708] NETFLIX.COM
    | name: "NETFLIX.COM AMSTERDAM"
    | title: ""
    | bank: TRANSAKCJA KARTA PLATNICZA

  [20 txns, 800] XTREME FITNESS GYMS MI
    | name: "XTREME FITNESS GYMS MI MIELEC"
    | title: "*********0015010"
    | bank: TRANSAKCJA KARTA PLATNICZA
```

**AI Response (obecna):**
```json
{
  "patternMappings": [
    {"pattern": "ZABKA", "suggestedCategory": "Zakupy spozywcze", ...},
    {"pattern": "BIEDRONKA", "suggestedCategory": "Zakupy spozywcze", ...},
    {"pattern": "ALLEGRO", "suggestedCategory": "Zakupy", ...},
    {"pattern": "BADOO", "suggestedCategory": "Inne wydatki", ...}
  ],
  "bankCategoryMappings": [
    {"bankCategory": "ZABKA", "targetCategory": "Zakupy spozywcze", ...}  ← BLAD!
  ]
}
```

**Problem:** AI pomylil pola - uzywa nazw sklepow jako `bankCategory` zamiast faktycznych kategorii bankowych.

### Gdzie w kodzie

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationPromptBuilder.java`

**Zmiana w getSystemPrompt() (linie 26-64):**
```java
// DODAJ na poczatku system prompt:
"""
CRITICAL RULES FOR MAPPINGS:

1. PATTERN MAPPINGS - MANDATORY FOR EVERY PATTERN:
   You MUST create a patternMapping for EVERY unique pattern shown in OUTFLOW/INFLOW PATTERNS.
   Current problem: Only 4 mappings for 45 patterns = 91% uncategorized!

   Example - if you see these patterns:
     [12 txns] NETFLIX.COM
     [20 txns] XTREME FITNESS
     [8 txns] ROSSMANN

   You MUST return:
     "patternMappings": [
       {"pattern": "NETFLIX.COM", "suggestedCategory": "Subskrypcje", "type": "OUTFLOW", ...},
       {"pattern": "XTREME FITNESS", "suggestedCategory": "Silownia", "type": "OUTFLOW", ...},
       {"pattern": "ROSSMANN", "suggestedCategory": "Drogeria", "type": "OUTFLOW", ...}
     ]

2. BANK CATEGORY MAPPINGS - ONLY FOR ACTUAL bankCategory VALUES:
   The "bank:" field shows the ACTUAL bankCategory from CSV.
   DO NOT use transaction names (like "ZABKA") as bankCategory!

   WRONG:
     {"bankCategory": "ZABKA", ...}  ← ZABKA is a name, not bankCategory!

   CORRECT:
     {"bankCategory": "TRANSAKCJA KARTA PLATNICZA", "targetCategory": "Zakupy", ...}

   Common bankCategory values in Polish banks:
   - TRANSAKCJA KARTA PLATNICZA (card payments - very generic, 489 transactions!)
   - PRZELEW (wire transfer)
   - PLATNOSC BLIK (BLIK payment)
   - OPLACENIE Z TYTULU POLECENIA ZAPLATY (direct debit)

   For generic categories like "TRANSAKCJA KARTA PLATNICZA", DO NOT create bankCategoryMapping.
   Instead, create patternMappings for individual merchants.

3. DO NOT RELY ON bankCategoryMappings:
   In Polish banks, 62% of transactions have bankCategory = "TRANSAKCJA KARTA PLATNICZA".
   This is useless for categorization. Use patternMappings based on transaction NAME.
"""
```

**Zmiana w buildUserPrompt() (linie 161-186) - dodaj ostrzezenie:**
```java
// Przed sekcja UNIQUE BANK CATEGORIES, dodaj:
sb.append("""

WARNING: The following bank categories are VERY GENERIC in Polish banks:
- TRANSAKCJA KARTA PLATNICZA (card payment) - covers 60%+ of transactions
- PRZELEW (transfer)
- PLATNOSC BLIK

DO NOT create bankCategoryMappings for these generic categories!
Instead, focus on creating patternMappings for individual merchants in OUTFLOW/INFLOW PATTERNS.

""");
```

### Dlaczego to nie zostalo zrobione wczesniej

1. **Prompt zaklada inteligentne rozroznianie przez AI** - AI powinien wiedziec ze ZABKA to nazwa, nie bankCategory
2. **Brak walidacji response** - system nie sprawdza czy bankCategoryMappings maja sens
3. **Rozne banki rozne struktury** - niektorzy (Pekao) maja dobre kategorie, inni (Nest Bank) ogolne
4. **Prompt ewoluowal organicznie** - dodawane byly nowe features bez calosciowego przegladu

### Impact

| Metryka | Przed | Po |
|---------|-------|-----|
| Pattern mappings od AI | 4 | ~40-45 |
| Transactions matched | 32% | ~80% |

**Effort:** 1-2h
**Impact:** Glowna poprawa jakosci

---

## STRATEGIA C: Walidacja bankCategoryMappings

### Problem
AI tworzy mappingi gdzie `bankCategoryName` to nazwa sklepu (ZABKA), a nie faktyczna kategoria bankowa (TRANSAKCJA KARTA PLATNICZA).

### Przyklad problemu

**AI Response:**
```json
{
  "bankCategoryMappings": [
    {"bankCategory": "ZABKA", "targetCategory": "Zakupy spozywcze", ...}
  ]
}
```

**Faktyczne bankCategory w danych:**
```
TRANSAKCJA KARTA PLATNICZA: 489 transakcji
PRZELEW: 88 transakcji
PLATNOSC BLIK: 72 transakcji
```

**ZABKA nie istnieje jako bankCategory!** → Mapping jest bezuzyteczny.

### Gdzie w kodzie

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/AiCategorizationResponseParser.java`

**Dodaj walidacje w parse() - po linii gdzie parsowane sa bankCategoryMappings:**
```java
// W metodzie parse(), po konwersji bankCategoryMappings:

/**
 * Validate that bankCategoryMappings reference actual bankCategories from transactions.
 * Remove mappings that reference non-existent bankCategories.
 */
private List<BankCategorySuggestion> validateBankCategoryMappings(
        List<BankCategorySuggestion> mappings,
        List<PatternDeduplicator.PatternGroup> patternGroups) {

    // Collect actual bankCategories from transactions
    Set<String> actualBankCategories = patternGroups.stream()
            .map(PatternDeduplicator.PatternGroup::bankCategory)
            .filter(bc -> bc != null && !bc.isBlank())
            .map(String::toUpperCase)
            .collect(Collectors.toSet());

    // Filter out mappings that don't match actual bankCategories
    List<BankCategorySuggestion> validMappings = mappings.stream()
            .filter(m -> actualBankCategories.contains(m.bankCategory().toUpperCase()))
            .toList();

    int removed = mappings.size() - validMappings.size();
    if (removed > 0) {
        log.warn("Removed {} invalid bankCategoryMappings (bankCategory not found in data). " +
                 "Actual categories: {}", removed, actualBankCategories);
    }

    return validMappings;
}
```

**Wywolanie w parse():**
```java
// Po parsowaniu bankCategoryMappings:
List<BankCategorySuggestion> rawBankMappings = convertBankCategoryMappings(json);
List<BankCategorySuggestion> validBankMappings = validateBankCategoryMappings(rawBankMappings, patternGroups);
```

### Dlaczego to nie zostalo zrobione wczesniej

1. **Zaufanie do AI** - zakladano ze AI zwroci poprawne dane
2. **Brak testow z blednym AI response** - nie testowano scenariusza gdzie AI myli pola
3. **Rozne struktury danych z roznych bankow** - trudno przewidziec wszystkie przypadki
4. **Focus na happy path** - implementacja koncentrowala sie na poprawnych danych

### Impact

| Metryka | Przed | Po |
|---------|-------|-----|
| Bledne bankCategoryMappings | 4 z 10 | 0 |
| Mylace logi | Tak | Nie |
| Zrozumienie problemu | Trudne | Latwe (warning w logach) |

**Effort:** 2h
**Impact:** Zapobieganie blednym mappingom

---

## STRATEGIA D: Parsowanie description dla transakcji bankowych

### Problem
Transakcje przez bank posredniczacy maja `name = "BANK PEKAO S.A."`, a prawdziwy merchant jest ukryty w `description`.

### Przyklad problemu

**Transakcja BADOO:**
```json
{
  "name": "BANK PEKAO S.A.",           // ← System patrzy tutaj
  "description": "ROZLICZENIE TRANSAKCJI NA KARTE **** **** 4001 5010 WYKONANEJ: Badoo help@badoo.com\\Suite 1, 4th Floor DN. 03/01/2026",
  "bankCategory": "PRZELEW"
}
```

**Normalizacja (obecna):**
```java
normalizer.normalize("BANK PEKAO S.A.") → "BANK PEKAO S.A."  // Nierozpoznawalne!
```

**Normalizacja (po naprawie):**
```java
// 1. Wykryj transakcje przez bank posredniczacy
// 2. Sprobuj wyciagnac merchant z description
// 3. Jesli sukces, uzyj merchant zamiast name

extractMerchantFromDescription("ROZLICZENIE TRANSAKCJI... Badoo help@badoo.com...")
    → "BADOO"  // Rozpoznany!
```

### Gdzie w kodzie

**Plik 1:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/TransactionNameNormalizer.java`

**Nowa metoda:**
```java
/**
 * Extracts merchant name from transaction description.
 * Used for transactions processed through bank intermediary
 * (e.g., "BANK PEKAO S.A." with Badoo in description).
 *
 * @param description the transaction description
 * @return extracted merchant name, or empty if not found
 */
public Optional<String> extractMerchantFromDescription(String description) {
    if (description == null || description.isBlank()) {
        return Optional.empty();
    }

    String upper = description.toUpperCase();

    // Pattern 1: "WYKONANEJ: MerchantName" (Pekao card settlement)
    // Example: "WYKONANEJ: Badoo help@badoo.com\Suite 1"
    Pattern executedPattern = Pattern.compile("WYKONANEJ:\\s*([A-Za-z0-9.]+)");
    Matcher executedMatcher = executedPattern.matcher(upper);
    if (executedMatcher.find()) {
        String merchant = executedMatcher.group(1);
        // Clean up domain extensions
        merchant = merchant.replaceAll("(?i)\\.com|\\.pl|\\.eu", "");
        return Optional.of(normalize(merchant));
    }

    // Pattern 2: Known merchants in description
    for (String pattern : KNOWN_SINGLE_WORD_PATTERNS) {
        if (upper.contains(pattern)) {
            return Optional.of(pattern);
        }
    }

    // Pattern 3: Email domain (e.g., "help@badoo.com" → "BADOO")
    Pattern emailPattern = Pattern.compile("@([A-Za-z0-9]+)\\.");
    Matcher emailMatcher = emailPattern.matcher(upper);
    if (emailMatcher.find()) {
        return Optional.of(emailMatcher.group(1).toUpperCase());
    }

    return Optional.empty();
}

/**
 * Checks if transaction name looks like a bank intermediary.
 */
public boolean isBankIntermediary(String name) {
    if (name == null) return false;
    String upper = name.toUpperCase();
    return upper.contains("BANK ") ||
           upper.contains("PEKAO") ||
           upper.contains("MBANK") ||
           upper.contains("PKO") ||
           upper.contains("ING ") ||
           upper.contains("SANTANDER");
}
```

**Plik 2:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/categorization/PatternDeduplicator.java`

**Zmiana w deduplicate() - linie 42-57:**
```java
// PRZED:
String originalName = transaction.originalData().name();
String normalizedPattern = normalizer.normalize(originalName);

// PO:
String originalName = transaction.originalData().name();
String normalizedPattern;

// Check if this is a bank intermediary transaction
if (normalizer.isBankIntermediary(originalName)) {
    // Try to extract merchant from description
    Optional<String> merchantFromDesc = normalizer.extractMerchantFromDescription(
            transaction.originalData().description());

    if (merchantFromDesc.isPresent()) {
        normalizedPattern = merchantFromDesc.get();
        log.trace("Extracted merchant from description: {} → {}",
                originalName, normalizedPattern);
    } else {
        normalizedPattern = normalizer.normalize(originalName);
    }
} else {
    normalizedPattern = normalizer.normalize(originalName);
}
```

### Dlaczego to nie zostalo zrobione wczesniej

1. **Wiekszosc bankow uzywa name jako merchant** - Pekao jest wyjatkiem
2. **Rozne formaty description** - kazdy bank ma inny format
3. **Trudne do wykrycia** - trzeba analizowac konkretne dane
4. **Priorytet na happy path** - najpierw podstawowa funkcjonalnosc

### Impact

| Metryka | Przed | Po |
|---------|-------|-----|
| BADOO w Uncategorized | 42 | 0 |
| BANK PEKAO transactions | 87 uncategorized | ~10 uncategorized |
| Netflix przez BANK | uncategorized | categorized |

**Effort:** 3h
**Impact:** +50-90 transakcji

---

## STRATEGIA E: Fallback Pattern Matching w RevalidateStagingCommandHandler

### Problem
`RevalidateStagingCommandHandler.findMatchingPattern()` uzywa tylko `normalizedName.contains(pattern)`.
Nie sprawdza description, nie obsluguje bank intermediary.

### Przyklad problemu

**Transakcja:**
```json
{
  "name": "BANK PEKAO S.A.",
  "description": "ROZLICZENIE TRANSAKCJI... Netflix.com...",
  "bankCategory": "PRZELEW"
}
```

**Pattern matching (obecny):**
```java
// RevalidateStagingCommandHandler.java, linie 210-223
private PatternMatchResult findMatchingPattern(String transactionName, Type type, List<PatternMapping> patterns) {
    String normalizedName = transactionName.toUpperCase();  // "BANK PEKAO S.A."

    return patterns.stream()
            .filter(p -> p.categoryType() == type)
            .filter(p -> normalizedName.contains(p.normalizedPattern()))  // "NETFLIX" NOT in "BANK PEKAO S.A."
            .max(...)
            .orElse(null);  // ← BRAK DOPASOWANIA!
}
```

### Gdzie w kodzie

**Plik:** `src/main/java/com/multi/vidulum/bank_data_ingestion/app/commands/revalidate_staging/RevalidateStagingCommandHandler.java`

**Zmiana w findMatchingPattern() - zastap obecna metode:**
```java
/**
 * Finds the best matching pattern for a transaction.
 *
 * Priority:
 * 1. Pattern in normalizedName (direct match)
 * 2. Pattern in description (fallback for bank intermediary transactions)
 *
 * @param transactionName original transaction name
 * @param description transaction description (may contain merchant for bank intermediary)
 * @param type transaction type
 * @param patterns available pattern mappings
 * @return match result or null if no match
 */
private PatternMatchResult findMatchingPattern(
        String transactionName,
        String description,  // ← NOWY PARAMETR
        Type type,
        List<PatternMapping> patterns) {

    if (patterns.isEmpty() || transactionName == null) {
        return null;
    }

    String normalizedName = transactionName.toUpperCase();
    String normalizedDesc = description != null ? description.toUpperCase() : "";

    // Priority 1: Direct match in name
    Optional<PatternMatchResult> nameMatch = patterns.stream()
            .filter(p -> p.categoryType() == type)
            .filter(p -> normalizedName.contains(p.normalizedPattern()))
            .max(Comparator.comparingDouble(PatternMapping::confidenceScore))
            .map(p -> new PatternMatchResult(p.suggestedCategory(), p.categoryType(), p));

    if (nameMatch.isPresent()) {
        return nameMatch.get();
    }

    // Priority 2: Fallback - check description (for bank intermediary transactions)
    // Only if name looks like a bank name
    if (isBankIntermediary(normalizedName)) {
        Optional<PatternMatchResult> descMatch = patterns.stream()
                .filter(p -> p.categoryType() == type)
                .filter(p -> normalizedDesc.contains(p.normalizedPattern()))
                .max(Comparator.comparingDouble(PatternMapping::confidenceScore))
                .map(p -> new PatternMatchResult(p.suggestedCategory(), p.categoryType(), p));

        if (descMatch.isPresent()) {
            log.debug("Found pattern match in description: {} → {}",
                    transactionName, descMatch.get().categoryName());
            return descMatch.get();
        }
    }

    return null;
}

private boolean isBankIntermediary(String normalizedName) {
    return normalizedName.contains("BANK ") ||
           normalizedName.contains("PEKAO") ||
           normalizedName.contains("MBANK") ||
           normalizedName.contains("PKO") ||
           normalizedName.contains("ING ") ||
           normalizedName.contains("SANTANDER");
}
```

**Zmiana wywolania - linia 117:**
```java
// PRZED:
PatternMatchResult patternMatch = findMatchingPattern(
        st.originalData().name(), st.originalData().type(), patternMappings);

// PO:
PatternMatchResult patternMatch = findMatchingPattern(
        st.originalData().name(),
        st.originalData().description(),  // ← DODAJ description
        st.originalData().type(),
        patternMappings);
```

### Dlaczego to nie zostalo zrobione wczesniej

1. **Optymalizacja na name** - wiekszosci transakcji wystarcza name
2. **Brak danych z Pekao w testach** - testowano glownie z Nest Bank
3. **Performance concerns** - sprawdzanie description dla kazdej transakcji
4. **Inkrementalna implementacja** - najpierw podstawowa funkcjonalnosc

### Impact

| Metryka | Przed | Po |
|---------|-------|-----|
| Bank intermediary transactions | 87 uncategorized | ~10 uncategorized |
| Pattern matching coverage | 32% | ~85% |

**Effort:** 3h (z testami)
**Impact:** +70-80 transakcji

---

## PODSUMOWANIE STRATEGII

```
┌────────────────────────────────────────────────────────────────────────────────────────────────┐
│                              STRATEGIE - PODSUMOWANIE                                           │
├──────────┬──────────┬──────────┬──────────────────────────────────────────────────────────────┤
│ Strategia│ Effort   │ Impact   │ Opis                                                          │
├──────────┼──────────┼──────────┼──────────────────────────────────────────────────────────────┤
│    A     │   1h     │  ~50 txn │ Rozszerz KNOWN_PATTERNS w TransactionNameNormalizer          │
│    B     │   2h     │  ~300 txn│ Ulepsz prompt AI - wymuszaj pattern mappings                 │
│    C     │   2h     │  prevent │ Waliduj bankCategoryMappings przed zapisem                   │
│    D     │   3h     │  ~90 txn │ Parsuj description dla bank intermediary (PatternDeduplicator)│
│    E     │   3h     │  ~80 txn │ Fallback description matching (RevalidateStaging)            │
├──────────┼──────────┼──────────┼──────────────────────────────────────────────────────────────┤
│  TOTAL   │  11h     │ 68%→10% │ Z 539 Uncategorized do ~80                                    │
└──────────┴──────────┴──────────┴──────────────────────────────────────────────────────────────┘
```

### Rekomendowana kolejnosc implementacji

1. **Faza 1 (Quick Wins - 5h):**
   - Strategia A (1h) - rozszerz normalizer
   - Strategia B (2h) - ulepsz prompt
   - Strategia C (2h) - waliduj mappings

2. **Faza 2 (Deep Fixes - 6h):**
   - Strategia D (3h) - description parsing w deduplikacji
   - Strategia E (3h) - fallback w revalidacji

### Oczekiwane wyniki

| Stan | Uncategorized | % |
|------|---------------|---|
| Obecny | 539 | 68% |
| Po Fazie 1 | ~150 | ~19% |
| Po Fazie 2 | ~80 | ~10% |
