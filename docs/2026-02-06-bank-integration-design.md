# Bank Integration & Recurring Rules Design

Data: 2026-02-06

## Kontekst

Rozważamy integrację z API bankowym jako source of truth dla transakcji. To fundamentalnie zmienia model systemu - zamiast ręcznego potwierdzania transakcji i atestacji miesięcy, system automatycznie pobiera dane z banku i dopasowuje je do planowanych transakcji.

## Obecny model (ręczny)

```
Użytkownik → appendExpectedCashChange → EXPECTED
Użytkownik → confirmCashChange → PAID
Użytkownik → attestMonth → zamknięcie miesiąca
```

**Problemy:**
- Użytkownik musi ręcznie potwierdzać każdą transakcję
- Atestacja miesięcy wymaga świadomej akcji
- `paidDate` nie jest persystowana w `CashChange` (ginie po potwierdzeniu)
- Brak mechanizmu dla transakcji EXPECTED które nie zostały opłacone

---

## Nowy model (z API bankowym)

```
Bank API → faktyczne transakcje (source of truth)
Użytkownik/Reguły → planowane transakcje (forecast)
System → automatyczne dopasowanie (reconciliation)
```

### Źródła transakcji

| Źródło | Typ | Opis |
|--------|-----|------|
| Bank API | Faktyczne | Pobierane automatycznie, source of truth dla salda i paidDate |
| Recurring Rules | Planowane | Automatycznie generowane wg harmonogramu |
| Ręczne | Planowane | Jednorazowe transakcje wprowadzone przez użytkownika |

---

## Podział transakcji - uproszczenie

| Źródło | Typ | Co potrzebujemy |
|--------|-----|-----------------|
| Bank API | Już opłacone (PAID) | Tylko kategorię |
| Recurring Rule | Planowane (EXPECTED) | Dopasowanie do banku |
| Ręczne | Planowane (EXPECTED) | Dopasowanie do banku |

**Kluczowy insight:** Transakcje z banku **nie wymagają reconciliation** - one już są faktem. Trzeba tylko przypisać kategorię.

---

## Statusy transakcji

### Transakcje planowane (forecast)

| Status | Opis | Przejścia |
|--------|------|-----------|
| `EXPECTED` | Czeka na dopasowanie z bankiem | → MATCHED, OVERDUE, CANCELLED |
| `MATCHED` | Dopasowana do transakcji bankowej | (końcowy) |
| `OVERDUE` | Minął dueDate, brak dopasowania | → MATCHED, CANCELLED, DEFERRED |
| `DEFERRED` | Przesunięta na inny termin | → EXPECTED (w nowym miesiącu) |
| `CANCELLED` | Anulowana przez użytkownika | (końcowy) |
| `UNMATCHED` | Koniec miesiąca, brak dopasowania, czeka na decyzję | → MATCHED, CANCELLED, DEFERRED |

### Transakcje z banku

| Status | Opis | Przejścia |
|--------|------|-----------|
| `UNCATEGORIZED` | Nowa, do przypisania kategorii | → MATCHED, CATEGORIZED |
| `MATCHED` | Dopasowana do EXPECTED | (końcowy) |
| `CATEGORIZED` | Przypisana kategoria (bez EXPECTED) | (końcowy) |

---

## Przepływy stanów

### Przepływ 1: Planowana transakcja zostaje opłacona

```
┌─────────────┐     dopasowanie      ┌─────────────┐
│  EXPECTED   │ ──────────────────── │   MATCHED   │
│  Czynsz     │      z bankiem       │  paidDate:  │
│  2000 PLN   │                      │  12 sty     │
│  due: 10sty │                      │  (2 dni     │
└─────────────┘                      │  opóźnienia)│
                                     └─────────────┘
```

### Przepływ 2: Transakcja z banku bez planu (jednorazowa)

```
┌─────────────┐    automatyczna      ┌─────────────┐
│UNCATEGORIZED│ ─────────────────── │ CATEGORIZED │
│  -150 PLN   │   kategoryzacja      │  Zakupy     │
│  "Allegro"  │   (bank/historia/AI) │  -150 PLN   │
└─────────────┘                      └─────────────┘
```

### Przepływ 3: Planowana transakcja nie została opłacona

