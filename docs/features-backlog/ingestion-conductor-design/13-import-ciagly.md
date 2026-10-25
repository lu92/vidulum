# 13 · Import ciągły — wgrywanie kolejnych wyciągów po atestacji

**Data:** 2026-10-24 · **Status:** analiza + projekt · **Pytanie:** *jak wgrywać kolejne pliki, gdy
cash flow jest już OPEN — czy to jest przewidziane?* · **Zadania:** S17, X8, X2, U8, M9 · **Przykład liczony:** §5

Odpowiedź w jednym zdaniu: **jest przewidziane w cashflow i nie jest przewidziane w ingestion v2.**
Agregat i model odczytu mają trzy mechanizmy pod import ciągły, z czego jeden jest wyłączony przez
brak endpointu; projekt ingestion v2 opisuje wyłącznie backfill i cztery jego elementy nie
przenoszą się na tryb ciągły.

---

## 1 · Co już jest po stronie cashflow — zmierzone

**(a) Okno importu dopuszcza OPEN.** `ImportWindow.refusalFor` w fazie OPEN odrzuca tylko miesiąc
**po** `activePeriod`, czyli przyszłość:

```java
case OPEN -> target.isAfter(activePeriod)
        ? Optional.of(new ImportToForecastedMonthNotAllowedException(…))
        : Optional.empty();
```

**(b) Model odczytu wprost przewiduje dowgrywanie.** `MonthStatuses.acceptsImportAt`:

| status miesiąca | przyjmuje import | znaczenie |
|---|---|---|
| `ACTIVE` | **tak** | bieżący miesiąc — tu trafia świeży wyciąg |
| `ROLLED_OVER` | **tak** | miesiąc zamknięty rolloverem; `MonthStatus` ma w javadocu wprost *„Allows gap filling - importing missed transactions from bank statements"* |
| `IMPORT_PENDING`, `IMPORTED` | tak | fazy backfillu |
| `FORECASTED` | nie | przyszłość |
| **`ATTESTED`** | **nie** | owner potwierdził saldo — miesiąc tylko do odczytu |

**(c) v1 świadomie dopuszcza OPEN.** Komentarz w `StartImportJobCommandHandler`:
*„Allow import in both SETUP mode (historical backfill) and OPEN mode (ongoing sync/gap filling)"*,
a odmowa dotyczy tylko `isClosed()`.

**(d) Mechanizm kontroli salda per miesiąc istnieje — i nikt go nie woła.**
`MakeMonthlyAttestationCommand(cashFlowId, period, currentMoney)` oraz `MonthAttestedEvent` są w
kodzie, ale **nadawcy produkcyjnego nie mają**: jedyne wywołania to trzy generatory w
`vidulum-cashflow-tests`. Nie ma też endpointu. To ten sam kształt, co
`TransactionCategorizationApi.forget` przed zadaniem R7 — komenda bez konsumenta.

**Konsekwencja (d) jest nieoczywista i warto ją nazwać:** `ATTESTED` to **jedyny** status
blokujący import, a jedyna komenda, która go ustawia, nie jest wystawiona. Więc dziś w trybie OPEN
**żaden miesiąc nigdy nie zostaje zablokowany** — każdy import przechodzi, a wraz z tym **nie
istnieje żadna kontrola, czy saldo po wgraniu zgadza się z wyciągiem**. Blokada jest, klucza nikt
nie przekręca.

---

## 2 · Czego nie ma w projekcie ingestion v2

| # | co | dlaczego to boli dopiero w trybie ciągłym |
|---|---|---|
| 1 | **Sesja nie wie, w jakim trybie działa.** Cała dokumentacja, przykład T0–T13 i fixture to backfill trzyletniego wyciągu | polityka, domknięcie i dostępne akcje różnią się (tabela w §3) |
| 2 | **Kompensacja nie działa w OPEN.** `RollbackImportCommandHandler` odmawia poza SETUP (`RollbackNotAllowedInNonSetupModeException`) | łańcuch W2 (`UNIMPORT → UNLEARN → DISCARD_ROWS`) i przycisk *Wycofaj import* w mockupach S4/S5/S6 **nie mają zastosowania** — UI obiecuje operację, której backend odmówi |
| 3 | **Brak domknięcia sesji.** Atestacja historyczna jest SETUP-only, a rollover zamyka miesiąc saldem **policzonym przez cashflow**, nie wziętym z wyciągu | zanika jedyna kontrola zewnętrzna: błąd typu „22,14 EUR weszło jako 22,14 PLN" w backfillu wychodzi jako `CASHFLOW_BALANCE_MISMATCH`, a w trybie ciągłym **nie wychodzi nigdy** |
| 4 | **Reguła daty (M9) przestaje być przypadkiem brzegowym** | w backfillu 24 wiersze na 791; w trybie ciągłym **każdy** wyciąg ściągnięty na początku miesiąca nosi operacje z poprzedniego, zaksięgowane już po jego zamknięciu — to główna ścieżka, nie wyjątek |

Plus jedno drobne: strażnik „jedna aktywna sesja na cash flow w SETUP" (**S7**) ma w OPEN inne
uzasadnienie. Tam nie chodzi już o kompensację, która zabrałaby dane poprzedniego importu, a o to,
żeby dwa równoległe wgrania nie liczyły tych samych duplikatów względem siebie.

---

## 3 · Projekt: jeden tryb na sesję, wyprowadzony, nie wybierany

```java
enum IngestionMode { BACKFILL, ONGOING }
```

**Wyprowadzany z fazy cash flow w chwili startu, nigdy nie wybierany przez ownera** — bo to nie jest
preferencja, a fakt o cash flow. SETUP → `BACKFILL`, OPEN → `ONGOING`, CLOSED → odmowa, jak dziś.
Zadanie **S17**.

