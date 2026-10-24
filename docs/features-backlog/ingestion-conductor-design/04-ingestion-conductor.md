# 04 · `vidulum-ingestion-conductor`

**Rola:** właściciel sagi i **jedynego rekordu procesu**. Decyduje, co jest następnym krokiem, komu
go zlecić, kiedy zapytać człowieka, a kiedy odpowiedzieć sobie samemu. Nie wykonuje żadnej pracy
merytorycznej — nie waliduje wierszy, nie rozstrzyga kategorii, nie pisze do cash flow.

**Posiada danych:** `ingestion_sessions` — i jest jej **jedynym piszącym**.

**Zależności (`pom.xml`):**

```xml
<dependency>vidulum-shared-kernel</dependency>
<dependency>vidulum-ingestion-conductor-api</dependency>   <!-- własny protokół -->
<dependency>vidulum-ingestion-canonical</dependency>
<dependency>vidulum-ingestion-intake</dependency>          <!-- parser, in-process, liść -->
<dependency>vidulum-cashflow-api</dependency>              <!-- TYLKO do czytania: exists, skeletonOf -->
<dependency>vidulum-ingestion-staging-api</dependency>     <!-- TYLKO do czytania: podgląd wierszy w GET -->

<dependency>spring-boot-starter-data-mongodb</dependency>  <!-- persystuje sesję -->
<dependency>spring-boot-starter-webmvc</dependency>        <!-- ma endpointy /api/v2/ingestion -->
<dependency>spring-boot-starter-validation</dependency>
<dependency>spring-boot-starter-security</dependency>      <!-- userId z JWT, nigdy z body -->
<dependency>spring-boot-starter-classic</dependency>
<dependency>spring-boot-jackson2</dependency>
<dependency>spring-kafka</dependency>                      <!-- wysyła komendy, czyta odpowiedzi -->
```

**Czego w tym pomie nie ma i to jest wymowne:** żadnego workera, żadnego
`vidulum-bank-data-ai-categorization-api`, żadnego `spring-ai`. Conductor nie wie, kto klasyfikuje.

## Co moduł zawiera

```
ingestion_conductor/
├── IngestionRestController                 /api/v2/ingestion — 5 endpointów
├── IngestionDto                            DTO wejścia i widoku sesji
├── saga/
│   ├── IngestionSaga                       CZYSTA funkcja: (faza, StepReply, polityka) → NextAction
│   ├── NextAction                          sealed: Dispatch | Ask | Finish | Compensate | Fail | Ignore
│   ├── StepDispatcher                      interfejs; KafkaStepDispatcher | InProcessStepDispatcher
│   ├── StepReplyListener                   implements StepReplyHandler, @KafkaListener
│   └── IngestionRecoveryScheduler          kroki bez odpowiedzi po deadline
├── policy/
│   ├── AutonomyPolicy                      wartość: progi, maxQuestions, TTL, fallback, poziom
│   └── MaterialityFilter                   kandydaci → pytania + auto-rozstrzygnięcia
├── domain/
│   ├── IngestionSession                    agregat (record + metody przejść)
│   ├── IngestionPhase, StepRecord, GroupRecord, DecisionRecord, Compensations, Failure
│   └── IngestionSessionRepository          interfejs domenowy
├── infrastructure/
│   ├── IngestionSessionMongoRepository, IngestionSessionRepositoryImpl
│   ├── entity/IngestionSessionEntity       @Document + @Version
│   ├── IngestionSessionDataCleaner         remove(), NIE dropCollection
│   └── IngestionKafkaConfig                topici komend + topic odpowiedzi + DLQ
├── app/commands/…                          6 komend CQRS
└── app/queries/…                           2 zapytania
```

## Kontrakt — HTTP

| metoda | adres | wejście | odpowiedź |
|---|---|---|---|
| POST | `/api/v2/ingestion` | multipart: `file`, `cashFlowId`, `transformationId?`, `policy?` | **202** `IngestionSessionJson` (faza `PARSED`) |
| GET | `/api/v2/ingestion/{sessionId}` | — | `IngestionSessionJson` — pełny stan |
| GET | `/api/v2/ingestion?cashFlowId=` | — | lista skrótów sesji |
| GET | `/api/v2/ingestion/{sessionId}/rows?status=` | — | wiersze sesji, stronicowo (przez `staging-api`) |
| POST | `/api/v2/ingestion/{sessionId}/decisions` | `[{decisionId, action, category, parent?, remember}]` | `IngestionSessionJson` — **nowy stan w tej samej odpowiedzi** |
| POST | `/api/v2/ingestion/{sessionId}/balance` | `{asOf, declaredBalance, createAdjustment}` | `IngestionSessionJson` z `balanceCheck` — różnicą i kandydatami |
| POST | `/api/v2/ingestion/{sessionId}/compensate` | `{reason}` | `IngestionSessionJson` (faza `COMPENSATED`) |

