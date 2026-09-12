#!/usr/bin/env node
/**
 * OKX private WebSocket listener
 * ------------------------------
 * Logs into the OKX private WebSocket and reports order, balance and position events.
 *
 * OKX splits channels across two endpoints:
 *   /ws/v5/private  -> orders, account, positions, balance_and_position, deposit-info, withdrawal-info
 *   /ws/v5/business -> fills (VIP5+ only, no instType), algo orders, grid
 * A separate connection is opened for each endpoint that is actually needed.
 *
 * The WebSocket never replays what happened before the connection, so after every login
 * the current order book state is reconciled over REST (open orders, plus orders that
 * closed while we were disconnected).
 *
 * Requires Node.js >= 22 (native WebSocket).
 */

import { appendFileSync } from "node:fs";
import { createHmac } from "node:crypto";
import {
  parseArgs, maybePrintHelp, resolveProfile, createRestClient,
  WS_HOSTS, INST_TYPES, FATAL_AUTH_CODES, sleep,
} from "./okx-common.mjs";

const USAGE = `
OKX private WebSocket listener

Usage:
  node --env-file=.env.demo okx-ws-listener.mjs --profile demo --region eea
  node --env-file=.env.prod okx-ws-listener.mjs --region eea --channels orders,fills

Profiles (credentials are read from the environment only - never from arguments):
  prod  -> OKX_KEY, OKX_SECRET, OKX_PASSPHRASE        demo -> OKX_DEMO_* equivalents

Options:
  --profile prod|demo     which set of environment variables to use (default: prod)
  --demo                  shorthand for --profile demo
  --region global|eea|us  WebSocket region (default: global; env OKX_REGION / OKX_DEMO_REGION)
                            global: ws.okx.com    / demo wspap.okx.com
                            eea:    wseea.okx.com / demo wseeapap.okx.com
                            us:     wsus.okx.com  / demo wsuspap.okx.com
  --ws-url wss://host:8443  override the base URL (no path)
  --domain <host>         REST host used for the post-login reconciliation
                            (default: OKX_DOMAIN / OKX_DEMO_DOMAIN, else openapi.okx.com)
  --channels a,b,c        default: orders,balance_and_position,account
  --no-catchup            skip the REST reconciliation after login
  --out-events <file>     append every event to a JSONL file
  --quiet                 suppress 'account' pushes that carry no balance change
  --verbose               log every REST call made during reconciliation
  --help                  show this message and exit

Notes:
  The 'fills' channel lives on /ws/v5/business and requires VIP5+.
  'orders' is a notification channel - it has no snapshot, hence the REST reconciliation.
`;

const args = parseArgs(process.argv.slice(2));
maybePrintHelp(args, USAGE);

// U9: native WebSocket landed in Node 22; without this the failure is a bare ReferenceError.
if (typeof WebSocket === "undefined") {
  console.error(`This script needs Node.js >= 22 for the native WebSocket client (running ${process.version}).`);
  console.error(`On Node 18/20 install the "ws" package and import it at the top of this file.`);
  process.exit(1);
}

const profile = resolveProfile(args);

const region = args.region ?? profile.env("REGION") ?? "global";
if (!WS_HOSTS[region]) {
  console.error(`Unknown region ${region}. Available: ${Object.keys(WS_HOSTS).join(", ")}`);
  process.exit(1);
}

const base = (args["ws-url"] ?? profile.env("WS_URL")
  ?? `wss://${WS_HOSTS[region][profile.simulated ? "demo" : "live"]}:8443`).replace(/\/ws\/v5\/.*$/, "");

const cfg = {
  channels: (args.channels ?? "orders,balance_and_position,account").split(",").map((c) => c.trim()).filter(Boolean),
  catchup: !args["no-catchup"],
  outEvents: typeof args["out-events"] === "string" ? args["out-events"] : undefined,
  quiet: !!args.quiet,
};

const rest = createRestClient({
  key: profile.key, secret: profile.secret, passphrase: profile.passphrase,
  domain: profile.domain, demo: profile.simulated, verbose: !!args.verbose,
});

