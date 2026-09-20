#!/usr/bin/env node
/**
 * Walks a real OKX account all the way to a Vidulum portfolio (tasks E2, E3 and E4).
 *
 *   node --env-file=.env.demo okx-onboard.mjs --profile demo [flags]
 *
 * Stages, in order:
 *   1. read the account from OKX                     (E1, already proven)
 *   2. register a Vidulum user and keep the JWT      (E2)
 *   3. publish quotes and check they landed          (E8)
 *   4. connect the exchange account                  (A8)
 *   5. build the snapshot and create a specification (E3, D1)
 *   6. answer what only a human can answer           (D2)
 *   7. confirm against a *fresh* snapshot            (D3)
 *   8. refresh quotes for what the portfolio holds   (E5)
 *   9. read the portfolio back and report it         (E7)
 *
 * By default it stops after stage 5 and prints the open questions. Answering on someone's behalf
 * needs `--assume-unknown` or `--answers`, because "I do not know" is a decision and a script
 * should not make it quietly.
 *
 * The prototype is deliberately not idempotent: a rerun against a dirty database fails loudly
 * rather than half-working.
 */

import { readFileSync, writeFileSync } from "node:fs";
import { createRestClient, maybePrintHelp, parseArgs, resolveProfile } from "./okx-common.mjs";
import { buildSpecRequest, buildSnapshotPositions, lockedByOpenOrders } from "./okx-snapshot.mjs";
import { describePortfolio, quotesNeededBy } from "./okx-portfolio.mjs";
import { missingSymbols, publishPath, publishQuery, requiredSymbols, resolveTicker,
         unquotableSymbols } from "./okx-quotes.mjs";
import { ANSWER_POLICY, buildConfirmRequest, buildConnectionRequest, describeQuestions,
         openQuestions, planAnswers } from "./okx-spec-flow.mjs";
import { createVidulumClient, throwawayUser, VidulumError } from "./vidulum-client.mjs";

const USAGE = `
okx-onboard.mjs - walk an OKX account into a Vidulum portfolio

  --profile <demo|prod>   which OKX credentials to use
  --base-url <url>        Vidulum backend (default http://localhost:8080)
  --currency <code>       valuation currency of the connection (default EUR)
  --region <code>         OKX region: EEA | GLOBAL | US (default EEA)
  --name <text>           portfolio name (default "OKX <uid>")
  --dry-run               build the snapshot only, do not talk to Vidulum
  --assume-unknown        answer every open question with "I do not know"
  --answers <file>        JSON array of {ticker, subName, kind, avgPrice}
  --skip-quotes           do not publish quotes (the portfolio will not be readable)
  --dust <number>         drop holdings at or below this quantity (default 0)
  --out <file>            write the whole run as JSON
  --verbose               log every request
`;

const args = parseArgs(process.argv.slice(2));
maybePrintHelp(args, USAGE);

const cfg = resolveProfile(args);
const verbose = Boolean(args.verbose);
const dryRun = Boolean(args["dry-run"]);
const currency = args.currency ?? "EUR";
const region = args.region ?? "EEA";
const dust = Number(args.dust ?? 0);
const policy = args["assume-unknown"] ? ANSWER_POLICY.ASSUME_UNKNOWN : ANSWER_POLICY.ASK;
const suppliedAnswers = args.answers
  ? JSON.parse(readFileSync(args.answers, "utf8"))
  : [];

const okx = createRestClient({
  key: cfg.key, secret: cfg.secret, passphrase: cfg.passphrase,
  domain: cfg.domain, demo: cfg.simulated, verbose,
});

const run = { stages: [] };
function stage(name, detail) {
  run.stages.push({ name, ...detail });
  console.error(`[${run.stages.length}] ${name}`);
}

// --- 1. what the exchange says -----------------------------------------------------------------

const [config] = await okx.get("/api/v5/account/config");
if (config.perm !== "read_only") {
  // The backend refuses anything broader; failing here saves a round trip and says why.
  console.error(`This key reports "${config.perm}". Vidulum accepts "read_only" only.`);
  process.exit(1);
}

