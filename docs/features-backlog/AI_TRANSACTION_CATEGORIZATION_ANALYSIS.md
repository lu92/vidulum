# AI Transaction Categorization - Analysis & Design

**Status:** Analysis Complete, Ready for Implementation
**Priority:** HIGH
**Estimated Cost:** ~0.31 gr per import (~0.86 gr/user/year)

## Executive Summary

This document analyzes AI-powered transaction categorization for Vidulum. The key insight is that **pattern-based deduplication** reduces AI costs by ~93% while maintaining high accuracy.

### Key Numbers

| Metric | Value |
|--------|-------|
| Cost per import (400 txn) | **0.31 gr** |
| Cost per user/year | **~0.86 gr** |
| 1,000 users/year | **~86 zł** |
| Expected accuracy | **85-95%** |
| Processing time | **~1-2 seconds** |

---

## Problem Statement

### Current User Pain Point

When importing bank statements for the first time, users must:

1. Create category structure manually (10-15 min)
2. Map each bank category to system category (2-5 min)
3. Realize bank categories are too generic ("Przelewy wychodzące" = everything)
4. Manually categorize transactions later (ongoing pain)

### Why Bank Category Mapping Alone Doesn't Work

Bank categories are too generic:

```
"Przelewy wychodzące" contains:
├── Rent payments
├── Utility bills
├── Online shopping (Allegro, Amazon)
├── Subscriptions (Netflix, Spotify)
├── Transfers to friends
├── Tax payments
└── Everything else...
```

One bank category → Many user categories needed.

---

## Proposed Solution

### Two-Phase AI Assistance

| Phase | When | What AI Does | Cost |
|-------|------|--------------|------|
| **1. Category Structure** | CashFlow creation | Suggests nested category hierarchy | ~0.2 gr (once) |
| **2. Transaction Categorization** | Each import | Assigns transactions to categories | ~0.3 gr/import |

---

## Phase 1: AI Category Structure Suggestion

### Input (to AI)

```json
{
  "task": "suggest_category_structure",
  "context": {
    "country": "PL",
    "currency": "PLN",
    "bankCategories": ["Przelewy wychodzące", "Przelewy przychodzące", "Opłaty i prowizje"]
  }
}
```

### Output (from AI)

```json
{
  "suggestedStructure": {
    "outflow": [
      {
        "name": "Mieszkanie",
        "subCategories": ["Czynsz", "Media", "Ubezpieczenie mieszkania"]
      },
      {
        "name": "Transport",
        "subCategories": ["Paliwo", "Komunikacja miejska", "Serwis samochodu", "Ubezpieczenie OC/AC"]
      },
      {
        "name": "Jedzenie",
        "subCategories": ["Zakupy spożywcze", "Restauracje", "Kawiarnie"]
      },
      {
        "name": "Rozrywka",
        "subCategories": ["Subskrypcje", "Kino i teatr", "Hobby"]
      },
      {
        "name": "Zdrowie",
        "subCategories": ["Leki", "Wizyty lekarskie", "Ubezpieczenie zdrowotne"]
      },
      {
        "name": "Zakupy",
        "subCategories": ["Odzież", "Elektronika", "Dom i ogród"]
      },
      {
        "name": "Opłaty",
        "subCategories": ["Bankowe", "Administracyjne", "Podatki"]
      },
      {
        "name": "Inne wydatki",
        "subCategories": ["Prezenty", "Darowizny"]
      }
    ],
    "inflow": [
      {
        "name": "Wynagrodzenie",
        "subCategories": ["Pensja podstawowa", "Premie", "Nadgodziny"]
      },
      {
        "name": "Inne przychody",
        "subCategories": ["Zwroty", "Odsetki", "Sprzedaż", "Prezenty"]
      }
    ]
  }
}
```

### UI Flow

```
┌─────────────────────────────────────────────────────────────────┐
│  🤖 AI Suggested Category Structure                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Based on Polish banking patterns, we suggest:                  │
│                                                                 │
│  WYDATKI:                                                       │
│  ☑️ Mieszkanie                                                  │
│     └── Czynsz, Media, Ubezpieczenie mieszkania                 │
│  ☑️ Transport                                                   │
│     └── Paliwo, Komunikacja miejska, Serwis, OC/AC              │
│  ☑️ Jedzenie                                                    │
│     └── Zakupy spożywcze, Restauracje, Kawiarnie                │
│  ☐ Rozrywka                                                     │
│     └── Subskrypcje, Kino i teatr, Hobby                        │
│  ☐ Zdrowie                                                      │
│     └── Leki, Wizyty lekarskie                                  │
│                                                                 │
│  PRZYCHODY:                                                     │
│  ☑️ Wynagrodzenie                                               │
│     └── Pensja podstawowa, Premie                               │
│  ☑️ Inne przychody                                              │
│     └── Zwroty, Odsetki, Sprzedaż                               │
│                                                                 │
│  [✓ Apply Selected]  [Customize First]  [Skip - Create My Own] │
└─────────────────────────────────────────────────────────────────┘
```

### Nested Categories Support

Vidulum fully supports nested categories (unlimited depth):

```java
public class Category {
    CategoryName categoryName;
    List<Category> subCategories;  // Recursive nesting
    // ...
}
```

AI suggestions leverage this by proposing 2-level hierarchy:
- **Level 1**: Main categories (Mieszkanie, Transport, Jedzenie...)
- **Level 2**: Subcategories (Czynsz, Paliwo, Zakupy spożywcze...)

Users can later create deeper nesting manually if needed.

---

## Phase 2: Transaction Categorization

### Key Optimization: Pattern Deduplication

Instead of sending all 402 transactions to AI, we:

1. **Normalize** transaction names (remove numbers, addresses)
2. **Deduplicate** to unique patterns
3. **Send patterns** to AI (typically 40-80 unique patterns)
4. **Apply mappings** to all transactions

### Normalization Examples

| Original Transaction | Normalized Pattern |
|---------------------|-------------------|
| BIEDRONKA WARSZAWA UL.PUŁAWSKA 123 | BIEDRONKA |
| BIEDRONKA KRAKÓW GALERIA | BIEDRONKA |
| ORLEN STACJA 4567 | ORLEN |
| ALLEGRO*SELLER123456 | ALLEGRO |
| NETFLIX.COM 2024-03 | NETFLIX.COM |

### Cost Reduction

| Approach | Transactions to AI | Cost |
|----------|-------------------|------|
| Without deduplication | 402 | 4.4 gr |
| **With deduplication** | 45 | **0.31 gr** |
| **Savings** | **89%** | **93%** |

### Request to AI (after deduplication)

```json
{
  "task": "categorize_patterns",
  "userCategories": {
    "outflow": [
      {"name": "Mieszkanie", "subCategories": ["Czynsz", "Media"]},
      {"name": "Transport", "subCategories": ["Paliwo", "Komunikacja"]},
      {"name": "Jedzenie", "subCategories": ["Zakupy spożywcze", "Restauracje"]},
      {"name": "Rozrywka", "subCategories": ["Subskrypcje"]}
    ],
    "inflow": [
      {"name": "Wynagrodzenie", "subCategories": ["Pensja", "Premie"]},
      {"name": "Inne przychody", "subCategories": ["Zwroty"]}
    ]
  },
  "patterns": [
    {"pattern": "BIEDRONKA", "count": 12, "type": "OUTFLOW"},
    {"pattern": "ORLEN", "count": 8, "type": "OUTFLOW"},
    {"pattern": "NETFLIX.COM", "count": 12, "type": "OUTFLOW"},
    {"pattern": "ALLEGRO", "count": 15, "type": "OUTFLOW"},
    {"pattern": "PRZELEW OD PRACODAWCA", "count": 12, "type": "INFLOW"},
    {"pattern": "PRZELEW DO KOWALSKI JAN", "count": 1, "type": "OUTFLOW"}
  ]
}
```

### Response from AI

```json
{
  "mappings": [
    {
      "pattern": "BIEDRONKA",
      "category": "Zakupy spożywcze",
      "parentCategory": "Jedzenie",
      "confidence": 99,
      "reasoning": "Polish grocery store chain"
    },
    {
      "pattern": "ORLEN",
      "category": "Paliwo",
      "parentCategory": "Transport",
      "confidence": 99,
      "reasoning": "Polish gas station chain"
    },
    {
      "pattern": "NETFLIX.COM",
      "category": "Subskrypcje",
      "parentCategory": "Rozrywka",
      "confidence": 99,
      "reasoning": "Streaming service subscription"
    },
    {
      "pattern": "ALLEGRO",
      "category": "Zakupy online",
      "parentCategory": null,
      "confidence": 75,
      "reasoning": "E-commerce marketplace - could be various product types",
      "suggestNewCategory": true
    },
    {
      "pattern": "PRZELEW OD PRACODAWCA",
      "category": "Pensja",
      "parentCategory": "Wynagrodzenie",
      "confidence": 95,
      "reasoning": "Employer transfer - likely salary"
    },
    {
      "pattern": "PRZELEW DO KOWALSKI JAN",
      "category": null,
      "parentCategory": null,
      "confidence": 15,
      "reasoning": "Personal transfer - purpose unclear"
    }
  ]
}
```

---

## Handling Nested Categories

### AI Response with Hierarchy

AI always returns both `category` and `parentCategory`:

```json
{
  "pattern": "ORLEN",
  "category": "Paliwo",           // Subcategory
  "parentCategory": "Transport",   // Parent category
  "confidence": 99
}
```

### Application Logic

```java
void applyAiSuggestion(Transaction txn, AiMapping mapping) {
    if (mapping.parentCategory() != null) {
        // Assign to subcategory
        txn.setCategory(mapping.category());
        txn.setParentCategory(mapping.parentCategory());
    } else if (mapping.category() != null) {
        // Assign to top-level category
        txn.setCategory(mapping.category());
    } else {
        // No suggestion - mark for manual review
        txn.setCategory("Uncategorized");
        txn.setNeedsReview(true);
    }
}
```

### Creating Missing Categories

If AI suggests a category that doesn't exist:

```json
{
  "pattern": "ALLEGRO",
  "category": "Zakupy online",
  "parentCategory": "Zakupy",
  "suggestNewCategory": true,
  "confidence": 75
}
```

UI prompts user:

```
┌─────────────────────────────────────────────────────┐
│  🤖 AI suggests creating new category              │
├─────────────────────────────────────────────────────┤
│                                                     │
│  Pattern: ALLEGRO (15 transactions)                 │
│                                                     │
│  Suggested: Create "Zakupy online" under "Zakupy"   │
│                                                     │
│  [✓ Create & Apply]  [Choose Different]  [Skip]    │
└─────────────────────────────────────────────────────┘
```

