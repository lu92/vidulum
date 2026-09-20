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

/** Share of the portfolio's value that a result can be computed for. */
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
    const profit = asset.profit === null || asset.profit === undefined
      ? "profit not computable"
      : `profit ${format(asset.profit)}`;
    const covered = coverage === null ? "" : ` [${(coverage * 100).toFixed(0)}% covered]`;
    return `${asset.ticker} ${asset.quantity.qty} -> ${value}; ${cost}; ${profit}${covered}`;
  });
}

/**
 * The portfolio as a whole.
 *
 * <p>`investedBalance` is reported alongside a warning when it is zero: a portfolio built from a
 * snapshot never went through a deposit, so the field says nothing about what was actually put
 * in. Task C9 decides what it should mean; until then, printing it without a caveat would be the
 * one number in this report that lies.
 */
export function describePortfolio(summary) {
  const coverage = portfolioCoverage(summary);
  const lines = [
    `${summary.name} (${summary.broker})`,
    `  value      ${format(summary.currentValue)}`,
    `  invested   ${format(summary.investedBalance)}` +
      (summary.investedBalance?.amount === 0
        ? "   <- always zero for a snapshot-built portfolio (task C9)"
        : ""),
    `  coverage   ${coverage === null ? "n/a" : (coverage * 100).toFixed(0) + "% of value has a known cost"}`,
  ];
  return lines.concat(describePositions(summary).map((line) => `  ${line}`));
}

function format(money) {
  if (!money) return "n/a";
  return `${money.amount} ${money.currency}`;
}
