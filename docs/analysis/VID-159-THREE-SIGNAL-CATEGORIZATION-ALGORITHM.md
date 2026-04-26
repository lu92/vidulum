# VID-159: Three-Signal Categorization Algorithm (merchant + bankCategory + description)

**Date**: 2026-04-25
**Status**: Proposal
**Context**: Current AI categorization ignores bankCategory from banks like Pekao, resulting in 87.7% Uncategorized. This design proposes using merchant, bankCategory, and description together as three cooperative signals.

## Problem Recap

- Pekao CSV provides 35 rich bankCategories (e.g., "Artykuły spożywcze", "Restauracje i kawiarnie")
- Current AI categorization groups by merchant name only, generates patternMappings + bankCategoryMappings separately
- bankCategoryMappings cover only 6/35 categories
- Result: 694/791 (87.7%) → Uncategorized

See also: [VID-157-TROUBLESHOOTING-BANK-CATEGORIES.md](VID-157-TROUBLESHOOTING-BANK-CATEGORIES.md)

## Core Idea

Instead of treating merchant and bankCategory as independent signals, group transactions by **(merchant, bankCategory) pairs** and let AI analyze them together. The description field acts as a tiebreaker when merchant and bankCategory conflict.

## Signal Classification

### Merchant Signal (strength = merchantConfidence)

| Strength | Confidence | Examples | Meaning |
|----------|-----------|----------|---------|
| STRONG | ≥0.8 | ZABKA, NETFLIX, UBER, ZUS, MCDONALDS | AI recognized the company with high certainty |
| MEDIUM | 0.5-0.7 | BP-OPAL, STACJA PALIW IWA-TANK, PAYPRO | Probably a company, but name is noisy |
| WEAK | ≤0.3 | UL. JAGIELLONCZYKA, LOPUSZANSKA, W101 | Not a merchant — it's an address/terminal/code |

### BankCategory Signal (strength = category type)

| Type | Examples | Usefulness |
|------|----------|------------|
| SEMANTIC | "Artykuły spożywcze", "Restauracje i kawiarnie", "Sport", "Paliwo", "Taxi", "Opieka medyczna" | Very high — bank recognized the spending type |
| CHANNEL | "Internet, TV, telefon", "Zakupy przez internet" | Partial — describes HOW payment was made, not WHAT for |
| GENERIC | "Inne", "Bez kategorii" | None — bank couldn't classify |

### Description Signal (strength = semantic content)

| Type | Examples | Usefulness |
|------|----------|------------|
| SEMANTIC | "czynsz za styczeń", "składki ZUS", "Faktura VAT nr 8348", "oplata za odpady" | High — reveals the PURPOSE of transaction |
| TECHNICAL | "BLIK REF 91769951500", "*********0015010" | None — reference/card number |
| EMPTY | "" | None |

## Decision Matrix — Which Signal Dominates

### CASE A: Strong merchant + semantic bankCategory → BOTH AGREE

```
merchant=ZABKA (0.95) + bankCat="Artykuły spożywcze" + desc="***0015010"
→ Signals confirm each other
→ Category: "Żywność" / Subcategory: "Zakupy spożywcze"
→ Confidence: VERY HIGH (98)
```

Real data: ZABKA (5 txns), MCDONALDS (4 txns), UBER (13 txns), XTREME FITNESS (24 txns)

### CASE B: Strong merchant + generic bankCategory → MERCHANT DECIDES

```
merchant=IFIRMA (0.8) + bankCat="Inne" + desc="Faktura VAT nr 8348"
→ bankCategory useless, merchant + description decide
→ Category: "Firma" / Subcategory: "Księgowość"
→ Confidence: HIGH (85)

merchant=BADOO (0.95) + bankCat="Inne" + desc=""
→ bankCategory useless, description empty, merchant alone decides
→ Category: "Rozrywka" / Subcategory: "Subskrypcje"
→ Confidence: HIGH (80)
```

