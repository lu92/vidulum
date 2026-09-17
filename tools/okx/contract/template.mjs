#!/usr/bin/env node
/**
 * OKX `orders` channel - field contract
 * ------------------------------------
 * GENERATED FILE - do not edit by hand. Run `npm run contract:generate`.
 * Prose and helpers: contract/template.mjs. Field tables: contract/generate.mjs.
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
 * Each entry carries two DESCRIPTIONS, deliberately kept apart:
 *   `docs` - OKX's own wording, transcribed from the reference. `null` where OKX does not
 *            document the field at all (currently only `slippage`, which arrives anyway).
 *   `note` - what we established, including facts the reference does not state: that `uTime`
 *            is not bumped for an attached TP/SL amend, that `ordId` exceeds 2^53, that
 *            attachAlgoOrds is narrower over WS than over REST.
 * Neither subsumes the other. `docs` carries unit rules and applicability conditions we would
 * not have derived from one session; `note` carries findings the reference omits.
 *
 * Each entry also carries two value lists:
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

/** name -> { group, verified, example, docs, observed, documented, note } */
export const ORDER_FIELDS = {
/*__FIELDS__*/
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


/**
 * What triggered a `balance_and_position` push. Only `snapshot` and `filled` have been observed;
 * the rest come from the OKX reference and are unconfirmed here.
 *
 * Note the asymmetry with the `account` channel: there `eventType` sits on the message envelope,
 * here it sits inside `data[]`. Reading the wrong level yields undefined, silently.
 */
export const BALANCE_POSITION_EVENT_TYPES = [
  "snapshot", "delivered", "exercised", "transferred", "filled", "liquidation", "claw_back",
  "adl", "funding_fee", "adjust_margin", "set_leverage", "interest_deduction", "settlement",
];

/**
 * Numeric codes the WS push carries as bare numbers, with the meanings OKX publishes.
 *
 * This matters because `cancelSourceReason` - the human-readable text - exists only in the REST
 * shape. Over WebSocket you get `cancelSource: "1"` and nothing else, so without this table the
 * difference between "the user cancelled it" and "risk control killed it" is invisible.
 */
export const CANCEL_SOURCE = {
  "0": "canceled by system",
  "1": "canceled by user",
  "2": "pre reduce-only order canceled - insufficient margin in position",
  "3": "risk cancellation - insufficient maintenance margin, liquidation risk",
  "4": "borrowings reached hard cap",
  "6": "ADL cancellation - low margin ratio, liquidation risk",
  "7": "futures contract delivery",
  "9": "insufficient balance after funding fees deducted",
  "10": "option contract expiration",
  "13": "FOK order not completely filled",
  "14": "IOC order partially canceled - not completely filled",
  "15": "order price beyond the limit",
  "17": "close order canceled - position already closed at market price",
  "20": "cancel-all-after triggered",
  "21": "TP/SL order canceled - position had been closed",
  "22": "reduce-only order canceled - better price available in the same direction",
  "23": "existing reduce-only order canceled - better price available in the same direction",
  "27": "price limit verification failed - counterparty price difference exceeds 5%",
  "31": "post-only order would have taken liquidity",
  "32": "self trade prevention",
  "33": "exceeded the maximum number of order matches per taker order",
  "36": "TP limit order canceled - corresponding SL order was triggered",
  "37": "TP limit order canceled - corresponding SL order was canceled",
  "38": "market maker protection orders canceled by the user",
  "39": "market maker protection triggered",
  "42": "chase difference reached the maximum",
  "43": "buy price above the index price, or sell price below it",
  "44": "insufficient balance for auto conversion at the risk control limit",
  "45": "RPI order price verification failed",
  "46": "delta reducing cancel orders",
};

export const AMEND_SOURCE = {
  "1": "amended by user",
  "2": "amended by user, quantity overridden by system due to reduce-only",
  "4": "quantity amended by system due to reduce-only",
  "5": "options px / pxVol / pxUsd modified by a linked variation",
  "6": "price adjusted by system for the RPI maker spacing rule",
};

export const AMEND_RESULT = {
  "-1": "failure",
  "0": "success",
  "1": "automatic cancel - amendment acknowledged then failed",
  "2": "automatic amendment succeeded (options pxVol / pxUsd)",
};

/** Why a normal order came into existence, when it was created by another order. */
export const ORDER_SOURCE = {
  "6": "triggered by a trigger order",
  "7": "triggered by a TP/SL order",
  "13": "triggered by an algo order",
  "25": "triggered by a trailing stop order",
  "34": "triggered by a chase order",
};

const CODE_MAPS = { cancelSource: CANCEL_SOURCE, amendSource: AMEND_SOURCE, amendResult: AMEND_RESULT, source: ORDER_SOURCE };

/** Returns the published meaning of a coded value, or null when the field carries no code map. */
export function explainCode(field, value) {
  if (value === "" || value === undefined || value === null) return null;
  return CODE_MAPS[field]?.[String(value)] ?? null;
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