async function readBalance() {
  const [balance] = await okx.get("/api/v5/account/balance");
  return balance.details ?? [];
}

const details = await readBalance();
const takenAt = new Date().toISOString();
const openOrders = await okx.paginate("/api/v5/trade/orders-pending", {}, { cursorField: "ordId" });

run.exchange = {
  uid: config.uid,
  environment: cfg.simulated ? "DEMO" : "LIVE",
  keyPermissions: config.perm,
};
// Not part of the snapshot: a lock changes what is available, not what is held (task D5).
run.lockedByOpenOrders = lockedByOpenOrders(openOrders);
stage("read OKX account", { positions: buildSnapshotPositions(details, { dustThreshold: dust }).length });

if (dryRun) {
  run.specRequest = buildSpecRequest({
    broker: "OKX", connectionId: null, takenAt, details, dustThreshold: dust,
  });
  finish();
}

// --- 2. who we are in Vidulum (E2) ---------------------------------------------------------------

const vidulum = createVidulumClient({
  baseUrl: args["base-url"] ?? "http://localhost:8080",
  verbose,
});

const credentials = throwawayUser();
const registered = await vidulum.register(credentials);
run.vidulum = { username: credentials.username, userId: registered.userId };
stage("registered user", { userId: registered.userId });

// --- 3. quotes, before anything is created (E8) ---------------------------------------------------

const snapshotPositions = buildSnapshotPositions(details, { dustThreshold: dust });
const required = requiredSymbols(snapshotPositions, currency);

if (!args["skip-quotes"] && required.length > 0) {
  const results = [];
  for (const symbol of required) {
    const { ticker, derived } = await resolveTicker(okx, symbol);
    results.push({ symbol, ticker: ticker ?? null });
    if (!ticker) continue;
    if (derived) console.error(`  ${symbol} priced through a substitute instrument (task B4)`);
    await vidulum.get(publishPath(publishQuery({ broker: "OKX", symbol, ticker })));
  }

  const unquotable = unquotableSymbols(results);
  if (unquotable.length > 0) {
    // OKX lists no such instrument - a PLN pair, for instance. Converting through another pair
    // is task B4; saying so is better than publishing a number we made up.
    console.error(`  OKX has no instrument for: ${unquotable.join(", ")}`);
  }

  // Checked against what the backend reports, not against what we believe we sent: a publish
  // that was accepted but never reached the cache is exactly the failure this guards.
  const status = await vidulum.get("/exchange/OKX/status");
  const missing = missingSymbols(status, required);
  run.quotes = { required, published: required.length - missing.length, missing };
  stage("published quotes", { required: required.length, missing: missing.length });

  if (missing.length > 0) {
    console.error(`  Missing after publishing: ${missing.join(", ")}`);
    console.error("  GET /portfolio would fail on those. Continuing anyway - " +
      "the specification does not need quotes, only reading the portfolio does.");
  }
}

// --- 4. connect the exchange account (A8) ---------------------------------------------------------

const connection = await attempt(() => vidulum.post("/exchange-connection",
  buildConnectionRequest({
    accountUid: config.uid,
    environment: run.exchange.environment,
    region,
    reportedKeyPermissions: config.perm,
    denominationCurrency: currency,
  })));
run.connection = { id: connection.id, status: connection.status };
stage("connected exchange account", { connectionId: connection.id, status: connection.status });

// --- 5. the specification (E3 + D1) -----------------------------------------------------------------

const specRequest = buildSpecRequest({
  broker: "OKX", connectionId: connection.id, takenAt, details, dustThreshold: dust,
});
run.specRequest = specRequest;

const spec = await attempt(() => vidulum.post("/portfolio-spec", specRequest));
run.spec = { id: spec.id, status: spec.status, differences: spec.differences.length };
stage("created specification", { specId: spec.id, status: spec.status });

const questions = describeQuestions(spec);
if (questions.length > 0) {
  console.error(`  ${questions.length} question(s) only you can answer:`);
  questions.forEach((q) => console.error(`    - ${q}`));
}

