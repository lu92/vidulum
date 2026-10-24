# 05 · `vidulum-ingestion-staging` (+ `vidulum-ingestion-staging-api`)

**Rola:** magazyn wierszy i **jedyne miejsce, w którym wiersz jest oceniany**: duplikat, zła data,
zamknięty miesiąc, przelew własny. Tu też powstaje podział na grupy, czyli jednostka pracy całego
dalszego pipeline'u.

**Posiada danych:** `ingestion_rows` — jedyny piszący.

## Dwa artefakty, bo czytanie i pisanie mają różnych odbiorców

```xml
<!-- vidulum-ingestion-staging-api — KONTRAKT, tylko czytanie -->
<dependency>vidulum-shared-kernel</dependency>
<dependency>vidulum-ingestion-canonical</dependency>
<dependency>vidulum-ingestion-conductor-api</dependency>   <!-- Resolution, GroupKey w filtrach -->
```

```xml
<!-- vidulum-ingestion-staging — IMPLEMENTACJA -->
<dependency>vidulum-ingestion-staging-api</dependency>
<dependency>vidulum-ingestion-conductor-api</dependency>
<dependency>vidulum-ingestion-canonical</dependency>
<dependency>vidulum-cashflow-api</dependency>                      <!-- okno importu, duplikaty -->
<dependency>vidulum-user-financial-profile-api</dependency>        <!-- konta własne -->
<dependency>spring-boot-starter-data-mongodb</dependency>          <!-- persystuje -->
<dependency>spring-kafka</dependency>                              <!-- mówi zdarzeniami -->
<dependency>spring-boot-starter-classic</dependency>
```

**Nie ma `spring-boot-starter-webmvc` i to jest celowe:** ten moduł nie ma endpointów. Podgląd
wierszy wystawia conductor, pytając przez `staging-api`. Pom jako dokumentacja modułu — reguła z
CLAUDE.md.

## Kontrakt — `ingestion-staging-api` (czytanie)

```java
/** Workerzy i conductor pytają o wiersze; pisze tylko staging, przez komendy sagi. */
public interface IngestionRowsApi extends ModuleApi {

    /** Stronicowo, bo 402 wiersze to minimum, nie maksimum. */
    RowPage rowsOf(SessionId sessionId, RowFilter filter, int page, int size);

    /** Liczniki bez ściągania wierszy — conductor odtwarza nimi stan po odzysku. */
    RowCounts countsOf(SessionId sessionId);

    /** Wiersze jednej grupy — do podglądu „co właściwie jest w tej grupie". */
    RowPage rowsOfGroup(SessionId sessionId, GroupKey groupKey, int page, int size);
}

public record RowFilter(Set<ValidationStatus> statuses, Boolean resolved, Boolean imported) {
    static RowFilter valid();
    static RowFilter all();
}
public record RowPage(List<StagedRowView> rows, int page, int size, long total) {}
public record RowCounts(int total, int valid, int duplicate, int rejected,
                        int resolved, int imported, int failedToImport) {}

/** Widok wiersza: wiersz kanoniczny + co o nim zdecydowano. Bez encji Mongo na zewnątrz. */
public record StagedRowView(String rowId, int lineNo, CanonicalRow original,
                            ValidationStatus status, List<String> errors,
                            String duplicateOfCashChangeId, boolean selfTransfer,
                            GroupKey groupKey, Resolution resolution,
                            String cashChangeId, String importError) {}

public enum ValidationStatus { VALID, REJECTED, DUPLICATE }
```

**`PENDING_MAPPING` zniknął ze słownika walidacji** — i to jest zmiana modelu, nie nazwy. Dziś wiersz
bez mapowania jest „niepoprawny" i blokuje import. Tutaj brak rozstrzygnięcia jest stanem **grupy** w
sesji (`GroupRecord.resolution == null`), a nie wadą wiersza. Wiersz jest `VALID`, `REJECTED` albo
`DUPLICATE` — trzy stany, każdy o czymś faktycznym.

## Kontrakt — cztery gniazda sagi, które ten moduł implementuje

