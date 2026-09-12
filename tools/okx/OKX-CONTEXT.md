# Integracja OKX – kontekst dla agenta

Ten plik jest streszczeniem ustaleń z researchu i testów. Przeczytaj go przed pracą nad czymkolwiek w `tools/okx/` lub nad modułem OKX w backendzie – oszczędza ponowne analizowanie dokumentacji OKX.

## Po co to robimy (kontekst biznesowy)

Vidulum/Widlum to SaaS do zarządzania cashflow dla polskich JDG (jednoosobowych działalności), z planowaną ekspansją na UE. Część użytkowników trzyma środki na giełdach krypto. Chcemy, żeby aplikacja:

1. pokazywała **aktualny stan portfela** na OKX (Trading + Funding) obok innych kont użytkownika,
2. importowała **historię wpłat, wypłat i transakcji** – z kursami, opłatami i adresami docelowymi – do księgowania i rozliczeń,
3. dostawała **powiadomienie w czasie rzeczywistym** o wykonanej transakcji, żeby uruchomić własną logikę (aktualizacja salda, notyfikacja).

Zasady nienegocjowalne:
- Integracja jest **wyłącznie do odczytu**. Nigdy nie składamy zleceń, nie robimy transferów ani wypłat na koncie użytkownika. Klucz API użytkownika ma mieć uprawnienie tylko `read_only`; backend to weryfikuje przez `GET /api/v5/account/config` (pole `perm`) i odrzuca klucze z `trade`/`withdraw`.
- Historia musi być **trwale zapisana u nas**, bo OKX nie oddaje jej w nieskończoność (patrz retencja niżej).
- Sekrety użytkowników (key/secret/passphrase) są szyfrowane w bazie, nigdy w logach, konfiguracji ani URL-ach.

## Stan obecny

`tools/okx/` zawiera dwa skrypty Node 22 (ESM, zero zależności npm) będące **prototypem / narzędziem deweloperskim** – docelowo logika ma trafić do backendu Spring Boot (Java, Kafka, MongoDB):
- `okx-readonly-export.mjs` – REST: uid, salda, deposit/withdrawal history, fills, bills → JSON.
- `okx-ws-listener.mjs` – prywatny WebSocket: `orders`, `balance_and_position`, `account`.

Oba mają profile `prod`/`demo` (zmienne `OKX_*` / `OKX_DEMO_*`, pliki `.env.prod` / `.env.demo` ładowane przez `node --env-file`). Przetestowane i działające na koncie demo w regionie EEA.

## Ustalenia techniczne (zweryfikowane na żywo)

### Regiony i hosty – najczęstsza przyczyna błędów
Konto właściciela repo jest zarejestrowane na `my.okx.com` (region **EEA**). Klucze EEA nie działają na hostach globalnych i odwrotnie.

| | REST | WS live | WS demo |
|---|---|---|---|
| global | `openapi.okx.com` | `wss://ws.okx.com:8443` | `wss://wspap.okx.com:8443` |
| **EEA** | `eea.okx.com` | `wss://wseea.okx.com:8443` | `wss://wseeapap.okx.com:8443` |
| US | `openapi.okx.com`* | `wss://wsus.okx.com:8443` | `wss://wsuspap.okx.com:8443` |

Ścieżki WS: `/ws/v5/private`, `/ws/v5/public`, `/ws/v5/business`.
- REST `50119 "API key doesn't exist"` / WS `60032` → zły region lub klucz demo bez trybu demo.
- WS `1006` przy connect → host nie istnieje (np. `wspap.my.okx.com` – nie używać).
- Region musi być konfigurowalny **per użytkownik** w backendzie (użytkownicy UE = EEA, ale nie wszyscy).

### Demo Trading
- Osobne klucze tworzone w trybie Demo Trading; wirtualne środki; bez wpłat i wypłat.
- REST demo: ten sam host co live + nagłówek `x-simulated-trading: 1`. WS demo: osobny host (`*pap.okx.com`), bez nagłówka.
- Do testów WS można na demo dać kluczowi `trade` i składać zlecenia z UI/REST, żeby wywołać eventy.

### Uwierzytelnianie
- REST: nagłówki `OK-ACCESS-KEY`, `OK-ACCESS-SIGN`, `OK-ACCESS-TIMESTAMP` (ISO 8601 UTC z ms), `OK-ACCESS-PASSPHRASE`. Podpis = `Base64(HMAC_SHA256(timestamp + METHOD + requestPath(+query) + body, secret))`. Timestamp starszy niż 30 s → `50102`.
- WS login: `{"op":"login","args":[{apiKey,passphrase,timestamp,sign}]}`, timestamp w **sekundach** (Unix), podpisywany string `timestamp + "GET" + "/users/self/verify"`.
- Klucz `read_only` wystarcza do wszystkich endpointów i kanałów, których używamy.

