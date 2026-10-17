# Granice modułów cashflow: cztery poddomeny i jeden brakujący kontrakt

| | |
|---|---|
| **Status** | analiza + **decyzja podjęta** (HTTP jest celowe). Pozycje 1–3 **wykonane**, pozycja 4 **w toku** (kontrakt ma dwie metody, `bank_data_ingestion` widzi już tylko `cashflow.api`), 5 otwarta |
| **Skąd** | cztery pytania z rzędu: „czy można wydzielić `user_financial_profile`? `recurring_rules`? `bank_data_ingestion`? a co z cashflow i jego api?" |
| **Zakres** | `vidulum-cashflow` i jego cztery poddomeny, `vidulum-recurring-rules`, przyszły `vidulum-cashflow-api` |
| **Data** | 2026-10-16 |

---

## 0. Wniosek najpierw

Cztery analizy skończyły się tym samym zdaniem: **każda z poddomen upiera się o to, czego nie ma — kontraktu cashflow.** Nie trzeba czterech wydzieleń; trzeba jednego kontraktu, po którym każde wydzielenie staje się tanie — tak jak po krokach 04 i 06 tanie stało się 06c.

| poddomena | czego brakuje, żeby wyszła |
|---|---|
| `user_financial_profile` | `CashFlowEvent` jako cel deserializacji + dwa value objecty |
| `recurring_rules` | **nic — już jest osobnym artefaktem**; został własny klient HTTP do cashflow |
| `bank_data_ingestion` | drugi własny klient HTTP + jeden odczyt przez repozytorium cashflow |
| `cashflow` | kontrakt, którego nie ma, przez co trzy powyższe piszą go sobie same |

## 1. Rozmiar: z czym mamy do czynienia

```
vidulum-cashflow/                              357 plików main
├── cashflow/                      119   rdzeń: agregat, komendy, zdarzenia, REST
├── bank_data_ingestion/           121   staging, mapowania, import, kategoryzacja AI
├── cashflow_forecast_processor/     52   projekcja prognozy ze zdarzeń
├── bank_data_adapter/               44   transformacja CSV przez AI
├── user_financial_profile/          21   własne konta, wykrywanie przelewów własnych
└── recurring-rules/  (osobny artefakt, 64)  reguły cykliczne
```

Kontrakt można zrobić **bez wydzielania czegokolwiek** — i to jest właściwa kolejność, bo kształt kontraktu nie zależy od tego, w którym jarze siedzi implementacja.

## 2. Decyzja: HTTP między poddomenami jest celowe

Rozstrzygnięte przez właściciela projektu (2026-10-16). To zmienia kształt kontraktu z `ModuleApi` na **deklaratywny interfejs HTTP**, i oznacza, że wzorzec już w repo jest wzorcem docelowym:

```java
// vidulum-cashflow/user-financial-profile-api — istnieje dziś
@HttpExchange("/api/v1/user/owned-accounts")
public interface UserFinancialProfileApi { … }
```

Serwer: `UserFinancialProfileRestController implements UserFinancialProfileApi`.
Klient: `HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).createClient(UserFinancialProfileApi.class)`,
z przepisywaniem nagłówka `Authorization` z żądania wejściowego i z `@ConditionalOnProperty("vidulum.cashflow-service.enabled")`, które w testach integracyjnych wyłącza proxy i podstawia bean kontrolera.

**Konsekwencja dla markerów z kroku 12:** kontrakt HTTP **nie nosi markera `ModuleApi`** — jest implementowany przez publiczny kontroler, więc łamałby dwie reguły ArchUnita (kontrakt bez Springa, implementacja niepubliczna). To nie wyjątek od reguły, a różnica gatunkowa: `ModuleApi` oznacza wywołanie w procesie, `@HttpExchange` wywołanie po sieci. Od teraz ta różnica jest widoczna w systemie typów.

## 3. `user_financial_profile`

