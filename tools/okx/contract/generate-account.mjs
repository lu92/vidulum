#!/usr/bin/env node
/**
 * Regenerates okx-account-contract.mjs - the field contracts for the `account` and
 * `balance_and_position` channels.
 *
 * Same principle as the orders contract: the field list is DERIVED from the recorded frames in
 * fixtures/orders-lifecycle.json, so it cannot drift from what OKX actually sends. Descriptions
 * come from the OKX reference; notes carry what we established ourselves.
 *
 * `posData` has no recorded sample - the demo account holds no positions - so its fields come
 * from the reference alone and are marked verified:false.
 *
 * Usage:  npm run contract:generate
 */

import { readFileSync, writeFileSync } from "node:fs";

const FIXTURES = new URL("../fixtures/orders-lifecycle.json", import.meta.url);
const OUTPUT = new URL("../okx-account-contract.mjs", import.meta.url);
const fx = JSON.parse(readFileSync(FIXTURES, "utf8"));

const ACC = fx.account.map((a) => a.data);
const BP = fx.balanceAndPosition.map((b) => b.data);

// ---- OKX reference descriptions -------------------------------------------------------------
const ACCOUNT_DESC = {
  uTime: "The latest time account information was refreshed, Unix timestamp in milliseconds.",
  totalEq: "The total amount of equity in USD.",
  isoEq: "Isolated margin equity in USD. Futures mode / Multi-currency margin / Portfolio margin.",
  adjEq: "Adjusted / effective equity in USD - the net fiat value of assets that can provide margin under cross-margin mode, discounted per currency to balance market risk.",
  availEq: "Account level available equity, excluding currencies restricted by the collateralized borrowing limit. Multi-currency margin / Portfolio margin.",
  ordFroz: "Margin frozen for pending cross orders in USD.",
  imr: "Initial margin requirement in USD across all open positions and pending cross orders.",
  mmr: "Maintenance margin requirement in USD across all open positions and pending cross orders.",
  borrowFroz: "Potential borrowing IMR of the account in USD. empty outside Spot/Multi-currency/Portfolio margin.",
  mgnRatio: "Maintenance margin ratio in USD.",
  notionalUsd: "Notional value of positions in USD.",
  notionalUsdForBorrow: "Notional value for borrow in USD.",
  notionalUsdForSwap: "Notional value of Perpetual Futures positions in USD.",
  notionalUsdForFutures: "Notional value of Expiry Futures positions in USD.",
  notionalUsdForOption: "Notional value of Option positions in USD.",
  upl: "Cross-margin unrealized profit and loss at the account level in USD.",
  delta: "Delta in USD.",
  deltaLever: "Delta neutral strategy account level delta leverage: delta / totalEq.",
  deltaNeutralStatus: "Delta risk status. 0: normal, 1: transfer restricted, 2: delta reducing.",
  details: "Detailed asset information per currency.",
};

const DETAIL_DESC = {
  ccy: "Currency.", eq: "Equity of currency.", cashBal: "Cash balance.",
  uTime: "Update time, Unix timestamp in milliseconds.",
  isoEq: "Isolated margin equity of currency.", availEq: "Available equity of currency.",
  disEq: "Discount equity of currency in USD.",
  fixedBal: "Frozen balance for Dip Sniper and Peak Sniper.",
  availBal: "Available balance of currency.", frozenBal: "Frozen balance of currency.",
  ordFrozen: "Margin frozen for open orders.",
  liab: "Liabilities of currency, a positive value.",
  upl: "Sum of unrealized P&L of all margin and derivatives positions of currency.",
  uplLiab: "Liabilities due to unrealized loss of currency.",
  crossLiab: "Cross liabilities of currency.", isoLiab: "Isolated liabilities of currency.",
  rewardBal: "Trial fund balance.",
  mgnRatio: "Cross maintenance margin ratio of currency.",
  imr: "Cross initial margin requirement at the currency level.",
  mmr: "Cross maintenance margin requirement at the currency level.",
  interest: "Interest of currency, a positive value.",
  twap: "Risk indicator of forced repayment, levels 0 to 5; higher means more likely to trigger.",
  frpType: "Forced repayment type. 0: none, 1: user based, 2: platform based. Returned when twap >= 1.",
  maxLoan: "Maximum borrowable amount for the currency under current account conditions.",
  eqUsd: "Equity of currency in USD.",
  borrowFroz: "Potential borrowing IMR of currency in USD.",
  notionalLever: "Leverage of currency. Futures mode.",
  coinUsdPrice: "Price index of currency in USD.",
  stgyEq: "Total equity allocated to trading bots for the currency.",
  isoUpl: "Isolated unrealized profit and loss of currency.",
  spotInUseAmt: "Actual spot hedging amount in use. Portfolio margin.",
  clSpotInUseAmt: "User-defined spot hedging amount. Portfolio margin.",
  maxSpotInUseAmt: "System-calculated maximum spot hedging amount. Portfolio margin.",
  spotIsoBal: "Balance acquired through spot copy trading, including amounts frozen by open orders.",
  smtSyncEq: "Smart sync equity. Default 0, copy traders only.",
  spotCopyTradingEq: "Spot smart sync equity. Default 0, copy traders only.",
  spotBal: "Spot balance, in the unit of the currency.",
  openAvgPx: "Spot average cost price in USD.",
  accAvgPx: "Spot accumulated cost price in USD.",
  spotUpl: "Spot unrealized profit and loss in USD.",
  spotUplRatio: "Spot unrealized profit and loss ratio.",
  totalPnl: "Spot accumulated profit and loss in USD.",
  totalPnlRatio: "Spot accumulated profit and loss ratio.",
  colRes: "Platform level collateral restriction status. 0: not enabled, 1: not enabled but close to the limit, 2: enabled - the crypto cannot back new orders.",
  colBorrAutoConversion: "Risk indicator of auto conversion, levels 1-5; 0 means no current risk, 5 means conversion is under way.",
  collateralRestrict: "Platform level collateralized borrow restriction. Deprecated, use colRes.",
  collateralEnabled: "Whether collateral is enabled. Multi-currency margin.",
  autoLendStatus: "Auto lend status: unsupported, off, pending, active.",
  autoLendMtAmt: "Auto lend matched amount. 0 unless autoLendStatus is active.",
  autoLendAmt: null,
  autoStakingStatus: null,
};

