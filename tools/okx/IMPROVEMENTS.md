# Usprawnienia — `tools/okx`

**Status: wszystkie 26 pozycji wdrożonych (2026-09-12).**

Lista powstała 2026-09-11 na podstawie analizy ~10-minutowej sesji `npm run ws:demo`
(2026-09-08 23:17–23:27 UTC) oraz weryfikacji read-only przez REST i surowe ramki WS.
Ten plik jest teraz zapisem *dlaczego* kod wygląda tak, jak wygląda — diagnozy zostawiono,
bo bez nich część poprawek wygląda na kosmetykę, a nią nie jest.

Objęte pliki: `okx-readonly-export.mjs`, `okx-ws-listener.mjs` oraz nowy `okx-common.mjs`
(wspólny moduł powstały przy U20).

## Przegląd

| # | Obszar | Usprawnienie | Waga | Status |
|---|--------|--------------|------|--------|
| **U1** | listener | REST-catchup (`orders-pending`) po każdym logowaniu | krytyczna | wdrożone |
| **U2** | listener | Scalanie przyrostowych pushy `account` w lokalny snapshot | krytyczna | wdrożone |
| **U3** | listener | Pokazywać `availBal`/`frozenBal`, nie tylko `cashBal` | krytyczna | wdrożone |
| **U4** | listener | `bal&pos`: użyć `pTime` zamiast `Date.now()` | krytyczna | wdrożone |
| **U5** | listener | Przerwać reconnect przy błędzie autoryzacji | wysoka | wdrożone |
| **U6** | listener | `try/catch` wokół `JSON.parse` | wysoka | wdrożone |
| **U7** | listener | Nie ignorować cicho nieznanych typów `event` | wysoka | wdrożone |
| **U8** | listener | Liveness oparty faktycznie na `pong`, nie na dowolnym ruchu | wysoka | wdrożone |
| **U9** | listener | Guard na Node < 22 z czytelnym komunikatem | średnia | wdrożone |
| **U10** | listener | Graceful shutdown na SIGINT/SIGTERM | średnia | wdrożone |
| **U11** | listener | Jitter w backoffie reconnectu | średnia | wdrożone |
| **U12** | eksport | Dociągnąć zlecenia (`orders-pending` + historia) | wysoka | wdrożone |
| **U13** | eksport | Dociągnąć pozycje (`account/positions`) | średnia | wdrożone |
| **U14** | eksport | Ostrzeżenie, gdy `--from` wykracza poza okno 3 mies. | wysoka | wdrożone |
| **U15** | eksport | Zabezpieczenie paginacji przed pętlą na równych `ts` | średnia | wdrożone |
| **U16** | listener | Persystencja zdarzeń do JSONL | średnia | wdrożone (bez rotacji) |
| **U17** | oba | `--help` / `usage()` | średnia | wdrożone |
| **U18** | oba | Odradzić sekrety w argv (widoczne w `ps`) | średnia | wdrożone (usunięte) |
| **U19** | listener | `--quiet`: tłumić pushe `account` bez zmiany sald | niska | wdrożone |
| **U20** | oba | Wspólny moduł: podpis HMAC, profile, regiony | niska | wdrożone |
| **U21** | npm | `check:prod` padnie — brak `OKX_DOMAIN` w `.env.prod` | wysoka | wdrożone |
| **U22** | npm | `check:*` nie zadziała na Windows `cmd` | niska | wdrożone (`--days`) |
| **U23** | listener | Log zleceń gubił `px`, `ordType` i doczepiony TP/SL | wysoka | wdrożone |
| **U24** | oba | Stop loss w starym stylu, trailing stop i odrzucone algo pomijane | wysoka | wdrożone |
| **U25** | listener | Diff zleceń po ręcznej liście pól — 27 z 54 zmieniało się po cichu | wysoka | wdrożone |
| **U26** | oba | Brak kontraktu pól i testu — fixtures były zmyślone | wysoka | wdrożone |

---

## Krytyczne — te sprawiały, że dane były po prostu nieprawdziwe