Real data: BADOO (5 txns in "Inne"), IFIRMA (35 txns in "Inne")

### CASE C: Weak merchant + semantic bankCategory → BANK CATEGORY DECIDES

```
merchant=UL. JAGIELLONCZYKA (0.3) + bankCat="Artykuły spożywcze" + desc="***0015010"
→ Merchant is just an address, not a company name
→ bankCategory is the only meaningful signal
→ Category: "Żywność" / Subcategory: "Zakupy spożywcze"
→ Confidence: HIGH (85)
```

Real data: 21 low-confidence merchants in "Artykuły spożywcze", 25 in "Restauracje i kawiarnie"
**This case alone accounts for ~100 transactions that currently go to Uncategorized.**

### CASE D: Conflicting signals → DESCRIPTION CORRECTS

```
merchant=DENIS (0.3) + bankCat="Internet, TV, telefon" + desc="Przelew na telefon. Fryzura"
→ bankCategory is MISLEADING (bank classified payment CHANNEL, not PURPOSE)
→ description "Fryzura" reveals actual purpose
→ Category: "Usługi osobiste" (not "Telekomunikacja")
→ Confidence: MEDIUM (70)
```

Real data: DENIS (3 txns as "Internet, TV, telefon" — all are actually hairdresser payments via phone transfer)

### CASE E: Payment intermediary → BANK CATEGORY > MERCHANT

```
merchant=PAYU (0.8) + bankCat="Zakupy przez internet" + desc="BLIK REF"
→ PAYU is an intermediary, not the actual seller
→ bankCategory provides better context than merchant
→ Category: "Zakupy" / Subcategory: "Zakupy online"
→ Confidence: MEDIUM (75)

merchant=PAYU (0.8) + bankCat="Bez kategorii" + desc="Cinema City Mokotow"
→ Both merchant and bankCategory weak, but description reveals seller
→ Category: "Rozrywka" / Subcategory: "Kino"
→ Confidence: MEDIUM (65)
```

Real data: PAYU (8 txns split across 2 bankCategories), PAYPRO (8 txns split across 2)

### CASE F: All signals weak → UNKNOWN

```
merchant=LUCJAN BIK (0.3) + bankCat="Bez kategorii" + desc="zycie"
→ All signals too weak to categorize
→ Needs additional signal (account number for SELF_TRANSFER detection)
→ Category: PENDING_MAPPING
→ Confidence: LOW (<30)
```

### CASE G: No merchant + semantic bankCategory → CLASSIFICATION-DRIVEN

```
merchant=null + bankCat="Opłaty bankowe" + desc="Prowizja za przelew"
→ classification=BANK_FEE confirms bankCategory
→ Auto-categorization (pre-filter, no AI needed)
→ Category: "Zarządzanie kontem" / Subcategory: "Opłaty bankowe"
→ Confidence: HIGH (95)
```

## Change 1: Pattern Deduplication by (merchant, bankCategory) Pairs

### Current Grouping (by merchant only)

```
Group "PAYU" (8 txns):
  bankCategories: [Zakupy przez internet ×6, Bez kategorii ×2]  ← MIXED!
  → AI sees ONE group with conflicting context

Group "DENIS" (4 txns):
  bankCategories: [Internet, TV, telefon ×3, Bez kategorii ×1]  ← MIXED!
  → AI sees ONE group with conflicting context
```

### Proposed Grouping (by merchant + bankCategory)

```
Group "PAYU | Zakupy przez internet" (6 txns)
  → Homogeneous: intermediary + online shopping context

Group "PAYU | Bez kategorii" (2 txns)
  → Homogeneous: intermediary + unknown → AI must use description

Group "DENIS | Internet, TV, telefon" (3 txns)
  → Homogeneous: weak merchant + channel category → AI checks description

Group "DENIS | Bez kategorii" (1 txn)
  → Homogeneous: both weak → AI checks description
```

