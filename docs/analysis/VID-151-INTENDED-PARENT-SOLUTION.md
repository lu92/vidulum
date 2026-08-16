# VID-151: intendedParentCategory - Kompletna Analiza Rozwiązania

**Data utworzenia:** 2026-04-12
**Status:** NOT IMPLEMENTED (Analiza i Design)
**Powiązane:** `docs/VID-151-PHASE2-IMPLEMENTATION-PLAN.md`, `docs/analysis/VID-151-BACKLOG.md`

---

## Spis Treści

1. [Problem do rozwiązania](#1-problem-do-rozwiązania)
2. [Obecny stan (bez intendedParentCategory)](#2-obecny-stan-bez-intendedparentcategory)
3. [Problem przy kolejnych importach](#3-problem-przy-kolejnych-importach)
4. [Rozwiązanie: intendedParentCategory](#4-rozwiązanie-intendedparentcategory)
5. [Pełny diagram przepływu](#5-pełny-diagram-przepływu)
6. [Przykład z życia - Transport](#6-przykład-z-życia---transport)
7. [Wpływ na transakcje i forecast](#7-wpływ-na-transakcje-i-forecast)
8. [Event Flow przy przesuwaniu kategorii](#8-event-flow-przy-przesuwaniu-kategorii)
9. [Przypadek specjalny: Rename Category](#9-przypadek-specjalny-rename-category)
10. [Podsumowanie zmian w kodzie](#10-podsumowanie-zmian-w-kodzie)
11. [Matryca operacji](#11-matryca-operacji)
12. [Ewolucja PatternMapping przy zmianach struktury](#12-ewolucja-patternmapping-przy-zmianach-struktury)

---

## 1. Problem do rozwiązania

Kiedy AI sugeruje hierarchię kategorii, np. `Jedzenie > Zakupy spożywcze`, ale ta hierarchia zostaje spłaszczona (bo parent miał tylko jedno dziecko), **tracimy informację o intencji AI**.

Przy kolejnym imporcie AI nie wie, że `Zakupy spożywcze` miało być pod `Jedzenie`, więc nie może zasugerować sensownego grupowania z nowymi kategoriami z tej samej domeny (np. `Restauracje`).

---

## 2. Obecny stan (bez intendedParentCategory)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         PIERWSZY IMPORT CSV                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  AI sugeruje:                      Po spłaszczeniu (single-child):          │
│  ┌─────────────────────┐           ┌─────────────────────┐                  │
│  │ OUTFLOW             │           │ OUTFLOW             │                  │
│  │ ├── Jedzenie        │    →      │ ├── Zakupy spożywcze│  ← parent       │
│  │ │   └── Zakupy      │           │                     │    usunięty!    │
│  │ │       spożywcze   │           └─────────────────────┘                  │
│  └─────────────────────┘                                                    │
│                                                                              │
│  PatternMapping zapisany:                                                    │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ pattern: "BIEDRONKA"                                                  │   │
│  │ targetCategory: "Zakupy spożywcze"                                    │   │
│  │ categoryType: OUTFLOW                                                 │   │
│  │ intendedParentCategory: null  ← BRAK INFORMACJI O INTENCJI!          │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Problem przy kolejnych importach

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DRUGI IMPORT CSV                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Nowe transakcje zawierają:                                                  │
│  - BIEDRONKA (istniejący pattern)                                           │
│  - MCDONALDS (nowy pattern - restauracje)                                   │
│  - KFC (nowy pattern - restauracje)                                         │
│                                                                              │
│  AI widzi w CACHE:                        AI NIE WIE że:                    │
│  ┌────────────────────────────────┐      ┌─────────────────────────────┐   │
│  │ pattern: "BIEDRONKA"           │      │ "Zakupy spożywcze" miało    │   │
│  │ targetCategory: "Zakupy        │      │ być pod "Jedzenie"!         │   │
│  │              spożywcze"        │      │                             │   │
│  │ intendedParentCategory: null   │      │ Nie może zasugerować        │   │
│  └────────────────────────────────┘      │ grupowania z MCDONALDS/KFC  │   │
│                                          └─────────────────────────────┘   │
│                                                                              │
│  AI sugeruje (bez kontekstu):              Idealne byłoby:                  │
│  ┌─────────────────────────────┐          ┌─────────────────────────────┐  │
│  │ OUTFLOW                     │          │ OUTFLOW                     │  │
│  │ ├── Zakupy spożywcze        │          │ ├── Jedzenie                │  │
│  │ ├── Restauracje             │    vs    │ │   ├── Zakupy spożywcze   │  │
│  │ │   ├── MCDONALDS           │          │ │   └── Restauracje        │  │
│  │ │   └── KFC                 │          │ │       ├── MCDONALDS      │  │
│  └─────────────────────────────┘          │ │       └── KFC            │  │
│                                           └─────────────────────────────┘  │
│  ^ Brak spójności! Zakupy spożywcze       ^ Spójna hierarchia!             │
│    i Restauracje to oba "Jedzenie"                                          │
│    ale AI tego nie wie                                                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Rozwiązanie: intendedParentCategory

### 4.1 Nowa struktura PatternMapping

```java
// PRZED (obecny stan)
public record PatternMapping(
    PatternMappingId patternMappingId,
    UserId userId,
    String pattern,
    String targetCategory,
    Type categoryType,
    Instant createdAt,
    Instant lastUsedAt,
    int usageCount
) {}

// PO (z Phase 2)
public record PatternMapping(
    PatternMappingId patternMappingId,
    UserId userId,
    String pattern,
    String targetCategory,
    Type categoryType,
    String intendedParentCategory,  // ← NOWE POLE
    Instant createdAt,
    Instant lastUsedAt,
    int usageCount
) {}
```

### 4.2 Jak to działa - krok po kroku

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    PIERWSZY IMPORT - Z intendedParentCategory                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. AI sugeruje hierarchię:                                                  │
│     ┌─────────────────────────────────────────────────────────────────┐     │
│     │ {                                                                │     │
│     │   "suggestedStructure": {                                        │     │
│     │     "outflow": [                                                 │     │
│     │       {"name": "Jedzenie", "subCategories": ["Zakupy spożywcze"]}│     │
│     │     ]                                                            │     │
│     │   },                                                             │     │
│     │   "patternSuggestions": [                                        │     │
│     │     {                                                            │     │
│     │       "pattern": "BIEDRONKA",                                    │     │
│     │       "category": "Zakupy spożywcze",                            │     │
│     │       "parentCategory": "Jedzenie",  ← AI sugeruje parenta      │     │
│     │       "type": "OUTFLOW"                                          │     │
│     │     }                                                            │     │
│     │   ]                                                              │     │
│     │ }                                                                │     │
│     └─────────────────────────────────────────────────────────────────┘     │
│                                                                              │
│  2. System spłaszcza single-child hierarchy:                                 │
│     Jedzenie > Zakupy spożywcze  →  Zakupy spożywcze (top-level)            │
│                                                                              │
│  3. ALE zapisujemy intendedParentCategory w PatternMapping:                  │
│     ┌─────────────────────────────────────────────────────────────────┐     │
│     │ PatternMapping {                                                 │     │
│     │   pattern: "BIEDRONKA",                                          │     │
│     │   targetCategory: "Zakupy spożywcze",                            │     │
│     │   categoryType: OUTFLOW,                                         │     │
│     │   intendedParentCategory: "Jedzenie"  ← ZACHOWANA INTENCJA!     │     │
│     │ }                                                                │     │
│     └─────────────────────────────────────────────────────────────────┘     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.3 Drugi import - AI ma kontekst

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    DRUGI IMPORT - AI używa intendedParentCategory            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. AI otrzymuje w PROMPT sekcję CACHED PATTERN INTENTS:                     │
│                                                                              │
│     ┌─────────────────────────────────────────────────────────────────┐     │
│     │ === CACHED PATTERN INTENTS ===                                   │     │
│     │ These patterns were previously categorized with intended         │     │
│     │ hierarchy. Consider grouping related new categories:             │     │
│     │                                                                  │     │
│     │ Pattern: BIEDRONKA                                               │     │
│     │   Current category: Zakupy spożywcze                             │     │
│     │   Intended parent: Jedzenie                                      │     │
│     │   Type: OUTFLOW                                                  │     │
│     │                                                                  │     │
│     │ If you see new patterns related to "Jedzenie" domain            │     │
│     │ (like restaurants, food delivery), consider:                     │     │
│     │ 1. Creating "Jedzenie" as parent                                 │     │
│     │ 2. Moving "Zakupy spożywcze" under it                           │     │
│     │ 3. Adding new food-related categories under "Jedzenie"          │     │
│     └─────────────────────────────────────────────────────────────────┘     │
│                                                                              │
│  2. AI widzi nowe transakcje MCDONALDS, KFC i TERAZ WIE:                    │
│     - "Zakupy spożywcze" miało być pod "Jedzenie"                           │
│     - MCDONALDS, KFC to też domena "Jedzenie"                               │
│     - Można teraz sensownie zgrupować!                                      │
│                                                                              │
│  3. AI sugeruje strukturę + structureOptimizations:                         │
│     ┌─────────────────────────────────────────────────────────────────┐     │
│     │ {                                                                │     │
│     │   "suggestedStructure": {                                        │     │
│     │     "outflow": [                                                 │     │
│     │       {                                                          │     │
│     │         "name": "Jedzenie",                                      │     │
│     │         "subCategories": ["Zakupy spożywcze", "Restauracje"]    │     │
│     │       }                                                          │     │
│     │     ]                                                            │     │
│     │   },                                                             │     │
│     │   "structureOptimizations": [                                    │     │
│     │     {                                                            │     │
│     │       "categoryName": "Zakupy spożywcze",                        │     │
│     │       "currentParent": null,                                     │     │
│     │       "suggestedParent": "Jedzenie",                             │     │
│     │       "action": "MOVE_TO_PARENT",                                │     │
│     │       "reason": "Restore intended hierarchy from previous import"│     │
│     │     }                                                            │     │
│     │   ]                                                              │     │
│     │ }                                                                │     │
│     └─────────────────────────────────────────────────────────────────┘     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Pełny diagram przepływu

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              IMPORT #1                                       │
│                                                                              │
│  CSV: BIEDRONKA, LIDL, ŻABKA                                                │
│           │                                                                  │
│           ▼                                                                  │
│  ┌─────────────────┐                                                        │
│  │  AI Categorize  │                                                        │
│  └────────┬────────┘                                                        │
│           │ suggeruje: Jedzenie > Zakupy spożywcze                          │
│           ▼                                                                  │
│  ┌─────────────────┐                                                        │
│  │  Flatten        │ (single-child)                                         │
│  │  Single-Child   │                                                        │
│  └────────┬────────┘                                                        │
│           │ wynik: Zakupy spożywcze (top-level)                             │
│           ▼                                                                  │
│  ┌─────────────────┐                                                        │
│  │  Accept AI      │                                                        │
│  │  Suggestions    │                                                        │
│  └────────┬────────┘                                                        │
│           │                                                                  │
│           ▼                                                                  │
│  ┌─────────────────────────────────────────────────────┐                    │
│  │  PatternMapping (MongoDB)                           │                    │
│  │  ┌───────────────────────────────────────────────┐  │                    │
│  │  │ pattern: "BIEDRONKA"                          │  │                    │
│  │  │ targetCategory: "Zakupy spożywcze"            │  │                    │
│  │  │ intendedParentCategory: "Jedzenie" ← SAVED!  │  │                    │
│  │  └───────────────────────────────────────────────┘  │                    │
│  └─────────────────────────────────────────────────────┘                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ 1 miesiąc później...
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              IMPORT #2                                       │
│                                                                              │
│  CSV: BIEDRONKA, MCDONALDS, KFC, UBER EATS                                  │
│           │                                                                  │
│           ▼                                                                  │
│  ┌─────────────────┐      ┌──────────────────────────────────────┐          │
│  │  Load Cached    │──────│ PatternMappings z intendedParent     │          │
│  │  Patterns       │      │ → BIEDRONKA chciał być pod "Jedzenie"│          │
│  └────────┬────────┘      └──────────────────────────────────────┘          │
│           │                                                                  │
│           ▼                                                                  │
│  ┌─────────────────┐      ┌──────────────────────────────────────┐          │
│  │  Build Prompt   │──────│ CACHED PATTERN INTENTS section       │          │
│  │  with Intents   │      │ informuje AI o intencjach            │          │
│  └────────┬────────┘      └──────────────────────────────────────┘          │
│           │                                                                  │
│           ▼                                                                  │
│  ┌─────────────────┐                                                        │
│  │  AI Categorize  │ ← AI WIE o intencji "Jedzenie"!                       │
│  └────────┬────────┘                                                        │
│           │                                                                  │
│           ▼                                                                  │
│  ┌─────────────────────────────────────────────────────────────────┐        │
│  │  AI Response:                                                    │        │
│  │  {                                                               │        │
│  │    "suggestedStructure": {                                       │        │
│  │      "outflow": [{                                               │        │
│  │        "name": "Jedzenie",                                       │        │
│  │        "subCategories": ["Zakupy spożywcze", "Restauracje"]     │        │
│  │      }]                                                          │        │
│  │    },                                                            │        │
│  │    "structureOptimizations": [{                                  │        │
│  │      "categoryName": "Zakupy spożywcze",                         │        │
│  │      "suggestedParent": "Jedzenie",                              │        │
│  │      "action": "MOVE_TO_PARENT"                                  │        │
│  │    }]                                                            │        │
│  │  }                                                               │        │
│  └─────────────────────────────────────────────────────────────────┘        │
│           │                                                                  │
│           ▼                                                                  │
│  ┌─────────────────────────────────────────────────────────────────┐        │
│  │  WYNIK KOŃCOWY:                                                  │        │
│  │                                                                  │        │
│  │  OUTFLOW                                                         │        │
│  │  └── Jedzenie                                                    │        │
│  │      ├── Zakupy spożywcze (BIEDRONKA, LIDL, ŻABKA)             │        │
│  │      └── Restauracje (MCDONALDS, KFC, UBER EATS)                │        │
│  │                                                                  │        │
│  │  ^ SPÓJNA HIERARCHIA!                                           │        │
│  └─────────────────────────────────────────────────────────────────┘        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Przykład z życia - Transport

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  SCENARIUSZ: Użytkownik importuje wyciągi przez kilka miesięcy              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  STYCZEŃ - tylko Bolt:                                                       │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │ AI sugeruje: Transport > Taxi                                  │         │
│  │ Spłaszczone do: Taxi (single-child)                           │         │
│  │ PatternMapping: BOLT → Taxi (intendedParent: "Transport")     │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  LUTY - Bolt + ZTM:                                                         │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │ AI widzi: BOLT miał być pod "Transport"                        │         │
│  │ Nowy pattern: ZTM (komunikacja miejska)                        │         │
│  │                                                                 │         │
│  │ AI sugeruje structureOptimization:                             │         │
│  │ - Utwórz "Transport"                                           │         │
│  │ - Przenieś "Taxi" pod "Transport"                              │         │
│  │ - Dodaj "Komunikacja miejska" pod "Transport"                  │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  MARZEC - Bolt + ZTM + PKP:                                                 │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │ Struktura już istnieje:                                        │         │
│  │ Transport                                                       │         │
│  │ ├── Taxi (BOLT)                                                │         │
│  │ └── Komunikacja miejska (ZTM)                                  │         │
│  │                                                                 │         │
│  │ AI dodaje: Transport > Kolej (PKP)                             │         │
│  │                                                                 │         │
│  │ WYNIK:                                                          │         │
│  │ Transport                                                       │         │
│  │ ├── Taxi                                                        │         │
│  │ ├── Komunikacja miejska                                         │         │
│  │ └── Kolej                                                       │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Wpływ na transakcje i forecast

### 7.1 Kluczowa zasada architektury

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    KLUCZOWA ZASADA ARCHITEKTURY                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  CashChange (transakcja) przechowuje TYLKO:                                 │
│  ┌─────────────────────────────────────────────────────────────────┐        │
│  │  {                                                               │        │
│  │    "cashChangeId": "CC123",                                      │        │
│  │    "name": "BIEDRONKA WARSZAWA",                                 │        │
│  │    "money": {"amount": -150.00, "currency": "PLN"},             │        │
│  │    "category": "Zakupy spożywcze",  ← TYLKO NAZWA (String)      │        │
│  │    "type": "OUTFLOW",                                            │        │
│  │    "paidDate": "2026-03-15"                                      │        │
│  │  }                                                               │        │
│  └─────────────────────────────────────────────────────────────────┘        │
│                                                                              │
│  NIE przechowuje:                                                            │
│  ✗ parentCategory                                                            │
│  ✗ categoryId                                                                │
│  ✗ pełnej ścieżki hierarchii                                                │
│  ✗ referencji do Category entity                                            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 7.2 Co się dzieje przy przesuwaniu kategorii?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SCENARIUSZ: Przesunięcie kategorii                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PRZED przesunięciem:                    PO przesunięciu:                   │
│                                                                              │
│  CashFlow Structure:                     CashFlow Structure:                │
│  ┌─────────────────────────┐            ┌─────────────────────────┐        │
│  │ OUTFLOW                 │            │ OUTFLOW                 │        │
│  │ ├── Zakupy spożywcze    │     →      │ ├── Jedzenie            │        │
│  │ └── Restauracje         │            │ │   ├── Zakupy spożywcze│        │
│  └─────────────────────────┘            │ │   └── Restauracje     │        │
│                                         └─────────────────────────┘        │
│                                                                              │
│  Transakcje (CashChange):                Transakcje (CashChange):           │
│  ┌─────────────────────────┐            ┌─────────────────────────┐        │
│  │ BIEDRONKA               │            │ BIEDRONKA               │        │
│  │ category: "Zakupy       │     →      │ category: "Zakupy       │        │
│  │           spożywcze"    │            │           spożywcze"    │        │
│  └─────────────────────────┘            └─────────────────────────┘        │
│                                                                              │
│  ^ BEZ ZMIAN!                            ^ BEZ ZMIAN!                       │
│                                                                              │
│  Transakcja nadal ma category="Zakupy spożywcze"                            │
│  Hierarchia jest DYNAMICZNIE obliczana z CashFlow.structure                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 7.3 Jak hierarchia jest obliczana dynamicznie

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    POBIERANIE TRANSAKCJI Z HIERARCHIĄ                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. Pobierz CashFlow (zawiera strukturę kategorii):                         │
│     ┌─────────────────────────────────────────────────────────────────┐     │
│     │ cashFlow.getOutflowCategories():                                 │     │
│     │ [                                                                │     │
│     │   {                                                              │     │
│     │     "name": "Jedzenie",                                          │     │
│     │     "subCategories": [                                           │     │
│     │       {"name": "Zakupy spożywcze", "subCategories": []},        │     │
│     │       {"name": "Restauracje", "subCategories": []}              │     │
│     │     ]                                                            │     │
│     │   }                                                              │     │
│     │ ]                                                                │     │
│     └─────────────────────────────────────────────────────────────────┘     │
│                                                                              │
│  2. Dla transakcji category="Zakupy spożywcze" oblicz hierarchię:           │
│     ┌─────────────────────────────────────────────────────────────────┐     │
│     │ findCategoryPath("Zakupy spożywcze", outflowCategories)         │     │
│     │ → ["Jedzenie", "Zakupy spożywcze"]                              │     │
│     └─────────────────────────────────────────────────────────────────┘     │
│                                                                              │
│  3. UI wyświetla:                                                            │
│     ┌─────────────────────────────────────────────────────────────────┐     │
│     │ BIEDRONKA WARSZAWA                                               │     │
│     │ -150.00 PLN                                                      │     │
│     │ Kategoria: Jedzenie > Zakupy spożywcze                          │     │
│     └─────────────────────────────────────────────────────────────────┘     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 7.4 CashFlow Forecast - struktura

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CASHFLOW FORECAST - STRUKTURA                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  CashFlowForecast też używa categoryName (String):                          │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │ CashFlowForecast {                                                   │    │
│  │   cashFlowId: "CF123",                                               │    │
│  │   periods: [                                                         │    │
│  │     {                                                                │    │
│  │       period: "2026-03",                                             │    │
│  │       categorizedOutFlows: [                                         │    │
│  │         {                                                            │    │
│  │           categoryName: "Zakupy spożywcze",  ← TYLKO NAZWA          │    │
│  │           total: {amount: -1500.00, currency: "PLN"},               │    │
│  │           items: [...]                                               │    │
│  │         }                                                            │    │
│  │       ]                                                              │    │
│  │     }                                                                │    │
│  │   ],                                                                 │    │
│  │   categoryStructure: {                                               │    │
│  │     outflowCategoryStructure: [                                      │    │
│  │       {                                                              │    │
│  │         name: "Jedzenie",                                            │    │
│  │         subCategories: ["Zakupy spożywcze", "Restauracje"]          │    │
│  │       }                                                              │    │
│  │     ]                                                                │    │
│  │   }                                                                  │    │
│  │ }                                                                    │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  categoryStructure jest KOPIĄ z CashFlow w momencie generowania forecast!   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 7.5 Scenariusz: Przesunięcie kategorii a Forecast

```
┌─────────────────────────────────────────────────────────────────────────────┐
│         CO SIĘ DZIEJE GDY UŻYTKOWNIK PRZESUWA KATEGORIĘ?                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. User przesuwa "Zakupy spożywcze" pod "Jedzenie":                        │
│     POST /cash-flow/cf=CF123/move-category                                  │
│     {                                                                        │
│       "categoryName": "Zakupy spożywcze",                                   │
│       "newParentCategoryName": "Jedzenie",                                  │
│       "categoryType": "OUTFLOW"                                             │
│     }                                                                        │
│                                                                              │
│  2. CashFlow Aggregate aktualizuje strukturę:                               │
│     ┌─────────────────────────────────────────────────────────────────┐     │
│     │ CashFlow.moveCategory("Zakupy spożywcze", "Jedzenie", OUTFLOW)  │     │
│     │   → Emituje: CategoryMovedEvent                                  │     │
│     └─────────────────────────────────────────────────────────────────┘     │
│                                                                              │
│  3. Transakcje (CashChange) - BEZ ZMIAN:                                    │
│     ┌─────────────────────────────────────────────────────────────────┐     │
│     │ CashChange.category = "Zakupy spożywcze"  ← NADAL TO SAMO!      │     │
│     └─────────────────────────────────────────────────────────────────┘     │
│                                                                              │
│  4. Forecast - CategoryMovedEvent triggeruje REGENERACJĘ:                   │
│     ┌─────────────────────────────────────────────────────────────────┐     │
│     │ CategoryMovedEventHandler                                        │     │
│     │   → Pobiera nową strukturę z CashFlow                           │     │
│     │   → Regeneruje CashFlowForecast z nową categoryStructure        │     │
│     └─────────────────────────────────────────────────────────────────┘     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Event Flow przy przesuwaniu kategorii

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         EVENT FLOW                                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  User: "Przesuń Zakupy spożywcze pod Jedzenie"                              │
│           │                                                                  │
│           ▼                                                                  │
│  ┌─────────────────────────┐                                                │
│  │  MoveCategoryCommand    │                                                │
│  │  Handler                │                                                │
│  └───────────┬─────────────┘                                                │
│              │                                                               │
│              ▼                                                               │
│  ┌─────────────────────────┐                                                │
│  │  CashFlow Aggregate     │                                                │
│  │  .moveCategory()        │                                                │
│  └───────────┬─────────────┘                                                │
│              │                                                               │
│              ├──────────────────────────────────────┐                       │
│              │                                      │                       │
│              ▼                                      ▼                       │
│  ┌─────────────────────────┐           ┌─────────────────────────┐         │
│  │  MongoDB: CashFlow      │           │  Kafka:                 │         │
│  │  (struktura zmieniona)  │           │  CategoryMovedEvent     │         │
│  └─────────────────────────┘           └───────────┬─────────────┘         │
│                                                    │                        │
│                                                    ▼                        │
│                                        ┌─────────────────────────┐         │
│                                        │  CashFlowEventListener  │         │
│                                        └───────────┬─────────────┘         │
│                                                    │                        │
│                                                    ▼                        │
│                                        ┌─────────────────────────┐         │
│                                        │  CategoryMovedEvent     │         │
│                                        │  Handler                │         │
│                                        └───────────┬─────────────┘         │
│                                                    │                        │
│                                                    ▼                        │
│                                        ┌─────────────────────────┐         │
│                                        │  CashFlowForecast       │         │
│                                        │  Processor              │         │
│                                        │  (regeneracja)          │         │
│                                        └───────────┬─────────────┘         │
│                                                    │                        │
│                                                    ▼                        │
│                                        ┌─────────────────────────┐         │
│                                        │  MongoDB: Forecast      │         │
│                                        │  (nowa struktura)       │         │
│                                        └─────────────────────────┘         │
│                                                                              │
│  TRANSAKCJE (CashChange) - NIGDZIE NIE SĄ MODYFIKOWANE!                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 9. Przypadek specjalny: Rename Category

To jest jedyny przypadek wymagający aktualizacji transakcji:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    RENAME CATEGORY (inny przypadek!)                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  User: "Zmień nazwę 'Zakupy spożywcze' na 'Groceries'"                      │
│                                                                              │
│  WYMAGA aktualizacji:                                                        │
│  ┌─────────────────────────────────────────────────────────────────┐        │
│  │ 1. CashFlow.structure - zmiana nazwy kategorii                  │        │
│  │ 2. Wszystkie CashChange gdzie category="Zakupy spożywcze"       │        │
│  │    → category="Groceries"                                        │        │
│  │ 3. PatternMapping gdzie targetCategory="Zakupy spożywcze"       │        │
│  │    → targetCategory="Groceries"                                  │        │
│  │ 4. CategoryMapping gdzie targetCategory="Zakupy spożywcze"      │        │
│  │    → targetCategory="Groceries"                                  │        │
│  │ 5. Regeneracja Forecast                                          │        │
│  └─────────────────────────────────────────────────────────────────┘        │
│                                                                              │
│  ALE TO JEST OSOBNA OPERACJA - RenameCategoryCommand                        │
│  (nie jest częścią Phase 2 / Structure Optimizations)                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 10. Podsumowanie zmian w kodzie

| Plik | Zmiana |
|------|--------|
| `PatternMapping.java` | Dodanie pola `String intendedParentCategory` |
| `PatternMappingEntity.java` | Dodanie pola + `fromDomain()`/`toDomain()` |
| `AcceptAiSuggestionsCommandHandler.java` | Zapisywanie `parentCategory` jako `intendedParentCategory` |
| `AiCategorizationService.java` | Metoda `getPatternIntentsForPrompt()` |
| `AiCategorizationPromptBuilder.java` | Sekcja `CACHED PATTERN INTENTS` w prompcie |
| `AiCategorizationResult.java` | Dodanie `List<StructureOptimization>` |
| `AiCategorizationResponseParser.java` | Parsowanie `structureOptimizations` z JSON |
| `BankDataIngestionRestController.java` | Zwracanie `structureOptimizations` do UI |

### Szacowany nakład pracy

| Zadanie | Estymacja |
|---------|-----------|
| PatternMapping + Entity | 1h |
| AcceptAiSuggestionsCommandHandler | 1h |
| AiCategorizationService | 1h |
| AiCategorizationPromptBuilder | 1-2h |
| AiCategorizationResult + Parser | 2h |
| REST Controller | 0.5h |
| Testy jednostkowe | 2h |
| Testy integracyjne | 2h |
| **Razem** | **10-12h** |

---

## 11. Matryca operacji

| Operacja | Transakcje (CashChange) | Forecast | PatternMapping |
|----------|------------------------|----------|----------------|
| **MOVE** (przesunięcie pod innego parenta) | BEZ ZMIAN | Regeneracja struktury | BEZ ZMIAN |
| **DELETE_EMPTY** (usunięcie pustej kategorii) | BEZ ZMIAN | Regeneracja struktury | BEZ ZMIAN |
| **RENAME** (zmiana nazwy) | Aktualizacja category | Regeneracja | Aktualizacja targetCategory |

**Kluczowa zasada**: Transakcje przechowują tylko `categoryName` (String). Hierarchia jest obliczana dynamicznie z `CashFlow.structure`. Dzięki temu przesuwanie kategorii jest **darmowe** - nie wymaga aktualizacji setek/tysięcy transakcji!

---

## Korzyści rozwiązania

1. **Spójność hierarchii** - AI pamięta zamierzoną strukturę nawet jeśli została spłaszczona
2. **Inteligentne grupowanie** - przy kolejnych importach AI może zasugerować przywrócenie hierarchii
3. **Lepsze UX** - użytkownik widzi sugestie `structureOptimizations` i może je zaakceptować
4. **Ewolucja kategorii** - struktura "rośnie" naturalnie w miarę importowania kolejnych danych
5. **Wydajność** - przesuwanie kategorii nie wymaga aktualizacji transakcji (tylko struktury)

---

## 12. Ewolucja PatternMapping przy zmianach struktury

### 12.1 Kluczowa zasada: intendedParentCategory jako SOFT HINT

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    PATTERN MAPPING - KLUCZOWA ZASADA                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PatternMapping przechowuje:                                                 │
│  ┌─────────────────────────────────────────────────────────────────┐        │
│  │ {                                                                │        │
│  │   "pattern": "BIEDRONKA",                                        │        │
│  │   "targetCategory": "Zakupy spożywcze",    ← AKTUALNA kategoria │        │
│  │   "intendedParentCategory": "Jedzenie",    ← INTENCJA (hint)    │        │
│  │   "categoryType": "OUTFLOW"                                      │        │
│  │ }                                                                │        │
│  └─────────────────────────────────────────────────────────────────┘        │
│                                                                              │
│  targetCategory = GDZIE TERAZ trafiają transakcje                           │
│  intendedParentCategory = GDZIE AI CHCIAŁ żeby była hierarchia (hint)       │
│                                                                              │
│  Te dwa pola są NIEZALEŻNE!                                                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 12.2 Scenariusz: User przesuwa kategorię (MOVE)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  User przesuwa "Zakupy spożywcze" z top-level pod "Jedzenie"                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PRZED:                              PO:                                     │
│  ┌────────────────────────────┐     ┌────────────────────────────┐          │
│  │ PatternMapping             │     │ PatternMapping             │          │
│  │ pattern: "BIEDRONKA"       │     │ pattern: "BIEDRONKA"       │          │
│  │ targetCategory: "Zakupy    │  →  │ targetCategory: "Zakupy    │          │
│  │              spożywcze"    │     │              spożywcze"    │          │
│  │ intendedParent: "Jedzenie" │     │ intendedParent: "Jedzenie" │          │
│  └────────────────────────────┘     └────────────────────────────┘          │
│                                                                              │
│  ^ BEZ ZMIAN!                        ^ BEZ ZMIAN!                           │
│                                                                              │
│  DLACZEGO?                                                                   │
│  - targetCategory to NAZWA kategorii, nie jej pozycja w hierarchii          │
│  - Transakcje nadal trafiają do "Zakupy spożywcze"                          │
│  - Tylko CashFlow.structure się zmienia                                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 12.3 Scenariusz: User ZMIENIA NAZWĘ kategorii (RENAME)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  User zmienia "Zakupy spożywcze" na "Groceries"                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PRZED:                              PO:                                     │
│  ┌────────────────────────────┐     ┌────────────────────────────┐          │
│  │ PatternMapping             │     │ PatternMapping             │          │
│  │ pattern: "BIEDRONKA"       │     │ pattern: "BIEDRONKA"       │          │
│  │ targetCategory: "Zakupy    │  →  │ targetCategory: "Groceries"│ ← ZMIANA │
│  │              spożywcze"    │     │                            │          │
│  │ intendedParent: "Jedzenie" │     │ intendedParent: "Jedzenie" │          │
│  └────────────────────────────┘     └────────────────────────────┘          │
│                                                                              │
│  WYMAGANA AKTUALIZACJA!                                                      │
│                                                                              │
│  RenameCategoryCommand musi zaktualizować:                                   │
│  1. CashFlow.structure                                                       │
│  2. Wszystkie CashChange.category                                            │
│  3. Wszystkie PatternMapping.targetCategory                                  │
│  4. Wszystkie CategoryMapping.targetCategory                                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 12.4 Scenariusz: User ZMIENIA NAZWĘ parenta

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  User zmienia "Jedzenie" na "Food"                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PYTANIE: Czy intendedParentCategory powinno się zmienić?                   │
│                                                                              │
│  OPCJA A: NIE ZMIENIAĆ (obecny design - rekomendowane)                      │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │ PatternMapping                                                  │         │
│  │ pattern: "BIEDRONKA"                                            │         │
│  │ targetCategory: "Zakupy spożywcze"                              │         │
│  │ intendedParent: "Jedzenie"  ← STALE! Kategoria "Jedzenie"      │         │
│  │                                już nie istnieje                 │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  KONSEKWENCJE:                                                               │
│  - intendedParent staje się "orphaned" (osierocony)                         │
│  - AI przy kolejnym imporcie nie znajdzie kategorii "Jedzenie"              │
│  - AI może zasugerować utworzenie nowej "Jedzenie" (duplikat?)              │
│  - LUB AI zignoruje intendedParent i użyje aktualnej struktury              │
│                                                                              │
│  OPCJA B: AKTUALIZOWAĆ (dodatkowa logika - przyszłość)                      │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │ PatternMapping                                                  │         │
│  │ pattern: "BIEDRONKA"                                            │         │
│  │ targetCategory: "Zakupy spożywcze"                              │         │
│  │ intendedParent: "Food"  ← ZAKTUALIZOWANE                       │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  WYMAGA: RenameCategoryCommand aktualizuje też intendedParentCategory       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 12.5 Rekomendowany design: SOFT HINT APPROACH

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    REKOMENDACJA: SOFT HINT APPROACH                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  intendedParentCategory to HINT, nie HARD REFERENCE:                        │
│                                                                              │
│  1. NIE jest Foreign Key - może wskazywać na nieistniejącą kategorię        │
│  2. NIE blokuje operacji - rename/delete kategorii nie wymaga aktualizacji  │
│  3. AI INTERPRETUJE hint w kontekście aktualnej struktury                   │
│                                                                              │
│  ALGORYTM AI przy odczycie intendedParent:                                  │
│  ┌─────────────────────────────────────────────────────────────────┐        │
│  │ 1. Sprawdź czy intendedParent istnieje w CashFlow.structure     │        │
│  │    → TAK: użyj jako sugestii dla nowych kategorii               │        │
│  │    → NIE: spróbuj znaleźć podobną kategorię (fuzzy match)       │        │
│  │           LUB zignoruj hint i użyj aktualnej pozycji            │        │
│  │                                                                  │        │
│  │ 2. Jeśli targetCategory jest już pod intendedParent:            │        │
│  │    → Hierarchia zgodna z intencją, brak structureOptimization   │        │
│  │                                                                  │        │
│  │ 3. Jeśli targetCategory jest gdzie indziej:                     │        │
│  │    → Zasugeruj structureOptimization: MOVE_TO_PARENT            │        │
│  └─────────────────────────────────────────────────────────────────┘        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 12.6 Matryca aktualizacji PatternMapping

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    MATRYCA AKTUALIZACJI PATTERN MAPPING                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  OPERACJA                          │ targetCategory │ intendedParent        │
│  ──────────────────────────────────┼────────────────┼───────────────────    │
│  MOVE kategoria                    │      ❌        │       ❌              │
│  DELETE pusta kategoria            │      ❌        │       ❌              │
│  RENAME targetCategory             │      ✅        │       ❌              │
│  RENAME intendedParent (opcja A)   │      ❌        │       ❌              │
│  RENAME intendedParent (opcja B)   │      ❌        │       ✅              │
│  Nowy import - AI aktualizuje      │      ❌*       │       ✅**            │
│                                                                              │
│  * targetCategory nie zmienia się automatycznie                              │
│    (chyba że user ręcznie zmieni mapowanie)                                 │
│                                                                              │
│  ** intendedParent może być zaktualizowany przez AI jeśli:                  │
│     - AI zasugeruje nową strukturę                                          │
│     - User zaakceptuje structureOptimization                                │
│     - System zaktualizuje intendedParent na nowy parent                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 12.7 Przyszła ewolucja: Opcje synchronizacji

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    PRZYSZŁOŚĆ: OPCJE SYNCHRONIZACJI                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  OPCJA 1: SOFT HINT (rekomendowane na start)                                │
│  ┌─────────────────────────────────────────────────────────────────┐        │
│  │ - intendedParent jest tylko wskazówką                           │        │
│  │ - Może się "zestarzeć" (orphaned) - to OK                       │        │
│  │ - AI interpretuje w kontekście aktualnej struktury              │        │
│  │ - Prostsze, mniej edge cases                                    │        │
│  └─────────────────────────────────────────────────────────────────┘        │
│                                                                              │
│  OPCJA 2: EVENTUAL CONSISTENCY (przyszłość, jeśli potrzebne)                │
│  ┌─────────────────────────────────────────────────────────────────┐        │
│  │ - CategoryRenamedEvent → aktualizuj intendedParent w mappings   │        │
│  │ - CategoryDeletedEvent → wyczyść intendedParent (set to null)   │        │
│  │ - Wymaga dodatkowych event handlerów                            │        │
│  │ - Większa spójność, ale więcej kodu                             │        │
│  └─────────────────────────────────────────────────────────────────┘        │
│                                                                              │
│  OPCJA 3: LAZY CLEANUP (kompromis)                                          │
│  ┌─────────────────────────────────────────────────────────────────┐        │
│  │ - Przy każdym odczycie PatternMapping sprawdź czy parent istnieje│       │
│  │ - Jeśli nie: wyczyść intendedParent (lazy update)               │        │
│  │ - Albo: batch job co jakiś czas czyści orphaned intents         │        │
│  └─────────────────────────────────────────────────────────────────┘        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 12.8 Podsumowanie ewolucji PatternMapping

| Pytanie | Odpowiedź |
|---------|-----------|
| Czy PatternMapping.targetCategory zmienia się przy MOVE? | **NIE** - tylko struktura CashFlow |
| Czy PatternMapping.targetCategory zmienia się przy RENAME? | **TAK** - musi być zaktualizowane |
| Czy intendedParentCategory zmienia się automatycznie? | **NIE** (rekomendacja: soft hint) |
| Co jeśli intendedParent już nie istnieje? | AI zignoruje lub zasugeruje podobną |
| Czy potrzebna automatyczna synchronizacja? | **Nie na start** - soft hint wystarczy |

**Kluczowa zasada:** `intendedParentCategory` to **HINT dla AI**, nie **HARD REFERENCE**. Może się zestarzeć i to jest OK - AI jest wystarczająco inteligentne żeby zinterpretować to w kontekście aktualnej struktury.

---

## Powiązane dokumenty

- `docs/VID-151-PHASE2-IMPLEMENTATION-PLAN.md` - szczegółowy plan implementacji 11 kroków
- `docs/analysis/VID-151-BACKLOG.md` - skonsolidowany backlog wszystkich funkcji VID-151
- `docs/features-backlog/VID-151-STRUCTURE_OPTIMIZATIONS_DESIGN.md` - design Structure Optimizations
- `docs/VID-151-EMPTY-CATEGORIES-ANALYSIS.md` - analiza problemu pustych kategorii
