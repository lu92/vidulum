# OKX integration - agent context

This file summarises the findings from research and live testing. Read it before working on
anything in `tools/okx/` or on the OKX module in the backend - it saves re-reading the OKX docs.

## Why we are doing this (business context)

Vidulum/Widlum is a SaaS for cashflow management aimed at Polish sole proprietorships (JDG), with
planned expansion into the EU. Some users keep funds on crypto exchanges. We want the application to:

1. show the **current OKX portfolio state** (Trading + Funding) alongside the user's other accounts,
2. import the **history of deposits, withdrawals and trades** - with rates, fees and destination
   addresses - for bookkeeping and settlement,
3. receive a **real-time notification** when a trade executes, so it can trigger our own logic
   (balance update, notification).

Non-negotiable rules:
- The integration is **read-only**. We never place orders, make transfers or withdraw from a user's
  account. A user's API key must carry the `read_only` permission alone; the backend verifies this
  via `GET /api/v5/account/config` (the `perm` field) and rejects keys with `trade`/`withdraw`.
- History must be **stored durably on our side**, because OKX does not serve it indefinitely
  (see retention below).
- User secrets (key/secret/passphrase) are encrypted at rest and never appear in logs,
  configuration or URLs.

## Current state

`tools/okx/` contains two Node 22 scripts (ESM, zero npm dependencies) that act as a
**prototype / developer tool** - the logic is ultimately meant to move into the Spring Boot backend
(Java, Kafka, MongoDB):
- `okx-readonly-export.mjs` - REST: uid, balances, deposit/withdrawal history, fills, bills -> JSON.
- `okx-ws-listener.mjs` - private WebSocket: `orders`, `balance_and_position`, `account`.

Both support `prod`/`demo` profiles (`OKX_*` / `OKX_DEMO_*` variables, `.env.prod` / `.env.demo`
loaded via `node --env-file`). Tested and working against a demo account in the EEA region.

## Technical findings (verified live)

### Regions and hosts - the most common source of errors
The repo owner's account is registered on `my.okx.com` (**EEA** region). EEA keys do not work
against global hosts, and vice versa.

| | REST | WS live | WS demo |
|---|---|---|---|
| global | `openapi.okx.com` | `wss://ws.okx.com:8443` | `wss://wspap.okx.com:8443` |
| **EEA** | `eea.okx.com` | `wss://wseea.okx.com:8443` | `wss://wseeapap.okx.com:8443` |
| US | `openapi.okx.com`* | `wss://wsus.okx.com:8443` | `wss://wsuspap.okx.com:8443` |

WS paths: `/ws/v5/private`, `/ws/v5/public`, `/ws/v5/business`.
- REST `50119 "API key doesn't exist"` / WS `60032` -> wrong region, or a demo key without demo mode.
- WS `1006` on connect -> the host does not exist (e.g. `wspap.my.okx.com` - do not use).
- The region must be configurable **per user** in the backend (EU users are EEA, but not all of them).

### Demo Trading
- Separate keys created in Demo Trading mode; virtual funds; no deposits or withdrawals.
- REST demo: same host as live plus the `x-simulated-trading: 1` header. WS demo: a separate host
  (`*pap.okx.com`), no header.
- For WS testing you can grant a demo key `trade` and place orders from the UI/REST to generate events.

### Authentication
- REST: headers `OK-ACCESS-KEY`, `OK-ACCESS-SIGN`, `OK-ACCESS-TIMESTAMP` (ISO 8601 UTC with ms),
  `OK-ACCESS-PASSPHRASE`. Signature =
  `Base64(HMAC_SHA256(timestamp + METHOD + requestPath(+query) + body, secret))`.
  A timestamp older than 30 s yields `50102`.
- WS login: `{"op":"login","args":[{apiKey,passphrase,timestamp,sign}]}`, timestamp in **seconds**
  (Unix), over the signed string `timestamp + "GET" + "/users/self/verify"`.
- A `read_only` key is sufficient for every endpoint and channel we use.

### REST endpoints we use
- `GET /account/config` -> `uid`, `mainUid`, `perm`, `acctLv`.
- `GET /account/balance` (Trading), `GET /asset/balances` (Funding). Both must be summed - deposits
  land in Funding, trading happens in Trading; transfers between them appear in `asset/bills`
  (subType 11/12; `from`/`to`: 6 = Funding, 18 = Trading).
