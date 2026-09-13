# tools/okx

Two standalone scripts (ESM, Node >= 22, **zero npm dependencies**) for read-only access to an OKX account:

| File | What it does |
|------|--------------|
| `okx-readonly-export.mjs` | REST export: uid / key permissions, balances, open orders, positions, order history, deposits, withdrawals, fills, bills |
| `okx-ws-listener.mjs` | Private WebSocket listener (`orders`, `balance_and_position`, `account`, optionally `fills`) |
| `okx-common.mjs` | Shared helpers: argument parsing, profiles, region hosts, signed REST client |
| `okx-order-contract.mjs` | Every field the `orders` channel sends, with a real example and what it means (generated) |
| `contract/generate.mjs` | Regenerates the contract from the recorded fixtures |
| `contract/template.mjs` | The contract's prose and helper functions; the field table is injected |
| `okx-common.test.mjs` | `npm test` - runs offline, no credentials needed |
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
```

**Credentials are read from the environment only.** They cannot be passed as arguments -
argv is visible to other users through `ps` and lands in shell history.

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

- `OKX-CONTEXT.md` - integration context: business goals, verified API findings, target backend design.
- `IMPROVEMENTS.md` - backlog of known weaknesses in both scripts, prioritised.