**Moduł `-api` już istnieje** (6 plików). Strona wejściowa jest czysta: jedyny produkcyjny konsument, `bank_data_ingestion`, idzie przez `.api` w trzech miejscach. Ma własny `DataCleaner`, własne konfiguracje Kafki, własne repozytorium i encję — jest ukształtowany dalej, niż było portfolio przed krokiem 06c.

Blokady, trzy importy:

```java
import com.multi.vidulum.cashflow.domain.BankAccount;        // wartość
import com.multi.vidulum.cashflow.domain.BankAccountNumber;  // wartość (+ IbanNumber pod spodem)
import com.multi.vidulum.cashflow.domain.CashFlowEvent;       // cel deserializacji
```

`CashFlowEvent` jest potrzebne **tylko po to, żeby zdeserializować kopertę** — listener czyta z niej dokładnie trzy pola (`userId`, `bankAccount`, `cashFlowId`). Wąski rekord w kontrakcie by wystarczył, **ale nie bez jednej zmiany**: `JsonContent.to()` używa `ObjectMapper` bez konfiguracji, a w Jacksonie `FAIL_ON_UNKNOWN_PROPERTIES` jest domyślnie włączone — czyli węższy rekord rzuciłby wyjątkiem na pozostałych polach. Potrzebne `@JsonIgnoreProperties(ignoreUnknown = true)` na tym rekordzie; globalnej zmiany mappera bym nie robił, bo dotyka każdej deserializacji w repo.

Trzecia drobna blokada: test `TestRegisterUserCommandHandler` w cashflow wstrzykuje `UserFinancialProfileService` — implementację.

## 4. `recurring_rules` — już wydzielone, i to najdalej

| | |
|---|---|
| artefakt | **osobny** (`vidulum-recurring-rules`), 64 pliki main, 3 test |
| zależności | `vidulum-shared-kernel` + Spring. **Nic z cashflow** |
| `recurring-rules/main` → cashflow | **0 importów** |
| `cashflow/main` → recurring-rules | **0 importów** |
| jak rozmawiają | po HTTP: `CashFlowHttpClient` → `/cash-flow/cf=`, `/cash-flow/expected-cash-change`, `…/cash-changes`, `…/cash-changes/batch` |

To **najbardziej niezależny moduł domenowy w repo** — bardziej niż `vidulum-risk`, który przynajmniej kompiluje się przeciw trzem kontraktom.

**`-api` nie ma dla kogo powstać.** Nikt w mainie nie pyta tego modułu o nic — on tylko woła na zewnątrz. Jedynym konsumentem jego typów jest jeden test integracyjny w cashflow. Kontrakt bez konsumenta to `InMemoryPortfolioModuleApi` raz jeszcze.

I tu jest różnica wobec `user_financial_profile`, warta nazwania: **ufp jest pytany** (więc jego `-api` jest uzasadnione i istnieje), **recurring-rules pyta** (więc potrzebuje nie własnego `-api`, a kontraktu cashflow).

## 5. `bank_data_ingestion` — najlepiej przygotowany i najtrudniejszy

**Droga zapisu jest już za portem**, z trzema implementacjami (HTTP produkcyjnie, dwie testowe):

```java
public interface CashFlowServiceClient {          // bank_data_ingestion/app
    CashFlowInfo getCashFlowInfo(String cashFlowId);
    boolean exists(String cashFlowId);
    void createCategory(String cashFlowId, String categoryName, String parentCategoryName, Type type);
    String importHistoricalTransaction(String cashFlowId, ImportTransactionRequest request);
    RollbackResult rollbackImport(String cashFlowId, boolean deleteCategories);
}
```

**Droga czytania ten port omija:**

```java
public class GetStagingPreviewQueryHandler … {
    private final DomainCashFlowRepository domainCashFlowRepository;   // repozytorium cashflow
    …
    CashFlowSnapshot snapshot = cashFlow.getSnapshot();
}
```

plus `import com.multi.vidulum.cashflow.domain.*` gwiazdką. Jeden plik, ale to on przesądza, że wydzielenie jest projektem, a nie przeniesieniem: podgląd stagingu potrzebuje istniejących nazw kategorii i sald, więc port musi te odczyty dostać — a to decyzja o kształcie kontraktu.