Impact on Pekao data: 154 unique merchants → 162 unique (merchant, bankCategory) pairs. Minimal increase, but **every group is contextually homogeneous**.

## Change 2: Updated Prompt Format

### Current format sent to AI

```
[5 txns, 234.50 PLN] ZABKA
  | name: "ZABKA Z7472 K.2 WARSZAWA"
  | merchant: "ZABKA" (95%)
  | title: "*********0015010"
  | bank: "Artykuły spożywcze"
```

### Proposed format

```
[5 txns, 234.50 PLN] ZABKA | Artykuły spożywcze
  | merchant: "ZABKA" (95%) — STRONG
  | bankCategory: "Artykuły spożywcze" — SEMANTIC
  | description: "*********0015010" — TECHNICAL (ignore)
  | signal: MERCHANT + BANKCAT AGREE

[3 txns, 450.00 PLN] DENIS | Internet, TV, telefon
  | merchant: "DENIS" (30%) — WEAK
  | bankCategory: "Internet, TV, telefon" — CHANNEL (misleading!)
  | description: "Przelew na telefon 48661***888. Fryzura" — SEMANTIC
  | signal: DESCRIPTION CORRECTS BANKCAT
```

## Change 3: New AI Response Format

### Current response (two separate mapping types)

```json
{
  "patternMappings": [
    { "pattern": "ZABKA", "suggestedCategory": "...", ... }
  ],
  "bankCategoryMappings": [
    { "bankCategory": "Artykuły spożywcze", "targetCategory": "...", ... }
  ]
}
```

### Proposed response (unified context mappings + fallbacks)

```json
{
  "categoryStructure": {
    "outflow": [
      { "name": "Żywność", "subCategories": ["Zakupy spożywcze", "Restauracje", "Alkohol"] },
      { "name": "Transport", "subCategories": ["Paliwo", "Taxi", "Transport publiczny"] },
      { "name": "Rozrywka", "subCategories": ["Kino", "Sport", "Subskrypcje"] }
    ]
  },

  "contextMappings": [
    {
      "merchant": "ZABKA",
      "bankCategory": "Artykuły spożywcze",
      "targetCategory": "Zakupy spożywcze",
      "parentCategory": "Żywność",
      "type": "OUTFLOW",
      "confidence": 98,
      "dominantSignal": "BOTH_AGREE",
      "reason": "Merchant ŻABKA is a grocery store, confirmed by bankCategory"
    },
    {
      "merchant": "PAYU",
      "bankCategory": "Zakupy przez internet",
      "targetCategory": "Zakupy online",
      "parentCategory": "Zakupy",
      "type": "OUTFLOW",
      "confidence": 75,
      "dominantSignal": "BANK_CATEGORY",
      "reason": "PAYU is payment intermediary, bankCategory provides actual context"
    },
    {
      "merchant": "DENIS",
      "bankCategory": "Internet, TV, telefon",
      "targetCategory": "Usługi osobiste",
      "parentCategory": "Usługi",
      "type": "OUTFLOW",
      "confidence": 70,
      "dominantSignal": "DESCRIPTION",
      "reason": "bankCategory misleading (phone transfer channel), description 'Fryzura' reveals actual purpose"
    }
  ],

  "bankCategoryFallbacks": [
    {
      "bankCategory": "Artykuły spożywcze",
      "defaultTarget": "Zakupy spożywcze",
      "parentCategory": "Żywność",
      "type": "OUTFLOW",
      "confidence": 90,
      "reason": "Fallback for any unmatched transaction with this bankCategory"
    },
    {
      "bankCategory": "Restauracje i kawiarnie",
      "defaultTarget": "Restauracje",
      "parentCategory": "Żywność",
      "type": "OUTFLOW",
      "confidence": 90,
      "reason": "Fallback for any unmatched restaurant transaction"
    }
  ]
}
```

