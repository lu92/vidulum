# 06 · `vidulum-ingestion-classification`

**Rola:** zapytać tego, kto klasyfikuje, i zapamiętać odpowiedź, bo kosztowała pieniądze. **Liść
pipeline'u** — nikt w rodzinie od niego nie zależy, co jest warunkiem wymienialności klasyfikatora.

**Posiada danych:** `ingestion_classifications` — cache odpowiedzi, jeden dokument na sesję.

**Zależności (`pom.xml`):**

```xml
<dependency>vidulum-ingestion-conductor-api</dependency>
<dependency>vidulum-ingestion-canonical</dependency>
<dependency>vidulum-ingestion-staging-api</dependency>             <!-- TYLKO czytanie wierszy, patrz niżej -->
<dependency>vidulum-bank-data-ai-categorization-api</dependency>   <!-- kontrakt z F66, bez zmian -->
<dependency>spring-boot-starter-data-mongodb</dependency>          <!-- cache -->
<dependency>spring-kafka</dependency>
<dependency>spring-boot-starter-classic</dependency>
```

**Bez `spring-ai`.** Model jest sprawą `bank-data-ai-categorization`; ten moduł zna wyłącznie
kontrakt. Warto to powiedzieć wprost, bo dzisiejszy `bank-data-ingestion/pom.xml` **nadal** ma
`spring-ai-starter-model-openai` z komentarzem „AI categorization", choć silnik wyjechał w F66 —
zależność została po przeprowadzce. Nowy moduł nie ma prawa jej odziedziczyć.

**Bez `webmvc`:** zero endpointów.

## Dlaczego ten moduł czyta wiersze, choć pracujemy na grupach

**Poprawka z audytu granic** ([plik 10, §4](10-granice-artefaktow.md)). W dokumencie nadrzędnym
napisałem, że `ClassifyGroupsCommand` nosi 29 grup zamiast 402 wierszy. Kontrakt F66 tego nie
przyjmie: `CategorizationRequest` bierze `List<TransactionToCategorize>` — **transakcje** — a silnik
grupuje je sam po `effectiveMerchant()`. Wysłanie syntetycznych reprezentantów grup zrobiłoby z jego
grupowania operację jałową i dałoby `transactionCount == 1` w sugestiach: pola wypełnione nieprawdą,
czyli ten sam kształt defektu, który F67 usunęło razem z `aiTokensUsed`.

Dlatego komenda nosi **klucze grup**, a ten moduł czyta ich wiersze przez
`IngestionRowsApi.rowsOfGroup(...)` — tylko do czytania, nigdy nie pisząc. Odwzorowanie odpowiedzi
wraca na grupy naturalnie, bo silnik **już** odpowiada per grupa: `ContextMapping` po
(pattern, bankCategory), `AutoCategorizableSuggestion` po słowie klasyfikacji,
`BankCategoryFallback` po bankCategory.

Alternatywa — metoda grupowa w kontrakcie F66 (`suggestForGroups`) — jest odłożona do momentu, gdy
rozmiar żądania zacznie przeszkadzać; wtedy to pomiar, nie przewidywanie.

## Kontrakt — jedno gniazdo

```java
// implements ClassifyGroupsHandler z conductor-api
@KafkaListener(groupId = "ingestion-classification", topics = TOPIC, containerFactory = CONTAINER_FACTORY)
void onClassifyGroups(ClassifyGroupsCommand command);
```

Co robi, w czterech zdaniach:

1. Liczy `requestHash` z (`cashFlowId`, zbiór `GroupKey` z `openGroups`, `language`,
   `categories`) — jeśli dokument o tym skrócie jest w cache, **odpowiada z niego i nie woła
   kontraktu**.
2. Buduje `CategorizationRequest`, przekładając `GroupSummary` na `TransactionToCategorize` —
   jedenaście wartości, tyle, ile silnik faktycznie czyta.
3. Woła `TransactionCategorizationApi.suggestFor(...)` **raz**, na całą paczkę grup.
4. Zapisuje odpowiedź i odsyła `GroupsClassified(suggestions[], cost)`.

```java
@Document("ingestion_classifications")
class ClassificationCacheEntity {
    @Id String id;                      // sessionId + ":" + requestHash
    @Indexed String sessionId;
    @Indexed String cashFlowId;
    String requestHash;
    String language;
    List<SuggestionDocument> suggestions;
    String rawEngineStatus;             // SUGGESTIONS_READY | NO_PATTERNS_TO_CATEGORIZE | ERROR
    int tokensUsed; String estimatedCost;   // RAPORT, nie stan sesji — lekcja F67
    Instant createdAt;
    @Indexed(expireAfter = "0s") Instant expiresAt;
}
```

**Dlaczego koszt leży tutaj, a nie w sesji:** F67 usunęło `aiTokensUsed` i `aiEstimatedCost` z
`StagingSessionEntity`, bo to były jednostki dostawcy w trwałym dokumencie procesu, a klasyfikator
bez modelu musiałby tam wpisywać zera na wieki. W cache'u **tej** sekcji są na miejscu: dokument
należy do modułu, który rozmawia z dostawcą, i znika razem z nim.