Pozostałe przecieki: `cashflow.domain.Type` w **8+ plikach** (w tym w sygnaturze samego portu i w DTO), `CashFlowDoesNotExistsException`, oraz `bank_data_adapter.domain.TransactionClassification` — sięgnięcie do **rodzeństwa**.

**To jest ten sam anty-wzorzec, który naprawiliśmy w kroku 06.** `CashFlowServiceClient` leży w `bank_data_ingestion` i definiuje, czego ten moduł potrzebuje od cashflow — jeden moduł deklaruje kształt API drugiego. Dokładnie `TradingRestClient` z `portfolio.domain`, usunięty na rzecz `TradingModuleApi`. I istnieje **trzecia kopia**: oba klienty mają własny rekord `CashFlowInfo` i własną metodę go zwracającą.

## 6. `vidulum-cashflow-api` — co by w nim było

Powierzchnia: **22 endpointy wystawione, osiem konsumowanych** (po deduplikacji dwóch klientów). Czyli kontrakt objąłby ~⅓ — zgodnie z regułą, że kontrakt to to, o co naprawdę ktoś pyta, a nie publiczne API kontrolera.

```
vidulum-cashflow-api/
├── cashflow/api/CashFlowApi.java       @HttpExchange — ~8 metod (unia dwóch klientów)
├── cashflow/api/view/                  CashFlowInfo · MonthStatus · ImportResult
├── cashflow/api/error/                 CashFlowNotFound · CategoryNotFound · CashFlowCommunication
├── cashflow/api/event/                 CashFlowUnifiedEvent (ze shared-kernela)
│                                       CashFlowCreated (wąski) + CashFlowCreatedHandler
└── cashflow/api/                       CashFlowId · CashChangeId · CategoryName · Type
                                        BankAccountNumber · BankName   ← odzyskane ze shared-kernela
```

Co z tego wypada: `HttpCashFlowServiceClient`, `CashFlowHttpClient`, `CashFlowServiceClient` jako port w konsumencie, pięć URL-i składanych konkatenacją w recurring-rules, drugi rekord `CashFlowInfo`, dwa zestawy wyjątków. Co zostaje bez zmian: `TestCashFlowServiceClient` na gatewayu — implementuje ten sam interfejs.

### Pułapka strony zdarzeń

`CashFlowEvent` to **22 rekordy**, a konsumenci są trzej i każdy inny:

| konsument | potrzebuje | gdzie jest |
|---|---|---|
| `cashflow_forecast_processor` | **wszystkich 22** — to projekcja | ten sam artefakt |
| `user_financial_profile` | 2 warianty, 3 pola | ten sam artefakt (dziś) |
| `websocket-gateway` | surowa koperta | **osobny deployment**, poza reaktorem |

**Całej sealed hierarchii nie wolno wkładać do kontraktu** — zamroziłoby to cały wewnętrzny słownik zdarzeń cashflow jako publiczne zobowiązanie, 22 rekordy, z których poza modułem potrzebne są dwa. Do kontraktu idzie koperta, interfejs handlera (wzorzec z kroku 12) i wąskie rekordy tego, co faktycznie przekracza granicę.

### Kontrakt odzyskuje typy ze shared-kernela

```
shared-kernel/common/        BankAccountId · BankName · CashChangeId · CashFlowId · CategoryName
                             + 2 wyjątki formatu identyfikatorów
shared-kernel/common/events/ CashFlowUnifiedEvent · BankDataIngestionUnifiedEvent
                             UserFinancialProfileUnifiedEvent · UserCreatedEvent
```

Siedem typów cashflow-owych i cztery zdarzenia integracyjne, **których producenci nie mają kontraktów**. Shared-kernel jest dziś składem dla rzeczy, które nie mają gdzie mieszkać — więc `vidulum-cashflow-api` to **największy pojedynczy kawałek zadania F24** (krok 07, odchudzenie shared-kernela).