Key differences:
- **`contextMappings`** replaces `patternMappings` + `bankCategoryMappings` — one unified format per (merchant, bankCategory) pair
- **`dominantSignal`** indicates which signal decided: `MERCHANT`, `BANK_CATEGORY`, `DESCRIPTION`, `BOTH_AGREE`
- **`bankCategoryFallbacks`** (NEW) — AI MUST define a default mapping for EVERY semantic bankCategory. This catches future/unknown merchants that share the same bankCategory

## Change 4: Mapping Application Algorithm (3-step cascade)

After accept-ai, revalidation applies mappings using this cascade:

```
For each PENDING_MAPPING transaction:

  Step 1: CONTEXT MATCH — (merchant, bankCategory) pair
    match = contextMappings.find(
      m.merchant == txn.merchant AND m.bankCategory == txn.bankCategory
    )
    if found → map to match.targetCategory

  Step 2: MERCHANT MATCH — merchant only (bankCategory differs)
    match = contextMappings.find(m.merchant == txn.merchant)
    if found → map (confidence = match.confidence * 0.8)

  Step 3: BANK CATEGORY FALLBACK — bankCategory only (unknown merchant)
    match = bankCategoryFallbacks.find(m.bankCategory == txn.bankCategory)
    if found AND bankCategory is SEMANTIC → map

  Step 4: No match → PENDING_MAPPING (truly uncategorizable)
```

### Application flow diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│ Transaction: ZABKA, bankCat="Artykuły spożywcze"                    │
│                                                                     │
│  Step 1: (ZABKA, Art.spożywcze) in contextMappings?                │
│  → YES: "Zakupy spożywcze" (conf 98)                              │
│  → ✅ MAPPED                                                       │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ Transaction: NOWY_SKLEP (never seen), bankCat="Art.spożywcze"       │
│                                                                     │
│  Step 1: (NOWY_SKLEP, Art.spożywcze) in contextMappings?           │
│  → NO                                                               │
│  Step 2: NOWY_SKLEP in any contextMapping?                          │
│  → NO                                                               │
│  Step 3: "Art.spożywcze" in bankCategoryFallbacks?                  │
│  → YES: "Zakupy spożywcze" (conf 90)                              │
│  → ✅ MAPPED (via fallback — bankCategory rescued it!)              │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ Transaction: PAYU, bankCat="Bez kategorii", desc="Cinema City"      │
│                                                                     │
│  Step 1: (PAYU, Bez kategorii) in contextMappings?                  │
│  → YES: AI saw desc → "Rozrywka/Kino" (conf 65)                   │
│  → ✅ MAPPED (description-driven during AI analysis)                │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ Transaction: ???, bankCat="Bez kategorii", desc="BLIK REF"          │
│                                                                     │
│  Step 1: no match                                                   │
│  Step 2: no match                                                   │
│  Step 3: "Bez kategorii" is GENERIC → skip fallback                │
│  → ❌ PENDING_MAPPING (all signals too weak — acceptable)           │
└─────────────────────────────────────────────────────────────────────┘
```

## New CashFlow Scenario (0 existing categories)

```
1. STAGING:
   → All transactions PENDING_MAPPING (unchanged — no categories to match)

2. AI CATEGORIZE (updated prompt):
   AI receives: 162 groups (merchant, bankCategory) from Pekao
   AI does NOT receive: existing categories (none exist)

   AI produces:
   a) categoryStructure — NEW hierarchy from scratch
      (same as today, but better informed by bankCategories)

   b) contextMappings — per (merchant, bankCategory) pair
      ZABKA + "Art.spożywcze" → "Zakupy spożywcze" / "Żywność"
      UBER + "Taxi" → "Taxi" / "Transport"
      ...for ALL 162 pairs

   c) bankCategoryFallbacks — per semantic bankCategory
      "Art.spożywcze" → "Zakupy spożywcze" / "Żywność"
      "Restauracje" → "Restauracje" / "Żywność"
      "Sport" → "Sport" / "Rozrywka"
      ...for ALL ~28 semantic bankCategories
      (skips: "Inne", "Bez kategorii")

