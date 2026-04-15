# VID-151: Analiza problemu granularności kategoryzacji AI

**Data analizy**: 2026-04-15
**Kontekst**: AI kategoryzuje transakcje do zbyt ogólnych kategorii ("Inne wydatki") zamiast bardziej szczegółowych

## Problem

Po wgraniu pliku CSV dla użytkownika, AI poprawnie rozpoznaje ZUS i Urząd Skarbowy jako "Opłaty obowiązkowe", ale wiele innych transakcji ląduje w generycznej kategorii "Inne wydatki":

| Pattern | AI sugeruje | Mogłoby być |
|---------|-------------|-------------|
| LUCJAN BIK PEKAO | Inne wydatki | Oszczędności / Kredyty |
| IKANO | Inne wydatki | Kredyty / Raty |
| SANTANDER | Inne wydatki | Kredyty / Raty |
| CREDIT AGRICOLE | Inne wydatki | Kredyty / Raty |
| PGE | Inne wydatki | Opłaty obowiązkowe / Rachunki |
| TUIR WARTA | Inne wydatki | Ubezpieczenia |
| SILVA SILVA | Inne wydatki | Mieszkanie / Czynsz |

## Analiza danych wejściowych

### Struktura transakcji w CSV (Nest Bank)

```csv
Data,Rodzaj operacji,Kwota,Dane kontrahenta,Numer rachunku kontrahenta,Tytuł operacji
31-12-2025,Przelewy wychodzące,-3000,Lucjan Bik Pekao,PL98124014441111001078171074,zycie
12-12-2025,Przelewy wychodzące,-2837,Urzad skarbowy w Mielcu,68101000712222817219307000,PIT28
12-12-2025,Przelewy wychodzące,-2415.9,ZUS,29600000020260018172193070,składki ZUS
```

### Najczęstsi kontrahenci

| Kontrahent | Ilość | Suma PLN | Tytuły operacji |
|------------|-------|----------|-----------------|
| Lucjan Bik Pekao | 74 | 329k | "zycie", "mieszkanie kredyt" |
| Urząd skarbowy | 51 | 330k | "PIT28", "VAT7K" |
| ZUS | 37 | 89k | "składki ZUS" |
| Ikano | 36 | 116k | "rata kredytu" |
| IFIRMA SA | 35 | 10k | "Faktura VAT nr..." |
| Lucjan Bik mbank | 32 | 167k | "mieszkanie kredyt", "nadplata" |
| Silva Silva | 15 | - | "czynsz" |
| Santander | 9 | 7k | "rata kredytu" |
| Credit Agricole | 9 | 4k | "umowa kredytu" |

### Najczęstsze tytuły operacji

| Tytuł | Ilość | Znaczenie |
|-------|-------|-----------|
| "zycie" | 66 | Przelewy własne (oszczędności?) |
| "składki ZUS" | 36 | Składki społeczne |
| "mieszkanie kredyt" | 29 | Rata kredytu hipotecznego |
| "rata kredytu" | 21 | Rata kredytu |
| "czynsz" | 15 | Opłata za mieszkanie |

## Kluczowe odkrycie: AI nie widzi tytułów!

W obecnej implementacji `AiCategorizationPromptBuilder.formatPatternGroup()`:

```java
sb.append(String.format("    | name: \"%s\"\n", pg.sampleTransaction()));
sb.append(String.format("    | title: \"%s\"\n", pg.sampleDescription()));
```

**Problem**: `sampleDescription` jest często puste lub zawiera tylko jeden przykład.

AI otrzymuje:
```
[74 txns, 329.2k] LUCJAN BIK PEKAO
  | name: "Lucjan Bik Pekao"
  | bank: Przelewy wychodzące
```

Ale **nie widzi**, że:
- 66 transakcji ma tytuł "zycie"
- 8 transakcji ma tytuł "mieszkanie kredyt"

---

## Pomysły na rozwiązanie

### Pomysł 1: Pokazać najczęstsze tytuły w promptcie

**Zmiana**: W `PatternDeduplicator` zbierać top 3 najczęstszych tytułów dla każdego wzorca.

**Przed**:
```
[74 txns, 329.2k] LUCJAN BIK PEKAO
  | name: "Lucjan Bik Pekao"
  | bank: Przelewy wychodzące
```

**Po**:
```
[74 txns, 329.2k] LUCJAN BIK PEKAO
  | name: "Lucjan Bik Pekao"
  | common titles: "zycie" (66x), "mieszkanie kredyt" (8x)
  | bank: Przelewy wychodzące
```

**Korzyści**:
- AI zobaczy, że "Lucjan Bik Pekao" z tytułem "mieszkanie kredyt" to spłata kredytu
- AI może zasugerować podział na subkategorie na podstawie tytułów

**Wymagane zmiany**:
- `PatternDeduplicator.PatternGroup` - nowe pole `commonTitles: Map<String, Integer>`
- `AiCategorizationPromptBuilder.formatPatternGroup()` - wyświetlić top 3 tytuły

---

### Pomysł 2: Słownik polskich instytucji finansowych

**Zmiana**: Dodać do `getSystemPrompt()` wiedzę domenową o polskim rynku.

