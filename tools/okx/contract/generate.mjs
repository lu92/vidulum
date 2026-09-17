#!/usr/bin/env node
/**
 * Regenerates okx-order-contract.mjs.
 *
 * The field list is DERIVED from the recorded frames in fixtures/orders-lifecycle.json rather
 * than typed by hand - that is the whole point of the contract, and why this generator belongs
 * in the repo. Re-record the fixtures, run this, and the contract follows reality.
 *
 * Three hand-maintained tables live here:
 *   DOCS - documented enumerations, from the OKX "WS / Order channel" reference
 *   DESC - OKX's own field descriptions, transcribed from the same reference
 *   M    - our own notes and grouping, including findings the reference does not state
 *
 * The generator FAILS if a recorded field has no entry in M or DESC, or if M carries a field
 * the recording does not contain. A new field on the wire therefore stops the build instead of
 * slipping through - exactly the failure mode that hid the attached take-profit for so long.
 *
 * Usage:  npm run contract:generate
 */

import { readFileSync, writeFileSync } from "node:fs";

// Resolved from this file, not from the working directory, so `npm run contract:generate`
// behaves the same wherever it is invoked from.
const FIXTURES = new URL("../fixtures/orders-lifecycle.json", import.meta.url);
const TEMPLATE = new URL("./template.mjs", import.meta.url);
const OUTPUT = new URL("../okx-order-contract.mjs", import.meta.url);

const fx = JSON.parse(readFileSync(FIXTURES, "utf8"));
const frames = fx.frames.map(f => f.data);

