#!/usr/bin/env node
/**
 * End-to-end smoke test against a running backend.
 *
 *   node --env-file=.env.demo okx-smoke.mjs --profile demo \
 *        --base-url http://localhost:9090 [--iterations 3]
 *
 * Runs the whole onboarding path repeatedly - a fresh user each time - and checks the state the
 * backend actually ends up in after every step, rather than only that the calls returned 2xx.
 *
 * OKX is read **once**: the account snapshot and the tickers are fetched up front and reused
 * across iterations. What is being exercised here is the backend, and hammering an exchange to
 * re-learn the same balances would only add rate limits to the failure modes.
 */

import { createRestClient, maybePrintHelp, parseArgs, resolveProfile, sleep } from "./okx-common.mjs";
import { coverageAgreement, describePortfolio, portfolioCoverage,
         quotesNeededBy } from "./okx-portfolio.mjs";
import { instrumentIdFor, missingSymbols, publishPath, publishQuery, requiredSymbols,
         resolveTicker } from "./okx-quotes.mjs";
import { buildConnectionRequest, planAnswers, ANSWER_POLICY } from "./okx-spec-flow.mjs";
import { buildSnapshotPositions } from "./okx-snapshot.mjs";
import { createVidulumClient, throwawayUser } from "./vidulum-client.mjs";

const USAGE = `
okx-smoke.mjs - run the whole onboarding path against a live backend, repeatedly

  --profile <demo|prod>   which OKX credentials to use
  --base-url <url>        Vidulum backend (default http://localhost:9090)
  --currency <code>       valuation currency (default EUR)
  --iterations <n>        how many users to onboard (default 3)
  --dust <number>         drop holdings at or below this quantity (default 0)
  --verbose               log every request
`;

const args = parseArgs(process.argv.slice(2));
maybePrintHelp(args, USAGE);

const cfg = resolveProfile(args);
const baseUrl = args["base-url"] ?? "http://localhost:9090";
const currency = args.currency ?? "EUR";
const iterations = Number(args.iterations ?? 3);
const dust = Number(args.dust ?? 0);
const verbose = Boolean(args.verbose);

let checks = 0, failures = 0;
function check(label, condition, detail = "") {
  checks++;
  if (condition) {
    console.log(`    ok   ${label}`);
  } else {
    failures++;
    console.log(`    FAIL ${label}${detail ? `\n           ${detail}` : ""}`);
  }
}

const okx = createRestClient({
  key: cfg.key, secret: cfg.secret, passphrase: cfg.passphrase,
  domain: cfg.domain, demo: cfg.simulated, verbose,
});

// --- read OKX once ----------------------------------------------------------------------------

console.log("reading OKX account...");
const [config] = await okx.get("/api/v5/account/config");
const [balance] = await okx.get("/api/v5/account/balance");
// Funding as well as Trading (E10) - a deposit left where it landed lives only here.
const funding = await okx.get("/api/v5/asset/balances");
const positions = buildSnapshotPositions({
  trading: balance.details ?? [], funding: funding ?? [], dustThreshold: dust });
const required = requiredSymbols(positions, currency);

console.log(`  uid ${config.uid}, perm ${config.perm}`);
console.log(`  ${positions.length} position(s): ${positions.map((p) => p.ticker).join(", ") || "none"}`);
console.log(`  quotes needed: ${required.join(", ") || "none"}`);

if (positions.length === 0) {
  console.error("\nThe account holds nothing. There is no portfolio to build - " +
    "fund the demo account or lower --dust.");
  process.exit(1);
}

const tickers = new Map();
const derived = [];
for (const symbol of required) {
  const resolved = await resolveTicker(okx, symbol);
  if (!resolved.ticker) continue;
  tickers.set(symbol, resolved.ticker);
  if (resolved.derived) derived.push(`${symbol} via ${resolved.instId}`);
}
console.log(`  priced ${tickers.size}/${required.length} by OKX`);
if (derived.length > 0) {
  console.log(`  derived from a substitute instrument: ${derived.join(", ")}`);
}
const unpriced = required.filter((s) => !tickers.has(s));
if (unpriced.length > 0) {
  console.log(`  no instrument at all for: ${unpriced.join(", ")} (task B4)`);
}

// --- iterate ------------------------------------------------------------------------------------

const outcomes = [];