---

## Accuracy Expectations

### By Transaction Type

| Transaction Type | Examples | Expected Accuracy | Confidence |
|-----------------|----------|-------------------|------------|
| **Retail chains** | Biedronka, Lidl, Żabka | 99% | Very High |
| **Gas stations** | Orlen, BP, Shell | 99% | Very High |
| **Subscriptions** | Netflix, Spotify, HBO | 99% | Very High |
| **E-commerce** | Allegro, Amazon, AliExpress | 85-90% | High |
| **Utilities** | PGE, Innogy, Veolia | 90-95% | High |
| **Insurance** | PZU, Warta, Allianz | 90-95% | High |
| **Restaurants** | McDonald's, KFC, local | 85-90% | High |
| **Transfers to companies** | Named recipients | 70-85% | Medium |
| **Personal transfers** | "PRZELEW DO JAN KOWALSKI" | 10-30% | Low |
| **Cash withdrawals** | ATM operations | 50% | Low |

### Overall Distribution (typical import)

```
┌─────────────────────────────────────────────────────┐
│  Categorization Results                             │
├─────────────────────────────────────────────────────┤
│                                                     │
│  ✅ HIGH CONFIDENCE (≥90%)         ~75-80%          │
│     Auto-accepted, no user action needed            │
│                                                     │
│  ⚠️ MEDIUM CONFIDENCE (50-89%)     ~15-20%          │
│     AI has suggestion, user confirms                │
│                                                     │
│  ❓ LOW CONFIDENCE (<50%)          ~5-10%           │
│     Manual categorization required                  │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### Why GPT-4o-mini Is Sufficient

| Capability | GPT-4o-mini | Assessment |
|------------|-------------|------------|
| Polish brand recognition | ✅ Excellent | Knows Biedronka, Żabka, Orlen, etc. |
| Context understanding | ✅ Good | "PRZELEW OD" vs "PRZELEW DO" |
| JSON output stability | ✅ Excellent | Rarely malformed |
| Category matching | ✅ Good | Maps to user's category names |
| Speed | ✅ Fast | ~1-2s per batch |
| Cost | ✅ Very low | $0.15/1M input, $0.60/1M output |

GPT-4o or Claude Sonnet would provide marginal improvement (~2-3%) at 10-20x cost - not worth it for this use case.

---

## Handling Transactions Without Category

### Confidence Thresholds

```java
public enum CategorizationResult {
    AUTO_ACCEPT,    // confidence >= 90%
    SUGGEST,        // confidence 50-89%
    MANUAL_REQUIRED // confidence < 50%
}
```

### Low Confidence Handling

When AI returns `confidence < 50%` or `category: null`:

1. **Mark as "Uncategorized"** (system category)
2. **Flag for review** in staging
3. **Show in UI** with special treatment

### UI for Uncategorized Transactions

```
┌─────────────────────────────────────────────────────────────────┐
│  ❓ Manual Categorization Required (23 transactions)            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  These transactions couldn't be automatically categorized:      │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │ PRZELEW DO KOWALSKI JAN              -500.00 PLN          │ │
│  │ 2024-03-15                                                │ │
│  │                                                           │ │
│  │ 🤖 AI: "Personal transfer - purpose unclear"              │ │
│  │                                                           │ │
│  │ Choose category:                                          │ │
│  │ [Prezenty] [Pożyczki] [Rozliczenia] [+ New]               │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │ WPŁATA WŁASNA                        +1000.00 PLN         │ │
│  │ 2024-03-10                                                │ │
│  │                                                           │ │
│  │ 🤖 AI: "Self-deposit - could be savings or income"        │ │
│  │                                                           │ │
│  │ Choose category:                                          │ │
│  │ [Oszczędności] [Inne przychody] [+ New]                   │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
│  Progress: ██░░░░░░░░░░░░░░░░░░ 2/23                           │
│                                                                 │
│  [Skip All → Uncategorized]  [Continue]                         │
└─────────────────────────────────────────────────────────────────┘
```

### Learning from Manual Categorization

When user manually categorizes, system learns:

```java
void onUserCategorization(String pattern, Category selectedCategory) {
    // 1. Save as cached mapping
    patternMappingCache.save(new PatternMapping(
        pattern,                    // "PRZELEW DO KOWALSKI"
        selectedCategory,           // "Prezenty"
        MappingSource.USER_DEFINED,
        100                         // confidence = 100 (user confirmed)
    ));

    // 2. Next import - this pattern auto-categorized
}
```

### "Apply to Similar" Feature

```
┌─────────────────────────────────────────────────────┐
│  🔗 Apply to Similar?                               │
├─────────────────────────────────────────────────────┤
│                                                     │
│  You categorized:                                   │
│  "PRZELEW DO KOWALSKI JAN" → Prezenty               │
│                                                     │
│  Found 2 similar transactions:                      │
│                                                     │
│  ☑️ PRZELEW DO KOWALSKI JAN  2024-02-15  -200 PLN  │
│  ☑️ PRZELEW DO KOWALSKI J    2024-01-20  -150 PLN  │
│                                                     │
│  [Apply "Prezenty" to selected]  [No, separately]   │
└─────────────────────────────────────────────────────┘
```

---

## Cost Analysis with Cache-First Architecture

### Cache-First, AI-Fallback Model

```
┌─────────────────────────────────────────────────────────────────┐
│  FLOW: Nowa transakcja do kategoryzacji                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Normalize: "BIEDRONKA WARSZAWA UL.X" → "BIEDRONKA"         │
│                                                                 │
│  2. Check cache (in priority order):                            │
│     ├── User's own mappings (highest priority, 100% confidence) │
│     ├── CashFlow-specific patterns                              │
│     └── Global patterns (system-wide known brands)              │
│                                                                 │
│  3. If cache HIT → use cached category (FREE, 0 tokens)        │
│     If cache MISS → send to AI (PAID)                           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Cache Hierarchy

```
┌─────────────────────────────────────────────────────────────────┐
│  CACHE HIERARCHY (Priority order)                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. USER MANUAL (100% confidence)                               │
│     User explicitly categorized this pattern                    │
│     → Always use, never override                                │
│                                                                 │
│  2. USER CONFIRMED AI (100% confidence)                         │
│     AI suggested, user accepted                                 │
│     → Use, but allow user to change                             │
│                                                                 │
│  3. CASHFLOW-SPECIFIC (95% confidence)                          │
│     Pattern seen before in this CashFlow                        │
│     → Use, mark as "suggested"                                  │
│                                                                 │
│  4. GLOBAL PATTERNS (90% confidence)                            │
│     System-wide known patterns (Biedronka, Netflix...)          │
│     → Use, mark as "auto-detected"                              │
│                                                                 │
│  5. AI CATEGORIZATION (variable confidence)                     │
│     New pattern, needs AI processing                            │
│     → Costs money, confidence 10-99%                            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Token Calculation (First Import)

**Per import (45 unique patterns, no cache):**

| Component | Tokens |
|-----------|--------|
| System prompt | ~400 |
| User categories | ~200 |
| 45 patterns | ~900 |
| **Input total** | **~1,500** |
| Response (45 mappings) | ~900 |
| **Output total** | **~900** |

### Cost per Import

```
Input:  1,500 × $0.15/1M = $0.000225 = 0.09 gr
Output:   900 × $0.60/1M = $0.000540 = 0.22 gr
──────────────────────────────────────────────────
Total (no cache):                      0.31 gr
```

### Real Transaction Pattern Analysis

Based on analysis of 402 real transactions from Nest Bank:

```
┌─────────────────────────────────────────────────────────────────┐
│  DEDUPLIKACJA + CACHE ANALYSIS                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  402 transakcje → ~45 unikalnych wzorców                        │
│                                                                 │
│  Wzorce powtarzające się (cacheable):                           │
│  ├── "MINDBOX" (wynagrodzenie)     → 12x  ✅ cache after 1st   │
│  ├── "ZUS SKŁADKI"                 → 12x  ✅ cache after 1st   │
│  ├── "URZĄD SKARBOWY PIT28"        → 12x  ✅ cache after 1st   │
│  ├── "URZĄD SKARBOWY VAT7K"        → 4x   ✅ cache after 1st   │
│  ├── "SILVA CZYNSZ"                → 12x  ✅ cache after 1st   │
│  ├── "IFIRMA FAKTURA"              → 12x  ✅ cache after 1st   │
│  ├── "LUCJAN BIK PEKAO ZYCIE"      → 20x  ✅ cache after 1st   │
│  ├── "LUCJAN BIK MBANK KREDYT"     → 8x   ✅ cache after 1st   │
│  ├── "IKANO RATA"                  → 6x   ✅ cache after 1st   │
│  ├── "PROWIZJA KIR"                → 8x   ✅ cache after 1st   │
│  └── inne jednorazowe              → ~15  ⚠️ AI needed        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Cache Hit Rate Over Time

```
Miesiąc:    1     2     3     4     5     6    ...   12
Cache %:    0%   65%   80%   88%   92%   95%  ...   98%
AI calls:  45    12     7     4     3     2   ...    1
Cost:     0.31  0.08  0.05  0.03  0.02  0.01 ...  0.01
```

### Cost per Subsequent Import (with cache)

| Scenariusz | Nowe wzorce | Do AI | Koszt |
|------------|-------------|-------|-------|
| Miesiąc 2 | ~5-8 nowych | 5-8 | **0.05 gr** |
| Miesiąc 3 | ~3-5 nowych | 3-5 | **0.03 gr** |
| Miesiąc 4+ | ~1-3 nowych | 1-3 | **0.01-0.02 gr** |

### Annual Cost per User (Realistic)

