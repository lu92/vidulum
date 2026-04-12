# VID-151: Analiza Problemu Uncategorized - Dlaczego 68% transakcji wpadło do Uncategorized

**Data analizy:** 2026-04-11
**CashFlow:** CF10000006 (user: lu100)
**Transakcji:** 791 total, **539 Uncategorized (68%)**

---

## 1. PODSUMOWANIE PROBLEMU

Po imporcie CSV i AI kategoryzacji, **539 z 791 transakcji** (68%) wpadło do kategorii "Uncategorized", mimo że wiele z nich to rozpoznawalne transakcje jak:
- NETFLIX (12 transakcji)
- XTREME FITNESS / ZDROFIT (38 transakcji siłowni)
- ORLEN/BP (stacje paliw)
- ROSSMANN
- PLUS (telefon)
- BADOO (42 transakcje!)

---

## 2. DIAGRAM PRZEPŁYWU DANYCH

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            BANK CSV FILE                                     │
│                                                                              │
│  Data operacji | Nadawca/Odbiorca | Typ operacji           | Kwota          │
│  09.01.2026   | BANK PEKAO S.A.  | PRZELEW                | -21.99         │
│  09.01.2026   | NETFLIX.COM      | TRANSAKCJA KARTĄ PŁAT. | -59.00         │
│  09.01.2026   | XTREME FITNESS   | TRANSAKCJA KARTĄ PŁAT. | -40.00         │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         BANK DATA ADAPTER                                    │
│                    (AI CSV Transformation)                                   │
│                                                                              │
│  Mapuje kolumny:                                                            │
│    "Nadawca/Odbiorca" → name                                                │
│    "Typ operacji"     → bankCategory                                        │
│    "Tytułem"          → description                                         │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      STAGED TRANSACTIONS                                     │
│                                                                              │
│  Transaction 1:                                                             │
│    name:         "BANK PEKAO S.A."           ← Nie "BADOO"!                 │
│    description:  "ROZLICZENIE TRANSAKCJI... Badoo help@badoo.com..."        │
│    bankCategory: "PRZELEW"                   ← Nie specyficzna kategoria    │
│                                                                              │
│  Transaction 2:                                                             │
│    name:         "NETFLIX.COM AMSTERDAM"                                    │
│    bankCategory: "TRANSAKCJA KARTĄ PŁATNICZĄ" ← Generic, 489 transakcji!   │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    AI CATEGORIZATION SERVICE                                 │
│                                                                              │
│  Step 1: Normalize names                                                    │
│    "NETFLIX.COM AMSTERDAM" → "NETFLIX" ✅                                   │
│    "BANK PEKAO S.A."       → "BANK PEKAO S.A." (nie rozpoznany pattern)    │
│                                                                              │
│  Step 2: Deduplicate → 45 unique patterns                                   │
│                                                                              │
│  Step 3: AI call with patterns                                              │
│                                                                              │
│  Step 4: Create mappings:                                                   │
│    Category Mappings (bankCategory based):                                  │
│      "ZABKA"      → "Zakupy spożywcze"   ⚠️ ALE: to NAZWA, nie bankCategory │
│      "BADOO"      → "Inne wydatki"       ⚠️ ALE: BADOO jest w DESCRIPTION   │
│      "PRZELEW"    → NIE ZMAPOWANE                                           │
│                                                                              │
│    Pattern Mappings (name based):                                           │
│      "ZABKA"      → "Zakupy spożywcze"                                      │
│      "BIEDRONKA"  → "Zakupy spożywcze"                                      │
│      (tylko 4 pattern mappings!)                                            │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         MAPPING APPLICATION                                  │
│                                                                              │
│  Dla każdej transakcji:                                                     │
│    1. Sprawdź bankCategory → category_mappings                              │
│       "TRANSAKCJA KARTĄ PŁATNICZĄ" → NIE ZNALEZIONO (489 transakcji!)      │
│       "PRZELEW" → NIE ZNALEZIONO                                            │
│                                                                              │
│    2. Sprawdź normalized name → pattern_mappings                            │
│       "BANK PEKAO S.A." → NIE ZNALEZIONO                                    │
│       "NETFLIX" → NIE ZNALEZIONO (tylko 4 patterns!)                        │
│                                                                              │
│    3. Jeśli nie znaleziono → "Uncategorized"                                │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. IDENTYFIKACJA PROBLEMÓW