```
┌─────────────┐    dueDate          ┌─────────────┐
│  EXPECTED   │ ─────────────────── │   OVERDUE   │
│  Czynsz     │     minął           │  Czynsz     │
│  due: 10sty │                     │  10+ dni    │
└─────────────┘                     └─────────────┘
       │                                   │
       │                         ┌─────────┴─────────┐
       │                         │         │         │
       ▼                         ▼         ▼         ▼
  ┌─────────┐              ┌─────────┐ ┌───────┐ ┌────────┐
  │ MATCHED │              │DEFERRED │ │CANCEL │ │ MATCHED│
  │(na czas)│              │ na luty │ │       │ │(późno) │
  └─────────┘              └─────────┘ └───────┘ └────────┘
```

### Przepływ 4: Częściowa płatność

```
┌─────────────┐                      ┌─────────────┐
│  EXPECTED   │     split            │   MATCHED   │
│  Czynsz     │ ──────────────────── │  1000 PLN   │
│  2000 PLN   │                      │  (część 1)  │
└─────────────┘                      └─────────────┘
       │
       │ pozostała kwota             ┌─────────────┐
       └──────────────────────────── │   OVERDUE   │
                                     │  1000 PLN   │
                                     │  (część 2)  │
                                     └─────────────┘
```

### Przepływ 5: EXPECTED bez dopasowania na koniec miesiąca

```
┌─────────────┐    koniec           ┌─────────────┐
│  EXPECTED   │ ─────────────────── │  UNMATCHED  │
│  Czynsz     │    miesiąca         │  Czynsz     │
│  due: 10sty │                     │             │
└─────────────┘                     └─────────────┘
                                           │
                         ┌─────────────────┼─────────────────┐
                         ▼                 ▼                 ▼
                   ┌──────────┐      ┌──────────┐      ┌──────────┐
                   │ Dopasuj  │      │ Oznacz   │      │  Usuń    │
                   │ ręcznie  │      │ nieopłac.│      │          │
                   └──────────┘      └──────────┘      └──────────┘
                         │                 │
                         ▼                 ▼
                   ┌──────────┐      ┌──────────┐
                   │ MATCHED  │      │ OVERDUE  │
                   └──────────┘      └──────────┘
```

---

## Kategoryzacja transakcji bankowych

### Przepływ kategoryzacji

```
Transakcja z banku
        │
        ▼
┌─────────────────────┐
│ Czy bank przysłał   │──── TAK ────▶ Użyj kategorii banku
│ kategorię?          │              (może wymagać mapowania)
└─────────────────────┘
        │ NIE
        ▼
┌─────────────────────┐
│ Czy numer konta     │──── TAK ────▶ Użyj kategorii z historii
│ odbiorcy znany?     │              (100% pewność)
└─────────────────────┘
        │ NIE
        ▼
┌─────────────────────┐
│ Czy opis pasuje     │──── TAK ────▶ Użyj kategorii z historii
│ do historii?        │              (90% pewność)
└─────────────────────┘
        │ NIE
        ▼
┌─────────────────────┐
│ AI kategoryzacja    │──── >80% ───▶ Użyj kategorii AI
│ (Claude API)        │
└─────────────────────┘
        │ <80% confidence
        ▼
┌─────────────────────┐
│ UNCATEGORIZED       │──────────────▶ Kategoria "Uncategorized"
│ (user może poprawić)│               (user kategoryzuje później)
└─────────────────────┘
```

### Szacowana skuteczność kategoryzacji

| Warstwa | % złapanych | Skumulowany |
|---------|-------------|-------------|
| Kategoria z banku | ~40% | 40% |
| Historia (numer konta) | ~25% | 65% |
| Historia (opis) | ~15% | 80% |
| AI (Claude API) | ~15% | 95% |
| UNCATEGORIZED | ~5% | 100% |

**Użytkownik kategoryzuje ręcznie tylko ~5% transakcji.**

### Domyślna kategoria UNCATEGORIZED

Zamiast blokować użytkownika, transakcje bez kategorii trafiają do:

```
Kategorie systemowe:
  INFLOW:
    - Uncategorized Income
  OUTFLOW:
    - Uncategorized Expense
```

Użytkownik może:
1. Zostawić (raporty nadal działają)
2. Skategoryzować później (batch)
3. Ustawić regułę "wszystko z Allegro → Zakupy"

---

## Atestacja miesiąca - czy potrzebna?

### Analiza: Co daje atestacja?

W obecnym modelu atestacja:
1. Zmienia status miesiąca na ATTESTED
2. Przenosi EXPECTED do następnego miesiąca
3. Przesuwa activePeriod

### Z integracją bankową atestacja jest zbędna

**Dlaczego?**

