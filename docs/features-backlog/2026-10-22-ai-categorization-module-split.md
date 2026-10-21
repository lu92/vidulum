# Wydzielenie `bank-data-ai-categorization` z `bank-data-ingestion`

**Status:** zrobione (2026-10-22) · **Zakres:** warianty A i B z analizy, w jednej zmianie

## Dlaczego

`bank-data-ingestion` miał 120 plików i 15 214 linii, z czego **~4 100 w 16 plikach** było
kategoryzacją AI, rozsianą po trzech warstwach: silnik w `app/categorization/`, typy w `domain/`,
pamięć wzorców w `infrastructure/`. Jedna klasa na 120 dotykała `spring-ai`
(`AiCategorizationService`), a zależność `spring-ai-starter-model-anthropic` ciągnęła **cały**
moduł — razem z CSV, stagingiem i importem.

To był wystarczający powód, by granicę przesunąć. Ale dopiero pomiar pokazał, **gdzie** ją
postawić.

## Dwa znaleziska, które zdecydowały o kształcie

### 1. Cache wzorców nie należał tylko do AI

`PatternMapping` czytały dwie komendy **stagingowe** — `StageTransactionsCommandHandler` i
`RevalidateStagingCommandHandler` — żeby przypisać kategorie bez pytania modelu. Zapisywał go
`accept_ai_suggestions`. Czyli pamięć AI była używana jako rulebook kategoryzacji przez trzech
różnych właścicieli kodu.

### 2. Reguła dopasowania istniała w dwóch rozjechanych kopiach

Wzorce **powstają** przez `TransactionNameNormalizer` (205 linii: zdejmuje miasta, ulice, daty,
numery kart, rozpoznaje marki). Ale **stosowane** były tak:

```java
String normalizedName = transactionName.toUpperCase();          // staging:185, revalidate:267
patterns.stream().filter(p -> normalizedName.contains(p.normalizedPattern()))
```

Dwie reguły dla jednej relacji, w trzech miejscach, bez żadnego testu, który by je porównał.

## Jaka reguła jest poprawna — trzy kandydatki na prawdziwych danych

To był najtrudniejszy fragment tej roboty i pierwsza wersja implementacji **była zła**.

| reguła | `ZAKŁAD UBEZPIECZEŃ SPOŁECZNYCH ZUS` ↔ `ZUS` | `DEMOLKA SERWIS` ↔ `MOL` |
|---|---|---|
| `contains` (stara) | dopasowuje ✔ | **dopasowuje ✘** |
| `normalize(name).equals(pattern)` (moja pierwsza) | **gubi ✘** | odrzuca ✔ |
| **zawieranie po granicy słowa** (wdrożona) | dopasowuje ✔ | odrzuca ✔ |

Druga wygląda czyściej i jest błędna, bo normalizator rozpoznaje markę **tylko na początku** nazwy
(`startsWith(brand + " ")`), a banki dopisują akronim na końcu wystarczająco często, by to miało
znaczenie. Dowiedziałem się tego nie z analizy, a z trzech padających testów integracyjnych —
i to jest argument za tym, żeby takie zmiany puszczać przez prawdziwy przebieg, a nie przez
rozumowanie.

Trzecia zachowuje **każdy** przypadek, który stara łapała poprawnie, i usuwa te, które łapała
błędnie. Granice sprawdzane są `Character.isLetterOrDigit`, nie regexowym `\b`, bo `\b` opiera się
na ASCII, a te nazwy są pełne Ł, Ż i Ę (`ŻABKA` ↔ `ŻABKAMI` to osobny przypadek testowy).

**Dlaczego to w ogóle defekt, a nie drobiazg:** lista zasiewana ma 173 wzorce, w tym `BP`, `CCC`,
`DOZ`, `AXA`, `MOL`. Trzy litery wewnątrz dłuższego słowa to nie przypadek teoretyczny, a wiersz
dopasowany błędnie wygląda dla importującego **identycznie** jak dopasowany poprawnie.

## Co powstało

```
vidulum-app-cashflow/
├── bank-data-ai-categorization-api/     # KONTRAKT: 9 plików, bez Springa, bez spring-ai
│   ├── TransactionCategorizationApi     #   suggestFor · matchLearned · learn · forget
│   ├── TransactionToCategorize          #   11 pól — zmierzonych, nie wybranych
│   ├── CategorizationRequest · CategoryStructure · TransactionToMatch
│   ├── PatternMatches · PatternToLearn · PatternSource
│   └── AiCategorizationResult           #   przeniesiony, zwężony (patrz niżej)
└── bank-data-ai-categorization/         # SILNIK + PAMIĘĆ: 5 klas silnika, cache, seeder, cleaner
```

### Kontrakt wyprowadzony z wołających, nie z klasy

| metoda | skąd się wzięła |
|---|---|
| `suggestFor` | `AiCategorizeCommandHandler`, który miał wstrzyknięty silnik i jego repozytorium |
| `matchLearned` | dwa handlery stagingowe z własną kopią reguły — **to ta metoda sprawia, że reguła istnieje raz** |
| `learn` | `accept_ai_suggestions`, które samo pisało dokumenty wzorców |
| `forget` | rollback importu: jeśli transakcje są wycofywane, wzorce wyuczone z ich akceptacji to ta sama decyzja |

