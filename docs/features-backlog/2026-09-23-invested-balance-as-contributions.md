# `investedBalance` jako lista wkładów

Rozstrzygnięcie zadania **C9** i fundament pod **C5**. Stan: **zaprojektowane, niezaimplementowane**.

Dokument powstał z rozmowy projektowej, nie z implementacji — decyzje są podjęte, kod nie istnieje.
Statusy zadań mieszkają w [tablicy](2026-09-18-okx-tasks.md); tutaj są powody.

---

## 1. Problem

`investedBalance` to pojedyncze `Money` na agregacie `Portfolio`, ruszane **wyłącznie** przez
`deposit` i `withdraw`. Portfel zbudowany ze snapshotu giełdy nie przechodzi przez żadne z nich,
więc zostaje zerem. Z żywego uruchomienia OKX:

```
BTC  0,00327  →     231,01 EUR   koszt 75 125,148 USD (EXCHANGE_REPORTED)   [traded]
BTC  1,0      →  70 644,40 EUR   koszt nieznany                             [transferred-in]
ETH  1,0      →   2 297,40 EUR   koszt nieznany                             [transferred-in]
EUR  4 386,68 →   4 386,68 EUR   po parze                                   [none]
USD  5 000    →   4 300,00 EUR   po parze
USDC 5 000    →   4 250,00 EUR   po parze
XRP  50 000   →  61 475,00 EUR   koszt nieznany
                ─────────────
                147 584,49 EUR   pokrycie 8,92%   investedBalance 0
```

Po C3 to pole **nie psuje już wyniku** — wynik liczy się z kosztów pozycji, nie z różnicy wobec
wpłat. Ale samo pole nadal jest w odpowiedzi API i czyta się jako fakt: *włożyłeś 0, masz 147 584*.

**Dlaczego skalar nie wystarczy.** To, co wchodzi do portfela, jest niejednorodne: 5 000 PLN
przelewem, 1 BTC z innej giełdy, 5 000 USDC z portfela sprzętowego. Zwinięcie tego do jednej liczby
wymaga wybrania waluty **i momentu** — a moment jest właśnie tym, czego przy portfelu ze snapshotu
nie ma w chwili zapisu. Lista niczego nie musi wybierać.

---

## 2. Decyzja prowadząca: wkład to nie koszt nabycia

Najważniejsze rozstrzygnięcie w całym projekcie. Przenosisz 1 BTC z Binance na OKX:

- **wkład do tego portfela** = 1 BTC, wart 60 000 EUR w dniu przyjścia
- **koszt nabycia** = 8 000 EUR, bo kupiłeś go w 2019 na innej giełdzie

Obie liczby są prawdziwe i odpowiadają na różne pytania, bo **mierzą od różnych granic**:

| | granica | pytanie |
|---|---|---|
| `CostBasis` (C1) | posiadanie waloru | ile mnie kosztowało, że go mam |
| `Contribution` | **ten portfel** | ile przeniosłem na to konto |

Jeżeli „wartość w dniu transferu" wycieknie do `CostBasis`, odtworzymy dokładnie to kłamstwo, które
C1 wyplenił — tylko piętro wyżej. Te dwie rzeczy stoją obok siebie i **nigdy się nie mieszają**.

Konsekwencja dla oceny konta: właściwym mianownikiem dla *tego* portfela jest 60 000, nie 8 000.
Przeniesienie BTC nie jest nową inwestycją, ale walor pojawił się na koncie w pewnej wartości i to
ona jest punktem odniesienia dla wyniku **tego konta**.

---

## 3. Co mamy w danych

**Datę transferu mamy.** `GET /asset/deposit-history` zwraca `ts`, `ccy`, `amt`, `txId`, `state`
(2 = zaksięgowane), `chain`. Dwa skrypty POC już to czytają (`okx-readonly-export.mjs`,
`okx-ws-listener.mjs`). **Brak udokumentowanego limitu retencji** — w odróżnieniu od
`fills-history` i `bills-archive` (3 miesiące) oraz `asset/bills` (1 miesiąc). Oś czasu wkładów da
się więc odtworzyć wstecz nawet tam, gdzie historia transakcji już wygasła.

**Ceny historyczne są osiągalne.** `GET /market/history-candles?instId=BTC-USDT&bar=1D` — OHLCV, do
100 na żądanie. Wycena w PLN wymaga dodatkowo łańcucha denominacji (B4).