### Problem #1: bankCategory jest zbyt ogólna

**Rozkład bankCategory w danych:**

| bankCategory | Ilość | % |
|-------------|-------|---|
| TRANSAKCJA KARTĄ PŁATNICZĄ | 489 | 62% |
| PRZELEW | 88 | 11% |
| PŁATNOŚĆ BLIK | 72 | 9% |
| PRZELEW INTERNET M/B | 31 | 4% |
| OBCIĄŻENIE Z TYTUŁU POLECENIA ZAPŁATY | 23 | 3% |
| ... inne | 88 | 11% |

**Problem:** 62% transakcji ma tę samą `bankCategory = "TRANSAKCJA KARTĄ PŁATNICZĄ"`.
Mapowanie po `bankCategory` nie ma sensu dla tak ogólnej kategorii.

### Problem #2: AI stworzył mapowania bankCategory jako nazwy sklepów

```
Category Mappings created:
   ZABKA      → Zakupy spożywcze    ❌ ALE "ZABKA" to NAZWA, nie bankCategory!
   ALLEGRO    → Zakupy              ❌
   BADOO      → Inne wydatki        ❌
   BIEDRONKA  → Zakupy spożywcze    ❌
```

**Problem:** AI pomylił pola - stworzył mapowania gdzie `bankCategoryName` = "ZABKA",
ale w danych `bankCategory` = "TRANSAKCJA KARTĄ PŁATNICZĄ", a "ZABKA" jest w polu `name`.

### Problem #3: Tylko 4 pattern mappings zostały utworzone

```
Pattern Mappings created:
   ZABKA      → Zakupy spożywcze
   ALLEGRO    → Zakupy
   BADOO      → Inne wydatki
   BIEDRONKA  → Zakupy spożywcze
```

**Problem:** Zaledwie 4 pattern mappings dla 45 unikalnych wzorców!
Brakuje mappingów dla NETFLIX, ORLEN, FITNESS, ROSSMANN, itp.

### Problem #4: BADOO jest w description, nie w name

```json
{
  "name": "BANK PEKAO S.A.",
  "description": "ROZLICZENIE TRANSAKCJI... Badoo help@badoo.com...",
  "bankCategory": "PRZELEW"
}
```

**Problem:** 42 transakcje BADOO mają:
- `name` = "BANK PEKAO S.A." (nazwa banku pośredniczącego)
- `description` = zawiera "Badoo help@badoo.com"

System normalizuje tylko `name`, nie analizuje `description`.

### Problem #5: Normalizer nie rozpoznał wielu wzorców

```java
KNOWN_SINGLE_WORD_PATTERNS = Set.of(
    "NETFLIX", "SPOTIFY", "ORLEN", ...
)
```

**Ale:** Normalizer sprawdza tylko czy `name.startsWith(PATTERN)`.

Dla transakcji `"XTREME FITNESS GYMS MI MIELEC"`:
- "XTREME" nie jest w `KNOWN_SINGLE_WORD_PATTERNS`
- Więc wynik = "XTREME FITNESS GYMS MI" (po usunięciu miasta)

---

## 4. STATYSTYKI PROBLEMATYCZNYCH TRANSAKCJI

### Transakcje które POWINNY być skategoryzowane:

| Pattern w name/description | Ilość w Uncategorized | Powinna być kategoria |
|---------------------------|----------------------|----------------------|
| BADOO (w description) | 42 | Subskrypcje |
| FITNESS/GYM/ZDROFIT/XTREME | 38 | Siłownia |
| ING (false positive) | 19 | - |
| NETFLIX | 12 | Subskrypcje |
| PLUS | 12 | Telekomunikacja |
| ROSSMANN | 8 | Drogeria |
| KEBAB/MCDONALDS | 11 | Jedzenie na mieście |
| ORLEN/BP/MOL/STACJA | 16 | Paliwo |
| APTEKA | 3 | Zdrowie |
| EMPIK/CCC | 4 | Zakupy |
| BANK PEKAO | 87 | - (pośrednik) |
| **UNKNOWN** | 310 | Wymaga analizy |

