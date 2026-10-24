# 11 · Znaleziska z danych — co pokazał drugi wyciąg bankowy

**Data:** 2026-10-24 · **Status:** zmierzone, siedem zadań otwartych ·
**Źródło pomiarów:** [fixture kanoniczny](../../../vidulum-cashflow-tests/src/test/resources/canonical/README.md)
(Nest 402 wiersze, Pekao 791) · **Zadania:** M7, M8, M9, K15, X7, R9, R10 w
[tablicy](../ingestion-v2-tasks.md)

Cały projekt ingestion v2 był mierzony na jednym banku: Nest, 402 wiersze, wyciąg **przelewowy**,
jedna waluta, cztery wartości kategorii banku. Pekao jest innym kształtem danych — 791 wierszy,
**kartowy**, 36 kategorii semantycznych, dwie waluty — i pokazał cztery rzeczy, których jeden bank
pokazać nie mógł. Dwie z nich leżą **poza** rodziną ingestion: w `vidulum-shared-kernel` i w
kontrakcie `cashflow-api`.

Ten plik jest pogłębieniem §25.2
[dokumentu projektowego](../2026-10-24-data-ingestion-conductor-saga.md); tam są wnioski, tu
mechanizmy, dowody i scenariusze.

---

## 1 · Waluta: `Money` wie o walucie, kiedy porównuje, i nie wie, kiedy liczy

**Zadania: K15** (strażnik w shared-kernelu) i **M7** (odrzucenie wiersza przy walidacji)

### Mechanizm

Dwie metody w `vidulum-shared-kernel/src/main/java/com/multi/vidulum/common/Money.java`:

```java
public Money plus(Money other)  { return new Money(amount.add(other.amount),      currency); }
public Money minus(Money other) { return new Money(amount.subtract(other.amount), currency); }
```

Kwota przychodzi z drugiej strony, **waluta zostaje z tej**. Żadnego sprawdzenia, żadnego wyjątku.
A trzydzieści linii niżej, w tym samym pliku:

```java
return amount.compareTo(money.amount) == 0 && Objects.equals(currency, money.currency);   // equals
```

Czyli `Money` traktuje walutę jako **część tożsamości** przy porównaniu i **ignoruje ją w
arytmetyce**. Ta niespójność siedzi w jednej klasie.

### Zasięg — to nie jest problem ingestion

```bash
grep -rn "\.plus(\|\.minus(" --include="*.java" . | grep -v /target/ | grep -v Test | cut -d/ -f1-2 | sort | uniq -c
```

| moduł | wywołań |
|---|---|
| `wealth/vidulum-portfolio` | 44 |
| `vidulum-app-cashflow/cashflow` | 43 |
| `wealth/vidulum-risk` | 5 |
| `wealth/vidulum-trading` | 4 |
| `wealth/vidulum-pnl` | 3 |
| `vidulum-shared-kernel` | 2 |
| **razem w kodzie produkcyjnym** | **101** |

Czyli to samo zachowanie liczy salda cash flow, **wyceny portfela i rachunek zysków**.

### Co się dzieje konkretnie

`CashFlow` aktualizuje saldo rachunku dokładnie tą metodą:

```java
bankAccount = bankAccount.withUpdatedBalance(bankAccount.balance().plus(event.money()));
```

a ścieżka importu nie patrzy na walutę ani razu — `grep` po
`cashflow/app/commands/importhistorical/` daje **zero** trafień na `currency`.

Dwa wiersze EUR z Pekao — `CLAUDE.AI SUBSCRIPTION` 22,14 EUR i `ETSY.COM*SOCIETYRITUAL` 12,18 EUR —
wchodzą do złotówkowego cash flow jako **+34,32 PLN**.

**Druga warstwa, groźniejsza od pierwszej:** przy kursie ~4,3 PLN/EUR te transakcje są warte
**~147 PLN**, więc saldo jest zaniżone o **~113 PLN**. Owner zobaczy to **raz, na końcu**, jako
`CASHFLOW_BALANCE_MISMATCH` przy atestacji — **jedną liczbę, bez wskazania, które wiersze ją
spowodowały**. Najbardziej prawdopodobna reakcja to „poprawię saldo początkowe", co **utrwala błąd**
i czyni go nieodwracalnym, bo po atestacji historia należy do ownera i kompensacja jest zabroniona.

