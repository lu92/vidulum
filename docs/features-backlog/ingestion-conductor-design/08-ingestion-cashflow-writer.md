# 08 · `vidulum-ingestion-cashflow-writer`

**Rola:** jedyny moduł rodziny, który **pisze do cash flow**. Tworzy kategorie z planu, importuje
wiersze, wycofuje import przy kompensacji.

**Posiada danych:** **żadnych.** Nie ma kolekcji, nie ma `DataCleaner`, nie ma
`spring-boot-starter-data-mongodb` — jak `vidulum-risk` i `vidulum-quotation` dziś. Wszystko, co
wie, przychodzi w komendzie albo przez `staging-api`; wszystko, co zmienia, należy do cashflow.

**Zależności (`pom.xml`):**

```xml
<dependency>vidulum-ingestion-conductor-api</dependency>
<dependency>vidulum-ingestion-canonical</dependency>
<dependency>vidulum-ingestion-staging-api</dependency>   <!-- TYLKO czytanie wierszy -->
<dependency>vidulum-cashflow-api</dependency>            <!-- createCategory, importHistorical, rollbackImport -->
<dependency>spring-kafka</dependency>
<dependency>spring-boot-starter-classic</dependency>
```

Brak `-data-mongodb` w tym pomie jest zdaniem o module: **ten moduł niczego nie persystuje.**

## Kontrakt — trzy gniazda

```java
// 1. implements ProvisionCategoriesHandler
void onProvisionCategories(ProvisionCategoriesCommand command);
//    plan.validated() → createCategory(name, parent, type, isImport=true) po kolei
//    CategoryAlreadyExistsException → existed[], NIE błąd
//    jakikolwiek inny wyjątek → StepReply(FAILED) i import się NIE ZACZYNA

// 2. implements ImportRowsHandler
void onImportRows(ImportRowsCommand command);
//    rowsOf(sessionId, VALID, strona) → importHistorical(...) per wiersz
//    co `progressEvery` wierszy: StepReply(PARTIAL, ImportProgress)
//    finalnie: RowsImported(pairs, failed) — SUCCEEDED gdy failed puste, inaczej PARTIAL

// 3. implements CompensateImportHandler
void onCompensateImport(CompensateImportCommand command);
//    skeletonOf().isInSetup() ? rollbackImport(cashFlowId, deleteCategories) : REJECTED(ATTESTED)
```

```java
/** Tłumaczenie wiersza na to, co rozumie cash flow. Jedno miejsce w całym projekcie. */
static HistoricalCashChange toCashChange(StagedRowView row) {
    return new HistoricalCashChange(
            row.resolution().category(),
            row.original().name(),
            row.original().effectiveDescription(),
            row.original().money(),            // skala BigDecimal nietknięta
            row.original().type(),
            paidDate(row),                     // ZonedDateTime - strefa i godzina nietknięte
            paidDate(row),                     // dueDate == paidDate: wyciąg nie ma terminu płatności
            row.selfTransfer(),
            row.original().bankTransactionId());   // na tym stoi idempotencja
}
```

Dwie rzeczy w tej metodzie są świadomymi powtórzeniami dzisiejszego kodu i mają własne testy:
`dueDate == paidDate` (bo wyciąg bankowy nie zna terminu płatności) oraz przekazanie
`bankTransactionId` — które „było dostępne cały czas i po prostu nigdy nie przekraczało granicy",
jak mówi komentarz w obecnym handlerze.

## Scenariusze testowe

### A. `PROVISION_CATEGORIES` — component (atrapa `CashFlowModuleApi` liczy wywołania)

| test | given → then |
|---|---|
| `shouldCreateParentsBeforeChildren` | plan: „Rozrywka", potem „Subskrypcje"(parent=Rozrywka) → kolejność wywołań `createCategory` **dokładnie** taka |
| `shouldRefusePlanWithChildBeforeParent` | plan w złej kolejności → `StepReply(FAILED)` **zanim** cokolwiek zostanie utworzone (`plan.validated()`) |
| `shouldPassIsImportTrue` | → każde wywołanie `createCategory(..., isImport=true)`; flaga widoczna w wywołaniu, nie wbita w URL |
| `shouldTreatAlreadyExistingAsExisted` | atrapa rzuca `CategoryAlreadyExistsException` na 2 z 11 → `created==9`, `existed==2`, status `SUCCEEDED` |
| `shouldFailWhenCategoryCreationBreaksForAnyOtherReason` | atrapa rzuca `RuntimeException` → `StepReply(FAILED)`, `created` zawiera **tylko** te, które naprawdę powstały |
| `shouldNotSwallowFailureAndContinue` | j.w. → **zero** dalszych wywołań `createCategory` po błędzie (dzisiejszy `catch (Exception) { log.warn; processed++; }` jest dokładnie tą wpadką) |
| `shouldReportCreatedNamesForCompensation` | → `CategoriesProvisioned.created` zgodne co do nazw, bo na tym stoi kompensacja |
| `shouldSucceedOnEmptyPlan` | plan pusty → `SUCCEEDED`, zero wywołań |