// field -> [group, note]. Notes are hand-written; example values come from the recording.
const M = {
  ordId:["identity","OKX order id. Exceeds 2^53 - keep it a string; Number() corrupts it."],
  clOrdId:["identity","Client-supplied order id. Empty unless you set one when placing the order."],
  tag:["identity","Client-supplied order tag."],
  algoId:["identity","Set when the order was created BY an algo order; empty for a plain order."],
  algoClOrdId:["identity","Client id of the algo order that created this one."],
  reqId:["identity","Echo of the amend request id, when the amend supplied one."],

  instId:["instrument","Instrument, e.g. BTC-EUR."],
  instType:["instrument","SPOT | MARGIN | SWAP | FUTURES | OPTION."],
  ccy:["instrument","Margin currency. On spot it mirrors the quote currency."],
  tradeQuoteCcy:["instrument","Quote currency actually used for the trade."],

  side:["terms","buy | sell."],
  ordType:["terms","limit | market | post_only | fok | ioc | optimal_limit_ioc."],
  px:["terms","Limit price. Empty for market orders."],
  sz:["terms","Order size. On spot limit orders it is denominated in the base currency."],
  tdMode:["terms","cash | cross | isolated. Spot uses cash."],
  posSide:["terms","Position side for derivatives; empty on spot."],
  reduceOnly:["terms","Derivatives only - the order may only reduce a position."],
  tgtCcy:["terms","For spot market orders: whether sz means base_ccy or quote_ccy."],
  lever:["terms","Leverage, 0.01 to 125. Only meaningful for MARGIN/FUTURES/SWAP; 0 on spot."],
  quickMgnType:["terms","Quick-margin mode for margin trading."],
  stpId:["terms","Self-trade prevention id. Deprecated by OKX; returns \"\" when not applicable."],
  stpMode:["terms","Self-trade prevention mode, e.g. cancel_maker."],
  slippage:["terms","UNDOCUMENTED: arrives on the wire but is absent from the OKX orders-channel reference. Treat with care."],
  pxType:["terms","Options only - how px should be read (px | pxVol | pxUsd)."],
  pxUsd:["terms","Options only - price in USD."],
  pxVol:["terms","Options only - price expressed as implied volatility."],

  state:["execution","live | partially_filled | filled | canceled | mmp_canceled. The lifecycle driver."],
  accFillSz:["execution","Cumulative filled size across every fill of this order."],
  avgPx:["execution","Average fill price across every fill."],
  fillPx:["execution","Price of THIS fill only - not cumulative. Empty on non-fill pushes."],
  fillSz:["execution","Size of THIS fill only."],
  fillTime:["execution","Timestamp of this fill, ms."],
  tradeId:["execution","Trade id for THIS update. Its presence means a fill; OKX may resend a message, so each tradeId must be processed once per instId."],
  execType:["execution","T = taker, M = maker."],
  lastPx:["execution","Last traded price on the instrument at push time - market data, not your order."],
  fillPnl:["execution","Realised PnL from this fill."],
  fillNotionalUsd:["execution","Notional value of this fill in USD."],
  fillIdxPx:["execution","Index price in USD at fill time. Enough to value the trade without fetching candles."],
  fillMarkPx:["execution","Mark price at fill time (derivatives)."],
  fillMarkVol:["execution","Mark volatility at fill time (options)."],
  fillPxUsd:["execution","Fill price in USD (options)."],
  fillPxVol:["execution","Fill price as implied volatility (options)."],
  fillFwdPx:["execution","Forward price at fill time (options)."],

  fee:["fees","Cumulative fee, negative when charged. Spot/Margin except maker sells: always negative, in feeCcy. For maker SELL orders on Spot/Margin it is fee plus rebate, in the quote currency."],
  feeCcy:["fees","Currency the cumulative fee is charged in."],
  fillFee:["fees","Fee for THIS fill only."],
  fillFeeCcy:["fees","Currency of this fill's fee."],
  rebate:["fees","Cumulative rebate."],
  rebateCcy:["fees","Rebate currency."],
  pnl:["fees","Cumulative realised PnL for the order."],

  amendResult:["amend","-1 failed, 0 succeeded, 1 automatic amend. Present only on the push that answers an amend."],
  amendSource:["amend","What triggered the amend, e.g. 1 = amended by the user."],
  code:["amend","Error code when an amend or the order itself failed; 0 otherwise."],
  msg:["amend","Error text matching code."],

  cancelSource:["cancel","Numeric reason the order was cancelled. 1 = cancelled by the owner."],
  source:["cancel","Origin of the cancellation for system-driven cases."],
  outcome:["cancel","Outcome classification supplied by OKX."],
  category:["cancel","normal | twap | adl | full_liquidation | partial_liquidation | delivery | ddh."],

  attachAlgoOrds:["protection","Attached take-profit / stop-loss / trailing stop. THE place TP and SL live. Over WS this array has 16 fields - failCode, failReason and percent appear only in REST."],
  attachAlgoClOrdId:["protection","Client id of the attached algo order."],
  linkedAlgoOrd:["protection","Linked stop-loss order, only for the TP limit leg of a one-cancels-the-other (oco) order. OKX sends {algoId:\"\"} rather than null when absent."],
  isTpLimit:["protection","true when the take-profit executes as a limit rather than a market order."],
  tpTriggerPx:["protection","Legacy placement of the take-profit trigger, outside attachAlgoOrds."],
  tpOrdPx:["protection","Legacy take-profit order price. -1 means execute at market."],
  tpTriggerPxType:["protection","last | index | mark - which price feed arms the trigger."],
  slTriggerPx:["protection","Legacy placement of the stop-loss trigger. Reading only attachAlgoOrds loses it."],
  slOrdPx:["protection","Legacy stop-loss order price. -1 means execute at market."],
  slTriggerPxType:["protection","last | index | mark for the stop-loss trigger."],

  notionalUsd:["valuation","Order notional in USD. Moves whenever the market moves, even with no order change."],

  cTime:["meta","Creation time, ms. Never changes."],
  uTime:["meta","Last update time, ms. NOT bumped when an attached TP/SL is amended - verified."],
};


// Enumerations from the official OKX reference ("WS / Order channel", transcribed 2026-09-12 -
// the single-page docs-v5 is too large to fetch programmatically).
const DOCS = {
  instType: ["SPOT","MARGIN","SWAP","FUTURES","OPTION","EVENTS"],
  tgtCcy: ["base_ccy","quote_ccy"],
  pxType: ["px","pxVol","pxUsd"],
  ordType: ["market","limit","post_only","fok","ioc","optimal_limit_ioc","mmp","mmp_and_post_only","op_fok","rpi","elp"],
  side: ["buy","sell"],
  posSide: ["net","long","short"],
  tdMode: ["cross","isolated","cash"],
  execType: ["T","M"],
  state: ["canceled","live","partially_filled","filled","mmp_canceled"],
  tpTriggerPxType: ["last","index","mark"],
  slTriggerPxType: ["last","index","mark"],
  source: ["6","7","13","25","34"],
  cancelSource: ["0","1","2","3","4","6","7","9","10","13","14","15","17","20","21","22","23","27","31","32","33","36","37","38","39","42","43","44","45","46"],
  amendSource: ["1","2","4","5","6"],
  amendResult: ["-1","0","1","2"],
  category: ["normal","twap","adl","full_liquidation","partial_liquidation","delivery","ddh","auto_conversion"],
  isTpLimit: ["true","false"],
  reduceOnly: ["true","false"],
  quickMgnType: ["manual","auto_borrow","auto_repay"],
  outcome: ["yes","no"],
};


