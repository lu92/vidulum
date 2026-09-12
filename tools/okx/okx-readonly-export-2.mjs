#!/usr/bin/env node
/**
 * OKX read-only export
 * ---------------------
 * Pobiera: userId (uid/mainUid/perm), saldo Trading + Funding, historię wpłat,
 * wypłat, transakcji (fills) i dziennik konta (bills) w zadanym oknie czasu.
 *
 * Wymaga Node.js >= 18 (natywne fetch + crypto). Brak zależności npm.
 *
 * Profile (--profile prod|demo, domyślnie prod):
 *   prod  -> OKX_KEY,      OKX_SECRET,      OKX_PASSPHRASE,      OKX_DOMAIN      (klucz live)
 *   demo  -> OKX_DEMO_KEY, OKX_DEMO_SECRET, OKX_DEMO_PASSPHRASE, OKX_DEMO_DOMAIN (klucz Demo Trading,
 *            automatycznie dodaje nagłówek x-simulated-trading: 1)
 *
 * Użycie:
 *   OKX_KEY=... OKX_SECRET=... OKX_PASSPHRASE=... OKX_DOMAIN=eea.okx.com \
 *   node okx-readonly-export.mjs --from 2026-01-01 --to 2026-09-06 --out export.json
 *
 *   OKX_DEMO_KEY=... OKX_DEMO_SECRET=... OKX_DEMO_PASSPHRASE=... \
 *   node okx-readonly-export.mjs --profile demo --out demo.json
 *
 * Argumenty (wszystkie opcjonalne, sekrety lepiej podawać przez env):
 *   --profile prod|demo             wybór zestawu zmiennych środowiskowych (domyślnie prod)
 *   --key, --secret, --passphrase   nadpisują zmienne env wybranego profilu
 *   --domain <host>                 eea.okx.com (UE, konto z my.okx.com) | openapi.okx.com (domyślnie)
 *   --from <YYYY-MM-DD>             początek okna (domyślnie 90 dni wstecz)
 *   --to <YYYY-MM-DD>               koniec okna (domyślnie teraz)
 *   --out <plik.json>               zapis wyniku do pliku (domyślnie tylko stdout)
 *   --inst-types SPOT,SWAP,...      typy instrumentów dla fills (domyślnie SPOT,MARGIN,SWAP,FUTURES,OPTION)
 *   --skip-bills                    pomiń dziennik konta (bills-archive) – bywa duży
 *   --demo                          skrót dla --profile demo
 *   --verbose                       loguj każde wywołanie HTTP
 */

import { createHmac } from "node:crypto";
import { writeFile } from "node:fs/promises";

// ---------- argumenty ----------
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

// ---------- profil ----------
const PROFILES = {
  prod: { envPrefix: "OKX_", simulated: false },
  demo: { envPrefix: "OKX_DEMO_", simulated: true },
};
const profileName = args.demo ? "demo" : (args.profile ?? process.env.OKX_PROFILE ?? "prod");
const profile = PROFILES[profileName];
if (!profile) {
  console.error(`Nieznany profil "${profileName}". Dostępne: ${Object.keys(PROFILES).join(", ")}.`);
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
  console.error(`Brak poświadczeń dla profilu "${profileName}". Ustaw ${p}KEY, ${p}SECRET, ${p}PASSPHRASE (env) lub --key/--secret/--passphrase.`);
  process.exit(1);
}
console.error(`Profil: ${profileName} (${cfg.domain}${cfg.demo ? ", x-simulated-trading" : ""})`);
if (Number.isNaN(cfg.from) || Number.isNaN(cfg.to)) {
  console.error("Zły format daty. Użyj YYYY-MM-DD.");
  process.exit(1);
}

// ---------- klient HTTP z podpisem ----------
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
    if (retries <= 0) throw new Error(`Rate limit na ${requestPath}`);
    await sleep(1500);
    return get(path, params, { retries: retries - 1 });
  }
  if (!res.ok || json.code !== "0") {
    throw new Error(`OKX ${requestPath} -> HTTP ${res.status}, code=${json.code}, msg=${json.msg}`);
  }
  return json.data;
}

/**
 * Paginacja wstecz. OKX zwraca rekordy od najnowszych; parametr `after`
 * oznacza "rekordy STARSZE niż podana wartość" (billId lub timestamp ms).
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

// ---------- pobieranie ----------
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

  // 1. Konfiguracja konta -> uid, mainUid, uprawnienia klucza
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
    console.error(`UWAGA: klucz ma uprawnienia "${config.perm}", a nie tylko read_only!`);
  }

  // 2. Salda: Trading + Funding
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

  // 3. Wpłaty (paginacja po timestamp)
  result.deposits = await paginate("/api/v5/asset/deposit-history", {}, { cursorField: "ts", tsField: "ts" });
  await sleep(250);

  // 4. Wypłaty (paginacja po timestamp)
  result.withdrawals = await paginate("/api/v5/asset/withdrawal-history", {}, { cursorField: "ts", tsField: "ts" });
  await sleep(250);

  // 5. Fills (transakcje) – ostatnie 3 miesiące, per instType, paginacja po billId
  result.fills = [];
  for (const instType of cfg.instTypes) {
    const rows = await paginate("/api/v5/trade/fills-history", { instType }, { cursorField: "billId", tsField: "ts" });
    result.fills.push(...rows);
    await sleep(500); // 5 req / 2 s
  }
  result.fills.sort((a, b) => Number(b.ts) - Number(a.ts));

  // 6. Dziennik konta tradingowego (3 miesiące) – wszystkie zmiany salda
  if (!cfg.skipBills) {
    result.tradingBills = await paginate("/api/v5/account/bills-archive", {}, { cursorField: "billId", tsField: "ts", minDelayMs: 450 });
    await sleep(250);
    // Dziennik konta Funding (1 miesiąc) – transfery, wpłaty, wypłaty
    result.fundingBills = await paginate("/api/v5/asset/bills", {}, { cursorField: "billId", tsField: "ts", minDelayMs: 450 });
  }

  result.meta.durationMs = Date.now() - t0;

  // ---------- podsumowanie ----------
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
    tradingBills: result.tradingBills?.length ?? "(pominięto)",
    fundingBills: result.fundingBills?.length ?? "(pominięto)",
  };
  console.error("\n=== PODSUMOWANIE ===");
  console.error(JSON.stringify(summary, null, 2));

  const json = JSON.stringify(result, null, 2);
  if (cfg.out) {
    await writeFile(cfg.out, json, "utf8");
    console.error(`\nZapisano ${cfg.out} (${(json.length / 1024).toFixed(1)} KB)`);
  } else {
    process.stdout.write(json + "\n");
  }
}

main().catch((err) => {
  console.error("BŁĄD:", err.message);
  process.exit(2);
});
