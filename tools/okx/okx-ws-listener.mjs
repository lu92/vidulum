#!/usr/bin/env node
/**
 * OKX private WebSocket listener
 * ------------------------------
 * Logs into the OKX private WebSocket and reports order, balance and position events.
 *
 * OKX splits channels across two endpoints:
 *   /ws/v5/private  -> orders, account, positions, balance_and_position, account-greeks,
 *                      liquidation-warning
 *   /ws/v5/business -> deposit-info, withdrawal-info, algo orders, fills (VIP5+), grid
 * A separate connection is opened for each endpoint that is actually needed.
 *
 * The WebSocket never replays what happened before the connection, so after every login
 * the current order book state is reconciled over REST (open orders, plus orders that
 * closed while we were disconnected).
 *
 * Requires Node.js >= 22 (native WebSocket).
 */

import { appendFileSync, readFileSync, writeFileSync } from "node:fs";
import { createHmac } from "node:crypto";
import {
  parseArgs, maybePrintHelp, resolveProfile, createRestClient,
  WS_HOSTS, INST_TYPES, FATAL_AUTH_CODES, sleep, formatOrder, diffOrder, foldBalances, formatBalancePosition,
} from "./okx-common.mjs";
import { TERMINAL_STATES } from "./okx-order-contract.mjs";

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
  --no-dedup              process repeated messages instead of discarding them
  --state <file>          watermark file surviving restarts (default .okx-listener-state.json)
  --no-state              do not persist the watermark; a restart then re-reads nothing
  --account-events-only   ask OKX to stop the regular 'account' heartbeat (updateInterval 0)
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
  dedup: !args["no-dedup"],
  accountEventsOnly: !!args["account-events-only"],
  stateFile: args["no-state"] ? null
    : (typeof args.state === "string" ? args.state : ".okx-listener-state.json"),
  outEvents: typeof args["out-events"] === "string" ? args["out-events"] : undefined,
  quiet: !!args.quiet,
};

const rest = createRestClient({
  key: profile.key, secret: profile.secret, passphrase: profile.passphrase,
  domain: profile.domain, demo: profile.simulated, verbose: !!args.verbose,
});

// Which channels live on which endpoint
// Verified empirically on 2026-09-13 by subscribing to each name on both endpoints: deposit-info
// and withdrawal-info live on /business, not /private as the OKX overview implies. Routing them
// to /private returns 60018 "channel doesn't exist".
const BUSINESS_CHANNELS = new Set([
  "fills", "orders-algo", "algo-advance", "grid-orders-spot", "grid-orders-contract",
  "deposit-info", "withdrawal-info",
]);
const NEEDS_INST_TYPE = new Set(["orders", "orders-algo", "algo-advance", "positions"]);

const byEndpoint = { private: [], business: [] };
for (const channel of cfg.channels) {
  const arg = NEEDS_INST_TYPE.has(channel) ? { channel, instType: "ANY" } : { channel };
  // The account channel pushes on events AND on a regular heartbeat. updateInterval 0 turns the
  // heartbeat off at the source, which beats filtering ~98% of the traffic on arrival.
  if (channel === "account" && cfg.accountEventsOnly) {
    arg.extraParams = JSON.stringify({ updateInterval: "0" });
  }
  byEndpoint[BUSINESS_CHANNELS.has(channel) ? "business" : "private"].push(arg);
}

// ---------- local state ----------
// U2: 'account' pushes are incremental - a push touching one currency carries only that
// currency in details[]. Overwriting would make the other currencies look like they vanished.
const state = {
  balances: new Map(), // ccy -> { cashBal, availBal, frozenBal }
  orders: new Map(),   // ordId -> last known order payload
  lastEventTs: 0,      // newest server timestamp seen, used as the catch-up watermark
  seenTrades: new Set(),     // instId:tradeId - a fill must be counted once
  terminalOrders: new Set(), // ordId that already reached filled / canceled
  seenAmends: new Set(),     // ordId:reqId - an amendment response must be counted once
  snapshotPages: null,       // account snapshot accumulated across pages
  funding: new Map(),        // ccy -> balance on the Funding account, REST-sourced
};

// ---------- durable watermark ----------
/**
 * The reconciliation watermark has to outlive the process. Held only in memory it resets to 0 on
 * every restart, so the catch-up degrades to "fetch live orders" and anything that reached a
 * final state while the listener was down is lost - silently, because the private channels carry
 * no sequence number to reveal the gap.
 */
