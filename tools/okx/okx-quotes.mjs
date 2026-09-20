/**
 * Getting prices into Vidulum's cache before a portfolio exists (task E8).
 *
 * `GET /portfolio/{id}/{currency}` prices **every** asset it holds. A portfolio created before
 * its quotes are loaded is a portfolio that cannot be read - and the failure surfaces at read
 * time, far from the request that caused it. So the prototype publishes first and verifies, then
 * onboards.
 *
 * Everything here is a pure function over what the two APIs returned, so it is testable without
 * either of them.
 */

/**
 * Which symbols need a published price.
 *
 * <p>The valuation currency against itself is left out on purpose: the backend computes an
 * identity quote as 1 and never reads it from the cache, so publishing it would be noise - and
 * a value someone could later "correct" to something wrong.
 */
export function requiredSymbols(positions, valuationCurrency) {
  const tickers = new Set(positions.map((p) => p.ticker));
  tickers.delete(valuationCurrency);
  return [...tickers].sort().map((ticker) => `${ticker}/${valuationCurrency}`);
}

/** `BTC/EUR` -> the OKX instrument id. */
export function instrumentIdFor(symbol) {
  return symbol.replace("/", "-");
}

/**
 * Turns an OKX ticker reply into the query the publish endpoint expects.
 *
 * `last` is the last traded price; `open24h` is where the day started, which is the only change
 * figure available from a single ticker call.
 */
export function publishQuery({ broker, symbol, ticker }) {
  const [origin, destination] = symbol.split("/");
  const last = Number(ticker.last);
  const open = Number(ticker.open24h);
  const pctChange = Number.isFinite(open) && open !== 0 ? (last - open) / open : 0;

  return {
    broker,
    origin,
    destination,
    amount: last,
    currency: destination,
    pctChange: Number(pctChange.toFixed(8)),
  };
}

export function publishPath(query) {
  const params = new URLSearchParams(
    Object.entries(query).map(([k, v]) => [k, String(v)]));
  return `/quote/publish?${params.toString()}`;
}

/**
 * What the status endpoint still does not know about.
 *
 * <p>This is the check that decides whether onboarding may start. Comparing against what the
 * backend reports - rather than against what we believe we published - is the point: a publish
 * that was accepted but never reached the cache is exactly the failure this guards.
 */
export function missingSymbols(status, required) {
  const present = new Set(status.quotedSymbols ?? []);
  return required.filter((symbol) => !present.has(symbol));
}

/** Instruments OKX does not list, e.g. a PLN pair. They need a conversion chain (task B4). */
export function unquotableSymbols(results) {
  return results.filter((r) => r.ticker === null).map((r) => r.symbol);
}

/**
 * How long to wait after a failed cycle (task E6).
 *
 * <p>A refresh loop that dies on the first hiccup is worse than no loop: the valuation silently
 * stops moving and nothing says so. Exponential backoff keeps it alive through a rate limit or a
 * dropped connection, and the cap stops it from drifting into checking once an hour.
 */
export function backoffDelay(consecutiveFailures, baseMs, maxMs = 5 * 60_000) {
  if (consecutiveFailures <= 0) return baseMs;
  return Math.min(baseMs * 2 ** consecutiveFailures, maxMs);
}

/**
 * One line summarising a refresh cycle.
 *
 * <p>Names what could not be refreshed rather than only what could: a loop that prints "5 ok"
 * while quietly skipping a sixth looks healthy while the portfolio slowly stops being valued in
 * full.
 */
export function describeCycle({ published, failed, at }) {
  const head = `${at} refreshed ${published.length}`;
  return failed.length === 0
    ? `${head}`
    : `${head}, failed ${failed.length}: ${failed.join(", ")}`;
}