3. ACCEPT AI:
   → Creates categories in CashFlow
   → Stores contextMappings + bankCategoryFallbacks in DB

4. REVALIDATE:
   → Applies 3-step cascade algorithm
   → Step 1: contextMappings matches ~50% of transactions
   → Step 3: bankCategoryFallbacks matches another ~28%
   → Only ~10% remains PENDING (generic "Inne"/"Bez kategorii" without description)

5. FORCE UNCATEGORIZED:
   → Only ~10% instead of 87.7%
```

## One Merchant, Multiple BankCategories — Real Data Analysis

From Pekao (lu100) data, 4 merchants have transactions in multiple bankCategories:

### PAYU (8 txns, 2 bankCategories)

| bankCategory | Count | Description | Reason |
|-------------|-------|-------------|--------|
| Zakupy przez internet | 6 | "BLIK REF 91769951500" | Bank recognized BLIK online payment |
| Bez kategorii | 2 | "/OPT/X/ PayU XX4625989777XX" | Bank couldn't classify card payment via PayU |

**Analysis**: PAYU is a payment intermediary. Bank classifies by payment channel, not by purchase type. With description "Cinema City" in one case, AI can extract the actual seller.

### DENIS (4 txns, 2 bankCategories)

| bankCategory | Count | Description | Reason |
|-------------|-------|-------------|--------|
| Internet, TV, telefon | 3 | "Przelew na telefon 48661***888. Fryzura" | Bank classified CHANNEL (phone transfer) |
| Bez kategorii | 1 | "Fryzura" | Direct transfer, bank couldn't classify |

**Analysis**: Same person (hairdresser), but bank classifies by payment channel. All 4 are actually "Usługi osobiste". Description "Fryzura" is the correct signal in all cases.

### GMINA MIELEC (2 txns, 2 bankCategories)

| bankCategory | Count | Description | Reason |
|-------------|-------|-------------|--------|
| Śmieci | 1 | "oplata za gospodarowanie odpadami komunalnymi" | Waste collection fee |
| Bez kategorii | 1 | "Decyzja nr POL.3127.1.1212.2025" | Administrative decision — unclear purpose |

**Analysis**: Same institution, genuinely different services. These SHOULD map to different categories. The (merchant, bankCategory) pair correctly separates them.

### Conclusion

One merchant having multiple bankCategories is **correct and desirable** — it means the system can produce more granular categorization than merchant-only grouping. The (merchant, bankCategory) pair captures this nuance naturally.

## Estimated Impact on Pekao Data (lu100)

```
CURRENT RESULT:
  Uncategorized:  694/791 (87.7%)
  Mapped:          97/791 (12.3%)

ESTIMATED RESULT AFTER CHANGE:
  contextMapping match:    ~400/791 (50%)   — merchant+bankCat pair known
  bankCategoryFallback:    ~220/791 (28%)   — same bankCat, new merchant
  Existing mappings:        ~97/791 (12%)   — as currently
  Uncategorized:            ~74/791 (10%)   — "Inne"+"Bez kat" without desc

