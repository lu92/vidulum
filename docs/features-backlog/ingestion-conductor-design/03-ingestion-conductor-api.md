# 03 · `vidulum-ingestion-conductor-api`

**Rola:** **protokół sagi**. Komendy, które conductor wysyła, odpowiedzi, które przyjmuje, i typy
wartości, którymi obie strony mówią o grupach i kategoriach.

**Posiada danych:** żadnych. Bez Springa, bez `@HttpExchange`, bez implementacji.

**Zależności (`pom.xml`):**

```xml
<dependency>vidulum-shared-kernel</dependency>        <!-- IntegrationEvent, ModuleEventHandler, Money, CategoryName -->
<dependency>vidulum-ingestion-canonical</dependency> <!-- CanonicalRow, GroupKey -->
<dependency>vidulum-cashflow-api</dependency>        <!-- Type -->
```

## To jest `ModuleSpi`, nie `ModuleApi` — i to korekta wobec §7

W dokumencie nadrzędnym napisałem, że ten artefakt zawiera też `IngestionSagaApi extends ModuleApi`.
Przy rozpisywaniu okazało się, że **nie powinien — jeszcze**:

> „`vidulum-pnl` i `vidulum-risk` nie mają `-api`, bo nikt ich o nic nie pyta. Kontrakt powstaje,
> gdy istnieje konsument." — CLAUDE.md

Conductora dziś nie pyta o nic żaden moduł; pyta go **frontend**, a to jest REST, nie kontrakt
modułowy. Więc:

- **teraz:** ten artefakt zawiera wyłącznie protokół kroków — conductor **deklaruje gniazda**,
  workerzy je **implementują**. Kształtem to `ModuleSpi` (jak `ExchangeAdapter` w
  `vidulum-exchange-api`, implementowany przez `vidulum-okx`).
- **gdy pojawi się konsument** (alerts z P4, albo recurring-rules chcące wiedzieć o imporcie) —
  dopisuje się `IngestionConductorApi extends ModuleApi` z metodami, o które ten konsument
  rzeczywiście pyta, i ani jedną więcej.

## Kontrakt — wspólny kształt komendy i odpowiedzi

```java
/** Każda komenda kroku. Trzy pola są mechanizmem idempotencji i odzysku, nie metadanymi. */
public interface StepCommand extends IntegrationEvent {
    String sessionId();      // klucz partycji - kolejność per sesja
    String commandId();      // ponowienie używa TEGO SAMEGO id
    int attempt();
    IngestionStep step();
}

public enum IngestionStep {
    PARSE, VALIDATE, RESOLVE, CLASSIFY, LEARN, APPLY_RESOLUTION,
    PROVISION_CATEGORIES, IMPORT, MARK_IMPORTED,
    UNIMPORT, UNLEARN, DISCARD_ROWS
}

/** Jedna koperta odpowiedzi dla wszystkich kroków, jeden topic. */
public record StepReply(
        String sessionId,
        String stepId,            // identyfikuje WYSTĄPIENIE kroku - RESOLVE jest dwa razy
        IngestionStep step,
        String commandId,         // po tym conductor odrzuca duplikat
        StepStatus status,        // SUCCEEDED | PARTIAL | FAILED | REJECTED
        StepOutcome outcome,      // null dla FAILED/REJECTED
        Failure failure,          // null dla SUCCEEDED/PARTIAL
        Instant occurredAt) implements IntegrationEvent {}

public enum StepStatus { SUCCEEDED, PARTIAL, FAILED, REJECTED }

/**
 * FAILED vs REJECTED to różnica, od której zależy, czy saga ponawia:
 *   FAILED   - coś się nie udało (timeout, 429, konflikt wersji); retryable decyduje o ponowieniu
 *   REJECTED - tak nie wolno (cash flow CLOSED, miesiąc ATTESTED); ponawianie nie pomoże
 */
public record Failure(ErrorCode code, String message, boolean retryable, IngestionStep step) {}

public sealed interface StepOutcome
        permits RowsValidated, CategoriesResolved, GroupsClassified, DecisionsLearned,
                ResolutionApplied, CategoriesProvisioned, ImportProgress, RowsImported,
                ImportMarked, ImportCompensated, LearnedForgotten, RowsDiscarded {}
```

## Kontrakt — komendy i gniazda, krok po kroku

Każdy krok ma ten sam kształt: `record ...Command implements StepCommand` + `interface ...Handler
extends ModuleEventHandler` ze stałymi `TOPIC` i `CONTAINER_FACTORY`. Wzorzec jest dokładnie ten z
`TradeConfirmedHandler` w `vidulum-trading-api`.

