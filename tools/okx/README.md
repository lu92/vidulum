# tools/okx

Two standalone scripts (ESM, Node >= 22, **zero npm dependencies**) for read-only access to an OKX account:

| File | What it does |
|------|--------------|
| `okx-readonly-export.mjs` | REST export: uid / key permissions, balances, open orders, positions, order history, deposits, withdrawals, fills, bills |
| `okx-ws-listener.mjs` | Private WebSocket listener (`orders`, `balance_and_position`, `account`, optionally `fills`) |
| `okx-common.mjs` | Shared helpers: argument parsing, profiles, region hosts, signed REST client |
| `okx-order-contract.mjs` | Every field the `orders` channel sends, with a real example and what it means (generated) |
| `okx-account-contract.mjs` | Field contracts for the `account` and `balance_and_position` channels (generated) |
| `contract/generate.mjs` | Regenerates the orders contract from the recorded fixtures |
| `contract/generate-account.mjs` | Regenerates the account / balance_and_position contracts |
| `contract/template.mjs` | The contract's prose and helper functions; the field table is injected |
| `okx-common.test.mjs` | `npm test` - runs offline, no credentials needed |
| `vidulum-client.mjs` | Minimal Vidulum REST client: registration, JWT, `ApiError` codes |
| `okx-snapshot.mjs` | Maps an OKX balance reply onto the `POST /portfolio-spec` body |
| `okx-onboard.mjs` | Walks an OKX account to a Vidulum portfolio (tasks E2, E3, E4) |
| `okx-spec-flow.mjs` | Decisions taken while walking the specification: which questions to answer, request bodies |
| `okx-quotes.mjs` | Which quotes a portfolio needs, and whether they reached the cache (task E8) |
| `okx-portfolio.mjs` | Reads the portfolio back and reports value, result and coverage (tasks E5, E7) |
| `okx-quote-loop.mjs` | Republishes quotes on a loop so the valuation keeps moving (task E6) |
| `okx-onboard.test.mjs` | Offline tests for the onboarding half, including a simulated full walk |
| `fixtures/orders-lifecycle.json` | 9 raw frames covering one order's full lifecycle |

Run either script with `--help` for the full flag list.

## Setting up `.env`

```bash
cp .env.example .env.prod   # live key -> OKX_*
cp .env.example .env.demo   # demo key -> OKX_DEMO_*
```

Fill in `KEY` / `SECRET` / `PASSPHRASE` in each file. An EEA account (`my.okx.com`) requires
`OKX_DOMAIN=eea.okx.com` - without it REST goes to `openapi.okx.com` and returns `60032`.
Create the keys as **read-only**; the export script warns if a key has broader permissions.
`.env.prod` and `.env.demo` are in `.gitignore`; `.env.example` is not.

## Onboarding do Vidulum (E2 + E3 + E4)

```bash
npm run onboard:demo:dry     # sam snapshot, bez backendu
npm run onboard:demo         # az do pytan, potem sie zatrzymuje
npm run onboard:demo:auto    # odpowiada "nie wiem" i konczy portfelem
```

`okx-onboard.mjs` przechodzi cala sciezke:

| krok | co robi | zadanie |
|---|---|---|
| 1 | czyta `account/config`, `account/balance`, `orders-pending` | E1 |
| 2 | rejestruje uzytkownika i trzyma JWT | E2 |
| 3 | publikuje kursy i **sprawdza, ze dotarly** | E8 |
| 4 | `POST /exchange-connection` | A8 |
| 5 | buduje snapshot i tworzy spec | E3, D1 |
| 6 | odpowiada na pytania | D2 |
| 7 | `confirm` przeciwko **swiezemu** snapshotowi | D3 |
| 8 | odswieza kursy dla tego, co portfel **faktycznie** trzyma | E5 |
| 9 | odczytuje portfel i raportuje wycene, wynik i pokrycie | E7 |

Kursy ida **przed** utworzeniem portfela, bo `GET /portfolio/{id}/{waluta}` wycenia **kazde**
aktywo, jakie portfel trzyma. Portfel zalozony wczesniej to portfel, ktorego nie da sie odczytac,
a awaria wychodzi daleko od zadania, ktore ja spowodowalo.

