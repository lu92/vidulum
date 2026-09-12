#!/usr/bin/env node
/**
 * OKX private WebSocket listener
 * ------------------------------
 * Logs into the OKX private WebSocket and prints events from the orders, fills,
 * balance_and_position and account channels (+ optionally deposit-info / withdrawal-info).
 *
 * OKX splits channels across two endpoints:
 *   /ws/v5/private  -> orders, account, positions, balance_and_position, deposit-info, withdrawal-info
 *   /ws/v5/business -> fills (VIP5+ only, no instType), algo orders, grid
 * The script opens a separate connection for each endpoint it needs.
 *
 * Requires Node.js >= 22 (native WebSocket). On Node 18/20: `npm i ws` and uncomment the import.
 *
 * Profiles / regions:
 *   --profile prod|demo   (env: OKX_* / OKX_DEMO_*)
 *   --region global|eea|us  or env OKX_REGION / OKX_DEMO_REGION (defaults to global)
 *     global: ws.okx.com      / demo: wspap.okx.com
 *     eea:    wseea.okx.com   / demo: wseeapap.okx.com   (my.okx.com accounts)
 *     us:     wsus.okx.com    / demo: wsuspap.okx.com
 *   --ws-url wss://host:8443   overrides the base URL (without path), e.g. --ws-url wss://wseeapap.okx.com:8443
 *
 * Usage:
 *   node okx-ws-listener.mjs --profile demo --region eea
 *   node okx-ws-listener.mjs --profile prod --region eea --channels orders,fills,deposit-info
 */

// import WebSocket from "ws"; // <- Node 18/20
import { createHmac } from "node:crypto";

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, arr) => {
    if (a.startsWith("--")) acc.push([a.slice(2), arr[i + 1]?.startsWith("--") || arr[i + 1] === undefined ? true : arr[i + 1]]);
    return acc;
  }, [])
);

const HOSTS = {
  global: { live: "ws.okx.com", demo: "wspap.okx.com" },
  eea: { live: "wseea.okx.com", demo: "wseeapap.okx.com" },
  us: { live: "wsus.okx.com", demo: "wsuspap.okx.com" },
};
const PROFILES = { prod: { prefix: "OKX_", demo: false }, demo: { prefix: "OKX_DEMO_", demo: true } };

const profileName = args.demo ? "demo" : (args.profile ?? process.env.OKX_PROFILE ?? "prod");
const profile = PROFILES[profileName];
if (!profile) { console.error(`Unknown profile ${profileName}`); process.exit(1); }
const env = (n) => process.env[profile.prefix + n];

const region = args.region ?? env("REGION") ?? "global";
if (!HOSTS[region]) { console.error(`Unknown region ${region}. Available: ${Object.keys(HOSTS).join(", ")}`); process.exit(1); }

const base = (args["ws-url"] ?? env("WS_URL") ?? `wss://${HOSTS[region][profile.demo ? "demo" : "live"]}:8443`).replace(/\/ws\/v5\/.*$/, "");
const cfg = {
  key: env("KEY"), secret: env("SECRET"), passphrase: env("PASSPHRASE"),
  channels: (args.channels ?? "orders,balance_and_position,account").split(","),
};
if (!cfg.key || !cfg.secret || !cfg.passphrase) {
  console.error(`Missing ${profile.prefix}KEY / SECRET / PASSPHRASE`); process.exit(1);
}

// Which channels live on which endpoint
const BUSINESS_CHANNELS = new Set(["fills", "orders-algo", "algo-advance", "grid-orders-spot", "grid-orders-contract"]);
const NEEDS_INST_TYPE = new Set(["orders", "orders-algo", "algo-advance", "positions"]);

const byEndpoint = { private: [], business: [] };
for (const channel of cfg.channels) {
  const arg = NEEDS_INST_TYPE.has(channel) ? { channel, instType: "ANY" } : { channel };
  byEndpoint[BUSINESS_CHANNELS.has(channel) ? "business" : "private"].push(arg);
}

