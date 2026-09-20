#!/usr/bin/env node
/**
 * Keeps a portfolio's valuation alive (task E6).
 *
 * Reads the portfolio, fetches a ticker for every asset it holds and republishes - on a loop,
 * until stopped. Without it the quotes loaded during onboarding stay frozen, and the portfolio
 * keeps reporting a price from whenever the last publish happened.
 *
 *   node --env-file=.env.demo okx-quote-loop.mjs --profile demo \\
 *        --portfolio <id> --token <jwt> [--interval 30] [--once]
 *
 * `--once` runs a single cycle, which is what makes the loop usable from a script or a check
 * rather than only by hand.
 */

import { createRestClient, maybePrintHelp, parseArgs, resolveProfile, sleep } from "./okx-common.mjs";
import { quotesNeededBy } from "./okx-portfolio.mjs";
import { backoffDelay, describeCycle, instrumentIdFor, publishPath, publishQuery } from "./okx-quotes.mjs";
import { createVidulumClient } from "./vidulum-client.mjs";

const USAGE = `
okx-quote-loop.mjs - republish quotes so a portfolio's valuation keeps moving

  --profile <demo|prod>   which OKX credentials to use
  --base-url <url>        Vidulum backend (default http://localhost:8080)
  --portfolio <id>        portfolio to keep valued (required)
  --currency <code>       valuation currency (default EUR)
  --token <jwt>           token from onboarding
  --username / --password authenticate instead of passing a token
  --interval <seconds>    seconds between cycles (default 30)
  --once                  run a single cycle and exit
  --verbose               log every request
`;

const args = parseArgs(process.argv.slice(2));
maybePrintHelp(args, USAGE);

const cfg = resolveProfile(args);
const verbose = Boolean(args.verbose);
const currency = args.currency ?? "EUR";
const intervalMs = Number(args.interval ?? 30) * 1000;
const portfolioId = args.portfolio;

if (!portfolioId) {
  console.error("--portfolio is required");
  process.exit(1);
}

const okx = createRestClient({
  key: cfg.key, secret: cfg.secret, passphrase: cfg.passphrase,
  domain: cfg.domain, demo: cfg.demo, verbose,
});

const vidulum = createVidulumClient({
  baseUrl: args["base-url"] ?? "http://localhost:8080",
  token: args.token ?? null,
  verbose,
});

if (!args.token) {
  if (!args.username || !args.password) {
    console.error("pass --token, or --username and --password");
    process.exit(1);
  }
  await vidulum.authenticate({ username: args.username, password: args.password });
}

let stopping = false;
process.on("SIGINT", () => {
  // Finish the cycle in flight rather than leaving half the symbols refreshed.
  console.error("\nstopping after this cycle");
  stopping = true;
});

let consecutiveFailures = 0;

do {
  try {
    const summary = await vidulum.get(`/portfolio/${portfolioId}/${currency}`);
    const symbols = quotesNeededBy(summary, currency);

    const published = [];
    const failed = [];
    for (const symbol of symbols) {
      const [ticker] = await okx.get("/api/v5/market/ticker",
        { instId: instrumentIdFor(symbol) }).catch(() => [null]);
      if (!ticker) {
        failed.push(symbol);
        continue;
      }
      await vidulum.get(publishPath(publishQuery({ broker: "OKX", symbol, ticker })));
      published.push(symbol);
    }

    console.error(describeCycle({ published, failed, at: new Date().toISOString() }));
    consecutiveFailures = 0;
  } catch (error) {
    consecutiveFailures += 1;
    const wait = backoffDelay(consecutiveFailures, intervalMs);
    // Kept alive on purpose: a loop that exits on the first hiccup stops the valuation moving
    // and says nothing about it afterwards.
    console.error(`cycle failed (${error.message}); retrying in ${wait / 1000}s`);
    if (args.once) process.exit(1);
    await sleep(wait);
    continue;
  }

  if (args.once || stopping) break;
  await sleep(intervalMs);
} while (!stopping);