### Dlaczego nie wyszło wcześniej

Nest ma 402 wiersze i jedną walutę. Pytanie nie mogło się pojawić.

### Dlaczego dwa zadania, a nie jedno

Strażnik w `Money` (**K15**) zamieni cichy błąd arytmetyczny na **wyjątek w trakcie importu** —
czyli wiersz padnie w kroku `IMPORT`, w połowie 791 wierszy, jako `failed[]`. To lepsze niż dziś, ale
nadal złe: owner powinien dowiedzieć się **przed** importem, z nazwą obu walut, i to jest **M7**
(odrzucenie w `VALIDATE` z `INGESTION_CURRENCY_MISMATCH`). Jedno bez drugiego zostawia dziurę:
K15 bez M7 daje brzydką porażkę w środku, M7 bez K15 zostawia defekt w portfelu i pnl.

### Pułapka w zależnościach, którą sam w to wpadłem

**M7 zależy od X7** (punkt 2 niżej), bo waluty cash flow **nie da się dziś odczytać przez
kontrakt** — `CashFlowSkeletonView` jej nie nosi. W pierwszej wersji tablicy M7 miało zależność
tylko od M3 i K9, co wysłałoby świeżą sesję na zadanie niewykonalne.

---

## 2 · Rachunek: drugi wyciąg to drugi cash flow, i nie ma czym tego sprawdzić

**Zadania: X7** (pole w kontrakcie) i **M8** (walidacja sesji)

### Mechanizm

`CashFlow` ma `private BankAccount bankAccount` — **jeden**. Nest i Pekao to dwa rachunki tej samej
osoby, więc dwa osobne cash flow.

### Dlaczego to groźne, a nie oczywiste

Okresy obu wyciągów **nachodzą się na cały 2025 rok**: Nest 2023-01…2025-12, Pekao 2025-01…2026-01.
Import Pekao do cash flow Nest przechodzi więc przez **każdą** istniejącą walidację:

| sprawdzenie | wynik dla Pekao w cash flow Nest |
|---|---|
| okno importu (`ImportWindow`) | przechodzi — daty mieszczą się w zakresie |
| statusy miesięcy | przechodzą — miesiące 2025 są otwarte |
| duplikaty (`alreadyImported`) | przechodzą — inne `bankTransactionId` |
| kategorie | powstają normalnie, 38 pozycji planu |

791 cash changes wchodzi, a sprzeczność wychodzi **dopiero przy atestacji salda** — znowu jako jedna
liczba bez przyczyny. I w tym momencie kompensacja jest już trudna: `rollbackImport` kasuje **cały**
import tego cash flow, czyli zabrałby też wcześniejszy import Nest (to jest luka z §25.1).

### Dlaczego nie da się tego naprawić samym ingestion

`CashFlowSkeletonView` ma **siedem pól**:

```java
cashFlowId, owner, phase, activePeriod, startPeriod, inflowCategories, outflowCategories
```

**Nie ma rachunku i nie ma waluty.** Kontrakt, przez który ingestion rozmawia z cashflow, fizycznie
nie przenosi informacji potrzebnej do tej walidacji — ani do walidacji waluty z punktu 1.

To czyni z tego **trzecią** brakującą rzecz w `cashflow-api`:

| czego brakuje | od czego zależy | zadanie |
|---|---|---|
| `recategorize(cashFlowId, cashChangeId, category)` | tryb `AUTOPILOT` (decyzja po imporcie) | X1 |
| `deleteImported(cashFlowId, ids)` | kompensacja selektywna, wiele wyciągów na jeden SETUP | X2 |
| rachunek + waluta w skeletonie | walidacja rachunku **i** walidacja waluty | **X7** |

### Proponowany kształt zmiany w kontrakcie (X7)

**Dwa pola i dwie metody. Żadnego nowego typu.**

```java
public record CashFlowSkeletonView(
        CashFlowId cashFlowId,
        UserId owner,
        LifecyclePhase phase,
        YearMonth activePeriod,
        YearMonth startPeriod,
        BankAccountId bankAccount,          // NOWE
        String currency,                    // NOWE — waluta rachunku, nie saldo
        List<CategoryNodeView> inflowCategories,
        List<CategoryNodeView> outflowCategories) {

    /**
     * Czy ten numer to ten sam rachunek. Tolerancja należy do cashflow, nie do konsumenta.
     */
    public boolean isSameAccount(BankAccountId candidate) { … }

    /**
     * Czy ta kwota w ogóle może wejść do tego cash flow. Pusty Optional = może.
     */
    public Optional<BusinessException> refusalFor(Money money) { … }
}
```