---

## 5. ROOT CAUSE ANALYSIS

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         ROOT CAUSES                                           │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  1. CONFUSION BETWEEN bankCategory AND name                                  │
│     ├─ AI stworzył "category mappings" z nazwami sklepów jako bankCategory   │
│     ├─ ALE w danych bankCategory = "TRANSAKCJA KARTĄ PŁATNICZĄ"              │
│     └─ Mapowania nie zadziałały bo nie ma takiej bankCategory                │
│                                                                               │
│  2. INSUFFICIENT PATTERN MAPPINGS                                            │
│     ├─ Tylko 4 pattern mappings utworzone                                    │
│     ├─ Brakuje: NETFLIX, ORLEN, FITNESS, ROSSMANN, PLUS, etc.               │
│     └─ AI nie zwrócił wszystkich wymaganych mappingów                       │
│                                                                               │
│  3. DESCRIPTION NOT ANALYZED                                                 │
│     ├─ Normalizacja bazuje tylko na polu "name"                             │
│     ├─ BADOO jest w "description", nie w "name"                             │
│     └─ 42 transakcje BADOO zostały pominięte                                │
│                                                                               │
│  4. NORMALIZER MISSING PATTERNS                                              │
│     ├─ XTREME, ZDROFIT, CLAUDE nie są w KNOWN_SINGLE_WORD_PATTERNS          │
│     └─ Normalizacja zostawia zbyt długie stringi                            │
│                                                                               │
│  5. BANK AS INTERMEDIARY                                                     │
│     ├─ 87 transakcji ma name = "BANK PEKAO S.A."                            │
│     ├─ Prawdziwy merchant jest ukryty w description                         │
│     └─ System nie parsuje description do identyfikacji merchantów           │
│                                                                               │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. STRATEGIE NAPRAWY

### Strategia A: Rozszerz analizę description (Quick Win)

```java
// W PatternDeduplicator lub AiCategorizationService
public String extractMerchantFromDescription(String description) {
    // Pattern for card transactions through bank
    // "ROZLICZENIE TRANSAKCJI... Badoo help@badoo.com..."
    // "... WYKONANEJ: Netflix.com DN. 03/01/2026"

    Pattern merchantPattern = Pattern.compile(
        "(?:WYKONANEJ:|:)\\s*([A-Za-z0-9.]+(?:\\s+[A-Za-z0-9.]+)?)"
    );
    // ...
}
```

**Effort:** 2-3h
**Impact:** +42 BADOO, +inne transakcje przez bank

### Strategia B: Ulepsz TransactionNameNormalizer

```java
// Dodaj brakujące wzorce:
KNOWN_SINGLE_WORD_PATTERNS.addAll(Set.of(
    "XTREME", "ZDROFIT", "CLAUDE", "TRADINGVIEW",
    "JUNONA", "SHIVAGO", "INTERMARCHE", "MOL"
));

// Dodaj fitness patterns:
KNOWN_TWO_WORD_PATTERNS.addAll(Set.of(
    "XTREME FITNESS", "ZDROFIT OCHOTA", "ZDROFIT WLOCHY"
));
```

**Effort:** 1h
**Impact:** +38 FITNESS, +12 NETFLIX (już działa), +inne

### Strategia C: Napraw logikę category_mappings

```java
// Problem: AI tworzy category_mappings z nazwami sklepów jako bankCategory
// Rozwiązanie: Waliduj że bankCategoryName faktycznie istnieje w danych

public void validateCategoryMappings(List<CategoryMapping> mappings,
                                      Set<String> actualBankCategories) {
    mappings.removeIf(m -> !actualBankCategories.contains(m.getBankCategoryName()));
}
```

**Effort:** 2h
**Impact:** Zapobiega tworzeniu bezużytecznych mappingów

### Strategia D: AI powinien tworzyć WIĘCEJ pattern mappings

**Zmiana w prompt:**