```java
public interface ValidateRowsHandler extends ModuleEventHandler {
    String TOPIC = "ingestion.cmd.validate";
    String CONTAINER_FACTORY = "ingestionValidateContainerFactory";
    void onValidateRows(ValidateRowsCommand command);
}

public record ValidateRowsCommand(
        String sessionId, String commandId, int attempt,
        CashFlowId cashFlowId, UserId owner,
        int chunkIndex, int chunkCount,          // jedyna komenda dzielona na części
        List<CanonicalRow> rows) implements StepCommand { }

public record RowsValidated(
        int total, int valid, int duplicate, int rejected,
        List<GroupSummary> groups,               // ~38 - to jest prawdziwy wynik tego kroku
        List<RejectedRow> rejectedRows,          // lineNo + ErrorCode + komunikat
        List<DuplicateRow> duplicates            // bankTransactionId + istniejący CashChangeId
) implements StepOutcome {}
```

| krok | komenda (pola poza trzema wspólnymi) | wynik |
|---|---|---|
| `VALIDATE` | `cashFlowId`, `owner`, `chunkIndex/Count`, `rows[]` | `RowsValidated` |
| `RESOLVE` | `cashFlowId`, `groups[]`, `categories: CategorySnapshot`, `pass`, `suggestions[]` | `CategoriesResolved(resolved[], open[], plan)` |
| `CLASSIFY` | `cashFlowId`, `language`, `openGroups[]`, `categories` | `GroupsClassified(suggestions[], cost)` |
| `LEARN` | `cashFlowId`, `directives[]` | `DecisionsLearned(mappingIds[], patternIds[])` |
| `APPLY_RESOLUTION` | `groupMappings: Map<GroupKey, Resolution>` | `ResolutionApplied(rowsResolved)` |
| `PROVISION_CATEGORIES` | `cashFlowId`, `plan: CategoryPlan` | `CategoriesProvisioned(created[], existed[])` |
| `IMPORT` | `cashFlowId`, `batchSize`, `progressEvery` | `ImportProgress` (PARTIAL) → `RowsImported(pairs[], failed[])` |
| `MARK_IMPORTED` | `pairs: List<ImportedPair>` | `ImportMarked(count)` |
| `UNIMPORT` | `cashFlowId`, `cashChangeIds[]`, `categoryNames[]` | `ImportCompensated(deleted, categoriesDeleted)` |
| `UNLEARN` | `cashFlowId`, `patternIds[]`, `mappingIds[]` | `LearnedForgotten(count)` |
| `DISCARD_ROWS` | — | `RowsDiscarded(count)` |

## Kontrakt — typy wartości

```java
/** Grupa: jednostka pracy i decyzji. 38 zamiast 402. */
public record GroupSummary(
        GroupKey key, Type type, int rowCount, Money total,
        List<String> samples,          // maks. 3 nazwy transakcji, do pokazania człowiekowi
        String merchant, String bankCategory, String classificationWord,
        boolean selfTransfer,
        double valueShare) {}          // |total| / Σ|total| - wejście filtra istotności

public record Resolution(
        CategoryName category, CategoryName parent, Type type,
        ResolutionSource source, int confidence,
        boolean provisional, String reason) {}

public enum ResolutionSource { SELF_TRANSFER, DIRECT, PATTERN, MAPPING, CLASSIFICATION, AI, USER, FALLBACK }

/** Kolejność listy JEST kontraktem: rodzic przed dzieckiem. */
public record CategoryPlan(List<CategoryPlanEntry> entries) {
    CategoryPlan validated();     // rzuca, gdy dziecko stoi przed rodzicem
}
public record CategoryPlanEntry(CategoryName name, CategoryName parent, Type type) {}

public record Suggestion(GroupKey key, CategoryName category, CategoryName parent,
                         Type type, int confidence, String reason) {}

/** Opcja decyzji - JEDNA struktura dla sugestii AI, wzorca z pamięci i wyboru człowieka. */
public record Option(MappingAction action, CategoryName category, CategoryName parent,
                     Type type, int confidence, ResolutionSource source, String reason) {}

public enum MappingAction { MAP_TO_EXISTING, CREATE_NEW, CREATE_SUBCATEGORY, MAP_TO_UNCATEGORIZED }

/** Co człowiek kazał zapamiętać. */
public record MappingDirective(GroupKey key, String bankCategory, String pattern,
                               CategoryName category, CategoryName parent, Type type,
                               int confidence, boolean remember) {}

/** Drzewo kategorii cash flow w kształcie, którego potrzebują workerzy - nie cała CashFlowSkeletonView. */
public record CategorySnapshot(Set<TypedCategory> categories) {
    Optional<CategoryName> findIgnoringCase(String rawName, Type type);
    CategoryName parentOf(CategoryName name, Type type);
    boolean contains(CategoryName name, Type type);
}
```

