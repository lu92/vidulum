# VID-157: Troubleshooting — Bank Categories Lost During Import

**Date**: 2026-04-24
**Context**: Import of Pekao CSV (`Lista_operacji_20260111_013400.csv`, 791 transactions)
**Result**: 694/791 transactions ended up as "Uncategorized" despite rich bank categories from Pekao

## Problem Summary

Bank Pekao provided 35 unique `bankCategory` values in the CSV (e.g., "Artykuły spożywcze", "Restauracje i kawiarnie", "Sport").
After the full import pipeline, 694 out of 791 transactions were mapped to "Uncategorized" — the bank's categorization was effectively discarded.

## Timeline of Events

| Time (UTC) | Action | Result |
|------------|--------|--------|
| 20:53:13 | Staging session created | CashFlow had **0 categories** defined |
| 20:53:53 | AI categorize started | 791 transactions analyzed |
| 20:54:28 | AI categorize completed | 5940 tokens, ~$0.06 |
| 20:55:07 | Accept AI + force-uncategorized | Only 26 category mappings created |
| 20:55:31 | Import started | |
| 20:55:50 | Import completed | 694 → Uncategorized, 97 → real categories |

## Root Cause Analysis

### Stage 1: Staging (Priority 0 failure)

In `StageTransactionsCommandHandler` (lines 248-284), **Priority 0** tries to match `bankCategory` against **existing CashFlow categories** (case-insensitive).

**Problem**: The CashFlow was freshly created with `POST /cash-flow/with-history` — it had **zero categories**. Every `bankCategory` from Pekao failed to match. All 791 transactions received `PENDING_MAPPING` status.

### Stage 2: AI Categorization (partial coverage)

AI categorize produced two types of suggestions:
- **Pattern suggestions**: based on merchant names (BADOO, PLUS, PGE, etc.)
- **Bank category suggestions**: mapping bankCategory → target category

**Problem**: AI only generated mappings for **6 out of 35** Pekao bankCategories:

| Mapped (6) | Not Mapped (29 — largest shown) |
|---|---|
| Internet, TV, telefon (33 txns) | Inne (173 txns) |
| Sprzedaż (2) | Artykuły spożywcze (112) |
| Prąd (2) | Bez kategorii (92) |
| Ubrania (4) | Restauracje i kawiarnie (65) |
| Śmieci (7) | Zakupy przez internet (46) |
| Podatki (3) | Przelew wewnętrzny (30) |
| | Paliwo (26), Sport (24), Myjnia (24), Kino (22), Transport (19), Hobby (18), Opieka medyczna (14), Taxi (13), Kosmetyki (10), ... |

The AI focused on merchant-name pattern matching rather than leveraging the bank's own category taxonomy.

### Stage 3: Force Uncategorized (fallback)

After accepting partial AI suggestions, `force-uncategorized` was called to resolve remaining `PENDING_MAPPING` transactions. This mapped all 694 unresolved transactions to "Uncategorized" and set `aiCategorizationStatus = SKIPPED`.

## Data Evidence

### Staged transactions — bankCategory distribution (all 791 had bankCategory set)

```
Inne:                           173
Artykuły spożywcze:             112
Bez kategorii:                   92
Restauracje i kawiarnie:         65
Zakupy przez internet:           46
Internet, TV, telefon:           33
Przelew wewnętrzny:              30
Paliwo:                          26
Sport:                           24
Myjnia, przeglądy i naprawy:    24
Kino i teatr:                    22
Transport publiczny:             19
Hobby:                           18
Opieka medyczna:                 14
Taxi:                            13
Kosmetyki:                       10
Naprawy i remonty:                8
Wypłata z bankomatu:              8
Opłaty bankowe:                   7
Lekarstwa:                        7
Uroda, fryzjer, kosmetyczka:      6
Książki:                          6
Usługi (pralnia, krawiec,...):    4
Ubrania:                          4
Multimedia:                       3
Sprzęt AGD i RTV:                 2
Puby i kluby:                     2
Sprzedaż:                         2
Prąd:                              2
Obuwie:                            2
Hotele:                            2
Alkohol:                           1
Podatki:                           1
Wyposażenie:                       1
Kary i mandaty:                    1
Śmieci:                            1
```