- `GET /asset/deposit-history` - `amt`, `ccy`, `chain`, `from`, `to`, `txId`, `state`
  (2 = credited), `ts`. No fee (deposits are free). Paginate with `after`=ts.
- `GET /asset/withdrawal-history` - as above plus `fee`, `wdId`. Paginate with `after`=ts.
- `GET /trade/fills-history?instType=SPOT|MARGIN|SWAP|FUTURES|OPTION` - `fillPx`, `fillSz`, `fee`,
  `feeCcy`, `tradeId`, `ordId`, `billId`, `ts`. Paginate with `after`=billId. **3-month retention.**
- `GET /account/bills-archive` - full trading account journal (trades, fees, funding fees,
  transfers, liquidations), `balChg`, `bal`, `px`. **3 months.** `GET /account/bills` - 7 days.
- `GET /asset/bills` - Funding journal. **1 month.**
- `GET /asset/convert/history` - conversions with their rate.
- OKX pagination: results are newest-first; `after=X` means "older than X"; limit 100.

### History older than 3 months - quarterly archive (async)
- `POST /account/bills-history-archive` `{year, quarter}` -> after ~2 h a `GET` on the same endpoint
  returns `fileHref` (CSV.zip) and `state` (`finished`/`ongoing`/`failed`). The link is valid ~5.5 h;
  a request for the same quarter stays valid 30 days; limit 1 request / 10 s.
- Data is available from **1 February 2021**, excluding the current quarter. The API does not serve
  anything earlier.
- Caution: for files generated after 2024-10-11 the "quarter" boundaries are shifted
  (e.g. "2024 Q2" = 01.07-30.09) - verify the range by `ts` inside the file, not by its name.
- The CSV contains `fillIdxPx` - the USDT index price at the moment of the trade; enough for
  valuation without fetching candles.
- History rebuild plan: walk back quarter by quarter to the first empty one or Q1 2021; the starting
  point is min(oldest bill, oldest deposit); if the oldest quarter begins with a non-zero `bal`,
  record it as the opening balance. Verification: sum of `balChg` + opening balance = today's balance.
- Applies to Trading only; Funding has a "monthly statement" (last year) in the Funding section.

### WebSocket - notifications
- There are no webhooks. We use the **`orders`** channel (`instType: ANY`) on `/ws/v5/private` as the
  notification source: `state` = `live` -> `partially_filled`* -> `filled` | `canceled`. The business
  trigger is `filled` (it carries `avgPx`, `accFillSz`, `fee`).
- `balance_and_position` - pushed on every balance/position change with an `eventType`
  (`filled_order`, `transferred`, `liquidation`, ...); a manual Funding<->Trading transfer also
  triggers it, which makes a good live test without trading.
- `account` - also pushes roughly every 5 s when valuation (`totalEq`) moves without any trade;
  do not treat it as a trade event.
- `deposit-info` / `withdrawal-info` on `/private` - pushed on deposit/withdrawal
  (not reachable on demo).
- **`fills` lives on `/ws/v5/business`, does not accept `instType`, and is available to VIP5+ only** -
  we do not rely on it; fill details are fetched over REST after a `filled` event.
- Keepalive: send the text `ping` every 20 s, the server replies `pong`; ~30 s without traffic and
  OKX drops the connection. A `notice` event with code 64008 means the server is about to close the
  connection (upgrade) -> reconnect.
- **The `orders` push carries the FULL order state, never a delta.** Verified live across 9 pushes
  spanning creation, price amend, TP/SL amend, cancellation and a fill: every frame had the same
  71 keys. "Empty" is always `""` - OKX never omits a key. Consumers can therefore diff two
  consecutive pushes field by field without guessing which fields were reported.
- The WS frame is **richer than `GET /trade/orders-pending`** (71 vs 54 fields). WS-only:
  `amendResult`, `amendSource`, `reqId`, `code`, `msg`, `notionalUsd`, `lastPx`, `execType`,
  `fillFee`, `fillFeeCcy`, `fillIdxPx`, `fillNotionalUsd`, `fillPnl`, `fillPxUsd`, `fillPxVol`,
  `fillMarkPx`, `fillMarkVol`, `fillFwdPx`.