#### Dlaczego `BankAccountId`, a nie `BankAccountNumber` cashflow

`BankAccountId` z shared-kernela ma w javadocu dokładnie ten przypadek użycia: *„used for account
matching without requiring full IBAN validation or iban4j dependency"* — normalizuje białe znaki i
wielkość liter, i nic więcej. `BankAccountNumber` z domeny cashflow **waliduje IBAN przez iban4j**,
więc wciągnięcie go do kontraktu znaczyłoby: kontrakt ciągnie iban4j, a każdy odczyt skeletonu
re-waliduje numer, który został zapisany dawno temu. To jest udokumentowana w CLAUDE.md wpadka
`user_financial_profile`: *„storage re-validating what it stored is a failure mode with no upside"* —
jeden zapisany IBAN, który iban4j odrzuciłby dziś, czynił cały profil nieczytelnym.

#### Dlaczego porównanie jest **metodą**, a nie porównaniem pól przez konsumenta

Bo w tych danych numery tego samego rachunku **mają różne długości**:

| skąd | postać | długość |
|---|---|---|
| metadane wyciągu Nest | `93187010452083105656550001` | 26 |
| wiersz Pekao | `'98124014441111001078171074` | 26 + apostrof |
| cash flow utworzony przez ownera | `PL61109010140000071219812874` | 28 |
| po przejściu przez `CsvParserService` | prefiks **doklejony z waluty** (`PLN → PL`) | 28 |

`BankAccountId` normalizuje spacje i wielkość liter, ale **nie prefiks kraju** — więc
`equals` na dwóch poprawnych reprezentacjach tego samego rachunku zwróci `false`. I to nie jest
hipoteza: „czy to ten sam rachunek" ma już **trzy implementacje** w repo
(`CsvParserService.normalizeIban`, `OwnedAccountNumber.fromIban` w profilu, `BankAccountNumber` w
cashflow), bo każdy konsument rozwiązywał to u siebie.

Precedens jest w tym samym pliku: `findCategoryIgnoringCase` jest **metodą widoku**, a jej javadoc
mówi wprost — *„deciding what counts as the same category is cashflow's policy: today that means
trimming and ignoring case, and if it ever means more every consumer should start agreeing with it on
the same day"*. „Ten sam rachunek" jest tą samą klasą pytania.

#### Dlaczego waluta, ale **nie saldo**

| | w skeletonie | dlaczego |
|---|---|---|
| waluta rachunku | **tak** | jest własnością *kształtu* cash flow, zmienia się nigdy, O(1) |
| saldo rachunku | **nie** | jest tym, *co w nim jest*, a nie *jak jest zorganizowany* — czyli dokładnie to, czego skeleton obiecuje nie nosić; zmienia się przy każdym imporcie, więc skeleton pobrany raz na sesję natychmiast kłamie; a konsument, który je dostanie, zacznie sam liczyć atestację, która jest sprawą cashflow |

Obietnica skeletonu zostaje nienaruszona: *„cash flow z czterema latami historii odpowiada tym samym
payloadem co utworzony minutę temu"*. Oba dodatki są O(1) i to jest kryterium akceptacji, nie
komentarz.

#### Czego świadomie **nie** dodajemy

| rzecz | gdzie należy | dlaczego nie w skeletonie |
|---|---|---|
| lista generycznych kategorii banku (`Inne`, `Bez kategorii`) | `ingestion-category-resolution` (R9) | to wiedza o **bankach**, nie o tym cash flow; dwa banki dają dwie listy, a cash flow nie ma z tym nic wspólnego |
| statusy miesięcy | `CashFlowForecastApi` | już jest osobnym kontraktem, bo należy do read modelu, nie do agregatu (§9 projektu) |
| kurs walutowy do przeliczenia EUR | `vidulum-quotation` | cashflow nie zna kursów, a przeliczanie zmieniłoby kwotę zapisaną przez bank — pomiar mówi, że te 2 wiersze EUR **nie mają** odpowiednika w PLN, więc odrzucenie jest poprawne, nie tylko bezpieczne |
| saldo i atestacja | pozostaje w cashflow | patrz wyżej |

