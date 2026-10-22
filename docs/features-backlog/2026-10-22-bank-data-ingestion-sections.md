# `bank-data-ingestion` na sekcje — i co naprawdę blokuje podmianę klasyfikatora

**Status:** analiza, nic nie zmienione · **Data:** 2026-10-22 · **Po:** F66
([wydzielenie kategoryzacji AI](2026-10-22-ai-categorization-module-split.md))

**Pytanie, na które odpowiada:** czy podzielić ingestion na sekcję wykrywania danych i sekcję
zarządzania sesją — i jak sprawić, żeby moduł był **mało zależny od AI** i dało się wstrzelić inny
klasyfikator, na przykład gotowy statyczny.

## Co zostało w module po F66

104 plików, ~11 100 linii. Trzynaście komend, pięć zapytań, cztery encje. Sekcje **istnieją** i są
dość czyste — widać je po tym, kto jest właścicielem danych:

| sekcja | komendy / zapytania | linie | własne dane |
|---|---|---|---|
| **wejście danych** | `upload_csv` + `CsvParserService` | ~440 | **żadnych** — plik → wiersze kanoniczne |
| **sesja stagingowa** | `stage_transactions` 925, `revalidate_staging` 485, `force_uncategorized` 327, `delete_staging_session`, `get_staging_preview`, `list_staging_sessions` | ~1 900 | `StagingSessionEntity`, `StagedTransactionEntity` |
| **mapowania kategorii** | `configure_mapping` 173, `delete_mapping`, `delete_all_mappings`, `get_mappings` | ~300 | `CategoryMappingEntity` |
| **wykonanie importu** | `start_import` 603, `finalize_import` 174, `rollback_import` 159, `get_import_progress`, `list_import_jobs` | ~1 000 | `ImportJobEntity` |
| **orkiestracja klasyfikacji** | `ai_categorize` 164, `accept_ai_suggestions` 393 | ~560 | — woła kontrakt |

## Przeszkodą nie jest podział, a słownictwo

Po F66 ingestion pyta już **neutralnego** kontraktu `TransactionCategorizationApi`. Statyczny
klasyfikator dałoby się wstawić **dziś**, bez żadnego podziału, i funkcjonalnie by działał. Tylko
że technologia wyciekła w trzy miejsca, których kontrakt nie obejmuje:

```java
// StagingSessionEntity — TRWAŁY SCHEMAT DOKUMENTU
private AiCategorizationStatus aiCategorizationStatus;   // + startedAt, completedAt
private Integer aiTokensUsed;                            // statyczny klasyfikator nie ma tokenów
private String aiEstimatedCost;                          // ani kosztu

// StagingSessionStatus — MASZYNA STANÓW
AI_SUGGESTIONS_READY,                                    // stan nazwany po dostawcy

// HTTP — POWIERZCHNIA PUBLICZNA
POST /staging/{id}/ai-categorize
POST /staging/{id}/accept-ai
```

Plus 30 wzmianek w `BankDataIngestionDto` i 54 w kontrolerze.

**Skutek:** statyczny klasyfikator by działał, a każdy log, status i adres **kłamałby**. To ten sam
zarzut, który postawiono kontraktowi przy ocenie F66 (`AiCost.tokensUsed`), z tą różnicą, że tu
siedzi w **trwałym schemacie dokumentu**, nie w typie przelotowym.

## Trzy warianty

### 1. Przemianowanie na zdolność, nie na dostawcę — *rekomendacja, najpierw*

Żadna granica się nie rusza; zmienia się nazewnictwo z „kto to robi" na „co to robi".

| dziś | po |
|---|---|
| `app/commands/ai_categorize/` | `app/commands/classify/` |
| `app/commands/accept_ai_suggestions/` | `app/commands/accept_classification/` |
| `AiCategorizationStatus` | `ClassificationStatus` |
| `aiTokensUsed`, `aiEstimatedCost` | **usunąć z sesji**, albo jedno neutralne `classificationCost` |
| `AI_SUGGESTIONS_READY` | `SUGGESTIONS_READY` |
| `POST /ai-categorize`, `/accept-ai` | `POST /classify`, `/accept-suggestions` |

Koszt: ~100 linii dotknięć w 8 plikach. Zmiana **łamiąca**: inny schemat dokumentu i inne URL-e —
na co Development Status z CLAUDE.md daje wprost zgodę (brak użytkowników produkcyjnych, w razie
wątpliwości czyścimy dane, żadnych migracji).