## Scenariusze testowe

### A. Unit — mapowanie i decyzja o cache

| test | given → then |
|---|---|
| `shouldMapGroupToWhatTheEngineReads` | `GroupSummary` ŻABKA → `TransactionToCategorize` z nazwą, kwotą, typem, bankCategory, merchantem, pewnością, **słowem** klasyfikacji (nie enumem), `isAutoCategorizeable`, kontem kontrahenta |
| `shouldPassClassificationAsTextNotEnum` | grupa `CRYPTO_SWAP` (słowo nieznane słownikowi) → w żądaniu leci tekst, nic nie rzuca |
| `shouldPassLanguageThrough` | `language="pl"` → `CategorizationRequest.detectedLanguage()=="pl"` |
| `shouldNotInventLanguageWhenMissing` | `language==null` → przekazane `null`, **bez** domyślnego „pl" |
| `shouldComputeStableRequestHash` | te same grupy w innej kolejności → **ten sam** hash |
| `shouldComputeDifferentHashForDifferentCategories` | `CategorySnapshot` zmieniony → inny hash (po utworzeniu kategorii sugestie mogą być inne) |

### B. Component — prawdziwy handler + `InMemoryClassificationCacheRepository` + atrapa kontraktu

| test | given → then |
|---|---|
| `shouldCallTheContractOnceForTheWholeBatch` | 29 grup → **1** wywołanie `suggestFor`, zero per grupa |
| `shouldSendOnlyRowsOfOpenGroups` | komenda z 29 kluczy z 38 → `rowsOfGroup` wołane 29×, żądanie do silnika nosi wiersze tych grup i **żadnego** wiersza grupy rozstrzygniętej |
| `shouldMapContextMappingBackToItsGroup` | `ContextMapping(pattern="ŻABKA", bankCategory="Zakupy kartą")` → `Suggestion` o `GroupKey` `MERCHANT\|ŻABKA\|Zakupy kartą\|OUTFLOW` |
| `shouldMapAutoCategorizableSuggestionToClassificationGroup` | `AutoCategorizableSuggestion(classification="BANK_FEE")` → `GroupKey` `CLASS\|BANK_FEE` |
| `shouldFallBackToBankCategorySuggestionWhenMerchantIsWeak` | grupa bez merchanta + `BankCategoryFallback` → sugestia z fallbacku, `reason` to mówi |
| `shouldNeverWriteToRows` | atrapa `IngestionRowsApi` → zero wywołań zapisujących (kontrakt jest tylko do czytania) |
| `shouldStoreTheAnswer` | → jeden dokument cache z `suggestions`, `tokensUsed`, `estimatedCost` |
| `shouldAnswerFromCacheWithoutCallingTheContract` | ta sama komenda drugi raz (ponowienie po awarii) → **zero** wywołań kontraktu, odpowiedź identyczna |
| `shouldReportCostInReplyNotInSession` | → `GroupsClassified.cost` wypełniony; w odpowiedzi nie ma żadnego pola stanu sesji |
| `shouldReplyFailedAndCacheNothingWhenEngineThrows` | kontrakt rzuca `AiServiceUnavailable` → `StepReply(FAILED, retryable=true)`, cache pusty |
| `shouldReplyFailedNotRetryableOnCostCap` | kontrakt rzuca przekroczeniem limitu → `FAILED(retryable=false)` — ponawianie tylko spali budżet |
| `shouldReplySucceededWithNoSuggestionsForEmptyInput` | `openGroups` puste → `SUCCEEDED`, 0 sugestii, **zero** wywołań kontraktu |
| `shouldPassEngineStatusThrough` | silnik odpowiada `NO_PATTERNS_TO_CATEGORIZE` → `SUCCEEDED` z 0 sugestiami, status zapisany w cache do audytu |
| `shouldMapSuggestionsWholeObject` | odpowiedź silnika → `List<Suggestion>` asercją całoobiektową (konstruktor), w tym `confidence` i `parent` |

### C. Czego nie testujemy tutaj

| rzecz | gdzie |
|---|---|
| jakość sugestii modelu | nigdzie — to nie jest testowalne deterministycznie; w 09 stoi atrapa |
| czy `DataCleaner` czyści cache | 09 (i to jest test o cenie: cache po przebiegu udaje determinizm) |
| TTL dokumentu cache | 09 |

**Czas wykonania:** < 2 s. **Nigdy nie woła prawdziwego modelu** — ani tu, ani w 09.

## Reguły architektoniczne (09)

1. **Nic w rodzinie ingestion nie zależy od tego modułu.** To ta sama reguła, co dzisiejsze
   `nothingDependsOnClassification`, tylko wymuszona Mavenem — i to ona czyni klasyfikator
   wymienialnym.
2. Ten moduł nie zależy od `staging`, `category-resolution`, `cashflow-writer` ani od conductora.
3. Ten moduł nie zależy od `cashflow-api` — nie pyta cash flow o nic; drzewo kategorii przychodzi w
   komendzie.
4. Żaden inny moduł nie zna `ClassificationCacheEntity`.