### Final mappedData.categoryName distribution after import

```
Uncategorized:          694  (87.7%)
Internet, TV, telefon:   46
Darowizny:               33
Śmieci:                   7
Ubrania:                  4
Podatki:                  3
Prąd:                     2
Sprzedaż:                 2
```

## Architectural Gap

The pipeline has **no "trust the bank's categories" pathway**. Three mechanisms exist, none covers this case:

1. **Priority 0 (direct match)** — requires categories to pre-exist in CashFlow. Fails for fresh CashFlows.
2. **AI categorize** — groups by merchant patterns, not by bankCategory. Produces partial bankCategory suggestions.
3. **Force uncategorized** — nuclear option that discards all unmapped bankCategories.

### What's Missing

An option to **auto-create CashFlow categories from bankCategory values** — when a bank like Pekao provides a rich taxonomy, the system should be able to adopt it directly (bankCategory → CashFlow category, 1:1) without requiring AI re-categorization from scratch.

Alternatively, the AI categorization prompt should generate 1:1 mapping suggestions for ALL bankCategories that have reasonable names (not just "Inne" or "Bez kategorii").

## Additional Finding: Bank Pekao Misclassifies 5.9% of Transactions

Beyond the architectural gap, analysis of the actual bank-provided categories revealed that **Pekao actively misclassifies 47/791 (5.9%) transactions**. This means even if the pipeline correctly adopted all bank categories, some would be wrong.

### Bank Error Types

| Error Type | Count | % | Example |
|---|---|---|---|
| **Wrong specific category** | 19 | 2.4% | KLASZTOR DOMINIKANOW (monastery) → "Kino i teatr" |
| **Inconsistent taxonomy** | 18 | 2.3% | ZDROFIT (gym) → "Hobby" vs XTREME FITNESS (gym) → "Sport" |
| **Channel confusion** | 10 | 1.3% | DENIS (hairdresser via phone transfer) → "Internet, TV, telefon" |

Additionally, **265/791 (33.5%) are generic** — bank used "Inne" or "Bez kategorii" instead of a specific category. This includes BADOO (139 txns) in "Inne" and charitable organizations (44 txns) in "Bez kategorii".

### "Kino i teatr" — Worst Category (59% Error Rate)

| Merchant | Count | Bank Said | Actually Is | Likely Cause |
|----------|-------|-----------|-------------|-------------|
| KLASZTOR DOMINIKANOW | 6 | Kino i teatr | Religia/Darowizny | MCC code for entertainment venues |
| EDUKACJA Z WARTOŚCIAMI | 5 | Kino i teatr | Edukacja | MCC code miscategorization |
| PARAFIA DUCHA SWIETEGO | 2 | Kino i teatr | Religia/Darowizny | MCC code for entertainment venues |
| MULTIKINO, HELIOS, CINEMA CITY | 9 | Kino i teatr | **Correct** | — |

Only 9/22 transactions (41%) in "Kino i teatr" are actually cinema.

### Overall Bank Category Quality

```
Correctly categorized:     479/791 (60.6%)
Generic (lazy):            265/791 (33.5%)  ← bank didn't try
Wrong specific:             19/791 (2.4%)   ← bank actively wrong
Channel confusion:          10/791 (1.3%)   ← classified by payment method
Inconsistent:               18/791 (2.3%)   ← same business, different category

Precision (specific cats):  479/526 = 91.1%
Coverage (non-generic):     526/791 = 66.5%
```

### Implication for the Pipeline

Bank categories should be treated as **strong hints, not ground truth**. The categorization-prompt should:
1. Use bankCategory as input signal alongside merchant and description
2. Cross-verify: if merchant name contradicts bankCategory (KLASZTOR ≠ Kino), AI should override
3. Normalize inconsistencies: ZDROFIT + XTREME FITNESS → same category
4. Infer categories for generic "Inne"/"Bez kategorii" using merchant + description