// Which channels live on which endpoint
const BUSINESS_CHANNELS = new Set(["fills", "orders-algo", "algo-advance", "grid-orders-spot", "grid-orders-contract"]);
const NEEDS_INST_TYPE = new Set(["orders", "orders-algo", "algo-advance", "positions"]);

const byEndpoint = { private: [], business: [] };
for (const channel of cfg.channels) {
  const arg = NEEDS_INST_TYPE.has(channel) ? { channel, instType: "ANY" } : { channel };
  byEndpoint[BUSINESS_CHANNELS.has(channel) ? "business" : "private"].push(arg);
}

// ---------- local state ----------
// U2: 'account' pushes are incremental - a push touching one currency carries only that
// currency in details[]. Overwriting would make the other currencies look like they vanished.
const state = {
  balances: new Map(), // ccy -> { cashBal, availBal, frozenBal }
  orders: new Map(),   // ordId -> last known order payload
  lastEventTs: 0,      // newest server timestamp seen, used as the catch-up watermark
};

function recordEvent(channel, payload) {
  if (!cfg.outEvents) return;
  try {
    appendFileSync(cfg.outEvents,
      JSON.stringify({ receivedAt: new Date().toISOString(), channel, data: payload }) + "\n");
  } catch (err) {
    console.error(`cannot write ${cfg.outEvents}: ${err.message}`);
  }
}

// ---------- REST reconciliation (U1) ----------
/**
 * The orders channel only pushes changes that happen after subscribing, so anything that
 * existed beforehand - or moved while we were offline - is invisible to it. After every
 * login we pull the live orders, and on a reconnect also the orders that reached a final
 * state during the gap.
 */
async function reconcileOrders(tag, sinceTs) {
  if (!cfg.catchup) return;
  try {
    const pending = await rest.paginate("/api/v5/trade/orders-pending", {}, { cursorField: "ordId" });
    for (const o of pending) state.orders.set(o.ordId, o);
    console.log(`[${tag}] catch-up: ${pending.length} live order(s)`);
    for (const o of pending) {
      console.log(`  [catchup/live] ${o.instId} ${o.side} state=${o.state} filled=${o.accFillSz}/${o.sz} px=${o.px} ordId=${o.ordId}`);
      recordEvent("catchup/orders-pending", o);
    }

    if (!sinceTs) return;
    const closed = [];
    for (const instType of INST_TYPES) {
      const rows = await rest.paginate("/api/v5/trade/orders-history", { instType },
        { cursorField: "ordId", tsField: "uTime", from: sinceTs });
      closed.push(...rows);
      await sleep(300);
    }
    closed.sort((a, b) => Number(a.uTime) - Number(b.uTime));
    console.log(`[${tag}] catch-up: ${closed.length} order(s) finished while disconnected`);
    for (const o of closed) {
      state.orders.set(o.ordId, o);
      console.log(`  [catchup/closed] ${new Date(Number(o.uTime)).toISOString()} ${o.instId} ${o.side} state=${o.state} filled=${o.accFillSz}/${o.sz} avgPx=${o.avgPx} ordId=${o.ordId}`);
      recordEvent("catchup/orders-history", o);
    }
  } catch (err) {
    // A failed catch-up must not kill a working listener, but it must be loud:
    // from here on the local order state is known to be incomplete.
    console.error(`[${tag}] catch-up FAILED, order state may be incomplete: ${err.message}`);
  }
}

function loginPayload() {
  // The WS login signature differs from the REST one: the timestamp is in seconds
  // (not ISO) and the signed path is the fixed string /users/self/verify.
  const timestamp = String(Math.floor(Date.now() / 1000));
  const sign = createHmac("sha256", profile.secret)
    .update(timestamp + "GET" + "/users/self/verify").digest("base64");
  return { op: "login", args: [{ apiKey: profile.key, passphrase: profile.passphrase, timestamp, sign }] };
}

