# Usprawnienia — `tools/okx`

Lista możliwych usprawnień dla `okx-readonly-export-2.mjs` i `okx-ws-listener-3.mjs`.
Sporządzono: 2026-09-11, na podstawie analizy ~10-minutowej sesji `npm run ws:demo`
(2026-09-08 23:17–23:27 UTC) oraz weryfikacji read-only przez REST i surowe ramki WS.

Stan na dziś **bez zmian** — to backlog, nie changelog. Żaden `.mjs` nie został zmodyfikowany.

## Przegląd

| # | Obszar | Usprawnienie | Waga | Koszt |
|---|--------|--------------|------|-------|
| **U1** | listener | REST-catchup (`orders-pending`) po każdym logowaniu | krytyczna | S |
| **U2** | listener | Scalanie przyrostowych pushy `account` w lokalny snapshot | krytyczna | M |
| **U3** | listener | Pokazywać `availBal`/`frozenBal`, nie tylko `cashBal` | krytyczna | S |
| **U4** | listener | `bal&pos`: użyć `pTime` zamiast `Date.now()` | krytyczna | XS |
| **U5** | listener | Przerwać reconnect przy błędzie autoryzacji | wysoka | S |
| **U6** | listener | `try/catch` wokół `JSON.parse` | wysoka | XS |
| **U7** | listener | Nie ignorować cicho nieznanych typów `event` | wysoka | XS |
| **U8** | listener | Liveness oparty faktycznie na `pong`, nie na dowolnym ruchu | wysoka | S |
| **U9** | listener | Guard na Node < 22 z czytelnym komunikatem | średnia | XS |
| **U10** | listener | Graceful shutdown na SIGINT/SIGTERM | średnia | XS |
| **U11** | listener | Jitter w backoffie reconnectu | średnia | XS |
| **U12** | eksport | Dociągnąć zlecenia (`orders-pending` + `orders-history`) | wysoka | S |
| **U13** | eksport | Dociągnąć pozycje (`account/positions`) | średnia | S |
| **U14** | eksport | Ostrzeżenie, gdy `--from` wykracza poza okno 3 mies. | wysoka | XS |
| **U15** | eksport | Zabezpieczenie paginacji przed pętlą na równych `ts` | średnia | S |
| **U16** | listener | Persystencja zdarzeń do JSONL | średnia | S |
| **U17** | oba | `--help` / `usage()` | średnia | S |
| **U18** | oba | Odradzić sekrety w argv (widoczne w `ps`) | średnia | XS |
| **U19** | listener | `--quiet`: tłumić pushe `account` bez zmiany sald | niska | S |
| **U20** | oba | Wspólny moduł: podpis HMAC, profile, regiony | niska | M |
| **U21** | npm | `check:prod` padnie — brak `OKX_DOMAIN` w `.env.prod` | wysoka | XS |
| **U22** | npm | `check:*` nie zadziała na Windows `cmd` | niska | S |

---

## Krytyczne — te sprawiają, że dane są po prostu nieprawdziwe

### U1 · Brak snapshotu zleceń po (re)connect
Kanał `orders` wysyła wyłącznie zmiany **po** zalogowaniu. Zweryfikowane: REST `orders-pending`
pokazał trzy żywe zlecenia, listener widział dwa — trzecie (`3905337054835232768`,
sell 0.25 BTC-EUR @ 80000) złożono przed startem sesji i nigdy nie pojawiło się w logu.
Każdy reconnect zostawia analogiczną dziurę.

**Poprawka:** po każdym udanym `login` pobrać `orders-pending` i scalić ze stanem lokalnym.
Po dłuższej przerwie dodatkowo `orders-history` za okno rozłączenia.

### U2 · Pushe `account` są przyrostowe, a kod traktuje je jak pełny stan
Gdy zdarzenie dotyczy jednej waluty, `details[]` zawiera tylko ją, przy globalnym `totalEq`.
Stąd mylące linie w logu:

```
[account] 23:19:40.244Z totalEq=167077     BTC=1
[account] 23:21:36.720Z totalEq=167050.87  EUR=4600
```

To nie jest zniknięcie pięciu walut. **Poprawka:** trzymać lokalny snapshot i scalać
przyrosty, nigdy nie nadpisywać.

### U3 · Log nie pokazuje pola, które faktycznie się zmienia
Handler drukuje `cashBal`, które przy składaniu zlecenia jest stałe. Przechwycona surowa ramka:

```
BTC: cashBal=1     availBal=0.675     frozenBal=0.325
EUR: cashBal=4600  availBal=4400.0002 frozenBal=199.9998
```

`0.325 BTC = 0.075 + 0.25` (dwa sell), `199.9998 EUR ~= 0.00307692 x 65000` (buy).
Cała informacja o blokadzie środków jest niewidoczna — stąd pozornie bezsensowne `BTC=1`.

### U4 · `bal&pos` stempluje czas lokalnie
Ramka ma klucze `balData, eventType, pTime, posData, trades` — **bez** `uTime` i `ts`.
Wyrażenie `d.uTime ?? d.ts ?? Date.now()` cicho spada do czasu odbioru, nie czasu serwera.
Dlatego `[bal&pos] 23:17:18.783` wypisało się *po* `[account] 23:17:18.847`, a ma wcześniejszy
stempel. **Poprawka:** `d.pTime`.

---