See [VID-160-BANK-VS-AI-CATEGORY-QUALITY-COMPARISON.md](VID-160-BANK-VS-AI-CATEGORY-QUALITY-COMPARISON.md) for full comparison of bank vs AI category quality.

## Fix Priorities and Implementation Order

### Recommended Order

```
PR 1: Three-Signal Categorization Algorithm (VID-159)
      → Fixes the main problem: Pekao 87.7% → ~10% Uncategorized
      → Pekao improves immediately, Nest Bank neutral

PR 2: SELF_TRANSFER Detection Fix (VID-158)
      → Fixes Nest Bank: 111 self-transfers correctly classified
      → Pekao already handled by Three-Signal (bankCategory "Przelew wewnętrzny")

PR 3: Enrichment-Level Bank Category Verification (VID-160)
      → Corrects ~35 bank MCC errors (klasztory, niespójne siłownie)
      → Can be woven into categorization-prompt improvement
```

### Why Three-Signal FIRST, SELF_TRANSFER Second

Three-Signal alone (without self-transfer fix) already handles both datasets well:

**Pekao (791 txns) — Three-Signal alone:**

```
                    BEFORE          THREE-SIGNAL ONLY     DELTA
Mapped:             97 (12.3%)      709 (89.6%)           +612 txns
Uncategorized:      694 (87.7%)     82 (10.4%)            -612 txns
```

The 30 "Przelew wewnętrzny" transactions in Pekao have a **semantic bankCategory** from the bank → `bankCategoryFallback` catches them. Self-transfer detection fix is **not needed** for Pekao because the bank already labeled them correctly.

Remaining 82 Uncategorized = "Bez kategorii" (57 txns with weak merchants like GZGK, PLUS, TIMELEFT) + "Inne" (25 txns with weak merchants like parking meters). These are genuinely hard to categorize.

**Nest Bank (402 txns) — Three-Signal alone:**

```
                    BEFORE          THREE-SIGNAL ONLY     DELTA
Mapped:             368 (91.5%)     285 (70.9%)           -83 txns (!)
Uncategorized:      34 (8.5%)       117 (29.1%)           +83 txns (!)
```

Wait — Three-Signal makes Nest Bank **worse**? No — the numbers shift because Three-Signal changes grouping logic, but the 117 Uncategorized are the same 111 LUCJAN BIK + 6 others that were already poorly categorized (mapped to "Inne wydatki" catch-all before). The real quality doesn't decrease — it just surfaces the problem instead of hiding it in "Inne wydatki".

**The SELF_TRANSFER fix (PR 2) then cleans up the remaining 111:**

```
Nest Bank after PR 1 + PR 2:
  Mapped:           396/402 (98.5%)
  Uncategorized:    6/402 (1.5%)    ← only truly unclear transactions
```

### Impact Matrix

```
                  THREE-SIGNAL (PR 1)    + SELF_TRANSFER (PR 2)    + VERIFICATION (PR 3)
                  ─────────────────────  ────────────────────────  ─────────────────────
PEKAO:
  Mapped:         709/791 (89.6%)        709/791 (89.6%)           ~744/791 (94.1%)
  Uncategorized:  82 (10.4%)             82 (10.4%)                ~47 (5.9%)
  
NEST BANK:
  Mapped:         285/402 (70.9%)        396/402 (98.5%)           396/402 (98.5%)
  Uncategorized:  117 (29.1%)            6 (1.5%)                  6 (1.5%)
  
COMBINED:
  Mapped:         994/1193 (83.3%)       1105/1193 (92.6%)         1140/1193 (95.6%)
  Uncategorized:  199 (16.7%)            88 (7.4%)                 53 (4.4%)
```

## BUG: Standalone Categories — bankCategoryFallback Targets Not Created in CashFlow

**Date discovered**: 2026-04-25 (during Three-Signal manual testing)
**Severity**: Medium — categories work but without parent-child hierarchy
**Affects**: 8 out of 34 categories in Pekao test (24 txns in "Myjnia", 40 in "Zakupy przez internet", etc.)