const BP_DESC = {
  pTime: "Push time of both balance and position information, Unix timestamp in milliseconds.",
  eventType: "What triggered the push.",
  balData: "Balance data. Sent only when the account balance changed.",
  posData: "Position data. Sent only when a position changed.",
  trades: "Details of the trade behind the change.",
};
const BALDATA_DESC = {
  ccy: "Currency.", cashBal: "Cash balance.",
  uTime: "Update time, Unix timestamp in milliseconds.",
};
const POSDATA_DESC = {
  posId: "Position ID.", tradeId: "Last trade ID.", instId: "Instrument ID.",
  instType: "Instrument type.", mgnMode: "Margin mode: isolated or cross.",
  avgPx: "Average open price.", ccy: "Currency used for margin.",
  posSide: "Position side: long, short or net.",
  pos: "Quantity of positions. Under isolated margin a manual transfer can create a position with pos of 0.",
  baseBal: "Base currency balance. MARGIN quick margin mode only. Deprecated.",
  quoteBal: "Quote currency balance. MARGIN quick margin mode only. Deprecated.",
  posCcy: "Position currency. MARGIN positions only.",
  nonSettleAvgPx: "Non-settlement entry price, reflecting only opens and increases. FUTURES cross.",
  settledPnl: "Accumulated settled P&L calculated by settlement price. FUTURES cross.",
  uTime: "Update time, Unix timestamp in milliseconds.",
};
const TRADES_DESC = { instId: "Instrument ID.", tradeId: "Trade ID." };

// ---- our own notes ---------------------------------------------------------------------------
const NOTES = {
  "account.totalEq": "Recomputed from mark prices, so it moves on every heartbeat even when no balance changed. Never treat a change here as an account event.",
  "account.details": "Incremental: an event_update carries only the currencies the event touched, a snapshot carries every currency with a non-zero balance.",
  "account.uTime": "Server time of this refresh. The envelope's eventType, not this field, tells you whether the push is a snapshot or an increment.",
  "detail.cashBal": "Does NOT move when an order is placed - only availBal and frozenBal do. Watching cashBal alone makes order activity invisible.",
  "detail.availBal": "The field that actually drops when funds are locked by an order.",
  "detail.frozenBal": "The field that actually rises when funds are locked by an order.",
  "detail.autoLendAmt": "UNDOCUMENTED: arrives on the wire, absent from the OKX field list.",
  "detail.autoStakingStatus": "UNDOCUMENTED: arrives on the wire, absent from the OKX field list.",
  "bp.eventType": "Sits INSIDE data[], unlike the account channel where it sits on the envelope. Reading the wrong level returns undefined silently.",
  "bp.trades": "The only reliable link between a balance change and the fill that caused it: its tradeId matches the orders channel. Channel arrival order is not guaranteed, so timestamps cannot be used for correlation.",
  "bp.balData": "A Funding<->Trading transfer reports the TRADING side only. The Funding balance appears in no push and must be pulled from /api/v5/asset/balances.",
};