### U1 · Brak snapshotu zleceń po (re)connect
Kanał `orders` wysyła wyłącznie zmiany **po** zalogowaniu. Zweryfikowane: REST `orders-pending`
pokazał trzy żywe zlecenia, listener widział dwa — trzecie (`3905337054835232768`,
sell 0.25 BTC-EUR @ 80000) złożono przed startem sesji i nigdy nie pojawiło się w logu.
Każdy reconnect zostawiał analogiczną dziurę.

**Wdrożono:** funkcja `reconcileOrders()` wołana po każdym udanym `login`. Zawsze pobiera
`orders-pending`; przy reconnekcie dodatkowo `orders-history` per `instType`, filtrowane po
`uTime >= state.lastEventTs` (watermark najnowszego widzianego zdarzenia). Nieudany catch-up
nie zabija listenera, ale loguje `catch-up FAILED, order state may be incomplete` — cicha
porażka byłaby groźniejsza niż brak funkcji. Wyłącznik: `--no-catchup`.

### U2 · Pushe `account` są przyrostowe, a kod traktował je jak pełny stan
Gdy zdarzenie dotyczy jednej waluty, `details[]` zawiera tylko ją, przy globalnym `totalEq`.
Stąd mylące linie w pierwotnym logu:

```
[account] 23:19:40.244Z totalEq=167077     BTC=1
[account] 23:21:36.720Z totalEq=167050.87  EUR=4600
```

To nie było zniknięcie pięciu walut.

**Wdrożono:** `state.balances` (Map po `ccy`) i `mergeBalances()`, które scala przyrost i zwraca
listę faktycznych zmian. Mapa jest czyszczona przy każdym logowaniu, bo po reconnekcie OKX
wysyła pełny snapshot — inaczej zostałyby w niej waluty z poprzedniej sesji.

### U3 · Log nie pokazywał pola, które faktycznie się zmienia
Handler drukował `cashBal`, które przy składaniu zlecenia jest stałe. Przechwycona surowa ramka:

```
BTC: cashBal=1     availBal=0.675     frozenBal=0.325
EUR: cashBal=4600  availBal=4400.0002 frozenBal=199.9998
```

`0.325 BTC = 0.075 + 0.25` (dwa sell), `199.9998 EUR ~= 0.00307692 x 65000` (buy).

**Wdrożono:** log pokazuje `ccy=cash(avail=... frozen=...)` dla całego scalonego stanu plus
osobne linie `-> CCY cash/avail/frozen: stare -> nowe` dla tego, co się zmieniło.

### U4 · `bal&pos` stemplował czas lokalnie
Ramka ma klucze `balData, eventType, pTime, posData, trades` — **bez** `uTime` i `ts`.
Wyrażenie `d.uTime ?? d.ts ?? Date.now()` cicho spadało do czasu odbioru, nie czasu serwera.
Dlatego `[bal&pos] 23:17:18.783` wypisało się *po* `[account] 23:17:18.847`, a miało wcześniejszy
stempel.

**Wdrożono:** `eventTime()` z łańcuchem `uTime ?? pTime ?? ts`. Zejście do czasu lokalnego jest
teraz jawne — sufiks `(local)` w logu. Przy okazji funkcja aktualizuje `state.lastEventTs`,
czyli watermark używany przez U1.

---

## Wysokie — odporność i kompletność

### U5 · Nieskończona pętla przy złych poświadczeniach
Ścieżka: `login failed` → `ws.close()` → `onclose` → reconnect po 1 s → `onopen`
resetuje `backoff` do 1000 → `login failed`... Przy błędnym kluczu skrypt biłby w OKX raz
na sekundę bez końca, co grozi rate-limitem albo blokadą IP.

**Wdrożono:** `backoff = 1000` przeniesione z `onopen` do gałęzi udanego logowania, więc
narastanie faktycznie działa. Kody z `FATAL_AUTH_CODES` (`50105`, `50111`, `50113`, `50119`,
`60009`, `60032`) kończą proces z podpowiedzią o regionie i cytowaniu passphrase w `.env`.

### U6 · `JSON.parse` bez `try/catch`
Jedna nieparsowalna ramka wywalała cały proces, w tym drugie połączenie.
**Wdrożono:** `try/catch`, przy błędzie log obciętej surowej ramki (200 znaków) i `return`.

