#!/usr/bin/env node
/**
 * OKX read-only export
 * ---------------------
 * Fetches a full read-only snapshot of an account: uid/permissions, Trading + Funding
 * balances, open orders, order history, positions, deposits, withdrawals, fills and the
 * account bills journal for a given time window.
 *
 * Requires Node.js >= 18 (native fetch + crypto). No npm dependencies.
 */

import { writeFile } from "node:fs/promises";
import {
  parseArgs, maybePrintHelp, resolveProfile, createRestClient, sleep, INST_TYPES,
} from "./okx-common.mjs";

const USAGE = `
OKX read-only export

Usage:
  node --env-file=.env.demo okx-readonly-export.mjs --profile demo --out demo.json
  node --env-file=.env.prod okx-readonly-export.mjs --from 2026-01-01 --to 2026-06-30 --out h1.json

Profiles (credentials are read from the environment only - never from arguments):
  prod  -> OKX_KEY,      OKX_SECRET,      OKX_PASSPHRASE,      OKX_DOMAIN
  demo  -> OKX_DEMO_KEY, OKX_DEMO_SECRET, OKX_DEMO_PASSPHRASE, OKX_DEMO_DOMAIN
           (adds the x-simulated-trading: 1 header automatically)

Options:
  --profile prod|demo        which set of environment variables to use (default: prod)
  --demo                     shorthand for --profile demo
  --domain <host>            eea.okx.com (EU, my.okx.com account) | openapi.okx.com (default)
  --from <YYYY-MM-DD>        window start (default: 90 days ago)
  --to <YYYY-MM-DD>          window end (default: now)
  --days <N>                 shorthand for "the last N days"; overrides --from/--to
  --out <file.json>          write the result to a file (default: stdout)
  --inst-types SPOT,SWAP,... instrument types for fills and order history
                             (default: ${INST_TYPES.join(",")})
  --skip-bills               skip the account journal (bills-archive) - it can be large
  --skip-orders              skip open orders, order history and positions
  --verbose                  log every HTTP call
  --help                     show this message and exit

Retention limits enforced by OKX (not by this script):
  fills-history, bills-archive, orders-history-archive -> 3 months
  asset/bills (Funding journal)                        -> 1 month
`;

const args = parseArgs(process.argv.slice(2));
maybePrintHelp(args, USAGE);

const profile = resolveProfile(args);

const DAY_MS = 86400_000;
const now = Date.now();
const days = args.days !== undefined && args.days !== true ? Number(args.days) : undefined;
if (days !== undefined && (!Number.isFinite(days) || days <= 0)) {
  console.error("--days must be a positive number.");
  process.exit(1);
}

const cfg = {
  profile: profile.name,
  domain: profile.domain,
  demo: profile.simulated,
  from: days !== undefined ? now - days * DAY_MS
      : args.from ? Date.parse(args.from + "T00:00:00Z")
      : now - 90 * DAY_MS,
  to: days !== undefined ? now
     : args.to ? Date.parse(args.to + "T23:59:59.999Z")
     : now,
  out: args.out,
  instTypes: (args["inst-types"] ?? INST_TYPES.join(",")).split(","),
  skipBills: !!args["skip-bills"],
  skipOrders: !!args["skip-orders"],
  verbose: !!args.verbose,
};

if (Number.isNaN(cfg.from) || Number.isNaN(cfg.to)) {
  console.error("Invalid date format. Use YYYY-MM-DD.");
  process.exit(1);
}
if (cfg.from > cfg.to) {
  console.error("--from is later than --to.");
  process.exit(1);
}

console.error(`Profile: ${cfg.profile} (${cfg.domain}${cfg.demo ? ", x-simulated-trading" : ""})`);

// Warn when the requested window reaches past what OKX still serves, so partial data
// is never mistaken for a complete history.
const THREE_MONTHS_MS = 92 * DAY_MS;
const ONE_MONTH_MS = 31 * DAY_MS;
if (cfg.from < now - THREE_MONTHS_MS) {
  const since = new Date(cfg.from).toISOString().slice(0, 10);
  console.error(`WARNING: --from ${since} reaches past OKX retention limits. Expect gaps:`);
  console.error(`  fills, trading bills, order history -> only the last ~3 months are returned`);
  console.error(`  funding bills                       -> only the last ~1 month is returned`);
  console.error(`  Older data is available only through the quarterly archive (see OKX-CONTEXT.md).`);
} else if (cfg.from < now - ONE_MONTH_MS) {
  console.error(`NOTE: funding bills (asset/bills) only cover the last ~1 month.`);
}

const rest = createRestClient({
  key: profile.key, secret: profile.secret, passphrase: profile.passphrase,
  domain: cfg.domain, demo: cfg.demo, verbose: cfg.verbose,
});