**Znalezione przy okazji — osobna usterka.** Snapshot czyta wyłącznie
`GET /api/v5/account/balance`, czyli **samo Trading**, choć `OKX-CONTEXT.md` mówi wprost: *„Both
must be summed — deposits land in Funding, trading happens in Trading"*. Praktycznie: wpłacasz 1 BTC
i nie przesuwasz go do Trading — Vidulum **w ogóle go nie widzi**. Niezależne od tego projektu,
warte osobnego zadania.

---

## 4. Rozstrzygnięcia

| # | pytanie | decyzja | powód |
|---|---|---|---|
| 1 | lista czy skalar | **lista pozycji** | brak kursu dla jednego waloru psuje całą liczbę; przy liście psuje jedną pozycję, a pokrycie mówi ile — reguła C4 o poziom niżej |
| 2 | jeden mechanizm czy dwa | **jeden** — `deposit`/`withdraw` też dopisują wkłady | dwa mechanizmy to dwie prawdy o tej samej rzeczy; przejechaliśmy się na tym trzy razy (C10, G3, G5) |
| 3 | `investedBalance` w API | **zostaje, ale pod nową nazwą**, wyprowadzone, z pokryciem i statusem | znaczenie się zmienia, więc nazwa musi — tak jak przy `unrealisedProfit` |
| 4 | granica portfela | **całe konto** (Trading + Funding) | człowiek nie myśli subkontami giełdy; przy samym Trading przesunięcie własnych środków między własnymi subkontami byłoby „wkładem" i stopa zwrotu straciłaby sens |
| 5 | wypłaty | **jedna lista, kierunek jako pole** (`IN`/`OUT`) | stopa ważona pieniądzem potrzebuje obu chronologicznie; ujemna `Quantity` otwiera furtkę, którą `SnapshotPosition` celowo zamyka |
| 6 | backfill przy onboardingu | **nie** — jeden **wkład otwarcia**, backfill później | onboarding ma już swoje tryby awarii; backfill wymaga cen historycznych, których nie mamy |

### Domyślne, niewymagające dyskusji

- **Każdy wkład ma własną tożsamość** — backfill ma go później zastąpić albo rozbić, a nie da się
  zastąpić czegoś, czego nie można wskazać.
- **Wkład otwarcia obejmuje gotówkę** — EUR, USD, USDC ze snapshotu to wkład jak każdy inny, tylko
  jego wartość jest znana trywialnie.
- **Wycena na moment `confirm`**, w walucie portfela.
- **Własna proweniencja** dla wkładu otwarcia, np. `OPENING_SNAPSHOT`. Nie `EXCHANGE_REPORTED`,
  bo giełda nie powiedziała „to jest twój wkład"; powiedziała „tyle masz". Ta różnica musi być
  widoczna w danych, nie w komentarzu.
- **Lista mieszka w agregacie `Portfolio`** — wydzielanie kolekcji dopiero, gdy będzie po co.

---

## 5. Kształt

```
Contribution(
    id,              // tożsamość - backfill ma co zastąpić
    when,            // ts z deposit-history albo zegar przy deposit
    direction,       // IN | OUT
    what,            // Money(5000, PLN)  |  Quantity(1, BTC)
    valueAtArrival,  // Money w walucie portfela — MOŻE BYĆ PUSTE
    provenance,      // EXCHANGE_REPORTED | USER_PROVIDED | OPENING_SNAPSHOT | brak
    source           // CASH_DEPOSIT | TRANSFER_IN | WITHDRAWAL | ...
)
```

`investedBalance` przestaje być polem i staje się **odczytem wyprowadzonym**: suma znanych
`valueAtArrival` plus pokrycie. Jeśli połowa wkładów nie ma wartości, nie dostajesz sumy, tylko
status.

**Wkład bez wartości jest pełnoprawny** — dokładnie jak pozycja bez kosztu w C1. Praktyczna
konsekwencja: oś czasu można wdrożyć, **zanim** będą ceny historyczne. Najpierw „1 BTC, 17 marca,
wartość nieznana", potem dociąganie świec jako osobne zadanie.

---

## 6. Impakt

### Skutek strukturalny: `confirm` musi zacząć czytać notowania

Wkład otwarcia niesie wartość w dniu przyjścia, a `ConfirmPortfolioSpecCommandHandler` tworzy dziś
portfel nie mając pojęcia o cenach — wycena dzieje się dopiero przy `GET /portfolio`.

Obejścia nie ma: zapisanie samych ilości i doliczenie wartości przy pierwszym odczycie dałoby cenę
z chwili pierwszego zajrzenia — liczbę niedeterministyczną i nie tę, o którą chodzi.

**Dane już tam są.** E8 wymusiło publikację kursów przed onboardingiem, bo inaczej `GET /portfolio`
rzuca `QuoteNotFoundException`. Nowa jest zależność, nie dane.