`userId` pochodzi z JWT, nigdy z body — jak w `/exchange-connection`. Sesja innego użytkownika
odpowiada **404, nie 403** (ta sama konwencja).

## Kontrakt — komendy i zapytania CQRS

```java
// komendy
record StartIngestionCommand(CashFlowId cashFlowId, UserId owner, byte[] csv,
                             String fileName, String transformationId,
                             AutonomyPolicy policy) implements Command {}
record RecordStepReplyCommand(StepReply reply) implements Command {}
record AnswerDecisionsCommand(SessionId sessionId, UserId answeredBy,
                              List<DecisionAnswer> answers) implements Command {}
record AutoResolveExpiredDecisionsCommand() implements Command {}        // z harmonogramu
record ConfirmBalanceCommand(SessionId sessionId, LocalDate asOf, Money declaredBalance,
                             boolean createAdjustment) implements Command {}
record CompensateIngestionCommand(SessionId sessionId, String reason) implements Command {}
record ResumeStalledStepsCommand() implements Command {}                 // z harmonogramu

// zapytania
record GetIngestionSessionQuery(SessionId sessionId) implements Query {}
record ListIngestionSessionsQuery(CashFlowId cashFlowId) implements Query {}
```

## Kontrakt — saga jako czysta funkcja

```java
/**
 * Bez repozytorium, bez Kafki, bez zegara w polu. To jest cała decyzyjność procesu
 * i dlatego da się ją przetestować tabelą, a nie scenariuszem z Dockerem.
 */
public final class IngestionSaga {
    public NextAction next(IngestionSession session, StepReply reply, Instant now);
    public NextAction start(IngestionSession session, Instant now);
}

public sealed interface NextAction
        permits Dispatch, Ask, Finish, Compensate, Fail, Ignore {}

record Dispatch(StepCommand command) implements NextAction {}
record Ask(List<DecisionRequest> requests, List<AutoResolved> autoResolved) implements NextAction {}
record Finish(IngestionPhase terminalPhase) implements NextAction {}
record Compensate(List<StepCommand> chain) implements NextAction {}   // UNIMPORT → UNLEARN → DISCARD
record Fail(Failure failure) implements NextAction {}
record Ignore(String reason) implements NextAction {}                 // duplikat, spóźniona odpowiedź
```

## Scenariusze testowe

### A. `IngestionSaga` — poziom unit, tabela przejść