Weryfikacja idzie przez `GET /exchange/OKX/status` i porownuje z tym, co **zglasza backend**, nie
z tym, co wyslalismy: publikacja przyjeta, ktora nie dotarla do cache, to dokladnie ten przypadek.
Kursu waluty wyceny do samej siebie (`EUR/EUR`) nie trzeba publikowac — backend liczy go jako 1.

Na koncu raport wyglada tak:

```
My OKX (OKX)
  value      71500 EUR
  invested   0 EUR   <- always zero for a snapshot-built portfolio (task C9)
  coverage   23% of value has a known cost
  BTC 0.3 -> 16500 EUR; cost 50000 EUR (EXCHANGE_REPORTED); profit 1500 EUR [100% covered]
  BTC 1 -> 55000 EUR; cost unknown; profit not computable [0% covered]
```

Trzy rzeczy sa tu celowe. **Brak kosztu jest nazwany**, nie zamilczany. **Brak wyniku nie jest
zerem** — pozycja przelana z zewnatrz nie ma zysku, ktory dalo by sie policzyc, a „0.00" znioslby
rozroznienie, ktore backend utrzymuje od C1. **Zerowe `invested` ma zastrzezenie**, bo portfel ze
snapshotu nigdy nie przeszedl przez wplate, wiec to pole nic nie mowi o tym, ile faktycznie
wlozono (C9).

**Domyslnie zatrzymuje sie po kroku 4** i wypisuje pytania. Zeby przejsc dalej, trzeba podac
`--assume-unknown` albo `--answers plik.json` — bo „nie wiem" to **decyzja**, a skrypt nie
powinien jej podejmowac po cichu za czlowieka.

Przed `confirm` saldo jest **czytane ponownie**. Backend porownuje ze swiezym snapshotem
niezaleznie od wieku spec-u, wiec wyslanie tego samego co na poczatku tylko zamaskowaloby
zmiane, ktora zaszla w trakcie odpowiadania.

Mapowanie jest krotkie, bo backend powstal wokol tego, co OKX faktycznie zwraca:

| OKX | snapshot |
|---|---|
| `cashBal` | `total` — wszystko, co jest na koncie |
| `spotBal` | `traded` — czesc, ktorej OKX podal cene |
| `openAvgPx` | `reportedAvgPrice` — srednia cena tej czesci, **w USD** |

**POC nie dzieli pozycji.** Przekazuje trzy liczby, a na `traded` i `transferred-in` rozdziela je
silnik roznicy po stronie backendu — inaczej ta sama regula zylaby w dwoch miejscach i zaczelaby
sie rozjezdzac.

Locki z otwartych zlecen sa zwracane **osobno**: zlecenie blokuje czesc salda, ale nie zmienia
tego, co jest w posiadaniu. Do portfela trafia dopiero w zadaniu D5.


## Zywa wycena (E6)

```bash
node --env-file=.env.demo okx-quote-loop.mjs --profile demo \
     --portfolio <id> --token <jwt> --interval 30
```

Bez tego kursy zaladowane przy onboardingu zostaja zamrozone i portfel w nieskonczonosc raportuje
cene z chwili ostatniej publikacji.

Petla czyta portfel w kazdym cyklu, wiec sama nadaza za tym, co sie w nim zmienilo. Przy bledzie
**nie konczy sie**, tylko czeka coraz dluzej — do pieciu minut. Petla, ktora umiera przy pierwszym
limicie API, jest gorsza niz jej brak: wycena po cichu przestaje sie ruszac i nic tego nie mowi.

Kazdy cykl wypisuje takze to, **czego nie udalo sie odswiezyc**. „5 ok" przy szostym pominietym
po cichu wyglada zdrowo, podczas gdy portfel przestaje byc wyceniany w calosci.

`--once` robi jeden cykl i wychodzi — do uzycia ze skryptu albo jako sprawdzenie.


## Running

```bash
npm test                   # offline checks against the recorded fixtures
npm run check:demo         # credential smoke test: --verbose, window from yesterday -> check-demo.json
npm run check:prod         # same against the live key -> check-prod.json

npm run export:demo        # full export (90 days back) -> demo.json
npm run export:prod        #                            -> prod.json
npm run export:demo:quick  # same with --skip-bills (faster, no account journal)
npm run export:prod:quick

npm run ws:demo            # demo WS, eea region (wseeapap.okx.com)
npm run ws:prod            # live WS, eea region (wseea.okx.com)
```

Each npm script loads the matching file via `node --env-file=`. Extra arguments go after `--`:

```bash
npm run export:prod -- --from 2026-01-01 --to 2026-06-30 --out h1.json
npm run ws:prod -- --channels orders,fills,deposit-info
```

Useful flags:

```bash
npm run export:prod -- --days 7              # last 7 days instead of --from/--to
npm run export:prod -- --skip-orders         # skip open orders, positions and order history
npm run ws:prod -- --quiet                   # hide 'account' pushes that carry no balance change
npm run ws:prod -- --out-events events.jsonl # append every event to a JSONL file
npm run ws:prod -- --no-catchup              # skip the REST reconciliation after login
npm run ws:prod -- --account-events-only     # stop the ~5s 'account' heartbeat at the source
npm run ws:prod -- --state /var/lib/okx.json # where to keep the watermark across restarts
```

**Credentials are read from the environment only.** They cannot be passed as arguments -
argv is visible to other users through `ps` and lands in shell history.

## Staying in sync across restarts

The private channels never replay and carry no sequence number, so a gap cannot be detected - only
reconciled away. The listener keeps a watermark in `.okx-listener-state.json` (git-ignored) and on
every login pulls back everything that happened since: open orders, orders that reached a final
state, fills, deposits and withdrawals. Without that file a restart can only see currently-live
orders, and anything that closed while the process was down is lost silently. `--no-state` turns
the file off; `--state <file>` moves it.

The Funding account is the one part no channel covers. A Funding<->Trading transfer is reported
from the Trading side only, so the listener re-reads `/api/v5/asset/balances` whenever a
`balance_and_position` event other than a snapshot arrives, or a deposit/withdrawal is pushed.

## Limitations and gotchas

- **`fills-history` only goes back 3 months.** An older `--from` will not return trades -
  they simply are not there. The same applies to `bills-archive` (3 months); `asset/bills`
  (Funding) covers only 1 month.
- **The WS `fills` channel requires VIP5+.** It lives on the `/ws/v5/business` endpoint and the
  subscription is rejected below that tier. The default channels (`orders`,
  `balance_and_position`, `account`) work on any account.
- **`orders` is a notification channel, not a source of truth.** It has no snapshot: only changes
  that happen after subscribing are pushed. The listener therefore reconciles over REST after every
  login - open orders always, plus orders that reached a final state during a disconnect. Full
  execution details still have to come from `fills-history`.
- **`60032` (REST) / `50119` (WS login) means the wrong region.** A key from `my.okx.com` only
  works against `eea.okx.com` + `wseea.okx.com` / `wseeapap.okx.com` (demo). The same key on a
  global domain looks like a non-existent API key.
- **A passphrase containing `#` must be quoted** in the `.env` file (`OKX_PASSPHRASE='my#phrase'`).
  `node --env-file` truncates an unquoted value at the `#`, and OKX then returns
  `50105 OK-ACCESS-PASSPHRASE incorrect`. The same applies to values containing spaces.
- The `demo` profile adds the `x-simulated-trading: 1` header automatically; demo and live keys
  cannot be mixed between profiles.
- Output files (`*.json`) are git-ignored - `package.json` is the exception.

## Tests

`npm test` needs no credentials, no network and no dependencies - it replays the frames in
`fixtures/orders-lifecycle.json`, recorded from a real order lifecycle on the demo account
(creation, price amend, stop-loss attach and amend, take-profit added, cancellation, fill).
Variants the account never produced - trailing stop, a rejected attached algo, a legacy
top-level stop-loss - are clearly marked as synthetic in the test file.

If OKX adds a field, the contract check fails with its name. The contract is **generated**, not
hand-edited - its field list is derived from the fixtures so it cannot drift from reality:

```bash
npm run contract:generate
```

Editing `okx-order-contract.mjs` directly means losing the change on the next run. The prose and
helper functions belong in `contract/template.mjs`; the per-field notes, the documented
enumerations and OKX's own descriptions live in the three tables at the top of
`contract/generate.mjs`. Adding a field to the recording without describing it there fails the
generator on purpose.

## Related documents

- `PODSUMOWANIE.md` - the standing summary (Polish): glossary, what was established and how, the
  sync architecture it implies, what remains unknown, and what comes next. Start here.
- `OKX-CONTEXT.md` - integration context: business goals, verified API findings, target backend design.
- `IMPROVEMENTS.md` - backlog of known weaknesses in both scripts, prioritised.