**Korekta zapisana świadomie:** w analizach z 15–16 października proponowałem przenieść `Type`, `BankAccount`, `BankAccountNumber` i `IbanNumber` do shared-kernela, „bo `CashFlowId` i `BankName` już tam są". Po tej analizie to jest ruch w złą stronę — te typy należą **do kontraktu**, nie do shared-kernela, inaczej przenosilibyśmy je dwa razy.

## 7. Kolejność

| | co | stan |
|---|---|---|
| **1** | `DataCleaner` per poddomena; `cashflow` i `recurring_rules` do reguły `assertSealed` | ✅ **wykonane** (krok 13) |
| **2** | `recurring_rules`: bootstrap deklaruje jawnie, cashflow → `test` scope | ✅ **wykonane** (krok 13) |
| 3 | decyzja HTTP vs w procesie | ✅ **podjęta**: HTTP jest celowe |
| **4** | `vidulum-cashflow-api` w kształcie z sekcji 6 | 🔸 **w toku**: **dwie metody** (kroki 14, 16), `Type` w kontrakcie (15), jeden wyjątek (14). Zostaje reszta kontraktu klienta i adapter HTTP |
| 5 | wydzielenia: `user_financial_profile` → `bank_data_ingestion` | otwarte, ale **`bank_data_ingestion` nie widzi już domeny cashflow w ogóle** — zszedł z listy odstępstw ArchUnita (15) |

## 8. Co zrobił krok 13

**Cztery cleanery zamiast jednego.** `CashFlowDataCleaner` czyścił **dziesięć kolekcji z czterech poddomen**: swoją jedną, dwie projekcji prognozy, pięć stagingu i dwa cache'y AI. Reguła z kroku 03 — „poddomena będąca właścicielem encji jako pierwsza zauważa nową, a cleaner obok encji przeżyje wydzielenie do osobnego artefaktu" — została wtedy zastosowana do wealth i do `user_financial_profile`, ale **nie do największych poddomen cashflow**. Teraz: `CashFlowDataCleaner` (1 kolekcja), `CashFlowForecastDataCleaner` (2), `BankDataIngestionDataCleaner` (5), `BankDataAdapterDataCleaner` (2). W rodzinie cashflow jest ich sześć, po jednym na poddomenę.

Dla `bank_data_adapter` to nie kosmetyka: transformacja AI jest **cache'em odpowiedzi modelu**, więc kolekcja zostawiona po poprzednim przebiegu sprawia, że test albo ręczny przebieg używa cudzej odpowiedzi i wygląda na deterministyczny, choć nie jest. Dlatego przewodnik ręcznego testowania każe robić `down -v`.

**Reguła `assertSealed` objęła dwa brakujące moduły.** `recurring_rules` przeszedł od razu na zielono — nikt nie sięga do jego wnętrza. `cashflow` wszedł z odstępstwami wyrażonymi jako dodatkowe „własne" pakiety, a nie jako lista nazw, bo tym właśnie są: trzy poddomeny w tym samym artefakcie, które nadal czytają agregat wprost. Wszystko **poza** tym zbiorem — portfolio, trading, pnl, bootstrap — jest już zabronione, i ta połowa jest warta zamrożenia dzisiaj.

**`recurring_rules` przestał trafiać do aplikacji przypadkiem.** Dostawał się tranzytywnie, przez compile-scope zależność `vidulum-cashflow` → `vidulum-recurring-rules`, mimo że main cashflow nie używa z niego **ani jednej klasy**. Był więc jedynym modułem implementacyjnym, którego beany docierały do aplikacji bez deklaracji — wbrew regule z kroków 06c, 09 i 10. Teraz deklaruje go bootstrap, a cashflow trzyma go w `test` scope dla jednego testu integracyjnego, który potrzebuje działającego cashflow po HTTP.

**I test, który tę zmianę pilnuje.** `BootstrapWiresEveryModuleTest` sprawdza, że kontrakty czterech modułów mają w kontekście aplikacji dokładnie jedną implementację, a cztery moduły bez kontraktu — swój kontroler. To runtime'owy odpowiednik `ModuleBoundariesArchTest`: tamten pilnuje, żeby nikt nie sięgał tam, gdzie nie wolno, ten — żeby moduły w ogóle **były**. Drugi tryb awarii jest cichszy: brak modułu na classpathzie bootstrapu nie psuje żadnego builda, beany po prostu nie powstają, wszystkie pozostałe testy przechodzą, a funkcja znika.