```
Polish financial institutions knowledge:

MANDATORY PAYMENTS (Opłaty obowiązkowe):
- ZUS = Zakład Ubezpieczeń Społecznych - mandatory social insurance
- Urząd Skarbowy = Tax Office - PIT, VAT, CIT payments

BANKS (likely loan/mortgage payments):
- Santander, mBank, ING, Credit Agricole, PKO BP, Pekao, BNP Paribas
- Ikano Bank = IKEA financing, installment loans for furniture

INSURANCE COMPANIES (Ubezpieczenia):
- WARTA, PZU, UNIQA, Allianz, Generali, AXA, Ergo Hestia

UTILITIES (Rachunki):
- PGE, TAURON, ENEA, Energa = Electricity
- PGNiG = Gas
- Veolia, MPWiK = Water

TELECOM (Telekomunikacja):
- Play, Orange, T-Mobile, Plus

ACCOUNTING SaaS (Księgowość):
- IFIRMA, inFakt, wFirma = Accounting software for freelancers/B2B

CATEGORIZATION HINTS:
- If counterparty is a bank AND title contains "rata", "kredyt" → category: Kredyty
- If title contains "czynsz" → category: Mieszkanie
- If title contains "składki ZUS" → category: ZUS (parent: Opłaty obowiązkowe)
```

**Korzyści**:
- AI będzie wiedział, że Ikano to bank = prawdopodobnie raty
- AI rozpozna PGE jako rachunki za prąd
- AI przypisze WARTA do ubezpieczeń

**Wymagane zmiany**:
- `AiCategorizationPromptBuilder.getSystemPrompt()` - rozszerzyć o słownik

---

### Pomysł 3: Grupowanie po tytule dla znanych banków

**Obserwacja**: Dla kontrahentów będących bankami, nazwa kontrahenta jest mało informacyjna. Tytuł operacji ("rata kredytu") jest kluczowy.

**Zmiana**: W `PatternDeduplicator` dla kontrahentów rozpoznanych jako banki, grupować dodatkowo po tytule.

**Przed** (1 grupa):
```
LUCJAN BIK PEKAO [74 txns] → Inne wydatki
```

**Po** (2 grupy):
```
LUCJAN BIK PEKAO / zycie [66 txns] → Oszczędności
LUCJAN BIK PEKAO / mieszkanie kredyt [8 txns] → Kredyty
```

**Wymagane zmiany**:
- Lista znanych banków (hardcoded lub konfiguracja)
- Logika w `PatternDeduplicator.deduplicate()` - dla banków grupować po `name + title`

---

### Pomysł 4: Dwuetapowa kategoryzacja

**Etap 1**: Obecne podejście - kategoryzacja po kontrahentach
**Etap 2**: Dla kategorii "Inne wydatki" z > 10 transakcjami - drugie przejście AI analizujące tytuły

**Korzyści**:
- Nie zmienia obecnego flow dla prostych przypadków
- Dodatkowa granularność tylko gdzie potrzebna

**Wady**:
- Podwójny koszt AI dla niektórych transakcji
- Bardziej skomplikowany flow

---

### Pomysł 5: Heurystyki pre-AI

**Zmiana**: Przed wysłaniem do AI, zastosować deterministyczne reguły.

```java
// Pre-AI rules
if (counterparty.contains("ZUS")) → category: "ZUS", parent: "Opłaty obowiązkowe"
if (counterparty.contains("Urząd Skarbowy")) → category: "Podatki", parent: "Opłaty obowiązkowe"
if (title.contains("rata kredytu") || title.contains("kredyt")) → category: "Kredyty"
if (title.contains("czynsz")) → category: "Mieszkanie"
if (counterparty in KNOWN_BANKS && title.contains("rata")) → category: "Kredyty"
```

**Korzyści**:
- Deterministyczne, przewidywalne wyniki
- Szybsze (nie czeka na AI)
- Tańsze (mniej tokenów do AI)

**Wady**:
- Wymaga utrzymania listy reguł
- Może być zbyt sztywne

---

## Rekomendacja

### Faza 1 (Quick Win): Pomysł 1 + 2

1. **Rozszerzyć prompt o common titles** - pokazać AI najczęstsze tytuły dla każdego wzorca
2. **Dodać słownik polskich instytucji** - dać AI wiedzę domenową

**Dlaczego**:
- Minimalny refaktor kodu
- Nie zmienia architektury
- Wykorzystuje istniejące dane (tytuły są już w transakcjach)

### Faza 2 (Opcjonalnie): Pomysł 3

Jeśli Faza 1 nie da wystarczającej granularności, rozważyć grupowanie po tytule dla banków.

---

## Pliki do zmiany (Faza 1)

| Plik | Zmiana |
|------|--------|
| `PatternDeduplicator.java` | Dodać `commonTitles` do `PatternGroup` |
| `AiCategorizationPromptBuilder.java` | Wyświetlić common titles w promptcie |
| `AiCategorizationPromptBuilder.java` | Rozszerzyć system prompt o słownik instytucji |

---

## Przykład oczekiwanego wyniku po zmianach

**Przed**:
```
OUTFLOW:
  Opłaty obowiązkowe
    - ZUS
    - Urząd skarbowy
  Inne wydatki (585 txns)
    - Lucjan Bik, Ikano, Santander, Credit Agricole, PGE, WARTA...
```

**Po**:
```
OUTFLOW:
  Opłaty obowiązkowe
    - ZUS
    - Podatki (Urząd skarbowy)
    - Rachunki (PGE)
  Kredyty i raty
    - Kredyt hipoteczny (mBank)
    - Raty Ikano
    - Raty Santander
    - Raty Credit Agricole
  Mieszkanie
    - Czynsz (Silva Silva)
  Ubezpieczenia
    - WARTA
  Księgowość
    - IFIRMA
  Inne wydatki
    - Przelewy własne (Lucjan Bik Pekao)
```
