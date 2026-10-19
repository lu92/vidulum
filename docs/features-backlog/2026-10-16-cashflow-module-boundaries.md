# Granice modułów cashflow: cztery poddomeny i jeden brakujący kontrakt

| | |
|---|---|
| **Status** | analiza + **decyzja podjęta** (HTTP jest celowe). Pozycje 1–3 **wykonane**, pozycja 4 **w toku** (kontrakt ma dwie metody, `bank_data_ingestion` widzi już tylko `cashflow.api`), 5 otwarta |
| **Skąd** | cztery pytania z rzędu: „czy można wydzielić `user_financial_profile`? `recurring_rules`? `bank_data_ingestion`? a co z cashflow i jego api?" |
| **Zakres** | `cashflow` i jego cztery poddomeny, `vidulum-recurring-rules`, przyszły `vidulum-cashflow-api` |
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

**`recurring_rules` przestał trafiać do aplikacji przypadkiem.** Dostawał się tranzytywnie, przez compile-scope zależność `cashflow` → `vidulum-recurring-rules`, mimo że main cashflow nie używa z niego **ani jednej klasy**. Był więc jedynym modułem implementacyjnym, którego beany docierały do aplikacji bez deklaracji — wbrew regule z kroków 06c, 09 i 10. Teraz deklaruje go bootstrap, a cashflow trzyma go w `test` scope dla jednego testu integracyjnego, który potrzebuje działającego cashflow po HTTP.

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
| `cashflow.domain.CashFlowDoesNotExistsException` | `cashflow` | **41 plików** |
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


## 13. Co zrobił krok 17: pierwszy **zapis** w kontrakcie i dwa transporty

Pytanie, od którego się zaczęło, brzmiało: czy `createCategory` może trafić do kontraktu,
a implementacja ma robić prawdziwy call HTTP sama do siebie, i czy wtedy da się odchudzić pozostałe
implementacje. Odpowiedź rozdzieliła się na dwie — i druga połowa okazała się odwrotna niż
propozycja.

### Fakt, który przesądził: produkcja **już** wołała samą siebie po HTTP

`CashFlowServiceClientConfig` miał `matchIfMissing = true` na `vidulum.cashflow-service.enabled`,
a tej właściwości nikt nigdzie nie ustawiał. Czyli domyślną implementacją portu w działającej
aplikacji był `HttpCashFlowServiceClient`, pukający na `http://localhost:8080` — **raz na każdą
zakładaną kategorię**, w pętli, przez cały łańcuch filtrów, w jednym JVM-ie. Nikt tego nie
zauważył, bo **każdy** test integracyjny ustawiał tę właściwość na `false` i podstawiał dubler.

To jest dokładnie ta sytuacja, przed którą ostrzega reguła o dowodach negatywnych, tylko odwrócona:
zielona suita nie dowodziła niczego o produkcyjnej ścieżce, bo produkcyjnej ścieżki nie dotykała.

### Dlaczego „tylko HTTP" byłoby krokiem w tył

| | |
|---|---|
| dwa konteksty testowe nie mają portu | `BankDataIngestionControllerTest` (32 testy, 13 odwołań do importu) i `AppIntegrationTest` (4 podklasy) to `WebEnvironment.MOCK` |
| endpointy są `.authenticated()` | self-call niesie token tylko przez `RequestContextHolder`, a ten istnieje **wyłącznie na wątku żądania** — dziś import jest synchroniczny, więc działa; pierwszy scheduler albo listener Kafki dostaje 403 |
| koszt | N okrążeń po loopbacku na import |
| kształt | bean API modułu → kontroler tego samego modułu → gateway → agregat, trzy przeskoki do kodu obok |

### Co zostało zrobione

**Kontrakt dostał metodę z flagą w sygnaturze**, zgodnie z decyzją:

```java
void createCategory(CashFlowId cashFlowId, CategoryName categoryName,
                    CategoryName parentCategoryName, Type type, boolean isImport);
```

`isImport` mówi, że kategoria powstaje w ramach importu historii — jedyna okoliczność, w której
cashflow w fazie `SETUP` przyjmuje nową kategorię. Wcześniej nie było tego **jak wyrazić**: klient
HTTP wpisywał `?isImport=true` na stałe w URL, więc jedyny wołający, który importem nie był
(`accept-ai`), dostawał uprawnienia importu po cichu, a żaden call site nie mówił, czym jest.

**Dwie implementacje, wzajemnie wyłączne**, wybierane jedną właściwością:

```
vidulum.cashflow.api.transport: in-process   # domyślnie — CashFlowModuleApiImpl
                                http          # HttpCashFlowModuleApi
```

`CashFlowModuleApiImpl` czyta przez repozytorium, **pisze przez gateway** — bo zakładanie kategorii
to decyzja agregatu (faza życia, zajęte nazwy, `CategoryCreatedEvent`), a sięganie po repozytorium
przepisałoby wszystkie trzy reguły. Wstrzyknięcie `CommandGateway` nie domyka cyklu, bo od kroku 08
gateway powstaje bez zależności, a handlery rejestrują się po fakcie.

`HttpCashFlowModuleApi` **mieszka w `cashflow`, nie u konsumenta** — i to jest pointa
zmiany. Wie, jakie URL-e istnieją, że kategoria przychodzi jako `{"categoryName":{"name":"…"}}`
i że pole w żądaniu nazywa się `category`, a nie `categoryName`. Ta wiedza należy do właściciela
endpointów. Poprzedni układ był odwrotny: konsument deklarował port, ręcznie pisał rekordy żądań
i odpowiedzi cudzego API i wbijał flagę w string URL-a — więc producent nie mógł zmienić własnego
payloadu, nie psując konsumenta, którego nie widzi.

### Wyjątek: ta sama duplikacja, co przy „nie ma takiego cashflow"

| przed | po |
|---|---|
| `cashflow/domain/CategoryAlreadyExistsException` — `BusinessException`, `CATEGORY_ALREADY_EXISTS`, 409 | **`cashflow/api/error/CategoryAlreadyExistsException`** — jeden, w kontrakcie |
| `CashFlowServiceClient.CategoryAlreadyExistsException` — `RuntimeException` **bez `ErrorCode`**, łapany i tłumaczony w 4 miejscach | usunięty |

Adapter HTTP odtwarza go z **kodu statusu**, nie z treści — stary port robił
`getResponseBodyAsString().contains("already exists")`.

### Czego odchudzenie dotknęło

| plik | co się stało |
|---|---|
| `HttpTestCashFlowServiceClient` | **usunięty cały** — implementował port i nie był instancjonowany przez nikogo (martwy kod) |
| `TestCashFlowServiceClient` ×2 (cashflow, app) | metoda usunięta; **29 klas testowych przestało ćwiczyć atrapę** i zaczęło kod produkcyjny |
| `HttpCashFlowServiceClient` | metoda i rekord `CreateCategoryRequest` usunięte |
| `AcceptAiSuggestionsCommandHandler` | **odłączony od portu w całości** — nie wstrzykuje go już wcale |
| `CashFlowServiceClient` | 4 metody → 3; javadoc mówi, co zostało i dlaczego dwie z trzech są trudne |

### Granica, w której transporty **nie** są identyczne — i test, który to mówi

Kontrakt deklaruje dla `createCategory` dwie porażki i obie przeżywają drut. Trzecia nie:
`OperationNotAllowedInSetupModeException` mieszka w domenie cashflow, nie w kontrakcie, więc przez
HTTP przychodzi jako goły `HttpClientErrorException.BadRequest`.
`shouldHonourIsImportFlagOverHttp` asertuje ten przeciek **po nazwie**, zamiast schować go za
`isInstanceOf(RuntimeException.class)` — tak, żeby luka była widoczna i żeby test zaświecił się na
czerwono w dniu, w którym ten wyjątek przeniesie się do `api/error` i mapowanie trzeba będzie dodać.

### Weryfikacja

| co | wynik |
|---|---|
| `CashFlowSkeletonComponentTest` | 6 → **9** (trzy testy zapisu: flaga przechodzi, `false` zostaje `false`, zapis idzie przez gateway a nie repozytorium) |
| `HttpCashFlowModuleApiIntegrationTest` (nowy) | **8**, cały kontrakt przez prawdziwe HTTP z prawdziwym JWT |
| `BankDataIngestionControllerTest` | **32** — bez dublera, pierwszy raz na kodzie produkcyjnym |
| `HttpCashFlowServiceClientIntegrationTest` | 6 → 4 (dwa testy kategorii poszły za metodą) |
| `BootstrapWiresEveryModuleTest` | dodana asercja, że przy nieustawionej właściwości beanem jest implementacja **nie-HTTP** |

**Dowód negatywny.** Flaga zahardcodowana na `true` w implementacji in-process — dokładnie to, co
robił stary klient HTTP — i `createCategoryWithoutTheImportFlagSendsAUserInitiatedCommand` upada
(`Expecting value to be false but was true`). Test mierzy to, co twierdzi, że mierzy.

## 14. Co zrobił krok 18: `importHistorical` — prawdziwe typy i jedna reguła dat

Dwa z czterech punktów analizy metody `importHistoricalTransaction`. Punkt 2 (`dueDate` jako kopia
`paidDate`) i punkt 4 (klucz z banku) **świadomie pominięte** — pierwszy nic nie psuje, drugi jest
osobnym kosztem.

### Punkt 1: port był jedynym miejscem, które psuło dane

Oba końce trzymały `Money` i `ZonedDateTime`. Degradował je wyłącznie pośrednik:

```
MappedTransactionData   Money(BigDecimal) · ZonedDateTime z godziną i strefą
  ↓ port DTO            double + String   · LocalDate
  ↓ klient HTTP         MoneyDto(double)  · atStartOfDay(ZoneId.systemDefault())
  ↓ endpoint DTO        Money             · ZonedDateTime      ← zawsze to przyjmował
```

**Co faktycznie ginęło — zmierzone, bo pierwszy raz opisałem to za mocno.**

| | |
|---|---|
| **godzina i strefa** | ginęły całkowicie: `13:45:30+01:00` → `00:00` w strefie **serwera**, więc zapisany moment zależał od miejsca wdrożenia. Dla transakcji przy granicy miesiąca przenosi ją do innego miesiąca, a miesiąc decyduje o walidacji i prognozie |
| **kwota** | **nie wartość, tylko skala.** `BigDecimal.valueOf(double)` idzie przez `Double.toString`, czyli najkrótszy zapis, który się odtwarza — każda realna kwota wracała identyczna liczbowo. Ginęło `1234.50` → `1234.5`, `0.10` → `0.1`: równe pod `compareTo`, różne pod `equals`, i z inną liczbą miejsc po przecinku niż raportował bank. Utrata **wartości** dopiero powyżej ~15 cyfr znaczących (`12345678901234567.89` → `12345678901234568`) |

Pierwsza wersja tej analizy mówiła o „utracie precyzji na kwotach pieniężnych". To było przesadzone
i sprawdzenie empiryczne to pokazało: sabotaż samej kwoty przeszedł na asercji `1234.57`, bo ta
wartość przetrwała round trip. Asercja została przepisana na `1234.50`, czyli na to, co **naprawdę**
się psuje.

**Kontrakt bierze teraz typy, które mają oba końce:**

```java
record HistoricalCashChange(CategoryName categoryName, String name, String description,
                            Money money, Type type,
                            ZonedDateTime dueDate, ZonedDateTime paidDate, boolean selfTransfer) {}

CashChangeId importHistorical(CashFlowId cashFlowId, HistoricalCashChange change);
```

`name` i `description` zostały `String`-ami, bo `Name` i `Description` mieszkają w
`cashflow.domain`, gdzie konsumentowi nie wolno zaglądać — opakowanie następuje po stronie cashflow.