**Zmienia się moment awarii**: dziś brak kursu wywala pierwszy odczyt, po zmianie wywali `confirm`.
Uważamy to za poprawę — lepiej nie utworzyć portfela, niż utworzyć taki, którego nie da się
odczytać — ale to zmiana zachowania, nie szczegół.

### Rozejście po kodzie

| warstwa | co się zmienia |
|---|---|
| `Portfolio` | dochodzi lista wkładów, znika pole `investedBalance`; `depositMoney`/`withdrawMoney` zapełniają listę; nowy niezmiennik do pilnowania, rzędu `quantity = locked + free` |
| `PortfolioSnapshot`, `PortfolioEntity` | nowe pole listy, usunięte stare |
| `PortfolioDto` | nowa nazwa + `coverage` + status, wzorem `unrealisedProfit` |
| `AggregatedPortfolio` | osobna instalacja do przerobienia: rekord `PortfolioInvestedBalance`, `appendPortfolioInvestedBalance`, sumowanie po brokerach |
| PnL | `MakePnlSnapshotCommandHandler` czyta `getInvestedBalance()` z obu podsumowań; `PnlStatement`, `PnlPortfolioStatement` i encje niosą pole dalej — przemianowanie przechodzi przez cały moduł, a pole staje się **nullowalne** |
| risk-management | `RiskManagementStatement.investedBalance` — to samo |
| testy | każda asercja całoobiektowa na `investedBalance`: `PortfolioSummaryMapperTest`, `LockingAssetsTests`, `TradingPortfolioIntegrationTest`, plus `PreciousMetalsLifecycleComponentTest` (wpłaty tworzą wkłady) |
| POC | w `okx-smoke.mjs` asercja *„invested balance is zero (task C9)"* **odwraca się**; z `okx-portfolio.mjs` znika linia `<- always zero for a snapshot-built portfolio` |
| baza | encja zyskuje listę, traci pole; wolumeny i tak czyścimy — migracji nie ma |

### Czego to **nie** naprawia

Pokrycie wyniku dla portfela ze snapshotu **zostaje na 8,92%**. Wkład i koszt to różne miary;
zapisanie, że 1 BTC przyszło warte 70 644 EUR, nie mówi nic o tym, ile kosztowało. C1 i C4 zostają
nietknięte — i tak ma być, patrz §2.

### Co to odblokowuje

- **C5** staje się liczalne: zmiana majątku = różnica wycen **minus wkłady w oknie**. Bez tej listy
  wpłata 10 000 wygląda identycznie jak wzrost wartości o 10 000, więc C5 jest nieliczalne.
- **Backfill** dostaje definicję: zastąp wkład otwarcia prawdziwymi depozytami.
- Pierwsza uczciwa **stopa zwrotu z konta** — wreszcie jest mianownik, który nie jest ani zerem,
  ani zmyśleniem.

---

## 7. Podział na kroki

Robione naraz da PR, którego nikt porządnie nie przejrzy: agregat, encja, trzy moduły konsumujące,
POC i kilkanaście testów całoobiektowych. Cztery kroki, każdy osobno wysyłalny:

1. ✅ **Zrobione (C9). Model wkładów i jeden mechanizm.** Lista w agregacie, `deposit`/`withdraw` ją zapełniają, nowa
   nazwa w API z pokryciem i statusem. **Bez ścieżki snapshotu** — portfele z wpłat działają od razu
   i pełną wartością, bo tam wszystko jest znane. Nowa mechanika sprawdzona tam, gdzie nie ma
   niewiadomych.
2. ✅ **Zrobione (C12). Wkład otwarcia przy `confirm`.** Tu wchodzi zależność od notowań i zmiana momentu awarii.
   Osobno, bo to jedyny krok ze strukturalną konsekwencją.
3. **C5** na gotowym fundamencie.
4. **Backfill.** Dopiero wtedy `history-candles` i granica konta z §4.4.

---

## 8. Zostaje do rozstrzygnięcia

- ~~**Nazwa** pola zastępującego `investedBalance` w API.~~ Rozstrzygnięte w C9: `netContributions`,
  obok `contributionCoverage` i `contributionStatus` — ten sam trójkąt, co przy wyniku (C4), bo to ta
  sama reguła zastosowana o poziom niżej.
- **Kiedy** wprowadzić granicę całego konta (§4.4) — nie blokowało kroków 1–2, blokuje krok 4.
- Czy backfill sięga wstecz **poza** datę utworzenia portfela w Vidulum, czy zatrzymuje się na niej.
- Czy wypłata gotówki i wypłata waloru to ten sam `source`, czy dwa różne.