### U7 · Nieznane `event` znikały bez śladu
Obsłużone były `login`, `subscribe`, `error`, `notice`; wszystko inne trafiało do
`for (const item of msg.data ?? [])`, gdzie `data` jest `undefined` → pusta pętla.

**Wdrożono:** catch-all `if (msg.event || msg.op)` z ostrzeżeniem. Poprawka opłaciła się
natychmiast — pierwszy test ujawnił realne komunikaty, które wcześniej ginęły:
`{"event":"channel-conn-count","channel":"orders","connCount":"2",...}`.

### U8 · Detektor martwego łącza był zamaskowany
`resetPongTimeout()` wołał *każdy* przychodzący komunikat, a `account` leci co 5 s, więc
50-sekundowy timeout nie mógł zadziałać. Padnięcie samej subskrypcji przy żywym sockecie
było niewykrywalne.

**Wdrożono:** reset wyłącznie w gałęzi `data === "pong"`.

### U12 · Eksport pomijał zlecenia
Zbierał salda, wpłaty, wypłaty, fills i bills — ale nie `orders-pending` ani historii zleceń.
Trzy żywe zlecenia (0.325 BTC i 200 EUR zablokowane) nie trafiłyby do `prod.json`.

**Wdrożono:** `result.openOrders` (bez filtra czasu — żywe zlecenie sprzed `--from` nadal
blokuje środki dziś), `result.orders` z `orders-history-archive` per `instType` filtrowane po
`cTime`, oraz liczniki w podsumowaniu. Flaga `--skip-orders` pomija tę sekcję.

### U14 · Ciche obcięcie okna czasu
`--from 2025-01-01` przechodziło bez słowa, choć `fills-history` i `bills-archive` sięgają
3 miesiące, a `asset/bills` miesiąc. Efekt: niepełne dane wyglądające na kompletne.

**Wdrożono:** ostrzeżenie na stderr wyliczające, których zbiorów dotyczy obcięcie, z odesłaniem
do archiwum kwartalnego opisanego w `OKX-CONTEXT.md`.

### U21 · `check:prod` nie działał
`.env.prod` nie miał `OKX_DOMAIN`, więc trafiał na `openapi.okx.com` i zwracał `60032`
(konto jest w regionie EEA). **Wdrożono:** dopisano `OKX_DOMAIN=eea.okx.com` do `.env.prod`.

---

## Średnie

- **U9** — guard `typeof WebSocket === "undefined"` z instrukcją dla Node 18/20 zamiast gołego `WebSocket is not defined`.
- **U10** — połączenia rejestrowane w tablicy `connections`; `shutdown()` na `SIGINT`/`SIGTERM` czyści timery i domyka sockety. Flaga `stopping` blokuje reconnect w trakcie zamykania.
- **U11** — opóźnienie reconnectu to `backoff + random(0..30% backoff)`, więc oba endpointy nie wracają w tym samym rytmie.
- **U13** — `result.positions` z `/api/v5/account/positions`, spłaszczone do pól istotnych dla wyceny.
- **U15** — `paginate()` ma limit `maxPages` (domyślnie 200) i wykrywa kursor, który przestał się przesuwać; oba przypadki kończą pętlę z ostrzeżeniem zamiast wisieć.
- **U16** — `--out-events plik.jsonl`, jedna linia JSON na zdarzenie (`receivedAt`, `channel`, `data`). Świadomie **bez rotacji** — do dodania, gdy listener ma chodzić długoterminowo.
- **U17** — `usage()` w obu skryptach, wywoływane **przed** sprawdzeniem poświadczeń. Wcześniej `--help` w eksporcie odpalał pełny eksport.
- **U18** — `--key/--secret/--passphrase` **usunięte całkowicie**. Sekrety wyłącznie ze zmiennych środowiskowych; komunikat o braku poświadczeń mówi wprost, że argumenty nie są obsługiwane, i przypomina o cytowaniu passphrase ze znakiem `#`.

## Niskie