```
┌─────────────────────────────────────────────────────────────────┐
│  ROCZNY KOSZT - Typowy użytkownik                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Import 1 (historyczny, 400 txn):     0.31 gr                   │
│  Import 2-3 (learning phase):         0.10 gr                   │
│  Import 4-12 (9 months × 0.02 gr):    0.18 gr                   │
│  ─────────────────────────────────────────────────              │
│  RAZEM ROK 1:                         ~0.60 gr                  │
│                                                                 │
│  ROK 2+ (cache mature):               ~0.15 gr/rok              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Global Patterns Optimization

Pre-built system-wide patterns (FREE, no AI calls):

```java
// System-wide cache (shared across all users)
BIEDRONKA      → Zakupy spożywcze (99% accuracy)
LIDL           → Zakupy spożywcze (99% accuracy)
ŻABKA          → Zakupy spożywcze (99% accuracy)
ORLEN          → Paliwo (99% accuracy)
BP             → Paliwo (99% accuracy)
NETFLIX        → Subskrypcje (99% accuracy)
SPOTIFY        → Subskrypcje (99% accuracy)
ZUS            → Składki ZUS (99% accuracy)
URZĄD SKARBOWY → Podatki (95% accuracy)
```

**Impact of global patterns on first import:**

| Scenariusz | Bez global | Z global patterns |
|------------|------------|-------------------|
| Patterns to AI | 45 | 25 (20 from global) |
| Cost | 0.31 gr | **0.17 gr** |
| Savings | - | **45%** |

### Scale: Cost Projections

| Users | Year 1 | Year 2+ | Notes |
|-------|--------|---------|-------|
| **100** | 0.60 zł | 0.15 zł | Startup phase |
| **1,000** | 6.00 zł | 1.50 zł | Small app |
| **10,000** | 60 zł | 15 zł | Growing app |
| **100,000** | 600 zł | 150 zł | Popular app |

### Cost Comparison: With vs Without Cache

| Approach | Cost/user/year | Total 1000 users |
|----------|----------------|------------------|
| **AI without cache** | ~3.7 gr | ~370 zł |
| **AI with cache** | ~0.6 gr (Y1), ~0.15 gr (Y2+) | **~6 zł** |
| **Savings** | **~98%** | **~364 zł** |

### ROI Summary

| Metric | Value |
|--------|-------|
| Cost per 1000 users (Year 1) | **6 zł** |
| User time saved per import | **1-2 hours** |
| Manual work eliminated | **75-90%** |
| ROI | **Excellent** |

---

## AI Accuracy Analysis

### Accuracy by Transaction Type

AI uses **both title and counterparty name** for categorization:

```java
// Data sent to AI for each transaction:
{
  "title": "PCB9QO25 11/2025",           // Often cryptic
  "counterparty": "MINDBOX SPÓŁKA AKCYJNA", // Informative!
  "amount": 31064.44,
  "type": "INFLOW"
}
// AI infers: regular payment from company = SALARY (confidence: 85%)
```

### Real Transaction Analysis

| Title | Counterparty | Title Only | Title + Counterparty |
|-------|--------------|------------|---------------------|
| `"zycie"` | Lucjan Bik Pekao | 5% | **15%** (still unclear) |
| `"PCB9QO25 11/2025"` | MINDBOX SA | 10% | **85%** (salary!) |
| `"składki ZUS"` | ZUS | 95% | **99%** |
| `"czynsz Lokal..."` | Silva, Warszawa | 95% | **99%** |
| `"PIT28\|VAT7K..."` | Urząd Skarbowy | 95% | **99%** |
| `"Faktura VAT nr..."` | IFIRMA SA | 60% | **90%** |
| `"mieszkanie kredyt"` | Lucjan Bik mbank | 85% | **95%** |
| `"rata kredytu"` | Ikano | 95% | **99%** |
| `"zaswiadczenie"` | Ikano | 30% | **50%** |

### Accuracy Distribution by User Type

**B2B / Freelancer (like your data):**

```
┌─────────────────────────────────────────────────────────────────┐
│  B2B/FREELANCER - 402 transakcji                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ✅ HIGH (90-99%): ~40-45% transakcji                          │
│     • ZUS, składki                                              │
│     • Urząd Skarbowy (PIT, VAT)                                 │
│     • Czynsz z adresem                                          │
│     • Raty kredytów (jawne tytuły)                              │
│     • Prowizje bankowe                                          │
│                                                                 │
│  ⚠️ MEDIUM (50-89%): ~25-30% transakcji                        │
│     • MINDBOX → "Wynagrodzenie" (from counterparty!)            │
│     • IFIRMA → "Usługi księgowe" (from company name)            │
│     • Kredyt mieszkaniowy (partial description)                 │
│                                                                 │
│  ❌ LOW (<50%): ~25-35% transakcji                              │
│     • "zycie" - ??? private transfer                            │
│     • "PCB9QO25 11/2025" - internal invoice number              │
│     • "zaswiadczenie" - unclear purpose                         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Typical Consumer:**

```
┌─────────────────────────────────────────────────────────────────┐
│  KONSUMENT - typowe transakcje                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ✅ HIGH (90-99%): ~70-80% transakcji                          │
│     • Biedronka, Lidl, Żabka (grocery)                          │
│     • Orlen, BP (fuel)                                          │
│     • Netflix, Spotify (subscriptions)                          │
│     • McDonald's, KFC (restaurants)                             │
│                                                                 │
│  ⚠️ MEDIUM (50-89%): ~15-20% transakcji                        │
│     • Allegro (could be various categories)                     │
│     • Local restaurants                                         │
│     • Online services                                           │
│                                                                 │
│  ❌ LOW (<50%): ~5-10% transakcji                               │
│     • Personal transfers to friends                             │
│     • Cash withdrawals                                          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### The "Business Internal Code" Problem

Many B2B transactions have cryptic titles:

```
"PCB9QO25 11/2025"  ← internal invoice number from Mindbox
"PBD9CH21 10/2025"  ← same pattern
"P9B9UU17 8/2025"   ← same pattern
"zycie"             ← private shorthand, AI cannot know
```

**Solution**: AI uses counterparty name as primary signal when title is cryptic.

### Cache Dramatically Improves Subsequent Imports

After first import with manual categorization:

```
Import 1:  40% auto | 30% suggest | 30% manual  ← Most work here
Import 2:  75% auto | 15% suggest | 10% manual  ← Cache kicks in
Import 3:  85% auto | 10% suggest |  5% manual
Import 4+: 90% auto |  7% suggest |  3% manual  ← Nearly automatic
```

### Key Insight

**First import requires user effort (~30% manual), but cache learning means subsequent imports are nearly automatic (~90%+ auto-categorized).**

This is acceptable because:
1. First import is a one-time event
2. User builds their personal categorization knowledge base
3. Subsequent monthly imports become trivial

---

## Implementation Phases

### Phase 1: Pattern Deduplication & Caching (Foundation)

- [ ] `TransactionNameNormalizer` - normalize transaction names
- [ ] `PatternDeduplicator` - group transactions by pattern
- [ ] `PatternMappingCache` - store user-confirmed mappings
- [ ] MongoDB collection for pattern mappings

### Phase 2: AI Integration

- [ ] `AiCategorizationService` - call GPT-4o-mini
- [ ] `AiCategorizationPromptBuilder` - build prompts with user categories
- [ ] `AiCategorizationResponseProcessor` - parse JSON response
- [ ] Confidence threshold configuration

### Phase 3: UI Integration

- [ ] Categorization results summary view
- [ ] Review interface for medium/low confidence
- [ ] "Apply to similar" bulk action
- [ ] Manual categorization with learning

### Phase 4: Category Structure Suggestion

- [ ] First-time user detection
- [ ] AI category structure suggestion
- [ ] UI for accepting/customizing structure
- [ ] Bulk category creation

---

## Summary

### What This Solves

| Problem | Solution |
|---------|----------|
| User doesn't know what categories to create | AI suggests nested category structure |
| Bank categories too generic | AI categorizes by transaction name + counterparty |
| Manual categorization takes hours | 75-90% auto-categorized (with cache) |
| First import is painful | Guided flow with AI suggestions |
| Business internal codes unreadable | AI uses counterparty name as fallback |
| Repeated patterns require re-categorization | Cache learns from user confirmations |

### Key Metrics

| Metric | First Import | With Cache (Month 4+) |
|--------|--------------|----------------------|
| Auto-categorization (≥90%) | 40-45% (B2B) / 70-80% (consumer) | **90%+** |
| Suggestions to confirm (50-89%) | 25-30% | **7%** |
| Manual required (<50%) | 25-35% (B2B) / 5-10% (consumer) | **3%** |
| Cost per import | 0.31 gr (no cache) / 0.17 gr (global) | **0.01-0.02 gr** |
| Cost per user/year | - | **~0.60 gr (Y1)**, **~0.15 gr (Y2+)** |

### Cost at Scale

| Users | Year 1 | Year 2+ |
|-------|--------|---------|
| 1,000 | **6 zł** | **1.50 zł** |
| 10,000 | **60 zł** | **15 zł** |
| 100,000 | **600 zł** | **150 zł** |

### ROI

- **User time saved**: 1-2 hours on first import, minutes on subsequent
- **Cache learning**: 98% cost reduction after first few months
- **Cost**: ~0.60 gr/user/year (Year 1), ~0.15 gr/user/year (Year 2+)
- **1000 users = 6 zł/year** - negligible infrastructure cost
- **User satisfaction**: Dramatically improved experience, especially for recurring imports

---

## Appendix: GPT-4o-mini Pricing (March 2026)

| Tier | Input | Output |
|------|-------|--------|
| Standard | $0.15 / 1M tokens | $0.60 / 1M tokens |
| Batch API (async) | $0.075 / 1M tokens | $0.30 / 1M tokens |

Batch API provides 50% discount for requests that can wait up to 24 hours.

---

---

## Implementation Guide (Pseudo-Code)

This section contains implementation details including Java code, AI prompts, and step-by-step instructions.

### 1. Transaction Name Normalizer

Normalizes transaction names to create consistent patterns for deduplication and caching.

```java
package com.multi.vidulum.cashflow.app.categorization;

import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

/**
 * Normalizes transaction names for pattern matching and deduplication.
 *
 * Examples:
 * - "BIEDRONKA WARSZAWA UL.PUŁAWSKA 123" → "BIEDRONKA"
 * - "ORLEN STACJA 4567" → "ORLEN"
 * - "ALLEGRO*SELLER123456" → "ALLEGRO"
 * - "NETFLIX.COM 2024-03-15" → "NETFLIX.COM"
 */
@Component
public class TransactionNameNormalizer {

