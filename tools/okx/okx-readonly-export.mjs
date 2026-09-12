#!/usr/bin/env node
/**
 * OKX read-only export
 * ---------------------
 * Fetches: userId (uid/mainUid/perm), Trading + Funding balances, deposit history,
 * withdrawal history, fills and the account bills journal for a given time window.
 *
 * Requires Node.js >= 18 (native fetch + crypto). No npm dependencies.
 *
 * Profiles (--profile prod|demo, defaults to prod):
 *   prod  -> OKX_KEY,      OKX_SECRET,      OKX_PASSPHRASE,      OKX_DOMAIN      (live key)
 *   demo  -> OKX_DEMO_KEY, OKX_DEMO_SECRET, OKX_DEMO_PASSPHRASE, OKX_DEMO_DOMAIN (Demo Trading key,
 *            automatically adds the x-simulated-trading: 1 header)
 *
 * Usage:
 *   OKX_KEY=... OKX_SECRET=... OKX_PASSPHRASE=... OKX_DOMAIN=eea.okx.com \
 *   node okx-readonly-export.mjs --from 2026-01-01 --to 2026-09-06 --out export.json
 *
 *   OKX_DEMO_KEY=... OKX_DEMO_SECRET=... OKX_DEMO_PASSPHRASE=... \
 *   node okx-readonly-export.mjs --profile demo --out demo.json
 *
 * Arguments (all optional; prefer env vars for secrets):
 *   --profile prod|demo             which set of env vars to use (defaults to prod)
 *   --key, --secret, --passphrase   override the selected profile's env vars
 *   --domain <host>                 eea.okx.com (EU, my.okx.com account) | openapi.okx.com (default)
 *   --from <YYYY-MM-DD>             window start (defaults to 90 days ago)
 *   --to <YYYY-MM-DD>               window end (defaults to now)
 *   --out <file.json>               write the result to a file (defaults to stdout only)
 *   --inst-types SPOT,SWAP,...      instrument types for fills (defaults to SPOT,MARGIN,SWAP,FUTURES,OPTION)
 *   --skip-bills                    skip the account journal (bills-archive) - can be large
 *   --demo                          shorthand for --profile demo
 *   --verbose                       log every HTTP call
 */

import { createHmac } from "node:crypto";
import { writeFile } from "node:fs/promises";

// ---------- arguments ----------
function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith("--")) continue;
    const k = a.slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith("--")) out[k] = true;
    else { out[k] = next; i++; }
  }
  return out;
}

const args = parseArgs(process.argv.slice(2));

// ---------- profile ----------
const PROFILES = {
  prod: { envPrefix: "OKX_", simulated: false },
  demo: { envPrefix: "OKX_DEMO_", simulated: true },
};
const profileName = args.demo ? "demo" : (args.profile ?? process.env.OKX_PROFILE ?? "prod");
const profile = PROFILES[profileName];
if (!profile) {
  console.error(`Unknown profile "${profileName}". Available: ${Object.keys(PROFILES).join(", ")}.`);
  process.exit(1);
}
const env = (name) => process.env[profile.envPrefix + name];

const cfg = {
  profile: profileName,
  key: args.key ?? env("KEY"),
  secret: args.secret ?? env("SECRET"),
  passphrase: args.passphrase ?? env("PASSPHRASE"),
  domain: args.domain ?? env("DOMAIN") ?? "openapi.okx.com",
  from: args.from ? Date.parse(args.from + "T00:00:00Z") : Date.now() - 90 * 86400_000,
  to: args.to ? Date.parse(args.to + "T23:59:59.999Z") : Date.now(),
  out: args.out,
  instTypes: (args["inst-types"] ?? "SPOT,MARGIN,SWAP,FUTURES,OPTION").split(","),
  skipBills: !!args["skip-bills"],
  demo: profile.simulated,
  verbose: !!args.verbose,
};

if (!cfg.key || !cfg.secret || !cfg.passphrase) {
  const p = profile.envPrefix;
  console.error(`Missing credentials for profile "${profileName}". Set ${p}KEY, ${p}SECRET, ${p}PASSPHRASE (env) or use --key/--secret/--passphrase.`);
  process.exit(1);
}
console.error(`Profile: ${profileName} (${cfg.domain}${cfg.demo ? ", x-simulated-trading" : ""})`);
if (Number.isNaN(cfg.from) || Number.isNaN(cfg.to)) {
  console.error("Invalid date format. Use YYYY-MM-DD.");
  process.exit(1);
}

// ---------- signed HTTP client ----------
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function sign(timestamp, method, requestPath, body = "") {
  const prehash = timestamp + method.toUpperCase() + requestPath + body;
  return createHmac("sha256", cfg.secret).update(prehash).digest("base64");
}

async function get(path, params = {}, { retries = 3 } = {}) {
  const qs = new URLSearchParams(
    Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== "")
  ).toString();
  const requestPath = qs ? `${path}?${qs}` : path;
  const timestamp = new Date().toISOString();
  const headers = {
    "OK-ACCESS-KEY": cfg.key,
    "OK-ACCESS-SIGN": sign(timestamp, "GET", requestPath),
    "OK-ACCESS-TIMESTAMP": timestamp,
    "OK-ACCESS-PASSPHRASE": cfg.passphrase,
    "Content-Type": "application/json",
  };
  if (cfg.demo) headers["x-simulated-trading"] = "1";

  if (cfg.verbose) console.error(`GET ${requestPath}`);
  const res = await fetch(`https://${cfg.domain}${requestPath}`, { headers });
  const json = await res.json().catch(() => ({}));

  if (res.status === 429 || json.code === "50011") {
    if (retries <= 0) throw new Error(`Rate limit on ${requestPath}`);
    await sleep(1500);
    return get(path, params, { retries: retries - 1 });
  }
  if (!res.ok || json.code !== "0") {
    throw new Error(`OKX ${requestPath} -> HTTP ${res.status}, code=${json.code}, msg=${json.msg}`);
  }
  return json.data;
}

