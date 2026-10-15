# Prowizja w koszcie nabycia i w wyniku zrealizowanym

| | |
|---|---|
| **Status** | zadanie, **nic nie zaimplementowane** |
| **Decyzja** | podjęta: **prowizja wchodzi** do kosztu bazowego |
| **Skąd** | krok **05d** w `2026-10-12-module-contracts-api-impl-split.md`; wyszło przy przeglądzie `vidulum-trading-api` po kroku 05a |
| **Zakres** | `vidulum-trading-api`, `vidulum-trading`, `vidulum-portfolio` (od kroku 06c osobne artefakty; luka (c) jest w agregacie portfela) |
| **Data** | 2026-10-13 |

---

## 1. Stan: prowizja nie opuszcza tradingu

```
fee żyje w:   trading/domain/Trade · MakeTradeCommand · TradeRestController · TradingDto
w pakiecie portfolio: słowo „fee" nie występuje ani razu
```

`MakeTradeCommandHandler` liczy i zapisuje w trade'ie `totalValue = value + totalFee`, ale ani
`TradeConfirmedEvent`, ani `ProcessTradeCommand`, ani `ExecutedTrade` nie mają pola prowizji. Portfel
dostaje wyłącznie cenę i ilość, więc `Portfolio.swing()` wycenia nabycie jako `cena × ilość`.

Prowizja ma dwa składniki, **każdy z własną walutą**:

```java
// trading/domain/Trade
public record Fee(
        Money exchangeCurrencyFee,   // zwykle w tokenie giełdy (np. BNB)
        Money transactionFee,        // zwykle w walucie rozliczenia
        Money totalFee) {}
```

To nie jest skutek kroku 05a — tak było w `OrderFilledEvent` i `TradeCapturedEvent`. Ale
`TradeConfirmedEvent` jest teraz **jedyną** drogą trade'u do portfela, czyli jedynym miejscem, gdzie
prowizję da się dołożyć.

## 2. Trzy luki, nie jedna

**(a) Koszt nabycia jest zaniżony.** Kupno 1 BTC po 70 000 z prowizją 50 daje dziś `costBasis`
70 000, a powinno 70 050. W konsekwencji `unrealisedProfit` i `pctUnrealisedProfit` są zawyżone.

**(b) Wynik zrealizowany nie odejmuje prowizji sprzedaży.** `realisedBy()` liczy
`proceeds = soldPortion.price().multiply(quantity)` — kwotę brutto. Sprzedaż 1 BTC po 80 000 z
prowizją 50 pokazuje wpływ 80 000, a nie 79 950.

**(c) Prowizja zapłacona w trzecim aktywie nie jest odejmowana ze stanu — w ogóle.**
`swing()` rusza dokładnie dwie nogi transakcji:

```java
reduceAsset(orderId, soldPortion);
increaseAsset(purchasedPortion);
```

Jeśli prowizję za `BTC/EUR` zapłacono w BNB, to **saldo BNB w portfelu jest po prostu nieprawdziwe**.
To największa z trzech luk i najmniej widoczna, bo nie objawia się złym zyskiem, a złym stanem.

## 3. Przeszkoda: agregat celowo nie liczy na walutach

W `realisedBy()` jest już komentarz, który rozstrzyga połowę tego zadania:

> *„Recorded, not subtracted: the cost may be in another currency than the sale, and the aggregate
> has no rates. `Money.minus` would not object — it keeps the left currency and subtracts the
> amounts — so the wrong answer would look like a right one."*

Ta sama obiekcja dotyczy prowizji. `Portfolio` nie ma dostępu do `QuotationApi` i mieć nie powinien —
denominacja dzieje się po stronie czytania (zadania B4 i C17). Więc **„prowizja wchodzi do kosztu"
nie może znaczyć „agregat dodaje `Money` do `Money`"**, dopóki waluty mogą się różnić.

## 4. Dwa warianty

**A — składanie w agregacie, tylko dla zgodnych walut.** Prowizję w walucie rozliczenia dodać do
kosztu; prowizję w innym aktywie zapisać osobno i odjąć ze stanu jako trzecią nogę.
*Wada:* dwa tryby w agregacie i cicha asymetria — ten sam trade liczony inaczej w zależności od tego,
czym zapłacono prowizję.

**B — zapisać, nie składać (rekomendowany).** Prowizja jedzie jako własne pole do `CostBasis` i
`RealisedResult`, bez dodawania. Arytmetykę robi `PortfolioSummaryMapper`, który już ma
`QuotationApi` i już robi tam denominację. Agregat zostaje czysty, a odpowiedź zyskuje możliwość
pokazania kosztu **brutto i netto** osobno.
*Zgodne z tym, co kod robi dziś dla `cost` kontra `proceeds` i z regułą, że przeliczanie należy do
strony czytającej.*