Sprawdziłem, że ten test faktycznie upada: po tymczasowym usunięciu zależności z pomu `vidulum-app` padł z komunikatem o recurring rules. Zielony test, który nie upada bez przedmiotu sprawdzenia, jest gorszy niż jego brak — ta sama pomyłka zdarzyła mi się w kroku 12 przy teście negatywnym ArchUnita.

## 9. Powiązania

- `2026-10-12-module-contracts-api-impl-split.md` — kroki **04**/**06** (ten sam anty-wzorzec portu w konsumencie), **03** (cleaner per poddomena), **12** (markery i ArchUnit), **13** (ten dokument), oraz **05c**/**07** jako F23/F24
- `2026-10-15-alerts-module-design.md` — pierwszy krój alertów blokuje się na tym, że zdarzenia cashflow nie noszą `UserId`; kontrakt zdarzenia z sekcji 6 to zdejmuje
- `2026-02-08-canonical-csv-architecture.md` — dotyczy `bank_data_ingestion`, warto przeczytać przed jego wydzielaniem

## 9. Co zrobił krok 14

### Kontrakt z jedną metodą, wyprowadzoną z jednego wywołania

```java
public interface CashFlowModuleApi extends ModuleApi {
    CashFlowSkeletonView skeletonOf(CashFlowId cashFlowId);
}
```

`vidulum-cashflow-api` zagnieżdżony w `vidulum-cashflow/`, zależny **tylko od shared-kernela**: bez
Springa, bez HTTP, bez `cashflow.domain` — w tym **bez `Type`**, bo kierunek wyraża to, na której z
dwóch list kategoria leży. To jedyna reprezentacja, która nie może sobie zaprzeczyć, i ona właśnie
uwalnia kontrakt od enuma dzielonego przez 39 plików `bank_data_ingestion`.

Widok ma siedem pól tam, gdzie snapshot agregatu ma szesnaście, z czego jedno to mapa wszystkich
transakcji. Trzy kształty rozstrzygnięte w rozmowie:

- **`parentName` zostaje** w `CategoryNodeView` — płaskie wyszukiwania w kategoryzacji są pisane
  przeciw niemu. Skoro jest nadmiarowe wobec drzewa, konstruktor zwarty **sprawdza zgodność**:
  nadmiarowość, której nikt nie waliduje, to pole, które kiedyś skłamie.
- **`archived` zostaje** — zarchiwizowana kategoria nie przyjmuje nowych transakcji, ale wskazują na
  nią historyczne, więc importer musi wiedzieć, że istnieje.
- **`LifecyclePhase` jest własnym enumem kontraktu**, nie `CashFlow.CashFlowStatus`. Ten słownik
  istniał w trzech kopiach; implementacja mapuje go `switch`em po enumie, nie `valueOf` — dodanie
  statusu w agregacie ma **przestać się kompilować**, a nie zaskoczyć konsumenta w runtime.

### Konsument: koniec z ładowaniem agregatu po dwa pola

`GetStagingPreviewQueryHandler` tracą: `DomainCashFlowRepository`, `CashFlow`, `CashFlowSnapshot`
**z czterech sygnatur**, 192-linijkową klasę `Category`, dwie prywatne metody przechodzące drzewo i
rzucanie wyjątku obcej domeny. Gwiazdkowy `import cashflow.domain.*` zwężony do jednego `Type`.
Bilans **+25/−38** linii. Porównania idą teraz **między typami** (`Set<CategoryName>`), a łańcuch
pojawia się wyłącznie na wyjściu do DTO.

### Jeden wyjątek zamiast trzech