### Endpointy REST, których używamy
- `GET /account/config` → `uid`, `mainUid`, `perm`, `acctLv`.
- `GET /account/balance` (Trading), `GET /asset/balances` (Funding). Oba trzeba sumować – wpłaty lądują na Funding, handel na Trading; transfery między nimi widać w `asset/bills` (subType 11/12; `from`/`to`: 6 = Funding, 18 = Trading).
- `GET /asset/deposit-history` – `amt`, `ccy`, `chain`, `from`, `to`, `txId`, `state` (2 = zaksięgowane), `ts`. Brak opłaty (wpłaty są darmowe). Paginacja `after`=ts.
- `GET /asset/withdrawal-history` – jw. plus `fee`, `wdId`. Paginacja `after`=ts.
- `GET /trade/fills-history?instType=SPOT|MARGIN|SWAP|FUTURES|OPTION` – `fillPx`, `fillSz`, `fee`, `feeCcy`, `tradeId`, `ordId`, `billId`, `ts`. Paginacja `after`=billId. **Retencja 3 miesiące.**
- `GET /account/bills-archive` – pełny dziennik konta tradingowego (trade, fee, funding fee, transfery, likwidacje), `balChg`, `bal`, `px`. **3 miesiące.** `GET /account/bills` – 7 dni.
- `GET /asset/bills` – dziennik Funding. **1 miesiąc.**
- `GET /asset/convert/history` – konwersje z kursem.
- Paginacja OKX: wyniki od najnowszych; `after=X` znaczy "starsze niż X"; limit 100.

### Historia starsza niż 3 miesiące – archiwum kwartalne (async)
- `POST /account/bills-history-archive` `{year, quarter}` → po ~2 h `GET` tego samego endpointu zwraca `fileHref` (CSV.zip) i `state` (`finished`/`ongoing`/`failed`). Link ważny ~5,5 h; wnioski o ten sam kwartał ważne 30 dni; limit 1 wniosek / 10 s.
- Dane dostępne od **1 lutego 2021**, z wyjątkiem bieżącego kwartału. Wcześniejszych danych API nie oddaje.
- Uwaga: dla plików generowanych po 11.10.2024 granice "kwartałów" są przesunięte (np. "2024 Q2" = 1.07–30.09) – weryfikować zakres po `ts` w pliku, nie po nazwie.
- CSV zawiera `fillIdxPx` – cenę indeksową w USDT w chwili transakcji; wystarcza do wyceny bez pobierania świec.
- Plan odbudowy historii: idź kwartałami wstecz do pierwszego pustego / Q1 2021; punkt startowy = min(najstarszy bill, najstarsza wpłata); jeśli najstarszy kwartał zaczyna się od niezerowego `bal`, zapisz saldo otwarcia. Weryfikacja: Σ`balChg` + saldo otwarcia = dzisiejsze saldo.
- Dotyczy tylko Trading; dla Funding jest "monthly statement" (ostatni rok) w sekcji Funding.

### WebSocket – powiadomienia
- Nie ma webhooków. Używamy kanału **`orders`** (`instType: ANY`) na `/ws/v5/private` jako źródła powiadomień: `state` = `live` → `partially_filled`* → `filled` | `canceled`. Trigger biznesowy = `filled` (ma `avgPx`, `accFillSz`, `fee`).
- `balance_and_position` – push przy każdej zmianie salda/pozycji z `eventType` (`filled_order`, `transferred`, `liquidation`...); wywołuje go też ręczny transfer Funding↔Trading – dobry test na live bez handlu.
- `account` – pushuje także co ~5 s przy zmianie wyceny (`totalEq`) bez transakcji; nie traktować jako event transakcji.
- `deposit-info` / `withdrawal-info` na `/private` – push przy wpłacie/wypłacie (na demo nieosiągalne).
- **`fills` żyje na `/ws/v5/business`, nie przyjmuje `instType` i jest dostępny tylko dla VIP5+** – nie polegamy na nim; szczegóły fillów dociągamy REST-em po evencie `filled`.
- Keepalive: co 20 s wysłać tekst `ping`, serwer odpowiada `pong`; brak ruchu ~30 s → OKX zrywa. Event `notice` kod 64008 = serwer zaraz zamknie połączenie (upgrade) → reconnect.
- WS nie odtwarza eventów sprzed połączenia. Po każdym reconnect dociągnąć REST-em `fills-history` od ostatniego znanego `billId`.

### Ceny historyczne (publiczne, bez klucza)
- `GET /market/history-candles?instId=BTC-USDT&bar=1D` (OHLCV, do 100/żądanie), `history-index-candles`, `history-mark-price-candles`. Do wyceny w PLN potrzebny osobny kurs USD/PLN (NBP).

### Rate limity
Per endpoint i per klucz; typowo 5–20 req / 2 s dla prywatnych endpointów. Skrypty robią 250–500 ms przerwy między stronami i retry na `429`/`50011`.

## Kierunek docelowy (backend)
- Moduł `okx` w Spring Boot: konfiguracja per użytkownik = {region, demo flag, encrypted credentials}; `@ConfigurationProperties` + profile Springa dla środowisk.
- Job synchronizacji REST (co N minut + na żądanie) zapisujący do MongoDB; deduplikacja po `billId` / `depId` / `wdId` / `tradeId`.
- Osobny job archiwum kwartalnego (kolejka wniosków, polling stanu, import CSV).
- Jeden WS `/private` na użytkownika z `orders` + `balance_and_position` (+ `deposit-info`/`withdrawal-info`), eventy publikowane na Kafkę.
- Do testów: fixtures z surowymi payloadami zebranymi na demo; lokalny mock WS do testów reconnect/pong.

## Gdzie szukać
- Dokumentacja: `https://www.okx.com/docs-v5/en/` (dla EEA warto sprawdzać wersję pod `my.okx.com/docs-v5`).
- Referencyjne SDK z obsługą regionów: `github.com/tiagosiebler/okx-api` (mapy hostów w `src/util/websocket-util.ts`).
