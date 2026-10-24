# 07 · `vidulum-ingestion-category-resolution`

**Rola:** jedyne miejsce, w którym żyje **drabina rozstrzygania kategorii**, i jedyne, które
pamięta, co człowiek już zdecydował. To biznesowe serce pipeline'u.

**Posiada danych:** `ingestion_category_mappings` — jedyny piszący.

**Zależności (`pom.xml`):**

```xml
<dependency>vidulum-ingestion-conductor-api</dependency>
<dependency>vidulum-ingestion-canonical</dependency>
<dependency>vidulum-cashflow-api</dependency>                      <!-- Type, CategoryName -->
<dependency>vidulum-bank-data-ai-categorization-api</dependency>   <!-- matchLearned, learn, forget -->
<dependency>spring-boot-starter-data-mongodb</dependency>
<dependency>spring-kafka</dependency>
<dependency>spring-boot-starter-classic</dependency>
```

Bez `webmvc` (zero endpointów — mapowania wystawia conductor, jeśli UI ich zapotrzebuje) i bez
`spring-ai`.

## Dlaczego ten moduł w ogóle istnieje osobno

Dzisiejsza drabina stoi w **dwóch kopiach**: `StageTransactionsCommandHandler` (763 linie) i
`RevalidateStagingCommandHandler` (436 linii). Kopie już się rozjechały — ta druga stosuje wzorce
jako **priorytet 1 przed** dopasowaniem bezpośrednim, żeby móc „re-kategoryzować" wiersze już
zmapowane, czego pierwsza nie robi. Dwie reguły dla jednej decyzji to definicja tego defektu, który
F66 usunęło na poziomie dopasowywania wzorców (`matchLearned`) i który tutaj trzeba usunąć na
poziomie całej drabiny.

## Kontrakt — trzy gniazda

| gniazdo | kiedy | co zapisuje |
|---|---|---|
| `ResolveCategoriesHandler` | `RESOLVE` ① i ② | **nic** — rozstrzygnięcie jest odpowiedzią |
| `LearnDecisionsHandler` | po decyzjach człowieka | `ingestion_category_mappings` + `categorizationApi.learn` |
| `ForgetLearnedHandler` | kompensacja | usuwa mapowania + `categorizationApi.forget` |

`forget` jest dziś **metodą kontraktu bez konsumenta** — zero wywołań produkcyjnych w całym repo
(pomiar w [pliku 10, §2](10-granice-artefaktow.md)). Krok `UNLEARN` jest jej pierwszym: skoro
transakcje, które skategoryzowała, są wycofywane, wzorce wyuczone z akceptacji człowieka są tą samą
decyzją i idą razem z nimi.

```java
public final class CategoryLadder {

    /**
     * JEDNA kopia reguły. Kolejność priorytetów JEST kontraktem i jest zamrożona testem.
     *
     * -1 SELF_TRANSFER   grupa oznaczona przez staging po IBAN-ie konta własnego
     *  0 DIRECT          categories.findIgnoringCase(bankCategory, type)
     *  1 PATTERN         matchLearned — tylko gdy kategoria docelowa ISTNIEJE w cash flow
     *  2 MAPPING         ingestion_category_mappings[(bankCategory, type)]
     *  3 CLASSIFICATION  słowo z CSV: BANK_FEE → "Opłaty bankowe" / "Zarządzanie kontem"
     *  4 AI              sugestia, jeśli pass==2 i pewność >= autoAccept
     *  5 (nic)           grupa trafia do open[] — pytanie albo fallback decyduje conductor
     */
    public LadderResult resolve(List<GroupSummary> groups,
                                CategorySnapshot categories,
                                List<CategoryMapping> mappings,
                                PatternMatches learned,
                                List<Suggestion> suggestions,
                                int pass,
                                Thresholds thresholds);
}

public record LadderResult(Map<GroupKey, Resolution> resolved,
                           List<OpenGroup> open,          // + kandydaci z pewnością 50..90
                           CategoryPlan plan) {}

public record OpenGroup(GroupSummary group, List<Option> options, Option recommended, String reason) {}

/** Progi przychodzą z polityki conductora — drabina ich nie wymyśla. */
public record Thresholds(int autoAccept, int askFloor) {}
```