**Czego celowo nie ma:** metody oddającej wzorce cash flowa. Wołający, który je pobierał, robił to
tylko po to, by dopasowywać nazwy — a to jest `matchLearned`. `patternsFor(cashFlowId)` zaprosiłby
następnego wołającego do odtworzenia reguły, dla której ten kontrakt istnieje. Ten sam argument co
przy `ImportWindow`.

### Trzy zwężenia na granicy

1. **`sessionId` to `String`, nie `StagingSessionId`.** Silnik nigdy w to nie zaglądał — odbija to
   wołającemu, żeby ten mógł skorelować odpowiedź.
2. **`PatternSuggestion.fromCache` bierze wartości, nie dokument `PatternMapping`.** W czym pamięć
   jest przechowywana, to sprawa jej właściciela.
3. **`classification` jedzie jako słowo, nie enum.** Dokładnie ten sam kontrakt tekstowy, jaki
   adapter ma już z ingestion: producent może znać słowa, dla których ten moduł nie ma polityki, i
   takie po prostu wpadają do AI. **Żadnej trzeciej kopii enuma.**

### Wejście zmierzone, nie wybrane

`TransactionToCategorize` ma 11 pól, bo tyle dokładnie czyta sześć klas silnika z
`StagedTransaction` — sprawdzone greppem po faktycznych wywołaniach, nie wywnioskowane. Reszta
staged transaction (status walidacji, sesja, mapped data, flagi duplikatów, daty) silnika nie
interesuje. Kontrakt przyjmujący cały wiersz zamieniłby każdą zmianę storage'u ingestion w zmianę
wejścia tego modułu.

## Co ten pom mówi o module

Nowy moduł **nie ma** `spring-boot-starter-webmvc` ani `spring-kafka` — nie ma endpointu i nic nie
ogłasza. Ma Mongo (swoją pamięć), Spring (żeby działać) i `spring-ai` (żeby rozmawiać z modelem).

**Endpointy zostały w ingestion**, razem z komendami: `AiCategorizeCommandHandler` zarządza stanem
sesji stagingowej (`startAiCategorization` / `completeAiCategorization` / `failAiCategorization`), a
`AcceptAiSuggestionsCommandHandler` wstrzykuje dwa inne handlery ingestion. Przeniesienie ich
zrobiłoby z ingestion dostawcę dla nowego modułu — kontrakt w dwie strony i ryzyko cyklu.
**Mierzalny dowód, że granica jest w dobrym miejscu: 3 277 linii testów HTTP nie zmieniło się ani o
znak.**

## `spring-ai` zniknął z pomu ingestion

To jest ten test, który albo przechodzi, albo cała zmiana jest pozorna. Przechodzi.

## Testy

| | |
|---|---|
| przeniesione 1:1 | 3 testy silnika (1 388 linii) + `CategoryStructureTest` do api |
| **nowy** `LearnedPatternMatcherTest` | 9 przypadków: tabela trzech reguł, polskie litery, strona INFLOW/OUTFLOW, najpewniejszy wygrywa, `recordUsage` tylko dla trafionych |
| poprawka importów | 2 testy integracyjne wzorców |
| **bez zmian** | 3 277 linii testów HTTP ingestion |

**Prognoza, która się nie potwierdziła — na moją korzyść:** zapowiadałem przepisanie
`PatternMatchingIntegrationTest` i `DirectCategoryMatchIntegrationTest` (11 przypadków, 928 linii).
Po wdrożeniu reguły granicy słowa **przeszły bez zmiany treści** — zmieniły się tylko importy. To
jest najlepszy dostępny dowód, że nowa reguła nie jest kompromisem: zachowuje zachowanie, na które
ktoś już kiedyś napisał asercje.

**Dowód negatywny:** przywrócenie `contains` w `LearnedPatternMatcher` wywala 3 z 9 przypadków, z
komunikatem *„MOL is inside DEMOLKA the way ART is inside PARTNER"*.

## Czego ta zmiana nie zrobiła

- **`GlobalPatternSeeder` (173 wzorce) pojechał z pamięcią** — i to było w wariancie A zgrzytem,
  który B usuwa: seeder zasiewa pamięć AI, więc należy do AI.
- **Polityka „słowo klasyfikacji → polska kategoria"** (`BANK_FEE` → „Opłaty bankowe") jest teraz
  mapą na słowach, nie `switch`em na enumie. Ryzyko: przemianowanie słowa w słowniku po cichu
  odbiera auto-kategorię i wiersz wpada do AI — degradacja, nie korupcja, ale warta testu
  end-to-end, którego **nie** dopisałem.
- **Nie ruszyłem `TransactionClassification`** — zostaje zduplikowany między adapterem i ingestion,
  jak chce tego wpis w CLAUDE.md, i nowy moduł nie dodaje trzeciej kopii.
