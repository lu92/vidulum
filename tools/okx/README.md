# tools/okx

Two standalone scripts (ESM, Node >= 22, **zero npm dependencies**) for read-only access to an OKX account:

| File | What it does |
|------|--------------|
| `okx-readonly-export.mjs` | REST export: uid / key permissions, Trading + Funding balances, deposits, withdrawals, fills, bills |
| `okx-ws-listener.mjs` | Private WebSocket listener (`orders`, `balance_and_position`, `account`, optionally `fills`) |

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

The full flag list lives in the header comment of each `.mjs` file.

## Limitations and gotchas

- **`fills-history` only goes back 3 months.** An older `--from` will not return trades -
  they simply are not there. The same applies to `bills-archive` (3 months); `asset/bills`
  (Funding) covers only 1 month.
- **The WS `fills` channel requires VIP5+.** It lives on the `/ws/v5/business` endpoint and the
  subscription is rejected below that tier. The default channels (`orders`,
  `balance_and_position`, `account`) work on any account.
- **`orders` is a notification channel, not a source of truth.** You get order state changes
  (`state=filled` and so on); the full execution history still has to come from REST
  (`fills-history`).
- **`60032` (REST) / `50119` (WS login) means the wrong region.** A key from `my.okx.com` only
  works against `eea.okx.com` + `wseea.okx.com` / `wseeapap.okx.com` (demo). The same key on a
  global domain looks like a non-existent API key.
- **A passphrase containing `#` must be quoted** in the `.env` file (`OKX_PASSPHRASE='my#phrase'`).
  `node --env-file` truncates an unquoted value at the `#`, and OKX then returns
  `50105 OK-ACCESS-PASSPHRASE incorrect`. The same applies to values containing spaces.
- The `demo` profile adds the `x-simulated-trading: 1` header automatically; demo and live keys
  cannot be mixed between profiles.
- Output files (`*.json`) are git-ignored - `package.json` is the exception.

## Related documents

- `OKX-CONTEXT.md` - integration context: business goals, verified API findings, target backend design.
- `IMPROVEMENTS.md` - backlog of known weaknesses in both scripts, prioritised.