- **U19** — `--quiet` pomija pushe `account`, w których `mergeBalances()` nie wykrył zmiany. W teście: 1 linia zamiast 5 w porównywalnym czasie.
- **U20** — `okx-common.mjs`: `parseArgs`, `maybePrintHelp`, `PROFILES`/`resolveProfile`, `WS_HOSTS`, `INST_TYPES`, `FATAL_AUTH_CODES` oraz podpisany klient REST z `get()` i `paginate()`. To był warunek wykonalności U1 — listener nie miał wcześniej żadnego klienta REST.
- **U22** — zamiast naprawiać `$(node -p ...)` w npm, eksport dostał flagę `--days N`, a `check:*` używa `--days 1`. Podstawienie powłoki zniknęło, więc problem z Windows też.

---

## Znalezione po wdrożeniu

### U23 · Log zleceń gubił cenę, typ i doczepiony take profit
Sesja z 2026-09-12 (modyfikacja zlecenia, a potem dodanie do niego TP) pokazała trzy pushe
`orders` dla `ordId=3916792731562668033`, z czego **dwa ostatnie były w logu nie do odróżnienia**:

```
[orders] 16:53:35.157Z BTC-EUR buy state=live filled=0/0.004 avgPx=0 ordId=3916792731562668033
[orders] 16:53:35.157Z BTC-EUR buy state=live filled=0/0.004 avgPx=0 ordId=3916792731562668033
```

Drugi z nich to było dodanie take profitu. Trzy przyczyny:

1. **`px` nie było drukowane** — amend zmieniający wyłącznie cenę dawał dwie identyczne linie.
2. **`ordType` nie było drukowane** — nie dało się odróżnić `limit` od `market` czy `post_only`.
3. **`attachAlgoOrds` nie było drukowane** — a OKX trzyma TP/SL *wewnątrz* zlecenia nadrzędnego,
   nie jako osobne zlecenie algo. Zweryfikowane REST-em: `orders-algo-pending` zwraca 0 rekordów
   dla wszystkich `ordType`, a samo zlecenie ma
   `attachAlgoOrds: [{attachAlgoId: 3916799439731159045, tpTriggerPx: "61000", tpOrdPx: "-1",
   tpTriggerPxType: "last", tpOrdKind: "condition"}]`.

**Wdrożono:** `formatOrder()` (wspólny dla kanału `orders` i catch-upu) dokłada `ordType`, `px`
oraz sekcję `[tp@... sl@...]` z `formatAttached()`; `tpOrdPx: "-1"` renderuje się jako `market`.
Do tego `diffOrder()` porównuje push z poprzednią wersją zlecenia trzymaną już wcześniej
w `state.orders` i wypisuje linie `-> pole: stare -> nowe`. Efekt na żywo:

```
[catchup/live] BTC-EUR buy limit state=live filled=0/0.004 px=60000 avgPx=- \
               ordId=3916792731562668033 [tp@61000(last)->market algoId=3916799439731159045]
```

**Uwaga o czasie:** OKX **nie** podbija `uTime` przy doczepianiu TP — `uTime` pozostało
`1789232015157` (16:53:35.157), czyli z momentu wcześniejszego amendu, mimo że TP dodano ~105 s
później. Znacznik w logu jest więc czasem ostatniej modyfikacji zlecenia, nie czasem tego pusha.
Dopiero linie z `diffOrder()` pokazują, że coś się realnie zmieniło.

**Nietknięte:** eksport zapisuje wiersze `orders-pending` surowe, bez mapowania, więc
`attachAlgoOrds` było i jest w `*.json` — luka dotyczyła wyłącznie logu listenera.

**Znane ograniczenie:** samodzielne zlecenia algo (TP/SL *nie* doczepione do zlecenia) żyją na
kanale `orders-algo` na endpoincie `/ws/v5/business` i domyślnie nie są subskrybowane.
Routing już je obsługuje — `--channels orders,orders-algo` wystarczy, gdy zajdzie potrzeba.

### U24 · Stop loss bywa w innym miejscu, niż czytaliśmy
U23 renderowało wyłącznie `attachAlgoOrds`. Zrzut wszystkich kluczy żywego zlecenia pokazał, że
OKX zwraca **dwa niezależne miejsca** na ochronę i oba są obecne w każdym zleceniu:

```
top-level:        tpTriggerPx, tpOrdPx, tpTriggerPxType,
                  slTriggerPx, slOrdPx, slTriggerPxType, isTpLimit
attachAlgoOrds[]: te same pola + activePx, callbackRatio, callbackSpread,
                  slTriggerRatio, tpTriggerRatio, percent, sz, failCode, failReason
```

W badanym zleceniu pola górnego poziomu były puste (TP siedział w `attachAlgoOrds`), ale zlecenie
utworzone w starszym stylu wypełnia właśnie je — i **stop loss zniknąłby z logu bez śladu**.
Dwie dodatkowe luki w tym samym miejscu: trailing stop przychodzi jako `callbackRatio` /
`callbackSpread`, a nie jako cena wyzwalania, więc renderował się jako pusty nawias; a odrzucone
zlecenie doczepione (`failCode` / `failReason`) wyglądało jak działająca ochrona.

**Wdrożono:** `formatProtection()` w `okx-common.mjs` czyta `attachAlgoOrds`, a gdy tam nic nie ma
— pola górnego poziomu. Obsługuje trailing stop (`callbackRatio` renderowany jako procent),
`activePx`, częściowy rozmiar ochrony oraz `FAILED <kod> (powód)`. Pole `sz` jest czytane
**wyłącznie** z wpisów doczepionych: na zleceniu nadrzędnym `sz` to rozmiar zlecenia i zgłoszenie
go jako ochrony byłoby błędem. `linkedAlgoOrd.algoId` pomijane, gdy puste (OKX zwraca
`{"algoId":""}`, nie `null`).

Przy okazji `formatOrder()` i `diffOrder()` przeniesiono z listenera do `okx-common.mjs`, dzięki
czemu dają się testować bez uruchamiania nasłuchu.

**Weryfikacja:** dziesięć syntetycznych ładunków w kształcie OKX (puste pola dokładnie jak
w odpowiedzi API), pokrywających: brak ochrony, TP doczepiony, SL doczepiony, TP+SL w jednym
wpisie, SL w starym stylu, trailing stop, częściowy TP, odrzucone algo, dwa osobne wpisy,
powiązane zlecenie algo. Plus test, że rozmiar zlecenia nie wycieka jako ochrona, i że diff
wykrywa zarówno dodanie SL, jak i amend zmieniający wyłącznie cenę.

```
SL doczepiony                      [sl@58000(mark)->57900 algoId=999]
SL w starym stylu (top-level)      [sl@58000(last)->market]
trailing stop                      [trailing 5.00% active@62000 algoId=1002]
doczepiony algo ODRZUCONY          [sl@58000->market FAILED 51280 (price out of range) algoId=1004]
```

### U25 · Diff zleceń pomijał połowę ładunku
U23 wprowadziło `diffOrder()` z **ręcznie wybraną** listą siedmiu pól. To ten sam wzorzec, który
doprowadził do U23 i U24: cokolwiek spoza listy zmieniało się bez śladu w logu. Pomiar na żywym
ładunku: **54 pola w zleceniu, 7 porównywanych, 9 obsłużonych jako ochrona — 27 niczyich.**

Wśród nich rzeczy wprost potrzebne do śledzenia losu zlecenia:

| Pole | Czego dotyczy |
|---|---|
| `cancelSource`, `cancelSourceReason` | **dlaczego** zlecenie zostało anulowane |
| `fillPx`, `fillSz`, `tradeId` | konkretne wykonanie, nie tylko narastające `accFillSz` |
| `fee`, `feeCcy`, `rebate`, `rebateCcy` | koszt transakcji |
| `pnl`, `lever`, `source`, `outcome` | wynik, dźwignia, źródło zlecenia |

Anulowanie samo w sobie było widoczne (`state: live -> canceled`), ale bez powodu — nie dało się
odróżnić anulowania przez użytkownika od odrzucenia przez giełdę z braku depozytu.