Improvement: 87.7% → ~10% Uncategorized
```

## Implementation Changes

| Component | Change | Complexity |
|-----------|--------|------------|
| `PatternDeduplicator` | Group by (merchant, bankCategory) instead of merchant only | Medium |
| `AiCategorizationPromptBuilder` | New prompt with signal matrix + require bankCategoryFallbacks | Medium |
| `AiCategorizationResponseParser` | Parse `contextMappings` + `bankCategoryFallbacks` | Medium |
| `AiCategorizationResult` | New records: `ContextMapping`, `BankCategoryFallback` | Low |
| `RevalidateStagingCommandHandler` | 3-step cascade: context → merchant → bankCat fallback | Medium |
| `AcceptAiSuggestionsCommandHandler` | Store new mapping types to DB | Low |

No existing endpoints or flows change. The improvement is in the quality of mappings produced by AI and the algorithm for applying them.

## Weaknesses, Corner Cases & Quality Metrics

### Signal Availability Matrix (from real data)

Before evaluating the algorithm, we must understand what signals are actually available:

**Pekao (791 transactions):**

| Signal Combination | Count | % | Implication |
|---|---|---|---|
| strong merchant + semantic bankCat + no desc | 238 | 30.1% | Both merchant and bankCat useful, desc can't help |
| weak merchant + semantic bankCat + no desc | 227 | 28.7% | **bankCategory is the ONLY useful signal** |
| strong merchant + generic bankCat + semantic desc | 87 | 11.0% | Merchant + description work, bankCat useless |
| weak merchant + generic bankCat + semantic desc | 68 | 8.6% | Only description helps |
| strong merchant + generic bankCat + no desc | 53 | 6.7% | Only merchant helps |
| weak merchant + generic bankCat + no desc | 52 | 6.6% | **Nothing helps — truly uncategorizable** |
| no merchant + any | 50 | 6.3% | **No merchant signal at all** |
| weak merchant + semantic bankCat + semantic desc | 16 | 2.0% | All three signals available |

**Nest Bank (402 transactions):**

| Signal Combination | Count | % | Implication |
|---|---|---|---|
| strong merchant + semantic bankCat + semantic desc | 168 | 41.8% | All signals available (ideal) |
| weak merchant + generic bankCat + semantic desc | 116 | 28.9% | Only description helps |
| strong merchant + generic bankCat + semantic desc | 90 | 22.4% | Merchant + description work |
| no merchant + semantic bankCat + semantic desc | 26 | 6.5% | bankCat + description |
| no merchant + generic bankCat + semantic desc | 2 | 0.5% | Only description |

**Key asymmetry**: Pekao has 74% technical/empty descriptions vs Nest Bank has 100% semantic descriptions. The description-as-tiebreaker strategy works well for Nest Bank but is largely irrelevant for Pekao.

### WEAKNESS 1: Bank Misclassification — bankCategoryFallback Propagates Bank Errors

**This is the most serious weakness.** Real evidence from Pekao data:

**"Kino i teatr" (22 transactions) — 13/22 (59%) incorrectly classified by the bank:**

| Merchant | Count | Actual Category | Bank Said |
|----------|-------|----------------|-----------|
| KLASZTOR DOMINIKANOW (monastery) | 6 | Donations/Religion | "Kino i teatr" |
| EDUKACJA Z WARTOSCIAM (education) | 5 | Education | "Kino i teatr" |
| PARAFIA DUCHA SWIETEGO (parish) | 2 | Donations/Religion | "Kino i teatr" |
| MULTIKINO, HELIOS, CINEMA CITY | 9 | Actually cinema | "Kino i teatr" |

If `bankCategoryFallback` blindly maps "Kino i teatr" → "Rozrywka/Kino", monasteries and parishes end up categorized as cinema.

**Mitigation**: `contextMappings` handles this correctly — AI sees merchant=KLASZTOR DOMINIKANOW and overrides the bank's category. The risk is limited to **new/unknown merchants** going through `bankCategoryFallback` (Step 3) where the merchant signal is too weak to correct the bank's error.

**"Sport" vs "Hobby" — inconsistent bank taxonomy:**

| Merchant | bankCategory | Both are gyms |
|----------|-------------|---------------|
| XTREME FITNESS (20 txns) | "Sport" | Yes |
| ZDROFIT (18 txns) | "Hobby" | Yes |

The bank uses different categories for the same business type. With `contextMappings`, AI can map both to "Sport/Siłownia", but `bankCategoryFallback` would create two separate mappings.

**Estimated false positive rate from bank errors: ~30-50 transactions (4-6%) in Pekao data.**

### WEAKNESS 2: Description Is Largely Useless for Pekao

```
Description quality:
                    PEKAO                NEST BANK
                    ─────                ─────────
