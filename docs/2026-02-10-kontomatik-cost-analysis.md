# Analiza kosztów Kontomatik dla Vidulum

**Data:** 2026-02-10
**Status:** Analiza kosztowa

---

## Cennik Kontomatik

| Pozycja | Koszt |
|---------|-------|
| Połączenie z kontem (stałe/miesiąc) | **2 PLN** |
| Każdy import transakcji | **0.50 PLN** |

---

## Strategia częstotliwości importu

### Analiza księgowań weekendowych

Banki w Polsce nie realizują większości operacji w weekendy:

| Typ transakcji | Sobota/Niedziela | Kiedy zaksięgowane |
|----------------|------------------|---------------------|
| **Płatność kartą** | Autoryzacja natychmiast | Księgowanie: **poniedziałek-wtorek** |
| **Przelew zwykły (Elixir)** | Nie działa | Księgowanie: **poniedziałek** |
| **Przelew natychmiastowy (Express Elixir)** | Działa 24/7 | Księgowanie: **natychmiast** |
| **BLIK** | Działa | Księgowanie: zazwyczaj **poniedziałek** |
| **Zlecenia stałe** | Nie realizowane | Realizacja: **poniedziałek** |

**Sesje Elixir:** Pn-Pt 3 sesje (8:00, 12:00, 16:00), Sob-Nd: BRAK

### Opcje synchronizacji

| Strategia | Importy/msc | Koszt import | Koszt total/konto | Świeżość danych |
|-----------|-------------|--------------|-------------------|-----------------|
| 7 dni/tydzień | 30 | 15 zł | **17 zł** | Do 24h opóźnienia |
| **5 dni/tydzień (Pn-Pt)** | 22 | 11 zł | **13 zł** | **REKOMENDOWANE** |
| 2x dziennie (Pn-Pt) | 44 | 22 zł | **24 zł** | Do 12h opóźnienia |
| 1x tygodniowo | 4 | 2 zł | **4 zł** | Do 7 dni opóźnienia |

### Rekomendacja: 5 dni/tydzień (Pn-Pt) rano (6:00)

**Dlaczego skip weekendów:**
- Elixir nie działa w weekendy
- Płatności kartą księgowane są w poniedziałek
- Oszczędność: **4 zł/konto/miesiąc (24% taniej!)**
- Poniedziałek rano = wszystkie weekendowe transakcje

**Optymalny harmonogram:**
```
Poniedziałek: 6:00 ← KRYTYCZNY (weekend + nowe)
Wtorek:       6:00
Środa:        6:00
Czwartek:     6:00
Piątek:       6:00
Sobota:       SKIP
Niedziela:    SKIP
```

**Oszczędność przy skali:**

| Konta | Oszczędność/msc | Oszczędność/rok |
|-------|-----------------|-----------------|
| 100 | 400 zł | 4,800 zł |
| 500 | 2,000 zł | 24,000 zł |
| 1000 | 4,000 zł | 48,000 zł |

**Dla premium/enterprise:** opcja "weekend sync" lub "on-demand refresh" (+4 zł/msc)

---

## Analiza kosztów per segment klienta

**Założenie: Import 5 dni/tydzień (Pn-Pt) = 22 importy/miesiąc**

### Consumer (1-2 konta)

| Wariant | Konta | Importy | Koszt Kontomatik | Cena subskrypcji | Marża |
|---------|-------|---------|------------------|------------------|-------|
| Free tier | 0 | 0 | 0 zł | 0 zł | - |
| Basic | 1 | 22/msc | **13 zł** | 29 zł | **16 zł (55%)** |
| Standard | 2 | 44/msc | **26 zł** | 49 zł | **23 zł (47%)** |

**Wniosek Consumer:** Marża lepsza przy skip weekendów. Nadal rozważ:
- Free tier = tylko CSV import (0 zł kosztu)
- Płatny = Open Banking jako premium feature

### SMB (3-5 kont)

