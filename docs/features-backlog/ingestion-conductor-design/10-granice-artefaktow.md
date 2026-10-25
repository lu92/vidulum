# 10 · Granice między artefaktami — zbadane na dzisiejszym kodzie

**Data:** 2026-10-24 · **Metoda:** nie deklaracja, a pomiar. Dzisiejszy `bank-data-ingestion` jest
jedynym dowodem, które typy faktycznie przechodzą przez które linie — więc proponowane granice
zostały przyłożone do jego 104 plików i policzone.

Wynik w jednym zdaniu: **z dziesięciu krawędzi, które dziś istnieją między sekcjami, osiem zostaje
zerwanych przez proponowany podział, jedna zostaje legalna i nazwana, a jedna — ta między
klasyfikacją a danymi wierszowymi — nie trzyma w kształcie, który opisałem, i wymaga poprawki.**

---

## 1. Krawędzie między sekcjami dziś

Liczba plików sekcji **A**, które importują coś z sekcji **B**:

| from → to | plików | co faktycznie przechodzi | los w nowym podziale |
|---|---|---|---|
| `importing` → `staging` | **11** | `StagedTransaction`, `StagingSessionId`, repozytorium, **encja i `StagingSessionMongoRepository`** | **zwężona** do `IngestionRowsApi` (tylko czytanie) |
| `classification` → `staging` | **6** | encja sesji + jej repozytorium Spring Data; 6 wywołań cyklu życia **obcej encji** | **zerwana w całości** |
| `staging` → `canonical` | 8 | wiersz kanoniczny | zostaje (to słownik) |
| `staging` → `mappings` | 3 | `CategoryMapping`, `MappingAction`, repozytorium | **zerwana** — drabina wyjeżdża do `category-resolution` |
| `staging` → `intake` | 2 | `CsvParserService` w `upload_csv` | **zerwana** — parsowanie to krok `PARSE` conductora |
| `importing` → `mappings` | 2 | `MappingAction` przy decydowaniu, co utworzyć | **zerwana** — writer dostaje gotowy `CategoryPlan` |
| `importing` → `canonical` | 2 | wiersz | zostaje |
| `classification` → `canonical` | 2 | wiersz | zostaje |
| `classification` → `mappings` | 1 | `accept_classification` konfiguruje mapowania | **zerwana** — robi to `LEARN` w resolution |
| `intake` → `canonical` | 1 | wiersz | zostaje |

**Najdroższa krawędź to `importing → staging` (11 plików), a najbrzydsza `classification → staging`
(6).** Pierwsza jest naturalna — import pisze to, co staging zdecydował — i dlatego zostaje, tylko
przez kontrakt. Druga jest patologią, którą opisuje F68: sekcja klasyfikacji ładuje encję Mongo
należącą do stagingu, woła na niej `startClassification()` / `completeClassification()` /
`failClassification()` i zapisuje. Po podziale **nie ma** jak tego zrobić, bo encji nie ma w
classpathie — i to jest cały zysk z granicy Mavena nad testem ArchUnit.

## 2. Wywołania kontraktów obcych modułów — kto woła co dziś, a kto będzie