Semantic:           208/791 (26%)        402/402 (100%)
Technical:          528/791 (67%)        0/402 (0%)
Empty:              55/791 (7%)          0/402 (0%)
```

The proposed algorithm uses description as a tiebreaker (CASE D: conflicting signals). This only works for 26% of Pekao transactions. For the remaining 74%, the decision must rely entirely on merchant + bankCategory with no fallback.

This means the DENIS case ("Fryzura" in description correcting misleading "Internet, TV, telefon") is the exception, not the rule. Most Pekao transactions with misleading bankCategories have only `*********0015010` as description.

### WEAKNESS 3: 227 Transactions Depend SOLELY on bankCategory

```
weak_merch + semantic_bankcat + no_desc: 227 transactions (28.7%)
```

These 227 transactions have:
- Merchant = address/terminal (confidence ≤0.3) → useless as identifier
- Description = `*********0015010` → technical, useless
- bankCategory = the **only** categorization signal

Example: `LOPUSZANSKA 22 WARSZAWA` + bankCat="Artykuły spożywcze" + desc="*********0015010"

For these transactions, `bankCategoryFallback` is the only option. If the bank was wrong (as with 59% error rate in "Kino i teatr"), there's no second signal to verify. However, most semantic bankCategories (Artykuły spożywcze, Paliwo, Taxi, Transport publiczny) are much more reliable than "Kino i teatr".

### WEAKNESS 4: Mapping Complexity Increase

```
Current system:
  patternMappings:       ~14 records (per merchant)
  bankCategoryMappings:   ~6 records (per bankCat)
  Total:                 ~20 records

Proposed system:
  contextMappings:       ~162 records (per merchant+bankCat pair)
  bankCategoryFallbacks:  ~28 records (per semantic bankCat)
  Total:                 ~190 records (9.5x more)
```

More mappings means more storage, more edge cases during updates, and potential conflicts between contextMapping and fallback results. Needs careful conflict resolution logic.

### WEAKNESS 5: No Improvement for CSV Without Bank Categories

For CSV files where bankCategory is AI-inferred (like Nest Bank), the algorithm is neutral:

```
                          CURRENT      PROPOSED       DELTA
Nest Bank Uncategorized:  34 (8.5%)    ~34 (8.5%)     0
Nest Bank Coverage:       91.5%        91.5%          0
```

The AI-inferred bankCategories from enrichment are the AI's own earlier conclusions — the categorization prompt gains no new information. This limits the value of the change to banks that provide their own categories (Pekao, mBank, PKO BP, etc.).

### WEAKNESS 6: "Over-Trust" Risk — Treating bankCategory as Ground Truth

The bank is a commercial product with its own taxonomy, not a gold standard:

| Problem | Real Example |
|---------|-------------|
| Bank uses wrong MCC code | KLASZTOR DOMINIKANOW → "Kino i teatr" (MCC for entertainment?) |
| Bank taxonomy differs from user's mental model | Bank: "Sport", User wants: "Zdrowie i fitness" |
| Bank is inconsistent across institutions | Pekao: "Artykuły spożywcze", Nest Bank: no category at all |
| Bank categories change over time | Same merchant may get different category after bank updates |
| Bank categorizes by payment channel | "Internet, TV, telefon" for a phone transfer to a hairdresser |

## Quality Metrics Comparison

### Coverage, Precision, and Quality Score

```
PEKAO (791 txns, 35 bankCategories from bank)
────────────────────────────────────────────────────────────────────
                            CURRENT         PROPOSED        DELTA