| usunięta klasa | gdzie była | użyć |
|---|---|---|
| `cashflow.domain.CashFlowDoesNotExistsException` | `vidulum-cashflow` | **41 plików** |
| `CashFlowServiceClient.CashFlowNotFoundException` | zagnieżdżona w interfejsie portu, `extends RuntimeException` | klient HTTP, 5 bloków `catch`, dubler |
| `recurring_rules…CashFlowNotFoundException` | `vidulum-recurring-rules` | klient HTTP |

Została jedna: `cashflow/api/error/CashFlowNotFoundException` — `BusinessException` z
`ErrorCode.CASHFLOW_NOT_FOUND`, czyli 404 bez niczyjego tłumaczenia. `vidulum-recurring-rules` dostał
zależność od `vidulum-cashflow-api`, co jest właściwym kierunkiem: jest konsumentem cashflow, więc
zależy od jego kontraktu; reszta jego komunikacji nadal jedzie po HTTP.

**Pięć bloków `try/catch` zniknęło w całości**, bo przestały mieć sens — nie ma już niezmapowanego
typu do przechwycenia.

### Jedna zmiana zachowania, nazwana wprost

Dwa z tych pięciu bloków **nie tłumaczyły na wyjątek biznesowy**:

```java
} catch (CashFlowServiceClient.CashFlowNotFoundException e) {
    throw new IllegalStateException("CashFlow not found: " + command.cashFlowId().id());
}
```

`RevalidateStagingCommandHandler` i `ForceUncategorizedCommandHandler`. `IllegalStateException` nie
ma `ErrorCode`, a `ErrorHttpHandler` nie obsługuje `RuntimeException` — więc te dwa endpointy
odpowiadały **500** na nieistniejący cashflow. Teraz odpowiadają 404. Żaden test tego nie złapał, co
jest spójne z **F19**: ścieżki błędu w `bank_data_ingestion` są słabo pokryte, a trzy testy, które je
pokrywały, są `@Disabled`.

### Testy: dwa dowody negatywne

`CashFlowSkeletonComponentTest` (6 przypadków, bez Springa) przypina przechodzenie drzewa,
`parentName` na każdym poziomie, archiwizację, trzy fazy, typowane nazwy — i to, po co ta metoda
istnieje: **przepływ z 500 transakcjami odpowiada identycznie jak bez żadnej**.

Siedemset dwa testy cashflow przeszły bez edycji **ani jednego pliku testowego** — ale po
sprawdzeniu okazało się, że **żaden nie asertował `categoryBreakdown[].isNewCategory` ani
`categoriesToCreate`**, czyli dwóch pól liczonych z podmienionego zbioru. „Testy przeszły" znaczyło
„handler się nie wywrócił", nie „odpowiada tak samo". Asercja dopisana do istniejącego
`DirectCategoryMatchIntegrationTest`, z `hasSize(3)` **przed** `allSatisfy`, bo `allSatisfy` na
pustej liście przechodzi.

Oba nowe zabezpieczenia sprawdzone **dowodem negatywnym**, bo zielony test, który nie upada bez
swojego przedmiotu, nie dowodzi niczego:

| test | podstawione | wynik |
|---|---|---|
| asercja `isNewCategory` | `Set.of()` zamiast `skeleton.allCategoryNames()` | upadł: „category [Transport] exists in the cash flow, so it is not new" |
| `BootstrapWiresEveryModuleTest` (krok 13) | usunięta zależność w pomie `vidulum-app` | upadł, nazywając recurring rules |

Przy usuwaniu klas wyszła jeszcze jedna rzecz warta zapamiętania: **pierwszy `test-compile` przeszedł
fałszywie zielono** na przeterminowanych klasach i dopiero `clean test-compile` pokazał dwa pliki
testowe odwołujące się do usuniętego typu.

### Weryfikacja

Pełny reaktor: **1274 testy, zero porażek, 6 pominiętych**, `BUILD SUCCESS`.

### Czego krok 14 nie zrobił