| test | given → then |
|---|---|
| `shouldDispatchValidateAfterParse` | faza `PARSED` → `Dispatch(ValidateRowsCommand)` |
| `shouldDispatchResolvePassOneAfterValidate` | `RowsValidated` → `Dispatch(ResolveCategoriesCommand(pass=1))` |
| `shouldSkipClassifyWhenNothingIsOpen` | `CategoriesResolved(open=[])` → `Dispatch(ApplyResolutionCommand)`, **bez** `CLASSIFY` |
| `shouldDispatchClassifyOnlyForOpenGroups` | `open=29` z 38 → komenda nosi dokładnie 29 grup |
| `shouldDispatchResolvePassTwoAfterClassify` | `GroupsClassified` → `Dispatch(ResolveCategoriesCommand(pass=2, suggestions=29))` |
| `shouldSkipClassifyWhenItAlreadyFailed` | `StepReply(CLASSIFY, FAILED)` po `maxAttempts` → `Dispatch(ResolveCategoriesCommand(pass=2, suggestions=[]))`, **nie** `Fail` |
| `shouldAskWhenOpenGroupsRemainAfterPassTwo` | `open=8`, polityka `REVIEW_MATERIAL` → `Ask(5 pytań, 3 auto)` |
| `shouldNeverAskInAutopilot` | ta sama sytuacja, `AUTOPILOT` → `Dispatch(ApplyResolutionCommand)`, `decisions` puste |
| `shouldAskAboutEverythingInReviewAll` | `REVIEW_ALL` → `Ask(8, 0)` |
| `shouldDispatchLearnBeforeApplyingResolution` | odpowiedzi usera z `remember=true` → `Dispatch(LearnDecisionsCommand)`, a `APPLY_RESOLUTION` dopiero po `DecisionsLearned` |
| `shouldDispatchProvisionBeforeImport` | `ResolutionApplied` → `Dispatch(ProvisionCategoriesCommand)` |
| `shouldNotImportWhenProvisioningFailed` | `StepReply(PROVISION_CATEGORIES, FAILED)` → `Compensate`, **nigdy** `Dispatch(ImportRowsCommand)` |
| `shouldConfirmBalanceWithoutAskingWhenStatementCarriesIt` | plik z kolumną salda → `Dispatch(ConfirmBalanceCommand(source=FROM_STATEMENT))` **bez** pytania ownera |
| `shouldAskForBalanceOnlyWhenStatementDoesNotCarryIt` | plik bez kolumny salda → sesja zostaje w `IMPORTED` z `balanceCheck == null` i `source = DECLARED_BY_OWNER`; `asOf` = data ostatniej operacji **w pliku**. **Bez nowej fazy** — import się skończył i pieniądze są w cash flow, więc oczekiwanie na sumę kontrolną nie może wyglądać jak niedokończony import |
| `shouldNeverCreateAnAdjustmentWithoutConsent` | różnica ≠ 0 i `createAdjustment=false` → `balanceCheck` zapisany, `adjustmentCashChangeId` **null**, zero zapisów do cash flow |
| `shouldFinishImportedWhenNothingFailed` | `RowsImported(failed=[])` → `Finish(IMPORTED)` |
| `shouldFinishPartiallyImportedWhenSomeRowsFailed` | `RowsImported(failed=15)` → `Finish(PARTIALLY_IMPORTED)` |
| `shouldRejectWithoutRetryWhenCashFlowRefuses` | `StepReply(REJECTED, CASHFLOW_CLOSED)` → `Fail`, zero ponowień |
| `shouldRetryRetryableFailureUntilMaxAttempts` | `FAILED(retryable=true)` × 3 przy `maxAttempts=3` → `Dispatch` z `attempt+1`, a po trzeciej `Compensate` |
| `shouldIgnoreDuplicateReply` | ten sam `commandId` drugi raz → `Ignore` |
| `shouldIgnoreReplyForStaleStepId` | odpowiedź `RESOLVE` z `stepId` z passu 1, gdy trwa pass 2 → `Ignore` |
| `shouldIgnoreReplyForUnknownSession` | `sessionId` nieistniejący → `Ignore`, bez wyjątku |
| `shouldCoverEveryPhaseAndStatusCombination` | **parametryzowany po `IngestionPhase.values()` × `StepStatus.values()`** → żadna kombinacja nie zwraca `null` ani nie rzuca |

Ostatni test jest tym, który ma wartość za dziesięć poprzednich: trzy równoległe maszyny stanów
dzisiejszego modułu rozjeżdżają się dokładnie w kombinacjach, których nikt nie wypisał.

### B. `MaterialityFilter` i `AutonomyPolicy` — poziom unit

| test | given → then |
|---|---|
| `shouldAskAboutGroupsCoveringNinetyPercentOfValue` | 8 kandydatów o udziałach 0.36/0.17/0.12/0.09/0.08/0.04/0.02/0.01 → pytania o pierwszych **5**, reszta `IMMATERIAL` |
| `shouldRespectMaxQuestions` | 20 kandydatów po 0.05 → dokładnie `maxQuestions` pytań, choć pokrycie < 90 % |
| `shouldNotAskBelowConfidenceFloor` | sugestia 40 % → `FALLBACK` + `provisional`, **nie** pytanie |
| `shouldNotAskAtOrAboveAutoAccept` | sugestia 90 % → auto, 89 % → kandydat (granica zamrożona testem) |
| `shouldPreferHigherValueWhenSharesTie` | dwie grupy o równym udziale → stabilna kolejność (po `rowCount`, potem po kluczu) — bez niedeterminizmu w UI |
| `shouldRecordWhyEachGroupWasNotAsked` | po filtrze każda nieprzepytana grupa ma `AutoResolveReason` ∈ {IMMATERIAL, LOW_CONFIDENCE} |

### C. `IngestionSession` — poziom unit (agregat)

| test | given → then |
|---|---|
| `shouldNeverClearFailureOnceRecorded` | `fail(...)` → `compensate(...)` → `failure` **nadal obecne** (dzisiejszy `failImport()` zeruje `importJobId` dokładnie w tym momencie) |
| `shouldKeepStepHistoryAcrossRetries` | trzy próby `IMPORT` → trzy `StepRecord`, nie nadpisany jeden |
| `shouldRecordBothResolvePassesSeparately` | `RESOLVE` ① i ② → dwa `StepRecord` o różnych `stepId` |
| `shouldRefuseAnswerForClosedDecision` | odpowiedź na już auto-rozstrzygniętą decyzję → `BusinessException`, decyzja bez zmian |
| `shouldApplyRecommendationAtTtl` | zegar przesunięty za `autoResolveAt` → `recommended` zastosowane, `autoResolved == TTL` |
| `shouldRefuseCompensationAfterTerminalPhase` | faza `COMPENSATED` → kolejna kompensacja odrzucona |
| `shouldComputeCountersFromRepliesOnly` | ustawione z trzech odpowiedzi → `counters` zgodne całoobiektowo |
| `shouldExposeOneStatusNotThree` | test strukturalny, refleksyjny → brak drugiego pola statusu w encji sesji |

