#!/usr/bin/env node
/**
 * Shared helpers for the OKX read-only tools
 * ------------------------------------------
 * Argument parsing, profile resolution, region hosts and a signed REST client.
 * Kept in one place so okx-readonly-export.mjs and okx-ws-listener.mjs cannot drift apart.
 *
 * Secrets are read from the environment only (see .env.example). They are deliberately
 * NOT accepted as command-line arguments: argv is visible to any user via `ps` and ends
 * up in shell history.
 */

import { createHmac } from "node:crypto";

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Minimal `--flag value` / `--flag` parser. */
export function parseArgs(argv) {
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

/** Prints usage and exits 0 when --help/-h is present. Call before any credential check. */
export function maybePrintHelp(args, usage) {
  if (args.help || args.h) {
    console.log(usage.trim());
    process.exit(0);
  }
}

export const PROFILES = {
  prod: { envPrefix: "OKX_", simulated: false },
  demo: { envPrefix: "OKX_DEMO_", simulated: true },
};

/** WebSocket hosts per region. REST hosts come from OKX_DOMAIN / OKX_DEMO_DOMAIN. */
export const WS_HOSTS = {
  global: { live: "ws.okx.com", demo: "wspap.okx.com" },
  eea: { live: "wseea.okx.com", demo: "wseeapap.okx.com" },
  us: { live: "wsus.okx.com", demo: "wsuspap.okx.com" },
};

export const INST_TYPES = ["SPOT", "MARGIN", "SWAP", "FUTURES", "OPTION"];

/**
 * Error codes that mean "these credentials will never work here".
 * Retrying them only burns rate limit, so callers should exit instead of reconnecting.
 */
export const FATAL_AUTH_CODES = new Set([
  "50105", // passphrase incorrect
  "50111", // invalid API key
  "50113", // invalid signature
  "50119", // API key doesn't exist (usually the wrong region)
  "60009", // WS login failed
  "60032", // WS API key doesn't exist (usually the wrong region)
]);

/**
 * Resolves the profile from --profile/--demo/OKX_PROFILE and loads its credentials.
 * Exits with a readable message when anything is missing.
 */
export function resolveProfile(args) {
  const name = args.demo ? "demo" : (args.profile ?? process.env.OKX_PROFILE ?? "prod");
  const profile = PROFILES[name];
  if (!profile) {
    console.error(`Unknown profile "${name}". Available: ${Object.keys(PROFILES).join(", ")}.`);
    process.exit(1);
  }
  const env = (k) => process.env[profile.envPrefix + k];
  const key = env("KEY"), secret = env("SECRET"), passphrase = env("PASSPHRASE");
  if (!key || !secret || !passphrase) {
    const p = profile.envPrefix;
    console.error(`Missing credentials for profile "${name}". Set ${p}KEY, ${p}SECRET and ${p}PASSPHRASE.`);
    console.error(`They are loaded from .env.${name} via "node --env-file"; secrets cannot be passed as arguments.`);
    console.error(`Hint: a passphrase containing # or spaces MUST be quoted in the .env file.`);
    process.exit(1);
  }
  return {
    name, env, key, secret, passphrase,
    simulated: profile.simulated,
    domain: args.domain ?? env("DOMAIN") ?? "openapi.okx.com",
  };
}

/** Signed read-only REST client. Only GET is implemented - these tools never write. */
export function createRestClient({ key, secret, passphrase, domain, demo = false, verbose = false }) {
  const sign = (timestamp, method, requestPath, body = "") =>
    createHmac("sha256", secret)
      .update(timestamp + method.toUpperCase() + requestPath + body)
      .digest("base64");

  async function get(path, params = {}, { retries = 3 } = {}) {
    const qs = new URLSearchParams(
      Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== "")
    ).toString();
    const requestPath = qs ? `${path}?${qs}` : path;
    const timestamp = new Date().toISOString();
    const headers = {
      "OK-ACCESS-KEY": key,
      "OK-ACCESS-SIGN": sign(timestamp, "GET", requestPath),
      "OK-ACCESS-TIMESTAMP": timestamp,
      "OK-ACCESS-PASSPHRASE": passphrase,
      "Content-Type": "application/json",
    };
    if (demo) headers["x-simulated-trading"] = "1";

    if (verbose) console.error(`GET ${requestPath}`);
    const res = await fetch(`https://${domain}${requestPath}`, { headers });
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
   * Backwards pagination. OKX returns records newest-first and `after` means
   * "records OLDER than this cursor value" (billId, ordId or a timestamp in ms).
   *
   * Guards against two failure modes:
   *  - a cursor that stops advancing (e.g. 100 records sharing one `ts`) would loop forever,
   *  - a runaway result set is capped by maxPages.
   *
   * Pass `from`/`to` (ms) to keep only rows inside the window and stop once the page
   * runs older than `from`. Omit them to fetch everything the endpoint returns.
   */
  async function paginate(path, baseParams, {
    cursorField, tsField, limit = 100, minDelayMs = 250, from, to, maxPages = 200,
  }) {
    const all = [];
    let after, pages = 0, prevCursor;
    for (;;) {
      if (++pages > maxPages) {
        console.error(`WARNING: ${path} hit the ${maxPages}-page cap - results may be truncated.`);
        break;
      }
      const page = await get(path, { ...baseParams, after, limit: String(limit) });
      if (!page.length) break;
      for (const row of page) {
        const ts = tsField ? Number(row[tsField]) : undefined;
        if (ts === undefined || ((from === undefined || ts >= from) && (to === undefined || ts <= to))) {
          all.push(row);
        }
      }
      const last = page[page.length - 1];
      if (tsField && from !== undefined && Number(last[tsField]) < from) break;
      if (page.length < limit) break;

      const cursor = last[cursorField];
      if (cursor === undefined || cursor === prevCursor) {
        console.error(`WARNING: ${path} cursor stopped advancing (${cursorField}=${cursor}) - stopping early.`);
        break;
      }
      prevCursor = cursor;
      after = cursor;
      await sleep(minDelayMs);
    }
    return all;
  }

  return { get, paginate };
}

// ---------- order rendering ----------

/**
 * Renders the protective orders attached to a parent order.
 *
 * OKX puts take-profit / stop-loss in TWO different places depending on how the order was
 * created, and returns both shapes on every order:
 *   - attachAlgoOrds[]  - current style (UI and newer API); more than one entry is possible
 *   - top-level tpTriggerPx / slTriggerPx / slOrdPx / ... - legacy style, populated only
 *     when the order was created that way
 * Reading just one of them silently drops stop-losses, so both are handled.
 *
 * A trailing stop is a stop-loss too - it arrives as callbackRatio / callbackSpread rather
 * than a trigger price, and would otherwise render as an empty bracket.
 */
export function formatProtection(d) {
  const price = (p) => (p === "-1" ? "market" : p);

  // `includeSz` only applies to attached entries, where sz is the protected quantity.
  // On the parent order sz is the order size and must not be reported as protection.
  const leg = (a, includeSz) => {
    const bits = [];
    if (a.tpTriggerPx) bits.push(`tp@${a.tpTriggerPx}${a.tpTriggerPxType ? `(${a.tpTriggerPxType})` : ""}->${price(a.tpOrdPx)}`);
    if (a.slTriggerPx) bits.push(`sl@${a.slTriggerPx}${a.slTriggerPxType ? `(${a.slTriggerPxType})` : ""}->${price(a.slOrdPx)}`);
    if (a.callbackRatio) bits.push(`trailing ${(Number(a.callbackRatio) * 100).toFixed(2)}%`);
    else if (a.callbackSpread) bits.push(`trailing spread ${a.callbackSpread}`);
    if (a.activePx) bits.push(`active@${a.activePx}`);
    if (includeSz && a.sz) bits.push(`sz=${a.sz}`);
    if (a.failCode && a.failCode !== "0") bits.push(`FAILED ${a.failCode}${a.failReason ? ` (${a.failReason})` : ""}`);
    if (!bits.length) return null;
    return bits.join(" ") + (a.attachAlgoId ? ` algoId=${a.attachAlgoId}` : "");
  };

  const parts = [];
  for (const a of d.attachAlgoOrds ?? []) {
    const rendered = leg(a, true);
    if (rendered) parts.push(rendered);
  }
  // Legacy placement - only when attachAlgoOrds did not already describe the protection.
  if (!parts.length) {
    const inline = leg(d, false);
    if (inline) parts.push(inline);
  }
  if (d.linkedAlgoOrd?.algoId) parts.push(`linkedAlgo=${d.linkedAlgoOrd.algoId}`);

  return parts.length ? ` [${parts.join(" | ")}]` : "";
}

/** One-line rendering shared by the orders channel and the REST reconciliation. */
export function formatOrder(d) {
  return `${d.instId} ${d.side} ${d.ordType ?? "?"} state=${d.state} filled=${d.accFillSz}/${d.sz}`
       + ` px=${d.px || "-"} avgPx=${d.avgPx || "-"} ordId=${d.ordId}${formatProtection(d)}`;
}

/** Fields worth reporting when the same ordId is pushed again (amend, fill, TP/SL attach). */
export const ORDER_DIFF_FIELDS = ["state", "sz", "px", "ordType", "accFillSz", "avgPx", "reduceOnly"];

/**
 * Diffs a push against the previously seen version of the same order. Without this an amend
 * that only moves the price, or a stop-loss attached to a live order, is indistinguishable
 * from a duplicate line.
 */
export function diffOrder(prev, next) {
  if (!prev) return [];
  const changes = [];
  for (const f of ORDER_DIFF_FIELDS) {
    if ((prev[f] ?? "") !== (next[f] ?? "")) changes.push(`${f}: ${prev[f] || "-"} -> ${next[f] || "-"}`);
  }
  const before = formatProtection(prev), after = formatProtection(next);
  if (before !== after) changes.push(`protection:${before || " (none)"} ->${after || " (none)"}`);
  return changes;
}
