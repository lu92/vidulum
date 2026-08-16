# VID-160: Bank Categories vs AI-Inferred Categories — Quality Comparison

**Date**: 2026-04-25
**Context**: Comparison of category assignment quality between Pekao (bank-provided, 791 txns) and Nest Bank (AI-inferred via enrichment-prompt, 402 txns). Analysis of bank misclassifications and implications for the categorization pipeline.

## Key Finding

**Neither approach is sufficient alone.** Bank categories have higher precision (91%) but lower coverage and contain unfixable errors (MCC-driven misclassification). AI-inferred categories have lower precision (64%) but the errors are fixable (missing data → add account numbers). The best result comes from combining both: bank categories verified and corrected by AI.

## Pekao Bank — Category Quality Audit

### Classification of All 791 Transactions

| Quality Level | Count | % | Description |
|---|---|---|---|
| **Correctly categorized** | 479 | 60.6% | Bank assigned the right specific category |
| **Generic (lazy)** | 265 | 33.5% | Bank used "Inne" or "Bez kategorii" |
| **Wrong specific category** | 19 | 2.4% | Bank assigned incorrect specific category |
| **Channel confusion** | 10 | 1.3% | Bank categorized by payment channel, not purpose |
| **Inconsistent taxonomy** | 18 | 2.3% | Same business type, different categories |

### Error Type 1: GENERIC — Bank Gave Up (265 transactions, 33.5%)

Bank used "Inne" (173 txns) or "Bez kategorii" (92 txns) instead of a specific category.

**"Inne" (173 txns) — major offenders:**

| Merchant | Count | Should Be | Why Bank Failed |
|----------|-------|-----------|-----------------|
| BADOO | 139 | Subskrypcje/Rozrywka | Foreign merchant, card settlement via "BANK PEKAO S.A." intermediary |
| WARSZAWA SPPN + parking merchants | 27 | Transport/Parking | Parking meters/garages not in bank's MCC mapping |
| Other (MADEJ, IKANO, DIT VISION, etc.) | 7 | Various | Unclear merchants |

**"Bez kategorii" (92 txns) — major offenders:**

| Merchant | Count | Should Be | Why Bank Failed |
|----------|-------|-----------|-----------------|
| Stowarzyszenia (SOS, UNICEF, Psychologów) | 44 | Darowizny/Charytatywne | Charitable organizations not mapped |
| PLUS (telecom) | 12 | Telekomunikacja | Phone top-ups via personal transfer, not direct billing |
| GZGK (water utility) | 7 | Media/Woda | Local utility company not in bank's database |
| PROXYAI | 5 | IT/Narzędzia | New SaaS, bank has no MCC mapping |
| TIMELEFT | 4 | Rozrywka | Foreign subscription service |
| Other (CONTABO, APPLE, WESTWING, etc.) | 20 | Various | Mix of foreign and niche merchants |

### Error Type 2: WRONG SPECIFIC CATEGORY (19 transactions, 2.4%)

Bank assigned a **wrong** specific category. These are the most damaging errors because they actively mislead categorization.

**"Kino i teatr" — 13/22 (59%) misclassified:**

| Merchant | Count | Bank Said | Actually Is |
|----------|-------|-----------|-------------|
| KLASZTOR DOMINIKANOW (Dominican monastery) | 6 | Kino i teatr | Donations/Religion |
| EDUKACJA Z WARTOŚCIAMI (education company) | 5 | Kino i teatr | Education |
| PARAFIA DUCHA SWIETEGO (parish/church) | 2 | Kino i teatr | Donations/Religion |

**Root cause**: These merchants likely have MCC codes in the "entertainment" range (7991-7999 or similar). MCC codes are assigned by acquirer banks based on merchant type, not transaction purpose. Religious/educational organizations that host events may receive entertainment MCC codes.

**"Sport" — 3/24 misclassified:**

| Merchant | Count | Bank Said | Actually Is |
|----------|-------|-----------|-------------|
| MUZEUM WARMII I MAZUR (museum) | 1 | Sport | Culture/Museums |
| WYPOŻYCZALNIA SLOTWINY (rental) | 1 | Sport | Rekreacja |
| PIRUET (dance studio?) | 1 | Sport | Inne usługi |

**"Książki" — 3/6 misclassified:**

| Merchant | Count | Bank Said | Actually Is |
|----------|-------|-----------|-------------|
| FC POWER TRANS (transport company) | 3 | Książki | Transport/Kurierzy |

### Error Type 3: CHANNEL CONFUSION (10 transactions, 1.3%)

Bank categorized by **payment channel** instead of **purchase purpose**.

| Merchant | Count | Bank Said | Actually Is | Channel Used |
|----------|-------|-----------|-------------|-------------|
| DENIS (hairdresser) | 3 | Internet, TV, telefon | Usługi osobiste | Phone transfer (BLIK na telefon) |
| DAMIAN DELIKOWSKI | 4 | Internet, TV, telefon | Przelewy osobiste | Phone transfer |
| KSAWERY | 2 | Internet, TV, telefon | Przelewy osobiste | Phone transfer |
| DAREK KRUK | 1 | Internet, TV, telefon | Przelewy osobiste | Phone transfer |