1. **Bank jest source of truth** - saldo i transakcje są faktami
2. **Reconciliation zastępuje ręczne potwierdzanie** - system dopasowuje automatycznie
3. **Czas płynie naturalnie** - miesiące "zamykają się" same gdy miną
4. **UNMATCHED wymusza decyzję** - użytkownik musi rozwiązać niedopasowane transakcje

### Rekomendacja: Usunąć atestację

| Aspekt | Z atestacją | Bez atestacji |
|--------|-------------|---------------|
| Zamknięcie miesiąca | Ręczne | Automatyczne (kalendarz) |
| EXPECTED bez dopasowania | Przenoszone auto | UNMATCHED - user decyduje |
| Edycja historii | Zablokowana | Dozwolona (audit trail) |
| UX | Wymaga akcji | Pasywny |

---

## Problem: Niedokładne dopasowanie recurring

### Przykłady niedokładności

```
Recurring Rule: Czynsz, 2000 PLN, 10-tego

Rzeczywistość:
  Styczeń:  2000.00 PLN, 10 sty  ✓ idealne
  Luty:     2050.00 PLN, 12 lut  ? podwyżka + opóźnienie
  Marzec:   2050.00 PLN,  8 mar  ? wcześniej
  Kwiecień: 1950.00 PLN, 10 kwi  ? korekta/nadpłata
  Maj:      4100.00 PLN, 10 maj  ? dwa miesiące razem
```

### Strategia dopasowania dla recurring

#### Krok 1: Znajdź kandydatów

Dla każdego EXPECTED szukaj transakcji bankowych w oknie:

```
EXPECTED: Czynsz, 2000 PLN, dueDate: 10 lutego

Szukaj w banku:
  - Data: 1-20 lutego (±10 dni)
  - Kwota: 1600-2400 PLN (±20%)
  - Typ: OUTFLOW
  - Status: UNCATEGORIZED lub pasująca kategoria
```

#### Krok 2: Priorytet - numer konta odbiorcy

**Numer konta to najlepszy identyfikator dla przelewów:**

```
Czynsz ZAWSZE idzie na: PL12 3456 7890 ...

Jeśli transakcja bankowa:
  - counterpartyAccount = PL12 3456 7890 ...
  - kwota: cokolwiek w rozsądnym zakresie
  - data: cokolwiek w tym miesiącu

→ 99% to czynsz (nawet jeśli kwota/data się różni)
```

#### Krok 3: Scoring kandydatów

| Kryterium | Punkty | Uwagi |
|-----------|--------|-------|
| **Numer konta odbiorcy** | 50 | Jeśli pasuje = prawie pewne |
| Kwota ±20% | 25 | Elastyczne |
| Data ±10 dni | 15 | Elastyczne |
| Pattern w opisie | 10 | Fallback |

**Threshold: 65+ = auto-match**

Jeśli numer konta pasuje (50 pkt) + kwota w zakresie (25 pkt) = 75 pkt → auto-match.
Data może być dowolna.

#### Krok 4: Decyzja

| Score | Akcja |
|-------|-------|
| 65+ | Auto-match, EXPECTED → MATCHED |
| 50-64 | Sugestia: "Czy 2050 PLN to Czynsz?" |
| <50 | Brak dopasowania, osobna kategoryzacja |

---

## Problem: Matching patterns - niska skuteczność

### Dlaczego patterns nie działają dobrze?

1. **Opisy transakcji są chaotyczne:**
```
Ten sam czynsz może wyglądać różnie:
  Miesiąc 1: "PRZELEW WYCHODZĄCY DO ZARZĄDCA NIERUCH."
  Miesiąc 2: "Przelew do ZN SP. Z O.O."
  Miesiąc 3: "przelew - czynsz styczeń"
  Miesiąc 4: "ZARZĄDCA NIERUCHOMOŚCI S 12345678901234"
```

2. **Różne banki, różne formaty:**
```
mBank:      "NETFLIX.COM 866-579-7172 NL"
PKO BP:     "NETFLIX INTERNATIONAL B.V."
Santander:  "PŁATNOŚĆ KARTĄ NETFLIX"
ING:        "Transakcja kartą NETFLIX.COM"
```

3. **Kwota + data często wystarczy** dla stałych opłat

4. **Pattern nie pomoże przy zmiennych kwotach** (zakupy)

### Lepsza strategia: Warstwy dopasowania

| Priorytet | Strategia | Skuteczność |
|-----------|-----------|-------------|
| 1 | Numer konta odbiorcy | Bardzo wysoka |
| 2 | Kwota + data | Wysoka dla stałych opłat |
| 3 | Historia dopasowań | Średnia-wysoka |
| 4 | AI kategoryzacja | Średnia |
| 5 | Pattern matching | Niska (tylko fallback) |