| | `BACKFILL` (SETUP) | `ONGOING` (OPEN) |
|---|---|---|
| typowy plik | cała historia, lata, setki wierszy | jeden miesiąc, kilkadziesiąt wierszy |
| nachodzenie na poprzednie wgranie | rzadkie | **oczekiwane** — wyciągi się zachodzą, duplikaty są normą, nie ostrzeżeniem |
| pytania o kategorie | dużo grup (cała historia) | zwykle **zero** — pamięć z poprzednich wgrań rozstrzyga wszystko (dowód: scenariusz R8) |
| miesiące zablokowane | brak | `ATTESTED` → reguła M9 jest ścieżką główną |
| kompensacja | `rollbackImport`, okno 1 h | **niemożliwa dziś** → wymaga `deleteImported` (**X2**, podnoszone z P3) |
| domknięcie | atestacja historyczna z `confirmedBalance` | **atestacja miesiąca** z saldem z wyciągu (**X8**) |
| po domknięciu | SETUP → OPEN | miesiąc → `ATTESTED`, cash flow zostaje OPEN |

### Domknięcie: ożywienie komendy, która już istnieje

Nie wymyślamy nowego pojęcia. `MakeMonthlyAttestationCommand` przyjmuje `period` i `currentMoney` —
dokładnie to, co jest na końcu wyciągu („Saldo po operacji" ostatniego wiersza). Czego brakuje:
**endpointu i nadawcy** (**X8**).

Przepływ trybu ciągłego kończy się więc tak:

```
… IMPORTED ──▶ owner widzi: „zaimportowano 68 transakcji za styczeń 2026"
            ──▶ POST /cash-flow/cf={id}/months/2026-01/attest  { currentMoney: 76047.25 PLN }
            ──▶ zgadza się  → miesiąc ATTESTED, od tej pory tylko do odczytu
            ──▶ nie zgadza się → 409 z RÓŻNICĄ i listą wierszy, które mogły ją spowodować
```

Druga gałąź jest tym, czego dziś nie ma nigdzie: **różnica pokazana razem z kandydatami na
przyczynę** — wiersze w obcej walucie, odrzucone, prowizoryczne. W backfillu owner dostaje jedną
liczbę bez wyjaśnienia; tutaj nie powtarzajmy tego błędu.

### Kompensacja w trybie ciągłym

Trzy opcje, w kolejności od najtańszej:

1. **Brak kompensacji, korekta per wiersz.** Owner poprawia kategorię (`recategorize`, **X1**) albo
   odrzuca cash change istniejącą operacją `reject`. Dla jednego miesiąca to realne.
2. **`deleteImported(cashFlowId, ids)` (**X2**)** — sesja zna `importedCashChangeIds`, więc wycofuje
   dokładnie swoje wiersze, nie cały import cash flow. To jest właściwa droga i dlatego X2 przestaje
   być zadaniem „na kiedyś".
3. **Rozszerzenie `rollbackImport` na OPEN** — odrzucone: ta metoda kasuje *wszystko, co import
   wniósł do cash flow*, więc w OPEN zabrałaby dane z poprzednich miesięcy.

Do czasu X2 **UI w trybie `ONGOING` nie pokazuje przycisku *Wycofaj***, a ekran mówi wprost, co
zamiast niego: popraw kategorię albo odrzuć transakcję. Lepiej nie mieć przycisku niż mieć taki,
który odpowiada 400.

---

## 4 · Co zmienia się na ekranach (zadanie U8)

| ekran | w `BACKFILL` | w `ONGOING` |
|---|---|---|
| S0 start | „Import wyciągu" | „Dowgraj wyciąg za *styczeń 2026*" + ostrzeżenie, gdy plik wychodzi poza miesiące otwarte |
| S1 przetwarzanie | liczniki jak dziś | duplikaty jako **norma**: „38 z 68 wierszy już masz — to normalne przy zachodzących wyciągach" |
| S2 decyzje | ≤7 pytań | zwykle **pomijany** (patrz R8); gdy się pojawi, to znaczy, że bank zmienił nazwy albo pojawił się nowy odbiorca |
| S4 sukces | *Atestuj import* + *Wycofaj* | ***Potwierdź saldo miesiąca*** + *Popraw kategorie*; **bez** *Wycofaj* do czasu X2 |
| nowy | — | **różnica salda**: gdy potwierdzenie nie zgadza się z wyciągiem, ekran pokazuje różnicę i kandydatów na przyczynę |

---

## 5 · Przykład: rok Pekao, miesiąc po miesiącu

Wszystkie liczby poniżej są policzone z fixture'u `pekao-canonical.csv`. Wyciąg miesięczny zawiera
wiersze **zaksięgowane** w danym miesiącu — i to rozróżnienie jest w tym przykładzie sednem, nie
szczegółem.

| miesiąc księgowania | wierszy | | miesiąc księgowania | wierszy |
|---|---|---|---|---|
| 2025-01 … 2025-11 | **716** (backfill) | | 2025-12 | **61** |
| | | | 2026-01 (do 9 stycznia) | **14** |

---

### T0 · Backfill, 1 grudnia 2025

Owner tworzy cash flow dla rachunku Pekao, `startPeriod = 2025-01`, i wgrywa wyciąg za jedenaście
miesięcy.

| | |
|---|---|
| tryb sesji | `BACKFILL` (cash flow w SETUP) |
| wiersze | **716** |
| pytania | kilka — pamięć jest pusta, więc drabina pyta o grupy materialne |
| domknięcie | `POST /cash-flow/cf={id}/attest-historical-import` z saldem z ostatniego wiersza |
| po domknięciu | **SETUP → OPEN**, `activePeriod = 2025-12`, miesiące 2025-01…2025-11 zamknięte |

---

### T1 · Pierwsze dowgranie, 2 stycznia 2026

Owner ściąga wyciąg grudniowy: **61 wierszy** zaksięgowanych w grudniu.

| | |
|---|---|
| tryb sesji | **`ONGOING`** — wyprowadzony z fazy OPEN, nie wybrany (**S17**) |
| wiersze | 61 |
| duplikaty | **0** — wyciąg grudniowy nie zachodzi na backfill po stronie księgowań |
| pytania | **zero.** Wszystkie grupy rozstrzygnęły się z pamięci po backfillu (to scenariusz **R8**) |
| zaimportowane | **60** |
| odrzucone | **1** — `CLAUDE.AI SUBSCRIPTION`, 22,14 **EUR**, bo cash flow prowadzi PLN (**M7**) |

**Ten jeden odrzucony wiersz jest pouczający i warto nie udawać, że M7 go naprawia.** M7 nie
sprawia, że transakcja znika z rzeczywistości — bank obciążył konto równowartością ~95 PLN, a w
cash flow tej kwoty nie ma. M7 zmienia tylko jedno: **z cichego błędu w arytmetyce robi nazwaną
lukę**. Bez niego saldo rośnie o 22,14 i nikt nigdy się nie dowie; z nim owner widzi wiersz, powód i
może dopisać transakcję ręcznie.

---

### T2 · Atestacja grudnia — i po co ona w ogóle jest

Owner wpisuje saldo zamknięcia z wyciągu grudniowego (**X8**):

```
POST /cash-flow/cf={id}/months/2025-12/attest   { currentMoney: … PLN }
```

Wyciąg grudniowy ma sumy: **−4 502,16** obciążeń i **+6 109,95** uznań. Cash flow policzył to samo
**minus** nieprzyjęty wiersz EUR, więc odpowiedź to **409 z różnicą ~95 PLN** i listą kandydatów:

```
Saldo nie zgadza się o 95,20 PLN.
Kandydaci:
  • 2025-12-07  CLAUDE.AI SUBSCRIPTION  22,14 EUR  — odrzucony: cash flow prowadzi PLN
```

Owner dopisuje tę transakcję ręcznie w PLN, powtarza atestację, grudzień dostaje status
**`ATTESTED`** i od tej pory nie przyjmuje importu.

**Bez X8 ten krok nie istnieje** — a wtedy różnica 95 PLN zostaje w cash flow na zawsze i nic o niej
nie mówi. To jest dokładnie to, co miałem na myśli, pisząc, że import ciągły jest dziś *możliwy, ale
niekontrolowany*.

---

### T3 · Drugie dowgranie, 11 stycznia 2026 — tu wychodzi M9

Owner ściąga wyciąg za okres **1 grudnia – 11 stycznia**, bo tak zwykle wygląda eksport z bankowości
(zakres dat, nie „miesiąc"). Dostaje **75 wierszy**: 61 grudniowych i 14 styczniowych.

| | |
|---|---|
| duplikaty | **61** — wszystkie grudniowe już są w cash flow, rozpoznane po `bankTransactionId` |
| nowe | **14** |
| pytania | zero |

I tu dwa z tych czternastu wierszy są tymi, o które chodzi w **M9**:

| operacja | księgowanie | kwota | nazwa |
|---|---|---|---|
| **2025-12-31** | 2026-01-07 | 21,99 PLN | BANK PEKAO S.A. (rozliczenie karty) |
| **2025-12-27** | 2026-01-02 | 21,99 PLN | BANK PEKAO S.A. (rozliczenie karty) |

Te wiersze **nie były** w wyciągu grudniowym — bank zaksięgował je w styczniu, więc nie weszły też do
salda, które owner zaatestował w T2. Teraz:

- **gdyby rządziła `operationDate`** → oba wiersze trafiają w grudzień, który jest `ATTESTED`, więc
  zostają **odrzucone**. Owner traci dwie transakcje, których nie mógł mieć wcześniej, i w styczniu
  brakuje mu 43,98 PLN wydatków;
- **gdyby wepchnąć je w grudzień siłą** → saldo grudnia przestaje zgadzać się z tym, co owner
  zaatestował, bo bank w grudniowym saldzie ich nie miał;
- **z wyjątkiem z M9** (miesiąc operacji zaatestowany, miesiąc księgowania otwarty → wiersz idzie do
  miesiąca księgowania, z zapisanym powodem) → oba wiersze trafiają do **stycznia**, dokładnie tam,
  gdzie umieścił je bank, a oba salda — grudniowe i styczniowe — pozostają zgodne.

**To jest argument arytmetyczny, nie wygoda:** wyjątek z M9 jest tym, co sprawia, że atestacja
miesięczna w ogóle się domyka. W backfillu dotyczył 24 wierszy na 791 i wyglądał na przypadek
brzegowy; przy dowgrywaniu miesięcznym wraca **w każdym cyklu**.

---

### T4 · Atestacja stycznia i stan ustalony

Styczeń zostaje `ATTESTED`, cash flow dalej w OPEN, i od lutego cykl powtarza się bez pytań:
**ściągnij wyciąg → wgraj → potwierdź saldo**. Trzy kliknięcia na miesiąc, zero decyzji o
kategoriach, zero wywołań modelu.

---

## 6 · Trzy pomyłki ownera i co się dzieje bez kompensacji

Ponieważ `rollbackImport` odmawia poza SETUP, trzeba wiedzieć, co realnie robi każda pomyłka.

**(a) Wgrał wyciąg z innego rachunku.** Sesja odrzucona **przed zapisaniem czegokolwiek** (**M8**),
komunikat pokazuje oba numery rachunków. Nie ma czego cofać — i to jest najlepszy możliwy wynik,
dlatego ta walidacja jest na poziomie sesji, nie wiersza.

**(b) Wgrał ten sam plik dwa razy.** Wszystkie wiersze rozpoznane jako duplikaty po
`bankTransactionId`, **zero nowych cash changes**, nic do cofania. Dlatego ekran w trybie `ONGOING`
pokazuje duplikaty jako **normalny wynik**, a nie ostrzeżenie — przy zakresach dat z bankowości
zachodzenie jest regułą (w T3: 61 z 75 wierszy).

**(c) Wgrał właściwy plik, ale źle rozstrzygnął kategorię.** **Dziś nie ma na to żadnej drogi — i
to jest gorsze, niż napisałem w pierwszej wersji tego pliku.** Zmierzone na kodzie:

| próba | co się dzieje z zaimportowanym wierszem |
|---|---|
| `POST /cash-flow/edit` | **odmowa**: `validateDueDateRange` wymaga `dueDate` w zakresie `activePeriod … +11 miesięcy`, a import ustawia `dueDate = paidDate`, czyli w przeszłości → `DueDateOutsideAllowedRangeException`. W SETUP w ogóle zablokowane (`OperationNotAllowedInSetupModeException`) |
| `BatchUpdateCashChangesCommand` | **cicho pomija**: aktualizuje tylko `PENDING`, a transakcje historyczne są importowane jako `CONFIRMED` |
| `POST /cash-flow/reject` | **cicho nic nie robi**: agregat ma `cashChange.onlyWhenIsPending(() -> setStatus(REJECTED))` |
| `rollbackImport` | tylko w SETUP, i kasuje **cały** import cash flow |

Czyli po atestacji historia nie jest tylko „własnością ownera" — jest **niezmienna**, a **dwie z
czterech prób kończą się ciszą, nie błędem**. To przesuwa **X1** z udogodnienia na warunek: bez niego
nie istnieje żadna naprawa pomyłki, a tryb „zaimportuj teraz, popraw później" (`AUTOPILOT`) jest
obietnicą bez pokrycia, bo nie ma „później". Przykład liczony: §6.1.

**Dlatego UI w trybie `ONGOING` nie pokazuje przycisku *Wycofaj*** (**U8**), a mówi, co zamiast
niego. Przycisk, który odpowiada 400, jest gorszy od jego braku.

## 6.1 · Czy atestacja musi być — i co naprawdę blokuje poprawianie

### Atestacja historyczna: tak, wymagana — ale nie z powodu salda

SETUP to tryb „tylko import": `EditCashChangeCommandHandler` rzuca
`OperationNotAllowedInSetupModeException("editCashChange")`, podobnie inne operacje. Czyli **dopóki
owner nie zaatestuje importu, cash flow jest workiem wyłącznie do zapisu** — nie da się w nim nic
poprawić, potwierdzić ani zaplanować. Kontrola salda jest przy tym drugim powodem, nie pierwszym.

### Atestacja miesiąca: **nie jest wymagana**, i to jest poprawna odpowiedź na „czy nie wystarczy wgrywać plików"

Zmierzone: `HistoricalImportAttestedEventHandler` przestawia miesiące
`IMPORT_PENDING → **IMPORTED**`, a **nie** `ATTESTED`. A `acceptsImportAt` dopuszcza `IMPORTED`.
Więc po backfillu wszystkie miesiące historyczne **nadal przyjmują import**, a `ATTESTED` pojawia się
wyłącznie wtedy, gdy ktoś jawnie zawoła `MakeMonthlyAttestationCommand` — czego dziś nie robi nikt.

**Wniosek: tryb „owner po prostu wgrywa pliki" jest w pełni działający i może być domyślny.**

| | bez atestacji miesiąca | z atestacją miesiąca (**X8**) |
|---|---|---|
| import w miesiąc wstecz | zawsze przechodzi | po atestacji odmowa |
| praca ownera | **0 kliknięć/miesiąc** | 1 kliknięcie/miesiąc |
| kontrola wobec banku | **żadna** | różnica + kandydaci na przyczynę |
| wiersz EUR z T1 | zostaje luką, o której nikt nie wie | wychodzi jako różnica ~95 PLN |
| reguła **M9** | **nieistotna** — nic nie jest `ATTESTED`, więc wyjątek to martwy kod | konieczna, i to z powodu arytmetycznego (§5 T3) |

Dlatego **X8 ma być opcjonalne (opt-in), nie obowiązkowe**, a wyjątek z **M9** ma sens dopiero razem
z nim. Kto chce szybko i bez ceremonii — wgrywa pliki. Kto chce wiedzieć, że dane zgadzają się z
bankiem — włącza atestację miesięczną i płaci za to jednym kliknięciem oraz regułą daty.

### Przykład: 147 wierszy do przekategoryzowania

Po imporcie Pekao w trybie `AUTOPILOT` dwie największe grupy trafiły do `Uncategorized` jako
prowizoryczne (liczby z fixture'u): **BADOO — 94 wiersze** i **ZABKA — 53 wiersze**. Owner otwiera
listę prowizorycznych i chce je rozdzielić: BADOO → „Subskrypcje", ZABKA → „Zakupy spożywcze".

**Dziś:** nie da się. Każda z czterech dróg z tabeli wyżej jest zamknięta, a dwie zamykają się po
cichu — UI wyśle 147 żądań, dostanie 147 odpowiedzi bez błędu i **nic się nie zmieni**.

**Z `recategorize` pojedynczym (X1):** 147 żądań, każde z nich to wczytanie agregatu, **zapis całego
dokumentu `CashFlowEntity`** (który trzyma wszystkie 1193 cash changes jako osadzoną listę),
zdarzenie na topicu i przeliczenie projekcji prognozy. Plus `@Version`: 147 warunkowych zapisów
jeden po drugim, z których każdy może przegrać z równoległą zmianą.

**Z wariantem zbiorczym:** jedno żądanie, 147 identyfikatorów, jedna kategoria, jedno zdarzenie.
I jest na to precedens w kodzie — `BatchUpdateCashChangesCommandHandler` i
`CashChangesBatchUpdatedEvent` już istnieją dla recurring rules; brakuje tylko tego, żeby obejmowały
transakcje `CONFIRMED`.

**Kształt X1, który z tego wynika:**

```java
// cashflow-api
void recategorize(CashFlowId cashFlowId, CashChangeId cashChangeId, CategoryName category);
int  recategorize(CashFlowId cashFlowId, List<CashChangeId> ids, CategoryName category);
```

Z jedną decyzją do zapisania wprost: **ta operacja pomija regułę `validateDueDateRange`**, bo ta
reguła pilnuje *planowania* (przyszła transakcja musi mieścić się w oknie prognozy), a nie *historii*.
Zastosowana do zaimportowanego wiersza blokuje jedyną sensowną zmianę, jakiej on potrzebuje — i to
jest powód, dla którego osobna, węższa operacja jest lepsza od rozluźnienia `/edit`.

## 7 · Wyrównanie salda przy każdym wgraniu — propozycja ownera, lepsza od atestacji miesiąca

Pytanie ownera: *czy przy wgrywaniu pliku nie zapytać na końcu, czy to aktualne transakcje konta, i
nie pozwolić potwierdzić albo poprawić salda — a różnicę dopisać jako wyrównanie? Bo owner może
logować się raz na dwa, trzy miesiące.*

**Odpowiedź: tak, i ten mechanizm jest już w kodzie — tylko odpala się dokładnie raz.**

### Co już istnieje

`AttestHistoricalImportCommand(cashFlowId, confirmedBalance, forceAttestation, createAdjustment)`.
Gdy saldo podane przez ownera różni się od policzonego:

- bez `createAdjustment` i bez `forceAttestation` → wyjątek (dzisiejszy `CASHFLOW_BALANCE_MISMATCH`);
- z `createAdjustment` → agregat tworzy **cash change**: `Name("Balance Adjustment")`,
  `Description("Automatic adjustment to reconcile balance difference")`, kwota = `|różnica|`, kierunek
  z jej znaku, kategoria `Uncategorized`, status `CONFIRMED`, a `bankTransactionId` **null** z
  komentarzem *„a balance adjustment the owner confirmed, not a bank transaction"*;
- projekcja (`HistoricalImportAttestedEventHandler`) dokłada go do **miesiąca ACTIVE**.

Czyli propozycja ownera to **uogólnienie sprawdzonego kodu**, nie nowy pomysł: to samo zdarzenie, ta
sama korekta, ta sama zasada „ląduje w bieżącym miesiącu" — tylko wywoływane po każdym wgraniu, a nie
raz na zakończenie wdrożenia.

### Czego nie trzeba pytać, bo bank już to powiedział

Wyciąg Nest ma kolumnę **`Saldo po operacji`**, a jej wartość w najnowszym wierszu to **76 047,25** —
dokładnie ta liczba, którą `CASHFLOW_IMPORT_TEST_GUIDE` każe testerowi **przepisać ręcznie** jako
`confirmedBalance`. **Transformacja tę kolumnę wyrzuca**: format kanoniczny ma 17 kolumn i żadna nie
nosi salda.

| bank | saldo bieżące w wyciągu | wniosek |
|---|---|---|
| **Nest** | **tak**, `Saldo po operacji` w każdym wierszu | kontrola **darmowa i bez pytania** — porównaj policzone saldo z saldem ostatniego wiersza |
| **Pekao** | **nie**, brak takiej kolumny | trzeba zapytać ownera |

Stąd projekt dzieli się na dwie ścieżki, a nie jedną:

1. **Wyciąg nosi saldo** → system sprawdza sam, bez jednego kliknięcia. Zgadza się — cisza. Nie
   zgadza się — ekran z różnicą i kandydatami, i to owner decyduje o wyrównaniu.
2. **Wyciąg nie nosi salda** → pytamy, ale **precyzyjnie** (niżej).

To wymaga **osiemnastej kolumny w formacie kanonicznym** (`balanceAfter`, wypełniana, gdy bank ją
daje) — czyli jednej zmiany w adapterze, który i tak już dokleja trzy kolumny przy enrichmencie. Do
tego czasu ścieżka 2 obsługuje oba banki.

### Pytanie trzeba zadać precyzyjnie, bo w jednej wersji psuje dane

**Wersja groźna:** *„Czy to aktualne saldo Twojego konta?"* zadane dla wyciągu, który kończy się dwa
dni wcześniej.

> 11 stycznia owner ściąga wyciąg za 1–9 stycznia. Jego realne saldo **dziś** zawiera jeszcze
> transakcje z 10 i 11 stycznia, których w pliku nie ma. Owner potwierdza saldo z bankowości, system
> liczy różnicę 250 zł i tworzy wyrównanie. W lutym owner wgrywa kolejny plik — i te transakcje z
> 10–11 stycznia **wchodzą normalnie**. Wynik: **te same 250 zł policzone dwa razy.**

**Wersja bezpieczna:** *„Jakie saldo miało konto po ostatniej operacji z tego pliku — 9 stycznia?"*
Pytanie jest weryfikowalne (owner widzi tę datę w historii banku), nie ma luki czasowej i nie ma czego
policzyć podwójnie.

Różnica w jednym zdaniu: **pytamy o saldo na dzień ostatniej operacji w pliku, nigdy o „saldo dziś".**

### Owner logujący się raz na dwa, trzy miesiące — co wtedy

Tu ta propozycja wypada najlepiej, bo nic nie wymaga regularności:

| co | jak się zachowuje bez ownera |
|---|---|
| przejście miesiąca | `MonthlyRolloverScheduler`, cron `0 0 2 1 * *` (2:00 pierwszego dnia), z **nadrabianiem**, gdy `activePeriod` jest więcej niż miesiąc z tyłu — dzieje się po stronie serwera, logowanie nie jest potrzebne |
| możliwość importu wstecz | miesiące zostają `IMPORTED` albo `ROLLED_OVER`, a oba **przyjmują import** — plik za trzy miesiące wchodzi bez przeszkód |
| pytanie o saldo | **raz na wgranie**, nie raz na miesiąc — owner logujący się w marcu odpowiada jeden raz, dla pliku za grudzień–luty |
| wyrównanie | jedna korekta, w miesiącu bieżącym |

**Uczciwy koszt, i trzeba go nazwać:** korekta jest przypisana do miesiąca **wgrania**, nie do
miesiąca, w którym powstał błąd. Przykład: dziura 95 zł z grudniowego wiersza w EUR, odkryta w marcu
→ grudzień zaniża wydatki o 95 zł, marzec zawyża o 95 zł, **suma jest poprawna**. Dla budżetu
osobistego to standardowy kompromis rozliczeniowy, ale korekta musi o tym **mówić**: nie
`"Balance Adjustment"`, a *„Wyrównanie salda za okres XII 2025 – II 2026"*, z listą kandydatów na
przyczynę obok.

Drugi koszt jest wprost proporcjonalny do rzadkości logowania: im dłuższa przerwa, tym więcej
pochłania jedna korekta i tym trudniej powiedzieć, **co** ją spowodowało. Dlatego ekran różnicy
pokazuje kandydatów (wiersze w obcej walucie, odrzucone, prowizoryczne) — a nie samą liczbę.

### Warunek, bez którego wyrównanie jest niebezpieczne: wykrycie luki

**Wyrównanie salda bez wiedzy o lukach w danych pochłonie cały brakujący miesiąc jako jedną
korektę** — i owner zatwierdzi to jednym kliknięciem, nie wiedząc, co właśnie zaakceptował.

Zmierzone na fixture Pekao: gdyby owner pominął jeden wyciąg, różnica salda wyniosłaby
**−1 583,31 PLN** dla stycznia (12 transakcji) albo **+2 105,58 PLN** dla grudnia (58 transakcji).
Ekran pokazałby jedną liczbę i przycisk „Utwórz wyrównanie", a cash flow dostałby jedną transakcję
zamiast pięćdziesięciu ośmiu — z poprawną sumą i **bezużytecznym** rozbiciem na kategorie.

Dlatego kontrola salda wymaga **wysokiej wody**: daty ostatniej zaimportowanej transakcji w tym cash
flow. I to jest darmowe — `alreadyImported`, które staging woła raz na wgranie dla deduplikacji,
zwraca `ImportedTransaction` z polem **`paidDate`**, więc maksimum liczy się z danych, które już mamy.
Bez dodatkowego żądania, bez nowej kolumny, bez zmiany kontraktu.

Co z tego wynika dla przepływu:

| sytuacja | co widzi owner **zanim** cokolwiek zatwierdzi |
|---|---|
| plik zaczyna się po wysokiej wodzie | **„Masz dane do 31 XII, ten plik zaczyna się 1 II — brakuje stycznia."** Wyrównanie jest **zablokowane**, dopóki owner nie wgra brakującego wyciągu albo nie potwierdzi świadomie, że go nie ma |
| plik nachodzi na wysoką wodę | „61 z 75 wierszy już masz" — i to jest normalny wynik, nie ostrzeżenie |
| cały plik starszy niż wysoka woda | „Ten plik jest w całości starszy niż Twoje dane — na pewno ten?" Dziś taki wyciąg wchodzi jako same duplikaty i nikt nie pyta |

### Projekt pokrycia: wyprowadzone z sesji, nie z transakcji

**Pierwsza wersja tego pomysłu — „maksimum `paidDate` z `alreadyImported`" — jest za wąska i warto
powiedzieć dlaczego**, bo błąd jest pouczający. Wysoka woda to jedna data, więc nie odróżnia dwóch
rzeczy, które dla ownera są zupełnie różne:

> **styczeń pusty, bo nie wgrano wyciągu** ≠ **styczeń pusty, bo nic się nie wydarzyło**

Z samych transakcji w cash flow to jest **nierozróżnialne**. Z historii wgrań — doskonale: „wgrano
wyciąg za 1–31 stycznia, zawierał 0 wierszy" to inna informacja niż „żaden wyciąg nie objął
stycznia". Stąd decyzja: **pokrycie jest twierdzeniem o tym, co wgrano, a nie o tym, jakie
transakcje istnieją** — więc należy do ingestion, a nie do cashflow, i mieszka w **conductorze**,
bo to on ma sesje i on blokuje wyrównanie.

Druga wada jednej daty: widzi tylko lukę **przed bieżącym plikiem**. Nie powie o dziurze z zeszłego
roku i nie rozpozna, że wgrywany plik **zapełnia** znaną lukę — pokaże go jako „cały starszy niż
Twoje dane".

```java
// czysta funkcja: bez Springa, bez bazy, testowalna tabelą
Coverage coverage(List<IngestionSession> imported, YearMonth startPeriod, LocalDate today);

record Coverage(List<CoveredRange> ranges, List<Gap> gaps, LocalDate highWaterMark) {}
record CoveredRange(LocalDate from, LocalDate to, PeriodSource source, int rows, String sessionId) {}
record Gap(LocalDate from, LocalDate to, Acknowledgement ack) {}   // ack == null → nieuznana

record Acknowledgement(Reason reason, String note, Instant at) {}
enum Reason { ACCOUNT_OPENED_LATER, BANK_CANNOT_EXPORT, NO_TRANSACTIONS, OTHER }
```

**Jedyne nowe pole na sesji** — wypełniane w kroku `PARSE`, resztę funkcja wylicza:

```java
record StatementPeriod(LocalDate from, LocalDate to, PeriodSource source) {}

enum PeriodSource { UPLOAD_PARAM, STATEMENT_HEADER, DERIVED_FROM_ROWS }
```

#### Algorytm, sześć kroków

```java
Coverage coverage(List<IngestionSession> sessions, YearMonth startPeriod, LocalDate today) {

    // 1. tylko sesje, które naprawdę coś wniosły — skompensowane wypadają, więc luka wraca
    var periods = sessions.stream()
            .filter(s -> s.phase() == IMPORTED || s.phase() == PARTIALLY_IMPORTED)
            .map(IngestionSession::statementPeriod)
            .sorted(comparing(StatementPeriod::from))
            .toList();

    // 2. scalanie: przedziały nachodzące ORAZ stykające się dniem (to + 1 dzień == from)
    var ranges = merge(periods);

    // 3. horyzont: od pierwszego dnia startPeriod do dziś — bez tego luka PRZED
    //    pierwszym wyciągiem jest niewidoczna
    var horizon = new Range(startPeriod.atDay(1), today);

    // 4. luki = dopełnienie scalonych przedziałów w tym horyzoncie
    var gaps = horizon.minus(ranges);

    // 5. nakładamy trwałe uznania; uznanie jest przypisane do PRZEDZIAŁU, więc po częściowym
    //    zapełnieniu zostaje na resztę i nie pytamy od nowa
    gaps = gaps.stream().map(g -> g.withAck(acknowledgementFor(g))).toList();

    // 6. ostatni dzień pokrycia — osobny od wysokiej wody transakcji z alreadyImported,
    //    która służy tylko jako kontrola krzyżowa
    return new Coverage(ranges, gaps, ranges.isEmpty() ? null : last(ranges).to());
}
```

Krok 2 jest całą regułą scalania: `[1–9]` + `[10–31]` → **jeden** przedział `[1–31]` i zero luk;
`[1–9]` + `[11–31]` → luka `[10–10]`. Krok 3 jest tym, co odróżnia ten algorytm od wysokiej wody:
dolna granica bierze się z deklaracji cash flow, nie z danych, więc dziura na początku historii
istnieje, zamiast być z definicji poza zasięgiem.

Nie wymaga nowej kolekcji ani nowego żądania: sesje są już trwałe, mają indeks po `cashFlowId` i
przeżywają wygaśnięcie wierszy. Liczą się sesje `IMPORTED` i `PARTIALLY_IMPORTED`, a skompensowane
wypadają.

#### Skąd brać przedział — i dlaczego nie z min/max wierszy

Tylko **okres wyciągu** potrafi powiedzieć „ten miesiąc jest pokryty, choć pusty". Źródła w
kolejności zaufania:

| # | źródło | dostępność |
|---|---|---|
| 1 | **parametry wgrania** — owner właśnie wybrał zakres dat w bankowości, więc UI go zna | zawsze, jeśli UI go przekaże |
| 2 | **metadane wyciągu** — surowy Nest ma w nagłówku `Historia operacji za okres od 01.01.2023 do 11.01.2026` | **tylko część banków**: Nest tak, Pekao nie — **ten sam wzorzec co z saldem**, a transformacja i tak to wyrzuca |
| 3 | min/max wierszy | zawsze, ale wtedy `source = DERIVED_FROM_ROWS`, żeby było widać, że pokrycie jest **wnioskowane**, nie zadeklarowane |

#### Granulacja: dni, nie miesiące

Wyciągi przychodzą jako zakresy dni, a dziura 10–14 stycznia to pięć dni prawdziwych transakcji.
Liczymy więc w dniach, a UI rysuje miesiące: miesiąc jest **częściowo pokryty**, gdy brakuje w nim
choć jednego dnia. Przedziały stykające się dniem (`to + 1 == from`) scalają się; `[1–9]` i `[11–31]`
zostawiają lukę `[10–10]`.

#### Luka wiąże się z RÓŻNICĄ, nie z samym swoim istnieniem

Pierwsza wersja mówiła „luka blokuje wyrównanie". **Za grubo** — tworzyłoby to fałszywe alarmy:
wgrywasz 1–9 i 11–20 stycznia, dziesiątego nic się nie wydarzyło, a system blokuje.

| różnica salda | luka | reakcja |
|---|---|---|
| **0** | brak | cisza |
| **0** | jest | **informacja** o luce — nie ma czego blokować, bo nie ma żadnej korekty do utworzenia |
| **≠ 0** | brak | różnica + kandydaci + wyrównanie dozwolone |
| **≠ 0** | **jest** | **blokada**: „zanim wyrównasz, wgraj brakujące 2026-01-10 … 2026-02-28" |

Domyka się to logicznie: **luka blokuje wyłącznie tworzenie korekty, a korekta istnieje tylko przy
niezerowej różnicy** — więc fałszywy alarm znika sam. Jedno zastrzeżenie, którego nie ukrywamy:
różnica zero **nie dowodzi**, że w luce nie było transakcji (mogło być `+100` i `−100`), dlatego w
drugim wierszu nadal informujemy — dla raportów miesięcznych ta luka ma znaczenie, choć dla salda nie.

Import wierszy z pliku **nigdy** nie jest blokowany, w żadnym z czterech przypadków.

#### Przykład na naszych danych, w tym luka, której bym nie wymyślił

Trzy wgrania do cash flow Pekao, `startPeriod = 2025-01`:

| sesja | okres wyciągu | wierszy |
|---|---|---|
| A | 2025-01-11 … 2025-11-30 | 716 |
| B | 2025-12-01 … 2025-12-31 | 61 |
| C | 2026-01-01 … 2026-01-09 | 14 |

Scalone pokrycie: **2025-01-11 … 2026-01-09**. A luka:
**2025-01-01 … 2025-01-10** — dziesięć dni, bo cash flow deklaruje `startPeriod` na styczeń, a
pierwszy wyciąg zaczyna się jedenastego. To realna sytuacja z fixture'u i naturalny przypadek dla
uznania z powodem `ACCOUNT_OPENED_LATER`; bez horyzontu liczonego od `startPeriod` byłaby niewidoczna.

#### Przypadki brzegowe, rozstrzygnięte

| przypadek | odpowiedź |
|---|---|
| wyciąg jednodniowy | zwykły przedział `[d, d]`, scala się normalnie |
| dwa wyciągi w jednym miesiącu | dwa przedziały, scalane; miesiąc pokryty tylko gdy razem pokrywają wszystkie dni |
| wyciąg przez granicę roku | przedział dni nie zna miesięcy; podział na bloki robi UI przy rysowaniu |
| luka uznana, potem **częściowo** zapełniona | uznanie jest przypisane do przedziału — po zapełnieniu części zostaje na resztę, bez pytania od nowa |
| sesja skompensowana | wypada z pokrycia, bo jej wiersze zniknęły z cash flow, i **luka wraca** |
| pierwsze wgranie w ogóle | brak sesji → pokrycie puste, horyzont cały; luka jest, a blokada działa tą samą regułą |

#### API

```
GET  /api/v2/ingestion/coverage?cashFlowId=CF10000001
     → { ranges: [{from, to, source, rows, sessionId}],
         gaps:   [{from, to, days, ack}],
         highWaterMark: "2026-01-09" }

POST /api/v2/ingestion/coverage/gaps/acknowledge
     { cashFlowId, from, to, reason: ACCOUNT_OPENED_LATER, note }
```

Pierwsze jest też odpowiedzią na pytanie, którego dziś nie da się zadać: **jak daleko sięgają moje
dane**.

Uznanie jest **trwałe i ma powód** — inaczej system pytałby o to samo przy każdym wgraniu. Cztery
powody wystarczają na realne przypadki: konto otwarte później, bank nie wyeksportuje tego okresu,
miesiąc bez transakcji, inne.

#### Zapełnienie luki to zwykłe wgranie

I dopiero stąd płynie korzyść z trzymania pokrycia zamiast jednej daty: plik za styczeń, wgrany w
marcu, przestaje być „plikiem starszym niż dane" i staje się **„ten plik zapełnia Twoją lukę
1–31 stycznia"**. Po imporcie luka znika z osi, a wyrównanie się odblokowuje.

#### Wysoka woda zostaje — jako kontrola krzyżowa

Maksimum z `alreadyImported` nie idzie do kosza. Jeśli cash flow ma transakcje **poza** jakimkolwiek
przedziałem pokrycia, weszły inną drogą: ręcznie, z reguł cyklicznych albo z wyrównania. To stan
**legalny**, więc nie wolno go raportować jako luki — ale rozjazd między „co wgrano" a „co jest" to
dokładnie ta informacja, która kiedyś wyjaśni dziwną różnicę salda.

### Trzy różne punkty odniesienia, i tylko jeden z nich to „dziś"

Pytanie, które to wywołało: *czy nie porównywać z datą ostatniej wgranej transakcji zamiast z
obecnym dniem?* Odpowiedź: **to trzy osobne pytania i każde ma inny punkt odniesienia** — mieszanie
ich byłoby błędem.

| punkt odniesienia | odpowiada na pytanie | używany dziś |
|---|---|---|
| **zegar** (`now`) | czy ta transakcja już się wydarzyła | **tak** — jedyne użycie zegara w walidacji: `paidDate.isAfter(now)` → `IMPORT_DATE_IN_FUTURE` |
| **wysoka woda** (max `paidDate` w cash flow) | czy ten plik ma lukę, nachodzi, czy jest stary | **nie — brak w projekcie** |
| **zakres pliku** (max data w pliku) | czy w samym pliku jest data odstająca | **nie — brak** |

**Czego nie wolno zrobić:** zastąpić zegara wysoką wodą w sprawdzeniu przyszłości. Wysoka woda jest
z definicji w przeszłości, a transakcja nowsza od niej to **normalny przypadek** — na tym polega
wgrywanie kolejnego wyciągu. Sprawdzenie przyszłości zostaje przy zegarze, bo pieniądze nie mogą
ruszyć się w przyszłości, i to jest reguła o świecie, nie o naszych danych.

**Co warto zapisać jako znaną własność:** to jedno sprawdzenie czyni walidację zależną od **momentu
wgrania** — ten sam plik z wierszem o dacie jutrzejszej (bank potrafi nadać przyszłą datę waluty)
zostanie odrzucony dziś i przyjęty pojutrze. Na obu zmierzonych wyciągach **nie ma ani jednego
wiersza z przyszłą datą**, więc nie dorabiamy tolerancji na zapas — ale gdy pierwszy taki plik się
pojawi, to jest miejsce, które trzeba obejrzeć, a nie zgadywać.

### Co ta propozycja **usuwa** z planu

| było | po |
|---|---|
| atestacja miesiąca jako warunek kontroli (**X8**) | **niepotrzebna jako wymóg** — kontrola odbywa się przy wgraniu, a nie na granicy miesiąca |
| reguła „która data rządzi miesiącem" z wyjątkiem dla miesiąca zaatestowanego (**M9**) | **wyjątek przestaje być potrzebny**, bo nic nie staje się `ATTESTED`; zostaje sam wybór daty |
| ekran „potwierdź saldo miesiąca" | zastąpiony krokiem w przepływie wgrania |

To rzadki przypadek, w którym propozycja **zdejmuje** zadania z tablicy, a nie dokłada. Zostaje
jedno nowe (wyrównanie przy wgraniu) plus osiemnasta kolumna, a dwa schodzą do opcjonalnych.

## 8 · Kolejność

1. **S17** — tryb na sesji. Bez niego każda kolejna decyzja musi być podejmowana dwa razy.
2. **X1** — `recategorize`, pojedynczo i zbiorczo. **Pierwsze, nie trzecie**: dziś nie istnieje żadna
   droga poprawienia kategorii zaimportowanej transakcji, a dwie z czterech prób kończą się ciszą.
   To warunek trybu `AUTOPILOT` i jedyna naprawa pomyłki w trybie ciągłym.
3. **X2** — `deleteImported`, kompensacja per sesja. Do tego czasu UI nie obiecuje wycofania.
4. **U8** — ekrany trybu ciągłego, bez przycisku *Wycofaj*.
5. **X8** — atestacja miesiąca, **opcjonalnie**. Nie jest warunkiem działania, jest warunkiem
   *kontroli*: bez niej import ciągły przechodzi zawsze i nic nie weryfikuje wyniku wobec banku.
6. **M9** — wyjątek dla miesiąca zaatestowanego. Ma sens **tylko razem z X8**; bez niego `ATTESTED`
   nie występuje i wyjątek jest martwym kodem.

Punkty 1–2 są warunkiem, żeby import ciągły był **naprawialny**. Punkty 5–6 są warunkiem, żeby był
**kontrolowany** — i to jest wybór ownera, nie wymóg systemu.
