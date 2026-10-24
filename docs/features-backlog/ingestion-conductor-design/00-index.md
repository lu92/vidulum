# `data-ingestion-conductor` — specyfikacje modułów

**Data:** 2026-10-24 · **Status:** projekt, nic nie zaimplementowane ·
**Dokument nadrzędny:** [`../2026-10-24-data-ingestion-conductor-saga.md`](../2026-10-24-data-ingestion-conductor-saga.md)

Jeden plik na moduł Mavena. Każdy odpowiada na te same cztery pytania: **co moduł posiada**, **jaki
ma kontrakt** (metody, komendy, odpowiedzi), **jakie testy** i na którym poziomie, oraz **czego w nim
nie ma i gdzie to jest**.

| # | plik | artifactId | posiada dane? | poziom testów |
|---|---|---|---|---|
| 01 | [`01-ingestion-canonical.md`](01-ingestion-canonical.md) | `vidulum-ingestion-canonical` | nie | unit |
| 02 | [`02-ingestion-intake.md`](02-ingestion-intake.md) | `vidulum-ingestion-intake` | nie | unit |
| 03 | [`03-ingestion-conductor-api.md`](03-ingestion-conductor-api.md) | `vidulum-ingestion-conductor-api` | nie | unit (kontraktowe) |
| 04 | [`04-ingestion-conductor.md`](04-ingestion-conductor.md) | `vidulum-ingestion-conductor` | **`ingestion_sessions`** | unit + component |
| 05 | [`05-ingestion-staging.md`](05-ingestion-staging.md) | `vidulum-ingestion-staging` (+ `-api`) | **`ingestion_rows`** | unit + component |
| 06 | [`06-ingestion-classification.md`](06-ingestion-classification.md) | `vidulum-ingestion-classification` | `ingestion_classifications` (cache) | unit + component |
| 07 | [`07-ingestion-category-resolution.md`](07-ingestion-category-resolution.md) | `vidulum-ingestion-category-resolution` | **`ingestion_category_mappings`** | unit + component |
| 08 | [`08-ingestion-cashflow-writer.md`](08-ingestion-cashflow-writer.md) | `vidulum-ingestion-cashflow-writer` | **nie** | unit + component |
| 09 | [`09-ingestion-tests.md`](09-ingestion-tests.md) | `vidulum-ingestion-tests` | nie (bez `src/main`) | integration + ArchUnit |
| 10 | [`10-granice-artefaktow.md`](10-granice-artefaktow.md) | — | **audyt granic** zmierzony na dzisiejszym kodzie + potwierdzenie `/api/v2` | — |
| 11 | [`11-znaleziska-z-danych.md`](11-znaleziska-z-danych.md) | — | **znaleziska z drugiego wyciągu bankowego**: mechanizmy, pomiary i scenariusze dla zadań M7, M8, M9, K15, X7, R9, R10 | — |
| 13 | [`13-import-ciagly.md`](13-import-ciagly.md) | — | **wgrywanie kolejnych wyciągów po atestacji**: co cashflow już przewiduje, która blokada jest wyłączona przez brak endpointu, tryb `BACKFILL` vs `ONGOING` | S17, X8, X2, U8 |
| 12 | [`12-ui-flow.md`](12-ui-flow.md) | — | **przepływ po stronie UI**: faza → ekran, dostępność endpointów, czego userowi nie wolno policzyć samemu, poprawa w liczbach. Mockupy: [`docs/design/ingestion-v2-ui-mockups.html`](../../design/ingestion-v2-ui-mockups.html) | ścieżka U |

> **Plik 10 jest nadrzędny wobec pozostałych tam, gdzie się różnią.** Powstał z pomiaru
> dzisiejszego `bank-data-ingestion` i poprawił sześć rzeczy w specyfikacjach 01, 05, 06, 07 i 09 —
> każda poprawka jest w nich naniesiona i oznaczona.

**Zadania, statusy i graf zależności:** [`../ingestion-v2-tasks.md`](../ingestion-v2-tasks.md) —
67 zadań wyprowadzonych z tych plików, każde z tytułem gotowym do PR i definicją ukończenia.

## Dwie rzeczy, o których trzeba wiedzieć przed pierwszym commitem

**Liczby z przykładu są ilustracyjne, nie zmierzone.** 38 grup, 9 z 38 rozstrzygniętych darmowo,
29 otwartych, 18/8/3 po progach, 11 kategorii w planie, 381 ważnych wierszy z 402 — to wartości
*prawdopodobne*, wyprowadzone z kształtu wyciągu, a **nie** wynik przebiegu. Nie dało się ich
zmierzyć, bo fixture, na którym miałyby powstać, nie istnieje (patrz niżej). W definicjach ukończenia
używaj więc **asercji niezmiennikowych**: suma `valueShare` ≈ 1,0, liczba grup = liczba unikalnych
kluczy, `total = valid + duplicate + rejected`, plan zwalidowany `plan.validated()`. Konkretne liczby
wpisz dopiero **po** pierwszym przebiegu na prawdziwym pliku — i wtedy są już faktem, nie
oczekiwaniem.

