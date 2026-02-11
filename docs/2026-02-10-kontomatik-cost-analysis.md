# Analiza kosztów Open Banking dla Vidulum

**Data:** 2026-02-10
**Status:** Analiza kosztowa
**Aktualizacja:** GoCardless/Nordigen zamknięty dla nowych klientów (2025)

---

## Porównanie providerów Open Banking (Polska, 2026)

| Provider | Status | Stałe/konto | Per import/user | Koszt 1 konta/msc | Polskie banki |
|----------|--------|-------------|-----------------|-------------------|---------------|
| **Kontomatik** | Dostępny | 2 zł | 0.50 zł | **13 zł** (22 imp.) | Bardzo dobre |
| **GoCardless/Nordigen** | ZAMKNIĘTY | - | - | - | - |
| **Tink (Visa)** | Dostępny | €0.50/user | 0 zł | **~2.20 zł** | Dobre (PKO, mBank, Pekao+) |
| **Yapily** | Dostępny | Custom | Custom | **?? zł** (kontakt) | Dobre (25M kont) |
| **Salt Edge** | Dostępny | Custom | Custom | **?? zł** (kontakt) | 5000+ banków |
| **Enable Banking** | Dostępny | Custom | Custom | **?? zł** (kontakt) | 2500+ ASPSPs |