---

## Problem: EXPECTED + PAID = podwójne liczenie

### Scenariusz

```
Recurring Rule: Czynsz, 2000 PLN, 10-tego

Luty w Vidulum (niedopasowane):
  EXPECTED: Czynsz, 2000 PLN, dueDate: 10 lut  ← wisi
  PAID:     2050 PLN, 12 lut                    ← osobno skategoryzowana

Statystyki pokazują:
  Wydatki planowane:  2000 PLN
  Wydatki faktyczne:  2050 PLN
  Razem:              4050 PLN  ← BŁĄD! Podwójne liczenie!
```

### Gdzie to psuje raporty

| Raport | Błąd |
|--------|------|
| Miesięczny cash flow | Zawyżone wydatki o 2000 PLN |
| Bilans | Zaniżone saldo |
| Forecast | Przyszłe miesiące też błędne |
| Kategoria "Mieszkanie" | 4050 zamiast 2050 |
| Budget vs Actual | Nonsensowne porównanie |

### Rozwiązanie 1: EXPECTED nie liczy się do "actual"

```
CashFlowStats:
  actual:    tylko PAID/MATCHED/CATEGORIZED  ← rzeczywiste pieniądze
  expected:  tylko EXPECTED/OVERDUE           ← planowane, nieopłacone
  forecast:  przyszłe miesiące                ← projekcja
```

### Rozwiązanie 2: Wymuszenie decyzji przy kategoryzacji

**Kluczowa zasada: Nigdy nie twórz PAID/CATEGORIZED w kategorii gdzie jest niedopasowany EXPECTED bez pytania użytkownika.**

```
Transakcja z banku: 2050 PLN, 12 lut, kategoria: "Mieszkanie"

ZANIM system skategoryzuje:
  1. Sprawdź czy jest EXPECTED w tej kategorii w tym miesiącu
  2. Jeśli tak - WYMUŚ decyzję:

  ┌─────────────────────────────────────────────────┐
  │ Nowa transakcja: 2050 PLN, 12 lut               │
  │                                                 │
  │ Masz planowany "Czynsz" (2000 PLN, 10 lut)     │
  │ w tej samej kategorii.                          │
  │                                                 │
  │ Czy to ta sama transakcja?                      │
  │                                                 │
  │ [Tak, dopasuj] [Nie, to osobna transakcja]     │
  └─────────────────────────────────────────────────┘
```

### Rozwiązanie 3: Auto-match na koniec miesiąca

```
Koniec lutego - system widzi:
  - EXPECTED: Czynsz, 2000 PLN, kategoria "Mieszkanie" (UNMATCHED)
  - CATEGORIZED: 2050 PLN, kategoria "Mieszkanie" (bez powiązania)

Logika:
  - Ta sama kategoria ✓
  - Podobna kwota (±5%) ✓
  - Ten sam miesiąc ✓
  - Tylko jedna para ✓

  → Auto-match z powiadomieniem:
    "Czynsz (2000 PLN) dopasowany do transakcji 2050 PLN"
    [Cofnij dopasowanie]
```

### Przepływ kategoryzacji z ochroną przed duplikatami

```
Transakcja z banku
        │
        ▼
┌─────────────────────────┐
│ Auto-match z EXPECTED?  │
│ (score 65+)             │
└─────────────────────────┘
      │           │
     TAK         NIE
      │           │
      ▼           ▼
┌──────────┐  ┌─────────────────────────┐
│ MATCHED  │  │ Kategoryzuj             │
│          │  │ (bank/historia/AI)      │
└──────────┘  └─────────────────────────┘
                    │
                    ▼
              ┌─────────────────────────┐
              │ Czy jest EXPECTED       │
              │ w tej kategorii/miesiącu?│
              └─────────────────────────┘
                    │           │
                   TAK         NIE
                    │           │
                    ▼           ▼
              ┌──────────┐  ┌──────────┐
              │ PYTAJ    │  │ CATEGO-  │
              │ USERA    │  │ RIZED    │
              └──────────┘  └──────────┘
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
    ┌──────────┐        ┌──────────┐
    │ MATCHED  │        │ CATEGO-  │
    │          │        │ RIZED +  │
    │          │        │ EXPECTED │
    │          │        │ zostaje  │
    └──────────┘        └──────────┘
```

---

## Widok użytkownika - Dashboard