// ---------- one connection = one endpoint ----------
const connections = [];

function createConnection(endpoint, subscribeArgs) {
  const url = `${base}/ws/v5/${endpoint}`;
  const tag = `${profile.name}/${endpoint}`;
  const handlesOrders = subscribeArgs.some((a) => a.channel === "orders");
  let ws, pingTimer, pongTimeout, backoff = 1000, loggedInOnce = false, stopping = false;

  const clearTimers = () => { clearInterval(pingTimer); clearTimeout(pongTimeout); };

  // U8: only a real pong proves the link is alive. Resetting on any frame made the
  // detector useless, because the account channel pushes every ~5 s regardless.
  const resetPongTimeout = () => {
    clearTimeout(pongTimeout);
    pongTimeout = setTimeout(() => {
      console.warn(`[${tag}] no pong for 50 s, reconnecting`);
      ws.close();
    }, 50_000);
  };

  const schedulePing = () => {
    clearInterval(pingTimer);
    pingTimer = setInterval(() => ws.readyState === 1 && ws.send("ping"), 20_000);
    resetPongTimeout();
  };

  const connect = () => {
    console.log(`[${tag}] connecting: ${url}`);
    ws = new WebSocket(url);

    ws.onopen = () => { ws.send(JSON.stringify(loginPayload())); schedulePing(); };

    ws.onmessage = ({ data }) => {
      if (data === "pong") { resetPongTimeout(); return; }

      // U6: one malformed frame must not take the whole process down.
      let msg;
      try {
        msg = JSON.parse(data);
      } catch {
        console.error(`[${tag}] unparsable frame: ${String(data).slice(0, 200)}`);
        return;
      }

      if (msg.event === "login") {
        if (msg.code !== "0") {
          console.error(`[${tag}] login failed: code=${msg.code} msg=${msg.msg}`);
          // U5: bad key/region/passphrase will never succeed - retrying just burns rate limit.
          if (FATAL_AUTH_CODES.has(String(msg.code))) {
            console.error(`[${tag}] this is a credential/region error, not a transient one - giving up.`);
            console.error(`[${tag}] check the key's region (EEA keys only work on wseea/wseeapap hosts) and the passphrase quoting in .env.`);
            shutdown(1);
            return;
          }
          ws.close();
          return;
        }
        // U5: the backoff is reset only once the connection is actually usable.
        backoff = 1000;
        const sinceTs = loggedInOnce ? state.lastEventTs : 0;
        loggedInOnce = true;
        console.log(`[${tag}] logged in, subscribing: ${subscribeArgs.map((a) => a.channel).join(", ")}`);
        ws.send(JSON.stringify({ op: "subscribe", args: subscribeArgs }));
        // U2: a reconnect re-sends a full account snapshot, so drop the stale merge base.
        state.balances.clear();
        if (handlesOrders) reconcileOrders(tag, sinceTs);
        return;
      }
      if (msg.event === "subscribe") { console.log(`[${tag}] subscribed: ${msg.arg.channel}`); return; }
      if (msg.event === "error") { console.error(`[${tag}] WS error:`, msg); return; }
      if (msg.event === "notice") { console.warn(`[${tag}] notice:`, msg.msg); return; } // 64008 = server about to disconnect
      // U7: anything else used to fall through into an empty loop and disappear.
      if (msg.event || msg.op) {
        console.warn(`[${tag}] unhandled control message:`, JSON.stringify(msg).slice(0, 300));
        return;
      }
      for (const item of msg.data ?? []) handleEvent(msg.arg?.channel, item);
    };

    ws.onclose = (e) => {
      clearTimers();
      if (stopping) return;
      // U11: jitter keeps the two endpoints from reconnecting in lockstep.
      const delay = backoff + Math.floor(Math.random() * backoff * 0.3);
      console.warn(`[${tag}] disconnected (${e.code}), reconnecting in ${delay} ms`);
      setTimeout(connect, delay);
      backoff = Math.min(backoff * 2, 30_000);
    };

    ws.onerror = (e) => console.error(`[${tag}] socket error:`, e.message ?? "");
  };

  connections.push({ tag, stop: () => { stopping = true; clearTimers(); try { ws?.close(); } catch {} } });
  connect();
}