**Wdrożono:** `diffOrder()` porównuje **wszystkie pola skalarne** ładunku. Zamiast listy tego, co
pokazujemy, jest krótka lista `ORDER_DIFF_IGNORE` tego, co pomijamy — tożsamość zlecenia (`ordId`,
`instId`, `cTime`…), `uTime` oraz struktury ochrony renderowane osobno przez `formatProtection()`.
Nowe pole w API OKX pojawi się w logu samo, bez zmiany w kodzie. Kolejność wyników porządkuje
`ORDER_DIFF_PRIORITY` (stan, powód anulowania, cena, rozmiar, wykonanie, opłata), reszta
alfabetycznie.

**Potwierdzone na żywym koncie (2026-09-12):** klucz z uprawnieniem `trade` na koncie demo pozwolił
przejść pełny cykl życia zlecenia. Dziewięć ramek kanału `orders` — utworzenie, zmiana samej ceny,
doczepienie SL, zmiana progu SL, dołożenie TP, anulowanie i wykonanie — **każda z identycznym
zestawem 71 kluczy**. OKX wysyła pełny stan, a pustkę zapisuje jako `""`. Generyczny diff wyłapał
przy okazji dziesięć pól, których nikt by nie przewidział: `amendResult`, `amendSource`,
`notionalUsd`, `lastPx`, `execType`, `fillFee`, `fillFeeCcy`, `fillIdxPx`, `fillNotionalUsd`,
`fillTime`. Przy ręcznej liście pól żadne z nich by się nie pojawiło.

**Luka ujawniona przy okazji:** `cancelSourceReason` **nie przychodzi po WebSockecie** — jest tylko
w REST. Log pokaże `cancelSource: - -> 1`, ale nigdy tekstowego powodu. Żeby go mieć, trzeba dociągnąć
zlecenie REST-em po anulowaniu.

**Odporność na ładunek częściowy:** diff iteruje po kluczach **przychodzącego** pusha, nie po sumie
kluczy obu wersji. OKX wysyła pełny stan zlecenia przy każdej aktualizacji i zapisuje pustkę jako
`""`, nigdy przez pominięcie klucza — dowód z logu sesji: push doczepiający take profit niósł
`instId`, `side`, `state`, `sz`, `accFillSz` i `avgPx`, mimo że żadne z nich się nie zmieniło.
Gdyby jednak kiedyś przyszedł ładunek częściowy, suma kluczy zamieniłaby jedną zmianę ceny w osiem
linii, z czego siedem fałszywych (`sz: 0.004 -> -`). Iterowanie po kluczach pusha daje poprawny
wynik przy obu semantykach.

Przy okazji **zrzut stanu przy zamykaniu**: `shutdown()` wypisuje ostatni znany stan każdego
zlecenia widzianego w sesji, kluczowany po `ordId` z OKX — bez odtwarzania logu od początku.

**Weryfikacja:** osiem scenariuszy cyklu życia na syntetycznych ładunkach — dodanie stop lossa,
anulowanie przez użytkownika, anulowanie przez giełdę, wykonanie częściowe, wykonanie pełne,
zmiana dźwigni, amend samej ceny oraz push bez żadnej zmiany (ma dać pustkę). Plus regresja na
żywym koncie: zrzut przy zamykaniu pokazał cztery zlecenia z zachowanym take profitem.

```
ANULOWANIE PRZEZ GIELDE
   -> state: live -> canceled
   -> cancelSource: - -> 33
   -> cancelSourceReason: - -> insufficient margin

CZESCIOWE WYKONANIE
   -> state: live -> partially_filled
   -> accFillSz: 0 -> 0.001
   -> fillSz: - -> 0.001      -> fillPx: - -> 60000
   -> tradeId: - -> 77123     -> fee: 0 -> -0.06
```

### U26 · Kontrakt pól i test na prawdziwych danych
Testy opierały się na payloadach, które sam napisałem, zgadując kształt odpowiedzi OKX.
Porównanie z dziewięcioma prawdziwymi ramkami ujawniło dwie fikcje:

1. **`cancelSourceReason` nie istnieje w ramce WS** — był wyłącznie w REST. Test sprawdzał więc
   renderowanie tekstowego powodu anulowania, którego listener nigdy nie dostanie. Przechodził.
