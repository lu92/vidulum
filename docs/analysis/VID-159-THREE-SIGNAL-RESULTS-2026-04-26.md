# VID-159: Three-Signal Categorization — Results Baseline (2026-04-26)

**Date**: 2026-04-26
**Version**: Three-Signal v1 (PR 1 — before SELF_TRANSFER fix, before standalone fix)
**Purpose**: Baseline metrics for measuring future improvements

## Test Setup

| Parameter | Pekao (lu100) | Nest Bank (lu101) |
|---|---|---|
| UserId | U10000003 | U10000004 |
| CashFlowId | CF10000001 | CF10000002 |
| CSV file | Lista_operacji_20260111_013400.csv | lista_operacji_20260111.csv |
| Transactions | 791 | 402 |
| Bank categories in CSV | 35 (from Pekao bank) | 0 (AI-inferred by enrichment) |
| AI tokens used | 11,970 | 9,124 |

## Summary Metrics

```
                          PEKAO (lu100)                    NEST BANK (lu101)
                          v1(old)  → v2(three-signal)     v1(old)  → v2(three-signal)
                          ───────    ─────────────────     ───────    ─────────────────
Categorized:              12.3%      88.5%  (+76.2pp)     91.5%      92.3%  (+0.8pp)
Uncategorized:            87.7%      11.5%  (-76.2pp)     8.5%       7.7%   (-0.8pp)
"Inne" (catch-all):       n/a        27.9%                41.3%      50.7%
Unique categories:        8          33     (+25)         8          7      (-1)
Parent categories:        0          7      (+7)          0          1      (+1)
Nested children:          0          23     (+23)         0          5      (+5)
BankCat coverage:         17%        86%    (+69pp)       n/a        n/a
```

---

## PEKAO (lu100, CF10000001) — Detailed Analysis

### Category Hierarchy (OUTFLOW)

```
📁 Żywność — 177 txns (22.4%)
   └─ Zakupy spożywcze: 112 (14.2%) ████████
   └─ Restauracje i kawiarnie: 65 (8.2%) █████

📁 Rozrywka — 77 txns (9.7%)
   └─ Hobby: 38 (4.8%) ███
   └─ Kino i teatr: 22 (2.8%) ██
   └─ Multimedia: 15 (1.9%) █
   └─ Puby i kluby: 2 (0.3%)

📁 Transport — 58 txns (7.3%)
   └─ Paliwo: 26 (3.3%) ██
   └─ Transport publiczny: 19 (2.4%) █
   └─ Taxi: 13 (1.6%) █

📁 Zdrowie — 21 txns (2.7%)
   └─ Opieka medyczna: 14 (1.8%) █
   └─ Lekarstwa: 7 (0.9%)

📁 Zakupy — 19 txns (2.4%)
   └─ Kosmetyki: 10 (1.3%) █
   └─ Ubrania: 4 | Obuwie: 2 | Sprzęt AGD: 2 | Wyposażenie: 1

📁 Usługi — 18 txns (2.3%)
   └─ Naprawy i remonty: 8 (1.0%)
   └─ Uroda, fryzjer, kosmetyczka: 6 (0.8%)
   └─ Usługi (pralnia, krawiec, szewc,...): 4 (0.5%)

📁 Opłaty obowiązkowe — 12 txns (1.5%)
   └─ Śmieci: 8 | Prąd: 2 | Podatki: 1 | Kary i mandaty: 1

📄 Inne: 221 (27.9%) █████████████████        ← standalone catch-all
📄 Uncategorized: 91 (11.5%) ███████

Standalone categories (should be nested — see standalone fix):
📄 Zakupy przez internet: 40 (5.1%) ███       ← should be under Zakupy
📄 Myjnia, przeglądy i naprawy: 24 (3.0%) ██ ← should be under Usługi
📄 Internet, TV, telefon: 18 (2.3%) █         ← should be under Rozrywka/Usługi
📄 Książki: 6 (0.8%)                          ← should be under Zakupy
📄 Sport: 4 (0.5%)                            ← should be under Rozrywka
📄 Hotele: 2 (0.3%)                           ← should be under Zakupy
📄 Alkohol: 1 (0.1%)                          ← should be under Rozrywka
```

### Category Hierarchy (INFLOW)

```
📄 Sprzedaż: 2
```

### Cross-reference: bankCategory → Final Category