**Root cause**: Bank classifies "Przelew na telefon" (phone transfer) as "Internet, TV, telefon" — conflating payment method with purchase category. The description says "Fryzura" (haircut) but bank ignores it.

### Error Type 4: INCONSISTENT TAXONOMY (18 transactions, 2.3%)

Same type of business gets different categories from the bank.

| Merchant | Count | Bank Said | Correct Category |
|----------|-------|-----------|-----------------|
| XTREME FITNESS (gym) | 20 | Sport | Sport/Siłownia |
| ZDROFIT (gym) | 18 | Hobby | Sport/Siłownia |

Both are gyms with memberships. Bank inconsistently categorized them. XTREME FITNESS uses one MCC code, ZDROFIT another — but from user's perspective they're identical spending.

## Nest Bank — AI-Inferred Category Quality Audit

### Classification of All 402 Transactions

| Quality Level | Count | % | Description |
|---|---|---|---|
| **Correctly categorized** | 194 | 48.3% | AI assigned the right category |
| **Generic (lazy)** | 91 | 22.6% | AI used "Inne" or "Uncategorized" |
| **Classification error** | 111 | 27.6% | SELF_TRANSFER missed (LUCJAN BIK) |
| **Wrong specific category** | 1 | 0.2% | PGE ENERGETYKA → "Inne" instead of "Prąd" |
| **Truly uncategorizable** | 5 | 1.2% | ZYDIE, NULL — legitimately unclear |

### AI Error Analysis

**Classification error — LUCJAN BIK (111 transactions, 27.6%):**

All "Lucjan Bik Pekao" with description "zycie" to account PL98124014441111001078171074. These are monthly self-transfers from Nest Bank to user's own Pekao account. AI classified as UNKNOWN because it doesn't receive account numbers in `TransactionForEnrichment`.

**This is a fixable, systemic error** — adding `sourceAccountNumber`, `targetAccountNumber`, and `accountOwnerName` to the enrichment prompt would eliminate all 111 errors.

**Generic overuse — "Inne" (91 transactions, 22.6%):**

| Merchant | Count | Should Be |
|----------|-------|-----------|
| MINDBOX (employer) | 52 | Wynagrodzenie/Przychody z działalności |
| IFIRMA (accounting) | 35 | Usługi firmowe/Księgowość |
| TRADINGVIEW | 1 | Subskrypcje |
| PGE ENERGETYKA | 1 | Media/Prąd |
| ANGELIKA KLOS | 1 | Prezenty |
| DANTEX | 1 | Usługi budowlane |

AI correctly identified the merchants but used overly generic category "Inne". This is "lazy" rather than "wrong" — similar to bank's behavior with "Inne" and "Bez kategorii".

## Head-to-Head Comparison

### Precision (how often a specific category is correct)

```
                PEKAO (bank)              NEST BANK (AI)
                ────────────              ──────────────
Specific cats:  526 txns assigned         311 txns assigned
Correct:        479                       194
Wrong:          47 (19+10+18)             117 (111+1+5)
Precision:      479/526 = 91.1%           194/311 = 63.6%

Winner: PEKAO (+27.5pp)
```

### Coverage (how many transactions get a specific, non-generic category)

```
                PEKAO (bank)              NEST BANK (AI)
                ────────────              ──────────────
Total txns:     791                       402
Specific cat:   526                       311
Generic/none:   265                       91
Coverage:       526/791 = 66.5%           311/402 = 77.4%

Winner: AI (+10.9pp)
```

### Granularity (richness of category taxonomy)

```
                PEKAO (bank)              NEST BANK (AI)
                ────────────              ──────────────
Categories:     35                        9
Semantic cats:  ~28                       ~6
Useful cats:    ~25                       ~6

Winner: PEKAO (4x more categories)
```

### Error Fixability

| Error Type | PEKAO (bank) | NEST BANK (AI) | Fixable? |
|---|---|---|---|
| MCC-driven wrong category | 19 txns | 0 | **NOT fixable** — bank's MCC assignment |
| Channel confusion | 10 txns | 0 | **NOT fixable** — bank's classification logic |
| Inconsistent taxonomy | 18 txns | 0 | **NOT fixable** — bank's internal mapping |
| Missing SELF_TRANSFER | 0 | 111 txns | **FIXABLE** — add account numbers to prompt |
| Generic overuse | 265 txns | 91 txns | **Partially fixable** — better prompting |
| Wrong specific (AI) | 0 | 1 txn | **FIXABLE** — better prompting |