### B. `IMPORT` — component

| test | given → then |
|---|---|
| `shouldImportEveryValidRow` | 381 wierszy przez atrapę `IngestionRowsApi` → 381 wywołań `importHistorical`, `pairs.size()==381` |
| `shouldReadOnlyValidRows` | w magazynie 402 wiersze (15 dup, 6 odrzutów) → filtr `VALID`, **zero** wywołań dla pozostałych |
| `shouldPreserveMoneyScale` | wiersz `1234.50 PLN` → `HistoricalCashChange.money()` **equals** `1234.50`, nie `1234.5` |
| `shouldPreserveTimeAndZone` | `paidDate = 2023-01-13T14:32+01:00[Europe/Warsaw]` → ta sama chwila i strefa w wywołaniu |
| `shouldSetDueDateToPaidDate` | → `dueDate == paidDate` (świadome powtórzenie, zamrożone testem) |
| `shouldPassBankTransactionId` | → id z wiersza w wywołaniu; brak id → test nie przechodzi |
| `shouldPassSelfTransferFlag` | wiersz `selfTransfer` → flaga `true` w wywołaniu |
| `shouldReportProgressEveryFiftyRows` | 381 wierszy, `progressEvery=50` → 7 odpowiedzi `PARTIAL` + 1 finalna; `processed` rosnące monotonicznie |
| `shouldContinueAfterSingleRowFailure` | atrapa rzuca na wierszu 100 → pozostałe 380 zaimportowane, `failed.size()==1` z `bankTransactionId` i powodem |
| `shouldReplyPartialWhenAnyRowFailed` | `failed` niepuste → status `PARTIAL`, nie `SUCCEEDED` |
| `shouldReplyRejectedWhenCashFlowRefusesWholeImport` | atrapa rzuca `ImportNotAllowedInClosedModeException` → `REJECTED(retryable=false)`, zero dalszych prób |
| `shouldReturnTheSameIdsOnRetry` | ponowienie z tym samym `commandId`, atrapa idempotentna po `bankTransactionId` → te same `pairs`, `failed` puste |
| `shouldPageWithoutLosingOrDuplicatingRows` | 381 wierszy, strona 100 → 381 unikalnych `rowId` w `pairs` |

### C. `UNIMPORT` — component

| test | given → then |
|---|---|
| `shouldCheckSetupBeforeCompensating` | skeleton `SETUP` → `rollbackImport` wołane; skeleton `OPEN` → **zero** wywołań, `REJECTED` z powodem `ATTESTED` |
| `shouldDeleteCategoriesOnlyWhenImportCreatedThem` | `createdCategoryNames` puste → `rollbackImport(..., deleteCategories=false)` |
| `shouldReportRealNumbersFromRollbackResult` | atrapa zwraca `RollbackResult(299, 9)` → `ImportCompensated(299, 9)`; **żadnych zer z palca** (udokumentowany powód istnienia `RollbackResult`) |
| `shouldReplyFailedWhenRollbackBreaks` | atrapa rzuca → `FAILED(retryable=true)`, kompensacja wznawialna |

### D. Czego nie testujemy tutaj

| rzecz | gdzie |
|---|---|
| czy cash change naprawdę powstał w Mongo i w prognozie | 09 |
| czy `importHistorical` jest faktycznie idempotentne | 09 (tu atrapa **zakłada** kontrakt; 09 go **dowodzi**) |
| czy kategoria z `isImport=true` przechodzi w SETUP | 09 |

**Czas wykonania:** < 3 s.

## Reguły architektoniczne (09)

1. **To jedyny moduł rodziny, który wywołuje `CashFlowModuleApi.createCategory`,
   `importHistorical`, `rollbackImport`** — reguła na poziomie wywołań metod, nie pakietów.
2. Nie zależy od `classification` ani `category-resolution`: do czasu importu klasyfikator już
   skończył (dzisiejsze `importingDoesNotKnowWhoClassifiedTheRows`, tylko wymuszone Mavenem).
3. Nie zależy od conductora — tylko od protokołu.
4. **Nie ma `@Document` ani repozytorium Spring Data** — reguła strukturalna; gdyby ten moduł
   zaczął cokolwiek zapisywać, znaczyłoby to, że przestał być pisarzem cudzego agregatu.