- **`cancelSourceReason` exists in REST but NOT in the WS push.** WS gives only the numeric
  `cancelSource`; the human-readable reason requires a REST lookup.
- **`uTime` is not bumped for an attached TP/SL amend.** Verified: three consecutive pushes that
  added and changed attached algos all carried the creation-time `uTime`. A price amend and a
  cancellation do bump it. Never treat `uTime` as "time of this push".
- Amending an attached TP/SL uses **`new`-prefixed fields** inside `attachAlgoOrds`
  (`newSlTriggerPx`, `newSlOrdPx`, `newTpTriggerPx`, ...). Passing the plain names is rejected with
  `51500 "You must enter a price, quantity, or TP/SL condition"`.
- A stop-loss price is validated **at submission** (`51047` for an SL above the order price), so
  `failCode` inside `attachAlgoOrds` describes a failure at trigger time, not a bad request.
- Limit orders are bounded by a price band; exceeding it returns `51137` naming the allowed limit.
- On a spot buy, the fee is charged **in the base currency** (BTC on BTC-EUR), not the quote.
- `balance_and_position` fires `eventType=filled` on a real execution - confirmed; until an order
  actually fills, the only event ever seen is the `snapshot` sent at subscribe time.
- **Field enumerations are only partly published.** `state`, `ordType`, `side`, `tdMode`,
  `instType`, `posSide` and the trigger price types have documented value sets, mirrored into
  `okx-order-contract.mjs` (source: the tiagosiebler/okx-api typings). `execType`, `category`,
  `cancelSource`, `amendResult`, `amendSource`, `stpMode`, `tgtCcy`, `tpOrdKind`, `source` and
  `outcome` are typed as plain strings there, and OKX's own single-page reference is too large to
  retrieve programmatically - for those fields the contract records only observed values. Treat an
  unrecognised value as data to investigate, not as an error.
- WS does not replay events from before the connection. After every reconnect, fetch
  `fills-history` over REST starting from the last known `billId`.

### Historical prices (public, no key required)
- `GET /market/history-candles?instId=BTC-USDT&bar=1D` (OHLCV, up to 100 per request),
  `history-index-candles`, `history-mark-price-candles`. Valuation in PLN additionally needs a
  USD/PLN rate (NBP).

### Rate limits
Per endpoint and per key; typically 5-20 req / 2 s for private endpoints. The scripts pause
250-500 ms between pages and retry on `429`/`50011`.

## Target design (backend)
- An `okx` module in Spring Boot: per-user configuration = {region, demo flag, encrypted
  credentials}; `@ConfigurationProperties` plus Spring profiles per environment.
- A REST synchronisation job (every N minutes plus on demand) writing to MongoDB; deduplication by
  `billId` / `depId` / `wdId` / `tradeId`.
- A separate quarterly-archive job (request queue, state polling, CSV import).
- One `/private` WS per user with `orders` + `balance_and_position` (+ `deposit-info` /
  `withdrawal-info`), publishing events to Kafka.
- For tests: fixtures built from raw payloads captured on demo, plus a local WS mock for
  reconnect/pong tests.

## Where to look
- Documentation: `https://www.okx.com/docs-v5/en/` (for EEA it is worth checking the version under
  `my.okx.com/docs-v5`).
- **Official SDK: Python only.** `python-okx` on PyPI (author `okxv5api <api@okg.com>`, source at
  `github.com/okxapi/python-okx`), linked from the OKX docs overview. It is a thin REST wrapper:
  methods take loose keyword arguments and return the raw response dict. `consts.py` holds endpoint
  paths and nothing else - there are no typed models and no field enumerations, so it does not help
  when you need to know which values a field can take. OKX publishes no Java or TypeScript SDK.
- Best available source of field enumerations: the typings in `github.com/sieblyio/okx-api`
  (formerly `tiagosiebler/okx-api`), `src/types/rest/shared.ts`. Third-party but explicit; it also
  carries the region host maps in `src/util/websocket-util.ts`. It enumerates `state`, `ordType`,
  `side`, `tdMode`, `instType`, `posSide` and the trigger price types, and types everything else as
  plain `string`.