Luka **(c)** jest niezależna od wyboru: prowizja w posiadanym aktywie musi zmniejszyć jego ilość i to
jest zadanie agregatu, nie mappera.

## 5. Do rozstrzygnięcia przed implementacją

1. **Wariant A czy B.** Rekomendacja: B.
2. **Czy `exchangeCurrencyFee` i `transactionFee` jadą osobno, czy tylko `totalFee`.** Osobno
   pozwala pokazać, co poszło w tokenie giełdy; `totalFee` jest już policzone i może sumować waluty,
   których nie wolno sumować — **sprawdzić, czy `totalFee` nie jest już dziś błędne**, bo
   `MakeTradeCommandHandler` robi `exchangeCurrencyFee.plus(transactionFee)`, a `Money.plus`
   zachowuje lewą walutę.
3. **Czy zmiana jest retroaktywna.** `PortfolioEvents.TradeProcessedEvent` jest **persystowane**;
   stare zdarzenia nie mają prowizji. Nowe pole musi być nullowalne, a pozycje policzone przed
   zmianą zostaną z kosztem bez prowizji — czyli albo żyjemy z mieszanką, albo przeliczamy. Projekt
   nie ma produkcyjnych użytkowników, więc najprościej wyczyścić dane.

## 6. Ścieżka techniczna

```
TradeConfirmedEvent          + fee          (vidulum-trading-api)
   ↓
ProcessTradeCommand          + fee          (portfolio/app/commands/update)
   ↓
ExecutedTrade                + fee          (portfolio/domain/trades)
   ↓
PortfolioEvents.TradeProcessedEvent + fee   ← PERSYSTOWANE, pole nullowalne
   ↓
calculatePurchasedPortionOfAsset / swing()  ← luka (c): trzecia noga
CostBasis / RealisedResult   + fee          ← wariant B
   ↓
PortfolioSummaryMapper                      ← arytmetyka, bo tu jest QuotationApi
```

Emisja: `FillOrderCommandHandler` ma dostęp do `command` fillu, ale **nie do prowizji** — ta jest w
`MakeTradeCommand` i w zapisanym `Trade`. Czyli obie gałęzie routingu muszą wziąć prowizję z trade'u,
nie z komendy: `TradeCapturedEvent` też potrzebuje pola, albo `FillOrderCommandHandler` musi czytać
trade z repozytorium.

## 7. Zasięg

- **23 pliki testowe** asertują `costBasis`, `unrealisedProfit` albo `realisedResult`
  (`PortfolioCostBasisTest`, `RealisedResultComponentTest`, `CostReconciliationTest`,
  `PortfolioSummaryMapperTest`, `StatedCostComponentTest`, `PortfolioSplitPositionsTest`,
  `WealthChangeComponentTest`, cały `portfolio_spec`, `TradingPortfolioIntegrationTest`,
  `LockingAssetsTests`…). Część liczb **ma** się zmienić — to właściwe zachowanie, nie regresja, i
  każda zmieniona liczba wymaga sprawdzenia ręcznie, a nie przeklejenia tego, co wyszło.
- Zdarzenie persystowane → patrz punkt 5.3.
- Scope buildu: `vidulum-trading-api`, potem `vidulum-trading`, `vidulum-portfolio`,
  `vidulum-pnl`, `vidulum-wealth-tests` i `vidulum-app`. Cashflow nietknięty.

## 8. Jak sprawdzić, że zrobione

1. Kupno 1 BTC po 70 000 z prowizją 50 w EUR → `costBasis.avgPrice` = 70 050, a saldo EUR spadło o
   70 050.
2. Sprzedaż tego samego z prowizją 50 → `RealisedResult.proceeds` netto 79 950.
3. Prowizja 0,1 BNB przy `BTC/EUR` → ilość BNB w portfelu spadła o 0,1.
4. `grep -c fee` w pakiecie `portfolio` przestaje być zerem.
5. Trade bez prowizji (import ręczny, `fee` null) liczy się jak dziś.

## 9. Powiązania

- `2026-10-12-module-contracts-api-impl-split.md` — krok **05d**, oraz sekcja 21 („Trzy decyzje
  podjęte po 05a") z uzasadnieniem decyzji
- Zadania **B4** i **C17** — reguła, że denominacja należy do strony czytającej; wariant B z niej
  wynika
- Zadanie **C8** — koszt podany przez właściciela (`USER_PROVIDED`); do rozstrzygnięcia, czy podana
  ręcznie kwota jest brutto czy netto