### Nierozwiązane transakcje

```
Dashboard - Luty 2025

⚠️ Nierozwiązane transakcje (1)

┌─────────────────────────────────────────────────────────────┐
│ Czynsz (planowany)                              2000 PLN    │
│ Oczekiwano: 10 lut                                          │
│                                                             │
│ Możliwe dopasowania:                                        │
│   ○ 2050 PLN, 12 lut, "ZARZĄDCA NIERUCH" (Mieszkanie)      │
│   ○ To nie zostało opłacone                                 │
│   ○ Usuń tę planowaną transakcję                            │
│                                                   [Rozwiąż] │
└─────────────────────────────────────────────────────────────┘
```

### Automatyczne dopasowania

```
✓ 5 transakcji dopasowanych automatycznie [Zobacz szczegóły]
```

---

## Recurring Rules - Reguły transakcji planowanych

### Model danych

```
RecurringRule:
  id: UUID
  cashFlowId: UUID

  # Co generować
  name: String                    # "Czynsz"
  description: String             # "Opłata za mieszkanie"
  amount: Money                   # 2000 PLN
  type: INFLOW | OUTFLOW
  categoryName: String            # "Mieszkanie"

  # Kiedy generować
  frequency: Frequency            # MONTHLY, WEEKLY, YEARLY, QUARTERLY, EVERY_N_DAYS
  dayOfMonth: Integer             # 1-28 lub -1 dla "ostatni dzień"
  dayOfWeek: DayOfWeek            # dla WEEKLY
  monthOfYear: Integer            # dla YEARLY (1-12)
  interval: Integer               # dla EVERY_N_DAYS

  # Okres obowiązywania
  startDate: LocalDate
  endDate: LocalDate              # opcjonalne
  maxOccurrences: Integer         # opcjonalne

  # Sezon/wykluczenia
  activeMonths: List<Integer>     # [1,2,3,4,5,6,9,10,11,12] dla przedszkola
  excludedDates: List<LocalDate>  # konkretne daty do pominięcia

  # Matching - priorytet 1 (najskuteczniejsze)
  counterpartyAccount: String     # numer konta odbiorcy

  # Matching - priorytet 2
  amountTolerance: Money          # ±50 PLN
  dateTolerance: Integer          # ±5 dni

  # Matching - priorytet 3 (fallback)
  matchingPatterns: List<String>  # ["CZYNSZ", "Zarządca"]

  # Status
  status: ACTIVE | PAUSED | ENDED
  createdAt: ZonedDateTime
  lastGeneratedPeriod: YearMonth  # do którego miesiąca wygenerowano
```

### Typy powtarzalności

| Frequency | Przykład | Parametry |
|-----------|----------|-----------|
| `MONTHLY` | Czynsz, Netflix | dayOfMonth |
| `WEEKLY` | Zakupy spożywcze | dayOfWeek |
| `YEARLY` | Ubezpieczenie | dayOfMonth + monthOfYear |
| `QUARTERLY` | Podatek VAT | dayOfMonth + quarterMonth (1,2,3) |
| `EVERY_N_DAYS` | Rata co 14 dni | interval + startDate |

### Generowanie CashChange

**Strategia: Hybrydowa**
1. Przy tworzeniu reguły - generuj do końca horyzontu (activePeriod + 11 miesięcy)
2. Przy przesunięciu activePeriod - generuj kolejny miesiąc
3. Przy edycji reguły - pytaj "tylko przyszłe czy wszystkie niezrealizowane?"

### Powiązanie z CashChange

```
CashChange:
  ...existing fields...
  paidDate: ZonedDateTime           # NOWE - kiedy faktycznie zapłacono
  recurringRuleId: UUID             # NOWE - opcjonalne, źródło reguły
  bankTransactionId: UUID           # NOWE - opcjonalne, dopasowana transakcja
  matchingScore: Integer            # NOWE - score dopasowania (0-100)
```

---

## Reconciliation - automatyczne dopasowanie

### Scoring Algorithm (zrewidowany)

| Kryterium | Punkty | Skuteczność |
|-----------|--------|-------------|
| **Numer konta odbiorcy** | 50 | Bardzo wysoka |
| Kwota ±20% | 25 | Wysoka dla stałych opłat |
| Data ±10 dni | 15 | Średnia |
| Pattern w opisie | 10 | Niska (fallback) |

**Threshold: 65+ = auto-match**

### Amount Matching