| bankCategory (from bank) | → Final Category | Txns | Assessment |
|---|---|---|---|
| Artykuły spożywcze | → Zakupy spożywcze | 112 | ✅ Correct |
| Restauracje i kawiarnie | → Restauracje i kawiarnie | 65 | ✅ Correct |
| Inne (127) | → Inne | 127 | ✅ Correct (bank gave generic) |
| Bez kategorii (85) | → Inne | 85 | ✅ Correct (bank gave generic) |
| Inne (46) | → Uncategorized | 46 | ⚠️ Weak merchants in generic bankCat |
| Zakupy przez internet | → Zakupy przez internet | 40 | ✅ Correct |
| Przelew wewnętrzny (30) | → Uncategorized | 30 | ❌ Awaits SELF_TRANSFER fix |
| Paliwo | → Paliwo | 26 | ✅ Correct |
| Myjnia, przeglądy | → Myjnia, przeglądy | 24 | ✅ Correct |
| Kino i teatr | → Kino i teatr | 22 | ⚠️ Contains monasteries/parishes (bank MCC error) |
| Sport (20) | → Hobby | 20 | ✅ AI correctly merged with Hobby (both are gyms) |
| Transport publiczny | → Transport publiczny | 19 | ✅ Correct |
| Hobby | → Hobby | 18 | ✅ Correct |
| Internet, TV, telefon (18) | → Internet, TV, telefon | 18 | ✅ Correct |
| Internet, TV, telefon (12) | → Multimedia | 12 | ✅ AI separated Netflix/Claude from phone transfers |
| Taxi | → Taxi | 13 | ✅ Correct |
| Kosmetyki | → Kosmetyki | 10 | ✅ Correct |
| Wypłata z bankomatu (8) | → Uncategorized | 8 | ❌ Should be auto-categorized (CASH_WITHDRAWAL) |
| Naprawy i remonty | → Naprawy i remonty | 8 | ✅ Correct |
| Lekarstwa | → Lekarstwa | 7 | ✅ Correct |
| Opłaty bankowe (7) | → Uncategorized | 7 | ❌ Should be auto-categorized (BANK_FEE) |

### Positive Surprises

1. **Sport → Hobby merge**: AI recognized XTREME FITNESS (bankCat "Sport") and ZDROFIT (bankCat "Hobby") as same business type and merged into "Hobby". Fixes bank's inconsistent taxonomy.

2. **Internet, TV, telefon → split into 2**: AI separated NETFLIX/CLAUDE.AI (→ "Multimedia") from phone transfers (→ "Internet, TV, telefon"). Intelligent channel vs content distinction.

3. **33 bankCategoryFallbacks** generated (vs 6 in old algorithm) — 450% improvement in bankCategory coverage.

4. **All 24 contextMappings** had `dominantSignal: BOTH_AGREE` — merchant and bankCategory confirmed each other.

### Known Issues (awaiting future PRs)

| Issue | Txns | Root Cause | Fix |
|---|---|---|---|
| "Inne" = 221 (27.9%) | 221 | BADOO(127) + "Bez kategorii"(85) + misc(9) | Better merchant mapping |
| Przelew wewnętrzny → Uncategorized | 30 | SELF_TRANSFER not implemented | PR SELF_TRANSFER |
| Wypłata z bankomatu → Uncategorized | 8 | Auto-categorization pre-filter gap | Fix pre-filter |
| Opłaty bankowe → Uncategorized | 7 | Auto-categorization pre-filter gap | Fix pre-filter |
| 7 standalone categories | 95 | Fallback targets not in acceptedCategories | Fix standalone |

### Quality Score

```
Coverage:     ★★★★★  88.5% categorized (from 12.3%)
Accuracy:     ★★★★☆  bankCategories correctly mapped for ~90% of transactions
Hierarchy:    ★★★★☆  7 parent + 23 children (7 standalone to fix)
Intelligence: ★★★★☆  Sport+Hobby merge, Netflix→Multimedia split
Overall:      ★★★★☆  Massive improvement, remaining issues are planned
```

---

## NEST BANK (lu101, CF10000002) — Detailed Analysis

### Category Hierarchy (OUTFLOW)

```
📁 Opłaty obowiązkowe — 167 txns (41.5%)
   └─ Podatki i składki: 89 (22.1%) █████████████
   └─ Spłata kredytu: 55 (13.7%) ████████
   └─ Mieszkanie: 15 (3.7%) ██
   └─ Opłaty urzędowe: 6 (1.5%) █
   └─ Ubezpieczenia: 2 (0.5%)

📄 Inne: 204 (50.7%) ██████████████████████████████   ← MAIN PROBLEM
📄 Uncategorized: 31 (7.7%) █████
```

### Category Hierarchy (INFLOW)

```
📄 Podatki i składki: 89 (flat, no parent)
📄 Inne: 204
```

### Cross-reference: bankCategory → Final Category