#### Dwa transporty muszą wypełnić to samo

`CashFlowModuleApi` ma dwie implementacje — `CashFlowModuleApiImpl` (gateway + repozytorium) i
`HttpCashFlowModuleApi` (REST). **Obie muszą wypełnić nowe pola**, a REST ma czym: `CashFlowDto`
wystawia już `BankAccountJson`. To nie jest formalność — CLAUDE.md opisuje dokładnie ten tryb awarii,
gdy jedna implementacja przekazywała `null` tam, gdzie druga tłumaczyła wartość, i projekcja prognozy
umarła na NPE, podczas gdy **każdy zapis nadal odpowiadał 2xx**.

Pinuje to: `CashFlowSkeletonViewTest` (wartości i `isSameAccount` z czterema postaciami numeru z
tabeli wyżej), `CashFlowSkeletonViewComponentTest` (że payload nie rośnie z historią) oraz
`BootstrapWiresEveryModuleTest` (że bean jest dokładnie jeden i nie jest tym po HTTP).

### Jak ma działać walidacja

Rachunek właściciela jest **w każdym wierszu**: źródłowy dla wypłat, docelowy dla wpłat. Więc
wystarczy wyznaczyć go raz na sesję (najczęstszy rachunek po stronie zgodnej z kierunkiem) i
porównać ze skeletonem. Niezgodność odrzuca **całą sesję przed zapisaniem 791 wierszy** — to
walidacja sesji, nie wiersza, i to jedyna taka w całym projekcie.

**Pułapka przy implementacji:** w wierszach rozliczeń kartowych Pekao (87 wierszy, kontrahent
`BANK PEKAO S.A.`) po jednej stronie stoi **techniczny rachunek banku**
(`53124000270999999999999999`), nie rachunek ownera. Wyznaczanie „rachunku wyciągu" przez większość,
a nie przez pierwszy wiersz, jest tu warunkiem poprawności.

---

## 3 · Data: która z dwóch rządzi miesiącem transakcji

**Zadanie: M9**

### Pomiar

| | Nest | Pekao |
|---|---|---|
| `operationDate ≠ bookingDate` | **1** wiersz | **292** z 791 |
| mediana opóźnienia księgowania | — | 0 dni |
| maksimum | — | **9 dni** |
| daty w **różnych miesiącach** | 0 | **24** wiersze, 910,68 PLN |
| operacja w XII.2025, księgowanie w I.2026 | — | **2** wiersze |

### Dlaczego to decyzja, a nie szczegół implementacyjny

Miesiąc transakcji decyduje o dwóch rzeczach naraz: **do którego miesiąca prognozy** wiersz trafi i
**czy miesiąc w ogóle przyjmuje zapis** — `ATTESTED` jest read-only. Projekt używał `paidDate`, nie
mówiąc, skąd ten `paidDate` się bierze, bo na Nest wybór dotyczył jednego wiersza.

### Scenariusz, który to psuje

Owner ściąga wyciąg 11 stycznia. **Dwa wiersze** mają operację w grudniu 2025 i księgowanie w
styczniu 2026. Jeśli zaatestował grudzień — a po to jest atestacja — to przy regule „rządzi
`operationDate`" te dwa wiersze zostaną **odrzucone jako miesiąc read-only**, mimo że bank
zaksięgował je w styczniu i w grudniowym saldzie ich nie było. Owner traci transakcje, których
poprawnie **nie mógł** mieć wcześniej.

### Reguła do zapisania

Rządzi **`operationDate`** — to moment, w którym pieniądze się ruszyły, i to pamięta człowiek.
**Wyjątek:** gdy miesiąc operacji jest `ATTESTED`, a miesiąc księgowania otwarty, wiersz idzie do
**miesiąca księgowania** z zapisanym powodem. `bookingDate` zostaje w danych w obu przypadkach, bo
jest w wierszu kanonicznym i nic nie każe go tracić.

To jest ta sama klasa decyzji, co lekcja z CLAUDE.md o porcie, który zgubił czas i strefę: *„blisko
granicy miesiąca przenosi to transakcję do innego miesiąca, co decyduje o walidacji i o prognozie"*.
Różnica jest w tym, że tam chodziło o strefę czasową, a tu o **dwie różne, prawdziwe daty** tej samej
transakcji.

