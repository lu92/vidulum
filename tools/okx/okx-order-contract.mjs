#!/usr/bin/env node
/**
 * OKX `orders` channel - field contract
 * ------------------------------------
 * Every field the private WebSocket `orders` channel delivers, with a real example value and
 * what it means. This is a hand-verified contract, not a transcription of the OKX docs: the
 * field list was generated from 9 frames captured on 2026-09-12 covering an order's whole
 * lifecycle (creation, price amend, stop-loss attach, stop-loss amend, take-profit added,
 * cancellation, and a fill). The raw frames live in fixtures/orders-lifecycle.json.
 *
 * Verified facts this contract encodes:
 *  - OKX sends the FULL order state on every push, never a delta. All 9 frames carried the
 *    same 71 keys; "empty" is always "" and a key is never omitted.
 *  - The WS frame is richer than GET /trade/orders-pending (71 vs 54 fields).
 *  - `cancelSourceReason` exists in REST but NOT over WS - the WS push gives only the numeric
 *    `cancelSource`.
 *  - `uTime` is NOT bumped when an attached take-profit or stop-loss is amended.
 *  - Inside attachAlgoOrds, WS carries 16 fields; `failCode`, `failReason` and `percent`
 *    appear only in the REST shape.
 *
 * Each entry carries two value lists, deliberately separated:
 *   `observed`   - every distinct non-empty value seen in the recorded session. Ground truth,
 *                  but only as complete as one session can be.
 *   `documented` - the full enumeration where one is published (source: the tiagosiebler/okx-api
 *                  typings cited in OKX-CONTEXT.md). `null` means no enumeration was obtainable:
 *                  OKX's own single-page reference is too large to retrieve programmatically, and
 *                  the SDK types those fields as plain strings. For them, `observed` is all we know.
 *
 * `verified: true` means the field was observed carrying a real value during that session.
 * `verified: false` means the key was always present but always empty - the note then
 * describes what OKX documents it for, and should be treated as unconfirmed here.
 *
 * All values arrive as STRINGS, including every number. `ordId` exceeds 2^53, so converting
 * it with Number() silently corrupts it - keep identifiers as strings end to end.
 *
 * Intended as the seed for the backend contract (see OKX-CONTEXT.md).
 */