function loadWatermark() {
  if (!cfg.stateFile) return;
  try {
    const raw = JSON.parse(readFileSync(cfg.stateFile, "utf8"));
    state.lastEventTs = Number(raw.lastEventTs) || 0;
    if (state.lastEventTs) {
      console.log(`resuming from ${new Date(state.lastEventTs).toISOString()} (${cfg.stateFile})`);
    }
  } catch {
    console.log(`no previous watermark in ${cfg.stateFile}; first run reconciles live orders only`);
  }
}

function saveWatermark() {
  if (!cfg.stateFile || !state.lastEventTs) return;
  try {
    writeFileSync(cfg.stateFile,
      JSON.stringify({ lastEventTs: state.lastEventTs, savedAt: new Date().toISOString() }, null, 2));
  } catch (err) {
    console.error(`cannot write ${cfg.stateFile}: ${err.message}`);
  }
}

/** Terminal orders accumulate forever otherwise; live ones are never evicted. */
const MAX_TRACKED_ORDERS = 1000;
function evictTerminalOrders() {
  if (state.orders.size <= MAX_TRACKED_ORDERS) return;
  let dropped = 0;
  for (const [ordId, o] of state.orders) {
    if (state.orders.size <= MAX_TRACKED_ORDERS) break;
    if (TERMINAL_STATES.has(o.state)) { state.orders.delete(ordId); dropped++; }
  }
  if (dropped) console.log(`evicted ${dropped} terminal order(s) held in memory (cap ${MAX_TRACKED_ORDERS})`);
}

/**
 * Pulls the Funding balances over REST.
 *
 * Verified 2026-09-13: a Funding<->Trading transfer is reported only from the Trading side -
 * `balance_and_position` fires `transferred` and `account` fires `event_update`, both carrying the
 * trading balance. The Funding side appears in no push at all, and no private channel covers that
 * account. A transfer notification therefore means "re-read Funding", not "Funding is now X".
 */
let fundingRefreshedAt = 0;
async function refreshFunding(reason) {
  const now = Date.now();
  if (now - fundingRefreshedAt < 5000) return; // the event often arrives on two channels at once
  fundingRefreshedAt = now;
  try {
    const rows = await rest.get("/api/v5/asset/balances");
    const next = new Map(rows.map((b) => [b.ccy, b.bal]));
    const changes = [];
    for (const [ccy, bal] of next) {
      if (state.funding.get(ccy) !== bal) changes.push(`${ccy}: ${state.funding.get(ccy) ?? "-"} -> ${bal}`);
    }
    for (const [ccy, bal] of state.funding) {
      if (!next.has(ccy)) changes.push(`${ccy}: ${bal} -> (zero balance, no longer listed)`);
    }
    state.funding = next;
    if (changes.length) {
      console.log(`[funding] refreshed after ${reason}: ${changes.join("  ")}`);
    }
  } catch (err) {
    console.error(`[funding] refresh after ${reason} FAILED, Funding balances may be stale: ${err.message}`);
  }
}

/**
 * OKX warns that the same message may be delivered more than once, sometimes with a different
 * uTime, and publishes the rules for collapsing them:
 *   - a tradeId marks a fill; each tradeId counts once per instrument,
 *   - a terminal state (filled / canceled / mmp_canceled) counts once per order,
 *   - a reqId marks an amendment response; each counts once.
 * Without this a single fill can be booked twice. Duplicates are reported rather than dropped
 * silently - a sudden stream of them is a signal, not noise.
 */
function duplicateReason(d) {
  if (!cfg.dedup) return null;
  let reason = null;
  if (d.tradeId) {
    const key = `${d.instId}:${d.tradeId}`;
    if (state.seenTrades.has(key)) reason = `fill already counted (tradeId=${d.tradeId})`;
    else state.seenTrades.add(key);
  } else if (TERMINAL_STATES.has(d.state)) {
    if (state.terminalOrders.has(d.ordId)) reason = `terminal state already seen (${d.state})`;
  } else if (d.reqId) {
    const key = `${d.ordId}:${d.reqId}`;
    if (state.seenAmends.has(key)) reason = `amendment response already seen (reqId=${d.reqId})`;
    else state.seenAmends.add(key);
  }
  if (!reason && TERMINAL_STATES.has(d.state)) state.terminalOrders.add(d.ordId);
  return reason;
}

