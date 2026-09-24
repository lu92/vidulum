/**
 * Reading a portfolio back and saying honestly what is known about it (tasks E5 and E7).
 *
 * The presentation matters as much as the numbers here. After the cost-basis work, a position
 * whose cost nobody knows reports `profit: null` rather than zero - and a prototype that printed
 * "0.00" would undo exactly the distinction the backend went to some trouble to preserve.
 */

/**
 * Quotes the portfolio actually needs, which is a narrower set than the snapshot's (task E5).
 *
 * <p>The snapshot was published against before the portfolio existed; by the time it does, dust
 * has been dropped and positions have been split, so republishing the whole snapshot would keep
 * prices alive for assets nobody holds.
 */
export function quotesNeededBy(summary, valuationCurrency) {
  const tickers = new Set((summary.assets ?? []).map((a) => a.ticker));
  tickers.delete(valuationCurrency);
  return [...tickers].sort().map((ticker) => `${ticker}/${valuationCurrency}`);
}

/**
 * How much of a position has a known cost.
 *
 * <p>`null` when nothing is known - deliberately not `0`, which would read as "we checked and it
 * is zero" rather than "there is nothing to compute from".
 */
export function coverageOf(asset) {
  const held = asset.quantity?.qty ?? 0;
  const covered = asset.costBasis?.quantity?.qty ?? 0;
  if (held === 0) return null;
  if (!asset.costBasis) return 0;
  return covered / held;
}

/**
 * Share of the portfolio's value that a result can be computed for.
 *
 * <p>Kept after C4 moved this into the backend, and deliberately so: it is the independent
 * reckoning the backend's own `profitCoverage` is checked against. Two implementations of the
 * same rule agreeing is evidence; one implementation agreeing with itself is not.
 */
export function portfolioCoverage(summary) {
  const assets = summary.assets ?? [];
  const total = assets.reduce((sum, a) => sum + (a.currentValue?.amount ?? 0), 0);
  if (total === 0) return null;
  const covered = assets.reduce((sum, a) => {
    const share = coverageOf(a);
    return sum + (share === null ? 0 : share * (a.currentValue?.amount ?? 0));
  }, 0);
  return covered / total;
}

/**
 * One line per position, with the provenance of the cost spelled out.
 *
 * <p>"we do not know" and "you told us" are different claims and a reader has to be able to tell
 * them apart - that is the whole reason provenance is a closed dictionary rather than a comment.
 */
export function describePositions(summary) {
  return (summary.assets ?? []).map((asset) => {
    const coverage = coverageOf(asset);
    const value = format(asset.currentValue);
    const cost = asset.costBasis
      ? `cost ${format({ amount: asset.costBasis.avgPrice.amount,
                         currency: asset.costBasis.avgPrice.currency })}` +
        ` (${asset.costBasis.provenance})`
      : "cost unknown";
    const gain = asset.unrealisedProfit === null || asset.unrealisedProfit === undefined
      ? "unrealised gain not computable"
      : `unrealised ${format(asset.unrealisedProfit)}`;
    const covered = coverage === null ? "" : ` [${(coverage * 100).toFixed(0)}% covered]`;
    // Shown only when something is actually held back, so the common line stays readable - but
    // shown at all, because "you have 50 000 XRP" and "you can move 40 000 of them" are different
    // statements and only one of them was being made before D5.
    const locked = (asset.locked?.qty ?? 0) > 0 ? ` (${asset.locked.qty} locked)` : "";
    return `${asset.ticker} ${asset.quantity.qty}${locked} -> ${value}; ${cost}; ${gain}${covered}`;
  });
}

/**
 * What the owner put in, as far as the ledger knows (tasks C9 and C12).
 *
 * <p>This used to print `investedBalance`, which moved only on deposits and withdrawals - so a
 * portfolio onboarded from a snapshot reported `0` beside six figures of holdings, and the report
 * had to carry a caveat saying the number meant nothing. It is now a ledger: onboarding writes one
 * opening entry worth what the account held on arrival, and the backend withholds the total when
 * too little of the ledger carries a value, exactly as it withholds a result (C4).
 */