// OKX's own field descriptions, transcribed from the same reference.
// Kept apart from `note`: `docs` is OKX's wording, `note` is what we established. Neither
// subsumes the other - uTime carries a fact of ours the reference omits, while fillSz carries
// unit rules from the reference that we did not have.
const DESC = {
  instType: "Instrument type.",
  instId: "Instrument ID.",
  tgtCcy: "Order quantity unit setting for sz: base_ccy or quote_ccy. Only applicable to SPOT market orders. Default is quote_ccy for buy, base_ccy for sell.",
  ccy: "Margin currency. Applicable to all isolated MARGIN orders and cross MARGIN orders in Futures mode, FUTURES and SWAP contracts.",
  ordId: "Order ID.",
  clOrdId: "Client Order ID as assigned by the client.",
  tag: "Order tag.",
  px: "Price. For options, use coin as unit (e.g. BTC, ETH).",
  pxUsd: "Options price in USD. Only applicable to options; returns \"\" for other instrument types.",
  pxVol: "Implied volatility of the options order. Only applicable to options; returns \"\" for other instrument types.",
  pxType: "Price type of options: px (in the unit of coin), pxVol, or pxUsd (in the unit of USD).",
  sz: "Quantity to buy or sell.",
  notionalUsd: "Estimated notional value in USD of order.",
  ordType: "Order type.",
  side: "Order side, buy or sell.",
  posSide: "Position side. net; long or short only applicable to FUTURES/SWAP.",
  tdMode: "Trade mode: cross, isolated, cash.",
  fillPx: "Filled price for the current update.",
  tradeId: "Trade ID for the current update.",
  fillSz: "Filled quantity for the current update. The unit is base_ccy for SPOT and MARGIN; for market orders the unit is base_ccy whether tgtCcy is base_ccy or quote_ccy; the unit is contract for FUTURES/SWAP/OPTION.",
  fillPnl: "Filled profit and loss for the current update, applicable to orders which have a trade and aim to close position. Always 0 in other conditions.",
  fillTime: "Filled time for the current update.",
  fillFee: "Filled fee amount or rebate amount for the current update. Negative represents the transaction fee charged by the platform; positive represents rebate.",
  fillFeeCcy: "Filled fee currency or rebate currency for the current update. Fee currency when fillFee is less than 0; rebate currency when fillFee >= 0.",
  fillPxVol: "Implied volatility when filled. Only applicable to options.",
  fillPxUsd: "Options price when filled, in the unit of USD. Only applicable to options.",
  fillMarkVol: "Mark volatility when filled. Only applicable to options.",
  fillFwdPx: "Forward price when filled. Only applicable to options.",
  fillMarkPx: "Mark price when filled. Applicable to FUTURES, SWAP, OPTION.",
  fillIdxPx: "Index price at the moment of trade execution. For cross currency spot pairs it returns the baseCcy-USDT index price (e.g. for LTC-ETH it returns the LTC-USDT index price).",
  execType: "Liquidity taker or maker for the current update. T: taker, M: maker.",
  accFillSz: "Accumulated fill quantity. The unit is base_ccy for SPOT and MARGIN; for market orders the unit is base_ccy whether tgtCcy is base_ccy or quote_ccy; the unit is contract for FUTURES/SWAP/OPTION.",
  fillNotionalUsd: "Filled notional value in USD of order.",
  avgPx: "Average filled price. If none is filled, it will return 0.",
  state: "Order state.",
  lever: "Leverage, from 0.01 to 125. Only applicable to MARGIN/FUTURES/SWAP.",
  attachAlgoClOrdId: "Client-supplied Algo ID when placing order with attached TP/SL or trailing stop.",
  tpTriggerPx: "Take-profit trigger price.",
  tpTriggerPxType: "Take-profit trigger price type: last, index or mark price.",
  tpOrdPx: "Take-profit order price.",
  slTriggerPx: "Stop-loss trigger price.",
  slTriggerPxType: "Stop-loss trigger price type: last, index or mark price.",
  slOrdPx: "Stop-loss order price.",
  attachAlgoOrds: "Attached TP/SL or trailing stop order information.",
  linkedAlgoOrd: "Linked SL order detail, only applicable to the TP limit order of a one-cancels-the-other order (oco).",
  stpId: "Self trade prevention ID. Returns \"\" if self trade prevention is not applicable. Deprecated.",
  stpMode: "Self trade prevention mode.",
  feeCcy: "Fee currency. For maker sell orders of Spot and Margin this represents the quote currency. For all other cases it represents the currency in which fees are charged.",
  fee: "Fee amount. For Spot and Margin excluding maker sell orders: accumulated fee charged by the platform, always negative. For maker sell orders in Spot and Margin, Expiry Futures, Perpetual Futures and Options: accumulated fee and rebate.",
  rebateCcy: "Rebate currency. For maker sell orders of Spot and Margin this represents the base currency. For all other cases it represents the currency in which rebates are paid.",
  rebate: "Rebate amount, only applicable to Spot and Margin. For maker sell orders: accumulated fee and rebate amount in the unit of base currency. For all other cases the maker rebate amount, always positive; returns \"\" if no rebate.",
  pnl: "Profit and loss excluding the fee. Applicable to orders which have a trade and aim to close position; always 0 in other conditions. For liquidation under cross margin mode it includes liquidation penalties.",
  source: "Order source - which kind of order triggered this normal order.",
  cancelSource: "Source of the order cancellation.",
  amendSource: "Source of the order amendment.",
  category: "Category.",
  isTpLimit: "Whether it is a TP limit order. true or false.",
  uTime: "Update time, Unix timestamp format in milliseconds.",
  cTime: "Creation time, Unix timestamp format in milliseconds.",
  reqId: "Client Request ID as assigned by the client for order amendment. Returns \"\" if there is no order amendment.",
  amendResult: "The result of amending the order. Returns \"\" when cxlOnFail is true and the amendment is rejected.",
  reduceOnly: "Whether the order can only reduce the position size. true or false.",
  quickMgnType: "Quick Margin type, only applicable to Quick Margin Mode of isolated margin: manual, auto_borrow, auto_repay. Deprecated.",
  algoClOrdId: "Client-supplied Algo ID. There will be a value when an algo order attaching algoClOrdId is triggered, otherwise \"\".",
  algoId: "Algo ID. There will be a value when an algo order is triggered, otherwise \"\".",
  lastPx: "Last price.",
  code: "Error code, the default is 0.",
  msg: "Error message, the default is \"\".",
  tradeQuoteCcy: "The quote currency used for trading.",
  outcome: "The market outcome the user traded on: yes or no. Only applicable to EVENTS.",
  slippage: null,
};