function recordEvent(channel, payload, envelope) {
  if (!cfg.outEvents) return;
  try {
    const row = { receivedAt: new Date().toISOString(), channel, data: payload };
    if (envelope?.eventType !== undefined) row.eventType = envelope.eventType;
    if (envelope?.curPage !== undefined) row.curPage = envelope.curPage;
    if (envelope?.lastPage !== undefined) row.lastPage = envelope.lastPage;
    appendFileSync(cfg.outEvents, JSON.stringify(row) + "\n");
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
async function reconcile(tag, sinceTs) {
  if (!cfg.catchup) return;
  try {
    const pending = await rest.paginate("/api/v5/trade/orders-pending", {}, { cursorField: "ordId" });
    for (const o of pending) state.orders.set(o.ordId, o);
    console.log(`[${tag}] catch-up: ${pending.length} live order(s)`);
    for (const o of pending) {
      console.log(`  [catchup/live] ${formatOrder(o)}`);
      recordEvent("catchup/orders-pending", o);
    }

    if (!sinceTs) return;
    const since = new Date(sinceTs).toISOString();

    const closed = [];
    for (const instType of INST_TYPES) {
      const rows = await rest.paginate("/api/v5/trade/orders-history", { instType },
        { cursorField: "ordId", tsField: "uTime", from: sinceTs });
      closed.push(...rows);
      await sleep(300);
    }
    closed.sort((a, b) => Number(a.uTime) - Number(b.uTime));
    console.log(`[${tag}] catch-up: ${closed.length} order(s) finished since ${since}`);
    for (const o of closed) {
      state.orders.set(o.ordId, o);
      console.log(`  [catchup/closed] ${new Date(Number(o.uTime)).toISOString()} ${formatOrder(o)}`);
      recordEvent("catchup/orders-history", o);
    }
    evictTerminalOrders();

    // Fills, deposits and withdrawals need the same treatment: the channels do not replay, there
    // is no sequence number to expose a gap, and these are the records the books are built from.
    const fills = [];
    for (const instType of INST_TYPES) {
      fills.push(...await rest.paginate("/api/v5/trade/fills-history", { instType },
        { cursorField: "billId", tsField: "ts", from: sinceTs }));
      await sleep(300);
    }
    fills.sort((a, b) => Number(a.ts) - Number(b.ts));
    console.log(`[${tag}] catch-up: ${fills.length} fill(s) since ${since}`);
    for (const f of fills) {
      console.log(`  [catchup/fill] ${new Date(Number(f.ts)).toISOString()} ${f.instId} ${f.side} px=${f.fillPx} sz=${f.fillSz} fee=${f.fee}${f.feeCcy} tradeId=${f.tradeId}`);
      recordEvent("catchup/fills-history", f);
    }

    for (const [name, path, label] of [
      ["deposit(s)", "/api/v5/asset/deposit-history", "catchup/deposit"],
      ["withdrawal(s)", "/api/v5/asset/withdrawal-history", "catchup/withdrawal"],
    ]) {
      const rows = await rest.paginate(path, {}, { cursorField: "ts", tsField: "ts", from: sinceTs });
      console.log(`[${tag}] catch-up: ${rows.length} ${name} since ${since}`);
      for (const r of rows) {
        console.log(`  [${label}] ${new Date(Number(r.ts)).toISOString()} ${r.ccy} ${r.amt} state=${r.state}`);
        recordEvent(label, r);
      }
      await sleep(300);
    }
    if (fills.length) await refreshFunding("reconciliation");
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
  let ws, pingTimer, pongTimeout, backoff = 1000, stopping = false;

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
        // Not gated on loggedInOnce any more: a watermark loaded from disk must be honoured on
        // the very first login, otherwise a restart silently skips the whole gap.
        const sinceTs = state.lastEventTs;
        console.log(`[${tag}] logged in, subscribing: ${subscribeArgs.map((a) => a.channel).join(", ")}`);
        ws.send(JSON.stringify({ op: "subscribe", args: subscribeArgs }));
        // The account snapshot that follows the subscribe replaces the map on its own,
        // so there is nothing to clear here.
        if (handlesOrders) reconcile(tag, sinceTs);
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
      // eventType / curPage / lastPage sit on the envelope, not inside data[] - without
      // forwarding them a paged snapshot is indistinguishable from an incremental update.
      const envelope = { eventType: msg.eventType, curPage: msg.curPage, lastPage: msg.lastPage };
      for (const item of msg.data ?? []) handleEvent(msg.arg?.channel, item, envelope);
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

function handleEvent(channel, d, envelope = {}) {
  const t = eventTime(d);
  recordEvent(channel, d, envelope);

  switch (channel) {
    case "orders": {
      const dup = duplicateReason(d);
      if (dup) { console.log(`[orders] ${t} DUPLICATE ignored - ${dup} ordId=${d.ordId}`); break; }
      const prev = state.orders.get(d.ordId);
      state.orders.set(d.ordId, d);
      if (TERMINAL_STATES.has(d.state)) evictTerminalOrders();
      console.log(`[orders] ${t} ${formatOrder(d)}`);
      // An amend that only moves the price, or a TP attached to an existing order, leaves
      // every other field untouched - without this diff the push reads as a duplicate line.
      for (const c of diffOrder(prev, d)) console.log(`  -> ${c}`);
      if (d.state === "filled") console.log("  -> ORDER FILLED - trigger your own operation here");
      break;
    }
    case "fills":
      console.log(`[fills] ${t} ${d.instId} ${d.side} px=${d.fillPx} sz=${d.fillSz} fee=${d.fee}${d.feeCcy ?? ""} tradeId=${d.tradeId}`);
      break;
    case "balance_and_position":
      console.log(`[bal&pos] ${t} ${formatBalancePosition(d)}`);
      // Only the trading side of a transfer reaches us; the Funding side has to be pulled.
      if (d.eventType && d.eventType !== "snapshot") refreshFunding(`bal&pos ${d.eventType}`);
      break;
    case "deposit-info":
    case "withdrawal-info":
      console.log(`[${channel}] ${t} ${JSON.stringify(d)}`);
      refreshFunding(channel);
      break;
    case "account": {
      // A snapshot may arrive in pages; only the complete set may replace the local map.
      const isSnapshot = envelope.eventType === "snapshot";
      if (isSnapshot && (envelope.curPage === undefined || envelope.curPage === 1)) {
        state.snapshotPages = [];
      }
      if (isSnapshot && Array.isArray(state.snapshotPages)) {
        state.snapshotPages.push(...(d.details ?? []));
        if (envelope.lastPage === false) break; // more pages coming, nothing to report yet
      }
      const details = isSnapshot && Array.isArray(state.snapshotPages) ? state.snapshotPages : d.details;
      const folded = foldBalances(state.balances, details, { replace: isSnapshot });
      state.balances = folded.balances;
      const changes = folded.changes;
      if (isSnapshot) state.snapshotPages = null;
      // U19: most account pushes are pure revaluation of totalEq with no balance movement.
      if (cfg.quiet && changes.length === 0) break;
      const merged = [...state.balances.entries()]
        .map(([ccy, b]) => `${ccy}=${b.cashBal}(avail=${b.availBal} frozen=${b.frozenBal})`)
        .join(" ");
      console.log(`[account] ${t} ${envelope.eventType ?? "?"} totalEq=${d.totalEq} ${merged}`);
      // U3: the field that moves when an order is placed is frozenBal, never cashBal.
      for (const c of changes) {
        const from = c.prev ? `${c.prev.cashBal}/${c.prev.availBal}/${c.prev.frozenBal}` : "(new)";
        const to = c.next ? `${c.next.cashBal}/${c.next.availBal}/${c.next.frozenBal}` : "(zero balance, no longer sent)";
        console.log(`  -> ${c.ccy} cash/avail/frozen: ${from} -> ${to}`);
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
  // Final snapshot: the in-memory order book keyed by the OKX ordId, so the last known
  // state of every order seen during the session is visible without replaying the log.
  if (state.orders.size) {
    console.log(`\norder state at shutdown (${state.orders.size}, keyed by ordId):`);
    for (const o of state.orders.values()) console.log(`  ${formatOrder(o)}`);
  }
  saveWatermark();
  console.log(`\nshutting down (${connections.length} connection(s))...`);
  for (const c of connections) c.stop();
  setTimeout(() => process.exit(code), 150).unref();
}
process.on("SIGINT", () => shutdown(0));
process.on("SIGTERM", () => shutdown(0));

loadWatermark();
// Periodic save as well as on shutdown: a SIGKILL or a crash would otherwise lose the window.
setInterval(saveWatermark, 30_000).unref();
if (cfg.outEvents) console.log(`recording events to ${cfg.outEvents}`);
for (const [endpoint, subscribeArgs] of Object.entries(byEndpoint)) {
  if (subscribeArgs.length) createConnection(endpoint, subscribeArgs);
}