/** name -> { group, verified, example, observed, documented, note } */
export const ORDER_FIELDS = {
  accFillSz: { group: "execution", verified: true, example: "0",
    observed: ["0", "0.0002"],
    documented: null,
    note: "Cumulative filled size across every fill of this order." },
  algoClOrdId: { group: "identity", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Client id of the algo order that created this one." },
  algoId: { group: "identity", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Set when the order was created BY an algo order; empty for a plain order." },
  amendResult: { group: "amend", verified: true, example: "0",
    observed: ["0"],
    documented: null,
    note: "-1 failed, 0 succeeded, 1 automatic amend. Present only on the push that answers an amend." },
  amendSource: { group: "amend", verified: true, example: "1",
    observed: ["1"],
    documented: null,
    note: "What triggered the amend, e.g. 1 = amended by the user." },
  attachAlgoClOrdId: { group: "protection", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Client id of the attached algo order." },
  attachAlgoOrds: { group: "protection", verified: true, example: "(object)",
    observed: [],
    documented: null,
    note: "Attached take-profit / stop-loss / trailing stop. THE place TP and SL live. Over WS this array has 16 fields - failCode, failReason and percent appear only in REST." },
  avgPx: { group: "execution", verified: true, example: "0",
    observed: ["0", "66588.7"],
    documented: null,
    note: "Average fill price across every fill." },
  cTime: { group: "meta", verified: true, example: "1789240636555",
    observed: ["1789240636555", "1789240715207", "1789240740101"],
    documented: null,
    note: "Creation time, ms. Never changes." },
  cancelSource: { group: "cancel", verified: true, example: "1",
    observed: ["1"],
    documented: null,
    note: "Numeric reason the order was cancelled. 1 = cancelled by the owner." },
  category: { group: "cancel", verified: true, example: "normal",
    observed: ["normal"],
    documented: null,
    note: "normal | twap | adl | full_liquidation | partial_liquidation | delivery | ddh." },
  ccy: { group: "instrument", verified: true, example: "EUR",
    observed: ["EUR"],
    documented: null,
    note: "Margin currency. On spot it mirrors the quote currency." },
  clOrdId: { group: "identity", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Client-supplied order id. Empty unless you set one when placing the order." },
  code: { group: "amend", verified: true, example: "0",
    observed: ["0"],
    documented: null,
    note: "Error code when an amend or the order itself failed; 0 otherwise." },
  execType: { group: "execution", verified: true, example: "T",
    observed: ["T"],
    documented: null,
    note: "T = taker, M = maker." },
  fee: { group: "fees", verified: true, example: "0",
    observed: ["-0.0000007", "0"],
    documented: null,
    note: "Cumulative fee. Negative means charged. On a spot BUY it is taken in the BASE currency." },
  feeCcy: { group: "fees", verified: true, example: "BTC",
    observed: ["BTC"],
    documented: null,
    note: "Currency the cumulative fee is charged in." },
  fillFee: { group: "fees", verified: true, example: "0",
    observed: ["-0.0000007", "0"],
    documented: null,
    note: "Fee for THIS fill only." },
  fillFeeCcy: { group: "fees", verified: true, example: "BTC",
    observed: ["BTC"],
    documented: null,
    note: "Currency of this fill's fee." },
  fillFwdPx: { group: "execution", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Forward price at fill time (options)." },
  fillIdxPx: { group: "execution", verified: true, example: "77356.8",
    observed: ["77356.8"],
    documented: null,
    note: "Index price in USD at fill time. Enough to value the trade without fetching candles." },
  fillMarkPx: { group: "execution", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Mark price at fill time (derivatives)." },
  fillMarkVol: { group: "execution", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Mark volatility at fill time (options)." },
  fillNotionalUsd: { group: "execution", verified: true, example: "15.446230572952912",
    observed: ["15.446230572952912"],
    documented: null,
    note: "Notional value of this fill in USD." },
  fillPnl: { group: "execution", verified: true, example: "0",
    observed: ["0"],
    documented: null,
    note: "Realised PnL from this fill." },
  fillPx: { group: "execution", verified: true, example: "66588.7",
    observed: ["66588.7"],
    documented: null,
    note: "Price of THIS fill only - not cumulative. Empty on non-fill pushes." },
  fillPxUsd: { group: "execution", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Fill price in USD (options)." },
  fillPxVol: { group: "execution", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Fill price as implied volatility (options)." },
  fillSz: { group: "execution", verified: true, example: "0",
    observed: ["0", "0.0002"],
    documented: null,
    note: "Size of THIS fill only." },
  fillTime: { group: "execution", verified: true, example: "1789240740102",
    observed: ["1789240740102"],
    documented: null,
    note: "Timestamp of this fill, ms." },
  instId: { group: "instrument", verified: true, example: "BTC-EUR",
    observed: ["BTC-EUR"],
    documented: null,
    note: "Instrument, e.g. BTC-EUR." },
  instType: { group: "instrument", verified: true, example: "SPOT",
    observed: ["SPOT"],
    documented: ["SPOT", "MARGIN", "SWAP", "FUTURES", "OPTION", "EVENTS"],
    note: "SPOT | MARGIN | SWAP | FUTURES | OPTION." },
  isTpLimit: { group: "protection", verified: true, example: "false",
    observed: ["false"],
    documented: null,
    note: "true when the take-profit executes as a limit rather than a market order." },
  lastPx: { group: "execution", verified: true, example: "66621",
    observed: ["66581.9", "66588.7", "66621"],
    documented: null,
    note: "Last traded price on the instrument at push time - market data, not your order." },
  lever: { group: "terms", verified: true, example: "0",
    observed: ["0"],
    documented: null,
    note: "Leverage. 0 on spot." },
  linkedAlgoOrd: { group: "protection", verified: true, example: "(object)",
    observed: [],
    documented: null,
    note: "Algo order linked to this one; OKX sends {algoId:\"\"} rather than null when absent." },
  msg: { group: "amend", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Error text matching code." },
  notionalUsd: { group: "valuation", verified: true, example: "11.583000000000002",
    observed: ["11.583000000000002", "11.59823706796567", "11.830201809324983", "15.472048248666201"],
    documented: null,
    note: "Order notional in USD. Moves whenever the market moves, even with no order change." },
  ordId: { group: "identity", verified: true, example: "3917085220311920641",
    observed: ["3917085220311920641", "3917087859435106305", "3917088694739136513"],
    documented: null,
    note: "OKX order id. Exceeds 2^53 - keep it a string; Number() corrupts it." },
  ordType: { group: "terms", verified: true, example: "limit",
    observed: ["limit"],
    documented: ["market", "limit", "post_only", "fok", "ioc", "optimal_limit_ioc", "mmp", "mmp_and_post_only", "elp", "rpi"],
    note: "limit | market | post_only | fok | ioc | optimal_limit_ioc." },
  outcome: { group: "cancel", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Outcome classification supplied by OKX." },
  pnl: { group: "fees", verified: true, example: "0",
    observed: ["0"],
    documented: null,
    note: "Cumulative realised PnL for the order." },
  posSide: { group: "terms", verified: false, example: null,
    observed: [],
    documented: ["net", "long", "short"],
    note: "Position side for derivatives; empty on spot." },
  px: { group: "terms", verified: true, example: "50000",
    observed: ["50000", "51000", "66700"],
    documented: null,
    note: "Limit price. Empty for market orders." },
  pxType: { group: "terms", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Options only - how px should be read (px | pxVol | pxUsd)." },
  pxUsd: { group: "terms", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Options only - price in USD." },
  pxVol: { group: "terms", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Options only - price expressed as implied volatility." },
  quickMgnType: { group: "terms", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Quick-margin mode for margin trading." },
  rebate: { group: "fees", verified: true, example: "0",
    observed: ["0"],
    documented: null,
    note: "Cumulative rebate." },
  rebateCcy: { group: "fees", verified: true, example: "EUR",
    observed: ["EUR"],
    documented: null,
    note: "Rebate currency." },
  reduceOnly: { group: "terms", verified: true, example: "false",
    observed: ["false"],
    documented: null,
    note: "Derivatives only - the order may only reduce a position." },
  reqId: { group: "identity", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Echo of the amend request id, when the amend supplied one." },
  side: { group: "terms", verified: true, example: "buy",
    observed: ["buy"],
    documented: ["buy", "sell"],
    note: "buy | sell." },
  slOrdPx: { group: "protection", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Legacy stop-loss order price. -1 means execute at market." },
  slTriggerPx: { group: "protection", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Legacy placement of the stop-loss trigger. Reading only attachAlgoOrds loses it." },
  slTriggerPxType: { group: "protection", verified: false, example: null,
    observed: [],
    documented: ["last", "index", "mark"],
    note: "last | index | mark for the stop-loss trigger." },
  slippage: { group: "terms", verified: true, example: "0",
    observed: ["0"],
    documented: null,
    note: "Allowed slippage for market orders." },
  source: { group: "cancel", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Origin of the cancellation for system-driven cases." },
  state: { group: "execution", verified: true, example: "live",
    observed: ["canceled", "filled", "live"],
    documented: ["live", "partially_filled", "filled", "canceled", "mmp_canceled"],
    note: "live | partially_filled | filled | canceled | mmp_canceled. The lifecycle driver." },
  stpId: { group: "terms", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Self-trade prevention group id." },
  stpMode: { group: "terms", verified: true, example: "cancel_maker",
    observed: ["cancel_maker"],
    documented: null,
    note: "Self-trade prevention mode, e.g. cancel_maker." },
  sz: { group: "terms", verified: true, example: "0.0002",
    observed: ["0.0002"],
    documented: null,
    note: "Order size. On spot limit orders it is denominated in the base currency." },
  tag: { group: "identity", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Client-supplied order tag." },
  tdMode: { group: "terms", verified: true, example: "cash",
    observed: ["cash"],
    documented: ["cross", "isolated", "cash", "spot_isolated"],
    note: "cash | cross | isolated. Spot uses cash." },
  tgtCcy: { group: "terms", verified: false, example: null,
    observed: [],
    documented: null,
    note: "For spot market orders: whether sz means base_ccy or quote_ccy." },
  tpOrdPx: { group: "protection", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Legacy take-profit order price. -1 means execute at market." },
  tpTriggerPx: { group: "protection", verified: false, example: null,
    observed: [],
    documented: null,
    note: "Legacy placement of the take-profit trigger, outside attachAlgoOrds." },
  tpTriggerPxType: { group: "protection", verified: false, example: null,
    observed: [],
    documented: ["last", "index", "mark"],
    note: "last | index | mark - which price feed arms the trigger." },
  tradeId: { group: "execution", verified: true, example: "1356365",
    observed: ["1356365"],
    documented: null,
    note: "Exchange trade id for this fill. The key to reconcile against fills-history." },
  tradeQuoteCcy: { group: "instrument", verified: true, example: "EUR",
    observed: ["EUR"],
    documented: null,
    note: "Quote currency actually used for the trade." },
  uTime: { group: "meta", verified: true, example: "1789240636555",
    observed: ["1789240636555", "1789240640873", "1789240657566", "1789240715207", "1789240734326", "1789240740101", "1789240740102"],
    documented: null,
    note: "Last update time, ms. NOT bumped when an attached TP/SL is amended - verified." },
};

export const ORDER_FIELD_GROUPS = [
  "identity", "instrument", "terms", "execution", "fees", "amend", "cancel",
  "protection", "valuation", "meta",
];

/** Order states, in lifecycle order. `filled` and `canceled` are terminal. */
export const ORDER_STATES = ["live", "partially_filled", "filled", "canceled", "mmp_canceled"];
export const TERMINAL_STATES = new Set(["filled", "canceled", "mmp_canceled"]);

/**
 * Compares an incoming payload against the contract.
 *
 * The point is the `unknown` list: OKX adds fields over time, and a silently ignored new
 * field is precisely how the attached take-profit stayed invisible for so long. Treat a
 * non-empty `unknown` as a prompt to re-record the fixtures and extend this file.
 */
export function auditOrderPayload(payload) {
  const known = Object.keys(ORDER_FIELDS);
  const present = Object.keys(payload);
  return {
    unknown: present.filter((k) => !known.includes(k)),
    absent: known.filter((k) => !present.includes(k)),
    populated: present.filter((k) => {
      const v = payload[k];
      return v !== "" && v !== null && v !== undefined && (!Array.isArray(v) || v.length > 0);
    }),
  };
}

/**
 * Flags values that fall outside a documented enumeration.
 *
 * A hit means either OKX extended the enum or this contract is stale - both worth knowing
 * before the value silently flows into downstream logic.
 */
export function validateEnums(payload) {
  const problems = [];
  for (const [k, f] of Object.entries(ORDER_FIELDS)) {
    if (!f.documented) continue;
    const v = payload[k];
    if (v === "" || v === undefined) continue;
    if (!f.documented.includes(v)) problems.push(`${k}="${v}" is outside [${f.documented.join(", ")}]`);
  }
  return problems;
}

/** Human-readable dump of one payload: only the fields that actually carry a value. */
export function describeOrderPayload(payload) {
  const lines = [];
  for (const group of ORDER_FIELD_GROUPS) {
    const rows = Object.entries(ORDER_FIELDS)
      .filter(([k, f]) => f.group === group)
      .filter(([k]) => {
        const v = payload[k];
        return v !== "" && v !== undefined && (!Array.isArray(v) || v.length > 0);
      })
      .map(([k]) => `    ${k} = ${JSON.stringify(payload[k])}`);
    if (rows.length) lines.push(`  [${group}]`, ...rows);
  }
  return lines.join("\n");
}