**Pliku kanonicznego 402-wierszowego nie ma w repo.** `nestbank_lista_operacji_20260111.csv` (409
linii) jest **surowy**: zaczyna się od `Numer rachunku:` i `Właściciel:`, ma kolumny Nest Banku. Jedyny
fixture w formacie kanonicznym to `bank-data-ingestion/historical-transactions.csv` — **23 wiersze i
11 kolumn**, czyli nagłówek bez `merchant`, `merchantConfidence`, `paymentMethod`, `classification`,
`classificationReason` i `location`. Wytworzenie fixture'u 17-kolumnowego jest **zadaniem K12** i jest
warunkiem wstępnym K6 (parser) oraz T1 (moduł testów).

## Zasady wspólne dla wszystkich plików

**Poziomy testów** — z CLAUDE.md, „Test Levels". Asercja idzie na **najtańszy poziom, który potrafi
ją postawić**:

| poziom | jak biegnie | co tam należy |
|---|---|---|
| **unit** | bez Springa, bez współpracowników | reguły jednego typu, parsowanie, wartości |
| **component** | bez Springa, **prawdziwi współpracownicy** + `InMemory*Repository` | cały przypadek użycia z mapowaniem encji, asercje całoobiektowe |
| **integration** | `@SpringBootTest` + Testcontainers + `*HttpActor` | to, co widać tylko na działającym serwerze: kody HTTP, serializacja, indeksy, Kafka |

**Dlatego żaden moduł z tej rodziny poza `vidulum-ingestion-tests` nie ma testu Springowego.**
To nie oszczędność, to warunek utrzymania reaktora acyklicznym: test, który potrzebuje *działającego*
cash flow, musiałby zależeć od `vidulum-cashflow` (implementacji), a workerzy zależą wyłącznie od
kontraktów. Tak samo jak `vidulum-cashflow-tests` i `vidulum-wealth-tests` dziś.

**Cztery reguły z CLAUDE.md, które obowiązują w każdym pliku:**

1. **Konstruktory, nie buildery, w obiektach oczekiwanych** — dodanie pola do modelu ma **nie
   skompilować** testu, zamiast cicho przejść.
2. **Asercja całoobiektowa** — `usingRecursiveComparison()`, minimum ignorowanych pól; czas przez
   wstrzyknięty `Clock`.
3. **`lombok` i startery testowe przychodzą z roota** — pom modułu wymienia tylko to, co o nim coś
   mówi: `-data-mongodb` = „persystuje", `-webmvc` = „ma endpointy", `spring-kafka` = „mówi
   zdarzeniami". `*-api` nie deklaruje żadnego z nich.
4. **Osiem nowych `ErrorCode` jednym commitem na początku P0.** `ErrorHttpHandler` siedzi w
   `vidulum-shared-kernel`, więc każdy nowy kod to zmiana shared-kernela, a to z reguł CLAUDE.md
   znaczy **pełne `./mvnw clean test`** (~7 min, 21 modułów). Osiem kodów po jednym na moduł to
   osiem pełnych przebiegów zamiast jednego. Lista w [pliku 10, §6](10-granice-artefaktow.md).
5. **Każda encja `@Document` ląduje w `DataCleaner`** swojej subdomeny, i to przez
   `mongoTemplate.remove(new Query(), X.class)` — **nigdy** `dropCollection`.

**Zakres budowania** — proponowany wiersz do tabeli „Deciding the scope" w CLAUDE.md:

| zmieniony | uruchom | dlaczego |
|---|---|---|
| `ingestion-canonical` / `-conductor-api` | `install` + `-f vidulum-app-ingestion/pom.xml` + `-pl :vidulum-ingestion-tests` | to powierzchnia kompilacji całej rodziny |
| jeden worker | `-pl :vidulum-ingestion-<worker>` (sekundy), potem `-pl :vidulum-ingestion-tests` | testy jednostkowe lokalnie, przepływ w module testów |
| `ingestion-conductor` | `-pl :vidulum-ingestion-conductor,:vidulum-ingestion-tests` | saga jest testowana oba poziomy |

## Konwencje nazewnicze w kodzie

| rzecz | wzór | przykład |
|---|---|---|
| komenda kroku (Kafka) | `<Czasownik><Rzecz>Command` | `ValidateRowsCommand` |
| interfejs handlera (gniazdo) | `<Czasownik><Rzecz>Handler` | `ValidateRowsHandler` |
| listener Kafki (implementacja) | `<Czasownik><Rzecz>Listener` | `ValidateRowsListener` |
| komenda CQRS w module | `<Czasownik><Rzecz>CqrsCommand` | `ValidateRowsCqrsCommand` |
| wynik kroku | `<Rzecz><Imiesłów>` | `RowsValidated`, `CategoriesProvisioned` |
| topic komendy | `ingestion.cmd.<krok>` | `ingestion.cmd.validate` |
| topic odpowiedzi | `ingestion.reply` | jeden dla wszystkich kroków |
| `groupId` konsumenta | nazwa modułu | `ingestion-staging` |

Rozróżnienie `Command` (CQRS, w procesie) od `Command` (krok sagi, na topicu) jest opisane w §19
dokumentu nadrzędnego i jest powodem sufiksu `CqrsCommand` w drugim rzędzie tabeli.