**Key insight**: Bank errors are **structural and unfixable** (driven by MCC codes and bank's internal logic). AI errors are **data-driven and fixable** (caused by missing input data that can be added).

### Combined Quality Score

```
Quality = (Correctly_categorized) / Total

PEKAO:     479/791 = 60.6%
NEST BANK: 194/402 = 48.3%

But excluding the fixable SELF_TRANSFER error:
NEST BANK: (194+111)/402 = 75.9%  ← if self-transfers were detected
```

## Implications for the Three-Signal Algorithm (VID-159)

### Bank Categories Need Verification, Not Blind Trust

The 5.9% error rate in Pekao's specific categories means `bankCategoryFallback` should **not** blindly trust bank categories. The categorization-prompt should:

1. **Use bank category as a strong hint, not ground truth**
2. **Cross-verify with merchant name** — if merchant=KLASZTOR but bankCat="Kino", AI should override
3. **Flag inconsistencies** — ZDROFIT in "Hobby" vs XTREME FITNESS in "Sport" → AI should normalize

### Proposed: Enrichment-Level Category Verification

Add a verification step in the enrichment-prompt that checks bank-provided categories against merchant identity:

```
For each transaction with original bankCategory:
  1. If merchant is STRONG (conf ≥0.8):
     → Verify: does merchant type match bankCategory?
     → If mismatch: flag bankCategory as SUSPECT, suggest correction
     → Example: KLASZTOR DOMINIKANOW + "Kino" → SUSPECT, suggest "Religia/Darowizny"
  
  2. If merchant is WEAK (conf ≤0.3):
     → Cannot verify: trust bankCategory as-is
     → Example: UL. JAGIELLONCZYKA + "Art. spożywcze" → trust (no better signal)
  
  3. If bankCategory is GENERIC ("Inne", "Bez kategorii"):
     → Attempt inference from merchant + description
     → Example: BADOO + "Inne" → infer "Subskrypcje/Rozrywka"
```

### Updated Metrics with Verification

```
PEKAO with enrichment verification:
  Bank correct + verified:    479 (60.6%)  → kept as-is
  Bank wrong + corrected:      47 (5.9%)   → AI corrects ~35 (where merchant is strong)
  Bank generic + inferred:    265 (33.5%)  → AI infers ~180 (where merchant/desc helps)
  Remaining uncategorizable:   ~0 + ~85    → truly unclear
  
  Estimated correct after verification: 479 + 35 + 180 = 694/791 (87.7%)
  Improvement: 60.6% → 87.7% (+27.1pp)
```

### Error Type Distribution — What Each Approach Catches

```
                          Bank     AI      Bank+AI
                          only     only    combined
                          ─────    ─────   ────────
MCC-wrong (klasztor):     MISS     CATCH   CATCH     ← AI sees merchant name
Channel confusion:        MISS     CATCH   CATCH     ← AI sees description
Inconsistent taxonomy:    MISS     CATCH   CATCH     ← AI normalizes
Self-transfer:            n/a      MISS    CATCH*    ← needs account numbers
Generic categories:       MISS     MISS    PARTIAL   ← both struggle here

* with account number enhancement
```

## Summary: Bank vs AI Error Profiles

```
╔══════════════════════════════════════════════════════════════════╗
║                    ERROR PROFILE COMPARISON                      ║
║                                                                  ║
║  BANK PEKAO:                                                     ║
║  ✅ High precision (91.1%) for specific categories               ║
║  ✅ Rich taxonomy (35 categories)                                ║
║  ❌ 33.5% generic (gave up on 265 txns)                         ║
║  ❌ 5.9% actively wrong (MCC errors, channel confusion)         ║
║  ❌ Errors are UNFIXABLE (bank's internal logic)                ║
║  ❌ Inconsistent across similar merchants                        ║
║                                                                  ║
║  AI ENRICHMENT:                                                  ║
║  ✅ Zero channel confusion errors                                ║
║  ✅ Zero inconsistency errors                                    ║
║  ✅ Errors are FIXABLE (add missing data)                        ║
║  ❌ Low precision (63.6%) due to self-transfer miss             ║
║  ❌ Only 9 categories (low granularity)                          ║
║  ❌ 22.6% generic (Inne/Uncategorized)                          ║
║  ❌ 27.6% classification error (missing account numbers)        ║
║                                                                  ║
║  OPTIMAL: Bank categories + AI verification                      ║
║  → Bank provides taxonomy (35 cats) + precision (91%)           ║
║  → AI corrects MCC errors + channel confusion + inconsistency   ║
║  → AI infers categories for generic "Inne"/"Bez kategorii"      ║
║  → Estimated result: ~87.7% correctly categorized               ║
╚══════════════════════════════════════════════════════════════════╝
```

## Related Documents

- [VID-157-TROUBLESHOOTING-BANK-CATEGORIES.md](VID-157-TROUBLESHOOTING-BANK-CATEGORIES.md) — Root cause: why bank categories were lost during Pekao import
- [VID-158-TROUBLESHOOTING-ENRICHMENT-QUALITY.md](VID-158-TROUBLESHOOTING-ENRICHMENT-QUALITY.md) — Root cause: enrichment quality issues for Nest Bank
- [VID-159-THREE-SIGNAL-CATEGORIZATION-ALGORITHM.md](VID-159-THREE-SIGNAL-CATEGORIZATION-ALGORITHM.md) — Proposed algorithm using merchant + bankCategory + description
