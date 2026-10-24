# Sesja jako monitor procesu ingestion — i gdzie dziś ten monitor kłamie

**Data:** 2026-10-23 · **Status:** analiza, nic nie zmienione · **Zadanie:** F68 (zależne od F67)

Pytanie, z którego to wyszło: *czy użyć Kafki i mieć topic, na którym staging dostawałby
powiadomienia, co inni aktorzy procesu zrobili — bo sam staging chyba nie jest tak istotny jak
sesja, która monitorowałaby cały proces; a co jak coś się nie uda?*

Odpowiedź w jednym zdaniu: **topic już istnieje i nikt go nie czyta, sesja już jest monitorem — ale
tylko pierwszej połowy procesu — a porażka jest obsłużona w sześciu miejscach, z których cztery
tracą ślad.** Poniżej pomiary.

## 1. Pełny przepływ, zmierzony z kodu

| faza | kto wykonuje | co zapisuje | rekord procesu |
|---|---|---|---|
| transformacja CSV przez AI | `bank-data-adapter` (inny moduł, przez HTTP) | `AiCsvTransformationDocument` | — |
| utworzenie sesji + parsowanie | `staging/upload_csv` → `stage_transactions`, woła `intake/CsvParserService` | `StagingSessionEntity`, `StagedTransactionEntity` | **sesja** |
| wykrycie przelewów własnych | `staging` → **HTTP** do `UserFinancialProfileApi` | pole na wierszu | sesja |
| klasyfikacja | `classification/classify` → kontrakt AI | `classificationStatus` + 3× `save` **obcej encji** | sesja |
| przyjęcie sugestii | `classification/accept_classification` → gateway → `mappings`, `staging` | `CategoryMappingEntity`, przeliczona sesja | sesja |
| import | `importing/start_import` | `ImportJobEntity` + N× `cashFlowApi.importHistorical` | **zadanie importu** |
| rollback / finalize | `importing` | status zadania; kasuje wiersze staged i mapowania | zadanie importu |

Dwie rzeczy, które ten rysunek mówi, a których nie widać z nazw sekcji:

- **Jedyny klient HTTP w całym module należy do stagingu** (`UserFinancialProfileClientConfig`),
  i to on, nie żaden kontroler, jest zewnętrznym wywołaniem w środku fazy parsowania.
- **Import jedzie w wątku żądania HTTP.** W module nie ma ani jednego `@Async`,
  `CompletableFuture` ani `TaskExecutor`. 402 wiersze to 402 zapisy agregatu cash flow w jednym
  żądaniu.

## 2. Rekordów procesu jest dwa, nie jeden

Sesja **już jest** monitorem: 25 pól, 9 metod cyklu życia, `@Version`, a w tym `importJobId`,
`importStartedAt`, `importCompletedAt`, `importedTransactionsCount`. Monitoruje jednak tylko fazy
przed importem. Od `startImport` pałeczkę przejmuje `ImportJob`, który ma własny
`stagingSessionId`, `status`, `timestamps`, `input`, `progress`, `result`, **`rollbackData`** i
`summary`.

Cała patologia siedzi w przekazaniu pałeczki:

1. **`failImport()` robi `this.importJobId = null`** z komentarzem *„Clear failed job reference"*.
   Czyli dokładnie w momencie porażki sesja zrywa ślad do zadania, które wie, co się stało. Zadanie
   żyje dalej ze statusem `FAILED` — tylko nic już na nie nie wskazuje.
2. **Rollback nie dotyka sesji ani razu.** Zero referencji do `StagingSession` w
   `RollbackImportJobCommandHandler`. Po wycofaniu importu sesja nadal mówi `COMPLETED`, a cash
   flow jest pusty.
3. **Sesja ma TTL w Mongo** (`@Indexed(expireAfter = "0s")` na `expiresAt`), `ImportJobEntity`
   **nie ma żadnego**. Po 24 h monitor pierwszej połowy procesu znika, a w zadaniu zostaje
   `stagingSessionId` wskazujący na nieistniejący dokument.