### Symptom

After accept-ai + import, some categories appear as **standalone roots** instead of being nested under their parent. Example: "Myjnia, przeglądy i naprawy" should be under "Usługi" but is created as a top-level category.

### How to Reproduce

1. Register user, create CashFlow with-history (startPeriod=2023-01)
2. Transform `Lista_operacji_20260111_013400.csv` (Pekao, 791 txns)
3. Import to staging → 791 transactions staged
4. AI categorize → returns `categoryStructure` + `bankCategoryFallbacks`
5. Build accept-ai request from AI response (using categoryStructure for acceptedCategories)
6. Accept AI → creates categories + mappings → revalidate
7. Force uncategorized → import
8. Check CashFlow `outflowCategories`:
   - "Myjnia, przeglądy i naprawy" is a ROOT category (no parent)
   - Should be under "Usługi" parent

### Root Cause — Step-by-Step

```
STEP 1: AI generates categoryStructure
─────────────────────────────────────────────────
AI creates hierarchy based on contextMappings (strong merchants only):

  categoryStructure.outflow:
    📁 Usługi
       └─ Naprawy i remonty     ← JUNONA (conf=0.5) had contextMapping
       └─ Usługi (pralnia...)   ← KILEZ (conf=0.5) had contextMapping
       ❌ Myjnia, przeglądy...  ← NOT HERE (PROCAR conf=0.3, MYJNIA conf=0.5
                                    — no strong merchant → no contextMapping
                                    → AI didn't add to structure)

STEP 2: AI generates bankCategoryFallbacks
─────────────────────────────────────────────────
AI correctly creates fallback WITH parent info:

  bankCategoryFallback:
    bankCat="Myjnia, przeglądy i naprawy"
    → target="Myjnia, przeglądy i naprawy"
    → parent="Usługi"                        ← AI KNOWS the correct parent
    → confidence=95

STEP 3: accept-ai request is built (Python script / frontend)
─────────────────────────────────────────────────
Script builds acceptedCategories from categoryStructure ONLY:

  acceptedCategories = [
    {name: "Usługi", parent: null},              ✅ from structure
    {name: "Naprawy i remonty", parent: "Usługi"}, ✅ from structure
    {name: "Usługi (pralnia...)", parent: "Usługi"}, ✅ from structure
  ]

  ❌ Script does NOT add categories from bankCategoryFallbacks!
  ❌ "Myjnia, przeglądy..." with parent "Usługi" is MISSING

Script builds acceptedBankCategoryMappings from fallbacks:

  acceptedBankCategoryMappings = [
    {bankCat: "Myjnia, przeglądy...",
     target: "Myjnia, przeglądy...",
     parent: "Usługi"}                           ✅ mapping exists
  ]

STEP 4: AcceptAiSuggestionsHandler processes request
─────────────────────────────────────────────────
Handler creates categories from acceptedCategories:
  ✅ "Usługi" (root)
  ✅ "Naprawy i remonty" (under "Usługi")
  ✅ "Usługi (pralnia...)" (under "Usługi")
  ❌ "Myjnia, przeglądy..." — NOT CREATED (not in acceptedCategories)

Handler creates CategoryMappings from acceptedBankCategoryMappings:
  ✅ "Myjnia, przeglądy..." → "Myjnia, przeglądy..." (mapping in DB)

STEP 5: Revalidation
─────────────────────────────────────────────────
Revalidation tries to apply mapping:
  bankCat="Myjnia, przeglądy..." → target="Myjnia, przeglądy..."
  → Looks for "Myjnia, przeglądy..." in CashFlow categories
  → NOT FOUND (wasn't created in step 4)
  → 24 transactions remain PENDING_MAPPING

STEP 6: Force-uncategorized + Import
─────────────────────────────────────────────────
Force-uncategorized maps remaining to "Uncategorized"
BUT import job creates missing categories ad-hoc:
  import.result.categoriesCreated = ["Myjnia, przeglądy i naprawy", ...]
  → Created as ROOT (no parent) because import doesn't know the parent
  → 24 transactions get category "Myjnia, przeglądy..." but it's standalone
```