| metoda kontraktu | dziś (sekcje) | po podziale (artefakt) | zmiana |
|---|---|---|---|
| `CashFlowModuleApi.createCategory` | **3 sekcje**: `staging` (×2, force-uncategorized), `classification` (accept-ai), `importing` | **1**: `cashflow-writer` | 3 → 1 |
| `CashFlowModuleApi.skeletonOf` | `staging` (×4), `importing` (×2), `classification` | `conductor` (pre-flight), `staging` (okno importu), `cashflow-writer` (`isInSetup`) | 3 sekcje → 3 artefakty, ale **inne role** |
| `CashFlowModuleApi.alreadyImported` | `staging` (×3) | `staging` | bez zmian |
| `CashFlowModuleApi.importHistorical` | `importing` | `cashflow-writer` | bez zmian |
| `CashFlowModuleApi.rollbackImport` | `importing` | `cashflow-writer` | bez zmian |
| `CashFlowModuleApi.exists` | `classification`, `mappings` | `conductor` | 2 → 1 |
| `CashFlowForecastApi.monthStatusesOf` | `staging` | `staging` | bez zmian |
| `TransactionCategorizationApi.matchLearned` | `staging` (×2 — **dwie kopie drabiny**) | `category-resolution` (×1) | 2 → 1 |
| `TransactionCategorizationApi.suggestFor` | `classification` | `ingestion-classification` | bez zmian |
| `TransactionCategorizationApi.learn` | `classification` | `category-resolution` | przeniesione |
| `TransactionCategorizationApi.forget` | **nikt** — 0 wywołań produkcyjnych | `category-resolution` (krok `UNLEARN`) | **metoda martwa od F66 ożywa** |
| `UserFinancialProfileApi` | `staging` (3 pliki) | `staging` | bez zmian |

Trzy wnioski, które warto wypisać osobno:

- **`createCategory` z 3 do 1 miejsca** jest najlepszym pojedynczym dowodem, że granica writera jest
  prawdziwa. Dziś trzy sekcje tworzą kategorie trzema różnymi ścieżkami, każda z własnym `catch`-em.
- **`matchLearned` z 2 do 1** usuwa drugą kopię drabiny — ta sama klasa defektu, którą F66 usunęło
  jedną warstwę niżej.