| Wariant | Konta | Importy | Koszt Kontomatik | Cena subskrypcji | Marża |
|---------|-------|---------|------------------|------------------|-------|
| SMB Basic | 3 | 66/msc | **39 zł** | 99 zł | **60 zł (61%)** |
| SMB Standard | 5 | 110/msc | **65 zł** | 199 zł | **134 zł (67%)** |
| SMB Pro | 5 | 110/msc | **65 zł** | 299 zł | **234 zł (78%)** |

**Wniosek SMB:** Bardzo dobra marża przy 199-299 zł/msc.

### Enterprise (10+ kont)

| Wariant | Konta | Importy | Koszt Kontomatik | Cena subskrypcji | Marża |
|---------|-------|---------|------------------|------------------|-------|
| Enterprise 10 | 10 | 220/msc | **130 zł** | 499 zł | **369 zł (74%)** |
| Enterprise 20 | 20 | 440/msc | **260 zł** | 999 zł | **739 zł (74%)** |
| Enterprise 50 | 50 | 1100/msc | **650 zł** | 1999 zł | **1349 zł (67%)** |

**Wniosek Enterprise:** Świetna marża, skalowalne.

---

## Koszt całkowity per tier (Kontomatik + AI)

### Założenia:
- Import 5 dni/tydzień (skip weekendów)
- AI (Claude Haiku): ~0.01 zł/transakcja (batch processing)
- ~100 transakcji/konto/miesiąc (consumer)
- ~500 transakcji/konto/miesiąc (business)

| Tier | Konta | Trans. | Kontomatik | AI | **Total koszt** | Cena | **Marża** |
|------|-------|--------|------------|-----|-----------------|------|-----------|
| Consumer Basic | 1 | 100 | 13 zł | 1 zł | **14 zł** | 29 zł | **15 zł (52%)** |
| Consumer Std | 2 | 200 | 26 zł | 2 zł | **28 zł** | 49 zł | **21 zł (43%)** |
| SMB Basic | 3 | 500 | 39 zł | 5 zł | **44 zł** | 99 zł | **55 zł (56%)** |
| SMB Standard | 5 | 1000 | 65 zł | 10 zł | **75 zł** | 199 zł | **124 zł (62%)** |
| SMB Pro | 5 | 2000 | 65 zł | 20 zł | **85 zł** | 299 zł | **214 zł (72%)** |
| Enterprise | 10 | 5000 | 130 zł | 50 zł | **180 zł** | 499 zł | **319 zł (64%)** |

---

## Optymalizacje kosztowe

### 1. Inteligentne planowanie importów

```
Zamiast: 1x dziennie dla każdego konta
Lepiej:  Import tylko gdy user aktywny

Logika:
- User nie logował się 7 dni → wstrzymaj import
- User zalogował się → wznów import + backfill
- Oszczędność: 50-70% kosztów dla nieaktywnych userów
```

**Potencjalna oszczędność:** 30-50% kosztów Kontomatik

### 2. Tiered sync frequency

```
Free:     Brak (tylko CSV)
Basic:    1x dziennie
Standard: 2x dziennie
Pro:      4x dziennie + on-demand
```

### 3. Smart caching

```
Transakcje starsze niż 30 dni → nie importuj ponownie
Importuj tylko: ostatnie 30 dni + nowe od ostatniego sync
```

### 4. Batch AI categorization

```
Nie kategoryzuj pojedynczo!
Zbieraj transakcje → batch 50-100 → 1 request do AI
Oszczędność: 7x taniej (system prompt nie powtarzany)
```

---

## Rekomendowany model cenowy (zaktualizowany)

| Tier | Cena | Konta | Sync | AI | Marża po kosztach |
|------|------|-------|------|-----|-------------------|
| **Free** | 0 zł | 0 | CSV only | Brak | 100% (0 zł kosztu) |
| **Starter** | 29 zł | 1 | 1x/dzień | Basic | ~38% (11 zł) |
| **Pro** | 79 zł | 3 | 1x/dzień | Full | ~50% (40 zł) |
| **Business** | 199 zł | 5 | 2x/dzień | Full | ~52% (104 zł) |
| **Enterprise** | 499 zł | 15 | 4x/dzień | Full + custom | ~55% (275 zł) |

---

## Break-even analysis