### Affected Categories (Pekao test, 8 categories)

| Category | Correct Parent | Txns | Status |
|---|---|---|---|
| Myjnia, przeglądy i naprawy | Usługi | 24 | Standalone (should be child of Usługi) |
| Zakupy przez internet | Zakupy | 40 | Standalone (should be child of Zakupy) |
| Internet, TV, telefon | Inne wydatki | 21 | Standalone |
| Sport | Rozrywka | 4 | Standalone (should be child of Rozrywka) |
| Książki | Zakupy | 6 | Standalone |
| Uroda, fryzjer, kosmetyczka | Zakupy | 6 | Standalone |
| Hotele | Zakupy | 2 | Standalone |
| Kary i mandaty | Inne wydatki | 1 | Standalone |

All 8 have correct `parent` info in `bankCategoryFallbacks` but the accept-ai request builder doesn't use it.

### Fix

When building the accept-ai request, also add categories from `bankCategoryFallbacks` to `acceptedCategories`:

```python
# Current: only categories from categoryStructure
for node in structure.outflow:
    acceptedCategories.append({name: node.name, parent: None})
    for sub in node.subCategories:
        acceptedCategories.append({name: sub, parent: node.name})

# Fix: ALSO add categories from bankCategoryFallbacks
for fallback in bankCategoryFallbacks:
    if fallback.defaultTarget not in already_added:
        acceptedCategories.append({
            name: fallback.defaultTarget,
            parent: fallback.parentCategory,  # AI already knows the parent
            type: fallback.type
        })
```

This can be done in:
- **Frontend** (when building accept-ai request) — simplest
- **AcceptAiSuggestionsHandler** (auto-create categories from fallback targets before revalidation)
- **REST controller** (enrich the request before passing to handler)

### Impact of Fix

```
BEFORE FIX:  8 standalone categories, hierarchy partially broken
AFTER FIX:   0 standalone, all 34 categories properly nested

Category tree changes:
  📁 Usługi
     └─ Naprawy i remonty
     └─ Usługi (pralnia...)
     └─ Myjnia, przeglądy i naprawy  ← ADDED under Usługi
  📁 Zakupy
     └─ Ubrania, Obuwie, Kosmetyki, ...
     └─ Zakupy przez internet         ← ADDED under Zakupy
     └─ Książki                        ← ADDED under Zakupy
     └─ Uroda, fryzjer, kosmetyczka   ← ADDED under Zakupy
     └─ Hotele                         ← ADDED under Zakupy
  📁 Rozrywka
     └─ Hobby, Kino i teatr, ...
     └─ Sport                          ← ADDED under Rozrywka
```

## Affected Code

| Component | File | Issue |
|-----------|------|-------|
| Priority 0 matching | `StageTransactionsCommandHandler.java:248-284` | Requires pre-existing CashFlow categories |
| AI categorize | `AiCategorizationService.java` | Doesn't generate 1:1 bankCategory suggestions for all categories |
| Force uncategorized | `ForceUncategorizedCommandHandler.java` | Blanket override, no partial application |
| Revalidation | `RevalidateStagingCommandHandler.java` | Only re-checks existing mappings |
| Accept-AI request builder | Frontend / REST controller | Does not add fallback targets to acceptedCategories |

## Related Documents

- [VID-158-TROUBLESHOOTING-ENRICHMENT-QUALITY.md](VID-158-TROUBLESHOOTING-ENRICHMENT-QUALITY.md) — Nest Bank enrichment issues + SELF_TRANSFER fix design
- [VID-159-THREE-SIGNAL-CATEGORIZATION-ALGORITHM.md](VID-159-THREE-SIGNAL-CATEGORIZATION-ALGORITHM.md) — Three-Signal algorithm design + weaknesses + metrics
- [VID-160-BANK-VS-AI-CATEGORY-QUALITY-COMPARISON.md](VID-160-BANK-VS-AI-CATEGORY-QUALITY-COMPARISON.md) — Bank vs AI category quality comparison