`bank_data_ingestion` nadal importuje `cashflow.domain.Type` w **39 plikach**, z czego cztery to
encje Mongo — czyli ten enum jest częścią schematu bazy i jego przeniesienie to zmiana schematu, nie
dopisek. Pozostałe sześć wywołań idzie dalej przez port i jego dwa żądania HTTP; wycofanie
`getCashFlowInfo` i `exists` to osobny krok, a pierwszym kandydatem jest `AiCategorizeCommandHandler`,
bo używa `CashFlowInfo` **do jednej rzeczy** — zbudowania struktury kategorii, czyli dokładnie tego,
co nosi szkielet.

## 10. Co zrobił krok 15: `Type` do kontraktu

`cashflow.domain.Type` był **najszerzej współdzieloną rzeczą w cashflow**: 95 plików, z czego 39 w
samym `bank_data_ingestion`, a cztery z nich to **encje Mongo** — czyli ten enum był częścią
schematu bazy tej poddomeny tak samo jak domeny cashflow. Słownik, od którego zależy tylu
wołających, jest kontraktem, w jakimkolwiek pakiecie się urodził.

**Dane nietknięte, i to sprawdzone przed ruszeniem klasy**: Spring Data Mongo i Jackson zapisują
enum **nazwą stałej**, a przy tym polu nie ma `_class` ani `@JsonTypeInfo`. `INFLOW` zostaje
`INFLOW` w każdym dokumencie i na każdym topicu. Pełny reaktor po przeniesieniu dał tę samą liczbę
testów — bo to była zmiana pakietu, nie zachowania.

Przeniesienie dotknęło 120 plików: 95 z jawnym importem i 25, które brały `Type` **gwiazdką** z
`cashflow.domain.*` i po usunięciu klasy przestały się kompilować. Tych 25 znalazł kompilator w
trzech rundach, nie grep.

### I dlatego reguła ArchUnita została zacieśniona

`bank_data_ingestion` **zszedł z listy odstępstw** dla cashflow. 121 plików, które w tej serii
sięgały po repozytorium, snapshot, agregat, `Category`, wyjątek domenowy i enum, nazywa teraz
wyłącznie `cashflow.api`:

| import | ile |
|---|---|
| `cashflow.api.Type` | 39 |
| `cashflow.api.error.CashFlowNotFoundException` | 7 |
| `cashflow.api.CashFlowModuleApi`, `…view.CashFlowSkeletonView` | 2 |
| **`cashflow.domain.*`** | **0** |

To jest moment, w którym listy odstępstw z kroku 12 pokazują, czym miały być: **miarą, która ma się
skracać**, nie stałym pozwoleniem. W cashflow zostają dwie poddomeny — `user_financial_profile`
(`BankAccount`, `BankAccountNumber`, `CashFlowEvent`) i `cashflow_forecast_processor`, która jako
projekcja potrzebuje pięciu typów domenowych.

Nazwy **nie zmieniałem**: `Type` w kontrakcie czytałoby się lepiej jako `CashChangeType` albo
`Direction`, bo konsument pisze `import …cashflow.api.Type;` i dostaje u siebie klasę „Type". To
decyzja o publicznym API kontraktu, nie szczegół przenoszenia — i warta osobnego commita, bo
mechaniczna i bezpieczna dla danych (nazwy stałych zostają).

## 11. Co zrobił krok 16: druga metoda i koniec `exists` w porcie

```java
public interface CashFlowModuleApi extends ModuleApi {
    CashFlowSkeletonView skeletonOf(CashFlowId cashFlowId);
    boolean exists(CashFlowId cashFlowId);
}
```

Trzy miejsca pytały o samo istnienie, i to przez port, gdzie `exists` było
`try { getCashFlowInfo(); return true; } catch { return false; }` — **boolean wyprodukowany z
wyjątku kosztem dwóch żądań i payloadu z mapą wszystkich transakcji**. W implementacji w procesie to
jeden odczyt Mongo.

Dlaczego osobna metoda, a nie `skeletonOf` dla wszystkich trzech: dwa z tych miejsc
(`ConfigureCategoryMapping`, `AcceptAiSuggestions`) **nigdy nie zaglądają do treści przepływu** —
pierwsze zapisuje mapowania, drugie tworzy kategorie. Kazanie im pobierać szkielet byłoby proszeniem
o dane na wyrzucenie, a `cashFlowApi.skeletonOf(id);` jako instrukcja z odrzuconym wynikiem to
wywołanie gettera dla efektu rzucenia wyjątku — źle się czyta i zaprasza do „optymalizacji".

