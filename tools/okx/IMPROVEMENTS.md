# Usprawnienia — `tools/okx`

**Status: wszystkie 22 pozycje wdrożone (2026-09-12).**

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

## Rzeczy, które były w porządku od początku

- Uprawnienia `.env.demo` / `.env.prod` to `600`.
- `.gitignore` zasłania `tools/okx/.env.*` i `tools/okx/*.json`, z wyjątkami na `.env.example` i `package.json`.
- `package.json` wymusza `engines.node >= 22`.
- `ordId` przychodzi z OKX jako **string**, więc `JSON.parse` nie traci precyzji — mimo że wartości
  przekraczają `2^53`. Uwaga mimo to: każde `Number(ordId)` je uszkodzi, trzymaj jako string.
- Sesja testowa: zero reconnectów, zero `notice`/`64008` przez ~10,5 min.
