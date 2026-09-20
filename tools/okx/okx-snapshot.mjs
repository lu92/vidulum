/**
 * Turns an OKX balance reply into the snapshot `POST /portfolio-spec` expects (task E3).
 *
 * <p>The mapping is short because the backend was shaped around what OKX actually reports:
 *
 *   total   = cashBal    everything held
 *   traded  = spotBal    the part OKX priced
 *   price   = openAvgPx  the average cost of that part
 *
 * The difference engine turns those three numbers into two positions - one with a cost, one
 * without. <b>The prototype does not decide that split</b>; it only forwards what the exchange
 * said. Deciding here would put the same rule in two places and let them drift.
 */

/** OKX reports `openAvgPx` in USD regardless of what the account is valued in. */
export const REPORTED_COST_CURRENCY = "USD";

/**
 * @param details `data[0].details` from `GET /api/v5/account/balance`
 * @returns positions ready to be sent, smallest holdings dropped
 */
export function buildSnapshotPositions(details = [], { dustThreshold = 0 } = {}) {
  return details
    .map(toPosition)
    .filter((position) => position !== null)
    .filter((position) => position.total.qty > dustThreshold)
    .sort((a, b) => a.ticker.localeCompare(b.ticker));
}

function toPosition(detail) {
  const total = num(detail.cashBal);
  if (total === null) return null;

  const traded = clamp(num(detail.spotBal) ?? 0, 0, total);
  const price = num(detail.openAvgPx);

  return {
    ticker: detail.ccy,
    total: { qty: total, unit: "Number" },
    traded: { qty: traded, unit: "Number" },
    // A price without a traded quantity would claim a cost for nothing, and the backend
    // refuses it - so it is dropped here rather than sent to be rejected.
    reportedAvgPrice: traded > 0 && price !== null
      ? { amount: price, currency: REPORTED_COST_CURRENCY }
      : null,
  };
}

/**
 * Assembles the whole request body.
 *
 * @param connectionId the connection this account was registered under; the backend takes the
 *                     broker and valuation currency from it, so they are not repeated here
 */
export function buildSpecRequest({ broker, connectionId, portfolioId = null,
                                   takenAt, details, dustThreshold = 0 }) {
  return {
    broker,
    connectionId,
    portfolioId,
    snapshotTakenAt: takenAt,
    positions: buildSnapshotPositions(details, { dustThreshold }),
  };
}

/**
 * Open orders lock part of a balance but do not change what is held, so they do not belong in
 * the snapshot. They map onto `Asset.locked` once the portfolio exists (task D5), which is why
 * this returns them separately instead of folding them in.
 */
export function lockedByOpenOrders(openOrders = []) {
  const locked = new Map();
  for (const order of openOrders) {
    const [base] = String(order.instId ?? "").split("-");
    const remaining = (num(order.sz) ?? 0) - (num(order.accFillSz) ?? 0);
    if (!base || remaining <= 0) continue;
    locked.set(base, (locked.get(base) ?? 0) + remaining);
  }
  return Object.fromEntries([...locked.entries()].sort(([a], [b]) => a.localeCompare(b)));
}

function num(value) {
  if (value === undefined || value === null || value === "") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function clamp(value, min, max) {
  return Math.min(Math.max(value, min), max);
}