const seen = {};
for (const f of frames) for (const [k, v] of Object.entries(f)) {
  if (typeof v === "object") { (seen[k] ??= new Set()).add("__obj__"); continue; }
  if (v !== "") (seen[k] ??= new Set()).add(String(v));
}
const keys = Object.keys(frames[0]).sort();
const missing = keys.filter(k => !M[k]);
if (missing.length) { console.error("No note (M entry) for:", missing.join(", ")); process.exit(1); }
const extra = Object.keys(M).filter(k => !keys.includes(k));
if (extra.length) { console.error("Note (M entry) for a field absent from the recording:", extra.join(", ")); process.exit(1); }

const esc = (s) => s.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
let body = "";
for (const k of keys) {
  const [group, note] = M[k];
  const vals = [...(seen[k] ?? [])].filter(v => v !== "__obj__");
  const isObj = (seen[k] ?? new Set()).has("__obj__");
  const example = isObj ? "(object)" : (vals[0] ?? null);
  const verified = isObj || vals.length > 0;
  const exStr = example === null ? "null" : `"${esc(example)}"`;
  const obs = isObj ? [] : vals.sort();
  const obsStr = obs.length ? `[${obs.map(v => `"${esc(v)}"`).join(", ")}]` : "[]";
  const docStr = DOCS[k] ? `[${DOCS[k].map(v => `"${v}"`).join(", ")}]` : "null";
  if (!(k in DESC)) { console.error(`No OKX description (DESC entry) for: ${k}`); process.exit(1); }
  const descStr = DESC[k] === null ? "null" : `"${esc(DESC[k])}"`;
  body += `  ${k}: { group: "${group}", verified: ${verified}, example: ${exStr},\n`
        + `    docs: ${descStr},\n`
        + `    observed: ${obsStr},\n`
        + `    documented: ${docStr},\n`
        + `    note: "${esc(note)}" },\n`;
}

const tpl = readFileSync(TEMPLATE, "utf8");
writeFileSync(OUTPUT, tpl.replace("/*__FIELDS__*/", body.trimEnd()));
console.log("Generated okx-order-contract.mjs");
console.log(`  fields: ${keys.length} | carrying a real value in the recording: ${keys.filter(k => (seen[k] ?? new Set()).size > 0).length}`);