```python
def amount_score(expected, bank):
    diff_percent = abs(expected.amount - bank.amount) / expected.amount

    if diff_percent == 0:
        return 25  # dokładne dopasowanie
    elif diff_percent <= 0.05:
        return 20  # 5% różnicy
    elif diff_percent <= 0.10:
        return 15  # 10% różnicy
    elif diff_percent <= 0.20:
        return 10  # 20% różnicy
    else:
        return 0
```

### Date Matching

```python
def date_score(expected, bank):
    days_diff = abs(expected.dueDate - bank.transactionDate).days

    if days_diff == 0:
        return 15
    elif days_diff <= 3:
        return 12
    elif days_diff <= 7:
        return 10
    elif days_diff <= 10:
        return 5
    else:
        return 0
```

### Counterparty Account Matching

```python
def account_score(expected, bank):
    if not expected.counterpartyAccount:
        return 0

    if expected.counterpartyAccount == bank.counterpartyAccount:
        return 50  # pewne dopasowanie

    return 0
```

### Auto-match vs Sugestia vs Manual

```
Score 65-100: Auto-match
  - System automatycznie dopasowuje
  - Użytkownik widzi powiadomienie
  - Może cofnąć jeśli błędne

Score 50-64: Sugestia
  - System pokazuje: "Czy to jest Czynsz?"
  - Użytkownik potwierdza lub odrzuca

Score 0-49: Brak dopasowania
  - Transakcja kategoryzowana osobno
  - Sprawdzenie czy jest konflikt z EXPECTED w tej kategorii
```

---

## AI do kategoryzacji

### Kiedy używać AI?

AI jest **ostatnią warstwą** gdy inne metody zawiodą:

```python
def categorize(transaction):
    # Tier 1: Kategoria z banku
    if bank_category := transaction.bankCategory:
        return map_bank_category(bank_category)

    # Tier 2: Historia (numer konta)
    if match := match_by_account(transaction):
        return match

    # Tier 3: Historia (opis)
    if match := match_by_description_history(transaction):
        if match.confidence > 0.9:
            return match

    # Tier 4: AI (fallback)
    ai_result = ai_categorize(transaction)
    if ai_result.confidence > 0.8:
        return ai_result

    # Tier 5: Uncategorized
    return "Uncategorized"
```

### Przykład AI kategoryzacji

```
Input:  "ALLEGRO *SELLER123 WARSZAWA"
Output: { category: "Zakupy online", confidence: 0.92 }

Input:  "PRZELEW OD PRACODAWCA SP ZOO WYNAGRODZENIE"
Output: { category: "Wynagrodzenie", confidence: 0.98 }
```

### Łączenie strategii - szacowana skuteczność

| Strategia | Co łapie dobrze | Co łapie słabo |
|-----------|-----------------|----------------|
| Numer konta | Przelewy stałe (czynsz, pensja) | Płatności kartą |
| Kwota + data | Subskrypcje (Netflix, Spotify) | Zmienne kwoty |
| Historia | Powtarzające się transakcje | Pierwsze wystąpienie |
| AI | Wszystko | Wymaga kontekstu |

**Łącznie: ~95% automatycznej kategoryzacji** po kilku miesiącach uczenia.

---

## Uczenie się systemu

### MatchingHistory

```java
MatchingHistory:
  id: UUID
  userId: String

  bankTransactionPattern: String    # regex lub substring
  counterpartyAccount: String       # numer konta
  assignedCategoryName: String
  assignedRecurringRuleId: UUID

  confidence: Double                # 0.0 - 1.0
  usageCount: Integer

  createdAt: ZonedDateTime
  lastUsedAt: ZonedDateTime
```

### Kluczowa zasada UX

**Nigdy nie pytaj dwa razy o to samo.**

```
Jeśli użytkownik raz powiedział:
  "Transakcja z ALLEGRO to Zakupy online"

To każda przyszła transakcja z ALLEGRO
powinna być auto-kategoryzowana.
```

### Wykrywanie nowych recurring

```
System zauważa: 3 miesiące z rzędu transakcja "SPOTIFY" ~29 PLN

Proponuje: "Czy chcesz utworzyć regułę dla Spotify?"

Użytkownik potwierdza → System tworzy RecurringRule z wykrytymi parametrami
```

---

## Scenariusze użytkownika

### Scenariusz 1: Pierwszy import z banku

```
1. Użytkownik łączy konto bankowe
2. System pobiera transakcje z ostatnich 3 miesięcy
3. AI/historia kategoryzuje ~80%
4. Użytkownik kategoryzuje resztę (~20%):
   - "To jest czynsz, powtarza się co miesiąc" → tworzy RecurringRule
   - "To są zakupy jednorazowe" → tylko kategoria
5. System zapamiętuje dla przyszłości
```