/** Runs an optional section without letting one unsupported endpoint abort the whole export. */
async function section(name, fn) {
  try {
    return await fn();
  } catch (err) {
    console.error(`WARNING: ${name} failed: ${err.message}`);
    return { error: err.message };
  }
}

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
  const [config] = await rest.get("/api/v5/account/config");
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
  const [trading] = await rest.get("/api/v5/account/balance");
  result.balances = {
    trading: {
      totalEqUsd: trading.totalEq,
      updatedAt: trading.uTime,
      assets: (trading.details ?? []).map((d) => ({
        ccy: d.ccy, equity: d.eq, cash: d.cashBal, available: d.availBal,
        frozen: d.frozenBal, eqUsd: d.eqUsd, unrealizedPnl: d.upl,
      })),
    },
    funding: (await rest.get("/api/v5/asset/balances")).map((b) => ({
      ccy: b.ccy, balance: b.bal, available: b.availBal, frozen: b.frozenBal,
    })),
  };
  await sleep(250);

  // 3. Open orders and positions - current state, deliberately NOT time-filtered:
  //    a live order created before --from still locks funds today.
  if (!cfg.skipOrders) {
    result.openOrders = await section("open orders", () =>
      rest.paginate("/api/v5/trade/orders-pending", {}, { cursorField: "ordId" }));
    await sleep(250);

    result.positions = await section("positions", async () =>
      (await rest.get("/api/v5/account/positions")).map((p) => ({
        instId: p.instId, instType: p.instType, posSide: p.posSide, pos: p.pos,
        avgPx: p.avgPx, upl: p.upl, lever: p.lever, mgnMode: p.mgnMode,
        liqPx: p.liqPx, notionalUsd: p.notionalUsd, uTime: p.uTime,
      })));
    await sleep(250);

    // Historical orders (3 months), per instType, paginated by ordId and filtered by cTime.
    result.orders = await section("order history", async () => {
      const rows = [];
      for (const instType of cfg.instTypes) {
        rows.push(...await rest.paginate("/api/v5/trade/orders-history-archive", { instType },
          { cursorField: "ordId", tsField: "cTime", from: cfg.from, to: cfg.to }));
        await sleep(500);
      }
      return rows.sort((a, b) => Number(b.cTime) - Number(a.cTime));
    });
    await sleep(250);
  }

  // 4. Deposits (paginated by timestamp)
  result.deposits = await rest.paginate("/api/v5/asset/deposit-history", {},
    { cursorField: "ts", tsField: "ts", from: cfg.from, to: cfg.to });
  await sleep(250);

  // 5. Withdrawals (paginated by timestamp)
  result.withdrawals = await rest.paginate("/api/v5/asset/withdrawal-history", {},
    { cursorField: "ts", tsField: "ts", from: cfg.from, to: cfg.to });
  await sleep(250);

  // 6. Fills (trades) - last 3 months, per instType, paginated by billId
  result.fills = [];
  for (const instType of cfg.instTypes) {
    const rows = await rest.paginate("/api/v5/trade/fills-history", { instType },
      { cursorField: "billId", tsField: "ts", from: cfg.from, to: cfg.to });
    result.fills.push(...rows);
    await sleep(500); // 5 req / 2 s
  }
  result.fills.sort((a, b) => Number(b.ts) - Number(a.ts));

  // 7. Trading account journal (3 months) - every balance change
  if (!cfg.skipBills) {
    result.tradingBills = await rest.paginate("/api/v5/account/bills-archive", {},
      { cursorField: "billId", tsField: "ts", from: cfg.from, to: cfg.to, minDelayMs: 450 });
    await sleep(250);
    // Funding account journal (1 month) - transfers, deposits, withdrawals
    result.fundingBills = await rest.paginate("/api/v5/asset/bills", {},
      { cursorField: "billId", tsField: "ts", from: cfg.from, to: cfg.to, minDelayMs: 450 });
  }

  result.meta.durationMs = Date.now() - t0;

  // ---------- summary ----------
  const sumBy = (rows, key) => rows.reduce((acc, r) => {
    acc[r.ccy] = (acc[r.ccy] ?? 0) + Number(r[key]);
    return acc;
  }, {});
  const count = (v) => Array.isArray(v) ? v.length : (v?.error ? `(failed: ${v.error})` : "(skipped)");
  const summary = {
    uid: result.account.uid,
    keyPermissions: result.account.keyPermissions,
    tradingTotalEqUsd: result.balances.trading.totalEqUsd,
    fundingAssets: result.balances.funding.length,
    openOrders: count(result.openOrders),
    positions: count(result.positions),
    orders: count(result.orders),
    deposits: { count: result.deposits.length, byCcy: sumBy(result.deposits, "amt") },
    withdrawals: {
      count: result.withdrawals.length,
      byCcy: sumBy(result.withdrawals, "amt"),
      feesByCcy: sumBy(result.withdrawals, "fee"),
    },
    fills: result.fills.length,
    tradingBills: count(result.tradingBills),
    fundingBills: count(result.fundingBills),
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