Efekt uboczny, który załatwia zaległość z F66: skoro sesja nie ma pola na tokeny, **telemetria
przestaje być obowiązkiem kontraktu**.

### 2. Sekcje jako pakiety w jednym module — *drugi krok*

```
bank_data_ingestion/
├── intake/          # plik → wiersze kanoniczne; zero storage
├── staging/         # sesja + wiersze: maszyna stanów
├── mappings/        # mapowania kategorii
├── importing/       # zadanie importu, rollback, finalizacja
└── classification/  # orkiestracja: woła kontrakt, stempluje sesję
```

Każda sekcja ze swoim `app` / `domain` / `infrastructure` i **testem architektonicznym**
zabraniającym sięgania między sekcjami inaczej niż przez zadeklarowane punkty — tak jak
`ModuleBoundariesArchTest` pilnuje modułów (i jak w F66 złapał implementację napisaną `public`).

To przenosiny plików, nie przepisywanie: ~100 deklaracji pakietów. Wartość: `classification/` staje
się **jednym katalogiem**, który wymienia się razem z implementacją kontraktu, zamiast być
rozsmarowanym po `commands/`, `domain/` i encji sesji.


#### Rozwinięcie: co dokładnie trafia do której sekcji

Przypisanie **wszystkich** 104 plików, bo bez tego powyższy rysunek jest tylko rysunkiem.

##### `intake/` — plik staje się wierszami kanonicznymi

Zero storage, zero stanu, czyste funkcje.

| co | pliki |
|---|---|
| wejście | `UploadCsvCommand`, `UploadCsvCommandHandler`, `UploadCsvResult` |
| parser | `CsvParserService` |
| endpoint | `POST /upload` |

`UploadCsvCommandHandler` wstrzykuje dziś **`StageTransactionsCommandHandler` wprost** (nie przez
gateway) i deleguje. Po podziale to jedyna krawędź `intake → staging` i powinna iść przez
`CommandGateway`, jak wszystko inne w tym projekcie.

##### `canonical/` — jedyny wspólny słownik (sekcja, której nie było na pierwszym rysunku)

Wiersza kanonicznego **nie da się** przypisać do jednej sekcji: `intake` go produkuje, `staging`
przechowuje, `classification` czyta. To kontrakt **między** sekcjami:

`BankCsvRow` · `OriginalTransactionData` · `PaymentMethod` · `TransactionClassification`

Ten sam argument, który dał `ImportWindow` miejsce w `cashflow-api`: wartość potrzebna dwóm stronom
nie należy do żadnej z nich.

##### `staging/` — sesja i wiersze: maszyna stanów

Największa sekcja (~1 900 linii), właściciel `StagingSessionEntity` i `StagedTransactionEntity`.

| co | pliki |
|---|---|
| komendy | `stage_transactions` (925), `revalidate_staging` (485), `force_uncategorized` (327), `delete_staging_session` |
| zapytania | `get_staging_preview`, `list_staging_sessions` |
| domena | `StagedTransaction`, `StagedTransactionId`, `StagedTransactionRepository`, `MappedTransactionData`, `TransactionValidation`, `ValidationStatus`, `StagingSessionId`, `StagingSessionStatus`, `UnmappedCategoriesException`, `StagingSessionNotFoundException`, `StagingSessionNotReadyException` |
| infrastruktura | `StagingSessionEntity`, `StagedTransactionEntity`, oba repozytoria Mongo, `StagedTransactionRepositoryImpl` |
| współpracownik | `OwnedAccountRegistry` + `UserFinancialProfileClientConfig` — wykrywanie przelewów własnych, jedyny wołający |
| endpointy | `GET/POST /staging`, `GET/DELETE /staging/{id}`, `/revalidate`, `/force-uncategorized` |

##### `mappings/` — mapowania kategorii bankowych

Najmniejsza i najczystsza (~300 linii). Nikogo nie potrzebuje; czyta ją `staging`, zapisuje
`classification`.

| co | pliki |
|---|---|
| komendy | `configure_mapping`, `delete_mapping`, `delete_all_mappings` |
| zapytania | `get_mappings` |
| domena | `CategoryMapping`, `MappingId`, `MappingAction`, `CategoryMappingRepository`, `CategoryMappingNotFoundException` |
| infrastruktura | `CategoryMappingEntity`, repozytorium Mongo, `CategoryMappingRepositoryImpl` |
| endpointy | `POST/GET /mappings`, `DELETE /mappings`, `DELETE /mappings/{id}` |