**Usunięte:** `ImportTransactionRequest`, `ImportHistoricalRequest`, `MoneyDto`,
`ImportFailedException` i cztery konwersje typów w call site.

### Punkt 3: reguła dat istniała dwa razy, w dwóch reprezentacjach

| | przed |
|---|---|
| `ImportHistoricalCashChangeCommandHandler` | `switch` po statusie + dwa `if`, pięć typowanych wyjątków z `ErrorCode` |
| `StageTransactionsCommandHandler` | te same pięć warunków jako `String.format(...)`, czytane z własnego DTO |

I **już się rozjechały**: staging czytał statusy miesięcy z modułu prognoz i odrzucał `ATTESTED`,
a agregat porównywał jedną nierówność — z komentarzem przyznającym się do tego wprost.

**Reguła jest teraz wartością, nie pytaniem.** `ImportWindow` w kontrakcie, zbudowane z trzech pól,
które `CashFlowSkeletonView` **już nosi**:

```java
CashFlowSkeletonView skeleton = cashFlowApi.skeletonOf(cashFlowId);   // jedno żądanie
skeleton.importWindow().refusalFor(paidDate, now)                     // 402 wiersze lokalnie
```

Metoda na kontrakcie by tego nie naprawiła — predykat pytany na wiersz to żądanie na wiersz.

**Oba miejsca używają teraz tej samej implementacji:** handler rzuca to, co zwróci
`refusalFor`, staging zapisuje `getMessage()`. Nad tym została warstwa, której cashflow nie zna —
statusy miesięcy należą do `cashflow_forecast_processor`, więc sprawdzenie `ATTESTED` zostało
w stagingu i jest pytane tylko wtedy, gdy okno już zaakceptowało datę.

**Pięć wyjątków przeniesionych do `cashflow/api/error/`** — trzeci raz ta sama reguła z `CLAUDE.md`:
jeśli porażkę widzi inny moduł, należy do kontraktu.

**Kolejność sprawdzeń to kolejność agregatu, co do znaku.** Data może łamać dwa warunki naraz,
a który wygra, decyduje o `ErrorCode` endpointu. `CashFlowErrorHandlingTest` opiera się na tym do
tego stopnia, że ma komentarz wyjaśniający, dlaczego **nie może** przetestować
`IMPORT_DATE_IN_FUTURE` w trybie SETUP. `ImportWindowTest` przypina tę precedencję dwoma testami.

### Przy okazji: jedno mapowanie statusu zamiast trzech

`CashFlowStatus → LifecyclePhase` zmierzało do trzech kopii (implementacja kontraktu, snapshot,
staging). Jest jedna, na `CashFlowSnapshot.phase()`; `CashFlowModuleApiImpl.phaseOf` usunięte.
Adapter HTTP ma własny `switch`, bo mapuje z innego wejścia — z enuma na drucie.

### Weryfikacja

| co | wynik |
|---|---|
| `ImportWindowTest` (nowy, **pierwszy test jednostkowy tej reguły w ogóle**) | **10** |
| `CashFlowSkeletonComponentTest` | 9 → **11** |
| `HttpCashFlowModuleApiIntegrationTest` | 8 → **10** — kwota i strefa przez prawdziwą serializację, odczytane z Mongo |
| port `CashFlowServiceClient` | 3 metody → **2** |

Obie zmiany mają dowód negatywny:

| sabotaż | co upada |
|---|---|
| `doubleValue()` + `atStartOfDay(systemDefault())` w implementacji in-process | `importHistoricalPassesMoneyAndTimeThroughUntouched` — `13:45:30` → `00:00` oraz `1234.50` → `1234.5` |
| sprawdzenie czasu przestawione **przed** fazę w `ImportWindow` | `thePhaseRefusalWinsOverTheFutureOne` |

### Czego krok 18 nie zrobił

- **`dueDate` nadal jest kopią `paidDate`** (punkt 2). Sprawdzone, że nic się na tym nie psuje:
  miesiąc liczy się z `paidDate` (jawnie, w `HistoricalCashChangeImportedEventHandler`), jedyny
  konsument decydujący na `dueDate` wymaga statusu `PENDING` i `sourceRuleId`, a import ma
  `CONFIRMED` i `null`. Warto wiedzieć, że agregat zapisuje `paidDate` jako **`endDate`** —
  `CashChangeSnapshot` nie ma pola `paidDate`.
- **Brak idempotencji** (punkt 4). `bankTransactionId` wciąż nie przechodzi do cashflow, więc
  powtórzony import duplikuje dane, a detekcja duplikatów w stagingu pozostaje martwa.
  Kontrakt mówi to wprost w javadocu `importHistorical`.

## 15. Co zrobił krok 19: klucz z banku, czyli punkt 4 — wariant A

### Korekta, od której trzeba zacząć

Pisałem: „to samo co `originTradeId` dla trade'ów — klucz plus indeks unikalny". **To było błędne
co do mechanizmu**, i wyszło przy sprawdzaniu, gdzie fizycznie mieszkają transakcje:

```java
// trading — jeden dokument na trade, indeks dziala
@Document("trade")
@CompoundIndex(def = "{'portfolioId': 1, 'originTradeId': 1}", unique = true)

// cashflow — jeden dokument na CALY cash flow
@Document("cash-flow-document")
private List<CashChangeEntity> cashChanges;   // 402 transakcje w jednym dokumencie
```

MongoDB wymusza unikalność **między dokumentami**. Dla indeksu multikey dokument *może* mieć
powtórzone wartości w tablicy, a `cashFlowId` jest i tak unikalny na dokument — więc indeks na
`{cashFlowId, "cashChanges.bankTransactionId"}` byłby spełniony trywialnie i **nie chroniłby przed
niczym**. Baza nie ma jak pomóc, dopóki transakcje są osadzone.

Dlatego straż jest w agregacie, który i tak trzyma wszystkie swoje transakcje w pamięci:

```java
// CashFlow
public Optional<CashChangeId> findImported(String bankTransactionId)

// ImportHistoricalCashChangeCommandHandler
Optional<CashChangeId> alreadyImported = cashFlow.findImported(command.bankTransactionId());
if (alreadyImported.isPresent()) {
    return alreadyImported.get();   // nic nie zapisuje
}
```

### Druga naprawa w tej samej cenie: martwy kod zaczął działać

Detekcja duplikatów w stagingu istniała i **nie mogła się nigdy wykonać** — z dwóch różnych
powodów:

| | przed | po |
|---|---|---|
| `HttpCashFlowServiceClient.extractTransactionIds` | `return new HashSet<>()` z komentarzem „the API might return bankTransactionIds… For now, return empty set" | czyta `bankTransactionId` z odpowiedzi endpointu |
| `TestCashFlowServiceClient` | wypełniał zbiór **`CashChangeId`-ami** (`CC1000000073`) — porównanie z innej przestrzeni nazw | czyta `bankTransactionId` ze snapshotu |

Zbiór niepusty w testach sprawiał, że suita **wyglądała** na pokrywającą tę funkcję.
`shouldDetectATransactionAlreadyImported` jest pierwszym testem, który ją faktycznie sprawdza.

### Usterka znaleziona po drodze: dwie ścieżki rehydracji

Pierwsza wersja idempotencji **nie działała** i test to pokazał (`CC1000000001` vs `CC1000000002`).
Przyczyna:

```java
// CashFlow.from(snapshot) — recznie przepisany builder, osobno od CashChange.from
.sourceRuleId(cashChangeSnapshot.sourceRuleId())
.selfTransfer(cashChangeSnapshot.selfTransfer())
.build())                                           // ← bankTransactionId gubiony
```

`CashChange.from(snapshot)` istniał i był kompletny, ale `CashFlow.from` miał **drugą kopię tego
mapowania**, wypisaną z ręki. Dodanie pola do jednej z dwóch ścieżek wystarczyło, żeby wszystko
ładowane z bazy miało `null`. `CashFlow.from` deleguje teraz do `CashChange.from`.

**Pole istniejące w dwóch ścieżkach mapowania w końcu zacznie istnieć tylko w jednej.**

### Reguła all-args zrobiła swoje

Dodanie pola do `CashChangeSnapshot` i do zdarzenia zatrzymało kompilację w **39 miejscach**:
23 obiekty oczekiwane w `BankDataIngestionHttpIntegrationTest`, 6 snapshotów w
`CashFlowAggregateTest`, 10 zdarzeń w `CashFlowForecastProcessorTest`. Dokładnie po to jest zasada
„konstruktory, nie buildery, dla obiektów oczekiwanych" z `CLAUDE.md` — przy builderze nowe pole
przeszłoby niezauważone w każdym z tych 39 miejsc.

### Weryfikacja

| co | wynik |
|---|---|
| `HttpCashFlowModuleApiIntegrationTest` | 10 → **13**: ta sama transakcja dwa razy → jeden wpis i ten sam id; dwie różne o identycznej treści → dwa wpisy; `null` nie pasuje do niczego |
| `BankDataIngestionControllerTest` | 32 → **33**, pierwszy test detekcji duplikatów |
| `CashFlowSkeletonComponentTest` | 11 (klucz dopisany do istniejących asercji) |

| sabotaż | co upada |
|---|---|
| straż w `findImported` wyłączona | `shouldBeIdempotentOnTheBanksOwnTransactionId` — dwa różne `CashChangeId` |
| dubler z powrotem na `CashChangeId` | `shouldDetectATransactionAlreadyImported` — `Expected size: 1 but was: 0` |

### Czego to nadal nie daje — powiedziane wprost

1. **Gwarancji w bazie.** To straż w kodzie. Wariant B (transakcje jako własna kolekcja) jest jedyną
   drogą do indeksu unikalnego i jest osobnym, dużo większym zadaniem.
2. **Odporności na wyścig.** `@Version` **nie występuje w całym projekcie**, a zapis podmienia cały
   dokument, więc dwie równoległe operacje na tym samym cash flow gubią jedną z nich — niezależnie
   od tej zmiany. To problem każdej operacji na cash flow, nie tylko importu, i wart osobnej
   pozycji na tablicy.

## 16. Co zrobił krok 20: `rollbackImport` — liczby, które agregat policzył i wyrzucił

### Trzech konsumentów, trzy różne wymyślone odpowiedzi

`RollbackImportCommandHandler` liczył dokładnie to, o co wszystkim chodziło:

```java
int transactionsCount = snapshot.cashChanges().size();
int categoriesCount = command.deleteCategories() ? countCustomCategories(...) : 0;
... new ImportRolledBackEvent(cashFlowId, transactionsCount, categoriesCount, ...)
return cashFlow.getSnapshot();   // ← snapshot PO rollbacku: cashChanges puste, liczb nie ma
```

Po czym każdy konsument radził sobie sam:

| | co robił |
|---|---|
| endpoint `DELETE /cash-flow/cf={id}/import` | zwracał **zahardcodowane `0`** dla obu liczb, z komentarzem „Actual count is logged, response shows post-rollback state" — czyli **frontend był informowany, że nic nie usunięto** |
| `HttpCashFlowServiceClient` | liczył **odejmowaniem przez pięć żądań**: `getCashFlowInfo` (2 żądania) → `DELETE` z `toBodilessEntity()` → `getCashFlowInfo` (2 żądania) → różnica. Dodatkowo wyścig: cokolwiek zmieni się pomiędzy, psuje licznik |
| `TestCashFlowServiceClient` | **nie wykonywał rollbacku wcale** — nie wysyłał `RollbackImportCommand`, zwracał liczbę transakcji, które *nadal tam są*, oraz `deleteCategories ? 0 : 0` (ternary z zerem w obu gałęziach) |