---

## 4 · Kategorie generyczne: jedna nazwa zbiera 23 % wartości wyciągu

**Zadania: R9** (lista generycznych) i **R10** (próg tworzenia kategorii)

### Pomiar

| | wiersze | % wierszy | wartość | % wartości |
|---|---|---|---|---|
| `Inne` | 173 | 22 % | — | — |
| `Bez kategorii` | 92 | 12 % | — | — |
| **razem generyczne** | **265** | **34 %** | **39 228,30 PLN** | **23 %** |

Nest nie ma tej kolumny wcale — jego cztery „kategorie" to rodzaje operacji (`Przelewy wychodzące`,
`Opłaty i prowizje`), więc problem nie mógł się ujawnić.

### Mechanizm — i dlaczego jest podstępny

Priorytet 0 drabiny to „`bankCategory` trafia bezpośrednio w istniejącą kategorię ownera, bez
względu na wielkość liter". Kategoria `Inne` **powstanie przy pierwszym imporcie**, bo plan ją
utworzy. Przy **drugim** imporcie 173 wiersze dopasują się do niej **najwyższym priorytetem**,
mijając wyuczone wzorce, mapowania i AI.

Efekt jest groźniejszy niż samo złe grupowanie: te wiersze **wyglądają na skategoryzowane**. Nie
trafiają do `open[]`, nie generują pytania, nie dostają flagi `provisional` i nie pojawiają się w
żadnym raporcie „do przeglądu". **Jedna czwarta pieniędzy ląduje w koszu, który udaje kategorię** —
i nic w systemie o tym nie mówi.

### Co podnosi wagę tego findingu

Kontrakt silnika kategoryzacji **już zna to pojęcie**. `CategorizationSuggestions.BankCategoryFallback`
ma w dokumentacji: *„AI MUST generate one fallback per semantic bankCategory (skipping generic ones
like 'Inne')"*. Czyli bez R9 dwie części systemu miałyby **sprzeczne reguły** dla tej samej danej:
silnik pomija generyczne, drabina dopasowywałaby je priorytetem najwyższym.

### R10 jest słabsze i warto to powiedzieć wprost

Plan kategorii rośnie z **4** pozycji (Nest) do **38** (Pekao), z czego **11 pozycji to grupy po
1–2 wiersze** — ale te 11 pozycji to zaledwie **17 wierszy i 2,1 % wartości**
(`Obuwie`, `Hotele`, `Kary i mandaty`, `Multimedia`…).

Czyli to **nie jest problem poprawności, a higieny**: te kategorie zostają w drzewie na stałe po
jednej transakcji — **także po wycofaniu importu**, bo kompensacja świadomie nie kasuje kategorii,
które mogły już zostać użyte (§13 projektu). Stąd P2, a nie P0, i stąd próg konfigurowalny
(domyślnie 3 wiersze) zamiast reguły twardej.

---

## Wniosek metodyczny

| | |
|---|---|
| zadań z tej rundy | 7 |
| takich, których **nie dało się zobaczyć na jednym banku** | **4** (M7, M8, M9, R9) |
| leżących **poza** rodziną ingestion | **2** (K15 w shared-kernelu, X7 w `cashflow-api`) |
| koszt | jeden plik wyciągu i pół godziny pomiarów |

**Czego nadal nie wiem.** Trzeci bank jest jedynym obszarem z zerem danych pomiarowych: inny kraj,
inna waluta jako główna, format MT940 albo CAMT.053 zamiast CSV. Dwie konkretne hipotezy, warte
sprawdzenia, gdy taki plik się pojawi:

1. **Strefy czasowe.** Wiersz kanoniczny nosi `LocalDate`, a cash flow `ZonedDateTime`; dziś
   konwersja zakłada jedną strefę. Wyciąg z innego kraju jest pierwszym miejscem, gdzie to zakłuje.
2. **Walidacja IBAN.** `CsvParserService` dokleja prefiks kraju **na podstawie waluty**
   (`CURRENCY_TO_COUNTRY`: `EUR → DE`). Rachunek w EUR prowadzony w Irlandii albo Hiszpanii
   dostanie więc `DE` i nie przejdzie kontroli długości — a wygląda to jak błąd danych, nie jak
   błąd reguły.
