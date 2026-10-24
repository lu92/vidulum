# 09 · `vidulum-ingestion-tests`

**Rola:** testy, które mają sens tylko na działającym systemie — cały przepływ od pliku do cash
flow, kody HTTP, serializacja, indeksy, TTL, Kafka, DLQ, odzysk — plus reguły architektoniczne
całej rodziny.

**`src/main` nie istnieje.** Tak jak `vidulum-cashflow-tests` i `vidulum-wealth-tests`.

## Dlaczego osobny moduł, a nie testy w modułach

Nie z wygody, tylko **żeby reaktor pozostał acykliczny**. Test całego przepływu potrzebuje
*działającego* cash flow, czyli `vidulum-cashflow` — implementacji. Workerzy zależą wyłącznie od
kontraktów (`cashflow-api`), i to jest własność, która czyni ich wymienialnymi. Gdyby
`ingestion-cashflow-writer` miał `vidulum-cashflow` choćby w zakresie `test`, pojawiłaby się
krawędź, której dziś nie ma — dokładnie ten sam powód, dla którego siedem testów
`bank-data-ingestion` mieszka w `vidulum-cashflow-tests`.

```xml
<artifactId>vidulum-ingestion-tests</artifactId>

<!-- cała rodzina, w zakresie test -->
<dependency>vidulum-ingestion-canonical</dependency>
<dependency>vidulum-ingestion-intake</dependency>
<dependency>vidulum-ingestion-conductor-api</dependency>
<dependency>vidulum-ingestion-conductor</dependency>
<dependency>vidulum-ingestion-staging-api</dependency>
<dependency>vidulum-ingestion-staging</dependency>
<dependency>vidulum-ingestion-classification</dependency>
<dependency>vidulum-ingestion-category-resolution</dependency>
<dependency>vidulum-ingestion-cashflow-writer</dependency>

<!-- implementacje, których przepływ naprawdę potrzebuje -->
<dependency>vidulum-cashflow</dependency>                      <!-- agregat + prognoza -->
<dependency>vidulum-user-financial-profile</dependency>        <!-- konta własne -->
<dependency>vidulum-bank-data-ai-categorization</dependency>   <!-- podmieniane atrapą, patrz niżej -->
<dependency>vidulum-bank-data-adapter</dependency>             <!-- TYLKO do testu słownika -->

<!-- narzędzia -->
<dependency>spring-boot-resttestclient</dependency>
<dependency>spring-kafka-test</dependency>
<dependency>org.testcontainers:junit-jupiter, :mongodb, :kafka</dependency>
<dependency>com.tngtech.archunit:archunit-junit5</dependency>
<dependency>org.awaitility:awaitility</dependency>
```

Miejsce w reaktorze: **po** `vidulum-app-cashflow` i `vidulum-app-ingestion`, obok
`vidulum-cashflow-tests`.

## Baza i zegar — jedna pułapka do nazwania od razu

```java
@SpringBootTest(classes = IngestionTestApplication.class, webEnvironment = RANDOM_PORT)
@Testcontainers
public abstract class IngestionIntegrationTest {

    /**
     * NIE 2022-01-01, mimo że tak robi FixedClockConfig w cashflow.
     *
     * Fixture Nest ma transakcje do 2025-12-31, a Pekao **do 2026-01-09** - i to Pekao wyznacza
     * dolną granicę zegara, nie Nest. ImportWindow odrzuca datę z przyszłości, więc zegar na
     * 2022-01-01 sprawiłby, że WSZYSTKIE wiersze są "w przyszłości" i przepływ zwróciłby same
     * odrzuty - test przechodziłby, nie testując niczego.
     */
    protected static final ZonedDateTime FIXED_NOW = ZonedDateTime.parse("2026-01-15T10:00:00Z[UTC]");

    @Container static MongoDBContainer mongo = ...;      // replica set - transakcje
    @Container static KafkaContainer kafka = ...;

    protected IngestionHttpActor ingestion;
    protected CashFlowHttpActor cashFlow;
    protected ForecastHttpActor forecast;

    protected String registerUserAndGetToken();          // JWT, jak w CashFlowIntegrationTest
    protected byte[] goldenCanonicalCsv();               // fixture 17-kolumnowy z K12 - NIE ten
                                                         // surowy nestbank_*.csv, który jest w repo
    protected void advanceClockBy(Duration d);           // MutableClock - do testów TTL
}
```