| bankCategory (AI-inferred) | → Final Category | Txns | Assessment |
|---|---|---|---|
| Uncategorized (113) | → Inne | 113 | ⚠️ 111 are LUCJAN BIK self-transfers |
| Inne (91) | → Inne | 91 | ✅ Correct (AI gave generic) |
| Podatki i składki | → Podatki i składki | 89 | ✅ Correct |
| Spłata kredytu | → Spłata kredytu | 55 | ✅ Correct |
| Opłaty bankowe (26) | → Uncategorized | 26 | ❌ Should be "Opłaty bankowe" |
| Mieszkanie | → Mieszkanie | 15 | ✅ Correct |
| Opłaty urzędowe | → Opłaty urzędowe | 6 | ✅ Correct |
| Uncategorized (4) | → Uncategorized | 4 | ✅ Correct (truly unclear) |
| Ubezpieczenia | → Ubezpieczenia | 2 | ✅ Correct |
| Telekomunikacja (1) | → Uncategorized | 1 | ❌ PLAY should be "Telekomunikacja" |

### Breakdown of "Inne" (204 txns = 50.7%)

| Merchant | Txns | Should Be | Awaits |
|---|---|---|---|
| LUCJAN BIK | 111 | Przelewy własne (SELF_TRANSFER) | PR SELF_TRANSFER |
| MINDBOX | 52 | Wynagrodzenie / Przychody | Better enrichment |
| IFIRMA | 35 | Usługi firmowe / Księgowość | Better enrichment |
| TRADINGVIEW | 1 | Subskrypcje | Minor |
| PGE ENERGETYKA | 1 | Media / Prąd | Minor |
| BLUE MEDIA, PAYPRO, ANGELIKA KLOS, DANTEX | 4 | Various | Minor |

### Breakdown of "Uncategorized" (31 txns = 7.7%)

| Merchant | Txns | Problem | Fix |
|---|---|---|---|
| NULL (BANK_FEE) | 28 | classification=BANK_FEE but not auto-categorized | Fix auto-categorization pre-filter |
| ZYDIE | 2 | Unknown merchant | Truly uncategorizable |
| PLAY | 1 | Telekomunikacja not mapped | Fix bankCat mapping |

### Quality Score

```
Coverage:     ★★★★☆  92.3% categorized (slight improvement from 91.5%)
Accuracy:     ★★★★☆  correct where AI had strong signal
Hierarchy:    ★★★☆☆  1 parent + 5 children — simple but logical
"Inne" bag:   ★★☆☆☆  50.7% — too large, but 111/204 is SELF_TRANSFER (fixable)
Overall:      ★★★☆☆  Neutral impact (expected), dramatic improvement after SELF_TRANSFER
```

---

## Combined Metrics — Improvement Tracking

### Current State (2026-04-26, Three-Signal v1)

```
                    PEKAO           NEST BANK       COMBINED
Categorized:        700/791 (88.5%) 371/402 (92.3%) 1071/1193 (89.8%)
Uncategorized:      91/791 (11.5%)  31/402 (7.7%)   122/1193 (10.2%)
"Inne" catch-all:   221/791 (27.9%) 204/402 (50.7%) 425/1193 (35.6%)
Effective quality:  479/791 (60.6%) 167/402 (41.5%) 646/1193 (54.2%)
  (categorized excl. "Inne")
```

### Planned Improvements (in priority order)

#### Fix 1: Standalone Categories (minor fix)

**Problem**: 7 categories created as root instead of being nested under parent (e.g., "Myjnia, przeglądy i naprawy" standalone instead of under "Usługi"). AI provides correct `parentCategory` in `bankCategoryFallbacks` but the accept-ai request builder doesn't add fallback targets to `acceptedCategories`.

**What to change**: When building accept-ai request, also add categories from `bankCategoryFallbacks` to `acceptedCategories` with their `parentCategory`.

**Impact**: Pekao 7 standalone → 0. Hierarchy improves from 7 parent to ~10 parent. No change in coverage (transactions already categorized, just hierarchy is flat).

**Affected txns**: 95 txns get proper parent (Zakupy przez internet→Zakupy, Myjnia→Usługi, Sport→Rozrywka, etc.)

**Details**: [VID-157 — BUG: Standalone Categories](VID-157-TROUBLESHOOTING-BANK-CATEGORIES.md)

#### Fix 2: SELF_TRANSFER Detection

**Problem**: 111 self-transfers "Lucjan Bik Pekao" (description "zycie") in Nest Bank classified as UNKNOWN instead of SELF_TRANSFER. They end up in "Inne" catch-all. Enrichment-prompt doesn't receive account numbers so it can't detect transfers to own accounts.

**What to change**: Two-layer fix:
1. Rule-based pre-filter (before AI): if transaction name contains account owner name AND type=OUTFLOW → classify as SELF_TRANSFER. Zero AI cost, catches 111/111.
2. Pass `sourceAccountOwner` and `targetAccountNumber` to `TransactionForEnrichment` for AI-assisted edge cases.