export function describeContributions(summary) {
  switch (summary.contributionStatus) {
    case "COMPUTED":
      return `${format(summary.netContributions)} net`;
    case "WITHHELD_LOW_COVERAGE":
      return "withheld - too little of the ledger has a known value to stand for the whole";
    case "NO_KNOWN_VALUE":
      return "not computable - nothing put in has a known value";
    case "NOTHING_CONTRIBUTED":
      return "nothing put in";
    default:
      return `unknown status: ${summary.contributionStatus}`;
  }
}


/**
 * How much the owner's wealth has changed since they started (task C5).
 *
 * <p>Printed next to the result rather than instead of it, because they answer different
 * questions: this one says whether there is more than was put in, the result says whether the
 * buying was good. On this account the result is withheld — 92% of the value has no known cost —
 * and this line is the only one that can speak.
 */
export function describeWealthChange(summary) {
  if (summary.wealthChange === null || summary.wealthChange === undefined) {
    // Derived from the ledger, so it falls silent with it and for the same stated reason.
    return `not computable — ${describeContributions(summary)}`;
  }
  const pct = summary.pctWealthChange === null || summary.pctWealthChange === undefined
    ? ""
    : ` (${(summary.pctWealthChange * 100).toFixed(2)}%)`;
  return `${format(summary.wealthChange)}${pct}`;
}

/**
 * The portfolio as a whole.
 */
export function describePortfolio(summary) {
  const coverage = portfolioCoverage(summary);
  const lines = [
    `${summary.name} (${summary.broker})`,
    `  value      ${format(summary.currentValue)}`,
    `  put in     ${describeContributions(summary)}`
      + (summary.contributionCoverage === null || summary.contributionCoverage === undefined
        ? ""
        : `  (${(summary.contributionCoverage * 100).toFixed(0)}% of the ledger is valued)`),
    `  coverage   ${coverage === null ? "n/a" : (coverage * 100).toFixed(0) + "% of value has a known cost"}`
      + `  (backend: ${describeBackendCoverage(summary)})`,
    `  growth     ${describeWealthChange(summary)}`,
    `  result     ${describeResult(summary)}`,
  ];
  return lines.concat(describePositions(summary).map((line) => `  ${line}`));
}

/**
 * What the backend now says about the same question (task C4), printed beside the prototype's own
 * figure so a disagreement is visible rather than silently preferred.
 */
export function describeBackendCoverage(summary) {
  if (summary.profitCoverage === null || summary.profitCoverage === undefined) return "n/a";
  return `${(summary.profitCoverage * 100).toFixed(0)}%`;
}

/**
 * The portfolio's result as the backend reports it.
 *
 * <p>`WITHHELD_LOW_COVERAGE` is not an error and must not read like one: the backend computed a
 * correct figure and decided it would mislead. Printing the status instead of a blank is the
 * whole point of C4.
 */
export function describeResult(summary) {
  switch (summary.profitStatus) {
    case "COMPUTED":
      return `${format(summary.unrealisedProfit)} unrealised`
        + ` (${(summary.pctUnrealisedProfit * 100).toFixed(2)}%)`;
    case "WITHHELD_LOW_COVERAGE":
      return "withheld - too little of the value has a known cost to stand for the whole";
    case "NO_KNOWN_COST":
      return "not computable - nothing held has a known cost";
    case "NOTHING_HELD":
      return "nothing held";
    default:
      return `unknown status: ${summary.profitStatus}`;
  }
}

/**
 * Does the backend agree with our own reckoning of coverage?
 *
 * <p>Returns the two figures so a caller can report the gap rather than a bare boolean.
 */
export function coverageAgreement(summary, tolerance = 1e-6) {
  const ours = portfolioCoverage(summary);
  const theirs = summary.profitCoverage ?? null;
  const agree = ours === null || theirs === null
    ? ours === theirs
    : Math.abs(ours - theirs) <= tolerance;
  return { ours, theirs, agree };
}

function format(money) {
  if (!money) return "n/a";
  return `${money.amount} ${money.currency}`;
}