/**
 * Backwards pagination. OKX returns records newest-first; the `after` parameter
 * means "records OLDER than the given value" (billId or timestamp in ms).
 */
async function paginate(path, baseParams, { cursorField, tsField, limit = 100, minDelayMs = 250 }) {
  const all = [];
  let after;
  for (;;) {
    const page = await get(path, { ...baseParams, after, limit: String(limit) });
    if (!page.length) break;
    for (const row of page) {
      const ts = Number(row[tsField]);
      if (ts >= cfg.from && ts <= cfg.to) all.push(row);
    }
    const last = page[page.length - 1];
    if (Number(last[tsField]) < cfg.from) break;
    if (page.length < limit) break;
    after = last[cursorField];
    await sleep(minDelayMs);
  }
  return all;
}

// ---------- fetching ----------
async function main() {
  const t0 = Date.now();
  const result = {
    meta: {
      profile: cfg.profile,
      domain: cfg.domain,
      demo: cfg.demo,
      from: new Date(cfg.from).toISOString(),
      to: new Date(cfg.to).toISOString(),
      generatedAt: new Date().toISOString(),
    },
  };

  // 1. Account config -> uid, mainUid, key permissions
  const [config] = await get("/api/v5/account/config");
  result.account = {
    uid: config.uid,
    mainUid: config.mainUid,
    isSubAccount: config.uid !== config.mainUid,
    keyPermissions: config.perm,
    keyLabel: config.label,
    boundIps: config.ip || null,
    accountMode: config.acctLv,
    kycLevel: config.kycLv,
  };
  if (config.perm !== "read_only") {
    console.error(`WARNING: this key has "${config.perm}" permissions, not read_only only!`);
  }

  // 2. Balances: Trading + Funding
  const [trading] = await get("/api/v5/account/balance");
  result.balances = {
    trading: {
      totalEqUsd: trading.totalEq,
      updatedAt: trading.uTime,
      assets: (trading.details ?? []).map((d) => ({
        ccy: d.ccy, equity: d.eq, cash: d.cashBal, available: d.availBal,
        frozen: d.frozenBal, eqUsd: d.eqUsd, unrealizedPnl: d.upl,
      })),
    },
    funding: (await get("/api/v5/asset/balances")).map((b) => ({
      ccy: b.ccy, balance: b.bal, available: b.availBal, frozen: b.frozenBal,
    })),
  };
  await sleep(250);

  // 3. Deposits (paginated by timestamp)
  result.deposits = await paginate("/api/v5/asset/deposit-history", {}, { cursorField: "ts", tsField: "ts" });
  await sleep(250);

  // 4. Withdrawals (paginated by timestamp)
  result.withdrawals = await paginate("/api/v5/asset/withdrawal-history", {}, { cursorField: "ts", tsField: "ts" });
  await sleep(250);

  // 5. Fills (trades) - last 3 months, per instType, paginated by billId
  result.fills = [];
  for (const instType of cfg.instTypes) {
    const rows = await paginate("/api/v5/trade/fills-history", { instType }, { cursorField: "billId", tsField: "ts" });
    result.fills.push(...rows);
    await sleep(500); // 5 req / 2 s
  }
  result.fills.sort((a, b) => Number(b.ts) - Number(a.ts));

  // 6. Trading account journal (3 months) - every balance change
  if (!cfg.skipBills) {
    result.tradingBills = await paginate("/api/v5/account/bills-archive", {}, { cursorField: "billId", tsField: "ts", minDelayMs: 450 });
    await sleep(250);
    // Funding account journal (1 month) - transfers, deposits, withdrawals
    result.fundingBills = await paginate("/api/v5/asset/bills", {}, { cursorField: "billId", tsField: "ts", minDelayMs: 450 });
  }

  result.meta.durationMs = Date.now() - t0;

  // ---------- summary ----------
  const sumBy = (rows, key) => rows.reduce((acc, r) => {
    acc[r.ccy] = (acc[r.ccy] ?? 0) + Number(r[key]);
    return acc;
  }, {});
  const summary = {
    uid: result.account.uid,
    keyPermissions: result.account.keyPermissions,
    tradingTotalEqUsd: result.balances.trading.totalEqUsd,
    fundingAssets: result.balances.funding.length,
    deposits: { count: result.deposits.length, byCcy: sumBy(result.deposits, "amt") },
    withdrawals: {
      count: result.withdrawals.length,
      byCcy: sumBy(result.withdrawals, "amt"),
      feesByCcy: sumBy(result.withdrawals, "fee"),
    },
    fills: result.fills.length,
    tradingBills: result.tradingBills?.length ?? "(skipped)",
    fundingBills: result.fundingBills?.length ?? "(skipped)",
  };
  console.error("\n=== SUMMARY ===");
  console.error(JSON.stringify(summary, null, 2));

  const json = JSON.stringify(result, null, 2);
  if (cfg.out) {
    await writeFile(cfg.out, json, "utf8");
    console.error(`\nSaved ${cfg.out} (${(json.length / 1024).toFixed(1)} KB)`);
  } else {
    process.stdout.write(json + "\n");
  }
}

main().catch((err) => {
  console.error("ERROR:", err.message);
  process.exit(2);
});