### Scenariusz 2: Nowa transakcja bankowa pasuje do reguły

```
1. Bank: -2000 PLN, konto: PL12...3456
2. System sprawdza EXPECTED transakcje
3. Znajduje: Czynsz, 2000 PLN, counterpartyAccount: PL12...3456
4. Score: 50 (konto) + 25 (kwota) = 75
5. Auto-match → EXPECTED zmienia się na MATCHED
6. Użytkownik widzi: "✓ Czynsz dopasowany automatycznie"
```

### Scenariusz 3: Transakcja bankowa - jednorazowa

```
1. Bank: -847.50 PLN, "ALLEGRO *12345678"
2. System sprawdza EXPECTED - brak dopasowania
3. System sprawdza historię: "ALLEGRO" → "Zakupy online" (90% confidence)
4. Auto-kategoryzacja → CATEGORIZED jako "Zakupy online"
5. Użytkownik nie musi nic robić
```

### Scenariusz 4: EXPECTED bez transakcji bankowej

```
1. Reguła: Czynsz, dueDate: 10 stycznia
2. 15 stycznia - brak dopasowanej transakcji bankowej
3. Status zmienia się na OVERDUE
4. Użytkownik widzi alert: "Czynsz - brak płatności"
5. Opcje:
   - Poczekaj (może przyjdzie)
   - Dopasuj ręcznie
   - Przesuń na luty (DEFERRED)
   - Anuluj (CANCELLED)
```

### Scenariusz 5: Dwie transakcje - ta sama kategoria - konflikt

```
1. EXPECTED: Czynsz luty, 2000 PLN, kategoria "Mieszkanie"
2. Bank: 2050 PLN, auto-kategoryzacja → "Mieszkanie"
3. System wykrywa konflikt:
   "Masz planowany Czynsz (2000 PLN) w tej kategorii.
    Czy 2050 PLN to ta sama transakcja?"
4. Użytkownik: [Tak, dopasuj]
5. EXPECTED → MATCHED, paidDate = data z banku
```

### Scenariusz 6: Koniec miesiąca - UNMATCHED

```
1. Koniec lutego
2. EXPECTED: Czynsz, 2000 PLN - nadal niedopasowany
3. Status → UNMATCHED
4. Dashboard pokazuje:
   "⚠️ Nierozwiązane: Czynsz (2000 PLN)
    Możliwe dopasowania:
    ○ 2050 PLN, 12 lut, 'ZARZĄDCA'
    ○ To nie zostało opłacone
    ○ Usuń"
5. Użytkownik rozwiązuje - system zapamiętuje
```

---

## Model danych - podsumowanie zmian

### CashChange (zmodyfikowany)

```java
CashChange:
  // Existing
  cashChangeId: UUID
  name: String
  description: String
  money: Money
  type: INFLOW | OUTFLOW
  categoryName: String
  status: CashChangeStatus        // ROZSZERZONY
  created: ZonedDateTime
  dueDate: ZonedDateTime
  endDate: ZonedDateTime

  // New
  paidDate: ZonedDateTime         // kiedy faktycznie zapłacono
  recurringRuleId: UUID           // źródło reguły (opcjonalne)
  bankTransactionId: UUID         // dopasowana transakcja (opcjonalne)
  matchingScore: Integer          // score dopasowania (0-100)
```

### CashChangeStatus (rozszerzony)

```java
enum CashChangeStatus {
  // Planowane
  EXPECTED,       // czeka na dopasowanie
  MATCHED,        // dopasowane do banku
  OVERDUE,        // przeterminowane (dueDate minął)
  UNMATCHED,      // koniec miesiąca, brak dopasowania
  DEFERRED,       // przesunięte na inny termin
  CANCELLED,      // anulowane

  // Z banku
  UNCATEGORIZED,  // nowe z banku, bez kategorii
  CATEGORIZED,    // przypisana kategoria
}
```

### BankTransaction (nowy)

```java
BankTransaction:
  id: UUID
  cashFlowId: UUID
  bankAccountNumber: String

  transactionDate: LocalDate
  bookingDate: LocalDate
  amount: Money

  description: String
  counterpartyName: String
  counterpartyAccount: String     // KLUCZOWE dla matching

  bankCategory: String            // kategoria z banku (opcjonalna)
  bankTransactionId: String       // ID z systemu bankowego

  status: UNCATEGORIZED | MATCHED | CATEGORIZED
  matchedCashChangeId: UUID       // jeśli MATCHED

  importedAt: ZonedDateTime
  rawData: JsonContent            // surowe dane z API
```