### Kiedy Kontomatik się opłaca vs CSV?

| Scenariusz | Czas usera (CSV) | Wartość czasu | Koszt Kontomatik | Opłaca się? |
|------------|------------------|---------------|------------------|-------------|
| Consumer (50 trans/msc) | 30 min | ~15 zł | 17 zł | **NIE** |
| Consumer (100 trans/msc) | 60 min | ~30 zł | 17 zł | **TAK** |
| SMB (500 trans/msc) | 5h | ~250 zł | 51 zł | **BARDZO TAK** |
| Enterprise (2000 trans/msc) | 20h | ~1000 zł | 170 zł | **BARDZO TAK** |

**Wniosek:** Open Banking opłaca się od ~100 transakcji/miesiąc (typowy aktywny consumer).

---

## Porównanie z konkurencją (GoCardless)

| Aspekt | Kontomatik | GoCardless |
|--------|------------|------------|
| Stałe/konto | 2 zł/msc | 0 zł |
| Import | 0.50 zł | 0 zł (AIS free) |
| Koszt 1 konta/msc | **17 zł** | **0 zł** |
| Polskie banki | Bardzo dobre | Dobre |
| Limit requestów | Brak | 4-10/dzień |
| Jakość danych | Świetna | Dobra |

**Rekomendacja:**
1. **Development/MVP:** GoCardless (darmowy)
2. **Produkcja PL:** Kontomatik (lepsze pokrycie polskich banków)
3. **Hybrid:** GoCardless jako fallback, Kontomatik jako primary

---

## Wpływ na unit economics

### Przed Kontomatik (tylko CSV)

```
Przychód: 199 zł/msc (SMB)
Koszty:
  - Infrastruktura: ~10 zł
  - AI: ~10 zł
  - Total: ~20 zł

Marża: 179 zł (90%)
```

### Po Kontomatik

```
Przychód: 199 zł/msc (SMB)
Koszty:
  - Infrastruktura: ~10 zł
  - AI: ~10 zł
  - Kontomatik: ~85 zł (5 kont)
  - Total: ~105 zł

Marża: 94 zł (47%)
```

**Spadek marży:** 90% → 47% (ale WARTOŚĆ dla klienta rośnie dramatycznie!)

---

## Rekomendacje końcowe

### 1. Pricing strategy

- **Podnieś ceny** o wartość Open Banking (~30-50 zł więcej)
- Komunikuj jako "automatyczna synchronizacja z bankiem"
- Alternatywnie: Open Banking jako add-on (+39 zł/msc)

### 2. Tier structure

```
Free:     CSV only, unlimited
Starter:  CSV + 1 konto Open Banking @ 39 zł
Pro:      CSV + 3 konta Open Banking @ 99 zł
Business: CSV + 5 kont Open Banking @ 249 zł
```

### 3. Cost control

- Import 1x dziennie (rano)
- Wstrzymaj sync dla nieaktywnych userów
- Batch AI categorization
- Cache historycznych transakcji

### 4. Negotiation with Kontomatik

Przy skali 100+ kont miesięcznie, negocjuj:
- Niższą stawkę per import (0.30-0.40 zł)
- Volume discount na stałą opłatę
- Lub flat fee per user zamiast per import

---

## Podsumowanie

| Metryka | Wartość |
|---------|---------|
| Koszt Kontomatik per konto | **13 zł/msc** (Pn-Pt, skip weekendów) |
| Oszczędność vs 7 dni/tydzień | **24% (4 zł/konto)** |
| Minimalna cena opłacalna (consumer) | **29 zł/msc** |
| Minimalna cena opłacalna (SMB) | **99 zł/msc** |
| Sweet spot marży | **60-70%** przy 199+ zł/msc |
| Rekomendowany harmonogram | **Pn-Pt 6:00, skip Sob-Nd** |

**Skip weekendów to łatwa optymalizacja - banki i tak nie księgują.**
Kontomatik jest opłacalny dla wszystkich tierów przy tej strategii.
Rozważ "weekend sync" jako opcję premium (+4 zł/msc) dla e-commerce/Express Elixir heavy users.