for (let run = 1; run <= iterations; run++) {
  console.log(`\n=== iteration ${run}/${iterations} ===`);
  const vidulum = createVidulumClient({ baseUrl, verbose });
  const outcome = { run };

  try {
    // 1. a fresh user
    const credentials = throwawayUser("smoke");
    const registered = await vidulum.register(credentials);
    check("registered a user", Boolean(registered.userId) && Boolean(registered.token),
      JSON.stringify(registered));
    outcome.userId = registered.userId;

    // 2. quotes, before anything is created
    for (const [symbol, ticker] of tickers) {
      await vidulum.get(publishPath(publishQuery({ broker: "OKX", symbol, ticker })));
    }
    // Kafka carries the publish, so the cache fills a moment later.
    const status = await waitFor(
      () => vidulum.get("/exchange/OKX/status"),
      (s) => missingSymbols(s, [...tickers.keys()]).length === 0);

    check("broker is registered", status.brokerRegistered === true, JSON.stringify(status));
    check("every published quote reached the cache",
      missingSymbols(status, [...tickers.keys()]).length === 0,
      `missing: ${missingSymbols(status, [...tickers.keys()]).join(", ")}`);
    check("reachability is reported as unknown, not invented",
      status.reachability === "UNKNOWN", status.reachability);

    // 3. connect
    const connection = await vidulum.post("/exchange-connection", buildConnectionRequest({
      accountUid: config.uid,
      environment: cfg.simulated ? "DEMO" : "LIVE",
      region: args.region ?? "EEA",
      reportedKeyPermissions: config.perm,
      denominationCurrency: currency,
    }));
    check("connection starts pending with no portfolio",
      connection.status === "PENDING" && connection.portfolioId === null,
      JSON.stringify(connection));
    outcome.connectionId = connection.id;

    // 4. specification
    const spec = await vidulum.post("/portfolio-spec", {
      broker: "OKX",
      connectionId: connection.id,
      denominationCurrency: currency,
      portfolioId: null,
      snapshotTakenAt: new Date().toISOString(),
      positions,
    });
    check("specification lists a difference per position kind",
      spec.differences.length > 0, JSON.stringify(spec.differences?.length));
    check("nothing the exchange priced is left as a question",
      spec.differences.filter((d) => d.costProvenance === "EXCHANGE_REPORTED")
        .every((d) => d.question === null));
    outcome.specId = spec.id;
    outcome.differences = spec.differences.length;

    // 5. answers, if any
    const answers = planAnswers(spec, ANSWER_POLICY.ASSUME_UNKNOWN);
    let current = spec;
    if (answers.length > 0) {
      current = await vidulum.put(`/portfolio-spec/${spec.id}/answers`, { answers });
      check("answering everything leaves the spec confirmed",
        current.status === "CONFIRMED", current.status);
      check("an unknown cost is recorded as a decision, not a gap",
        current.differences.filter((d) => d.answerKind === "COST_UNKNOWN")
          .every((d) => d.costProvenance === null));
    }

    // 6. confirm against a fresh snapshot
    const [freshBalance] = await okx.get("/api/v5/account/balance");
    const freshFunding = await okx.get("/api/v5/asset/balances");
    const applied = await vidulum.post(`/portfolio-spec/${spec.id}/confirm`, {
      portfolioName: `smoke ${run}`,
      denominationCurrency: currency,
      broker: "OKX",
      snapshotTakenAt: new Date().toISOString(),
      positions: buildSnapshotPositions({
        trading: freshBalance.details ?? [], funding: freshFunding ?? [], dustThreshold: dust }),
    });
    check("confirmation applies the specification", applied.status === "APPLIED", applied.status);
    check("a portfolio id comes back", Boolean(applied.portfolioId));
    outcome.portfolioId = applied.portfolioId;

    // 7. the connection is in service
    const inService = await vidulum.get(`/exchange-connection/${connection.id}`);
    check("connection is active and points at the portfolio",
      inService.status === "ACTIVE" && inService.portfolioId === applied.portfolioId,
      JSON.stringify(inService));

    // 8. read the portfolio
    const portfolio = await vidulum.get(`/portfolio/${applied.portfolioId}/${currency}`);
    check("the portfolio can be valued at all", Boolean(portfolio.currentValue));
    check("it holds something", (portfolio.assets ?? []).length > 0);
    check("a position with no known cost reports no gain, not zero",
      (portfolio.assets ?? []).filter((a) => !a.costBasis)
        .every((a) => a.unrealisedProfit === null));

    // C3 + C4: the backend now answers both "what is the result" and "of how much".
    const agreement = coverageAgreement(portfolio);
    check("backend coverage matches our own reckoning of it (C4)",
      agreement.agree,
      `backend ${agreement.theirs}, ours ${agreement.ours}`);
    check("every position reports its own coverage",
      (portfolio.assets ?? []).every((a) => typeof a.coverage === "number"),
      JSON.stringify((portfolio.assets ?? []).map((a) => [a.ticker, a.coverage])));

    const expectedStatus = portfolioCoverage(portfolio) >= 0.5 ? "COMPUTED" : "WITHHELD_LOW_COVERAGE";
    check("a result computed from a minority of the value is withheld, not printed (C4)",
      portfolio.profitStatus === expectedStatus,
      `status ${portfolio.profitStatus}, coverage ${agreement.theirs}`);
    check("a withheld result carries no figure to misread",
      portfolio.profitStatus !== "WITHHELD_LOW_COVERAGE"
        || (portfolio.unrealisedProfit === null && portfolio.pctUnrealisedProfit === null),
      JSON.stringify([portfolio.unrealisedProfit, portfolio.pctUnrealisedProfit]));

    // The defect C3 fixed, checked where it actually appeared: the result used to be
    // "value - invested", and a snapshot-built portfolio invested nothing, so the old formula
    // turned the whole portfolio into profit.
    check("the whole portfolio is not reported as gain just because nothing was deposited (C3)",
      portfolio.unrealisedProfit === null
        || portfolio.unrealisedProfit.amount !== portfolio.currentValue.amount,
      JSON.stringify([portfolio.unrealisedProfit, portfolio.currentValue]));
    // C9 and C12, checked where the old field was at its worst: onboarding passes through no
    // deposit, so "invested" used to answer 0 beside the whole balance. The ledger now opens with
    // one entry worth what the account held on arrival, marked as a snapshot and not as a deposit.
    check("the ledger opens with what the account was worth, not with a zero (C9, C12)",
      portfolio.contributionStatus === "COMPUTED"
        && portfolio.netContributions?.amount > 0,
      JSON.stringify([portfolio.contributionStatus, portfolio.netContributions]));
    check("the opening entry is valued in the portfolio's own currency",
      portfolio.netContributions?.currency === currency,
      JSON.stringify(portfolio.netContributions));
    // C5: the measure that can speak when the result cannot. Checked against our own arithmetic
    // rather than against itself - the backend and this script must agree on what it means.
    check("wealth change is the value less what was put in (C5)",
      portfolio.wealthChange !== null && portfolio.wealthChange !== undefined
        && Math.abs(portfolio.wealthChange.amount
            - (portfolio.currentValue.amount - portfolio.netContributions.amount)) < 0.01,
      JSON.stringify([portfolio.wealthChange, portfolio.currentValue, portfolio.netContributions]));
    check("wealth change is stated even though the result is withheld (C5 vs C4)",
      portfolio.profitStatus !== "WITHHELD_LOW_COVERAGE" || portfolio.wealthChange !== null,
      `profit ${portfolio.profitStatus}, growth ${JSON.stringify(portfolio.wealthChange)}`);

    // D5: every position used to arrive entirely free, so an account with an open order told its
    // owner they could move money the exchange would refuse to release.
    const frozenByTicker = Object.fromEntries(
      positions.map((p) => [p.ticker, p.frozen?.qty ?? 0]));
    const lockedByTicker = {};
    for (const asset of portfolio.assets ?? []) {
      lockedByTicker[asset.ticker] = (lockedByTicker[asset.ticker] ?? 0) + (asset.locked?.qty ?? 0);
    }
    check("what the exchange froze arrives as locked (D5)",
      Object.entries(lockedByTicker).every(([ticker, locked]) =>
        Math.abs(locked - (frozenByTicker[ticker] ?? 0)) < 1e-8),
      JSON.stringify([lockedByTicker, frozenByTicker]));
    check("every position still adds up: locked plus free is what is held",
      (portfolio.assets ?? []).every((a) =>
        Math.abs((a.locked?.qty ?? 0) + (a.free?.qty ?? 0) - a.quantity.qty) < 1e-8),
      JSON.stringify((portfolio.assets ?? []).map((a) => [a.ticker, a.locked, a.free, a.quantity])));

    check("a ledger fully valued reports full coverage",
      portfolio.contributionCoverage === 1,
      String(portfolio.contributionCoverage));
    outcome.value = portfolio.currentValue;

    // 9. refresh quotes and read again
    const needed = quotesNeededBy(portfolio, currency);
    const before = portfolio.currentValue.amount;
    for (const symbol of needed) {
      const { ticker } = await resolveTicker(okx, symbol);
      if (ticker) {
        await vidulum.get(publishPath(publishQuery({ broker: "OKX", symbol, ticker })));
      }
    }
    await sleep(1500);
    const reread = await vidulum.get(`/portfolio/${applied.portfolioId}/${currency}`);
    check("the portfolio still values after republishing",
      Boolean(reread.currentValue), JSON.stringify(reread.currentValue));
    check("refreshing narrows to what is actually held",
      needed.length <= required.length,
      `needed ${needed.join(",")} vs snapshot ${required.join(",")}`);
    outcome.valueAfterRefresh = reread.currentValue;
    outcome.moved = reread.currentValue.amount !== before;

    console.log("");
    describePortfolio(reread).forEach((line) => console.log(`    ${line}`));

    outcome.ok = true;
  } catch (error) {
    failures++;
    outcome.ok = false;
    outcome.error = `${error.code ?? error.name}: ${error.message}`;
    console.log(`    FAIL iteration blew up\n           ${outcome.error}`);
  }

  outcomes.push(outcome);
}

// --- report ---------------------------------------------------------------------------------------

console.log("\n=== summary ===");
outcomes.forEach((o) => console.log(
  `  run ${o.run}: ${o.ok ? "ok" : "FAILED"} ` +
  `${o.portfolioId ? `portfolio ${o.portfolioId} worth ${o.value?.amount} ${o.value?.currency}` : ""}` +
  `${o.error ? ` - ${o.error}` : ""}`));
console.log(`\n${checks - failures}/${checks} checks passed`);
process.exit(failures === 0 ? 0 : 1);

/** Kafka delivers the publish asynchronously, so the cache fills a beat after the call returns. */
async function waitFor(read, predicate, attempts = 20, delayMs = 500) {
  let last;
  for (let i = 0; i < attempts; i++) {
    last = await read();
    if (predicate(last)) return last;
    await sleep(delayMs);
  }
  return last;
}