4. **`StagingSessionStatus.EXPIRED` nigdy nie jest nikomu przypisywany** — jest tylko czytany jako
   stan terminalny w `recalculateStatus`. TTL kasuje dokument, zamiast go oznaczyć. Martwy stan,
   ta sama klasa defektu co skasowane w F67 pola `aiTokensUsed` i `aiEstimatedCost`.

Do tego trzy równoległe źródła prawdy o tym samym procesie: `status` sesji, `classificationStatus`
sesji i `status` zadania. Pierwsze dwa mogą się rozjechać bez żadnego ostrzeżenia — patrz punkt 4
tabeli porażek.

## 3. Kafka: temat jest, konsumenta nie ma

`BankDataIngestionEventEmitter` emituje **sześć** zdarzeń na topic `bank_data_ingestion`,
kluczowany `cashFlowId`, jedna partycja:

`ImportJobStartedEvent` · `ImportProgressEvent` · `ImportJobCompletedEvent` ·
`ImportJobFailedEvent` · `ImportJobRolledBackEvent` · `ImportJobFinalizedEvent`

**W całym reaktorze nie ma ani jednego `@KafkaListener` na ten temat.** Trzy istniejące czytają
`cash_flow`, `quotes` i `user_created`. Jedyny konsument `bank_data_ingestion` to
`websocket-gateway`, który **nie jest modułem reaktora** — jego dołączenie to dopiero zadanie F4.

Czyli ingestion **już dziś pisze telemetrię procesu w próżnię**. `ImportProgressEvent` leci co 10
transakcji z komentarzem *„Can be used for real-time UI updates via WebSocket"* i nikt go nie
odbiera. To ten sam kształt co pola kosztu AI: zapisywane, nieczytane.

### Obserwacja kontra kontrola — to dwie różne rzeczy

| | obserwacja (co się stało) | **kontrola** (zmiana stanu procesu) |
|---|---|---|
| Kafka | **tak, i to pilnie** — zdarzenia już są, brakuje konsumenta w reaktorze | **nie** |
| dlaczego | postęp, zakończenie, porażka i rollback to fakty dla UI, alertów i audytu; nikogo nie blokują | odpowiedź HTTP **musi** zawierać nowy stan: `accept-ai` zwraca podsumowanie rewalidacji, `classify` zwraca sugestie i status sesji |

Gdyby powiadomienia sterowały stanem sesji, odpowiedź endpointu przestałaby się zgadzać z sesją do
czasu dojścia zdarzenia. Trzy dodatkowe przeszkody po stronie kontroli:

- **Nie ma DLQ** — `docs/features-backlog/TODO-kafka-dead-letter-queue.md` wciąż otwarte.
- CLAUDE.md opisuje już raz przeżyte „zdarzenie porzucone po dziewięciu próbach, a każdy zapis
  2xx" — po cichu zabiło projekcję prognozy.
- Sesja ma `@Version`, więc konflikt dwóch aktorów jest dziś uczciwym 409. Przez kolejkę
  zamieniłby się w cichego zwycięzcę.

**Jest natomiast jedno miejsce, gdzie asynchroniczność jest naprawdę potrzebna, i nie jest to
powiadamianie sesji:** sam import. Projekt był pisany pod import w tle (stąd zdarzenia postępu),
którego nigdy nie dorobiono.

## 4. Co się dzieje przy porażce — stan faktyczny