### RecurringRule (nowy)

```java
RecurringRule:
  id: UUID
  cashFlowId: UUID

  name: String
  description: String
  amount: Money
  type: INFLOW | OUTFLOW
  categoryName: String

  frequency: MONTHLY | WEEKLY | YEARLY | QUARTERLY | EVERY_N_DAYS
  dayOfMonth: Integer
  dayOfWeek: DayOfWeek
  monthOfYear: Integer
  interval: Integer

  startDate: LocalDate
  endDate: LocalDate
  maxOccurrences: Integer

  activeMonths: List<Integer>
  excludedDates: List<LocalDate>

  counterpartyAccount: String     // najważniejsze dla matching
  amountTolerance: Money
  dateTolerance: Integer
  matchingPatterns: List<String>  // fallback

  status: ACTIVE | PAUSED | ENDED
  createdAt: ZonedDateTime
  lastGeneratedPeriod: YearMonth
```

### MatchingHistory (nowy - dla ML)

```java
MatchingHistory:
  id: UUID
  userId: String

  bankTransactionPattern: String
  counterpartyAccount: String
  assignedCategoryName: String
  assignedRecurringRuleId: UUID

  confidence: Double
  usageCount: Integer

  createdAt: ZonedDateTime
  lastUsedAt: ZonedDateTime
```

---

## Odpowiedź na kluczowe pytania

### Czy reconciliation pomoże użytkownikowi?

**TAK**, znacząco:

| Aspekt | Bez reconciliation | Z reconciliation |
|--------|-------------------|------------------|
| Potwierdzanie transakcji | Ręczne, każda | Automatyczne ~95% |
| Kategoryzacja | Ręczna | Automatyczna ~95% |
| Atestacja miesiąca | Wymagana | Zbędna |
| Czas użytkownika | Dużo | Minimalny |
| Błędy (duplikaty) | Możliwe | Wykrywane automatycznie |

### Czy potrzebna jest atestacja miesiąca?

**NIE**, z integracją bankową atestacja jest zbędna:

1. **Bank jest source of truth** - nie trzeba "zamykać" miesiąca
2. **Reconciliation** - zastępuje ręczne potwierdzanie
3. **UNMATCHED** - wymusza rozwiązanie problemów
4. **Czas płynie naturalnie** - miesiące zamykają się same

### Rekomendacja

Usunąć atestację. Zamiast tego:
- Automatyczne dopasowanie transakcji
- UNMATCHED dla nierozwiązanych na koniec miesiąca
- Dashboard z alertami o problemach
- Audit trail dla zmian w historii

---

## API bankowe - integracja

### Potencjalne źródła (Polska)

| Provider | Typ | Uwagi |
|----------|-----|-------|
| Kontomatik | Aggregator | Wiele banków PL |
| Salt Edge | Aggregator | PSD2, global |
| Plaid | Aggregator | Głównie US/UK |
| Bezpośrednie API banków | PSD2 | Wymaga licencji TPP |

### Sync strategy

```
1. Initial sync: 3-6 miesięcy wstecz
2. Incremental sync: co 4-12 godzin
3. On-demand sync: użytkownik może wymusić
4. Webhook (jeśli wspierane): real-time
```

---

## Pytania otwarte

1. **Horyzont forecast** - ile miesięcy do przodu generować z RecurringRules?
2. **Edycja historii** - czy można edytować MATCHED transakcje?
3. **Multi-currency** - jak obsługiwać przewalutowania?
4. **Shared CashFlow** - czy wielu użytkowników może zarządzać jednym CashFlow?
5. **Backup manual mode** - czy system działa bez API bankowego?
6. **Wybór providera API bankowego** - Kontomatik vs Salt Edge?

---

## Następne kroki

1. [ ] Dodać `paidDate` do `CashChange`
2. [ ] Zaprojektować `RecurringRule` aggregate
3. [ ] Zaprojektować `BankTransaction` entity
4. [ ] Zdefiniować API dla reconciliation
5. [ ] Wybrać provider API bankowego
6. [ ] Prototyp matching algorithm
7. [ ] Usunąć atestację miesiąca
8. [ ] Dodać UNMATCHED status i workflow
9. [ ] Zaimplementować wykrywanie duplikatów (EXPECTED + CATEGORIZED)
10. [ ] Dodać AI kategoryzację (Claude API)
