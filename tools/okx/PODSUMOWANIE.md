# Integracja OKX — podsumowanie i wnioski

Dokument zamykający fazę prototypu (2026-09-07 … 2026-09-17). Powstał, żeby nie trzeba było
wracać do historii rozmów: zbiera **ustalenia, wnioski i otwarte pytania** w jednym miejscu.

Podział dokumentów w tym katalogu:

| plik | rola | język |
|---|---|---|
| `README.md` | jak uruchomić narzędzia | angielski |
| `OKX-CONTEXT.md` | fakty o API i docelowy projekt backendu | angielski |
| `IMPROVEMENTS.md` | 34 poprawki: co było źle i jak naprawione | polski |
| **`PODSUMOWANIE.md`** | **wnioski, stan wiedzy, co dalej** | **polski** |

---

## 1. Słownik — pojęcia używane w tych dokumentach

**payload** (w rozmowach też „ładunek") — treść komunikatu, który przychodzi z OKX. Jeden push
kanału `orders` to obiekt JSON z 71 polami opisującymi stan zlecenia. Linia w logu jest tylko
widokiem na payload, nie osobnym źródłem informacji.

**koperta** (envelope) — pola komunikatu leżące **obok** `data`, nie w środku. Kanał `account`
trzyma tam `eventType`, `curPage` i `lastPage`. Czytanie ich na złym poziomie zwraca `undefined`
bez błędu — to był błąd U28.

**watermark** (znacznik postępu) — zapamiętany czas ostatniego przetworzonego zdarzenia. Po
restarcie listener pyta OKX „co się działo po tym czasie". Bez niego start zaczyna od zera i nie ma
jak wykryć luki, bo kanały prywatne nie niosą numerów sekwencyjnych. Trzymany w
`.okx-listener-state.json`.

**protection** (nasza nazwa, nie OKX-owa) — zbiorcze określenie na take profit, stop loss i trailing
stop doczepione do zlecenia. OKX nie ma dla nich wspólnej nazwy; rozrzuca je po `attachAlgoOrds`,
polach górnego poziomu i `linkedAlgoOrd`.

**snapshot vs event_update** — dwa kształty pushu kanału `account`. Snapshot niesie wszystkie waluty
z niezerowym saldem, event_update tylko te dotknięte zdarzeniem. Waluta, która spadła do zera,
przestaje być wysyłana — dlatego snapshot musi **zastępować** lokalną mapę, a nie ją uzupełniać.

---

## 2. Co zostało ustalone — i jak

Wszystko poniżej jest zweryfikowane na żywym koncie demo (region EEA), nie wywnioskowane.

### Kanały WebSocket

Zmapowane empirycznie przez subskrypcję każdej nazwy na obu endpointach:

| kanał | endpoint | status |
|---|---|---|
| `orders`, `account`, `balance_and_position` | `/private` | używane |
| `positions`, `account-greeks`, `liquidation-warning` | `/private` | przyjmowane, nietestowane |
| **`deposit-info`, `withdrawal-info`** | **`/business`** | przyjmowane — **nie `/private`**, wbrew temu, co sugeruje dokumentacja |
| `fills`, `grid-orders-*` | `/business` | odrzucane na demo (`60018`) |
| `asset`, `funding`, `funding-balance`, `balance`, `account-balance` | oba | **nie istnieją** |

### Semantyka danych

- **Kanał `orders` wysyła pełny stan zlecenia, nigdy delty.** Dziewięć ramek obejmujących
  utworzenie, zmianę ceny, doczepienie SL, zmianę SL, dołożenie TP, anulowania i wykonanie —
  każda z identycznym zestawem 71 kluczy. Pustka to zawsze `""`; klucz nigdy nie znika.
- **Ramka WS jest bogatsza niż REST** — 71 pól wobec 54 w `orders-pending`. Osiemnaście pól
  (szczegóły wykonania, `amendResult`, `reqId`) istnieje tylko na WebSockecie.
- **`cancelSourceReason` przychodzi wyłącznie REST-em.** Po WS dostajesz sam kod `cancelSource`.
  Dokumentacja podaje mapowanie 30 kodów — jest w kontrakcie, więc powód renderujemy sami.
- **`uTime` nie jest podbijany przy zmianie doczepionego TP/SL.** Trzy kolejne pushe niosły czas
  utworzenia. Zmiana ceny i anulowanie podbijają. Nigdy nie traktuj `uTime` jako „czasu tego pushu".
- **`trades` w `balance_and_position` to jedyny pewny łącznik** między zmianą salda a wykonaniem,
  które ją spowodowało. Kolejność kanałów nie jest gwarantowana — przy jednym zdarzeniu `account`
  wyprzedził `orders` mimo wcześniejszego znacznika — więc korelacja po czasie jest zawodna.
- **OKX powtarza komunikaty.** Dokumentacja podaje reguły odsiewania: `tradeId` raz na instrument,
  stan terminalny raz na zlecenie, `reqId` raz na zmianę. Bez nich wykonanie księguje się dwa razy.
- **Brak numerów sekwencyjnych.** 95 różnych pól w 48 ramkach — nic w rodzaju `seq`/`nonce`.
  Utraconego komunikatu **nie da się wykryć**; jedyna obrona to uzgodnienie REST-em.

### Pola nieudokumentowane, a przychodzące

`slippage` (kanał `orders`), `autoLendAmt` i `autoStakingStatus` (`account.details`). Kontrakt
oznacza je jawnie; testy pilnują, żeby lista się nie rozjechała.

### Uprawnienia klucza — trzy osobne bramki

| operacja | wymaga | błąd przy braku |
|---|---|---|
| odczyt | `read_only` | — |
| składanie zleceń | `trade` **plus** włączona kategoria produktu (konta EEA) | `50120`, potem `50123` |
| przelew między własnymi kontami | `withdraw` | `50120` |

---

## 3. Wniosek główny — jak odwzorować konto w Vidulum

**Sam WebSocket nie wystarczy.** Konto dzieli się na dwie części o różnym pokryciu:

```
Trading  →  pełne pokrycie WS (account, orders, balance_and_position)
Funding  →  ZERO pokrycia WS — wyłącznie REST /api/v5/asset/balances
```

Potwierdzone eksperymentem: przelew 10 USDC Trading→Funding dał `balance_and_position` z
`eventType=transferred` i `account` z `event_update` — **oba pokazały wyłącznie stronę Trading**
(5000 → 4990). Dziesięciu USDC, które wylądowały na Funding, nie ma w żadnym pushu. REST w tym
samym czasie pokazywał `0 → 10 → 0`.

Stąd reguła dla backendu:

> Powiadomienie o przelewie lub wpłacie znaczy **„odśwież Funding"**, a nie „Funding wynosi teraz X".

Architektura, która z tego wynika:

1. **WS jako wyzwalacz i źródło stanu Trading** — `orders`, `account`, `balance_and_position`
   na `/private`, `deposit-info` i `withdrawal-info` na `/business`.
2. **REST jako źródło Funding i jako uzgodnienie** — `/asset/balances` przy każdym zdarzeniu
   nie-snapshot, plus okresowo jako siatka bezpieczeństwa.
3. **Watermark trwały** — po każdym logowaniu dociągnąć zlecenia otwarte, zlecenia zamknięte,
   fille, wpłaty i wypłaty od ostatniego znacznika.
4. **Deduplikacja wg reguł OKX** — bez niej powtórzony komunikat księguje się drugi raz.
5. **`updateInterval: 0`** na kanale `account`, jeśli nie potrzebujesz przeszacowań wyceny —
   zmierzone 6 pushów na 25 s spada do 1.

---

## 4. Czego nie wiemy

| luka | dlaczego | jak zamknąć |
|---|---|---|
| kształt payloadu `deposit-info` / `withdrawal-info` | demo nie przyjmuje wpłat zewnętrznych | konto live albo wklejona sekcja dokumentacji |
| `posData` — 15 z 15 pól bez próbki | konto spot nie ma pozycji | konto z kontraktami |
| 27 z 71 pól `orders` bez wartości | opcje, dźwignia, tryby marżowe nieużywane | jw. |
| częściowe wykonanie, `fills` (VIP5+), kanał `positions` | nie zaszły / brak poziomu VIP | konto live |
| pełna choreografia reconnectu | rozłączenie musi przyjść z zewnątrz | test z odciętą siecią |

Nigdy nie uruchomiliśmy niczego na koncie **live** — `npm run check:prod` (same odczyty) byłby
pierwszym sensownym krokiem.

---

## 5. Stan prototypu

34 pozycje backlogu wdrożone, 70 asercji przechodzi bez sieci i bez poświadczeń
(`npm test`). Kontrakty obejmują 167 pól trzech kanałów, generowane z nagranych ramek, więc nie
mogą rozjechać się z rzeczywistością — `npm run contract:generate`.

Narzędzia w repo są **wyłącznie do odczytu**: klient REST ma tylko `get()` i `paginate()`, nie ma
metody POST. Skrypty, które w trakcie testów składały zlecenia i robiły przelewy, celowo nigdy nie
trafiły do repozytorium.

Cztery lekcje, które powtórzyły się na tyle razy, że warto je zapisać:

1. **Kod obsługiwał to, co ktoś zgadł, że przyjdzie.** Take profit ginął przez zgadnięte pola,
   stop loss przez zgadnięte miejsce, 27 pól przez ręczną listę. Za każdym razem naprawa polegała
   na zastąpieniu zgadywania obserwacją — stąd generyczny diff i generowany kontrakt.
2. **Ciche pominięcie jest gorsze od błędu.** Nieznany typ zdarzenia wpadał w pustą pętlę;
   po poprawce pierwsze uruchomienie ujawniło realne komunikaty `channel-conn-count`.
3. **Testy na zmyślonych danych potrafią poświadczać fikcję.** Nasz test sprawdzał renderowanie
   `cancelSourceReason`, którego listener nigdy nie dostanie — bo sam wpisałem to pole do
   syntetycznego payloadu.
4. **Dokumentacja dała to, czego żadna liczba przebiegów nie da.** Reguły deduplikacji, semantyka
   snapshotu i `updateInterval` opisują zdarzenia, które na koncie demo po prostu nie zaszły.

---

## 6. Co dalej

Prototyp odpowiedział na pytania, które musiał znać moduł backendowy. Dalszy ciąg to
implementacja `okx` w Spring Boocie wg planu z `OKX-CONTEXT.md` (sekcja „Target design"):
konfiguracja per użytkownik, job synchronizacji do MongoDB z deduplikacją po
`billId`/`tradeId`/`depId`/`wdId`, osobny job archiwum kwartalnego, jedno połączenie WS na
użytkownika z publikacją zdarzeń na Kafkę.

Fixtures w `fixtures/orders-lifecycle.json` to gotowy materiał na testy tego modułu — surowe ramki
z giełdy, w tym warianty, których na demo nie da się wywołać powtórnie.
