/**
 * Turns OKX's balances into the snapshot `POST /portfolio-spec` expects (tasks E3, E10).
 *
 * <p>The mapping is short because the backend was shaped around what OKX actually reports:
 *
 *   total   = cashBal + bal       everything held, across both accounts
 *   traded  = spotBal             the part OKX priced
 *   frozen  = frozenBal + frozenBal   what is committed, in both accounts (task D5)
 *   price   = openAvgPx           the average cost of that part
 *
 * The difference engine turns those three numbers into two positions - one with a cost, one
 * without. <b>The prototype does not decide that split</b>; it only forwards what the exchange
 * said. Deciding here would put the same rule in two places and let them drift.
 *
 * <p><b>Both accounts, not one (E10).</b> OKX keeps money in two places: deposits land in
 * Funding, trading happens in Trading. Reading only Trading meant a coin paid in and left where
 * it landed was invisible to Vidulum entirely - while the business context has asked for
 * "Trading + Funding" from the start. Funding reports no `spotBal` and no `openAvgPx`, because
 * nothing is traded there, so its balance flows into the untraded part on its own. That is not a
 * convenient coincidence: the exchange really has not priced it.
 */

/** OKX reports `openAvgPx` in USD regardless of what the account is valued in. */
export const REPORTED_COST_CURRENCY = "USD";

/**
 * Named rather than positional on purpose: a second balance added as another positional argument
 * would leave every existing caller silently passing nothing, and a snapshot missing half the
 * account looks exactly like an account with half as much in it.
 *
 * @param trading `data[0].details` from `GET /api/v5/account/balance`
 * @param funding `data` from `GET /api/v5/asset/balances`
 * @returns positions ready to be sent, smallest holdings dropped
 */
export function buildSnapshotPositions({ trading = [], funding = [], dustThreshold = 0 } = {}) {
  const held = new Map();

  for (const detail of trading) {
    const total = num(detail.cashBal);
    if (total === null) continue;
    held.set(detail.ccy, {
      ticker: detail.ccy,
      total,
      traded: clamp(num(detail.spotBal) ?? 0, 0, total),
      // An open order commits part of the balance without changing what is held, so it travels
      // as its own number rather than being subtracted from the total (task D5).
      frozen: Math.max(num(detail.frozenBal) ?? 0, 0),
      price: num(detail.openAvgPx),
    });
  }

  // Funding adds to what is held and to nothing else. It has no traded part and no reported
  // price, so whatever sits here lands in the untraded half of the split by arithmetic alone.
  for (const balance of funding) {
    const amount = num(balance.bal);
    if (amount === null || amount === 0) continue;
    // Funding freezes too - a pending withdrawal sits here - and the owner has one balance, not
    // two, so the two frozen figures add up exactly as the totals do.
    const frozen = Math.max(num(balance.frozenBal) ?? 0, 0);
    const existing = held.get(balance.ccy);
    if (existing) {
      existing.total += amount;
      existing.frozen += frozen;
    } else {
      held.set(balance.ccy,
        { ticker: balance.ccy, total: amount, traded: 0, frozen, price: null });
    }
  }

  return [...held.values()]
    // Dust is judged on the whole holding, after both accounts are in. A trace in Trading beside
    // a real balance in Funding is not dust, and dropping it would hide the larger half.
    .filter((position) => position.total > dustThreshold)
    .map(toPosition)
    .sort((a, b) => a.ticker.localeCompare(b.ticker));
}

function toPosition({ ticker, total, traded, frozen, price }) {
  return {
    ticker,
    total: { qty: total, unit: "Number" },
    // Clamped again: Funding raises the total, never the traded part, but a Trading-only
    // position whose spotBal exceeded cashBal was already clamped above.
    traded: { qty: clamp(traded, 0, total), unit: "Number" },
    // Clamped because the two accounts are read at slightly different moments: a freeze released
    // between the calls can otherwise exceed a total read a fraction of a second earlier, and the
    // backend rejects that outright rather than quietly holding an impossible position.
    frozen: { qty: clamp(frozen ?? 0, 0, total), unit: "Number" },
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
export function buildSpecRequest({ broker, connectionId, denominationCurrency, portfolioId = null,
                                   takenAt, trading, funding, dustThreshold = 0 }) {
  return {
    broker,
    connectionId,
    // Sent at creation, not only at confirmation: the backend needs it to know which line of the
    // snapshot is cash, and cash is the one position it must not split (task C10).
    denominationCurrency,
    portfolioId,
    snapshotTakenAt: takenAt,
    positions: buildSnapshotPositions({ trading, funding, dustThreshold }),
  };
}

/**
 * The same freeze counted a second way, from the open orders themselves.
 *
 * <p>`frozenBal` is what the snapshot actually carries (task D5); this is the independent
 * reckoning it can be checked against, the same trick E7 uses for coverage. Two readings that
 * agree are evidence; one reading agreeing with itself is not. They can legitimately differ —
 * a pending withdrawal freezes a balance without any open order behind it — so a mismatch is
 * reported, not enforced.
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