**Granica odpowiedzialności, którą warto powtórzyć:** drabina odpowiada *co to za kategoria*;
**czy pytać człowieka** decyduje `MaterialityFilter` w conductorze (04). Dlatego `Thresholds` są
parametrem, a nie stałą w tym module.

## Kontrakt — model mapowania

```java
public record CategoryMapping(
        MappingId mappingId, CashFlowId cashFlowId,
        String bankCategoryName,                   // jak to napisał bank
        CategoryName targetCategoryName,
        CategoryName parentCategoryName,           // null = kategoria główna
        Type categoryType,
        MappingAction action,
        Integer confidence,                        // skąd przyszła pewność, gdy z AI
        ResolutionSource learnedFrom,              // USER | AI | PATTERN — kto to nauczył
        ZonedDateTime createdAt, ZonedDateTime updatedAt,
        Long version) {}                           // @Version — zapis warunkowy (F57)
```

## Scenariusze testowe

### A. Drabina — unit, i to jest najważniejsza tabela w całym projekcie

| test | given → then |
|---|---|
| `shouldPreferSelfTransferOverEverythingElse` | grupa `SELF_TRANSFER`, która **jednocześnie** ma mapowanie, wzorzec i sugestię AI → `source == SELF_TRANSFER` |
| `shouldPreferDirectMatchOverPattern` | bankCategory == istniejąca kategoria, a wzorzec wskazuje inną → `source == DIRECT` |
| `shouldPreferPatternOverMapping` | wzorzec i mapowanie wskazują różne kategorie → `source == PATTERN` |
| `shouldPreferMappingOverClassificationWord` | grupa `BANK_FEE` z własnym mapowaniem → `source == MAPPING` |
| `shouldUseClassificationWordWhenNothingElseMatches` | `BANK_FEE`, pusta pamięć, brak mapowań → „Opłaty bankowe" / „Zarządzanie kontem", `source == CLASSIFICATION` |
| `shouldCoverEveryPriorityExactlyOnce` | **parametryzowany po `ResolutionSource.values()`** → dla każdego źródła istnieje sytuacja, w której wygrywa; żadne nie jest martwe |
| `shouldIgnorePatternWhenTargetCategoryDoesNotExist` | wzorzec → „Subskrypcje", której cash flow nie ma → grupa idzie niżej (dzisiejsza reguła) |
| `shouldMatchDirectIgnoringCase` | bankCategory „zakupy kartą", kategoria „Zakupy kartą" → trafia |
| `shouldNotMatchAcrossDirections` | kategoria „Transfer" istnieje jako INFLOW, grupa jest OUTFLOW → nie trafia |
| `shouldNotUseAiSuggestionsInPassOne` | `pass=1` z wypełnioną listą sugestii → sugestie **ignorowane**, grupa w `open[]` |
| `shouldAcceptAiAtOrAboveAutoAccept` | sugestia 90 przy `autoAccept=90` → `resolved`, `source == AI` |
| `shouldOfferCandidateBetweenFloorAndAutoAccept` | sugestia 70 → `open[]` z `options` i `recommended`, **nie** `resolved` |
| `shouldNotOfferBelowFloor` | sugestia 40 → `open[]` z `recommended == FALLBACK` i powodem `LOW_CONFIDENCE` |
| `shouldAskLearnedPatternsOncePerPass` | 38 grup → **1** wywołanie `matchLearned` z batchem 38, zero per grupa |

### B. Plan kategorii — unit