2. **`failCode`, `failReason`, `percent` są tylko w kształcie REST** — `attachAlgoOrds` ma 19 pól
   w REST i 16 po WebSockecie. Gałąź `FAILED` może odpalić się wyłącznie na danych z catch-upu.

Do tego skala: syntetyczne zlecenie miało 27 pól, prawdziwa ramka ma 71.

**Wdrożono:**
- `fixtures/orders-lifecycle.json` — 9 surowych ramek `orders` z sesji 2026-09-12 (utworzenie,
  zmiana ceny, doczepienie SL, zmiana progu SL, dołożenie TP, dwa anulowania, wykonanie) oraz
  2 ramki `balance_and_position`, w tym pierwsza w historii z `eventType=filled`.
- `okx-order-contract.mjs` — wszystkie 71 pól z grupą, przykładową wartością i opisem. Flaga
  `verified` odróżnia 44 pola zaobserwowane z realną wartością od 27, które były zawsze puste.
  `auditOrderPayload()` wykrywa pola spoza kontraktu — gdy OKX coś doda, dowiemy się, zamiast
  to zignorować. Lista pól jest generowana z ramek, więc nie może się rozjechać z rzeczywistością.
- `okx-common.test.mjs` + `npm test` — 31 asercji, bez sieci i bez poświadczeń, działa na świeżym
  klonie. Cztery warianty, których konto nie wyprodukowało (trailing stop, odrzucona ochrona,
  SL w starym stylu, ochrona częściowa) są **jawnie oznaczone jako syntetyczne**, żeby nikt nie
  wziął ich za dowód.

`fixtures/` nie wymagało zmian w `.gitignore` — reguła `tools/okx/*.json` nie przechodzi przez `/`.

---

## Decyzje podjęte przy wdrożeniu

1. **U1 — pełne uzgadnianie**, nie tylko stan w pamięci: `orders-pending` zawsze, `orders-history` po reconnekcie.
2. **U16 — bez rotacji** na tym etapie; sam zapis JSONL.
3. **U18 — usunięcie** argumentów z sekretami zamiast ostrzeżenia.
4. **U21 — `OKX_DOMAIN` dopisany** bezpośrednio do `.env.prod`.

Dwie decyzje spoza pierwotnej listy:

5. **Nowe sekcje eksportu są nieblokujące.** `openOrders`, `positions` i `orders` idą przez wrapper
   `section()`: błąd endpointu daje ostrzeżenie i `{error}` w JSON zamiast przerwania całego eksportu.
   Bez tego dołożenie trzech endpointów mogłoby zepsuć działający dotąd eksport.
6. **Nieudany catch-up nie zabija listenera, ale krzyczy** — patrz U1.

## Weryfikacja (konto demo, region EEA)

- `npm run check:demo` — 19 wywołań REST, `keyPermissions: read_only`, `openOrders: 3`
  (dokładnie te zlecenia, których wcześniej brakowało w eksporcie).
- Catch-up po logowaniu odnalazł wszystkie trzy żywe zlecenia, w tym `3905337054835232768`.
- `[account]` pokazuje scalony stan sześciu walut z `avail`/`frozen` oraz linie delty.
- U7 wyłapał realne `channel-conn-count` przy pierwszym uruchomieniu.
- `SIGINT` → `shutting down (1 connection(s))...`, proces kończy się czysto.
- `--help` działa bez zmiennych środowiskowych i nie wykonuje eksportu.
- Po U23 catch-up renderuje doczepiony take profit: `[tp@61000(last)->market algoId=3916799439731159045]`.

## Rzeczy, które były w porządku od początku

- Uprawnienia `.env.demo` / `.env.prod` to `600`.
- `.gitignore` zasłania `tools/okx/.env.*` i `tools/okx/*.json`, z wyjątkami na `.env.example` i `package.json`.
- `package.json` wymusza `engines.node >= 22`.
- `ordId` przychodzi z OKX jako **string**, więc `JSON.parse` nie traci precyzji — mimo że wartości
  przekraczają `2^53`. Uwaga mimo to: każde `Number(ordId)` je uszkodzi, trzymaj jako string.
- Sesja testowa: zero reconnectów, zero `notice`/`64008` przez ~10,5 min.