##### `importing/` — zadanie importu, rollback, finalizacja

Właściciel `ImportJobEntity` **i wszystkich zdarzeń modułu**: sprawdzone — wszystkie sześć
(`ImportJobStarted`, `ImportProgress`, `ImportJobCompleted`, `Failed`, `RolledBack`, `Finalized`)
emitują wyłącznie trzy handlery tej sekcji.

| co | pliki |
|---|---|
| komendy | `start_import` (603), `finalize_import`, `rollback_import` |
| zapytania | `get_import_progress`, `list_import_jobs` |
| domena | `ImportJob`, `ImportJobId`, `ImportJobStatus`, `ImportPhase`, `PhaseStatus`, `ImportJobRepository`, `ImportJobAlreadyExistsException`, `ImportJobNotFoundException`, `ImportJobNotCompletedException`, `RollbackNotAllowedException` |
| infrastruktura | `ImportJobEntity`, repozytorium Mongo, `ImportJobRepositoryImpl`, `BankDataIngestionKafkaConfig` |
| zdarzenia | `BankDataIngestionEvent` (sealed), `BankDataIngestionEventEmitter` |
| endpointy | `POST /import`, `GET /import`, `GET /import/{jobId}`, `/rollback`, `/finalize` |

##### `classification/` — orkiestracja: woła kontrakt, stempluje sesję

Sekcja, dla której cała reorganizacja ma sens: ~560 linii, i ma być **liściem**.

| co | pliki |
|---|---|
| komendy | `classify` (dziś `ai_categorize`), `accept_classification` (dziś `accept_ai_suggestions`) |
| domena | `ClassificationStatus` (dziś `AiCategorizationStatus`) |
| zależność | `TransactionCategorizationApi` — kontrakt, nie implementacja |
| endpointy | `POST /staging/{id}/classify`, `POST /staging/{id}/accept-suggestions` |

#### Kierunki zależności — treść testu architektonicznego

```
canonical      ←── intake, staging, classification
mappings       ←── staging, classification
staging        ←── importing, classification
classification ←── NIKT          ← własność, dla której to się robi
```

Trzy reguły, każda z jednozdaniowym „dlaczego":

1. **`canonical` nie zależy od niczego w module** — słownik, który zna swoich użytkowników, nie jest
   słownikiem.
2. **`intake` i `mappings` nie zależą od niczego poza `canonical`** — liście zostają liśćmi.
3. **Nic nie zależy od `classification`** — to jest dokładnie to, co pozwala wymienić klasyfikator:
   cała sekcja plus implementacja kontraktu wychodzi, i wchodzi inna.

**Reguła 3 jest dziś złamana w jednym miejscu i tylko w jednym:**
`ForceUncategorizedCommandHandler:260` czyta `sessionEntity.getAiCategorizationStatus()` do logu.
Jedna linia — do wycięcia albo do przeniesienia na neutralne pole sesji.

#### Największa praktyczna wygrana: rozpad kontrolera i DTO

| plik | dziś | po podziale |
|---|---|---|
| `BankDataIngestionRestController` | **1 195 linii, 18 endpointów** | 5 kontrolerów po ~150–300, każdy w swojej sekcji |
| `BankDataIngestionDto` | **867 linii** | 5 plików DTO wzdłuż tych samych granic |

To **2 062 linie w dwóch plikach** — 19% modułu — i właśnie one sprawiają, że moduł *wygląda* na
niepodzielny, bo każdy endpoint każdej sekcji przechodzi przez jedną klasę. Po rozdzieleniu
`/classify` i `/accept-suggestions` to jeden kontroler w `classification/`, wychodzący razem z
sekcją.

#### Decyzja do podjęcia: `DataCleaner`

Dziś jeden `BankDataIngestionDataCleaner` czyści cztery kolekcje. Po podziale trzy sekcje mają własne
dane (`staging` dwie kolekcje, `mappings` jedną, `importing` jedną), a CLAUDE.md mówi: **jeden
cleaner na poddomenę**, bo poddomena będąca właścicielem encji zauważa nową.