**Klasyfikator jest atrapą, zawsze.** `IngestionTestApplication` rejestruje
`DeterministicCategorizationApi implements TransactionCategorizationApi` z `@Primary`: odpowiada z
tablicy sugestii opisanej w teście. Powody są trzy i każdy wystarcza: prawdziwy model **kosztuje**,
jest **niedeterministyczny**, a testy mają biegać w CI bez klucza API. Jeden test z prawdziwym
modelem może istnieć — `@Disabled`, uruchamiany ręcznie, i tak oznaczony.

## Aktorzy

Wzorzec z CLAUDE.md („Use Actor pattern"), odpowiedniki `BankDataIngestionHttpActor`:

```java
class IngestionHttpActor {
    SessionJson start(String cfId, byte[] csv, String fileName, AutonomyLevel level);
    SessionJson session(String sessionId);
    SessionJson answer(String sessionId, List<DecisionAnswerJson> answers);
    SessionJson compensate(String sessionId, String reason);
    SessionJson awaitPhase(String sessionId, IngestionPhase phase, Duration timeout);  // Awaitility
    List<RowJson> rows(String sessionId, String filter);
}
class CashFlowHttpActor {
    String createWithHistory(String startPeriod, Money initialBalance, String iban);
    CashFlowJson get(String cfId);
    void attest(String cfId, Money confirmedBalance);
}
class ForecastHttpActor {
    ForecastJson get(String cfId);
    MonthStatusesJson monthStatuses(String cfId);
}
```

## Scenariusze testowe

### A. `IngestionHappyPathTest` — przepływ, dla którego to wszystko powstało

| test | scenariusz → asercje |
|---|---|
| `shouldImportWholeStatementWithoutAnyUserDecision` | `AUTOPILOT`, plik złoty (K12) → faza `IMPORTED`; `decisions` **puste**; niezmienniki: `total == valid + duplicate + rejected`, liczba cash changes w cash flow `== counters.imported`, każdy zaimportowany wiersz ma `cashChangeId`, suma kwot miesiąca w prognozie `==` suma wierszy tego miesiąca. **Liczby bezwzględne (402/381/15/6) wpisz po pierwszym przebiegu** — dziś są szacunkiem |
| `shouldAskOnlyAboutMaterialGroupsAndImportAfterOneAnswer` | `REVIEW_MATERIAL` → faza `AWAITING_DECISION`, `openDecisions.size() <= 7`; **jedno** `POST /decisions` z 5 odpowiedziami → `IMPORTED`; jedna z odpowiedzi tworzy podkategorię „Subskrypcje" pod „Rozrywka" i ta hierarchia **jest** w cash flow |
| `shouldAskNothingOnSecondUploadOfTheSameBank` | po pierwszym imporcie drugi plik tego samego banku (inne `bankTransactionId`, te same merchanci) → `decisions` **puste**, faza `IMPORTED`; dowód, że `LEARN` działa |
| `shouldCountOnlyTwoUserRequestsForTheWholeImport` | licznik żądań w aktorze → dokładnie **2** wywołania zmieniające stan (`start`, `decisions`); `GET`-y nie liczone |
| `shouldConfirmBalanceSilentlyWhenStatementCarriesIt` | fixture Nest z kolumną salda → sesja kończy `IMPORTED` z `balanceCheck.difference == 0.00` i **bez żadnego pytania**; saldo ostatniego wiersza (76 047,25) zgadza się z policzonym przez cash flow |
| `shouldSurfaceTheCurrencyGapAsABalanceDifference` | fixture Pekao, 2 wiersze EUR odrzucone przy walidacji → `balanceCheck.difference` równa ich realnej wartości, a odpowiedź wymienia je jako kandydatów |
| `shouldCreateAdjustmentOnlyOnConsent` | różnica ≠ 0 i `createAdjustment=false` → zero nowych cash changes; z `true` → **jeden**, bez `bankTransactionId`, w miesiącu `ACTIVE` |
| `shouldNotDoubleCountWhenBalanceIsAskedForTheStatementDate` | wgranie pliku do 9 stycznia z saldem **na 9 stycznia**, potem pliku z transakcjami z 10–11 stycznia → suma zgodna z bankiem, **zero podwójnie policzonych kwot** |
| `shouldPlaceEveryImportedRowInTheRightForecastMonth` | po imporcie → suma przepływów miesiąca 2023-01 z prognozy równa sumie wierszy tego miesiąca z `GET rows` |
| `shouldImportTwoStatementsIntoTwoCashFlows` | **fixture Pekao obok Nest**: dwa cash flow (bo `CashFlow` ma jeden rachunek), dwie sesje, nachodzące się miesiące 2025 → każdy wyciąg trafia do swojego cash flow, a próba wgrania Pekao do cash flow Nest kończy się odmową (M8) |
| `shouldHandleCardHeavyStatement` | Pekao, 791 wierszy, 165 grup, 492 płatności kartą → `IMPORTED`; liczba pytań **≤ maxQuestions**, a sesja raportuje, jaki procent wartości pokryły (bo 90 % wymagałoby 35 pytań) |

### B. `IngestionValidationTest` — wiersz nie blokuje wyciągu

| test | scenariusz → asercje |
|---|---|
| `shouldRejectRowsOutsideImportWindowAndImportTheRest` | plik z 4 wierszami przed `startPeriod` i 2 z przyszłości → `rejected == 6` z `ErrorCode` per wiersz, `imported == 381`, faza `IMPORTED` (odrzut **nie** daje `PARTIALLY_IMPORTED`) |
| `shouldRefuseImportIntoAttestedMonth` | miesiąc 2023-01 zaatestowany przed importem → wiersze tego miesiąca `REJECTED` z komunikatem nazywającym miesiąc; pozostałe wchodzą |
| `shouldSkipRowsAlreadyImported` | ten sam plik dwa razy (nowa sesja) → druga sesja: `duplicate == 402`, `imported == 0`, cash flow **bez zmian**, `GET rows` pokazuje `duplicateOfCashChangeId` wskazujący realne `CC…` |
| `shouldReturnTheSameSessionForTheSameFileUploadedTwice` | dwa `POST /ingestion` z identycznymi bajtami w trakcie trwania pierwszej sesji → ten sam `sessionId`, jedna sesja w Mongo |
| `shouldRefuseSecondConcurrentSessionForCashFlowInSetup` | druga sesja (inny plik) przy aktywnej pierwszej → **409**; strażnik luki z §25 |
| `shouldAnswerNotFoundForUnknownCashFlow` | `cashFlowId=CF99999999` → **404** z `CASHFLOW_NOT_FOUND`, sesja nie powstaje |
| `shouldAnswerNotFoundForAnotherUsersSession` | sesja usera A czytana tokenem usera B → **404**, nie 403 |
| `shouldRefuseClosedCashFlow` | cash flow `CLOSED` → **400/409** z `CASHFLOW_CLOSED` |

### C. `IngestionFailureTest` — porażki i kompensacja

| test | scenariusz → asercje |
|---|---|
| `shouldContinueWhenClassificationFails` | atrapa klasyfikatora rzuca przy każdej próbie → `steps[CLASSIFY].status == FAILED`, faza mimo to `IMPORTED`, kategorie z fallbacku, `imported == 381`; **nic nie stracone** |
| `shouldNotImportAnythingWhenProvisioningFails` | atrapa cashflow odmawia utworzenia jednej kategorii → faza `FAILED`, cash flow ma **0** cash changes, `failure` nazywa kategorię |
| `shouldCompensateAfterPartialImport` | wstrzyknięta porażka na wierszu 300 → `PARTIALLY_IMPORTED`; `POST /compensate` → cash flow **pusty**, wzorce zapomniane, wiersze usunięte, faza `COMPENSATED`, **`failure` nadal obecne** |
| `shouldRefuseCompensationAfterAttestation` | import → atestacja → `POST /compensate` → odmowa z powodem `ATTESTED`, cash flow **nietknięty** |
| `shouldResumeStepAfterReplyIsLost` | przełącznik testowy gubi `StepReply` z `IMPORT` → `IngestionRecoveryScheduler` wznawia ten sam `commandId` → dokładnie **381** cash changes, nie 762; idempotencja dowiedziona end-to-end |
| `shouldAutoResolveDecisionAfterTtl` | `REVIEW_MATERIAL`, zegar przesunięty za `autoResolveAt` → `recommended` zastosowane, `decisions[].autoResolved == TTL`, import przeszedł; **upload nie przepadł** (dziś TTL kasuje sesję) |
| `shouldSendPoisonMessageToDeadLetterTopic` | wiadomość nieparsowalna na `ingestion.cmd.validate` → ląduje na `ingestion.cmd.validate.dlt`, konsument **nie** wpada w pętlę, faza `FAILED` |
| `shouldFailNotRetryableOnRejectedReply` | `REJECTED` z workera → zero ponowień w `steps[]`, faza `FAILED` |

### D. `IngestionStorageTest` — to, czego adnotacja nie dowodzi

| test | scenariusz → asercje |
|---|---|
| `shouldCreateCompoundAndTtlIndexes` | `mongoTemplate.indexOps(IngestionRowEntity.class).getIndexInfo()` → obecny `cf_bank_txn_idx`, `session_status_idx` i indeks TTL na `expiresAt`; to samo dla sesji i mapowań. **Asercja na bazie, nie na adnotacji** (CLAUDE.md) |
| `shouldRejectConcurrentSessionWriteWithOptimisticLock` | dwa równoległe `POST /decisions` → jeden **409**, drugi przechodzi; stan sesji spójny (nie mieszanka dwóch) |
| `shouldCleanEveryIngestionCollection` | zapis do `ingestion_sessions`, `ingestion_rows`, `ingestion_category_mappings`, `ingestion_classifications` → uruchomienie wszystkich `DataCleaner` → kolekcje puste **i indeksy nadal obecne** (lekcja `dropCollection`) |
| `shouldNotReuseCachedClassificationAfterCleaning` | klasyfikacja → cleaner → ponowny przebieg → atrapa klasyfikatora wołana **znowu**; bez tego przebieg udaje determinizm (ta sama uwaga, co przy `bank_data_adapter`) |
| `shouldExpireRowsButKeepTheSessionRecord` | TTL wierszy przekroczony → `ingestion_rows` puste, **sesja nadal czytelna** przez `GET` (dziś jest odwrotnie: sesja ma TTL, `ImportJobEntity` nie ma żadnego) |

### E. `IngestionMessagingTest` — Kafka jako kontrakt

| test | scenariusz → asercje |
|---|---|
| `shouldKeySessionMessagesBySessionId` | konsument testowy na topikach komend → każdy rekord ma `key == sessionId` (kolejność per sesja) |
| `shouldPublishOneReplyPerStep` | pełny przepływ → na `ingestion.reply` dokładnie po jednej odpowiedzi finalnej na krok + odpowiedzi `PARTIAL` postępu |
| `shouldChunkLargeValidateCommand` | plik 5 000-wierszowy → komendy z `chunkCount > 1`, każda pod 1 MB, jedna odpowiedź |
| `shouldRunTheWholeFlowWithInProcessTransport` | `vidulum.ingestion.step.transport=in-process` → ten sam przepływ bez Kafki; **ten test pilnuje, że testy komponentowe modułów nie kłamią** |
| `shouldNotEmitFactsUntilThereIsAConsumer` | przegląd topików → brak `ingestion.facts` dopóki nie ma konsumenta w reaktorze (P4). Test, żeby nikt nie dopisał szóstego zdarzenia w próżnię — dokładnie to, co dziś robi `bank_data_ingestion` |

### E2. `OngoingImportTest` — wgrywanie kolejnych plików po atestacji

Grupa dopisana po analizie importu ciągłego (specyfikacja 13). **Żadnego z tych przypadków nie da
się pokazać testem komponentowym**, bo każdy zależy od tego, co atestacja zrobiła z cash flow.

| test | scenariusz → asercje |
|---|---|
| `shouldImportSubsequentStatementAfterAttestation` | backfill Nest → atestacja → cash flow **OPEN** → wgranie wyciągu grudniowego (61 wierszy) → tryb sesji `ONGOING`, **zero pytań** (pamięć po backfillu), 60 zaimportowanych, 1 odrzucony (EUR) |
| `shouldAcceptOverlappingDateRanges` | po wgraniu grudnia wgranie zakresu **1 XII – 11 I** (75 wierszy) → **61 duplikatów pominiętych**, 14 nowych, zero pytań; duplikaty raportowane jako normalny wynik, nie ostrzeżenie |
| `shouldImportAMultiMonthFileAfterAQuarterOfSilence` | harmonogram rollover przeszedł **trzy razy bez ownera** (zegar przesuwany) → wgranie pliku za XII–II → wszystkie trzy miesiące przyjmują dane, **jedno** pytanie o saldo, **jedna** korekta w miesiącu bieżącym |
| `shouldFileLateBookedRowsIntoTheBookingMonthWhenOperationMonthIsAttested` | grudzień zaatestowany, wyciąg styczniowy nosi **dwa wiersze 21,99 PLN** z operacją 27 i 31 XII → trafiają do **stycznia**, a zaatestowane saldo grudnia **nadal się zgadza**. To arytmetyczny sens wyjątku z M9, nie wygoda |
| `shouldRefuseCompensationInOngoingMode` | cash flow OPEN → `POST /compensate` → odmowa nazywająca powód; **po X2** ta sama sesja wycofuje **tylko swoje** 61 cash changes, a backfill zostaje nietknięty |
| `shouldRecategoriseImportedRowsInBulk` | po imporcie w `AUTOPILOT` 147 wierszy prowizorycznych (BADOO 94 + ZABKA 53) → **jedno** wywołanie przekategoryzowania → kategorie zmienione, prognoza przeliczona, kwoty nietknięte. **Dziś ten test jest niewykonalny** i to jest jego wartość: zapina X1 |
| `shouldTreatGenericBankCategoryAsUnresolved` | cash flow ma już kategorię `Inne`, wgrywany Pekao ma 265 wierszy z `Inne`/`Bez kategorii` → **żaden** nie rozstrzyga się priorytetem 0, wszystkie idą do wzorców i AI |
| `shouldNotPolluteTheCategoryTreeWithTailGroups` | Pekao z progiem 3 wierszy → plan tworzy mniej niż 38 kategorii, grupy po 1–2 wiersze idą do rodzica albo fallbacku; po wycofaniu importu kategorie **zostają** (świadomie) |
| `shouldCollapseCardAuthorisationSettlementAndReversal` | 43 rozliczenia kartowe i 45 storn o identycznych kwotach → suma per merchant **bez podwójnego liczenia**: BADOO ma tyle transakcji, ile było zakupów, nie 94 wiersze; `valueShare` liczone po zwinięciu (**M12**) |

### F. `IngestionBoundariesArchTest` — reguły rodziny

Wzorzec: `ModuleBoundariesArchTest` z `vidulum-app`. **Bez listy wyjątków** — moduł jest nowy, więc
nie ma długu do zmierzenia.

| reguła | treść |
|---|---|
| `theConductorDependsOnNoWorker` | nic z `ingestion_conductor` nie sięga po `staging`, `classification`, `category_resolution`, `cashflow_writer` |
| `theConductorDoesNotKnowWhoClassifies` | nic z `ingestion_conductor` nie zależy od `bank_data_ai_categorization` ani od `org.springframework.ai` |
| `onlyTheWriterWritesToCashFlow` | wywołania `createCategory`, `importHistorical`, `rollbackImport` występują **wyłącznie** w `ingestion_cashflow_writer` |
| `theConductorOnlyReadsCashFlow` | w `ingestion_conductor` z `CashFlowModuleApi` używane są tylko `exists` i `skeletonOf` |
| `noWorkerDependsOnAnotherWorkersImplementation` | cztery slice'y workerów parami rozłączne **co do implementacji**; dozwolona jest wyłącznie zależność od `ingestion-staging-api` (tylko czytanie), i mają ją dokładnie trzy moduły: `cashflow_writer`, `classification`, `conductor` — uzasadnienie w [pliku 10, §4](10-granice-artefaktow.md) |
| `onlyStagingWritesRows` | `save`/`delete` na repozytorium wierszy wyłącznie w `ingestion_staging`; `staging-api` nie wystawia metody zapisującej |
| `nothingDependsOnClassification` | klasyfikator jest liściem — warunek jego wymienialności |
| `everyKafkaListenerImplementsAModuleEventHandler` | żadnego topicu jako goły string |
| `everyStepCommandAndReplyIsARecordImplementingIntegrationEvent` | zero klas-kopert z `Map<String,String>` |
| `everyWorkerIsSealed` | `assertSealed` dla każdego modułu: `app`, `domain`, `infrastructure` nieosiągalne z zewnątrz |
| `theFamilyIsFreeOfCycles` | `slices().matching("..vidulum.ingestion_(*)..").should().beFreeOfCycles()` |
| `onlyOneModuleWritesEachCollection` | `save`/`remove` na danym repozytorium występuje wyłącznie w module-właścicielu |

### G. `TransactionClassificationVocabularyTest` — jedyny test, który widzi oba słowniki

Przenosiny z `vidulum-cashflow-tests`, ta sama rola: porównuje stałe
`ingestion_canonical.TransactionClassification` z `bank_data_adapter.domain.TransactionClassification`
i **psuje się, gdy któraś strona zyska słowo, którego nie ma druga**. Dlatego ten moduł ma
`vidulum-bank-data-adapter` w zależnościach — do jednego testu, i to jest cała lista jego powodów.

| test | asercja |
|---|---|
| `bothSidesKnowTheSameWords` | zbiory nazw stałych identyczne |
| `theCanonicalSideParsesEveryWordTheAdapterCanProduce` | dla każdej stałej adaptera `fromString(name)` **nie** zwraca `UNKNOWN` |

## Co ten moduł **nie** testuje

| rzecz | gdzie i dlaczego |
|---|---|
| jakość sugestii modelu | nigdzie — niedeterministyczne; atrapa zwraca to, co test opisał |
| transformacja AI z surowego CSV | `bank-data-adapter` (**nietknięty**); tu wchodzi już plik kanoniczny |
| tabela priorytetów drabiny | 07, poziom unit — tam jest szybciej i dokładniej |
| filtr istotności i tabela przejść sagi | 04, poziom unit — czysta funkcja nie potrzebuje Dockera |
| atestacja i prognoza jako takie | `vidulum-cashflow-tests` — to sprawy cashflow, nie ingestion; tu sprawdzamy tylko, że import ich nie psuje |

## Uruchamianie

```bash
./mvnw test -pl :vidulum-ingestion-tests          # cały przepływ, Docker wymagany
./mvnw test -pl :vidulum-ingestion-tests -Dtest=IngestionHappyPathTest
./mvnw test -f vidulum-app-ingestion/pom.xml      # wszystkie moduły rodziny, BEZ Dockera
```

**Przed każdym uruchomieniem: `docker ps`.** Zostawiony `docker-compose` głodzi Testcontainers, a
objawy wyglądają jak regresja — rejestracje odpowiadające 500, `TimeoutException: Timed out waiting
for a node assignment`, testy po kilka minut. Wtedy
`docker-compose -f docker-compose-final.yml down -v`, nie debugowanie kodu.

**Oczekiwany czas:** ~2–3 min na pełny moduł (jedno podniesienie kontekstu, kontenery współdzielone
przez klasy bazowe). Jeśli przekroczy 5 min, to znak, że asercja zjechała za wysoko i należy ją
zepchnąć do modułu — tak jak `vidulum-cashflow` zjechało z ~500 testów i czterech minut do 96
testów w 5 sekundach.