**Trzecie miejsce poszło w drugą stronę.** `AiCategorizeCommandHandler` używał `CashFlowInfo` do
jednej rzeczy — zbudowania struktury kategorii — więc jedno `skeletonOf` zastąpiło u niego **i straż
`exists()`, i `getCashFlowInfo()`**: cztery żądania HTTP zamienione na jedno wywołanie. Ten handler
**przestał wstrzykiwać port w ogóle** i jest pierwszym w tym pakiecie, który rozmawia z cashflow
tylko przez kontrakt. Doszła fabryka `ExistingCategoryStructure.from(CashFlowSkeletonView)`, która
bierze płaskie zbiory nazw wprost z widoku, a drzewo przechodzi tylko po to, by zamienić
`CategoryName` na `String` — bo ten rekord trzyma nazwy jako łańcuchy.

Potem `exists` zniknęło z portu i z **pięciu** implementacji (HTTP, trzy dublery testowe) razem z
testem `shouldVerifyCashFlowExists`, którego przedmiot przestał istnieć; obsługę 404 pokrywa
`shouldHandleNotFoundError`.

### Weryfikacja i arytmetyka, która wyłapuje ciche straty

Pełny reaktor: **1275 testów, zero porażek, 6 pominiętych**. Liczba zgadza się do jednego:

```
1274  ostatni zielony pełny przebieg
  +2  JsonContentTest
  −1  shouldVerifyCashFlowExists
────
1275
```

## 12. Usterka znaleziona po drodze: `JsonContent`

Nie jest to część podziału modułów, ale wyszła przy nim i dotyczy produkcji, nie testów.

```java
public <T> T to(Class<T> clazz) {
    return OBJECT_MAPPER.findAndRegisterModules().readValue(content, clazz);   // przed
}
```

`findAndRegisterModules()` **rekonfigurowało współdzielony statyczny mapper przy każdym odczycie**, a
`to()` wołają dwa listenery Kafki z tego samego topicu `cash_flow` — prognoza i
`user_financial_profile` — każdy na własnym wątku konsumenta. Dwa mechanizmy, oba usuwane tą samą
poprawką:

1. `ObjectMapper` jest bezpieczny wątkowo **po** zakończeniu konfiguracji; rejestracja modułu resetuje
   cache serializatorów i kreatorów.
2. `findAndRegisterModules()` szuka przez `ServiceLoader` z **context classloaderem wołającego
   wątku** — wywołanie z wątku widzącego mniej classpathu rejestruje mniej modułów **trwale i dla
   wszystkich**.

Ofiara w obu przypadkach ta sama: bez `ParameterNamesModule` klasa z samym `@AllArgsConstructor` nie
ma dla Jacksona konstruktora. Poprawka: konfiguracja **raz**, w inicjalizatorze statycznym.

**Dokąd sięgają dowody** — i to jest ważniejsze niż sama poprawka, bo opisywałem to najpierw jak
usterkę potwierdzoną:

| | |
|---|---|
| 5 błędów `CashFlowForecastProcessorTest` z dokładnie tym komunikatem w jednym przebiegu | tak |
| ta sama klasa w izolacji | 28/28 |
| **ta sama suita powtórzona na starym kodzie** | **702, zero błędów** |
| `JsonContentTest` uruchomiony na starym kodzie | **przeszedł** — nie odtworzył usterki |

Czyli związek jest **wywnioskowany z mechanizmu, nie wykazany**. Co stoi samo: rekonfigurowanie
współdzielonego stanu przy każdym odczycie nie ma obrony. `JsonContentTest` został jako straż na
przyszłość i jako sprawdzenie, że klasa z samym `@AllArgsConstructor` przechodzi round-trip — z
javadokiem mówiącym wprost, że usterki nie odtworzył.