Rekomendacja: **trzy cleanery**, po jednym w sekcji — to samo rozumowanie, które przy F66 dało
`AiCategorizationDataCleaner`. Koszt: dwa pliki i wpis w tabeli CLAUDE.md.

#### Rozmiar roboty

Przenosiny ~100 deklaracji pakietów (mechaniczne, kompilator pilnuje) · rozdzielenie dwóch dużych
plików (realna robota: trzeba rozstrzygnąć wspólne DTO) · jedna linia do wycięcia w
`force_uncategorized` · trzy cleanery · jeden test architektoniczny.

**Co to kupuje:** po podziale i przemianowaniu wstawienie statycznego klasyfikatora to nowy moduł z
czterema metodami, właściwość `vidulum.categorization.engine=static` i **zero zmian** w `intake`,
`staging`, `mappings`, `importing` — bo żadna z nich nie zna `classification`. Bez podziału ten
ostatni punkt brzmi: „zero zmian w 104 plikach, w które trzeba zajrzeć, żeby się upewnić".

### 3. Podział na moduły Mavena — *odradzane*

`bank-data-intake`, `bank-data-staging`, `bank-data-import` — trzy konkretne powody przeciw:

- **Dane są wspólne.** `start_import` czyta staged transactions **i** sesję; `revalidate` pisze
  jedno i drugie; `stage_transactions` czyta mapowania. Moduły wymagałyby kontraktu na sesję — dużo
  powierzchni za mało korzyści.
- **Handlery wołają handlery wprost.** `AcceptAiSuggestionsCommandHandler` wstrzykuje
  `ConfigureCategoryMappingCommandHandler` **i** `RevalidateStagingCommandHandler`. To trzeba
  rozplątać niezależnie od modułów, i to jest osobne zadanie.
- **Precedens z CLAUDE.md:** „zmierz, jak wartość przechodzi granicę, zanim dasz jej moduł". F66
  zadziałało, bo silnik miał **jednego** wołającego i **własne** dane. Tutaj żaden z tych warunków
  nie jest spełniony.

## Kolejność, jeśli celem jest „wstrzelić statyczny klasyfikator"

1. **Przemianowanie (wariant 1)** — po nim ingestion jest neutralny *nazwowo*, nie tylko
   strukturalnie.
2. **Test kontraktu w `bank-data-ai-categorization-api`** — zaległość z F66: abstrakcyjna klasa
   testowa, którą każda implementacja odpala na swoim beanie. Bez niej nowy klasyfikator
   **skompiluje się**, ale nikt nie sprawdzi, czy zachowuje się tak, jak ingestion potrzebuje
   (granica słowa, rozdział INFLOW/OUTFLOW, najpewniejszy wygrywa, `learn` aktualizuje zamiast
   duplikować).
3. **Przełącznik beanów** `vidulum.categorization.engine=ai|static` plus asercja w
   `BootstrapWiresEveryModuleTest`, że bean jest dokładnie jeden — wzorzec, który cashflow ma dla
   transportów. Dziś dwie implementacje na classpathie dają konflikt beanów.
4. **Sekcje jako pakiety (wariant 2)** — dopiero teraz, bo wobec punktów 1–3 to porządkowanie
   struktury, nie zmiana możliwości.

**Po punktach 1–3** podmiana klasyfikatora znaczy: nowy moduł implementujący cztery metody,
przechodzący test kontraktu, wskazany właściwością. **Bez punktu 1** zostaje statyczny klasyfikator
raportujący `aiTokensUsed = 0` pod adresem `/ai-categorize`.

## Rozstrzygnięte: URL-e zostają

Przemianowanie **nie obejmuje adresów**. `POST /staging/{id}/ai-categorize` i `/accept-ai` zostają
takie, jakie są — decyzja właściciela kodu z 2026-10-22.

Konsekwencja, którą trzeba znać: po przemianowaniu wnętrza adres przez chwilę nie będzie zgadzał się
z nazwą komendy, którą wywołuje (`/ai-categorize` → `ClassifyCommand`). To jest **świadomy** koszt
trzymania zewnętrznej powierzchni w miejscu, a nie przeoczenie - i dlatego zapisany tutaj oraz w
javadocu kontrolera, kiedy ta zmiana nastąpi. Wszystko inne (pakiety, nazwy klas, pola dokumentu,
stany sesji) jest wewnętrzne i kosztuje tylko rekompilację.