| test | given → then |
|---|---|
| `shouldPutParentsBeforeChildren` | rozstrzygnięcia „Subskrypcje"/„Rozrywka", gdzie brak obu → `plan` ma „Rozrywka" **przed** „Subskrypcje" |
| `shouldAddMissingParentEvenWhenOnlyChildWasSuggested` | AI proponuje tylko dziecko → `plan` zawiera rodzica |
| `shouldNotPlanWhatAlreadyExists` | kategoria w `CategorySnapshot` → nieobecna w planie |
| `shouldPlanTheSameNameTwiceForTwoDirections` | „Transfer" potrzebny jako INFLOW i OUTFLOW → dwie pozycje |
| `shouldNotDuplicatePlanEntries` | 12 grup celujących w tę samą nową kategorię → jedna pozycja |
| `shouldProducePlanOfElevenForTheGoldenFile` | scenariusz z §21 → `plan.size() == 11`, kolejność zwalidowana `plan.validated()` |

### C. Nauka i zapominanie — component (prawdziwy handler + in-memory repo + atrapa kontraktu)

| test | given → then |
|---|---|
| `shouldStoreMappingAndLearnPattern` | dyrektywa `remember=true` → 1 dokument mapowania + `learn` wołane raz z `PatternSource.USER` |
| `shouldNotStoreAnythingWhenRememberIsFalse` | `remember=false` → zero zapisów, zero wywołań `learn` |
| `shouldStoreParentForCreateSubcategory` | `CREATE_SUBCATEGORY("Subskrypcje","Rozrywka")` → mapowanie z `parentCategoryName` |
| `shouldUpdateInsteadOfDuplicating` | ta sama `(bankCategory, type)` druga raz → **jeden** dokument, `confidence` i `updatedAt` zaktualizowane, `createdAt` bez zmian |
| `shouldBeIdempotentOnCommandRetry` | ta sama komenda z tym samym `commandId` dwa razy → jedno mapowanie, jedno `learn` |
| `shouldReportMappingAndPatternIdsForCompensation` | → `DecisionsLearned(mappingIds, patternIds)` niepuste i zgodne z zapisanymi |
| `shouldForgetOnlyThisCashFlow` | mapowania dwóch cash flow → `forget` usuwa tylko swoje, `categorizationApi.forget` wołane raz |
| `shouldReplyFailedWhenEngineRefusesToLearn` | kontrakt rzuca → `StepReply(FAILED, retryable=true)`, mapowania **nie** zapisane (albo wycofane) |

### D. Component — pełny krok `RESOLVE`

| test | given → then |
|---|---|
| `shouldResolveNineGroupsOnTheFirstPassOfTheGoldenFile` | 38 grup, pusta pamięć, cash flow z 2 kategoriami → `resolved==9`, `open==29`; asercja całoobiektowa na `CategoriesResolved` |
| `shouldResolveEverythingOnSecondUpload` | te same 38 grup, ale mapowania i wzorce po pierwszym imporcie → `open` **puste** (to jest dowód na „drugi upload nie pyta o nic") |
| `shouldNotReadAnyRow` | atrapa `IngestionRowsApi` wstrzyknięta → **zero** wywołań (ten moduł nie zna wierszy) |
| `shouldReplyFailedWhenLearnedPatternsAreUnavailable` | `matchLearned` rzuca → `FAILED(retryable=true)`; ponowienie przechodzi |

### E. Czego nie testujemy tutaj

| rzecz | gdzie |
|---|---|
| czy pytanie trafiło do człowieka | 04 (polityka) i 09 (HTTP) |
| czy kategorie z planu powstały | 08 i 09 |
| `@Version` jako realny konflikt zapisu | 09 |

**Czas wykonania:** cel < 4 s na ~50 testach.

## Reguły architektoniczne (09)

1. Nie zależy od `staging` (ani od `staging-api`), `classification`, `cashflow-writer`, conductora.
2. Z `cashflow-api` używa **wyłącznie typów** (`Type`, `CategoryName`) — **żadnego wywołania**
   `CashFlowModuleApi`. Drzewo kategorii przychodzi w komendzie jako `CategorySnapshot`.
3. `CategoryLadder` nie zależy od Springa, Mongo ani Kafki — czysta funkcja, dlatego tabela
   priorytetów jest testem jednostkowym.
4. Żaden inny moduł nie zna `CategoryMappingEntity`.