```
CRITICAL: Create pattern mappings for EVERY unique pattern you see.
Current issue: Only 4 mappings created for 45 patterns = 91% uncategorized!

You MUST create a patternMapping for:
- NETFLIX → Subskrypcje
- ORLEN → Paliwo
- ROSSMANN → Drogeria
- etc.

DO NOT rely on bankCategory mappings - they are too generic.
```

**Effort:** 1h (prompt change)
**Impact:** Główna poprawa jakości

### Strategia E: Fallback pattern matching (description-based)

```java
// Gdy name = "BANK PEKAO S.A." i description zawiera merchant
public Optional<String> fallbackMerchantExtraction(StagedTransaction tx) {
    if (tx.getName().contains("BANK") && tx.getDescription() != null) {
        // Extract merchant from description patterns
        return extractMerchantFromDescription(tx.getDescription());
    }
    return Optional.empty();
}
```

**Effort:** 3h
**Impact:** +87 transakcji BANK PEKAO + inne banki-pośrednicy

---

## 7. REKOMENDACJA IMPLEMENTACJI

### Faza 1: Quick Wins (4h)

1. **Dodaj brakujące wzorce do Normalizer** - 1h
   - XTREME, ZDROFIT, CLAUDE, MOL, etc.

2. **Zmień prompt AI** - 1h
   - Wymuszaj tworzenie pattern mappings dla wszystkich wzorców
   - Jasno komunikuj że bankCategory jest generic

3. **Walidacja category_mappings** - 2h
   - Nie twórz mappingów dla nieistniejących bankCategory

### Faza 2: Głębsze zmiany (6h)

4. **Parsowanie description** - 3h
   - Ekstrakcja merchant z description dla transakcji bankowych

5. **Fallback dla BANK jako name** - 3h
   - Specjalna logika dla transakcji gdzie bank jest pośrednikiem

### Oczekiwany wynik:

| Metryka | Przed | Po Fazie 1 | Po Fazie 2 |
|---------|-------|------------|------------|
| Uncategorized | 68% (539) | ~30% (~240) | ~10% (~80) |
| Auto-kategoryzowane | 32% (252) | ~70% (~550) | ~90% (~710) |

---

## 8. APPENDIX: RAW DATA

### A. Category Mappings (10 total, ale 6 niepoprawnych):

```
CORRECT (matching actual bankCategory):
- PRZELEW INTERNET → Opłaty obowiązkowe
- PROWIZJE AUT. → Inne wydatki
- PŁATNOŚĆ BLIK → Inne wydatki
- WYPŁATA BLIK → Inne wydatki
- PRZELEW BLIK WYCHODZĄCY → Inne wydatki
- PRZELEW KRAJOWY MIĘDZYBANKOWY → Inne przychody

INCORRECT (bankCategoryName = name, not actual bankCategory):
- ZABKA → Zakupy spożywcze (BŁĄD: ZABKA to name, nie bankCategory)
- ALLEGRO → Zakupy (BŁĄD)
- BADOO → Inne wydatki (BŁĄD)
- BIEDRONKA → Zakupy spożywcze (BŁĄD)
```

### B. Pattern Mappings (tylko 4!):

```
ZABKA → Zakupy spożywcze (intendedParent: Żywność)
ALLEGRO → Zakupy (intendedParent: Zakupy)
BADOO → Inne wydatki (intendedParent: Inne wydatki)
BIEDRONKA → Zakupy spożywcze (intendedParent: Żywność)
```

### C. Actual bankCategory distribution:

```
TRANSAKCJA KARTĄ PŁATNICZĄ: 489 (62%)
PRZELEW: 88 (11%)
PŁATNOŚĆ BLIK: 72 (9%)
PRZELEW INTERNET M/B: 31 (4%)
OBCIĄŻENIE Z TYTUŁU POLECENIA ZAPŁATY: 23 (3%)
PRZELEW KRAJOWY MIĘDZYBANKOWY: 16 (2%)
PRZELEW EXPRESS ELIXIR: 13 (2%)
WYPŁATA BLIK: 12 (1.5%)
PRZELEW INTERNET: 11 (1.4%)
PRZELEW BLIK WYCHODZĄCY: 10 (1.3%)
... inne: 26 (3%)
```