| gniazdo (z `conductor-api`) | co robi | co zapisuje |
|---|---|---|
| `ValidateRowsHandler` | ocenia 402 wiersze, nadaje `groupKey`, buduje 38 `GroupSummary` | **402 nowe dokumenty** |
| `ApplyResolutionHandler` | rozstrzygnięcie grupy → `resolution` na jej wierszach | update po `groupKey` |
| `MarkImportedHandler` | pary `rowId → cashChangeId` | update `importResult` |
| `DiscardRowsHandler` | kompensacja: usuwa wiersze sesji | delete po `sessionId` |

Walidacja, w tej kolejności i ani w innej:

```java
1. duplikat        alreadyImported.find(bankTransactionId)        → DUPLICATE + id istniejącego
2. okno importu    skeleton.importWindow().refusalFor(date, now)  → REJECTED + ErrorCode cashflow
3. status miesiąca monthStatuses.acceptsImportAt(period)          → REJECTED  [TYLKO gdy 2. przeszło]
4. przelew własny  ownedAccounts.contains(counterpartyAccount)    → selfTransfer = true
5. grupa           GroupKey.of(row, selfTransfer)
```

Punkt 3 **tylko wtedy, gdy 2 przeszło** — żeby jedna zła data nie dała dwóch komunikatów. To reguła,
którą dzisiejszy `StageTransactionsCommandHandler` ma i którą trzeba przenieść wraz z testem.

## Scenariusze testowe

### A. Walidacja wiersza — unit

| test | given → then |
|---|---|
| `shouldMarkRowAsDuplicateOfTheExistingCashChange` | `bankTransactionId` już zaimportowany jako `CC00001234` → `DUPLICATE`, `duplicateOfCashChangeId == "CC00001234"` (**nie** id wiersza przychodzącego — dokładnie ta wpadka jest udokumentowana w `TransactionValidation`) |
| `shouldRejectDateBeforeStartPeriod` | `2022-12-28` przy `startPeriod=2023-01` → `REJECTED`, `ErrorCode.IMPORT_DATE_BEFORE_START` |
| `shouldRejectFutureDate` | data po `now` → `REJECTED`, `IMPORT_DATE_IN_FUTURE` |
| `shouldReportOnlyTheFirstRefusalWhenDateBreaksTwoRules` | data jednocześnie przed `startPeriod` i w nieotwartym miesiącu → **jeden** błąd, ten z okna importu (kolejność sprawdzania jest kontraktem) |
| `shouldRejectAttestedMonth` | miesiąc `ATTESTED` → `REJECTED` z komunikatem nazywającym miesiąc |
| `shouldNotConsultMonthStatusesWhenWindowAlreadyRefused` | atrapa `CashFlowForecastApi` → **zero** wywołań dla takiego wiersza |
| `shouldNotConsultMonthStatusesInSetup` | skeleton `SETUP` → statusy miesięcy nie decydują (dzisiejsza reguła `skeleton.isOpen()`) |
| `shouldAcceptRowInsideTheWindow` | data w zakresie, miesiąc `FORECASTED`/`ACTIVE` → `VALID`, `errors` puste |

### B. Przelew własny — unit

| test | given → then |
|---|---|
| `shouldDetectSelfTransferByOwnedIban` | kontrahent = jeden z 3 kont własnych → `selfTransfer=true`, `groupKey == SELF_TRANSFER` |
| `shouldNotDetectSelfTransferForForeignIban` | kontrahent nieznany → `false` |
| `shouldIgnoreBlankCounterparty` | oba konta puste → `false`, zero wywołań do profilu per wiersz |
| `shouldDetectSelfTransferWhenAiSwappedColumns` | OUTFLOW z kontem własnym w `sourceAccountNumber` → wykryty (fallback `counterpartyAccount()`) |
| `shouldAskUserProfileOncePerSession` | 402 wiersze → **1** wywołanie `UserFinancialProfileApi` |
| `shouldTakeOwnerFromTheCommandNotFromTheSkeleton` | `ValidateRowsCommand.owner` ustawione → `skeleton.owner()` **nie** wołane (conductor zna ownera z JWT; pomiar w [pliku 10, §5](10-granice-artefaktow.md)) |