**Dlaczego `CategorySnapshot`, a nie `CashFlowSkeletonView` na topicu:** skeleton nosi
`cashFlowId`, `owner`, `phase`, `activePeriod`, `startPeriod` i okno importu — workerzy kategorii
nie potrzebują żadnego z nich, a `ImportWindow` to reguła cashflow, która nie ma prawa krążyć po
topicach ingestion. Conductor buduje snapshot ze skeletonu w jednym miejscu.

## Scenariusze testowe — poziom unit, refleksyjne i kontraktowe

Wzorzec istnieje: `TransactionCategorizationApiContractTest` i `CashFlowSkeletonViewTest`.

| test | given → then |
|---|---|
| `everyStepCommandIsARecordImplementingStepCommand` | refleksja po pakiecie komend → każda klasa jest `record`, implementuje `StepCommand` i `IntegrationEvent` |
| `everyStepHasExactlyOneHandlerInterface` | dla każdej stałej `IngestionStep` istnieje dokładnie jeden interfejs `*Handler extends ModuleEventHandler` |
| `everyHandlerDeclaresTopicAndContainerFactory` | refleksja → obie stałe istnieją, są `String`, niepuste |
| `topicsAreUniqueAcrossHandlers` | zbiór wszystkich `TOPIC` → brak duplikatów (dwa kroki na jednym topicu = worker czyta cudze komendy) |
| `topicsFollowTheNamingConvention` | każdy `TOPIC` pasuje do `ingestion.cmd.[a-z-]+` albo jest `ingestion.reply` |
| `stepOutcomePermitsExactlyTheOutcomesOfDeclaredSteps` | `StepOutcome.class.getPermittedSubclasses()` vs mapa krok→wynik → **wyczerpująco**, bez luk i bez nadmiaru |
| `everyFailureCarriesAnErrorCode` | `Failure` nie da się zbudować z `null` kodem → `NullPointerException` z komunikatem |
| `aRejectedFailureIsNeverRetryable` | `new Failure(code, msg, retryable=true, step)` w kontekście `REJECTED` → `StepReply` odrzuca taką kombinację w konstruktorze |
| `moneySurvivesJacksonRoundTrip` | `Money.of(new BigDecimal("1234.50"), "PLN")` → serializacja i deserializacja konfiguracją projektu → **`equals`**, nie tylko `compareTo` (lekcja `BigDecimal.valueOf(double)`) |
| `zonedDateTimeKeepsZoneAndTimeThroughRoundTrip` | `2023-01-13T14:32:00+01:00[Europe/Warsaw]` → po round-tripie ta sama chwila **i** strefa (lekcja „czas i strefa: stracone bezpowrotnie") |
| `everyCommandSurvivesRoundTrip` | parametryzowany po wszystkich komendach: instancja → JSON → instancja, asercja `usingRecursiveComparison` |
| `aValidatedPlanRefusesChildBeforeParent` | `CategoryPlan` z „Subskrypcje" przed „Rozrywka" → `validated()` rzuca; odwrotna kolejność → przechodzi |
| `aPlanAllowsTheSameNameInBothDirections` | „Transfer" INFLOW + „Transfer" OUTFLOW → dwie pozycje, bez kolizji |
| `categorySnapshotMatchesIgnoringCaseWithinDirection` | „zakupy kartą" szukane jako OUTFLOW → trafia; jako INFLOW → `Optional.empty()` |
| `groupSummarySharesSumToOne` | lista 38 grup z sumami → `Σ valueShare` ≈ 1.0 (tolerancja 1e-9) |
| `optionIsTheSameShapeForEverySource` | opcje z `AI`, `PATTERN`, `USER` → ten sam typ, te same pola wypełnione (jedna struktura na decyzję, nie trzy) |

Trzy z tych testów są warte nazwania wprost, bo każdy pilnuje udokumentowanej wpadki tego repo:
`moneySurvivesJacksonRoundTrip` (skala `BigDecimal`), `zonedDateTimeKeepsZoneAndTimeThroughRoundTrip`
(port, który zubożył typ), `stepOutcomePermitsExactlyTheOutcomesOfDeclaredSteps` (wyczerpujący
`switch` plus test po `values()`).

**Czas wykonania:** < 2 s.

## Czego tu nie ma

| rzecz | gdzie | dlaczego |
|---|---|---|
| `IngestionPhase`, `AutonomyPolicy`, encja sesji | 04 conductor | to stan właściciela sagi, nie protokół |
| `IngestionConductorApi extends ModuleApi` | **nigdzie, na razie** | nikt nie pyta; kontrakt powstaje z konsumentem |
| implementacje handlerów, `@KafkaListener`, `KafkaTemplate` | workerzy i conductor | kontrakt nie zna Springa |
| DTO REST (`IngestionSessionJson`) | 04 conductor | powierzchnia HTTP to nie protokół sagi |