### D. Component — prawdziwy handler + `InMemoryIngestionSessionRepository`

| test | given → then |
|---|---|
| `shouldStartSessionAndDispatchValidate` | 402 wiersze → sesja `PARSED` zapisana, `StepDispatcher` dostał `ValidateRowsCommand` z 402 wierszami; asercja **całoobiektowa** (konstruktor, nie builder) |
| `shouldAnswerFourOhFourWhenCashFlowDoesNotExist` | `cashFlowApi.exists == false` → `CashFlowNotFoundException` z `cashflow-api`, **sesja nie powstaje** |
| `shouldRefuseWhenCashFlowIsClosed` | skeleton `CLOSED` → `BusinessException(CASHFLOW_CLOSED)`, sesja nie powstaje |
| `shouldReturnTheSameSessionForTheSameFile` | ten sam plik dwa razy → ten sam `sessionId`, jedna sesja, jedna komenda `VALIDATE` |
| `shouldRefuseSecondConcurrentSessionInSetup` | istnieje sesja niekończowa dla tego cash flow w SETUP → **409** (strażnik luki z §25 dokumentu nadrzędnego) |
| `shouldAllowSecondSessionWhenFirstIsTerminal` | pierwsza `COMPENSATED` → druga startuje |
| `shouldPersistDecisionsAndDispatchLearn` | 5 odpowiedzi → `decisions[].answer/answeredBy/answeredAt` zapisane, `LearnDecisionsCommand` wysłana, faza `RESOLVED` |
| `shouldReturnNewStateInTheSameHttpResponse` | component test na kontrolerze → ciało odpowiedzi `POST /decisions` **nie zawiera** `AWAITING_DECISION` |
| `shouldResumeStepWithTheSameCommandId` | krok `IMPORT` bez odpowiedzi po `deadline` → ten sam `commandId`, `attempt+1` |
| `shouldCompensateInOrder` | `PARTIALLY_IMPORTED` → `UNIMPORT`, po odpowiedzi `UNLEARN`, potem `DISCARD_ROWS` — kolejność zamrożona |
| `shouldRefuseCompensationWhenCashFlowLeftSetup` | skeleton `OPEN` → odmowa z powodem `ATTESTED`, nic nie wysłane |
| `shouldSerialiseSessionViewWholeObject` | `IngestionSessionJson` → `usingRecursiveComparison` z obiektem z konstruktora; czas z wstrzykniętego `Clock`, zero pól ignorowanych |

### E. Czego **nie** testujemy na tym poziomie

| rzecz | gdzie | dlaczego |
|---|---|---|
| czy komenda naprawdę dotarła na topic | 09 integration | `StepDispatcher` jest tu atrapą — i to jest cel |
| TTL, indeksy, `@Version` jako realny konflikt | 09 integration | adnotacja nie dowodzi indeksu (CLAUDE.md) |
| 404 jako **kod HTTP** | 09 integration | tu dowodzimy wyjątku, tam odwzorowania |
| DLQ, wiadomość trująca | 09 integration | wymaga brokera |

**Czas wykonania modułu:** cel < 5 s, bez Dockera — tak jak `vidulum-cashflow` robi dziś 96 testów
w 5 sekundach.

## Reguły architektoniczne (sprawdzane w 09)

1. Nic w tym module nie zależy od **implementacji** żadnego workera (`ingestion-staging`,
   `-classification`, `-category-resolution`, `-cashflow-writer`). Jedyna dozwolona krawędź to
   `ingestion-staging-api` (R), użyta wyłącznie w zapytaniu podglądu wierszy.
2. Nic nie zależy od `bank_data_ai_categorization` ani od `spring-ai` — **conductor nie wie o AI**.
3. Z `cashflow-api` używa **wyłącznie** `exists` i `skeletonOf`; `createCategory`,
   `importHistorical`, `rollbackImport` są zakazane — to robota writera (reguła na poziomie wywołań
   metod, nie pakietów).
4. `IngestionSaga` nie zależy od Springa, Mongo ani Kafki.