**Impact**: Nest Bank "Inne" drops from 204 (50.7%) to ~93 (23.1%). Nest Uncategorized drops from 31 to ~31 (self-transfers were in "Inne", not Uncategorized). Pekao: 30 "Przelew wewnętrzny" already handled by bankCategoryFallback, minimal additional impact.

**Affected txns**: 111 txns Nest Bank, 30 txns Pekao

**Details**: [VID-158 — SELF_TRANSFER Detection Fix](VID-158-TROUBLESHOOTING-ENRICHMENT-QUALITY.md)

#### Fix 3: Auto-Categorization Pre-Filter Gap

**Problem**: 28 BANK_FEE transactions in Nest Bank + 8 CASH_WITHDRAWAL + 7 BANK_FEE in Pekao end up as Uncategorized. They have `classification=BANK_FEE` or `CASH_WITHDRAWAL` and should be caught by auto-categorization pre-filter in `AiCategorizationService`, but they pass through to force-uncategorized.

**What to change**: Investigate why `preFilterAutoCategorizableTransactions()` doesn't catch these. Likely the auto-categorizable suggestions are generated but not applied during accept-ai (similar to standalone fix — suggestions exist but aren't converted to `acceptedCategories`).

**Impact**: Pekao Uncategorized 91→~76 (-15). Nest Uncategorized 31→~3 (-28).

**Affected txns**: 28 BANK_FEE (Nest), 8 CASH_WITHDRAWAL + 7 BANK_FEE (Pekao)

#### Fix 4: Better Merchant Mapping for "Inne"

**Problem**: "Inne" catch-all contains identifiable merchants that should have specific categories:
- BADOO (139 txns Pekao) → should be "Subskrypcje" or "Rozrywka"
- Parking merchants (27 txns Pekao) → should be "Transport/Parking"
- MINDBOX (52 txns Nest) → should be "Wynagrodzenie" or "Przychody z działalności"
- IFIRMA (35 txns Nest) → should be "Usługi firmowe" or "Księgowość"

**What to change**: Improve categorization-prompt to better handle merchants in generic "Inne"/"Bez kategorii" bankCategories. When merchant is STRONG but bankCategory is GENERIC, AI should create a specific category based on merchant identity.

**Impact**: Pekao "Inne" 221→~55 (-166). Nest "Inne" ~93→~6 (after SELF_TRANSFER fix first).

**Affected txns**: ~250 txns across both datasets

### Projected Cumulative Impact

```
                    FIX                         PEKAO    NEST BANK   COMBINED
                                                Uncat%   Uncat%      Uncat%
Current:            Three-Signal v1             11.5%    7.7%        10.2%
+ Fix 1 Standalone: Hierarchy only              11.5%    7.7%        10.2%
+ Fix 2 SELF_TRANSFER: Pre-filter               11.5%    7.7%        10.2%
+ Fix 3 Auto-cat:  BANK_FEE/CASH_WITHDRAWAL    ~9.6%    ~0.7%       ~6.6%
+ Fix 4 "Inne":    Better merchant mapping      ~9.6%    ~0.7%       ~6.6%

                                                "Inne"%  "Inne"%     "Inne"%
Current:                                        27.9%    50.7%       35.6%
+ Fix 2 SELF_TRANSFER:                          ~27%     ~23%        ~25%
+ Fix 4 "Inne":                                 ~7%      ~1.5%       ~5%
```

### Metrics to Track in Future PRs

| Metric | Baseline (2026-04-26) | Target |
|---|---|---|
| Pekao Uncategorized | 11.5% | <5% |
| Nest Uncategorized | 7.7% | <2% |
| Pekao "Inne" | 27.9% | <10% |
| Nest "Inne" | 50.7% | <15% |
| Pekao parent categories | 7 | ~10 |
| Pekao standalone categories | 7 | 0 |
| Nest SELF_TRANSFER in "Inne" | 111 txns | 0 txns |
| Nest BANK_FEE in Uncategorized | 28 txns | 0 txns |
| Combined effective quality | 54.2% | >85% |

---

## Related Documents

- [VID-157-TROUBLESHOOTING-BANK-CATEGORIES.md](VID-157-TROUBLESHOOTING-BANK-CATEGORIES.md) — Root cause + standalone fix description
- [VID-158-TROUBLESHOOTING-ENRICHMENT-QUALITY.md](VID-158-TROUBLESHOOTING-ENRICHMENT-QUALITY.md) — Nest Bank enrichment + SELF_TRANSFER fix design
- [VID-159-THREE-SIGNAL-CATEGORIZATION-ALGORITHM.md](VID-159-THREE-SIGNAL-CATEGORIZATION-ALGORITHM.md) — Algorithm design + weaknesses + metrics
- [VID-160-BANK-VS-AI-CATEGORY-QUALITY-COMPARISON.md](VID-160-BANK-VS-AI-CATEGORY-QUALITY-COMPARISON.md) — Bank vs AI category quality comparison