### Dlatego test przechodził z niewłaściwego powodu

```java
assertThat(rollbackResponse.getRollbackSummary().getTransactionsDeleted())
        .isEqualTo(transactionsImported);
```

Dubler zwracał liczbę transakcji nieusuniętych, co przypadkiem równa się liczbie zaimportowanych.
**Żaden test nie sprawdzał, czy po rollbacku cash flow jest pusty.**

A `shouldEmitRollbackEvent` miał zaszyte `categoriesDeleted = 0` — czyli **oczekiwanie kodujące
usterkę dublera**. Po podłączeniu prawdziwego rollbacku test upadł, pokazując `1`, co jest prawdą:
import założył jedną kategorię i rollback ją usunął. Oczekiwanie poprawione na `1`.

### Co zostało zrobione

Handler zwraca `RollbackImportResult` (liczniki + status) zamiast snapshotu, a kontrakt dostał:

```java
RollbackResult rollbackImport(CashFlowId cashFlowId, boolean deleteCategories);
record RollbackResult(int transactionsDeleted, int categoriesDeleted) {}
```

Jedna zmiana typu zwracanego naprawia **cztery rzeczy**:

| | |
|---|---|
| endpoint przestaje kłamać | prawdziwe liczby zamiast `0` |
| 5 żądań → 1 | adapter HTTP **czyta odpowiedź**, zamiast ją wyrzucać |
| wyścig znika | liczby pochodzą z tej samej transakcji, która usuwała |
| dubler znika | implementacja in-process jest tym, co dubler udawał — więc test przestaje przechodzić przypadkiem |

Przy okazji usunięte martwe pole: `RollbackResult.cashFlowInfoAfter` było deklarowane i **nigdy
nieczytane**.

### I jeszcze jeden wołający odłączony od portu

`RollbackImportJobCommandHandler` używał `getCashFlowInfo` **wyłącznie po `isInSetupMode()`** —
czyli dwa żądania HTTP i payload z każdą transakcją za jeden boolean. Teraz
`cashFlowApi.skeletonOf(...).isInSetup()`, a handler nie wstrzykuje portu wcale.

### Port: jedna metoda

```
exists · createCategory · importHistorical · rollbackImport  →  kontrakt
getCashFlowInfo                                               →  zostaje
```

I zostaje z powodu, który nie jest kwestią rozmiaru: **scala stan cashflow ze statusami miesięcy
z modułu prognoz** za jedną sygnaturą, robiąc pod spodem dwa żądania. Z pięciu wołających **czterem
wystarczy to, co `CashFlowSkeletonView` już nosi**; piąty chce statusów miesięcy, które należą do
`cashflow_forecast_processor` — czyli to argument za **drugim kontraktem**, nie za metodą w tym.

### Weryfikacja

| co | wynik |
|---|---|
| `HttpCashFlowModuleApiIntegrationTest` | 13 → **16**: rollback opróżnia cash flow i podaje `3`/`2`; `deleteCategories=false` zostawia kategorie i podaje `0` **bo o nie nie proszono, a nie bo nikt nie policzył**; nieznany cash flow → `CashFlowNotFoundException` |
| `BankDataIngestionControllerTest` | **33**, z jednym oczekiwaniem poprawionym z `0` na `1` |

| sabotaż | co upada |
|---|---|
| przywrócone zahardcodowane `0` w kontrolerze | `shouldRollbackOverHttpAndReportRealCounts` i `shouldRollbackWithoutTouchingCategories` — `expected: 3 but was: 0` |

### Czego krok 20 nie zrobił

Nie dotknąłem `getCashFlowInfo`. Rozbicie go na „szkielet + `importedSourceIds` + statusy miesięcy"
jest opisane w analizie kandydatów i czeka na decyzję — w szczególności dlatego, że trzecia część
nie jest kontraktem cashflow.

## 17. Co zrobił krok 21: rozbicie `getCashFlowInfo`, części 1 i 2

Analiza mówiła, że ta metoda rozpada się na trzy i tylko jedna część jest kontraktem cashflow.
Wykonane części 1 i 2; część 3 (statusy miesięcy) zostaje świadomie.

### Część 1 — operacje drzewa na szkielecie

Cztery metody pomocnicze `CashFlowInfo` nie były danymi, tylko **operacjami na danych, które
szkielet już nosi**:

| `CashFlowInfo` | `CashFlowSkeletonView` |
|---|---|
| `findCategoryNameIgnoreCase(String)` → `Optional<String>` | `findCategoryIgnoringCase(String)` → `Optional<CategoryName>` |
| `findCategoryNameIgnoreCase(String, Type)` | `findCategoryIgnoringCase(String, Type)` |
| `findParentCategory(String, Type)` | `parentOf(CategoryName, Type)` |
| `getAllTypedCategoryKeys()` → `Set<TypedCategoryKey(String, Type)>` | `typedCategoryNames()` → `Set<TypedCategoryName(CategoryName, Type)>` |

**Dlaczego to należy do kontraktu, a nie do konsumenta.** Krok 14 uczynił szkielet typowanym
z uzasadnieniem: *„a name read out of a bank statement is not the same thing as a category until
someone says it is"*. `findCategoryIgnoringCase` **jest tym momentem, w którym ktoś to mówi** —
bierze surowy string z banku, przycina, składa wielkość liter i oddaje nazwę kategorii. To polityka
dopasowywania, więc należy do właściciela kategorii. Dopóki robili to konsumenci, każdy mógł ją
mieć inną — i jeśli kiedyś znaczenie „ta sama kategoria" obejmie ogonki czy interpunkcję, wszyscy
zaczną się zgadzać tego samego dnia.

Typowanie od razu wyciągnęło, że `RevalidateStagingCommandHandler` żonglował `String`-ami:
`Optional<String>`, `String actualCategoryName`, dwie sygnatury pomocnicze i dwa
`new CategoryName(...)` na wyjściu. Teraz `CategoryName` jedzie przez cały handler.

### Część 2 — jedna nowa metoda

```java
Set<String> importedSourceIds(CashFlowId cashFlowId);
```

**Osobna metoda, nie pole szkieletu** — bo `skeletonOf` ma własność przypiętą testem: cash flow
z czterema latami historii odpowiada tym samym payloadem co utworzony minutę temu. Zbiór 402
identyfikatorów ją niszczy, a płaciłby za to każdy, kto chce tylko fazę — na przykład
`RollbackImportJobCommandHandler`, który od kroku 20 pyta wyłącznie o `isInSetup()`.

### Trzech wołających odłączonych od portu

| handler | co teraz woła |
|---|---|
| `StartImportJobCommandHandler` | **tylko `skeletonOf`** — zero nowych metod, dokładnie jak przewidywała analiza |
| `ForceUncategorizedCommandHandler` | `skeletonOf` + `importedSourceIds` |
| `RevalidateStagingCommandHandler` | `skeletonOf` + `importedSourceIds` |

Wszystkie trzy **nie wstrzykują już portu wcale**. Został jeden wołający:
`StageTransactionsCommandHandler`, bo potrzebuje statusów miesięcy.

### Korekta: reguła dat istniała **cztery** razy, nie dwa

W kroku 18 napisałem, że reguła dat „istniała dwa razy" — agregat i staging. To było **zaniżone**.
Przy konwersji tych dwóch handlerów znalazłem **dwie kolejne kopie**, z własnym brzmieniem dla tego
samego warunku:

| plik | komunikat |
|---|---|
| `StageTransactionsCommandHandler` | naprawiony w kroku 18 |
| `ForceUncategorizedCommandHandler` | `"In SETUP mode, paidDate %s must be before activePeriod %s"` |
| `RevalidateStagingCommandHandler` | `"paidDate %s is not before activePeriod %s"` |

Czyli: cztery implementacje jednej reguły, trzy z nich jako tekst, i każda mogła się rozejść
osobno. Sprawdziłem tamtego dnia tylko handler, który konwertowałem. Teraz w `bank_data_ingestion`
jest **zero** kopii — wszystkie trzy przeszły na `skeleton.importWindow().refusalFor(...)`.

### Weryfikacja