## Wysokie — odporność i kompletność

### U5 · Nieskończona pętla przy złych poświadczeniach
Ścieżka: `login failed` -> `ws.close()` -> `onclose` -> reconnect po 1 s -> `onopen`
resetuje `backoff` do 1000 -> `login failed`... Przy błędnym kluczu skrypt bije w OKX raz
na sekundę bez końca, co grozi rate-limitem albo blokadą IP.

**Poprawka:** nie resetować backoffu przed udanym logowaniem; błędy typu „zły klucz/region"
(`60009`, `50119`, `60032`) powinny kończyć proces zamiast wyzwalać retry.

### U6 · `JSON.parse` bez `try/catch`
Jedna nieoczekiwana ramka wywala cały proces, w tym drugie połączenie.

### U7 · Nieznane `event` znikają bez śladu
Obsłużone są `login`, `subscribe`, `error`, `notice`; wszystko inne trafia do
`for (const item of msg.data ?? [])`, gdzie `data` jest `undefined` -> pusta pętla.
Komunikaty kontrolne OKX (m.in. o limitach połączeń) przepadają cicho.

### U8 · Detektor martwego łącza jest zamaskowany
`resetPongTimeout()` woła *każdy* przychodzący komunikat, a `account` leci co 5 s.
50-sekundowy timeout nigdy nie zadziała. Jeśli padnie sama subskrypcja przy żywym
sockecie — nie dowiesz się w ogóle. **Poprawka:** resetować timeout wyłącznie na `pong`.

### U12 · Eksport pomija zlecenia
Zbiera salda, wpłaty, wypłaty, fills i bills — ale nie `orders-pending` ani `orders-history`.
Konkretnie: trzy żywe zlecenia (0.325 BTC i 200 EUR zablokowane) nie trafiłyby do `prod.json`.
Dla „read-only zdjęcia konta" to istotna dziura.

### U14 · Ciche obcięcie okna czasu
`--from 2025-01-01` przejdzie bez słowa, ale `fills-history` i `bills-archive` sięgają
3 miesiące, a `asset/bills` miesiąc. Dostajesz niepełne dane wyglądające na kompletne.
Wystarczy ostrzeżenie na stderr.

### U21 · `check:prod` nie zadziała
`.env.prod` nie ma `OKX_DOMAIN`, więc poleci na `openapi.okx.com` i zwróci `60032`
(konto jest w regionie EEA). Trzeba dopisać `OKX_DOMAIN=eea.okx.com`.

---

## Średnie

- **U9** — na Node 20 dostajesz gołe `WebSocket is not defined`; jedna linia guardu daje czytelny komunikat.
- **U10** — `Ctrl+C` ubija proces bez zamknięcia socketów; SIGINT/SIGTERM powinien je domknąć.
- **U11** — dwa połączenia reconnectują się w tym samym rytmie; losowy jitter to rozsuwa.
- **U13** — brak `account/positions`; dla SPOT nieistotne, dla SWAP/FUTURES konieczne.
- **U15** — jeśli 100 rekordów w stronie ma identyczny `ts`, kursor `after` nie przesunie się i paginacja zapętli. Mało prawdopodobne, ale brakuje limitu iteracji.
- **U16** — zdarzenia lecą wyłącznie na stdout; po restarcie nie ma po nich śladu. JSONL z rotacją to podstawa, jeśli listener ma chodzić długo.
- **U17** — `--help` nie istnieje w żadnym skrypcie. Co gorsza, `npm run export:prod -- --help` nie wypisze pomocy, tylko **wykona pełny eksport**.
- **U18** — `--key/--secret/--passphrase` są widoczne w `ps` i lądują w historii powłoki; warto je odradzić w pomocy albo usunąć.

## Niskie

- **U19** — ~98% ruchu to pushe `account` bez zmiany sald (~125 linii na 3 zdarzenia biznesowe, ~17 tys./dobę). Filtr porównujący `details`, nie `totalEq`, robi log czytelnym.
- **U20** — podpis HMAC, mapa profili i rozwiązywanie regionów są zduplikowane w obu plikach; wspólny `okx-common.mjs` usuwa ryzyko rozjechania się.
- **U22** — `$(node -p ...)` w `check:*` działa na sh; na Windows `cmd` nie.

---

## Sugerowana kolejność

1. **U4, U6, U7, U9, U11** — pięć drobiazgów, każdy 1–3 linie, razem ~30 min.
2. **U3 + U2** — naprawiają najbardziej mylącą część outputu.
3. **U1 + U5** — poprawność stanu zleceń i koniec pętli reconnectu.
4. **U12 + U14 + U21** — domknięcie eksportu.
5. Reszta wg potrzeb.

---

## Rzeczy już w porządku (nie wymagają działania)

- Uprawnienia `.env.demo` / `.env.prod` to `600`.
- `.gitignore` zasłania `tools/okx/.env.*` i `tools/okx/*.json`, z wyjątkami na `.env.example` i `package.json`.
- `package.json` wymusza `engines.node >= 22`.
- `ordId` przychodzi z OKX jako **string**, więc `JSON.parse` nie traci precyzji — mimo że wartości
  przekraczają `2^53`. Uwaga mimo to: każde `Number(ordId)` je uszkodzi, trzymaj jako string.
- Sesja testowa: zero reconnectów, zero `notice`/`64008` przez ~10,5 min.