### C. Grupowanie — unit

| test | given → then |
|---|---|
| `shouldGroupGoldenFileIntoThirtyEightGroups` | 402 wiersze pliku testowego → 38 `GroupSummary` |
| `shouldSumValueSharesToOne` | `Σ valueShare` ≈ 1.0 |
| `shouldCapSamplesAtThree` | grupa 34-wierszowa → `samples.size() == 3` |
| `shouldGroupAutoCategorizableByClassification` | 18 wierszy `BANK_FEE` o różnych nazwach → jedna grupa |
| `shouldKeepDirectionsApart` | ten sam merchant INFLOW i OUTFLOW → dwie grupy |
| `shouldExcludeDuplicatesAndRejectsFromGroupTotals` | 15 duplikatów + 6 odrzutów → sumy grup liczone z 381, nie z 402 |

### D. Component — prawdziwe handlery + `InMemoryIngestionRowRepository`

| test | given → then |
|---|---|
| `shouldStoreEveryRowWithValidationAndGroup` | `ValidateRowsCommand(402)` → 402 dokumenty; losowy wiersz asercją **całoobiektową** przez konstruktor `StagedRowView` |
| `shouldReplyWithCountsAndGroups` | → `RowsValidated(total=402, valid=381, duplicate=15, rejected=6, groups=38)`, asercja całoobiektowa |
| `shouldAskExactlyFourExternalQuestions` | atrapy liczące wywołania → `skeletonOf` 1×, `monthStatusesOf` 1×, `alreadyImported` 1×, profil 1× na **cały** batch 402 wierszy |
| `shouldHandleChunkedCommand` | 3 chunki po 150 wierszy → jedna odpowiedź po ostatnim, 402 dokumenty, zero duplikatów |
| `shouldBeIdempotentOnRetry` | ta sama komenda z tym samym `commandId` dwa razy → 402 dokumenty, nie 804 |
| `shouldApplyGroupResolutionToAllItsRows` | `ApplyResolutionCommand` z 1 grupą 34-wierszową → 34 wiersze z `resolution`, pozostałe bez |
| `shouldMarkProvisionalResolution` | rozstrzygnięcie `FALLBACK` → `resolution.provisional == true` na wierszach |
| `shouldNotTouchRowsOfAnotherSession` | dwie sesje tego samego cash flow → update tylko swoich |
| `shouldMarkImportedPairs` | 381 par → `importResult.cashChangeId`; nieznany `rowId` pominięty bez wyjątku |
| `shouldDiscardOnlyItsOwnRows` | `DiscardRowsCommand` → usuwa 402 wiersze tej sesji, wiersze drugiej zostają |
| `shouldReplyFailedWhenRepositoryBreaks` | atrapa repozytorium rzuca → `StepReply(FAILED, retryable=true)`, nic nie połknięte |
| `shouldPageRowsThroughTheContract` | 402 wiersze, `size=100` → 5 stron, `total==402`, brak powtórzeń między stronami |

### E. Czego nie testujemy tutaj

| rzecz | gdzie |
|---|---|
| indeks `(cashFlowId, bankTransactionId)`, TTL, `expireAfter` | 09 — `indexOps(...).getIndexInfo()` |
| realna kolejność wiadomości i klucz partycji | 09 |
| czy duplikat naprawdę istnieje w cash flow | 09 (tu atrapa `alreadyImported`) |

**Czas wykonania modułu:** cel < 4 s na ~60 testach, bez Dockera.

## Reguły architektoniczne (09)

1. `ingestion-staging` nie zależy od `classification`, `category-resolution`, `cashflow-writer` ani
   od conductora — tylko od protokołu.
2. Nic poza `ingestion-staging` nie zna `IngestionRowEntity` ani `IngestionRowMongoRepository`
   (`assertSealed`).
3. `ingestion-staging-api` nie zależy od Springa i nie wystawia typu Mongo.
4. Żaden inny moduł nie pisze do `ingestion_rows` — reguła na poziomie wywołań: tylko ten moduł
   woła `save`/`delete` na tym repozytorium.