| co | wynik |
|---|---|
| `CashFlowSkeletonViewTest` (nowy) | **8** — w tym to, że jednostronny lookup nie przechodzi między kierunkami (cash flow może mieć „Transfer" po obu stronach) i że `allCategoryNames()` te dwie strony scala, co jest powodem istnienia `typedCategoryNames()` |
| `ImportWindowTest` | 10 |
| `HttpCashFlowModuleApiIntegrationTest` | 16 → **18** — `importedSourceIds` zgłasza dwa zaimportowane i pomija ten bez identyfikatora; nieznany cash flow → `CashFlowNotFoundException` |

### Czego krok 21 nie zrobił

**Część 3: statusy miesięcy.** Zostają w `StageTransactionsCommandHandler` przez port, bo:

1. źródłem jest `GET /cash-flow-forecast/cf={id}/month-statuses` w **innym module**;
2. to **model odczytu utrzymywany asynchronicznie** ze zdarzeń Kafki, więc nie jest synchronicznie
   spójny z cashflow — kontrakt cashflow podawałby cudzą projekcję, potencjalnie opóźnioną;
3. ma **sześć statusów miesiąca** (`IMPORT_PENDING`, `IMPORTED`, `ROLLED_OVER`, `ACTIVE`,
   `FORECASTED`, `ATTESTED`), podczas gdy agregat cashflow zna trzy.

To jest argument za `cashflow-forecast-api`, nie za metodą tutaj.

**I znalezisko, które nie zależy od żadnej decyzji:** odmowa importu do miesiąca `ATTESTED` —
reguła „ten miesiąc jest tylko do odczytu" — **nie jest pokryta ani jednym testem**. Dubler
`TestCashFlowServiceClient.generateMonthStatuses` wymyśla statusy z heurystyki i sam przyznaje się
w komentarzu, że nie umie `ATTESTED` ani `IMPORTED`: *„For test purposes, assume ROLLED_OVER for
past months"*. Jedyne wystąpienia `ATTESTED` w `src/test` to ten komentarz i dwa generatory, których
surefire nie uruchamia. **Czwarty taki przypadek w tej serii** po `exists`, detekcji duplikatów
i rollbacku.


## 18. Co zrobił krok 22: `Set<String>` nie potrafił powiedzieć, z czym kolidujesz

### Usterka, która to uruchomiła

`DuplicateInfoJson.duplicateOf` ma javadoc: *„ID of the existing transaction this is a duplicate
of"*. Wszystkie trzy call sites wypełniały je tak:

```java
return TransactionValidation.duplicate(st.originalData().bankTransactionId());
```

Czyli identyfikatorem **badanego wiersza**. Frontend dostawał:

```json
{ "bankTransactionId": "NEST-001", "name": "Biedronka", "duplicateOf": "NEST-001" }
```

Pole obiecujące odpowiedź, zawierające pytanie. I **nie dlatego, że ktoś je źle wypełnił** — bo
`importedSourceIds` zwracało `Set<String>`, więc `CashChangeId` istniejącej transakcji nigdy nie
przechodził przez granicę. Nie było czym wypełnić.

### Co zastąpiło zbiór identyfikatorów

```java
ImportedTransactions alreadyImported(CashFlowId cashFlowId);
```

```java
public record ImportedTransactions(Map<String, ImportedTransaction> bySourceId) {
    boolean contains(String sourceId);
    Optional<ImportedTransaction> find(String sourceId);
    int count();  boolean isEmpty();  Collection<ImportedTransaction> all();
}

public record ImportedTransaction(String sourceId, CashChangeId cashChangeId,
                                  ZonedDateTime paidDate, Money money, CategoryName categoryName) {}
```

**Dlaczego kolekcja jest nazwanym typem, a nie `Set<ImportedTransaction>`.** Każdy wołający zadaje
to samo pytanie — „widziałem już ten identyfikator?" — a na zbiorze rekordów `contains` jest
bezużyteczne: trzeba mieć **cały rekord**, żeby sprawdzić obecność. Każdy konsument budowałby sobie
indeks. Nazwany typ go nosi, więc call site pozostaje tą jedną linią, którą był:

```java
if (alreadyImported.contains(sourceId)) { ... }
```

**Dlaczego `paidDate`, `money` i `categoryName`, choć nie mają konsumenta w kodzie.** „12 z 402
wierszy to duplikaty" jest dla człowieka nieweryfikowalne. Dwie prawdziwe wizyty w tym samym
sklepie, tego samego dnia, na tę samą kwotę to realny przypadek — `shouldNotConfuseTwoGenuinelyDifferentTransactions`
istnieje właśnie dlatego, że odróżnia je **wyłącznie** identyfikator źródłowy. Lista duplikatów jest
pokazywana użytkownikowi, więc musi dać mu czym ocenić.

### Zmiana, bez której dane stanęłyby na granicy

`TransactionValidation.duplicate(String)` → `duplicate(CashChangeId)`. Bez tego nowy typ dojechałby
do konsumenta i `duplicateOf` nadal dostawałoby to samo co dotąd.

**Dowód negatywny wyszedł mocniejszy, niż planowałem.** Przywróciłem tautologię:

```java
TransactionValidation.duplicate(CashChangeId.of(txn.bankTransactionId()));
```

i test nie zawiódł asercji — **wywalił się na typie**:

```
InvalidCashChangeIdFormatException: Invalid CashChange ID format: 'txn-001'.
Expected: CCXXXXXXXXXX (e.g., CC1000000001)
```

Stary błąd przestał być reprezentowalny. Identyfikator z banku nie przechodzi tam, gdzie stoi
`CashChangeId`.

### Martwe pole, które przy okazji odpadło

`CashFlowInfo.existingTransactionIds` straciło **ostatniego czytającego w produkcji**. Usunięte
razem z `HttpCashFlowServiceClient.extractTransactionIds` i wypełnianiem w obu dublerach. Przez
długi czas i tak nie działało: w produkcji zwracało pusty zbiór, w testach identyfikatory z innej
przestrzeni nazw.

### Weryfikacja

| co | wynik |
|---|---|
| `ImportedTransactionsTest` (nowy) | **7** — w tym że `contains(null)` nie pasuje do wszystkiego, że pusty wynik znaczy „nic nie zaimportowano", i że indeks jest kopiowany, więc wołający nie zmieni go pod stopami |
| `CashFlowSkeletonViewTest` | 8 |
| `ImportWindowTest` | 10 |
| `HttpCashFlowModuleApiIntegrationTest` | **18** — `find("NEST-A")` oddaje `CashChangeId`, kwotę ze skalą i `paidDate` czytany z `endDate` |
| `BankDataIngestionControllerTest` | **33** — `shouldDetectATransactionAlreadyImported` asertuje teraz, że `duplicateOf` **wskazuje istniejącą transakcję**, a nie samo siebie |


## 19. Co zrobił krok 23: `bank_data_ingestion` jako osobny artefakt

### Dlaczego ten, a nie cashflow

Pytanie wyszło od drugiej strony — od wydzielenia `cashflow` + `cashflow_forecast_processor`.
Pomiar pokazał, że **tamto ma warunek wstępny, a to nie**:

| | wydzielić cashflow | wydzielić ingestion |
|---|---|---|
| zależności wychodzące wyciąganego | **0** | 3, z czego 2 już przez istniejące `-api` |
| blokada | **cykl w reaktorze**: `CashFlowIntegrationTest` leży w pakiecie `cashflow`, a wiąże dubler portu należącego do ingestion | **jeden enum** |
| warunek wstępny | likwidacja portu (część 3) | **brak** |
| potrzebny `-api` dla wyciąganego | już jest | **nie** |

### Macierz, która to przesądziła

```
                             cashflow  forecast  ingestion  adapter  profile
cashflow                         –         0          0        0        0
cashflow_forecast_processor     76         –          0        0        0
bank_data_ingestion             75         0          –        8        4
bank_data_adapter                0         0          0        –        0
user_financial_profile           5         0          0        0        –
```

**Te 75 importów z cashflow idą wyłącznie przez `cashflow.api`. Ani jeden nie dotyka agregatu** —
to wynik kroku 15, i to on czyni ten moduł budowalnym osobno. Gdyby ingestion sięgał do
`cashflow.domain`, wydzielenie byłoby niemożliwe bez wcześniejszej pracy nad kontraktem.

### `-api` nie powstał, i to jest decyzja

Przeszukane całe repozytorium: **ani jeden plik produkcyjny nie importuje `bank_data_ingestion`.**
Wszystkie odwołania z zewnątrz to testy. Czyli ten sam przypadek, który `CLAUDE.md` opisuje dla
`vidulum-pnl` i `vidulum-risk`: *kontrakt powstaje, gdy pojawia się konsument*. Ingestion konsumuje
kontrakty i odpowiada frontendowi po REST — nikt go o nic nie pyta.

### Jedyna blokada: jeden enum — i moja pomyłka przy jej rozwiązaniu

`TransactionClassification` — czysty enum opisujący, czym AI uznało transakcję. Osiem importów
w ingestion, mieszkał w `bank_data_adapter.domain`.

**Założyłem dla niego moduł `vidulum-bank-data-adapter-api` i to było przeinżynierowanie.**
Pytanie, którego nie zadałem przed zrobieniem: *jak ta klasyfikacja w ogóle przechodzi z adaptera
do ingestion?* Odpowiedź przesądza wszystko:

```java
// bank_data_adapter — DOPISUJE kolumne do CSV
newHeaderLine = headerLine + ",classification,classificationReason,location";
```

```java
// bank_data_ingestion — CZYTA ja jako tekst
return TransactionClassification.fromString(getOptionalString(record, header));
```

**Te moduły nigdy nie wymieniają typu Javy. Wymieniają kolumnę CSV.** Faktycznym kontraktem jest
nazwa kolumny i zbiór dopuszczalnych napisów, więc wspólny enum był **wygodą udającą zależność** —
a moduł Mavena dla niego był zależnością wymyśloną, żeby wyrazić coś, co CSV już wyraża.

Co więcej, projekt **już to zakładał**:

```java
catch (IllegalArgumentException e) { return UNKNOWN; }
```

Nieznane słowo daje `UNKNOWN`, nie wyjątek. To jest definicja kontraktu tekstowego: producent może
znać słowa, których konsument nie zna.

**Rozwiązanie: każdy moduł ma własny enum.** Adapter w `bank_data_adapter.domain`, ingestion
w `bank_data_ingestion.domain`, kontraktem zostaje kolumna. Moduł `bank-data-adapter-api` usunięty.
Efekt jest lepszy niż pierwotny: **ingestion zależy teraz wyłącznie od `vidulum-shared-kernel`,
`vidulum-cashflow-api` i `vidulum-user-financial-profile-api`** — i od adaptera nie zależy wcale.

Ryzyko rozjazdu nie zniknęło, tylko przestało być zamaskowane wspólnym typem — i dostało test.
`TransactionClassificationVocabularyTest` sprawdza, że każde słowo, które adapter umie zapisać,
ingestion umie przeczytać, i w drugą stronę. Dowód negatywny: stała dopisana tylko po stronie
adaptera wywala dwa z trzech testów. Bez niego taki rozjazd zamieniłby każdą transakcję z nowym
słowem na `UNKNOWN` **w ciszy**.

### Testy: siedem zostaje, pięć jedzie

Podział nie jest arbitralny. Testy, które potrzebują **implementacji** cashflow albo wspólnej bazy
`CashFlowIntegrationTest`, muszą zostać u rodzica — inaczej byłby cykl:

```
vidulum-bank-data-ingestion  →  vidulum-cashflow-api        (compile)
vidulum-cashflow             →  vidulum-bank-data-ingestion (test + test-jar)
```

Acykliczne **tylko dlatego, że ingestion zależy od kontraktu, nie od implementacji.**

To ten sam układ, który u nas działa od dawna: `vidulum-recurring-rules` jest osobnym modułem,
a jego `RecurringRulesHttpIntegrationTest` leży w `vidulum-cashflow/src/test`. Czyli
`vidulum-cashflow/src/test` **pełni już rolę** tego, czym dla `wealth` jest `vidulum-wealth-tests`
— tylko bez osobnej nazwy.

| | |
|---|---|
| zostaje u rodzica | 8 plików (7 testów + aktor): `BankDataIngestionControllerTest`, `…HttpIntegrationTest`, `DirectCategoryMatch`, `PatternMatching`, `StagingSessionLifecycle`, `HttpCashFlowServiceClient…`, `…ErrorHandlingTest`, `BankDataIngestionHttpActor` |
| jedzie do nowego modułu | 5 plików, **104 testy** |

**Te 104 testy wykonują się w 0,4 sekundy** — bez Springa, bez Testcontainerów. Dotąd zmiana
w ingestion odpalała ~4-minutową suitę całego `cashflow`.

### Granica jest teraz sprawdzana kompilatorem, nie testem

Dowód negatywny wyszedł mocniejszy, niż planowałem. Dodałem do `cashflow.app` import
z `bank_data_ingestion.app` — i **kompilacja padła**, bo `cashflow` ma ingestion wyłącznie
przy `test` scope.

Dotąd tej granicy pilnował `ModuleBoundariesArchTest` (zielony od kroku 15). Teraz pilnuje jej
Maven. Pieczęcie `assertSealed("bank_data_ingestion", …)` i `assertSealed("bank_data_adapter", …)`
dopisane jako druga linia — na wypadek, gdyby ktoś dodał zależność przy `compile`. Pieczęć
adaptera jest przy tym **wreszcie prawdziwa**: po rozdzieleniu enumów ingestion nie importuje
z adaptera nic.

### Czego to nie daje

**Niezależnego wdrożenia.** `vidulum-app` nadal montuje wszystko w jeden kontekst — tak samo jak
przy `wealth`. To jest monolit z twardymi granicami, nie mikroserwisy.

### Wart odnotowania wart

**Konfiguracja się nie przeniosła.** Klucze `vidulum.ingestion.*`, `vidulum.cashflow-service.*`
i `bank-data-ingestion:` zostały w `vidulum-cashflow/src/main/resources/application.yml`. Runtime
działa, bo ten plik jest nadal na classpathie aplikacji, a **wszystkie klucze ingestion mają
wartości domyślne w `@Value`** — sprawdzone. Ale leżą w złym miejscu i warto je przenieść przy
okazji; to osobna, drobna porządkowa zmiana.


## 20. Co zrobił krok 24: `bank_data_adapter` jako osobny artefakt

Najtańsze wydzielenie z całej serii, i pomiar mówi dlaczego.

### Zależności: zero w obie strony

```
                             cashflow  forecast  adapter  profile  ingestion
bank_data_adapter                0         0        –        0         0
ktokolwiek  ->  adapter          0         0        –        0         0
```

Po kroku 23, w którym `TransactionClassification` przestał być wspólnym typem, adapter jest
**całkowicie odizolowany**. Z shared-kernela bierze 27 typów (`common` ×26, `shared` ×1) — to
zostaje i jest normalne.

Lepszy stan niż ingestion przed wydzieleniem, które miało trzy zależności wychodzące.

### Niuans, którego importy nie pokazały

Adapter ma **trzeci ręcznie pisany klient HTTP** w tym repozytorium:

```java
// bank_data_adapter/infrastructure/BankDataIngestionClient.java
String url = baseUrl + "/api/v1/bank-data-ingestion/cf=" + cashFlowId + "/upload";
```

z komentarzem przyznającym wzorzec wprost:

```java
/** Matches BankDataIngestionDto.StageTransactionsResponse structure. */
public record UploadCsvResponse(...)
```

Czyli ten sam anty-wzorzec co przy `HttpCashFlowServiceClient` i `CashFlowHttpClient`: **konsument
deklaruje kształt API producenta**. I jedna rzecz warta zapamiętania o kierunku: **adapter woła
ingestion**, nie na odwrót — mimo że przepływ biznesowy („najpierw transformuj CSV, potem
zaimportuj") sugeruje coś innego.

**Dla wydzielenia to nie była blokada** — gada po URL-u, więc przeżyło granicę bez zmiany, jak port
cashflow w kroku 23. Zapisane, nie rozwiązane.

### Testy: 13 jedzie, 2 zostają

Najlepszy stosunek z dotychczasowych (ingestion miał 8 zostających na 5 jadących):

| | |
|---|---|
| **jadą** | 13 plików, **111 testów**, wszystkie bez Springa |
| **zostają** | **2**: `AiBankCsvAdapterErrorHandlingTest` i `UnifiedCsvImportIntegrationTest`, wyłącznie przez wspólną bazę `CashFlowIntegrationTest` |

Oba zostające używają aktorów z listy „jadą", więc `test-jar` był konieczny — tak samo jak przy
ingestion.

### Stan `cashflow` po tym kroku

```
vidulum-cashflow/
├── cashflow-api/                 kontrakt
├── user-financial-profile-api/   kontrakt
├── bank-data-adapter/            NOWY
├── bank-data-ingestion/          (krok 23)
├── recurring-rules/
└── src/   ←  cashflow, cashflow_forecast_processor, user_financial_profile
```

**Jeden krok od celu**, o który pytanie wyszło: zostaje `user_financial_profile`, z trzema typami
z `cashflow.domain` jako jedyną przeszkodą (`BankAccountNumber` ×3, `BankAccount`,
`CashFlowEvent`), a jego `-api` już istnieje.

### Czego ten krok nie dał

To samo, co poprzedni: **niezależnego wdrożenia** (`vidulum-app` nadal montuje wszystko w jeden
kontekst) i **konfiguracji we właściwym miejscu** — klucze `vidulum.ai.*`,
`bank-data-ingestion.base-url` i reszta zostały w `application.yml` rodzica, tak jak klucze
ingestion. Działa, bo ten plik jest na classpathie aplikacji, ale leży nie tam, gdzie powinien.
Zbiera się z tego osobna, drobna porządkowa zmiana — już druga.


## 21. Co zrobił krok 25: `user_financial_profile` — i dwa fałszywe podejścia po drodze

Cel osiągnięty: **`vidulum-cashflow/src/main` to już tylko `cashflow` + `cashflow_forecast_processor`.**

### Blokada: 5 importów, 3 typy

| gdzie | co |
|---|---|
| `UserFinancialProfileService` | `BankAccount`, `BankAccountNumber` |
| `UserFinancialProfileCashFlowListener` | `CashFlowEvent` |
| `UserFinancialProfileEntity`, `OwnedBankAccount` | `BankAccountNumber` |

### Pierwsze podejście, które zawinąłem

Przeniosłem sześć rekordów bankowych (`BankAccount`, `BankAccountNumber`, `IbanNumber`,
`CountryCode`, `BankCode`, `BranchCode`) do `cashflow-api`. Kompilator pokazał, dlaczego to błąd:

- przegapiłem siódmy typ (`BankIdentifierCode`),
- a **`IbanNumber` ciągnie `org.iban4j`** i własny wyjątek.

Czyli kontrakt dostałby zależność od biblioteki zewnętrznej, wbrew regule *„`*-api` deklaruje żadnej
z nich i ma dokładnie jedną zależność"*. **Zwalidowany `BankAccount` to domena cashflow, nie
słownik** — walidacja jest zachowaniem. Zawinąłem to, zamiast przepychać.

### Co okazało się prawdą po zmierzeniu

Profil **nigdy nie używał tego typu jako obiektu wartości**. Wszystkie siedem miejsc czytających
natychmiast go spłaszcza:

```java
account.bankAccountNumber().fetchRawIban()
account.bankAccountNumber().denomination().getId()
```

a encja i tak przechowuje `String iban`, `String currency`, `String bankName`. I jedna rzecz, którą
to ujawniło:

```java
// UserFinancialProfileEntity, przed zmianą
BankAccountNumber.fromIban(iban, Currency.of(currency))
```

**Odczyt z Mongo rewalidował IBAN przez iban4j.** Konto, którego zapisany numer iban4j odrzuciłby
dzisiaj, czyniło **cały profil nieczytelnym**. Składowanie rewalidujące to, co samo zapisało, to
tryb awarii bez korzyści.

### Drugie podejście: trzy własne rzeczy

**`OwnedAccountNumber(String iban, Currency denomination)`** — nośnik, bez walidacji przy odczycie.

**`OwnedAccountNumber.fromIban(...)`** — z walidacją, bo profil **ma własne drzwi**:
`POST /user-financial-profile/accounts` przyjmuje IBAN-y wpisane przez człowieka, a ścieżka
masowego dodawania miała komentarz `// Pre-validate: parse all IBANs (throws on invalid)`. Dlatego
profil dostał `iban4j` we własnym pomie — **dwa zamki, nie zduplikowane pojęcie**: każdy moduł
odpowiada za to, co przez jego drzwi wchodzi.

**`UserFinancialProfileCashFlowListener.CashFlowCreated`** — własny widok payloadu z topiku, pięć
zagnieżdżonych rekordów z `@JsonIgnoreProperties`, nazywających **trzy wartości** z payloadu, który
niesie nazwę, opis, oba okresy, saldo początkowe, znaczniki czasu i checksum.

Dotąd listener deserializował `CashFlowEvent.CashFlowCreatedEvent` — klasę z **domeny** producenta.
Payload na topiku jest tekstem; nazywanie klasy producenta po to, żeby go przeczytać, oznacza, że
konsumenta nie da się zbudować bez wnętrzności producenta na classpathie.

Jeden rekord na dwa typy zdarzeń, bo profil traktuje je identycznie — cash flow utworzony z historią
i bez niej oba znaczą „ten użytkownik ma to konto". `switch` mówi to jedną etykietą zamiast dwoma
blokami wołającymi tę samą metodę.

### Testy: 1 jedzie, 2 zostają

| | |
|---|---|
| jedzie | `UserFinancialProfileHttpActor` |
| zostają | `SelfTransferDetectionIntegrationTest`, `UserFinancialProfileHttpIntegrationTest` — potrzebują wspólnej bazy i działającego cash flow |

Nowy moduł nie ma więc własnych testów — jego dwa testy integracyjne zostały u rodzica, a `test-jar`
oddaje im aktora. To nie jest luka: jego zachowanie jest w całości pokryte, tylko z miejsca, gdzie
widać oba moduły.

### Lista odstępstw ArchUnita schudła do jednego

```java
assertSealed("user_financial_profile", "com.multi.vidulum.user_financial_profile..");

assertSealed("cashflow",
        "com.multi.vidulum.cashflow..",
        "com.multi.vidulum.cashflow_forecast_processor..");   // bylo tez user_financial_profile
```

Zostało **jedno** odstępstwo, i to takie, które ma zostać: prognozy są read modelem cashflow,
mieszkają w tym samym artefakcie z rozmysłu i projektują z 36 importów `CashFlowEvent`.

### Stan końcowy

```
vidulum-cashflow/
├── cashflow-api/                     kontrakt
├── user-financial-profile-api/       kontrakt
├── user-financial-profile/           NOWY  (krok 25)
├── bank-data-adapter/                (krok 24)
├── bank-data-ingestion/              (krok 23)
├── recurring-rules/
└── src/   ←  cashflow + cashflow_forecast_processor
```


## 22. Co zrobił krok 26: `vidulum-cashflow-tests`

### Problem, który zostawiły trzy poprzednie kroki

Po wydzieleniu ingestion, adaptera i profilu `cashflow` był **dwiema rzeczami naraz**:
implementacją agregatu i miejscem, gdzie mieszkają testy integracyjne wszystkich pozostałych.
Miał **cztery zależności `test` scope na moduły, które z niego wyszły**:

```
vidulum-bank-data-ingestion · vidulum-bank-data-adapter
vidulum-user-financial-profile · vidulum-recurring-rules
```

Konsekwencja praktyczna, która podkopywała sens tamtych kroków: **`./mvnw test -pl :vidulum-cashflow`
wymagał, żeby wszystkie cztery były wcześniej zbudowane.**

### Dlaczego te testy nie mogły pojechać ze swoimi modułami

Bo każdy z nich zależy **wyłącznie od kontraktów, nigdy od `cashflow`** — i to jest
dokładnie to, co trzyma reaktor acyklicznym. Test integracyjny potrzebuje jednak implementacji,
więc musiałby odwrócić tę zależność.

### Podział, z pomiaru

| | plików |
|---|---|
| przekraczają granice (wymagają innych modułów) | 17 |
| własne, ale potrzebują Springa/Testcontainerów | 13 |
| wspólna infrastruktura potrzebna po stronie integracyjnej | 7 |
| **jadą** | **37** |
| **zostają** — czysto jednostkowe + `TestIds` i dwa `InMemory*Repository` | **12** |

Klasyfikator dał przy tym jeden fałszywy alarm, wart zapamiętania: `CashFlowAggregateTest` wyszedł
jako „potrzebuje Testcontainerów", bo ma komentarz *„Pure unit test — no Spring context, no
Testcontainers"*. Szukanie wzorców bez usuwania komentarzy mierzy prozę, nie kod.

### Rzecz, która przesądziła o kształcie

`@SpringBootTest(classes = {FixedClockConfig.class, TestAiConfig.class})` **nie nazywa aplikacji** —
Spring szuka `@SpringBootApplication` skanując w górę od pakietu testu. `CashFlowTestApplication`
leży w `com.multi.vidulum`, więc wszystkie testy pod tym pakietem ją znajdują.

Czyli aplikacja testowa **musiała pojechać z testami**: gdyby została, przeniesione testy nie
miałyby kontekstu. A skoro zostające testy Springa nie potrzebują, `cashflow` nie ma już
aplikacji testowej wcale — i nie powinien mieć.

### Wynik

| | przed | po |
|---|---|---|
| `cashflow`: zależności `test` scope | **8** (4 moduły + 4 `test-jar`) | **0** |
| `cashflow`: testy | ~500, ~4 min, Testcontainery | **91 w 5 sekundach**, bez Dockera |
| `cashflow`: Testcontainery, `spring-kafka-test`, `awaitility`, `resttestclient` w pomie | tak | **usunięte** |
| `vidulum-cashflow-tests` | — | wszystkie testy integracyjne rodziny |

`cashflow` publikuje `test-jar` z `TestIds` i dwoma `InMemory*Repository` — jedynymi
helperami potrzebnymi po obu stronach (5 użyć po stronie zostającej, 6 po przeniesionej).

To ten sam model co `vidulum-wealth-tests`, i `CLAUDE.md` opisuje go dla `wealth/` od dawna:
*„unit and component tests only — no Spring, in-memory repositories"*. Teraz jest prawdziwy także
dla rodziny cashflow.

### Czego to nie zmienia

**Czasu pełnego reaktora.** Te same testy, ten sam Docker — przeniesione, nie usunięte. Skraca się
pętla przy pracy nad agregatem, nie przebieg CI.

**Liczby zestawów infrastruktury testowej.** Są teraz trzy równoległe (`cashflow-tests`,
`wealth-tests`, `vidulum-app`), każdy z własną bazą i własną aplikacją. To nie jest nowe, ale stało
się wyraźniejsze.


## 23. Co zrobił krok 27: `vidulum-app-cashflow` jako agregator

Po sześciu krokach `cashflow` był artefaktem, który **nazywał rodzinę, a zawierał jej
najmniejszą część**: agregat plus read model, z sześcioma modułami zagnieżdżonymi w swoim folderze.
Nazwa kłamała i struktura kłamała.

### Co się zmieniło

| | przed | po |
|---|---|---|
| `cashflow` | `jar` z `cashflow` + `cashflow_forecast_processor` **i** folderem sześciu innych modułów | **`jar` wyłącznie z agregatem i jego read modelem** |
| `vidulum-app-cashflow` | — | **`pom`, sam `<modules>`**, bez źródeł i bez zależności |
| wpisy w korzeniu reaktora | siedem | **jeden** |

```
vidulum-app-cashflow/          pom, agregator
├── cashflow-api/              kontrakt
├── user-financial-profile-api/ kontrakt
├── cashflow/                  agregat + read model (artifactId vidulum-cashflow)
├── user-financial-profile/
├── bank-data-adapter/
├── bank-data-ingestion/
└── recurring-rules/
```

### Wzorzec nie jest nowy

To dokładnie to, czym są `market/` i `wealth/` od kroku 10: **agregator, nie rodzic**. Każdy modul
poniżej dziedziczy z poma korzennego (`../../pom.xml`), więc przeniesienie go do innego folderu nie
zmienia ani jednej zależności ani `artifactId`. Grupa buduje się samodzielnie:

```bash
./mvnw test -f vidulum-app-cashflow/pom.xml
```

### Co dzięki temu stało się widoczne w jednym miejscu

Opis agregatora zapisuje dwie reguły, które do tej pory trzeba było wyczytać z siedmiu pomów:

1. **`cashflow` nie jest zależnością nikogo w tej grupie.** Cztery moduły konsumujące
   kompilują się wobec `cashflow-api` i `user-financial-profile-api`, nigdy wobec implementacji — i
   to jest dokładnie to, co pozwala `vidulum-cashflow-tests` trzymać testy integracyjne wszystkich
   bez cyklu.
2. **Żaden moduł w grupie nie ma zależności `test` scope na inny.** Testy wymagające działającego
   systemu są poza grupą.

Kolejność w `<modules>` to kolejność zależności: kontrakty, agregat, konsumenci.

### Uwaga o nazwie

`market` i `wealth` mają foldery o krótkich nazwach i `artifactId` `vidulum-market` /
`vidulum-wealth`. Tutaj folder agregatora i jego `artifactId` to oba `vidulum-app-cashflow` —
odstępstwo od tej konwencji, zgodnie z decyzją właściciela kodu.

Folder implementacji nazywa się natomiast **`cashflow/`**, przy `artifactId` `vidulum-cashflow` —
spójnie z rodzeństwem (`cashflow-api`, `bank-data-adapter`, `recurring-rules`). I to jest zarazem
najkrótszy dowód tezy tej sekcji: **nazwa folderu nie dotyka `artifactId`**, bo agregator nie jest
rodzicem. Każdy, kto zależy od `vidulum-cashflow`, nie zauważył zmiany.

---

## 24. Co zrobił krok 28: `CashFlowForecastApi` — drugi kontrakt w tym samym artefakcie

Ostatnia metoda portu, `getCashFlowInfo`, miała **jednego wołającego w produkcji** i strukturalny
powód, by zostać: scalała stan agregatu ze statusami miesięcy z modułu prognoz. Ten krok rozdziela
te dwie rzeczy.

### Czego naprawdę brakowało

Z jedenastu rzeczy, które `StageTransactionsCommandHandler` bierze z `CashFlowInfo`, **dziewięć
było już w `CashFlowSkeletonView`**. Brakowało dwóch, i obie dotyczyły miesięcy:

```java
cashFlowInfo.isMonthImportAllowed(paidPeriod)
cashFlowInfo.getMonthStatus(paidPeriod)
```

Pod jednym podpisem `HttpCashFlowServiceClient` robił **dwa żądania do dwóch modułów**:
`/cash-flow/cf={id}` oraz `/cash-flow-forecast/cf={id}/month-statuses`. A ponieważ ten port ma
**jedną implementację produkcyjną i jest ona HTTP**, w jednej maszynie wirtualnej staging statementu
oznaczał żądanie aplikacji **do własnego endpointu po loopbacku** — ta sama klasa defektu co
self-call w `createCategory` z kroku 17.

### Dlaczego osobny interfejs, a nie metoda na `CashFlowModuleApi`

Bo implementacja decyduje o tym, nie nazwa. `CashFlowModuleApiImpl` siedzi w `cashflow.app` i trzyma
`DomainCashFlowRepository`; żeby podać statusy miesięcy, musiałby sięgnąć po
`CashFlowForecastStatementRepository` — i to byłby **pierwszy import `cashflow` →
`cashflow_forecast_processor`**. Dziś ta krawędź ma zero wystąpień. Dwa kontrakty w jednym
artefakcie, dwie implementacje, po jednej na poddomenę:

```java
public interface CashFlowForecastApi extends ModuleApi {
    MonthStatuses monthStatusesOf(CashFlowId cashFlowId);
}
```

Osobny artefakt `cashflow-forecast-api` będzie uzasadniony wtedy, gdy read model wyjdzie z
`vidulum-cashflow` na swoje. Dziś obie implementacje jadą w jednym jarze, a kontrakt ma jedną
zależność — shared-kernel.

### Wartość, nie predykat — ten sam wybór co `ImportWindow`

```java
public record MonthStatuses(CashFlowId cashFlowId, Map<YearMonth, MonthStatus> byMonth) {
    public Optional<MonthStatus> at(YearMonth month);
    public boolean acceptsImportAt(YearMonth month);
}
```

Reguła jest pytana **raz na wiersz**, a plik testowy ma 402 wiersze — `acceptsImportAt` jako metoda
kontraktu byłaby 402 żądaniami na jeden upload. Konsument pobiera raz i liczy lokalnie, wobec logiki
producenta, nie wobec jej kopii.

**Kolejność dwóch reguł jest celowa i zapisana w kodzie.** `ImportWindow` jest warunkiem
*koniecznym* — faza cyklu życia i obie granice okresu — i należy do agregatu, czyli jest tą regułą,
którą wymusiłby zapis. `MonthStatuses` jest *ostrzejszy* i należy do modelu odczytu, więc staging
pyta o niego dopiero, gdy okno już przyjęło datę. Inaczej jeden wiersz dostawałby dwa komunikaty o
tej samej zepsutej dacie.

**Co zostało po stronie konsumenta:** same słowa. Trzy zdania o wierszu wyciągu bankowego to sprawa
stagingu; decyzja, czy miesiąc przyjmuje import, należy do modułu, który statusy posiada. To
odwrotnie niż w kroku 18, gdzie razem z regułą przeniosłem też typowane odmowy — tam odmowy były
wyjątkami z `ErrorCode`, które endpoint musi oddać; tutaj to `String` na liście walidacji.

### Dwa słowniki zamiast jednego, i co je trzyma razem

Kontrakt nie może wystawiać `CashFlowMonthlyForecast.Status` — konsument nie skompilowałby się bez
implementacji producenta na ścieżce klas. Więc `MonthStatus` jest kopią sześciu stałych, a w ryzach
trzyma je **wyczerpujący `switch`** w implementacji: nowy status w modelu odczytu **nie kompiluje
się**, dopóki kontrakt go nie nazwie. Plus test, który przechodzi po `Status.values()` i sprawdza
odpowiednik dla każdego — w tym samym duchu co `TransactionClassificationVocabularyTest`.

To jest ta sama diagnoza co w kroku 24 przy `TransactionClassification`, z przeciwnym wynikiem: tam
granicą był **tekst** (kolumna CSV), więc duplikacja plus test wystarczyły; tutaj granicą jest
**wywołanie metody w jednej JVM**, więc mapowanie jest sprawdzane przez kompilator.

### Co zniknęło

| | |
|---|---|
| `CashFlowInfo.MonthStatus` | szósta kopia tych stałych |
| `CashFlowInfo.getMonthStatus`, `isMonthImportAllowed`, pole `monthStatuses` | — |
| `HttpCashFlowServiceClient.fetchMonthStatuses` + drugie żądanie | 286 → 199 linii |
| `extractTransactionIds` | martwe od kroku 22, usunięte przy okazji |
| `TestCashFlowServiceClient.generateMonthStatuses` ×2 | heurystyka, patrz niżej |
| `CashFlowForecastRestController.extractMonthStatuses` | przejście przez kontrakt, jedno przejście po statemencie |

### F38: reguła nie była nieprzetestowana, była nieosiągalna

`TestCashFlowServiceClient.generateMonthStatuses` **wymyślał statusy z heurystyki** („past months in
OPEN mode are ROLLED_OVER") i w komentarzu przyznawał, że nie umie wyprodukować `ATTESTED` ani
`IMPORTED`. Dopóki ten dubler odpowiadał na pytanie o miesiące, odmowa importu do miesiąca
zaatestowanego **nie mogła zapalić się w żadnym teście**.

Kontrakt z jedną metodą czyni z tego wejścia jednolinijkową lambdę — i to jest cały powód, dla
którego `StagingRefusesClosedMonthsTest` może dziś istnieć:

```java
CashFlowForecastApi forecast = id -> new MonthStatuses(id, Map.of(FEBRUARY, MonthStatus.ATTESTED));
```

Cztery przypadki, każdy z datą **wewnątrz** okna importu — inaczej wiersz byłby nieważny z powodu
niemającego nic wspólnego ze statusem miesiąca, a test przechodziłby nie ćwicząc reguły, od której
wziął nazwę. Dowód negatywny: po przestawieniu `ATTESTED` na `true` w `acceptsImportAt` pada
dokładnie asercja o lutym, nie cała klasa.

### Czego nie dało się zrobić bez naprawy czegoś innego

Test integracyjny importu w trybie OPEN **zaczął padać** — i to był najcenniejszy wynik tego kroku.

Najpierw diagnoza pozorna: statement prognozy nieodczytany, bo model odczytu jest asynchroniczny,
czyli wyścig. Dodane `await()` **przeterminowało się po 10 sekundach** — statement nie był
spóźniony, on nigdy nie powstawał. W logu:

```
ERROR DefaultErrorHandler : Backoff ... currentAttempts=10, maxAttempts=9 exhausted for cash_flow-0@4
Caused by: java.lang.NullPointerException: Cannot invoke "CategoryName.equals(Object)" because
          the return value of "CashFlowEvent$CategoryCreatedEvent.parentCategoryName()" is null
          at CategoryCreatedEventHandler.fun(CategoryCreatedEventHandler.java:64)
```

`CashFlowModuleApiImpl` przepuszczał `null` wprost do `CreateCategoryCommand`, a domena zapisuje
„brak rodzica" jako `CategoryName.NOT_DEFINED`. **Kontroler REST konwertował od zawsze**
(`ofNullable(...).orElse(CategoryName.NOT_DEFINED)`), implementacja kontraktu nie — więc kategoria
najwyższego poziomu utworzona przez kontrakt zabijała projekcję prognozy dla całego cash flow, a
**każdy zapis dalej odpowiadał 2xx**. Dziewięć ponowień, zdarzenie do kosza, nic czerwonego.

To **regres z kroku 17**: dopóki ingestion tworzył kategorie przez HTTP, konwersja się zdarzała;
przeniesienie go na kontrakt ją zgubiło. Dlaczego nie wyszło wtedy: żaden test nie czytał prognozy
po kategorii utworzonej tą drogą. Krok 28 kazał stagingowi ją czytać — i wtedy wyszło.

**A najgorsza część nie jest w kodzie produkcyjnym.** Asercja, którą sam napisałem w kroku 17,
brzmiała:

```java
assertThat(handler.received.parentCategoryName())
        .as("null parent means top-level, the same encoding CategoryNodeView.isRoot() uses")
        .isNull();                         // <- wymagała defektu
```

Test przechodził, bo **wymagał** tego, co było zepsute. Sprawdzałem, czy implementacja przekazuje
argument bez zmian, zamiast czy mówi domenie to, co domena rozumie — i zielony test trzymał defekt
w miejscu. Zamienione na `isEqualTo(CategoryName.NOT_DEFINED)` z wyjaśnieniem w komunikacie.

Co z tego zostało otwarte: **brak statementu jest nieodróżnialny od miesiąca zamkniętego** —
„jeszcze nie zbudowany", „zdarzenie porzucone" i „naprawdę zamknięty" dają ten sam komunikat.
Odziedziczone (`fetchMonthStatuses` łapał każdy wyjątek i zwracał pustą mapę), zapisane jako F46.

### Liczby

| | przed | po |
|---|---|---|
| metody na porcie ingestion | 1 (dwa moduły) | 1 (jeden moduł) |
| żądania HTTP w `getCashFlowInfo` | 2 | 1 |
| testy `vidulum-cashflow-api` | 25 | 37 |
| testy `vidulum-cashflow` | 91 | 96 |
| testy `vidulum-bank-data-ingestion` | 104 | 108 |
| testy `vidulum-cashflow-tests` | 420, z cicho porzucanymi zdarzeniami | 420, bez ani jednego |

Co zostało z F26: ręczny klient w recurring-rules (`CashFlowHttpClient`) i odzyskanie siedmiu typów
cashflow ze shared-kernela. Statusy miesięcy są z tej listy zdjęte.

---

## 25. Co zrobił krok 29: port ingestion przestaje istnieć

Po kroku 28 na `CashFlowServiceClient` została jedna metoda, pytająca już tylko jeden moduł. Ten krok
ją usuwa — i okazało się, że to w większości **kasowanie, nie przepisywanie**.

### Wszystkie dziewięć użyć było już w szkielecie

| `CashFlowInfo` | `CashFlowSkeletonView` |
|---|---|
| `cashFlowId()`, `activePeriod()`, `startPeriod()` | te same komponenty |
| `userId()` → `String` | `owner()` → `UserId` |
| `status()`, `isInOpenMode()` | `phase()`, `isOpen()` |
| `findCategoryNameIgnoreCase(raw[, type])` ×4 | `findCategoryIgnoringCase(...)` → `Optional<CategoryName>` |
| `findParentCategory(String, Type)` ×2 | `parentOf(CategoryName, Type)` |
| `getAllCategoryNames()` ×2 | `allCategoryNames()` → `Set<CategoryName>` |

**Zmiany typów wyszły na plus.** Handler robił `new CategoryName(actualCategoryName)` po tym, jak
dostał `String` z lookupu, i `st.mappedData().categoryName().name()`, żeby szukać w `Set<String>` —
w obu miejscach rozpakowywał wartość, którą kontrakt oddaje gotową. Zostało jedno owinięcie, w
`CategoryBreakdownBuilder`, bo ten trzyma nazwę jako `String` dla DTO.

I zniknął prywatny `importWindowOf(cashFlowInfo)` — czternaście linii, które ręcznie mapowały
`status → phase`, żeby zbudować `ImportWindow`. Szkielet oddaje go całym.

### Większość `CashFlowInfo` była już martwa

To jest właściwe odkrycie tego kroku. Zanim cokolwiek tknąłem:

| | wołający w produkcji |
|---|---|
| `ExistingCategoryStructure.fromCashFlowInfo` | **0** |
| `getAllTypedCategoryKeys` + `TypedCategoryKey` | **0** |
| `countCategories`, `isInSetupMode`, `isInClosedMode`, `cashChangesCount` | **0** |
| import `CashFlowInfo` w `RevalidateStagingCommandHandler` | nieużywany |

`ExistingCategoryStructure.from(CashFlowSkeletonView)` istniała od dawna i to z niej korzysta
`AiCategorizeCommandHandler`. Czyli były dwie fabryki tej samej rzeczy — a **318 linii testów stało
pod tą bez wołającego, gdy ta z produkcji nie miała ani jednego**. Pokrycie usiadło na martwym
bliźniaku. Testy przeniesione na `from(skeleton)`; dwa przypadki nie przeniesione, bo sprawdzały
`CashFlowInfo.getAllTypedCategoryKeys` — nigdy nie dotyczyły tego rekordu i są pokryte w
`CashFlowSkeletonViewTest`.

### Co zniknęło

| plik | linii |
|---|---|
| `CashFlowInfo` | 210 |
| `CashFlowServiceClient` | 63 |
| `HttpCashFlowServiceClient` | 199 |
| `TestCashFlowServiceClient` ×2 | 190 |
| `HttpCashFlowServiceClientIntegrationTest` | 4 testy |
| `fromCashFlowInfo` + `convertCategories` + `collectAllNames` | ~45 |

Plus cztery konteksty testowe, które podstawiały dubler: trzy z nich miały zagnieżdżoną
`@TestConfiguration` wyłącznie na niego, więc klasa i jej `@Import` odpadły.
`AppIntegrationTest` zatrzymuje swoją — stoi w niej jeszcze zamiennik
`SecurityContextUserProvider` — więc dostała nazwę mówiącą, co w niej zostało.

`CashFlowServiceClientConfig` budowała dwa klienty HTTP; został jeden, więc nazywa się teraz
`UserFinancialProfileClientConfig`. Ten drugi jest inny w naturze: `UserFinancialProfileApi` jest
zadeklarowany przez `@HttpExchange`, więc proxy **jest** implementacją, a nie jednym z dwóch
transportów. Przy okazji odpadły dwa `@Value` na timeouty, których nikt nigdy nie czytał.

**Czego nie ruszyłem:** klucze `vidulum.cashflow-service.*` nadal tak się nazywają, choć nie
opisują już tego, co przełączają. Trzy bazowe klasy testowe ustawiają `.enabled=false`, więc zmiana
klucza to zmiana w każdej z nich plus produkcyjny `yml` — warto zrobić, warto zrobić osobno.
Zapisane w javadocu tej klasy.

### Liczby

| | przed | po |
|---|---|---|
| metody na porcie ingestion | 1 | **port nie istnieje** |
| żądania HTTP stagingu do cashflow | 1 | 0 |
| testy `vidulum-bank-data-ingestion` | 108 | 106 |
| testy `vidulum-cashflow-tests` | 420 | 416 |
| testy `vidulum-cashflow` / `vidulum-app` | 96 / 49 | 96 / 49 |

Usunięte netto: ~700 linii produkcji i dublerów.

Z F26 zostaje druga połowa: `CashFlowHttpClient` w recurring-rules — własny `CashFlowInfo`, pięć
metod, cudzy JSON parsowany jako `Map<String, Object>` — plus odzyskanie siedmiu typów cashflow ze
shared-kernela.

---

## 26. Audyt granic grupy po kroku 29

Po usunięciu portu ingestion sprawdziłem całą grupę `vidulum-app-cashflow` na jedno pytanie:
**czy zostały sprzężenia międzymodułowe, których nie pokrywa kontrakt `-api`?**

### Czas kompilacji: czysto, i to jest sprawdzone

Żaden z siedmiu modułów nie deklaruje zależności od cudzej implementacji — tylko kontrakty i
shared-kernel. Bezpośredni test importów w `src/main` i `src/test`: **ani jeden** moduł nie sięga do
`app`, `domain` ani `infrastructure` innego. `assertSealed` to zamraża, a jedyne odstępstwo —
`cashflow_forecast_processor` — jest *wewnątrz* tego samego artefaktu.

Czyli wszystko, co zostało, leży **poza kompilatorem**. Pięć kanałów.

### Trzy wymagają roboty — F48, F49, F50

| | |
|---|---|
| **F48** | `bank-data-adapter` → `bank-data-ingestion` po loopbacku HTTP, pod zaszytym `:8080`, którego nikt nie ustawia, i bez testu na sam skok |
| **F49** | `bank_data_adapter.rest` leży poza strażą `assertSealed` — trzy kontrolery importowalne skądkolwiek |
| **F50** | `cashflow` deklaruje `user-financial-profile-api` i nie importuje z niego niczego |

Czwarty kanał to **F26**, czyli `CashFlowHttpClient` w recurring-rules: moduł ma `cashflow-api` na
ścieżce klas i bierze z niego **jedną klasę wyjątku**, a sześć operacji woła po REST z własnym
`CashFlowInfo` i `Map<String, Object>`, przeciągając `authToken` przez każdą sygnaturę.

### Dwa kanały są pokryte i nie wymagają nic

- **Topic `cash_flow` → `user-financial-profile`.** Koperta `CashFlowUnifiedEvent` jest poprawnym
  `IntegrationEvent` (`metadata` + nieprzejrzysty `JsonContent`, zero typów domenowych), a konsument
  deklaruje własny kształt z `@JsonIgnoreProperties`. Sprzężeniem są **nazwy pól w JSON-ie** i są
  pilnowane: `UserFinancialProfileHttpIntegrationTest` czeka na przejęcie IBAN-u po zdarzeniu, więc
  zmiana nazwy u producenta to zepsuje. Nie ma dedykowanego testu słownika jak przy
  `TransactionClassification` — tutaj tę rolę grają testy integracyjne, bo oba moduły jadą w jednym
  kontekście.
- **Cashflow-owe typy w shared-kernelu** (`CashFlowId`, `CashChangeId`, `CategoryName`,
  `BankAccountId`, `BankName`). Poza grupą występują **wyłącznie** w trzech kopiach testowego
  `TestIds`, więc wycieku produkcyjnego nie ma. Pozostaje częścią F24 jako porządek, nie jako defekt.

### Jedna obserwacja bez zadania

`user-financial-profile-api` deklaruje `spring-boot-starter-webmvc` i `-validation` — dokładnie to,
czego według CLAUDE.md `*-api` deklarować nie powinien. Ma to jednak przyczynę, nie przeoczenie:
`UserFinancialProfileApi` jest interfejsem `@HttpExchange` z `@Valid`, więc ten kontrakt **jest**
springowy, a implementacją w procesie jest sam kontroler. Zapisane tu, żeby następny czytelnik reguły
nie uznał tego za niedopatrzenie.

---

## 27. Co zrobił krok 30: dwa odczyty recurring-rules na kontrakt

Audyt z sekcji 26 wskazał `CashFlowHttpClient` jako najostrzejszy przypadek: moduł **ma**
`cashflow-api` na ścieżce klas i bierze z niego jedną klasę wyjątku, a sześć operacji woła po REST.
Ten krok przenosi te dwie, które kontrakt już umie.

### Co dokładnie było nie tak

```java
ResponseEntity<Map> response = restTemplate.exchange(url, GET, ..., Map.class);
List<Map<String, Object>> inflowCategories = (List<Map<String, Object>>) body.get("inflowCategories");
Map<String, String> catName = (Map<String, String>) category.get("categoryName");
result.add(new CategoryName(catName.get("name")));
List<Map<String, Object>> subCategories = (List<Map<String, Object>>) category.get("subCategories");
```

Cztery literały nazywające pola JSON-a cashflow, pod rzutowaniami `@SuppressWarnings`. **Producent
mógł przemianować którekolwiek i nic by się tu nie przestało kompilować** — a wynikowy
`ClassCastException` wpadłby w `catch (Exception e)` i zostałby zgłoszony jako *błąd komunikacji*,
czyli diagnoza wskazałaby sieć, nie kontrakt.

Drugi koszt: `validateCategoryExists` wołało `getCashFlowInfo`, czyli **pełne żądanie po całe cash
flow, z każdą transakcją, po jeden boolean**. W monolicie — po loopbacku.

### Co jest teraz

```java
CashFlowSkeletonView skeleton = cashFlowApi.skeletonOf(cashFlowId);
...
Set<CategoryName> categories = isInflow
        ? skeleton.inflowCategoryNames()
        : skeleton.outflowCategoryNames();
if (!categories.contains(categoryName)) { throw new CategoryNotFoundException(...); }
```

**Semantyka jest identyczna, i to sprawdzone, nie założone:** `namesOf` spłaszcza podkategorie
dokładnie jak tamten rekurencyjny spacer, a `CategoryName` jest rekordem, więc `contains` to to samo
porównanie pełnym łańcuchem co `cat.name().equals(categoryName.name())`. Szkielet ma też wyszukiwanie
ignorujące wielkość litery — użycie go **zmieniłoby** to, co ta walidacja przyjmuje, więc zostało
osobną decyzją, zapisaną w javadocu metody.

### Niespodzianka: `validateCategoryExists` nie miało wołającego w produkcji

Trzeci raz ten sam wzorzec w tej serii (po `fromCashFlowInfo` i `extractTransactionIds`): metoda
utrzymywana w życiu wyłącznie przez swój test. Usunięta razem z nim.

### Pokrycie poszło za logiką, nie za plikiem

Pięć testów klienta sprawdzało rekurencyjne wyciąganie kategorii. Samo spłaszczanie jest pokryte po
stronie kontraktu (`CashFlowSkeletonComponentTest:128` sprawdza te same zbiory per kierunek, z
zagnieżdżeniem), więc tamte pięć odpadło bez straty.

**Ale odmowa serwisu nie miała testu nigdzie** — ani jednostkowo, ani w 57 testach integracyjnych
reguł, które 404 sprawdzają tylko dla samych reguł. Czyli przenosząc tę walidację, nie miałem czym
udowodnić, że ją zachowałem. Dlatego `RecurringRuleCategoryValidationTest` (5 przypadków):
nieznana kategoria, **kategoria istniejąca tylko po drugiej stronie** (w obie strony), podkategoria
przyjęta, nieistniejący cash flow odmówiony przez kontrakt bez tłumaczenia na błąd komunikacji, i
jedno pytanie do kontraktu na regułę.

Dowód negatywny: podmiana reguły na `allCategoryNames()` — czyli zgubienie kierunku — wywala
dokładnie jeden test, ten o drugiej stronie.

### Co zostało w kliencie

Cztery operacje bez metody w kontrakcie: trzy zapisy (`createExpectedCashChange`,
`batchDeleteExpectedCashChanges`, `batchUpdateExpectedCashChanges`) i jeden odczyt
(`getCashChangeStatuses`, który dalej parsuje `body.get("cashChanges")` z `Map<String, Object>`).
Każda nadal bierze `authToken` — i to jest pozostały ślad tego, że decyzja o transporcie wyciekła w
górę, do sygnatur przypadków użycia. To reszta **F26**.

| | przed | po |
|---|---|---|
| operacje w `CashFlowHttpClient` | 6 | 4 |
| żądania HTTP na utworzenie reguły | 1 + N zapisów | **N zapisów** |
| parsowanie cudzego JSON-a w odczytach | 2 metody | 1 (`getCashChangeStatuses`) |
| testy `vidulum-recurring-rules` | 22 | **27** |

---

## 28. Co zrobił krok 31: dwie komendy dostają własne handlery

Te dwie, które pytają kontrakt cashflow — `CreateRuleCommand` i `UpdateRuleCommand` — wyszły z
`RecurringRuleService` w kształcie, którego cashflow używa od początku: **folder na komendę, komenda i
jej handler obok siebie, trasowanie przez `CommandGateway`**.

```
app/commands/create/   CreateRuleCommand.java  CreateRuleCommandHandler.java
app/commands/update/   UpdateRuleCommand.java  UpdateRuleCommandHandler.java
```

`RecurringRuleService` miał **dwanaście metod `handle` w 736 liniach**, więc czytelnik szukający,
co robi utworzenie reguły, znajdował to pośród jedenastu innych, a wszystko, czego którakolwiek z
nich potrzebowała, było polem tej samej klasy. Zostało **535 linii** i dziesięć metod — i **zero**
odwołań do `CashFlowModuleApi`, bo kontrakt wyszedł razem z tymi dwiema.

### Dwóch kolaboratorów, bo logika była wspólna

| | dlaczego osobno |
|---|---|
| `ExpectedCashChangeSynchronizer` | `generate` miało **sześciu** wołających, `clear` **siedmiu** — dwa z nich wyszły, pięć zostało. Dwie strony jednej roboty na tych samych trzech zależnościach, więc jeden komponent, nie dwa. Przeniesione bez zmiany ciała |
| `CashFlowCategoryValidator` | pytany przez oba handlery i **jedyne miejsce w module, które rozmawia z `CashFlowModuleApi`** — jedno żądanie na komendę, odpowiadające na dwie rzeczy: że cash flow istnieje (kontrakt sam rzuca) i że kategoria istnieje, po właściwej stronie |

Oba są **publiczne**, bo handlery siedzą w innym pakiecie, a `package-private` w Javie nie jest
hierarchiczne. To nie jest rozszczelnienie: `assertSealed` trzyma `recurring_rules.app..` zamknięte
dla innych modułów, i tak samo publiczne są w cashflow `DomainCashFlowRepository` czy
`CashFlowEventEmitter`.

### Decyzja, którą trzeba nazwać: `authToken` wszedł do komendy

`CommandHandler<T, R>.handle(T command)` bierze komendę i **nic więcej**, a token był dotąd drugim
parametrem metody serwisu. Żeby zaimplementować ten interfejs, musiał trafić do rekordu komendy.

**To nie jest poprawa — to przeniesienie.** Token okaziciela nie ma czego szukać w komendzie, i
zapisałem to wprost w javadocu obu komend: pole istnieje, dopóki zapisy jadą po HTTP, i znika razem
z nimi (F26). Alternatywa — wyciąganie tokenu z kontekstu bezpieczeństwa — jest lepsza, ale to
zmiana sposobu pozyskania tokenu, a nie przeniesienie metody, więc nie wsadzałem jej tutaj.

### Pokrycie poszło za logiką, i złapało coś, czego nie było

Test, który napisałem w kroku 30, celował w serwis; logika się przeprowadziła, więc test też — i
rozpadł się na dwa, bo to są dwa pytania:

- **`CashFlowCategoryValidatorTest`** (4) — *czy* kategoria istnieje. Prawdziwy walidator nad
  zaślepionym kontraktem, celowo: spłaszczanie podkategorii i rozdział kierunków odpowiada szkielet,
  więc zamockowany walidator nie testowałby niczego.
- **`CreateRuleCommandHandlerTest`** (4) — co robi handler. Przy odmowie **nic nie zostaje
  zapisane i nic nie jest generowane**, a jeden przypadek przypina **kolejność**: komenda z
  jednocześnie złą kategorią i odwróconym zakresem dat zawodzi na kategorii. Ta kolejność była
  wcześniej tylko kolejnością linii w metodzie i nie miała żadnego testu.

### Stan przejściowy, nazwany w kodzie

Sześć komend nadal leży płasko w `app/commands/` i nadal idzie przez serwis; kontroler wysyła przez
gateway **dwie**. Mieszanka jest uczciwym stanem migracji, nie projektem — i tak jest napisane przy
polu `commandGateway`: komenda dostaje własny handler wtedy, gdy ktoś ma powód jej dotknąć.

| | przed | po |
|---|---|---|
| `RecurringRuleService` | 736 linii, 12 × `handle` | **535**, 10 × `handle` |
| odwołania do `CashFlowModuleApi` w serwisie | 2 | **0** |
| testy `vidulum-recurring-rules` | 27 | **30** |
| testy integracyjne reguł | 57 | 57, przez gateway |

Rejestracja potwierdzona w logu, nie założona:
`Command handler [...CreateRuleCommandHandler] registered!` i to samo dla update.

### Dopisek: wejście do tej komendy dostało walidację, której brakowało

`CreateRuleRequest` miał adnotacje jakarta na polach własnych, a kontroler `@Valid` — ale pole
`pattern` nosiło tylko `@NotNull`, a **bean validation nie wchodzi w zagnieżdżony obiekt, dopóki się
jej nie każe**. `PatternDto.toPattern()` robi `switch (type)`, więc brak typu kończył się
`NullPointerException` przy budowaniu komendy: **500 za żądanie, o którym widać, że jest
niekompletne**.

```java
@Valid                                   // <- bez tego nic w PatternDto nie jest sprawdzane
@NotNull(message = "Pattern is required")
private PatternDto pattern;
```

Plus `@NotNull` na `PatternDto.type`. Odpowiedzią jest teraz 400 z `VALIDATION_ERROR` i
`fieldErrors` nazywającym `pattern.type`.

**Czego świadomie nie dopisałem:** zakresów per typ (`dayOfMonth` 1–31) ani relacji
`startDate`/`endDate`. Pierwsze sprawdza domena — `MonthlyPattern` rzuca przy konstrukcji — drugie
handler, przez `InvalidDateRangeException`. Trzecia kopia tej samej reguły to dokładnie to, co ta
seria usuwa, więc jest na to osobny test: `itDoesNotTakeOverTheChecksThatBelongElsewhere` przechodzi
wtedy, gdy walidator **nie** zgłasza odwróconego zakresu dat.

Podział poziomów zgodny z tabelą z CLAUDE.md: adnotacje są własnością rekordu, więc
`CreateRuleRequestValidationTest` (6 przypadków) używa prawdziwego walidatora jakarta — bez Springa i
bez Dockera — a dwa testy w `RecurringRulesHttpIntegrationTest` pokrywają to, co widać tylko po
HTTP: status, `ErrorCode` i przeżycie nazw pól przez serializację.

Dowód negatywny zrobiony na obu poziomach, i ten po HTTP wyszedł mocniejszy: bez `@Valid` test
jednostkowy przestaje widzieć naruszenie `pattern.type`, a test integracyjny pokazuje, co to
znaczyło w praktyce — `expected: 400 BAD_REQUEST but was: 500 INTERNAL_SERVER_ERROR`.

`UpdateRuleRequest` miał tę samą lukę i został domknięty tak samo (F54) — `@Valid` na polu plus
własny `UpdateRuleRequestValidationTest`, osobny od create, bo **adnotacje należą do klasy**: zdjęta
z jednej nie zostałaby zauważona przez test drugiej. Dowód negatywny powtórzył się co do znaku:
`expected: 400 BAD_REQUEST but was: 500 INTERNAL_SERVER_ERROR`.

Jeden przypadek w tamtym teście pilnuje **różnicy** między dwoma żądaniami: update nie nazywa ani
użytkownika, ani cash flow, bo `UpdateRuleCommandHandler` czyta cash flow z zapisanej reguły — więc
edycją nie da się przenieść reguły między cash flowami, i lista wymaganych pól jest tam wypisana
wprost, żeby ta właściwość miała gdzie upaść.