| porażka | co się dzieje dziś | luka |
|---|---|---|
| pojedynczy wiersz nie wchodzi | `catch` → `recordFailedTransaction`, `processed++`, **pętla leci dalej**; zadanie kończy `COMPLETED` | sesja też dostaje `COMPLETED`, tylko `importedTransactionsCount` < `total`. **Nic nie emituje porażki** i nic nie mówi UI „15 wierszy nie weszło" |
| wyjątek całej fazy importu | `importJob.fail(...)`, `sessionEntity.failImport()`, `ImportJobFailedEvent` | sesja wraca do `READY_FOR_IMPORT` (świadomie, do ponowienia), ale **gubi `importJobId`** — ślad do przyczyny |
| klasyfikacja padnie | `failClassification()` → `ClassificationStatus.FAILED` | `status` sesji zostaje na `HAS_UNMAPPED_CATEGORIES`: **dwa źródła prawdy**, a `StagingSessionStatus` **nie ma `FAILED`** |
| crash JVM w trakcie importu | nic — nie ma transakcji; N wierszy jest w cash flow, zadanie wisi `PROCESSING`, sesja `IMPORTING` | **żadnego odzysku**; po 24 h sesja znika, a jedynym świadkiem jest `rollbackData` zadania, którego nikt automatycznie nie czyta |
| wycofanie importu | `cashFlowApi.rollbackImport`, status `ROLLED_BACK`, zdarzenie | **sesja nietknięta** — mówi `COMPLETED` nad pustym cash flow |
| kategoria już istnieje | `catch (CategoryAlreadyExistsException)` → pominięcie | poprawne |

Co działa dobrze i warto to powiedzieć: wycofanie jest bronione czterema warunkami (`canRollback`,
`isRollbackDeadlinePassed` z okna 1 h, zgodność cash flow, `isInSetup()` — po atestacji nie wolno),
a idempotencja importu stoi na `bankTransactionId`, więc ponowienie nie dubluje wierszy.

## 5. Projekt: sesja zostaje jedynym monitorem, ale przestaje być encją, do której piszą obcy

1. **Jeden stan procesu, z `FAILED`.** Zamiast trzech równoległych — faza sesji
   (`PARSED → CLASSIFIED → MAPPED → IMPORTING → IMPORTED | FAILED`), a przy porażce
   **`importJobId` zostaje**, nie jest kasowany, plus `failureReason`.
2. **Obcy aktorzy wysyłają komendy, nie piszą encji.** Sześć wywołań cyklu życia
   (`startClassification`, `completeClassification`, `failClassification` z `classification`;
   `startImport`, `completeImport`, `failImport` z `importing`) zamienia się w komendy obsługiwane
   w stagingu. To jednocześnie zdejmuje blokadę wydzielenia stagingu jako modułu Maven: granica
   powstaje sama, bez `staging-api` wystawiającego encję Mongo i repozytorium Spring Data.
3. **Kafka dla obserwacji, z pierwszym konsumentem w reaktorze.** Do istniejących sześciu zdarzeń
   dochodzą brakujące: `StagingSessionCreated`, `ClassificationFailed`,
   `TransactionsPartiallyImported` (z liczbą odrzuconych wierszy). Bez konsumenta w reaktorze to
   dopisywanie kolejnego pisania w próżnię, więc **DLQ przestaje być opcjonalne**.
4. **Kompensacja nazwana per faza, nie jedna.** Fazy mają różne odwracalności: kategorie
   (zostają — mogą już być używane), cash changes (`rollbackData` zna ich id), wiersze staged (TTL),
   mapowania (opcjonalnie przy `finalize`). Dziś to jedna flaga `deleteCategories`.
5. **Import poza żądaniem HTTP.** `POST /import` oddaje `jobId` i `202`, import jedzie osobno,
   postęp leci na topic, który wreszcie ktoś czyta. Tu kolejka jest właściwym narzędziem.

Kolejność ma znaczenie: **punkty 1 i 2 nie potrzebują Kafki** i są zmianą w jednym module. Bez nich
każde nowe zdarzenie opisywałoby stan trzymany w trzech miejscach naraz. Kafka wchodzi dopiero w
punktach 3 i 5.

## Powiązane

- `2026-10-22-bank-data-ingestion-sections.md` — podział na sekcje (F67), z którego to wynikło
- `TODO-kafka-dead-letter-queue.md` — warunek wstępny punktu 3
- `2026-10-15-alerts-module-design.md` — naturalny dom konsumenta zdarzeń ingestion
- F4 (`websocket-gateway` do reaktora) — dziś jedyny konsument topicu żyje poza reaktorem