if (policy === ANSWER_POLICY.ASK && suppliedAnswers.length === 0 && questions.length > 0) {
  run.stoppedBecause = "questions need answering";
  console.error("\nStopping here. Re-run with --assume-unknown or --answers to continue.");
  finish();
}

// --- 6. answers (D2) --------------------------------------------------------------------------

if (questions.length > 0) {
  const answers = planAnswers(spec, policy, suppliedAnswers);
  const answered = await attempt(() => vidulum.put(`/portfolio-spec/${spec.id}/answers`, { answers }));
  run.answers = answers;
  stage("answered", { count: answers.length, status: answered.status });

  if (openQuestions(answered).length > 0) {
    run.stoppedBecause = "not every question was answered";
    finish();
  }
}

// --- 7. confirmation against a fresh snapshot (D3) ----------------------------------------------

// Read the account again. The backend compares regardless of how old the specification is, so
// reusing the first reply would only hide a change that happened while questions were answered.
const freshDetails = await readBalance();
const freshTakenAt = new Date().toISOString();

const applied = await attempt(() => vidulum.post(`/portfolio-spec/${spec.id}/confirm`,
  buildConfirmRequest({
    portfolioName: args.name ?? `OKX ${config.uid}`,
    denominationCurrency: currency,
    broker: "OKX",
    takenAt: freshTakenAt,
    positions: buildSnapshotPositions(freshDetails, { dustThreshold: dust }),
  })));

run.portfolioId = applied.portfolioId;
stage("confirmed", { status: applied.status, portfolioId: applied.portfolioId });

const finalConnection = await vidulum.get(`/exchange-connection/${connection.id}`);
run.connection = { id: finalConnection.id, status: finalConnection.status,
                   portfolioId: finalConnection.portfolioId };
stage("connection in service", { status: finalConnection.status });

// --- 8. quotes for what the portfolio actually holds (E5) ---------------------------------------

// Narrower than what was published before onboarding: dust was dropped and positions were split,
// so republishing the whole snapshot would keep prices alive for assets nobody holds.
let summary = await vidulum.get(`/portfolio/${run.portfolioId}/${currency}`);

if (!args["skip-quotes"]) {
  const needed = quotesNeededBy(summary, currency);
  for (const symbol of needed) {
    const { ticker } = await resolveTicker(okx, symbol);
    if (!ticker) continue;
    await vidulum.get(publishPath(publishQuery({ broker: "OKX", symbol, ticker })));
  }
  run.refreshedQuotes = needed;
  stage("refreshed quotes", { symbols: needed.length });

  // Read again so the report shows the prices that were just published rather than the ones the
  // portfolio happened to be created with.
  summary = await vidulum.get(`/portfolio/${run.portfolioId}/${currency}`);
}

// --- 9. what it is worth (E7) ---------------------------------------------------------------------

run.portfolio = summary;
stage("read portfolio", { assets: summary.assets.length });

console.error("");
describePortfolio(summary).forEach((line) => console.error(line));
console.error("");

finish();

// --- helpers ------------------------------------------------------------------------------------

/** Surfaces the backend's own error code, which is what the flow reacts to. */
async function attempt(call) {
  try {
    return await call();
  } catch (error) {
    if (error instanceof VidulumError) {
      console.error(`\n${error.code}: ${error.body?.message ?? error.message}`);
      if (error.code === "PORTFOLIO_SPEC_SNAPSHOT_CHANGED") {
        console.error("The account moved while this ran. The specification was recomputed - " +
          "re-read it and answer again.");
      }
      if (error.code === "EXCHANGE_ACCOUNT_ALREADY_CONNECTED") {
        console.error("This account is already connected. The prototype is not idempotent - " +
          "start from a clean database.");
      }
    }
    throw error;
  }
}

function finish() {
  const json = JSON.stringify(run, null, 2);
  if (args.out) {
    writeFileSync(args.out, json);
    console.error(`Written to ${args.out}`);
  } else {
    console.log(json);
  }
  process.exit(0);
}