### Źródła:
- [GoCardless zamknięty dla nowych klientów](https://forum.invoiceninja.com/t/gocardless-nordigen-service-no-longer-available-alternative-needed/22576)
- [Tink Pricing](https://tink.com/pricing/) - €0.50/user/miesiąc
- [Yapily Pricing](https://www.yapily.com/pricing) - darmowy sandbox, płatne produkcja
- [Salt Edge Coverage](https://www.saltedge.com/products/account_information/coverage)
- [Enable Banking](https://enablebanking.com)
- [Open Banking Poland](https://www.openbankingtracker.com/country/poland)

### Ważne: GoCardless/Nordigen niedostępny!

GoCardless (który przejął Nordigen) **nie przyjmuje już nowych klientów** od 2025 roku.
Mollie przejmuje GoCardless za €1.05 mld - transakcja zamknie się w połowie 2026.
Darmowa opcja Open Banking **już nie istnieje** dla nowych użytkowników.

---

## Realistyczne opcje dla Vidulum

### Opcja 1: Kontomatik (aktualnie analizowany)

| Pozycja | Koszt |
|---------|-------|
| Połączenie z kontem (stałe/miesiąc) | **2 PLN** |
| Każdy import transakcji | **0.50 PLN** |

### Opcja 2: Tink (Visa) - najtańsza alternatywa

| Pozycja | Koszt |
|---------|-------|
| Per user/miesiąc | **€0.50 (~2.20 PLN)** |
| Per import | **0 zł** (unlimited w ramach €0.50) |

**Porównanie Tink vs Kontomatik:**

| Scenariusz | Kontomatik | Tink | Oszczędność |
|------------|------------|------|-------------|
| 1 konto, 22 imp./msc | 13 zł | 2.20 zł | **83% taniej** |
| 5 kont, 110 imp./msc | 65 zł | 11 zł | **83% taniej** |
| 10 kont, 220 imp./msc | 130 zł | 22 zł | **83% taniej** |

**UWAGA:** Tink wymaga kontaktu z sales dla nowych klientów. Cena €0.50 może być tylko dla istniejących.

### Opcja 3: On-demand import (user klika przycisk)

Niezależnie od providera, model on-demand drastycznie redukuje koszty:

| Model | Importy/msc | Kontomatik | Tink |
|-------|-------------|------------|------|
| Scheduled (Pn-Pt) | 22 | 13 zł | 2.20 zł |
| **On-demand (8 logowań)** | 8 | **6 zł** | **2.20 zł** |
| On-demand (4 logowania) | 4 | **4 zł** | **2.20 zł** |

---

## Cennik Kontomatik (szczegóły)

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

## Porównanie providerów (zaktualizowane 2026)

| Aspekt | Kontomatik | Tink | GoCardless |
|--------|------------|------|------------|
| Status | **Dostępny** | **Dostępny** | **ZAMKNIĘTY** |
| Stałe/konto | 2 zł/msc | €0.50/user | - |
| Import | 0.50 zł | 0 zł (w cenie) | - |
| Koszt 1 konta/msc (scheduled) | **13 zł** | **~2.20 zł** | - |
| Koszt 1 konta/msc (on-demand) | **6 zł** | **~2.20 zł** | - |
| Polskie banki | Bardzo dobre | Dobre | - |
| Limit requestów | Brak | Unlimited | - |

**Rekomendacja (2026):**
1. **Negocjuj z Tink** - 6x tańszy niż Kontomatik (€0.50 vs 13 zł)
2. **Kontomatik on-demand** - jeśli Tink niedostępny, użyj modelu "user klika"
3. **Yapily/Salt Edge** - zapytaj o pricing, mogą być konkurencyjni

---

## Model On-Demand (user klika przycisk)

### Dlaczego on-demand?

| Model | Importy/msc | Koszt Kontomatik | Koszt Tink |
|-------|-------------|------------------|------------|
| Scheduled Pn-Pt | 22 | 13 zł | 2.20 zł |
| **On-demand (8x)** | 8 | **6 zł** | **2.20 zł** |
| On-demand (4x) | 4 | **4 zł** | **2.20 zł** |

**Oszczędność Kontomatik:** 54% (13 zł → 6 zł)

### Jak działa on-demand UX:

```
┌─────────────────────────────────────────────────────────────┐
│  User loguje się → Dashboard                                │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  Ostatnia synchronizacja: 2 dni temu                │   │
│  │                                                     │   │
│  │  [🔄 Synchronizuj z bankiem]                        │   │
│  └─────────────────────────────────────────────────────┘   │
│         │                                                   │
│         ▼ (klik)                                            │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  🔄 Synchronizuję dane z banku...                   │   │
│  │  ████████████░░░░░░░░  45%                          │   │
│  │                                                     │   │
│  │  • Pobieranie transakcji... ✓                       │   │
│  │  • Kategoryzacja AI...                              │   │
│  └─────────────────────────────────────────────────────┘   │
│         │                                                   │
│         ▼ (30-90 sek)                                       │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  ✅ Zsynchronizowano! 12 nowych transakcji          │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Warianty modelu:

| Tier | Model | Opis |
|------|-------|------|
| Free | CSV only | 0 zł kosztu |
| Starter | On-demand (manual) | User klika przycisk |
| Pro | On-demand + auto on login | Sync przy logowaniu |
| Enterprise | Scheduled + on-demand | Codziennie rano + manual |

### Zalety on-demand:

1. **54% tańszy** (Kontomatik: 6 zł vs 13 zł)
2. **Dane real-time** - user dostaje najświeższe dane
3. **Brak marnowania** - import tylko gdy potrzebny
4. **Lepszy UX** - user widzi progress i kontroluje

### Wady on-demand:

1. **Czekanie 30-90 sek** - akceptowalne z progress barem
2. **Dashboard nie gotowy od razu** - cache ostatni wynik

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

## Podsumowanie i rekomendacje

### Koszty per model (1 konto):

| Model | Kontomatik | Tink |
|-------|------------|------|
| Scheduled 7 dni | 17 zł | 2.20 zł |
| Scheduled Pn-Pt | 13 zł | 2.20 zł |
| **On-demand (8x/msc)** | **6 zł** | **2.20 zł** |

### Rekomendacja strategiczna:

1. **Negocjuj z Tink** - €0.50/user to 6x taniej niż Kontomatik scheduled
2. **Jeśli Kontomatik - użyj on-demand** - 54% oszczędności
3. **Skip weekendów** - dodatkowe 24% oszczędności przy scheduled
4. **Zapytaj Yapily/Salt Edge** - mogą mieć lepszą ofertę

### Model on-demand - kluczowe metryki:

| Metryka | Wartość |
|---------|---------|
| Koszt Kontomatik on-demand | **6 zł/konto/msc** (8 importów) |
| Koszt Tink | **~2.20 zł/konto/msc** (unlimited) |
| Czas synchronizacji | **30-90 sekund** |
| Oszczędność vs scheduled | **54%** (Kontomatik) |

### Finalna rekomendacja:

```
Tier         Model              Provider      Koszt/konto
─────────────────────────────────────────────────────────
Free         CSV only           -             0 zł
Starter      On-demand          Tink/Kontom.  2-6 zł
Pro          On-demand+login    Tink/Kontom.  2-6 zł
Business     Scheduled Pn-Pt    Tink          ~2.20 zł
Enterprise   Scheduled+manual   Tink          ~2.20 zł
```

**Model on-demand z Tink to optymalne rozwiązanie:**
- Najtańszy (€0.50/user)
- Unlimited importów w cenie
- Dobre pokrycie polskich banków
- User dostaje real-time dane

**Jeśli Tink niedostępny - Kontomatik on-demand:**
- 6 zł/konto vs 13 zł scheduled
- User klika przycisk gdy chce świeże dane
- Akceptowalne 30-90 sek czekania z progress barem