function loginPayload() {
  const timestamp = String(Math.floor(Date.now() / 1000)); // seconds, not ISO!
  const sign = createHmac("sha256", cfg.secret).update(timestamp + "GET" + "/users/self/verify").digest("base64");
  return { op: "login", args: [{ apiKey: cfg.key, passphrase: cfg.passphrase, timestamp, sign }] };
}

// ---------- one connection = one endpoint ----------
function createConnection(endpoint, subscribeArgs) {
  const url = `${base}/ws/v5/${endpoint}`;
  let ws, pingTimer, pongTimeout, backoff = 1000;
  const tag = `${profileName}/${endpoint}`;

  const resetPongTimeout = () => {
    clearTimeout(pongTimeout);
    pongTimeout = setTimeout(() => { console.warn(`[${tag}] no response for 50 s, closing`); ws.close(); }, 50_000);
  };
  const schedulePing = () => {
    clearInterval(pingTimer);
    pingTimer = setInterval(() => ws.readyState === 1 && ws.send("ping"), 20_000);
    resetPongTimeout();
  };

  const connect = () => {
    console.log(`[${tag}] connecting: ${url}`);
    ws = new WebSocket(url);
    ws.onopen = () => { backoff = 1000; ws.send(JSON.stringify(loginPayload())); schedulePing(); };
    ws.onmessage = ({ data }) => {
      resetPongTimeout();
      if (data === "pong") return;
      const msg = JSON.parse(data);
      if (msg.event === "login") {
        if (msg.code !== "0") { console.error(`[${tag}] login failed:`, msg); ws.close(); return; }
        console.log(`[${tag}] logged in, subscribing: ${subscribeArgs.map((a) => a.channel).join(", ")}`);
        ws.send(JSON.stringify({ op: "subscribe", args: subscribeArgs }));
        return;
      }
      if (msg.event === "subscribe") { console.log(`[${tag}] subscribed: ${msg.arg.channel}`); return; }
      if (msg.event === "error") { console.error(`[${tag}] WS error:`, msg); return; }
      if (msg.event === "notice") { console.warn(`[${tag}] notice:`, msg.msg); return; } // 64008 = server is about to disconnect
      for (const item of msg.data ?? []) handleEvent(msg.arg?.channel, item);
    };
    ws.onclose = (e) => {
      clearInterval(pingTimer); clearTimeout(pongTimeout);
      console.warn(`[${tag}] disconnected (${e.code}), reconnecting in ${backoff} ms`);
      setTimeout(connect, backoff);
      backoff = Math.min(backoff * 2, 30_000);
    };
    ws.onerror = (e) => console.error(`[${tag}] socket error:`, e.message ?? "");
  };
  connect();
}

// ---------- hook your own logic in here ----------
function handleEvent(channel, d) {
  const t = new Date(Number(d.uTime ?? d.ts ?? Date.now())).toISOString();
  switch (channel) {
    case "orders":
      console.log(`[orders] ${t} ${d.instId} ${d.side} state=${d.state} filled=${d.accFillSz}/${d.sz} avgPx=${d.avgPx} ordId=${d.ordId}`);
      if (d.state === "filled") console.log("  -> ORDER FILLED - trigger your own operation here");
      break;
    case "fills":
      console.log(`[fills] ${t} ${d.instId} ${d.side} px=${d.fillPx} sz=${d.fillSz} fee=${d.fee}${d.feeCcy ?? ""} tradeId=${d.tradeId}`);
      break;
    case "balance_and_position":
      console.log(`[bal&pos] ${t} eventType=${d.eventType}`, JSON.stringify(d.balData ?? []), JSON.stringify(d.posData ?? []));
      break;
    case "account":
      console.log(`[account] ${t} totalEq=${d.totalEq}`, (d.details ?? []).map((x) => `${x.ccy}=${x.cashBal}`).join(" "));
      break;
    default:
      console.log(`[${channel}]`, JSON.stringify(d));
  }
}

for (const [endpoint, subscribeArgs] of Object.entries(byEndpoint)) {
  if (subscribeArgs.length) createConnection(endpoint, subscribeArgs);
}