// ---------- event handling ----------
function eventTime(d) {
  // U4: balance_and_position carries pTime only - no uTime, no ts. Without it the chain
  // silently fell through to Date.now(), stamping local receive time as if it were server time.
  const raw = d.uTime ?? d.pTime ?? d.ts;
  if (raw === undefined) return `${new Date().toISOString()}(local)`;
  const n = Number(raw);
  if (!Number.isFinite(n) || n <= 0) return `${new Date().toISOString()}(local)`;
  if (n > state.lastEventTs) state.lastEventTs = n;
  return new Date(n).toISOString();
}

/** U2/U3: merge the incremental push and report what actually moved. */
function mergeBalances(details = []) {
  const changes = [];
  for (const d of details) {
    const prev = state.balances.get(d.ccy);
    const next = { cashBal: d.cashBal, availBal: d.availBal, frozenBal: d.frozenBal };
    if (!prev || prev.cashBal !== next.cashBal || prev.availBal !== next.availBal || prev.frozenBal !== next.frozenBal) {
      changes.push({ ccy: d.ccy, prev, next });
    }
    state.balances.set(d.ccy, next);
  }
  return changes;
}

function handleEvent(channel, d) {
  const t = eventTime(d);
  recordEvent(channel, d);

  switch (channel) {
    case "orders": {
      state.orders.set(d.ordId, d);
      console.log(`[orders] ${t} ${d.instId} ${d.side} state=${d.state} filled=${d.accFillSz}/${d.sz} avgPx=${d.avgPx} ordId=${d.ordId}`);
      if (d.state === "filled") console.log("  -> ORDER FILLED - trigger your own operation here");
      break;
    }
    case "fills":
      console.log(`[fills] ${t} ${d.instId} ${d.side} px=${d.fillPx} sz=${d.fillSz} fee=${d.fee}${d.feeCcy ?? ""} tradeId=${d.tradeId}`);
      break;
    case "balance_and_position":
      console.log(`[bal&pos] ${t} eventType=${d.eventType}`, JSON.stringify(d.balData ?? []), JSON.stringify(d.posData ?? []));
      break;
    case "account": {
      const changes = mergeBalances(d.details);
      // U19: most account pushes are pure revaluation of totalEq with no balance movement.
      if (cfg.quiet && changes.length === 0) break;
      const merged = [...state.balances.entries()]
        .map(([ccy, b]) => `${ccy}=${b.cashBal}(avail=${b.availBal} frozen=${b.frozenBal})`)
        .join(" ");
      console.log(`[account] ${t} totalEq=${d.totalEq} ${merged}`);
      // U3: the field that moves when an order is placed is frozenBal, never cashBal.
      for (const c of changes) {
        const from = c.prev ? `${c.prev.cashBal}/${c.prev.availBal}/${c.prev.frozenBal}` : "(new)";
        console.log(`  -> ${c.ccy} cash/avail/frozen: ${from} -> ${c.next.cashBal}/${c.next.availBal}/${c.next.frozenBal}`);
      }
      break;
    }
    default:
      console.log(`[${channel}]`, JSON.stringify(d));
  }
}

// ---------- lifecycle (U10) ----------
let shuttingDown = false;
function shutdown(code = 0) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`\nshutting down (${connections.length} connection(s))...`);
  for (const c of connections) c.stop();
  setTimeout(() => process.exit(code), 150).unref();
}
process.on("SIGINT", () => shutdown(0));
process.on("SIGTERM", () => shutdown(0));

if (cfg.outEvents) console.log(`recording events to ${cfg.outEvents}`);
for (const [endpoint, subscribeArgs] of Object.entries(byEndpoint)) {
  if (subscribeArgs.length) createConnection(endpoint, subscribeArgs);
}