- **`forget` jest dziś metodą kontraktu bez konsumenta** (0 wywołań w produkcji, dokładnie to, przed
  czym CLAUDE.md przestrzega: „kontrakt powstaje, gdy istnieje konsument"). W nowym projekcie
  dostaje go: kompensacja importu zapomina wzorce, bo to była ta sama decyzja człowieka.

## 3. Trzy granice, które trzymają bez naciągania

**(1) `conductor` ↛ worker.** Conductor potrzebuje z cashflow dokładnie dwóch rzeczy —
`exists` i `skeletonOf` dla odmowy 404/409 przed założeniem sesji — i niczego od workerów.
Sprawdzalne regułą na poziomie wywołań metod, nie pakietów.

**(2) `classification` jako liść.** Dziś jedyne, co wiąże klasyfikację z resztą, to pisanie do encji
sesji. Gdy stan kroku trafia do sesji conductora, zostaje **zero** powodów, by ktokolwiek zależał od
tego modułu. To ta sama reguła co `nothingDependsOnClassification` w F67, tylko niemożliwa do
obejścia.

**(3) `cashflow-writer` bez storage.** Pomiar potwierdza: `importing` czyta staged rows, woła trzy
metody cashflow i zapisuje **własny** `ImportJobEntity`. W nowym podziale ten rekord to sesja
conductora, więc writerowi nie zostaje nic do zapisania — stąd brak `-data-mongodb` w jego pomie,
jak w `vidulum-risk` i `vidulum-quotation`.

## 4. Granica, która NIE trzyma: klasyfikacja a dane grupowe

**To jest główny wynik badania.** W dokumencie nadrzędnym (§20, §21) napisałem: „dane wierszowe
podróżują referencją, dane grupowe wartością", i że `ClassifyGroupsCommand` nosi **29 grup, nie 402
wiersze**. Przyłożenie tego do istniejącego kontraktu F66 pokazuje, że tak nie można:

```java
public record CategorizationRequest(
        String sessionId, CashFlowId cashFlowId, String detectedLanguage,
        List<TransactionToCategorize> transactions,     // ← TRANSAKCJE, nie grupy
        CategoryStructure categoryStructure) {}
```

A `TransactionToCategorize` ma własne `effectiveMerchant()` i `hasHighConfidenceMerchant()` — czyli
**silnik grupuje sam**, wewnętrznie, po merchancie. Wysłanie 29 syntetycznych „reprezentantów grup"
miałoby dwa skutki, oba złe: grupowanie silnika stałoby się operacją jałową, a
`transactionCount`/`totalAmount` w jego sugestiach wyszłyby jako 1 i kwota jednego wiersza —
**pola wypełnione nieprawdą, których nikt nie czyta**, czyli dokładnie ten kształt defektu, który
F67 usunęło razem z `aiTokensUsed`.

### Trzy wyjścia

| | co | koszt | ocena |
|---|---|---|---|
| **A** | wysyłać reprezentanta grupy | zero zmian | **odrzucone** — fałszywe liczniki w odpowiedzi silnika |
| **B** | `ingestion-classification` czyta wiersze grup z `open[]` przez `ingestion-staging-api` | druga krawędź do kontraktu magazynu wierszy | **rekomendowane** |
| **C** | dodać do kontraktu F66 metodę grupową `suggestForGroups(...)` | zmiana w świeżo ustalonym kontrakcie obcego modułu + jego implementacji | odłożone — do rozważenia, gdy rozmiar żądania zacznie przeszkadzać |

### Dlaczego B jest tanie: silnik **już** odpowiada per grupa

Pomiar na `CategorizationSuggestions` pokazuje, że wyniki są kluczowane dokładnie tak, jak mój
`GroupKey`:

| kształt odpowiedzi silnika | klucz | mój `GroupKey` |
|---|---|---|
| `ContextMapping(pattern, bankCategory, …)` | **(pattern, bankCategory)** | `MERCHANT\|<merchant>\|<bankCategory>\|<type>` |
| `AutoCategorizableSuggestion(classification, …)` | **słowo klasyfikacji** | `CLASS\|BANK_FEE` |
| `BankCategoryFallback(bankCategory, …)` | **bankCategory** | fallback dla grup o słabym merchancie |
| `PatternSuggestion(pattern, …)` | pattern | droga starsza, zostawiona |

Czyli odwzorowanie odpowiedzi na grupy jest naturalne, **pod jednym warunkiem**: komponent
merchanta w `GroupKey` musi być tym samym, czym silnik grupuje, czyli `effectiveMerchant()`
(merchant, a gdy pusty — nazwa). Specyfikacja 01 już tak to definiuje; teraz jest to **wymóg
zgodności z obcym modułem**, a nie wygodna konwencja, i musi mieć test.

### Konsekwencja dla reguły architektonicznej

Reguła „**żaden worker nie zależy od innego workera**" była za ostra — i co ważniejsze, była ostra
w nieistotnym miejscu. Nowe sformułowanie:

> Żaden worker nie zależy od **implementacji** innego workera. Magazyn wierszy wystawia kontrakt
> **tylko do czytania** (`ingestion-staging-api`) i ma **trzech** czytających: `cashflow-writer`
> (import), `ingestion-classification` (wiersze grup otwartych) i `ingestion-conductor` (podgląd
> wierszy pod `GET /api/v2/ingestion/{id}/rows`). Pisać do `ingestion_rows` może wyłącznie
> `ingestion-staging`, i tylko w odpowiedzi na komendę sagi — czego pilnuje osobna reguła, bo
> kontrakt R nie ma metody zapisującej.

Co to zmienia w plikach: **06** (classification zyskuje zależność `ingestion-staging-api`), **09**
(reguła `noWorkerDependsOnAnotherWorker` przeformułowana), **01** (test zgodności `GroupKey` z
`ContextMapping`).

## 5. Granica napięta, ale uzasadniona: `CategorySnapshot` vs `CashFlowSkeletonView`

Pomiar użycia skeletonu w dzisiejszym kodzie, per sekcja:

| metoda | `staging` | `importing` |
|---|---|---|
| `findCategoryIgnoringCase` | 6 | — |
| `allCategoryNames` | 4 | — |
| `parentOf` | 4 | — |
| `importWindow` | 3 | — |
| `owner` | 2 | — |
| `isOpen` | 1 | — |
| `startPeriod` | 1 | — |
| `typedCategoryNames` | — | 1 |
| `isClosed` | — | 1 |

Rozkład po nowych granicach:

- **`category-resolution` potrzebuje tylko trzech metod kategorii** (`findIgnoringCase`, `parentOf`,
  `contains`/typed) — więc `CategorySnapshot` jako osobny typ protokołu jest uzasadniony pomiarem, a
  nie estetyką: 3 z 9 metod, zero pól o cyklu życia cash flow.
- **`staging` musi zostać przy `cashflow-api`** i nie ma tu nic do uratowania: używa
  `importWindow()` oraz `startPeriod()` w komunikacie błędu („Month %s is outside the CashFlow
  forecast range (start: %s)"). Okno importu to reguła cashflow i **nie wolno jej kopiować do
  protokołu ingestion**.
- **`skeleton.owner()` w stagingu jest zbędne.** Conductor zna ownera z JWT i tak, i wysyła go w
  `ValidateRowsCommand`. Dwa wywołania znikają, a `OwnedAccountRegistry` przestaje zależeć od
  skeletonu.
- **`cashflow-writer` potrzebuje `isInSetup`/`isClosed`** — jedno pytanie przed kompensacją. To jest
  powód, dla którego nie nasłuchuje `HistoricalImportAttestedEvent`: jedno synchroniczne pytanie do
  źródła prawdy bije nasłuch, który może się spóźnić.

## 6. Granica niewidoczna z rysunku: `ErrorCode` siedzi w shared-kernel

`ErrorHttpHandler` jest `@RestControllerAdvice` w **`vidulum-shared-kernel`** i mapuje
`BusinessException.getErrorCode()`. Czyli każdy nowy błąd rodziny ingestion wymaga stałej w
`com.multi.vidulum.common.error.ErrorCode` — **w shared-kernelu**. A to, z reguł CLAUDE.md, znaczy:

> `vidulum-shared-kernel` → **pełne `./mvnw clean test`**, ~7 min, 21 modułów — bez wyjątków.

Obejścia nie ma i nie należy go szukać: własny enum w rodzinie oznaczałby wyjątek, którego
`ErrorHttpHandler` nie zna, czyli **500 zamiast 404** — dokładnie ta wpadka, którą CLAUDE.md opisuje
przy zagnieżdżonym `RuntimeException` w porcie ingestion.

**Wniosek operacyjny:** osiem brakujących kodów dopisać **jednym commitem na początku P0**, nie po
jednym na moduł:

```
INGESTION_SESSION_NOT_FOUND          404   (jest INGESTION_STAGING_NOT_FOUND — nowa nazwa)
INGESTION_SESSION_ALREADY_ACTIVE     409   druga sesja dla cash flow w SETUP (§25)
INGESTION_DECISION_CLOSED            409   odpowiedź na decyzję już rozstrzygniętą
INGESTION_DECISION_NOT_FOUND         404
INGESTION_STEP_FAILED                500   krok padł po maxAttempts
INGESTION_COMPENSATION_NOT_ALLOWED   400   cash flow po atestacji
INGESTION_PLAN_INVALID               500   dziecko przed rodzicem w planie
INGESTION_SESSION_EXPIRED            410   sesja wygasła (dziś stan martwy)
```

## 7. Brak presji wejściowej — i co z tego wynika

Grep po całym repo: **żaden produkcyjny plik poza `bank-data-ingestion` nie importuje jego typów.**
Jedyne odwołania to 9 plików testowych w `vidulum-cashflow-tests` i `ModuleBoundariesArchTest`.

Trzy wnioski:

1. **`ingestion-conductor-api` słusznie nie ma `ModuleApi`** (korekta z pliku 03 potwierdzona
   pomiarem): nikt dziś nie pyta ingestion o nic, więc kontrakt wejściowy nie miałby konsumenta.
2. **Nowa rodzina może żyć obok starej bez kolizji typów** — nie ma wspólnych nazw klas w jednym
   pakiecie, nie ma wspólnych kolekcji (`ingestion_*` vs `staging_*`/`import_jobs`), nie ma
   wspólnych topiców (`ingestion.*` vs `bank_data_ingestion`).
3. **Wyłączenie starego modułu to usunięcie zależności, nie migracja** — zgodnie z Development
   Status, bez użytkowników produkcyjnych i bez skryptów migracyjnych.

## 8. Reaktor, bootstrap, cleanery

| rzecz | stan | co trzeba |
|---|---|---|
| kolejność reaktora | `vidulum-app-ingestion` zależy od `cashflow-api`, `user-financial-profile-api`, `bank-data-ai-categorization-api` | agregator **po** `vidulum-app-cashflow`, przed `vidulum-cashflow-tests` |
| skanowanie komponentów | `@SpringBootApplication` na `com.multi.vidulum` — bez `scanBasePackages` | **nic** — pakiety `ingestion_*` zostaną znalezione, o ile są na classpathie |
| `vidulum-app/pom.xml` | wymienia 15 implementacji jawnie | **+6 zależności compile**; bez nich moduły nie wstaną, choć się zbudują |
| `BootstrapWiresEveryModuleTest` | asercje per kontrakt i per kontroler | + asercja na `IngestionRestController` i (gdy powstanie) na kontrakt conductora |
| `DataCleaner` | zbierane przez `List<DataCleaner>` w `CommandLineRunner` | **+4 cleanery**; `remove()`, nie `dropCollection` |
| indeksy | `spring.data.mongodb.auto-index-creation` już włączone w `vidulum-app` | nic — ale test w 09 i tak sprawdza bazę, nie adnotację |

## 9. `/api/v2` — potwierdzone, z dowodem

Założenie o prefiksie jest **trafne i tanie**, i to jest sprawdzone, nie przyjęte:

| co | stan faktyczny | wniosek |
|---|---|---|
| kolizje | `api/v2` nie występuje **nigdzie** w repo (ani w kodzie, ani w yml) | wolne |
| autoryzacja | `SecurityConfiguration`: whitelist to tylko `/api/v1/auth/**` i `/actuator/health`, dalej `anyRequest().authenticated()` | `/api/v2/**` jest **domyślnie chronione JWT bez żadnej zmiany** |
| role | reguły `hasAnyAuthority` dotyczą wyłącznie `/api/v1/management/**` | v2 nie wymaga nowych uprawnień |
| CORS | `source.registerCorsConfiguration("/**", …)` | obejmuje v2; cztery dozwolone originy bez zmian |
| obsługa błędów | `ErrorHttpHandler` to globalny `@RestControllerAdvice` | nowe `BusinessException` mapują się same — pod warunkiem §6 |
| multipart | `max-file-size: 10MB`, `max-request-size: 10MB` | plik 402-wierszowy ≈ 160 KB; limit starcza na ~25 000 wierszy — nie ruszać |
| stare API | `/api/v1/bank-data-ingestion/**` żyje dalej | **to jest warunek nietykalności adaptera**: ma zabity na stałe `/api/v1/bank-data-ingestion/cf={id}/upload` |

**Najważniejsza konsekwencja:** `/api/v2` **nie wymaga żadnej zmiany w `vidulum-shared-kernel`** —
a każda taka zmiana kosztowałaby pełny przebieg reaktora. Prefiks jest darmowy; nowe `ErrorCode`
(§6) nie są, i to jedyne miejsce, gdzie ta rodzina sięga do shared-kernela.

### Kształt adresów — jedna świadoma zmiana konwencji

| dziś | v2 |
|---|---|
| `/api/v1/bank-data-ingestion/cf={cashFlowId}/staging/{sessionId}/…` | `/api/v2/ingestion/{sessionId}/…` |
| cash flow w ścieżce, sesja zagnieżdżona | **sesja jest zasobem**, cash flow jest jej atrybutem |
| lista: `GET …/cf={id}/staging` | `GET /api/v2/ingestion?cashFlowId=CF10000001` |

Uzasadnienie: sesja ma własny, globalnie unikalny id, więc zagnieżdżanie jej pod cash flow dodaje
do adresu informację, którą serwer i tak ma z dokumentu — a pięć dzisiejszych poziomów ścieżki jest
tym, co czyni dzisiejsze URL-e trudnymi do zapamiętania. Filtrowanie listy query paramem jest
zgodne z tym, co w tym projekcie już jest: `GET /cash-flow?userId={userId}`.
Konwencja `cf={id}` **zostaje w v1** i nie jest przenoszona.

## 10. Granice ostateczne — jedna tabela

| artefakt | wolno mu zależeć od | wolno mu **wołać** | wolno mu **pisać** |
|---|---|---|---|
| `ingestion-canonical` | shared-kernel, cashflow-api (`Type`) | — | — |
| `ingestion-intake` | canonical | — | — |
| `ingestion-conductor-api` | shared-kernel, canonical, cashflow-api (`Type`) | — | — |
| `ingestion-conductor` | conductor-api, canonical, intake, **cashflow-api**, **staging-api (R)** | `exists`, `skeletonOf`, `rowsOf` | `ingestion_sessions` |
| `ingestion-staging-api` | shared-kernel, canonical, conductor-api | — | — |
| `ingestion-staging` | staging-api, conductor-api, canonical, **cashflow-api**, **user-financial-profile-api** | `skeletonOf`, `monthStatusesOf`, `alreadyImported`, konta własne | `ingestion_rows` |
| `ingestion-classification` | conductor-api, canonical, **staging-api (R)**, **ai-categorization-api** | `suggestFor` | `ingestion_classifications` |
| `ingestion-category-resolution` | conductor-api, canonical, cashflow-api (**tylko typy**), **ai-categorization-api** | `matchLearned`, `learn`, `forget` | `ingestion_category_mappings` |
| `ingestion-cashflow-writer` | conductor-api, canonical, **staging-api (R)**, **cashflow-api** | `createCategory`, `importHistorical`, `rollbackImport`, `skeletonOf` | **nic** |
| `vidulum-ingestion-tests` | wszystko powyżej + implementacje (test scope) | — | — |

Trzy kolumny zamiast jednej, bo „zależeć" i „wołać" to nie to samo: `category-resolution` ma
`cashflow-api` w pomie **wyłącznie dla typów** `Type` i `CategoryName`, i reguła w 09 pilnuje, że nie
wywołuje z niego ani jednej metody. Drzewo kategorii przychodzi w komendzie.

## 11. Poprawki do specyfikacji, które wynikły z tego badania

| plik | poprawka |
|---|---|
| **06** | + zależność `ingestion-staging-api` (czytanie wierszy grup z `open[]`); + testy odwzorowania `ContextMapping` → `GroupKey` |
| **09** | reguła `noWorkerDependsOnAnotherWorker` → „nie zależy od **implementacji**; magazyn wierszy ma kontrakt R, z trzema czytającymi i zerem piszących poza stagingiem" |
| **04** | + zależność `ingestion-staging-api` (R) i endpoint `GET /api/v2/ingestion/{id}/rows` — podgląd wierszy musi skądś brać dane |
| **01** | `GroupKey` — komponent merchanta **musi** być `effectiveMerchant()`, bo tym grupuje silnik; + test zgodności |
| **05** | `OwnedAccountRegistry` nie potrzebuje `skeleton.owner()` — owner przychodzi w komendzie |
| **07** | `forget` przestaje być martwą metodą kontraktu; krok `UNLEARN` jest jej pierwszym konsumentem |
| **00** | + wiersz: osiem nowych `ErrorCode` jednym commitem w P0 (pełny reaktor raz, nie osiem razy) |