    // Polish street prefixes to remove
    private static final Pattern STREET_PATTERN = Pattern.compile(
        "\\b(UL\\.?|AL\\.?|PL\\.?)\\s*[A-ZŁŚŻŹĆŃ]+\\s*\\d*",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // Numbers (transaction IDs, dates, amounts)
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    // Special characters at end (*, #, etc.)
    private static final Pattern TRAILING_SPECIAL = Pattern.compile("[*#]+$");

    // Multiple spaces
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    // City names (common Polish cities to remove)
    private static final Pattern CITY_PATTERN = Pattern.compile(
        "\\b(WARSZAWA|KRAKÓW|WROCŁAW|POZNAŃ|GDAŃSK|ŁÓDŹ|KATOWICE|LUBLIN)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    public String normalize(String transactionName) {
        if (transactionName == null || transactionName.isBlank()) {
            return "";
        }

        String normalized = transactionName.toUpperCase().trim();

        // 1. Remove street addresses
        normalized = STREET_PATTERN.matcher(normalized).replaceAll("");

        // 2. Remove city names
        normalized = CITY_PATTERN.matcher(normalized).replaceAll("");

        // 3. Remove all numbers
        normalized = NUMBER_PATTERN.matcher(normalized).replaceAll("");

        // 4. Remove trailing special characters
        normalized = TRAILING_SPECIAL.matcher(normalized).replaceAll("");

        // 5. Collapse multiple spaces
        normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();

        return normalized;
    }

    /**
     * Extract merchant name from common patterns.
     * Handles formats like:
     * - "ALLEGRO*SELLER123" → "ALLEGRO"
     * - "PAYPAL *SHOPNAME" → "PAYPAL"
     */
    public String extractMerchant(String transactionName) {
        String normalized = normalize(transactionName);

        // Split on common separators
        if (normalized.contains("*")) {
            return normalized.split("\\*")[0].trim();
        }
        if (normalized.contains(" ")) {
            // Take first word if it's a known merchant
            String firstWord = normalized.split(" ")[0];
            if (isKnownMerchant(firstWord)) {
                return firstWord;
            }
        }

        return normalized;
    }

    private boolean isKnownMerchant(String name) {
        // Common Polish and international merchants
        return Set.of(
            "BIEDRONKA", "LIDL", "ŻABKA", "KAUFLAND", "AUCHAN", "CARREFOUR",
            "ORLEN", "BP", "SHELL", "CIRCLE",
            "ALLEGRO", "AMAZON", "ALIEXPRESS", "TEMU",
            "NETFLIX", "SPOTIFY", "HBO", "DISNEY",
            "PAYPAL", "REVOLUT", "WISE"
        ).contains(name);
    }
}
```

### 2. Deduplicated Transaction Record

Groups transactions by normalized pattern.

```java
package com.multi.vidulum.cashflow.app.categorization;

import com.multi.vidulum.cashflow.domain.CashChangeType;
import java.util.List;

/**
 * Represents a group of transactions with the same normalized pattern.
 *
 * Example:
 * - normalizedName: "BIEDRONKA"
 * - occurrenceCount: 12
 * - originalNames: ["BIEDRONKA WARSZAWA UL.PUŁAWSKA 123", "BIEDRONKA KRAKÓW GALERIA", ...]
 * - transactionIds: ["TXN001", "TXN002", ...]
 * - type: OUTFLOW
 */
public record DeduplicatedTransaction(
    String normalizedName,
    int occurrenceCount,
    List<String> originalNames,
    List<String> transactionIds,
    CashChangeType type
) {

    /**
     * Get sample original name for display purposes.
     */
    public String sampleOriginalName() {
        return originalNames.isEmpty() ? normalizedName : originalNames.get(0);
    }

    /**
     * Check if this pattern appears frequently (good for caching).
     */
    public boolean isFrequent() {
        return occurrenceCount >= 3;
    }
}
```

### 3. Pattern Deduplicator Service

Groups transactions into unique patterns.

```java
package com.multi.vidulum.cashflow.app.categorization;

import com.multi.vidulum.cashflow.domain.CashChange;
import com.multi.vidulum.cashflow.domain.CashChangeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PatternDeduplicator {

    private final TransactionNameNormalizer normalizer;

    /**
     * Groups transactions by normalized pattern.
     *
     * Input: 402 transactions
     * Output: ~45 unique patterns
     * Reduction: ~89%
     */
    public List<DeduplicatedTransaction> deduplicate(List<CashChange> transactions) {
        // Group by (normalizedName, type)
        Map<PatternKey, List<CashChange>> grouped = transactions.stream()
            .collect(Collectors.groupingBy(txn -> new PatternKey(
                normalizer.extractMerchant(txn.getName()),
                txn.getType()
            )));

        return grouped.entrySet().stream()
            .map(entry -> toDeduplicatedTransaction(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(DeduplicatedTransaction::occurrenceCount).reversed())
            .toList();
    }

    private DeduplicatedTransaction toDeduplicatedTransaction(
            PatternKey key,
            List<CashChange> transactions) {
        return new DeduplicatedTransaction(
            key.normalizedName(),
            transactions.size(),
            transactions.stream()
                .map(CashChange::getName)
                .distinct()
                .limit(5) // Keep max 5 examples
                .toList(),
            transactions.stream()
                .map(txn -> txn.getCashChangeId().getId())
                .toList(),
            key.type()
        );
    }

    private record PatternKey(String normalizedName, CashChangeType type) {}
}
```

### 4. Pattern Mapping Cache

Stores user-confirmed and AI-learned mappings.

```java
package com.multi.vidulum.cashflow.app.categorization;

import lombok.Builder;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Cached pattern → category mapping.
 */
@Document(collection = "pattern_mappings")
@Builder
public record PatternMapping(
    @Id String id,
    String userId,
    String cashFlowId,
    String normalizedPattern,
    String categoryName,
    String parentCategoryName,  // nullable for top-level categories
    MappingSource source,
    int confidence,
    int timesUsed,
    Instant created,
    Instant lastUsed
) {
    public enum MappingSource {
        AI_SUGGESTED,      // AI suggested, user confirmed
        USER_DEFINED,      // User manually selected
        SYSTEM_DEFAULT     // Built-in defaults (e.g., BIEDRONKA → Zakupy spożywcze)
    }
}

/**
 * Repository for pattern mappings.
 */
public interface PatternMappingRepository extends MongoRepository<PatternMapping, String> {

    /**
     * Find mapping for pattern within user's CashFlow.
     */
    Optional<PatternMapping> findByUserIdAndCashFlowIdAndNormalizedPattern(
        String userId, String cashFlowId, String normalizedPattern);

    /**
     * Find all cached mappings for a CashFlow.
     */
    List<PatternMapping> findByUserIdAndCashFlowId(String userId, String cashFlowId);

    /**
     * Find global defaults (system-wide patterns).
     */
    List<PatternMapping> findBySourceAndConfidenceGreaterThanEqual(
        PatternMapping.MappingSource source, int minConfidence);
}
```

### 5. AI Categorization Service

Calls GPT-4o-mini for categorization.

```java
package com.multi.vidulum.cashflow.app.categorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiCategorizationService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final AiPromptBuilder promptBuilder;

    /**
     * Categorize patterns using AI.
     *
     * @param patterns Deduplicated transaction patterns
     * @param userCategories User's category structure
     * @return List of AI suggestions with confidence scores
     */
    public List<AiCategorySuggestion> categorize(
            List<DeduplicatedTransaction> patterns,
            CategoryStructure userCategories) {

        // Build prompts
        String systemPrompt = promptBuilder.buildSystemPrompt(userCategories);
        String userPrompt = promptBuilder.buildUserPrompt(patterns);

        log.info("Sending {} patterns to AI for categorization", patterns.size());

        // Call AI
        String response = chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .call()
            .content();

        // Parse response
        return parseResponse(response, patterns);
    }

    private List<AiCategorySuggestion> parseResponse(
            String response,
            List<DeduplicatedTransaction> patterns) {
        try {
            AiCategorizationResponse parsed = objectMapper.readValue(
                response, AiCategorizationResponse.class);
            return parsed.mappings();
        } catch (Exception e) {
            log.error("Failed to parse AI response: {}", response, e);
            // Return empty suggestions - all will need manual categorization
            return List.of();
        }
    }
}
```

### 6. AI Prompt Builder

Constructs prompts for GPT-4o-mini.

```java
package com.multi.vidulum.cashflow.app.categorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AiPromptBuilder {

    private final ObjectMapper objectMapper;

    /**
     * Build system prompt with user's category structure.
     */
    public String buildSystemPrompt(CategoryStructure categories) {
        return """
            You are a financial transaction categorizer for Polish bank statements.
            Your task is to match transaction patterns to the user's category structure.

            ## Available Categories

            ### OUTFLOW (Wydatki)
            %s

            ### INFLOW (Przychody)
            %s

            ## Instructions

            1. For each pattern, suggest the most appropriate category
            2. If a subcategory fits, use it (e.g., "Paliwo" under "Transport")
            3. Provide confidence score (0-100):
               - 90-100: Very confident (known brands, clear purpose)
               - 70-89: Confident (likely correct based on name)
               - 50-69: Uncertain (could be multiple categories)
               - Below 50: Cannot determine (generic names, personal transfers)
            4. If no existing category fits well, you may suggest creating a new one
            5. For personal transfers (e.g., "PRZELEW DO JAN KOWALSKI"), return low confidence

            ## Response Format

            Respond ONLY with valid JSON in this exact format:
            ```json
            {
              "mappings": [
                {
                  "pattern": "BIEDRONKA",
                  "category": "Zakupy spożywcze",
                  "parentCategory": "Jedzenie",
                  "confidence": 99,
                  "reasoning": "Polish grocery store chain"
                }
              ]
            }
            ```

            If suggesting a new category, add:
            ```json
            {
              "pattern": "ALLEGRO",
              "category": "Zakupy online",
              "parentCategory": "Zakupy",
              "confidence": 75,
              "reasoning": "E-commerce marketplace",
              "suggestNewCategory": true
            }
            ```
            """.formatted(
                formatCategories(categories.outflow()),
                formatCategories(categories.inflow())
            );
    }

    /**
     * Build user prompt with patterns to categorize.
     */
    public String buildUserPrompt(List<DeduplicatedTransaction> patterns) {
        StringBuilder sb = new StringBuilder();
        sb.append("Categorize these transaction patterns:\n\n");

        for (DeduplicatedTransaction pattern : patterns) {
            sb.append("- Pattern: \"").append(pattern.normalizedName()).append("\"\n");
            sb.append("  Type: ").append(pattern.type()).append("\n");
            sb.append("  Count: ").append(pattern.occurrenceCount()).append("\n");
            if (!pattern.originalNames().isEmpty()) {
                sb.append("  Example: \"").append(pattern.sampleOriginalName()).append("\"\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String formatCategories(List<CategoryNode> categories) {
        StringBuilder sb = new StringBuilder();
        for (CategoryNode cat : categories) {
            sb.append("- ").append(cat.name()).append("\n");
            for (String sub : cat.subCategories()) {
                sb.append("  - ").append(sub).append("\n");
            }
        }
        return sb.toString();
    }
}
```

### 7. AI Request/Response Models

```java
package com.multi.vidulum.cashflow.app.categorization;

import java.util.List;

/**
 * Category structure passed to AI.
 */
public record CategoryStructure(
    List<CategoryNode> outflow,
    List<CategoryNode> inflow
) {}

public record CategoryNode(
    String name,
    List<String> subCategories
) {}

/**
 * AI categorization response.
 */
public record AiCategorizationResponse(
    List<AiCategorySuggestion> mappings
) {}

/**
 * Single AI suggestion for a pattern.
 */
public record AiCategorySuggestion(
    String pattern,
    String category,
    String parentCategory,
    int confidence,
    String reasoning,
    boolean suggestNewCategory
) {
    public CategorizationResult getResult() {
        if (confidence >= 90) return CategorizationResult.AUTO_ACCEPT;
        if (confidence >= 50) return CategorizationResult.SUGGEST;
        return CategorizationResult.MANUAL_REQUIRED;
    }
}

/**
 * How to handle the suggestion.
 */
public enum CategorizationResult {
    AUTO_ACCEPT,    // confidence >= 90% - apply automatically
    SUGGEST,        // confidence 50-89% - show to user for confirmation
    MANUAL_REQUIRED // confidence < 50% - user must choose
}
```

### 8. Categorization Orchestrator

Main service that coordinates the process.

```java
package com.multi.vidulum.cashflow.app.categorization;

import com.multi.vidulum.cashflow.domain.CashChange;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.user.domain.UserId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategorizationOrchestrator {

    private final PatternDeduplicator deduplicator;
    private final PatternMappingRepository mappingRepository;
    private final AiCategorizationService aiService;
    private final TransactionNameNormalizer normalizer;

    /**
     * Main entry point - categorize transactions for import.
     */
    public CategorizationResult categorize(
            UserId userId,
            CashFlowId cashFlowId,
            List<CashChange> transactions,
            CategoryStructure userCategories) {

        // 1. Deduplicate transactions to patterns
        List<DeduplicatedTransaction> patterns = deduplicator.deduplicate(transactions);
        log.info("Deduplicated {} transactions to {} patterns",
            transactions.size(), patterns.size());

        // 2. Check cache for known patterns
        Map<String, PatternMapping> cached = loadCachedMappings(userId, cashFlowId);

        List<DeduplicatedTransaction> uncachedPatterns = patterns.stream()
            .filter(p -> !cached.containsKey(p.normalizedName()))
            .toList();

        log.info("Found {} cached mappings, {} patterns need AI",
            patterns.size() - uncachedPatterns.size(),
            uncachedPatterns.size());

        // 3. Call AI for uncached patterns (if any)
        List<AiCategorySuggestion> aiSuggestions = List.of();
        if (!uncachedPatterns.isEmpty()) {
            aiSuggestions = aiService.categorize(uncachedPatterns, userCategories);
        }

        // 4. Merge cached + AI suggestions
        return buildResult(patterns, cached, aiSuggestions, transactions);
    }

    private Map<String, PatternMapping> loadCachedMappings(UserId userId, CashFlowId cashFlowId) {
        return mappingRepository
            .findByUserIdAndCashFlowId(userId.getId(), cashFlowId.getId())
            .stream()
            .collect(Collectors.toMap(
                PatternMapping::normalizedPattern,
                m -> m
            ));
    }

    private CategorizationResult buildResult(
            List<DeduplicatedTransaction> patterns,
            Map<String, PatternMapping> cached,
            List<AiCategorySuggestion> aiSuggestions,
            List<CashChange> transactions) {

        Map<String, AiCategorySuggestion> aiByPattern = aiSuggestions.stream()
            .collect(Collectors.toMap(AiCategorySuggestion::pattern, s -> s));

        List<TransactionSuggestion> suggestions = new ArrayList<>();

        for (DeduplicatedTransaction pattern : patterns) {
            TransactionSuggestion suggestion;

            if (cached.containsKey(pattern.normalizedName())) {
                // Use cached mapping (100% confidence)
                PatternMapping mapping = cached.get(pattern.normalizedName());
                suggestion = TransactionSuggestion.fromCache(pattern, mapping);
            } else if (aiByPattern.containsKey(pattern.normalizedName())) {
                // Use AI suggestion
                suggestion = TransactionSuggestion.fromAi(pattern,
                    aiByPattern.get(pattern.normalizedName()));
            } else {
                // No suggestion available
                suggestion = TransactionSuggestion.noSuggestion(pattern);
            }

            suggestions.add(suggestion);
        }

        return new CategorizationResult(
            suggestions,
            countByResult(suggestions, CategorizationResultType.AUTO_ACCEPT),
            countByResult(suggestions, CategorizationResultType.SUGGEST),
            countByResult(suggestions, CategorizationResultType.MANUAL_REQUIRED)
        );
    }

    private int countByResult(List<TransactionSuggestion> suggestions,
                              CategorizationResultType type) {
        return (int) suggestions.stream()
            .filter(s -> s.resultType() == type)
            .mapToInt(s -> s.transactionCount())
            .sum();
    }
}
```

### 9. Apply Categorization Logic

```java
package com.multi.vidulum.cashflow.app.categorization;

import com.multi.vidulum.cashflow.domain.CashChange;
import com.multi.vidulum.cashflow.domain.CategoryName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CategorizationApplier {

    private final PatternMappingRepository mappingRepository;
    private final TransactionNameNormalizer normalizer;

    /**
     * Apply AI/cached suggestion to a transaction.
     */
    public void applySuggestion(
            CashChange transaction,
            TransactionSuggestion suggestion,
            String userId,
            String cashFlowId) {

        if (suggestion.category() != null) {
            // Set category
            transaction.setCategory(new CategoryName(suggestion.category()));

            // Set parent category if nested
            if (suggestion.parentCategory() != null) {
                transaction.setParentCategory(new CategoryName(suggestion.parentCategory()));
            }
        } else {
            // No suggestion - mark as uncategorized
            transaction.setCategory(CategoryName.UNCATEGORIZED);
            transaction.setNeedsReview(true);
        }
    }

    /**
     * User confirms a suggestion - save to cache for future imports.
     */
    public void confirmSuggestion(
            TransactionSuggestion suggestion,
            String userId,
            String cashFlowId) {

        PatternMapping mapping = PatternMapping.builder()
            .userId(userId)
            .cashFlowId(cashFlowId)
            .normalizedPattern(suggestion.normalizedPattern())
            .categoryName(suggestion.category())
            .parentCategoryName(suggestion.parentCategory())
            .source(PatternMapping.MappingSource.AI_SUGGESTED)
            .confidence(100)  // User confirmed = 100% confidence
            .timesUsed(1)
            .created(Instant.now())
            .lastUsed(Instant.now())
            .build();

        mappingRepository.save(mapping);
    }

    /**
     * User manually categorizes - save to cache.
     */
    public void saveManualCategorization(
            String normalizedPattern,
            String category,
            String parentCategory,
            String userId,
            String cashFlowId) {

        PatternMapping mapping = PatternMapping.builder()
            .userId(userId)
            .cashFlowId(cashFlowId)
            .normalizedPattern(normalizedPattern)
            .categoryName(category)
            .parentCategoryName(parentCategory)
            .source(PatternMapping.MappingSource.USER_DEFINED)
            .confidence(100)
            .timesUsed(1)
            .created(Instant.now())
            .lastUsed(Instant.now())
            .build();

        mappingRepository.save(mapping);
    }

    /**
     * Apply suggestion to all transactions with same pattern.
     */
    public void applyToSimilar(
            String normalizedPattern,
            String category,
            String parentCategory,
            List<CashChange> allTransactions) {

        allTransactions.stream()
            .filter(txn -> normalizer.extractMerchant(txn.getName())
                .equals(normalizedPattern))
            .forEach(txn -> {
                txn.setCategory(new CategoryName(category));
                if (parentCategory != null) {
                    txn.setParentCategory(new CategoryName(parentCategory));
                }
                txn.setNeedsReview(false);
            });
    }
}
```

---

## AI Prompt Templates

### System Prompt (Full Version)

```text
You are a financial transaction categorizer for Polish bank statements.
Your task is to match transaction patterns to the user's category structure.

## Available Categories

### OUTFLOW (Wydatki)
- Mieszkanie
  - Czynsz
  - Media
  - Ubezpieczenie mieszkania
- Transport
  - Paliwo
  - Komunikacja miejska
  - Serwis samochodu
- Jedzenie
  - Zakupy spożywcze
  - Restauracje
  - Kawiarnie
- Rozrywka
  - Subskrypcje
  - Kino i teatr
  - Hobby

### INFLOW (Przychody)
- Wynagrodzenie
  - Pensja podstawowa
  - Premie
- Inne przychody
  - Zwroty
  - Odsetki

## Instructions

1. For each pattern, suggest the most appropriate category from the list above
2. Use subcategories when appropriate (e.g., "Paliwo" under "Transport")
3. Provide confidence score (0-100):
   - 90-100: Very confident - known brands, clear purpose
   - 70-89: Confident - likely correct based on name
   - 50-69: Uncertain - could be multiple categories
   - Below 50: Cannot determine - generic names, personal transfers
4. If no existing category fits, you may suggest creating a new one
5. For personal transfers (e.g., "PRZELEW DO JAN KOWALSKI"), return low confidence

## Response Format

Respond ONLY with valid JSON (no markdown, no explanation):

{
  "mappings": [
    {
      "pattern": "BIEDRONKA",
      "category": "Zakupy spożywcze",
      "parentCategory": "Jedzenie",
      "confidence": 99,
      "reasoning": "Polish grocery store chain"
    },
    {
      "pattern": "ALLEGRO",
      "category": "Zakupy online",
      "parentCategory": null,
      "confidence": 75,
      "reasoning": "E-commerce marketplace - varies",
      "suggestNewCategory": true
    },
    {
      "pattern": "PRZELEW DO KOWALSKI",
      "category": null,
      "parentCategory": null,
      "confidence": 15,
      "reasoning": "Personal transfer - purpose unknown"
    }
  ]
}
```

### User Prompt Template

```text
Categorize these transaction patterns:

- Pattern: "BIEDRONKA"
  Type: OUTFLOW
  Count: 12
  Example: "BIEDRONKA WARSZAWA UL.PUŁAWSKA 123"

- Pattern: "ORLEN"
  Type: OUTFLOW
  Count: 8
  Example: "ORLEN STACJA 4567 KRAKÓW"

- Pattern: "NETFLIX.COM"
  Type: OUTFLOW
  Count: 12
  Example: "NETFLIX.COM 2024-03-15"

- Pattern: "PRZELEW OD PRACODAWCA"
  Type: INFLOW
  Count: 12
  Example: "PRZELEW OD MINDBOX SA"

- Pattern: "PRZELEW DO KOWALSKI JAN"
  Type: OUTFLOW
  Count: 1
  Example: "PRZELEW DO KOWALSKI JAN"
```

### Expected AI Response

```json
{
  "mappings": [
    {
      "pattern": "BIEDRONKA",
      "category": "Zakupy spożywcze",
      "parentCategory": "Jedzenie",
      "confidence": 99,
      "reasoning": "Major Polish grocery chain, clearly food shopping"
    },
    {
      "pattern": "ORLEN",
      "category": "Paliwo",
      "parentCategory": "Transport",
      "confidence": 99,
      "reasoning": "Polish gas station chain"
    },
    {
      "pattern": "NETFLIX.COM",
      "category": "Subskrypcje",
      "parentCategory": "Rozrywka",
      "confidence": 99,
      "reasoning": "Video streaming subscription service"
    },
    {
      "pattern": "PRZELEW OD PRACODAWCA",
      "category": "Pensja podstawowa",
      "parentCategory": "Wynagrodzenie",
      "confidence": 95,
      "reasoning": "Transfer from employer indicates salary"
    },
    {
      "pattern": "PRZELEW DO KOWALSKI JAN",
      "category": null,
      "parentCategory": null,
      "confidence": 15,
      "reasoning": "Personal transfer to individual - purpose cannot be determined"
    }
  ]
}
```

---

## Implementation Checklist

### Phase 1: Core Infrastructure
- [ ] Create `TransactionNameNormalizer` class
- [ ] Create `DeduplicatedTransaction` record
- [ ] Create `PatternDeduplicator` service
- [ ] Create `PatternMapping` MongoDB document
- [ ] Create `PatternMappingRepository`

### Phase 2: AI Integration
- [ ] Add Spring AI dependency with OpenAI
- [ ] Create `AiPromptBuilder` class
- [ ] Create `AiCategorizationService` service
- [ ] Create request/response DTOs
- [ ] Add error handling and fallbacks

### Phase 3: Orchestration
- [ ] Create `CategorizationOrchestrator` service
- [ ] Create `CategorizationApplier` service
- [ ] Integrate with existing import flow
- [ ] Add cache lookup before AI call
- [ ] Save confirmed suggestions to cache

### Phase 4: REST API
- [ ] `POST /api/v1/categorization/preview` - get suggestions
- [ ] `POST /api/v1/categorization/confirm` - confirm suggestion
- [ ] `POST /api/v1/categorization/manual` - manual categorization
- [ ] `GET /api/v1/categorization/uncategorized` - get pending

### Phase 5: Testing
- [ ] Unit tests for normalizer
- [ ] Unit tests for deduplicator
- [ ] Integration test with mock AI
- [ ] Integration test with real OpenAI (manual)

---

## Architecture Integration Diagram

This section shows exactly where AI categorization integrates with the existing Vidulum codebase.

### Current Flow (Without AI)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         OBECNY FLOW IMPORTU                                  │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────┐     ┌──────────────────┐     ┌────────────────────────────┐
│                 │     │                  │     │                            │
│   CSV File      │────▶│ UnifiedCsv       │────▶│ AiBankCsvTransformService  │
│   (Bank Export) │     │ ImportController │     │ (Format transformation)    │
│                 │     │                  │     │                            │
└─────────────────┘     └──────────────────┘     └────────────────────────────┘
                                                              │
                        ┌─────────────────────────────────────┘
                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  AiCsvTransformationDocument                                                 │
│  ├── transformationId                                                        │
│  ├── detectedBank: "Nest Bank"                                               │
│  ├── transformedCsvContent (BankCsvRow format)                              │
│  ├── bankCategories: ["Przelewy wychodzące", "Opłaty i prowizje", ...]      │
│  └── suggestedStartPeriod: "2023-01"                                         │
└─────────────────────────────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  POST /api/v1/bank-data-ingestion/cf={cashFlowId}/staging                   │
│                                                                              │
│  StageTransactionsCommandHandler                                            │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │  For each transaction:                                                  │ │
│  │                                                                         │ │
│  │  1. Load CategoryMapping for (bankCategory, type)                      │ │
│  │                                                                         │ │
│  │  2. IF mapping exists:                                                  │ │
│  │     └── Create MappedTransactionData                                   │ │
│  │         ├── categoryName: "Mieszkanie"                                 │ │
│  │         ├── parentCategoryName: null (lub "Dom")                       │ │
│  │         └── validation: VALID                                          │ │
│  │                                                                         │ │
│  │  3. IF mapping NOT exists:                        ◄── PROBLEM!         │ │
│  │     └── validation: PENDING_MAPPING                                    │ │
│  │         └── User musi ręcznie zmapować                                 │ │
│  │                                                                         │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  StageTransactionsResult                                                     │
│  ├── status: HAS_UNMAPPED_CATEGORIES | READY_FOR_IMPORT                     │
│  ├── unmappedCategories: [                                                  │
│  │     { bankCategory: "Przelewy wychodzące", count: 180, type: OUTFLOW }   │
│  │   ]                                                                       │
│  └── User musi ręcznie skonfigurować mappings!                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Proposed Flow (With AI)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      NOWY FLOW Z AI KATEGORYZACJĄ                            │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────┐     ┌──────────────────┐     ┌────────────────────────────┐
│   CSV File      │────▶│ UnifiedCsv       │────▶│ AiBankCsvTransformService  │
│   (Bank Export) │     │ ImportController │     │ (bez zmian)                │
└─────────────────┘     └──────────────────┘     └────────────────────────────┘
                                                              │
                        ┌─────────────────────────────────────┘
                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  POST /api/v1/bank-data-ingestion/cf={cashFlowId}/staging                   │
│                                                                              │
│  StageTransactionsCommandHandler (ZMODYFIKOWANY)                            │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │                                                                         │ │
│  │  1. Load CategoryMappings (existing)                                   │ │
│  │  2. Load PatternMappings (NEW - cached AI/user mappings)               │ │
│  │  3. Load GlobalPatterns (NEW - system-wide known patterns)             │ │
│  │                                                                         │ │
│  │  For each transaction:                                                  │ │
│  │  ┌─────────────────────────────────────────────────────────────────┐   │ │
│  │  │                                                                 │   │ │
│  │  │  CHECK 1: CategoryMapping exists?                               │   │ │
│  │  │  └── YES → Use mapping (existing behavior)                      │   │ │
│  │  │                                                                 │   │ │
│  │  │  CHECK 2: PatternMapping in cache? (NEW)                        │   │ │
│  │  │  └── YES → Use cached pattern (FREE, instant)                   │   │ │
│  │  │      └── Source: USER_CONFIRMED | AI_CONFIRMED                  │   │ │
│  │  │                                                                 │   │ │
│  │  │  CHECK 3: GlobalPattern match? (NEW)                            │   │ │
│  │  │  └── YES → Use global pattern (FREE, instant)                   │   │ │
│  │  │      └── "BIEDRONKA" → "Zakupy spożywcze"                       │   │ │
│  │  │      └── "ZUS" → "Składki ZUS"                                  │   │ │
│  │  │                                                                 │   │ │
│  │  │  CHECK 4: None above → Mark for AI batch (NEW)                  │   │ │
│  │  │  └── Collect in uncategorizedBatch                              │   │ │
│  │  │                                                                 │   │ │
│  │  └─────────────────────────────────────────────────────────────────┘   │ │
│  │                                                                         │ │
│  │  AFTER LOOP: Process uncategorizedBatch with AI (NEW)                  │ │
│  │  ┌─────────────────────────────────────────────────────────────────┐   │ │
│  │  │                                                                 │   │ │
│  │  │  1. Deduplicate patterns (402 txn → 45 patterns)               │   │ │
│  │  │  2. Call AiCategorizationService.categorizeBatch()             │   │ │
│  │  │  3. Apply suggestions to StagedTransactions                    │   │ │
│  │  │                                                                 │   │ │
│  │  └─────────────────────────────────────────────────────────────────┘   │ │
│  │                                                                         │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  StageTransactionsResult (EXTENDED)                                          │
│  ├── status: READY_FOR_IMPORT | HAS_LOW_CONFIDENCE_SUGGESTIONS              │
│  ├── aiSuggestions: [                                                        │
│  │     {                                                                     │
│  │       pattern: "MINDBOX",                                                 │
│  │       suggestedCategory: "Wynagrodzenie",                                │
│  │       parentCategory: null,                                               │
│  │       confidence: 85,                                                     │
│  │       reasoning: "Regular monthly payment from company",                  │
│  │       transactionCount: 12,                                               │
│  │       status: SUGGEST (50-89%)                                           │
│  │     },                                                                    │
│  │     {                                                                     │
│  │       pattern: "BIEDRONKA",                                               │
│  │       suggestedCategory: "Zakupy spożywcze",                             │
│  │       parentCategory: "Jedzenie",                                         │
│  │       confidence: 99,                                                     │
│  │       status: AUTO_ACCEPT (≥90%)                                         │
│  │     }                                                                     │
│  │   ]                                                                       │
│  ├── autoAcceptedCount: 285 (71%)                                           │
│  ├── suggestionsToReview: 95 (24%)                                          │
│  └── manualRequired: 22 (5%)                                                │
└─────────────────────────────────────────────────────────────────────────────┘
```

### New Module Structure

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  ai_categorization module (NEW)                                              │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  domain/                                                            │    │
│  │  ├── PatternMapping.java (MongoDB document)                        │    │
│  │  │   ├── id: String                                                │    │
│  │  │   ├── userId: String                                            │    │
│  │  │   ├── cashFlowId: String (nullable = global)                    │    │
│  │  │   ├── normalizedPattern: String ("BIEDRONKA")                   │    │
│  │  │   ├── categoryName: String                                      │    │
│  │  │   ├── parentCategoryName: String (nullable)                     │    │
│  │  │   ├── source: USER_CONFIRMED | AI_CONFIRMED | GLOBAL            │    │
│  │  │   ├── confidence: int (100 for user-confirmed)                  │    │
│  │  │   └── timesUsed: int                                            │    │
│  │  │                                                                  │    │
│  │  ├── AiCategorySuggestion.java                                     │    │
│  │  │   ├── pattern: String                                           │    │
│  │  │   ├── category: String                                          │    │
│  │  │   ├── parentCategory: String                                    │    │
│  │  │   ├── confidence: int (0-100)                                   │    │
│  │  │   ├── reasoning: String                                         │    │
│  │  │   └── resultType: AUTO_ACCEPT | SUGGEST | MANUAL_REQUIRED       │    │
│  │  │                                                                  │    │
│  │  └── GlobalPattern.java (seed data)                                │    │
│  │      ├── Polish grocery chains: BIEDRONKA, LIDL, ŻABKA, etc.      │    │
│  │      ├── Gas stations: ORLEN, BP, SHELL                            │    │
│  │      ├── Streaming: NETFLIX, SPOTIFY, HBO, DISNEY+                 │    │
│  │      └── Government: ZUS, URZĄD SKARBOWY                           │    │
│  │                                                                     │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  app/                                                               │    │
│  │  ├── TransactionNameNormalizer.java                                │    │
│  │  │   └── normalize("BIEDRONKA WARSZAWA UL.X 123") → "BIEDRONKA"    │    │
│  │  │                                                                  │    │
│  │  ├── PatternDeduplicator.java                                      │    │
│  │  │   └── deduplicate(402 txn) → 45 unique patterns                 │    │
│  │  │                                                                  │    │
│  │  ├── AiCategorizationService.java                                  │    │
│  │  │   ├── categorizeBatch(patterns, userCategories) → suggestions  │    │
│  │  │   └── Uses: OpenAI GPT-4o-mini / Claude Haiku                  │    │
│  │  │                                                                  │    │
│  │  └── PatternMappingService.java                                    │    │
│  │      ├── findCachedMapping(pattern, cashFlowId)                    │    │
│  │      ├── saveConfirmedSuggestion(suggestion, userId)               │    │
│  │      └── saveUserMapping(pattern, category, userId)                │    │
│  │                                                                     │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  infrastructure/                                                    │    │
│  │  ├── PatternMappingRepository.java (MongoDB)                       │    │
│  │  ├── LlmClient.java (OpenAI/Claude API client)                     │    │
│  │  └── CategorizationPromptBuilder.java                              │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Nested Categories - Where AI Inserts Categories

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  CashFlow.inflowCategories / outflowCategories (NESTED STRUCTURE)           │
└─────────────────────────────────────────────────────────────────────────────┘

outflowCategories: List<Category>
│
├── "Uncategorized" (SYSTEM, isModifiable=false)
│   └── subCategories: []
│
├── "Mieszkanie" (USER_CREATED or AI_SUGGESTED)        ◄── AI może stworzyć
│   └── subCategories:
│       ├── "Czynsz"                                   ◄── lub jako subcategory
│       ├── "Media"
│       └── "Ubezpieczenie"
│
├── "Transport" (USER_CREATED or AI_SUGGESTED)
│   └── subCategories:
│       ├── "Paliwo"                                   ◄── AI: ORLEN → tu
│       ├── "Komunikacja miejska"
│       └── "Serwis samochodu"
│
├── "Jedzenie" (USER_CREATED or AI_SUGGESTED)
│   └── subCategories:
│       ├── "Zakupy spożywcze"                         ◄── AI: BIEDRONKA → tu
│       ├── "Restauracje"
│       └── "Kawiarnie"
│
└── "Opłaty" (USER_CREATED or AI_SUGGESTED)
    └── subCategories:
        ├── "Składki ZUS"                              ◄── AI: ZUS → tu
        ├── "Podatki"                                  ◄── AI: URZĄD SKARBOWY → tu
        └── "Bankowe"                                  ◄── AI: PROWIZJA → tu


inflowCategories: List<Category>
│
├── "Uncategorized" (SYSTEM)
│
├── "Wynagrodzenie" (USER_CREATED or AI_SUGGESTED)
│   └── subCategories:
│       ├── "Pensja"                                   ◄── AI: MINDBOX → tu
│       └── "Premie"
│
└── "Inne przychody"
    └── subCategories:
        ├── "Zwroty"
        └── "Odsetki"
```

### Key Integration Point

**File**: `src/main/java/com/multi/vidulum/bank_data_ingestion/app/commands/stage_transactions/StageTransactionsCommandHandler.java`

**Location**: Lines 136-175 (processTransaction method)

```java
// CURRENT CODE (line 140-151):
if (mapping == null) {
    return StagedTransaction.create(
        cashFlowId,
        stagingSessionId,
        originalData,
        null, // no mapped data yet
        TransactionValidation.pendingMapping(txn.bankCategory()),
        now,
        config.getStagingTtlHours()
    );
}

// NEW CODE (replace above):
if (mapping == null) {
    // CHECK 2: Pattern cache (user confirmed or AI confirmed)
    String normalizedName = normalizer.normalize(txn.name());
    Optional<PatternMapping> cachedPattern = patternMappingService
        .findCachedMapping(normalizedName, cashFlowId);

    if (cachedPattern.isPresent()) {
        return createFromCachedPattern(cachedPattern.get(), ...);  // FREE
    }

    // CHECK 3: Global patterns (system-wide known patterns)
    Optional<PatternMapping> globalPattern = patternMappingService
        .findGlobalPattern(normalizedName);

    if (globalPattern.isPresent()) {
        return createFromGlobalPattern(globalPattern.get(), ...);  // FREE
    }

    // CHECK 4: None found - add to AI batch
    forAiBatch.add(new TransactionForAi(txn, normalizedName));

    return StagedTransaction.create(
        ...,
        null,
        TransactionValidation.pendingAiCategorization(),  // NEW status
        ...
    );
}
```

### New REST Endpoints

```
NEW ENDPOINTS:

┌─────────────────────────────────────────────────────────────────────────────┐
│ POST /api/v1/bank-data-ingestion/cf={cashFlowId}/staging/{sessionId}/       │
│      accept-ai-suggestions                                                   │
│                                                                              │
│ Request:                                                                     │
│ {                                                                            │
│   "acceptedPatterns": [                                                      │
│     { "pattern": "MINDBOX", "category": "Wynagrodzenie", "accepted": true }, │
│     { "pattern": "ZYCIE", "category": "Oszczędności", "accepted": true },   │
│     { "pattern": "XYZ", "accepted": false }  // rejected - manual later     │
│   ]                                                                          │
│ }                                                                            │
│                                                                              │
│ Action:                                                                      │
│ 1. For accepted: Save to PatternMapping cache (for future imports)          │
│ 2. For accepted: Create CategoryMapping (for this import)                   │
│ 3. Trigger revalidation                                                      │
│                                                                              │
│ Response:                                                                    │
│ {                                                                            │
│   "acceptedCount": 2,                                                        │
│   "rejectedCount": 1,                                                        │
│   "savedToCache": 2,                                                         │
│   "revalidationTriggered": true                                              │
│ }                                                                            │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ GET /api/v1/categorization/patterns?cashFlowId={id}                         │
│                                                                              │
│ Returns user's cached patterns for review/editing                           │
│                                                                              │
│ Response:                                                                    │
│ {                                                                            │
│   "patterns": [                                                              │
│     { "pattern": "BIEDRONKA", "category": "Zakupy spożywcze",              │
│       "source": "GLOBAL", "timesUsed": 45 },                                │
│     { "pattern": "MINDBOX", "category": "Wynagrodzenie",                    │
│       "source": "AI_CONFIRMED", "timesUsed": 12 }                           │
│   ]                                                                          │
│ }                                                                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### MongoDB Collection

```
Collection: pattern_mappings

{
  "_id": "pm_12345",
  "userId": "user_abc",
  "cashFlowId": "cf_xyz",        // null = global pattern
  "normalizedPattern": "BIEDRONKA",
  "categoryName": "Zakupy spożywcze",
  "parentCategoryName": "Jedzenie",
  "categoryType": "OUTFLOW",
  "source": "USER_CONFIRMED",    // USER_CONFIRMED | AI_CONFIRMED | GLOBAL
  "confidence": 100,
  "timesUsed": 45,
  "created": ISODate("2026-03-01"),
  "lastUsed": ISODate("2026-03-28")
}

Indexes:
- (userId, cashFlowId, normalizedPattern) - unique
- (normalizedPattern, source) - for global pattern lookup
- (userId, lastUsed) - for cache cleanup
```

### Integration Priority Matrix

| Location | What to Add | Priority | Effort |
|----------|-------------|----------|--------|
| `StageTransactionsCommandHandler` (line 141) | Pattern cache lookup + AI batch | **CRITICAL** | Medium |
| New module `ai_categorization` | All new services | **HIGH** | High |
| `StagedTransaction` | Add `aiSuggestion` field | **HIGH** | Low |
| `TransactionValidation` | Add `PENDING_AI` status | **MEDIUM** | Low |
| `StageTransactionsResult` | Add `aiSuggestions` list | **MEDIUM** | Low |
| REST endpoint | `accept-ai-suggestions` | **MEDIUM** | Medium |
| MongoDB | Collection `pattern_mappings` | **HIGH** | Low |
| Global patterns seed | Initial data for known patterns | **LOW** | Low |

### Configuration

```yaml
# application.yml
vidulum:
  ai-categorization:
    enabled: true
    provider: openai  # or claude
    batch-size: 50
    timeout-seconds: 30

    thresholds:
      auto-accept: 90      # confidence >= 90% → auto-apply
      suggest: 50          # confidence 50-89% → show to user
      # confidence < 50% → manual required

    openai:
      api-key: ${OPENAI_API_KEY}
      model: gpt-4o-mini

    claude:
      api-key: ${ANTHROPIC_API_KEY}
      model: claude-3-5-haiku-20241022
```

---

## Alternative: AI Categorization WITHOUT Modifying Staging Handler

This section describes an alternative architecture where the **existing staging handler remains completely unchanged**. AI categorization is implemented as a separate, optional step called AFTER staging.

### Why Keep Staging Unchanged?

| Benefit | Description |
|---------|-------------|
| **Zero risk** | No changes to working production code |
| **Backward compatible** | Old flow works exactly as before |
| **Opt-in** | Users can choose whether to use AI |
| **Testable** | AI logic isolated in new handler |
| **Rollback safe** | Disable AI without touching staging |

### Architecture: POST-STAGING AI Step

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    FLOW BEZ MODYFIKACJI STAGING                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. POST /staging                    ← BEZ ZMIAN, staging jak dotychczas     │
│         │                                                                    │
│         ▼                                                                    │
│  ┌──────────────────┐                                                        │
│  │ StagedTransactions│  status: PENDING_MAPPING (brak kategorii)             │
│  │ w MongoDB         │                                                       │
│  └────────┬─────────┘                                                        │
│           │                                                                  │
│           ▼                                                                  │
│  2. POST /ai-categorize/{sessionId}  ← NOWY ENDPOINT (opcjonalny)            │
│         │                                                                    │
│         │  • Odczytuje staged transactions                                   │
│         │  • Normalizuje nazwy transakcji                                    │
│         │  • Sprawdza cache (PatternMapping, GlobalPatterns)                 │
│         │  • Wysyła nowe wzorce do AI (batch)                                │
│         │  • Zapisuje sugestie do StagedTransaction.aiSuggestion             │
│         │                                                                    │
│         ▼                                                                    │
│  ┌──────────────────┐                                                        │
│  │ StagedTransactions│  status: AI_SUGGESTED + aiSuggestion field            │
│  │ (zaktualizowane)  │                                                       │
│  └────────┬─────────┘                                                        │
│           │                                                                  │
│           ▼                                                                  │
│  3. POST /accept-suggestions         ← USER POTWIERDZA/MODYFIKUJE            │
│         │                                                                    │
│         │  • Zapisuje do PatternMapping cache (na przyszłość)                │
│         │  • Aktualizuje StagedTransaction z finalnymi kategoriami           │
│         │                                                                    │
│         ▼                                                                    │
│  4. POST /import                     ← BEZ ZMIAN, import jak dotychczas      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### New Endpoint: AI Categorize

```java
// BankDataIngestionRestController.java - NOWY ENDPOINT

@PostMapping("/cf={cashFlowId}/staging/{sessionId}/ai-categorize")
public AiCategorizationResultDto aiCategorize(
    @PathVariable CashFlowId cashFlowId,
    @PathVariable UUID sessionId) {

    return commandGateway.send(new AiCategorizeTransactionsCommand(
        cashFlowId,
        sessionId
    ));
}
```

### New Command Handler (Separate File)

```java
// AiCategorizeTransactionsCommandHandler.java - NOWY PLIK
// Nie modyfikuje StageTransactionsCommandHandler!

@CommandHandlerAnnotation
@RequiredArgsConstructor
public class AiCategorizeTransactionsCommandHandler {

    private final StagedTransactionRepository stagedRepo;
    private final PatternMappingRepository patternRepo;
    private final GlobalPatternRepository globalRepo;
    private final AiCategorizationService aiService;
    private final TransactionNameNormalizer normalizer;

    public AiCategorizationResultDto handle(AiCategorizeTransactionsCommand cmd) {

        // 1. Pobierz staged transactions (READ-ONLY)
        List<StagedTransaction> staged = stagedRepo.findBySessionId(cmd.sessionId());

        // 2. Zbuduj listę do kategoryzacji
        List<TransactionForCategorization> forCategorization = staged.stream()
            .filter(s -> s.needsCategorization())
            .map(s -> new TransactionForCategorization(
                s.getId(),
                normalizer.normalize(s.getName()),
                s.getCounterparty(),
                s.getType()
            ))
            .toList();

        // 3. Kategoryzuj (cache-first, AI-fallback)
        List<AiSuggestion> suggestions = categorize(forCategorization);

        // 4. Zapisz sugestie do StagedTransaction (UPDATE tylko aiSuggestion pole)
        for (AiSuggestion suggestion : suggestions) {
            StagedTransaction txn = stagedRepo.findById(suggestion.transactionId());
            txn.setAiSuggestion(suggestion);  // Nowe pole
            stagedRepo.save(txn);
        }

        // 5. Zwróć podsumowanie
        return new AiCategorizationResultDto(
            suggestions.stream().filter(s -> s.confidence() >= 90).count(),  // auto-accept
            suggestions.stream().filter(s -> s.confidence() >= 50 && s.confidence() < 90).count(),  // suggest
            suggestions.stream().filter(s -> s.confidence() < 50).count()  // manual required
        );
    }

    private List<AiSuggestion> categorize(List<TransactionForCategorization> transactions) {
        List<AiSuggestion> results = new ArrayList<>();
        List<TransactionForCategorization> forAi = new ArrayList<>();

        for (TransactionForCategorization txn : transactions) {
            // CHECK 1: User's cached mapping (100% confidence)
            Optional<PatternMapping> cached = patternRepo.findByPattern(txn.normalizedName());
            if (cached.isPresent()) {
                results.add(AiSuggestion.fromCache(txn.id(), cached.get()));
                continue;
            }

            // CHECK 2: Global pattern (90% confidence)
            Optional<GlobalPattern> global = globalRepo.findByPattern(txn.normalizedName());
            if (global.isPresent()) {
                results.add(AiSuggestion.fromGlobal(txn.id(), global.get()));
                continue;
            }

            // CHECK 3: Need AI
            forAi.add(txn);
        }

        // Batch AI call for remaining
        if (!forAi.isEmpty()) {
            results.addAll(aiService.categorizeBatch(forAi));
        }

        return results;
    }
}
```

### Minimal Change to StagedTransaction

```java
// StagedTransaction.java - TYLKO dodanie nowego pola

public class StagedTransaction {
    // ... existing fields (bez zmian) ...

    // NOWE POLE (nullable)
    private AiSuggestion aiSuggestion;

    @Data
    public static class AiSuggestion {
        String suggestedCategory;
        String suggestedSubCategory;  // nullable
        int confidence;               // 0-100
        String source;                // "GLOBAL", "PATTERN_CACHE", "AI"
        String reasoning;             // AI explanation
    }

    public boolean needsCategorization() {
        return this.mappedData == null || this.mappedData.getCategoryName() == null;
    }
}
```

### UI Flow with POST-STAGING AI

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          UI FLOW (FRONTEND)                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  STEP 1: User uploads CSV                                                    │
│  ───────────────────────────────────────────────────────────────────────────│
│  POST /csv-import/upload                                                     │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  STEP 2: System creates staging session                                      │
│  ───────────────────────────────────────────────────────────────────────────│
│  POST /staging (automatically or manually)                                   │
│  Returns: { sessionId, hasUnmappedCategories: true }                        │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  STEP 3: UI shows option                                                     │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                                                                       │  │
│  │  📋 Staging session utworzona                                         │  │
│  │                                                                       │  │
│  │  Masz 180 transakcji bez kategorii.                                   │  │
│  │                                                                       │  │
│  │  Jak chcesz je skategoryzować?                                        │  │
│  │                                                                       │  │
│  │  [🤖 Użyj AI (zalecane)]     [📝 Mapuj ręcznie]                       │  │
│  │                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  IF USER CLICKS "Użyj AI":                                                   │
│  ───────────────────────────────────────────────────────────────────────────│
│  POST /ai-categorize/{sessionId}                                             │
│  [Loading: "AI kategoryzuje transakcje..."]                                  │
│                                                                              │
│  Returns:                                                                    │
│  {                                                                           │
│    autoAccepted: 145,    // ≥90% confidence                                 │
│    suggestions: 25,       // 50-89% confidence                              │
│    manualRequired: 10     // <50% confidence                                │
│  }                                                                           │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  STEP 4: Show AI results                                                     │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                                                                       │  │
│  │  🤖 AI Categorization Results                                         │  │
│  │                                                                       │  │
│  │  ✅ Auto-accepted (≥90% pewności): 145 transakcji                    │  │
│  │     Te transakcje zostały automatycznie skategoryzowane               │  │
│  │                                                                       │  │
│  │  ⚠️ Do potwierdzenia (50-89% pewności): 25 transakcji                │  │
│  │     [Przejrzyj sugestie]                                              │  │
│  │                                                                       │  │
│  │  ❓ Wymagają ręcznej kategoryzacji (<50%): 10 transakcji              │  │
│  │     [Kategoryzuj ręcznie]                                             │  │
│  │                                                                       │  │
│  │  [Kontynuuj import]                                                   │  │
│  │                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  IF USER CLICKS "Mapuj ręcznie":                                             │
│  ───────────────────────────────────────────────────────────────────────────│
│  → Stary flow: POST /mappings (bez zmian)                                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Benefits Summary

| Aspect | Benefit |
|--------|---------|
| **StageTransactionsCommandHandler** | ZERO ZMIAN |
| **RevalidateCommandHandler** | ZERO ZMIAN |
| **ImportCommandHandler** | ZERO ZMIAN |
| **Backward compatibility** | 100% - stary flow działa bez AI |
| **Testowanie** | Łatwe mockowanie AI serwisu |
| **Opt-in** | User może pominąć AI i mapować ręcznie |
| **Rollback** | Wyłączenie AI nie wymaga zmian w staging |
| **Debugowanie** | AI logic izolowana w osobnym handlerze |

### Files to Create (No Modifications to Existing)

```
NEW FILES:
├── app/commands/ai_categorize/
│   ├── AiCategorizeTransactionsCommand.java
│   └── AiCategorizeTransactionsCommandHandler.java
│
├── app/commands/accept_suggestions/
│   ├── AcceptAiSuggestionsCommand.java
│   └── AcceptAiSuggestionsCommandHandler.java
│
├── domain/
│   ├── AiSuggestion.java (embedded in StagedTransaction)
│   ├── PatternMapping.java (MongoDB document)
│   └── GlobalPattern.java (MongoDB document)
│
├── infrastructure/
│   ├── PatternMappingRepository.java
│   └── GlobalPatternRepository.java
│
└── BankDataIngestionRestController.java  ← ADD 2 new endpoints only
```

### Implementation Order

| Phase | Task | Changes to Existing Code |
|-------|------|--------------------------|
| 1 | Create `PatternMapping` MongoDB document | None |
| 2 | Create `PatternMappingRepository` | None |
| 3 | Create `GlobalPattern` seed data | None |
| 4 | Add `aiSuggestion` field to `StagedTransaction` | **Minimal** - add 1 field |
| 5 | Create `AiCategorizeTransactionsCommandHandler` | None |
| 6 | Create `AcceptAiSuggestionsCommandHandler` | None |
| 7 | Add 2 endpoints to REST controller | **Minimal** - add 2 methods |
| 8 | Update `StageTransactionsResult` DTO | **Minimal** - add optional field |

### Comparison: Two Approaches

| Aspect | Modify Staging | POST-STAGING (This Section) |
|--------|---------------|------------------------------|
| Code changes | Medium (modify handler) | Minimal (add new handlers) |
| Risk | Medium | **Low** |
| Performance | Slightly better (1 pass) | Slightly slower (2 passes) |
| Complexity | Higher (mixed logic) | **Lower (separated logic)** |
| Testability | Harder | **Easier** |
| Rollback | Requires code change | **Feature flag** |
| Backward compat | Needs testing | **Guaranteed** |

### Recommendation

**Use POST-STAGING approach** for initial implementation because:

1. **Zero risk** to existing working code
2. **Easier to test** - AI logic completely isolated
3. **Opt-in** - users can skip AI if they prefer manual
4. **Faster to market** - no regression testing needed
5. **Rollback** - just disable endpoint, no code changes

Later, if performance becomes an issue (unlikely for <1000 transactions), consider migrating to in-staging approach.

---

## Related Documents

- [AI Use Cases Overview](./AI_USE_CASES.md)
- [AI Categorization Plan (Original)](./AI_CATEGORIZATION_PLAN.md)
- [Bank Data Ingestion Pipeline](../bank-data-ingestion-pipeline.md)
- [Unified CSV Import UI Integration](../UNIFIED_CSV_IMPORT_UI_INTEGRATION.md)