// ---- build -----------------------------------------------------------------------------------
const esc = (s) => s.replace(/\\/g, "\\\\").replace(/"/g, '\\"');

function table(name, samples, desc, notePrefix, extraKeys = []) {
  const seen = {};
  for (const row of samples) {
    for (const [k, v] of Object.entries(row)) {
      if (typeof v === "object") { (seen[k] ??= new Set()).add("__obj__"); continue; }
      if (v !== "") (seen[k] ??= new Set()).add(String(v));
    }
  }
  const keys = [...new Set([...(samples[0] ? Object.keys(samples[0]) : []), ...extraKeys])].sort();
  const missing = keys.filter((k) => !(k in desc));
  if (missing.length) { console.error(`${name}: no description for ${missing.join(", ")}`); process.exit(1); }

  let out = `export const ${name} = {\n`;
  for (const k of keys) {
    const vals = [...(seen[k] ?? [])].filter((v) => v !== "__obj__").sort();
    const isObj = (seen[k] ?? new Set()).has("__obj__");
    const verified = isObj || vals.length > 0;
    const example = isObj ? '"(object)"' : (vals[0] !== undefined ? `"${esc(vals[0])}"` : "null");
    const d = desc[k] === null ? "null" : `"${esc(desc[k])}"`;
    const note = NOTES[`${notePrefix}.${k}`];
    out += `  ${k}: { verified: ${verified}, example: ${example},\n    docs: ${d},\n`;
    if (note) out += `    note: "${esc(note)}",\n`;
    out += `  },\n`;
  }
  return out + "};\n";
}

const header = `#!/usr/bin/env node
/**
 * OKX \`account\` and \`balance_and_position\` channels - field contracts
 * -------------------------------------------------------------------
 * GENERATED FILE - do not edit by hand. Run \`npm run contract:generate\`.
 * Tables and notes: contract/generate-account.mjs.
 *
 * Field lists are derived from the frames in fixtures/orders-lifecycle.json, so they cannot drift
 * from what OKX sends. \`docs\` is OKX's wording; \`note\` is what we established ourselves;
 * \`docs: null\` marks a field that arrives on the wire but is absent from the OKX reference.
 *
 * Verified structural facts:
 *  - \`account\`: eventType / curPage / lastPage sit on the message ENVELOPE, beside data[].
 *    snapshot carries every non-zero-balance currency (possibly paged, commit at lastPage);
 *    event_update carries only what the event touched. A currency dropping to zero simply stops
 *    being sent, so a snapshot must REPLACE the local map rather than merge into it.
 *  - \`balance_and_position\`: eventType sits INSIDE data[]. Verified event types on this account:
 *    snapshot, filled, transferred.
 *  - Neither channel covers the Funding account. No private channel does.
 */

`;

const body = [
  table("ACCOUNT_FIELDS", ACC, ACCOUNT_DESC, "account"),
  table("ACCOUNT_DETAIL_FIELDS", ACC.flatMap((a) => a.details ?? []), DETAIL_DESC, "detail"),
  table("BALPOS_FIELDS", BP, BP_DESC, "bp"),
  table("BALPOS_BALDATA_FIELDS", BP.flatMap((b) => b.balData ?? []), BALDATA_DESC, "baldata"),
  table("BALPOS_POSDATA_FIELDS", [], POSDATA_DESC, "posdata", Object.keys(POSDATA_DESC)),
  table("BALPOS_TRADES_FIELDS", BP.flatMap((b) => b.trades ?? []), TRADES_DESC, "trades"),
].join("\n");

const tail = `
/** Documented event types for balance_and_position; observed here: snapshot, filled, transferred. */
export { BALANCE_POSITION_EVENT_TYPES } from "./okx-order-contract.mjs";

/** account envelope eventType values. */
export const ACCOUNT_EVENT_TYPES = ["snapshot", "event_update"];

/** Reports fields present in a payload but absent from the contract. */
export function auditAgainst(table, payload) {
  const known = Object.keys(table);
  return Object.keys(payload).filter((k) => !known.includes(k));
}
`;

writeFileSync(OUTPUT, header + body + tail);
const count = (t) => (t.match(/^  \w+: \{/gm) ?? []).length;
console.log("Generated okx-account-contract.mjs");
console.log(`  account ${count(body.split("export const")[1])} + details ${count(body.split("export const")[2])}`
  + ` | bal&pos ${count(body.split("export const")[3])} + balData ${count(body.split("export const")[4])}`
  + ` + posData ${count(body.split("export const")[5])} + trades ${count(body.split("export const")[6])}`);