Coverage (mapped/total):    12.3%           87.1%           +74.8pp
Uncategorized:              87.7%           12.9%           -74.8pp
Precision (correct mappings): ~95%          ~85-90%         -5-10pp
BankCat utilization:        6/35 (17%)      28/35 (80%)     +63pp
Merchant utilization:       14/154 (9%)     154/154 (100%)  +91pp
False positives (bank err): ~0              ~30-50 txns     NEW RISK
Token cost:                 5940            ~6300           +6%
AI calls:                   1               1               same
```

```
NEST BANK (402 txns, 0 bankCategories from bank)
────────────────────────────────────────────────────────────────────
                            CURRENT         PROPOSED        DELTA
Coverage:                   91.5%           91.5%           same
Uncategorized:              8.5%            8.5%            same
Precision:                  ~85%            ~85%            same
Token cost:                 5426            ~5700           +5%
```

### Categorization Quality Score (CQS)

```
CQS = Coverage × Precision × (1 - FalsePositiveRate)

PEKAO:
  Current:   0.123 × 0.95 × 1.00 = 0.117  (11.7%)
  Proposed:  0.871 × 0.87 × 0.95 = 0.720  (72.0%)
  Improvement: +604% ↑

NEST BANK:
  Current:   0.915 × 0.85 × 1.00 = 0.778  (77.8%)
  Proposed:  0.915 × 0.85 × 1.00 = 0.778  (77.8%)
  Improvement: 0%
```

### Rescue Analysis — What bankCategoryFallback Saves

From 694 currently Uncategorized transactions in Pekao, bankCategoryFallback would rescue 483:

| bankCategory | Rescued from Uncategorized |
|---|---|
| Artykuły spożywcze | 112 |
| Restauracje i kawiarnie | 65 |
| Zakupy przez internet | 46 |
| Przelew wewnętrzny | 30 |
| Paliwo | 26 |
| Sport | 24 |
| Myjnia, przeglądy i naprawy | 24 |
| Kino i teatr | 22 |
| Transport publiczny | 19 |
| Hobby | 18 |
| Opieka medyczna | 14 |
| Taxi | 13 |
| Kosmetyki | 10 |
| + 16 other categories | 60 |
| **Total rescued** | **483 / 694** |

Remaining Uncategorized (211 transactions):
- "Inne" (173): includes BADOO (139), parking (25), misc (9) — merchant is strong, bankCat useless → contextMapping handles these, not fallback
- "Bez kategorii" (38): mixed bag, some with strong merchants (APPLE, CONTABO) → contextMapping handles

**Net estimated Uncategorized after full algorithm: ~102/791 (12.9%)** — down from 694 (87.7%).

### Trade-off Summary

```
╔══════════════════════════════════════════════════════════════════════╗
║                     KEY TRADE-OFF                                    ║
║                                                                      ║
║  GAIN:   694 transactions RESCUED from Uncategorized                 ║
║  COST:   ~30-50 potentially MISCLASSIFIED (from bank errors)         ║
║                                                                      ║
║  Of those ~40 misclassified:                                         ║
║  - ~15 would be caught by contextMappings (AI sees merchant name)   ║
║  - ~25 truly wrong (weak merchant + wrong bankCat + no desc)        ║
║                                                                      ║
║  Net: 592 correctly rescued vs 25 incorrectly classified             ║
║  Ratio: 24:1 (24 correct rescues per 1 false positive)              ║
║                                                                      ║
║  Verdict: WORTH IT — even with bank errors, vastly better than      ║
║  87.7% sitting in Uncategorized                                      ║
╚══════════════════════════════════════════════════════════════════════╝
```

## Related Documents

- [VID-157-TROUBLESHOOTING-BANK-CATEGORIES.md](VID-157-TROUBLESHOOTING-BANK-CATEGORIES.md) — Root cause analysis for Pekao (lu100)
- [VID-158-TROUBLESHOOTING-ENRICHMENT-QUALITY.md](VID-158-TROUBLESHOOTING-ENRICHMENT-QUALITY.md) — Root cause analysis for Nest Bank (lu101)
