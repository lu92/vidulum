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
4. **Każda encja `@Document` ląduje w `DataCleaner`** swojej subdomeny, i to przez
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
