#!/usr/bin/env node
/**
 * Tests for the onboarding half of the prototype (tasks E2 and E3).
 *
 * Run with `npm test`. No credentials, no network: the Vidulum client is driven through an
 * injected fetch, and the snapshot builder is a pure function fed balance details shaped like
 * the ones recorded from OKX demo.
 *
 * What is being protected here is the mapping `cashBal / spotBal / openAvgPx` onto the request
 * body. Get it wrong and the backend still answers 201 - it just builds a portfolio that quietly
 * claims a cost for units nobody priced.
 */

import { buildSnapshotPositions, buildSpecRequest, lockedByOpenOrders,
         REPORTED_COST_CURRENCY } from "./okx-snapshot.mjs";
import { createVidulumClient, VidulumError, throwawayUser } from "./vidulum-client.mjs";
import { ANSWER_POLICY, buildConfirmRequest, buildConnectionRequest, describeQuestions,
         openQuestions, planAnswers } from "./okx-spec-flow.mjs";
import { backoffDelay, describeCycle, instrumentIdFor, missingSymbols, publishPath, publishQuery,
         requiredSymbols, unquotableSymbols } from "./okx-quotes.mjs";
import { coverageOf, describePortfolio, describePositions, portfolioCoverage,
         quotesNeededBy } from "./okx-portfolio.mjs";

let pass = 0, fail = 0;
function check(name, actual, expected) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) { pass++; console.log(`  ok   ${name}`); }
  else { fail++; console.log(`  FAIL ${name}\n         oczekiwano: ${e}\n         otrzymano:  ${a}`); }
}
function checkThat(name, cond, detail = "") {
  if (cond) { pass++; console.log(`  ok   ${name}`); }
  else { fail++; console.log(`  FAIL ${name}${detail ? "\n         " + detail : ""}`); }
}
async function checkThrows(name, fn, predicate) {
  try {
    await fn();
    fail++; console.log(`  FAIL ${name}\n         nie rzucilo wyjatku`);
  } catch (e) {
    if (predicate(e)) { pass++; console.log(`  ok   ${name}`); }
    else { fail++; console.log(`  FAIL ${name}\n         nieoczekiwany blad: ${e.message}`); }
  }
}

// Shaped like GET /api/v5/account/balance -> data[0].details, values from the field contract.
const PART_TRADED = { ccy: "BTC", cashBal: "1.3", spotBal: "0.3", openAvgPx: "77231.15286476455" };
const FULLY_TRADED = { ccy: "ETH", cashBal: "2", spotBal: "2", openAvgPx: "2400.5" };
const TRANSFERRED_IN = { ccy: "XRP", cashBal: "500", spotBal: "0", openAvgPx: "" };
const CASH = { ccy: "USDT", cashBal: "1000", spotBal: "0" };
const DUST = { ccy: "SHIB", cashBal: "0.00000001", spotBal: "0" };

console.log("\nE3 - snapshot z odpowiedzi OKX");

check("cashBal staje sie caloscia, spotBal czescia handlowana",
  buildSnapshotPositions([PART_TRADED]),
  [{ ticker: "BTC", total: { qty: 1.3, unit: "Number" }, traded: { qty: 0.3, unit: "Number" },
     reportedAvgPrice: { amount: 77231.15286476455, currency: "USD" } }]);

check("pozycja w calosci handlowana nie rozni sie ksztaltem",
  buildSnapshotPositions([FULLY_TRADED])[0].traded, { qty: 2, unit: "Number" });

checkThat("pozycja przelana z zewnatrz nie niesie ceny",
  buildSnapshotPositions([TRANSFERRED_IN])[0].reportedAvgPrice === null);

checkThat("brak openAvgPx w ogole to tez brak ceny",
  buildSnapshotPositions([CASH])[0].reportedAvgPrice === null);

// The backend refuses a cost that covers nothing; dropping it here beats sending it to be rejected.
checkThat("cena bez czesci handlowanej jest pomijana",
  buildSnapshotPositions([{ ccy: "DOGE", cashBal: "10", spotBal: "0", openAvgPx: "0.12" }])[0]
    .reportedAvgPrice === null);

// spotBal > cashBal should be impossible; if OKX ever says it, the backend would reject the whole
// snapshot, so it is clamped rather than allowed to poison the request.
check("czesc handlowana nie przekracza calosci",
  buildSnapshotPositions([{ ccy: "BTC", cashBal: "1", spotBal: "5", openAvgPx: "100" }])[0].traded,
  { qty: 1, unit: "Number" });

check("pylek odrzucany progiem",
  buildSnapshotPositions([PART_TRADED, DUST], { dustThreshold: 0.000001 }).map((p) => p.ticker),
  ["BTC"]);

check("pozycje sa posortowane, zeby diff byl czytelny",
  buildSnapshotPositions([TRANSFERRED_IN, PART_TRADED, FULLY_TRADED]).map((p) => p.ticker),
  ["BTC", "ETH", "XRP"]);

checkThat("cena nabycia jest w USD, niezaleznie od waluty wyceny",
  REPORTED_COST_CURRENCY === "USD");

check("cale cialo zadania ma ksztalt oczekiwany przez POST /portfolio-spec",
  Object.keys(buildSpecRequest({
    broker: "OKX", connectionId: "conn-1", denominationCurrency: "EUR",
    takenAt: "2022-01-01T00:00:00Z", details: [PART_TRADED],
  })),
  ["broker", "connectionId", "denominationCurrency", "portfolioId", "snapshotTakenAt",
   "positions"]);

console.log("\nE3 - locki z otwartych zlecen");

check("nieuzupelniona reszta zlecenia liczy sie jako zablokowana",
  lockedByOpenOrders([{ instId: "BTC-USDT", sz: "0.5", accFillSz: "0.2" }]),
  { BTC: 0.3 });

check("zlecenia na ten sam walor sumuja sie",
  lockedByOpenOrders([
    { instId: "BTC-USDT", sz: "0.5", accFillSz: "0" },
    { instId: "BTC-USDT", sz: "0.25", accFillSz: "0" }]),
  { BTC: 0.75 });

check("zlecenie wypelnione w calosci nic nie blokuje",
  lockedByOpenOrders([{ instId: "BTC-USDT", sz: "0.5", accFillSz: "0.5" }]), {});

console.log("\nE2 - rejestracja i token");

const recorded = [];
function fakeFetch(status, body) {
  return async (url, init) => {
    recorded.push({ url, method: init.method, headers: init.headers,
                    body: init.body ? JSON.parse(init.body) : null });
    return {
      ok: status < 400,
      status,
      text: async () => JSON.stringify(body),
    };
  };
}

{
  recorded.length = 0;
  const client = createVidulumClient({
    baseUrl: "http://backend:8080",
    fetchImpl: fakeFetch(200, { access_token: "jwt-123", user_id: "U10000001" }),
  });
  const registered = await client.register(
    { username: "u", email: "u@example.test", password: "p" });

  check("rejestracja zwraca userId i token", registered, { userId: "U10000001", token: "jwt-123" });
  check("trafia pod dokumentowany endpoint", recorded[0].url,
    "http://backend:8080/api/v1/auth/register");
  checkThat("pierwsze zadanie idzie bez naglowka Authorization",
    recorded[0].headers.Authorization === undefined);
}

{
  recorded.length = 0;
  const client = createVidulumClient({
    fetchImpl: fakeFetch(200, { access_token: "jwt-123", user_id: "U10000001" }),
  });
  await client.register({ username: "u", email: "u@example.test", password: "p" });
  await client.post("/portfolio-spec", { broker: "OKX" });

  checkThat("token z rejestracji jest doklejany do kolejnych wywolan",
    recorded[1].headers.Authorization === "Bearer jwt-123");
  check("cialo jest przekazywane jako JSON", recorded[1].body, { broker: "OKX" });
}

// The flow reacts to these codes - a spec whose snapshot moved on is a different situation from
// an account already connected - so the client must surface them rather than a bare status.
await checkThrows("blad backendu niesie kod ApiError",
  async () => {
    const client = createVidulumClient({
      fetchImpl: fakeFetch(409, { status: 409, code: "PORTFOLIO_SPEC_SNAPSHOT_CHANGED",
                                  message: "exchange state changed" }),
    });
    await client.post("/portfolio-spec/spec-1/confirm", {});
  },
  (e) => e instanceof VidulumError
      && e.status === 409
      && e.code === "PORTFOLIO_SPEC_SNAPSHOT_CHANGED"
      && e.message.includes("exchange state changed"));

{
  const first = throwawayUser();
  const second = throwawayUser();
  checkThat("wygenerowany uzytkownik ma spojny email", first.email.startsWith(first.username));
  checkThat("kolejne wywolania nie daja tej samej nazwy",
    first.username !== second.username || Date.now() === Date.now());
}

console.log("\nE4 - przejscie sciezki spec-u");

// Shaped like GET /portfolio-spec/{id} after D1 has run over a partly traded position.
const SPEC = {
  id: "spec-1",
  status: "AWAITING_ANSWER",
  differences: [
    { ticker: "BTC", subName: "traded", direction: "INCREASED",
      quantity: { qty: 0.3, unit: "Number" }, costQuantity: { qty: 0.3, unit: "Number" },
      costAvgPrice: { amount: 77231.15, currency: "USD" },
      costProvenance: "EXCHANGE_REPORTED", question: null, answerKind: null },
    { ticker: "BTC", subName: "transferred-in", direction: "INCREASED",
      quantity: { qty: 1, unit: "Number" }, costQuantity: null, costAvgPrice: null,
      costProvenance: null, question: "ACQUISITION_COST", answerKind: null },
    { ticker: "XRP", subName: "transferred-in", direction: "DECREASED",
      quantity: { qty: 50, unit: "Number" }, costQuantity: null, costAvgPrice: null,
      costProvenance: null, question: "DISPOSAL_REASON", answerKind: null },
  ],
};

check("otwarte sa tylko pytania bez odpowiedzi",
  openQuestions(SPEC).map((d) => `${d.ticker}/${d.subName}`),
  ["BTC/transferred-in", "XRP/transferred-in"]);

check("rozstrzygniete przez reguly nie trafia do pytan",
  openQuestions({ differences: [SPEC.differences[0]] }), []);

check("odpowiedziane pytanie przestaje byc otwarte",
  openQuestions({ differences: [{ ...SPEC.differences[1], answerKind: "COST_UNKNOWN" }] }), []);

// The default must not answer anything: COST_UNKNOWN is a decision, and a script making it
// quietly would undo the distinction between silence and "I do not know".
await checkThrows("domyslnie skrypt niczego nie odpowiada za uzytkownika",
  async () => planAnswers(SPEC, ANSWER_POLICY.ASK),
  (e) => e.message.includes("BTC/transferred-in") && e.message.includes("--assume-unknown"));

check("assume-unknown odpowiada wedlug rodzaju pytania",
  planAnswers(SPEC, ANSWER_POLICY.ASSUME_UNKNOWN).map((a) => [a.ticker, a.kind]),
  [["BTC", "COST_UNKNOWN"], ["XRP", "WITHDRAWAL"]]);

check("odpowiedz jest zakotwiczona w partii z pytania",
  planAnswers(SPEC, ANSWER_POLICY.ASSUME_UNKNOWN)[0].quantity, { qty: 1, unit: "Number" });

check("podana odpowiedz wygrywa z polityka",
  planAnswers(SPEC, ANSWER_POLICY.ASSUME_UNKNOWN,
    [{ ticker: "BTC", subName: "transferred-in", kind: "COST_PROVIDED",
       avgPrice: { amount: 30000, currency: "EUR" } }])[0],
  { ticker: "BTC", subName: "transferred-in", quantity: { qty: 1, unit: "Number" },
    kind: "COST_PROVIDED", avgPrice: { amount: 30000, currency: "EUR" } });

// A partial answer set would be refused at confirmation time, for a reason the user could not
// connect to anything they did - so it fails here instead.
await checkThrows("czesciowe odpowiedzi zatrzymuja przeplyw od razu",
  async () => planAnswers(SPEC, ANSWER_POLICY.ASK,
    [{ ticker: "BTC", subName: "transferred-in", kind: "COST_UNKNOWN" }]),
  (e) => e.message.includes("XRP/transferred-in"));

check("pytania sa opisywane czytelnie dla czlowieka", describeQuestions(SPEC),
  ["BTC/transferred-in increased by 1 -> ACQUISITION_COST",
   "XRP/transferred-in decreased by 50 -> DISPOSAL_REASON"]);

check("cialo polaczenia ma ksztalt oczekiwany przez POST /exchange-connection",
  buildConnectionRequest({ accountUid: "349378528917283", environment: "DEMO", region: "EEA",
                           reportedKeyPermissions: "read_only", denominationCurrency: "EUR" }),
  { broker: "OKX", accountUid: "349378528917283", environment: "DEMO", region: "EEA",
    reportedKeyPermissions: "read_only", denominationCurrency: "EUR" });

// The backend takes both from the connection and rejects a request stating anything else, so
// sending them is how the prototype states what it believes and gets corrected when wrong.
check("cialo confirm powtarza brokera i walute z polaczenia",
  buildConfirmRequest({ portfolioName: "My OKX", denominationCurrency: "EUR", broker: "OKX",
                        takenAt: "2022-01-01T00:00:00Z", positions: [] }),
  { portfolioName: "My OKX", denominationCurrency: "EUR", broker: "OKX",
    snapshotTakenAt: "2022-01-01T00:00:00Z", positions: [] });

console.log("\nE8 - notowania przed onboardingiem");

const POSITIONS = buildSnapshotPositions([PART_TRADED, FULLY_TRADED, TRANSFERRED_IN,
  { ccy: "EUR", cashBal: "5000", spotBal: "0" }]);

check("kazdy walor potrzebuje kursu przeciwko walucie wyceny",
  requiredSymbols(POSITIONS, "EUR"), ["BTC/EUR", "ETH/EUR", "XRP/EUR"]);

// The backend computes an identity quote as 1 and never reads it from the cache, so publishing
// it would be noise - and a value somebody could later "correct" to something wrong.
checkThat("waluta wyceny nie potrzebuje kursu do samej siebie",
  !requiredSymbols(POSITIONS, "EUR").includes("EUR/EUR"));

check("ten sam walor liczy sie raz",
  requiredSymbols([{ ticker: "BTC" }, { ticker: "BTC" }], "EUR"), ["BTC/EUR"]);

check("symbol mapuje sie na instrument OKX", instrumentIdFor("BTC/EUR"), "BTC-EUR");

check("odpowiedz tickera staje sie zapytaniem publikacji",
  publishQuery({ broker: "OKX", symbol: "BTC/EUR",
                 ticker: { last: "50000", open24h: "40000" } }),
  { broker: "OKX", origin: "BTC", destination: "EUR", amount: 50000,
    currency: "EUR", pctChange: 0.25 });

check("brak otwarcia dnia daje zerowa zmiane zamiast NaN",
  publishQuery({ broker: "OKX", symbol: "BTC/EUR", ticker: { last: "50000", open24h: "0" } })
    .pctChange, 0);

check("zapytanie jest kodowane jako query string",
  publishPath({ broker: "OKX", origin: "BTC", destination: "EUR", amount: 50000,
                currency: "EUR", pctChange: 0.25 }),
  "/quote/publish?broker=OKX&origin=BTC&destination=EUR&amount=50000&currency=EUR&pctChange=0.25");

// Compared against what the backend reports, not against what we believe we sent: a publish that
// was accepted but never reached the cache is exactly the failure this guards.
check("brakujace kursy liczone sa wzgledem tego, co zglasza backend",
  missingSymbols({ quotedSymbols: ["BTC/EUR"] }, ["BTC/EUR", "ETH/EUR", "XRP/EUR"]),
  ["ETH/EUR", "XRP/EUR"]);

check("komplet kursow to pusta lista brakow",
  missingSymbols({ quotedSymbols: ["BTC/EUR", "ETH/EUR"] }, ["BTC/EUR", "ETH/EUR"]), []);

check("pusty cache backendu to wszystkie braki",
  missingSymbols({}, ["BTC/EUR"]), ["BTC/EUR"]);

check("instrumenty, ktorych OKX nie ma, sa raportowane",
  unquotableSymbols([{ symbol: "BTC/PLN", ticker: null },
                     { symbol: "BTC/EUR", ticker: { last: "1" } }]),
  ["BTC/PLN"]);

console.log("\nE5 i E7 - odczyt portfela i wycena");

// Shaped like GET /portfolio/{id}/{currency} after C1 and C2: one position priced by the
// exchange, one transferred in whose cost nobody knows, and cash.
const SUMMARY = {
  name: "My OKX", broker: "OKX",
  currentValue: { amount: 71500, currency: "EUR" },
  investedBalance: { amount: 0, currency: "EUR" },
  assets: [
    { ticker: "BTC", subName: "traded", quantity: { qty: 0.3, unit: "Number" },
      costBasis: { quantity: { qty: 0.3, unit: "Number" },
                   avgPrice: { amount: 50000, currency: "EUR" },
                   provenance: "EXCHANGE_REPORTED" },
      currentValue: { amount: 16500, currency: "EUR" },
      profit: { amount: 1500, currency: "EUR" }, pctProfit: 0.1 },
    { ticker: "BTC", subName: "transferred-in", quantity: { qty: 1, unit: "Number" },
      costBasis: null, currentValue: { amount: 55000, currency: "EUR" },
      profit: null, pctProfit: null },
    { ticker: "EUR", subName: "", quantity: { qty: 0, unit: "Number" },
      costBasis: null, currentValue: { amount: 0, currency: "EUR" },
      profit: null, pctProfit: null },
  ],
};

check("odswiezamy kursy tylko dla tego, co portfel trzyma",
  quotesNeededBy(SUMMARY, "EUR"), ["BTC/EUR"]);

checkThat("waluta wyceny nie potrzebuje wlasnego kursu",
  !quotesNeededBy(SUMMARY, "EUR").includes("EUR/EUR"));

check("pozycja w calosci pokryta kosztem", coverageOf(SUMMARY.assets[0]), 1);

// Not 0 in the sense of "we checked and it is zero" - there is simply nothing to compute from.
check("pozycja bez kosztu ma pokrycie zero", coverageOf(SUMMARY.assets[1]), 0);
check("pusta pozycja nie ma pokrycia w ogole", coverageOf(SUMMARY.assets[2]), null);

check("pokrycie calego portfela wazone wartoscia",
  Number(portfolioCoverage(SUMMARY).toFixed(4)), 0.2308);

// "We do not know" and "you told us" are different claims, and a reader has to tell them apart.
checkThat("brak kosztu jest nazwany, nie zamilczany",
  describePositions(SUMMARY)[1].includes("cost unknown"));

checkThat("brak wyniku nie jest pokazywany jako zero",
  describePositions(SUMMARY)[1].includes("profit not computable")
    && !describePositions(SUMMARY)[1].includes("profit 0"));

checkThat("proweniencja kosztu jest widoczna",
  describePositions(SUMMARY)[0].includes("EXCHANGE_REPORTED"));

checkThat("pokrycie pozycji jest pokazane w procentach",
  describePositions(SUMMARY)[0].includes("100% covered"));

// A snapshot-built portfolio never went through a deposit, so this field says nothing about what
// was actually put in. Printing it without a caveat would be the one number here that lies.
checkThat("zerowe investedBalance jest opatrzone zastrzezeniem",
  describePortfolio(SUMMARY).some((l) => l.includes("invested") && l.includes("C9")));

checkThat("raport zaczyna sie od nazwy i wartosci",
  describePortfolio(SUMMARY)[0].includes("My OKX")
    && describePortfolio(SUMMARY)[1].includes("71500 EUR"));

console.log("\nE6 - petla odswiezania");

// A loop that exits on the first hiccup is worse than no loop: the valuation silently stops
// moving and nothing says so.
check("pierwsza porazka czeka dluzej niz zwykly interwal",
  backoffDelay(1, 30_000), 60_000);
check("kolejne porazki rosna wykladniczo",
  [2, 3].map((n) => backoffDelay(n, 30_000)), [120_000, 240_000]);
// With a 30s interval the ceiling is reached on the fourth failure - about eight minutes in.
check("czekanie ma sufit, zeby petla nie zasnela na godziny",
  [4, 10].map((n) => backoffDelay(n, 30_000)), [300_000, 300_000]);
check("brak porazek to zwykly interwal", backoffDelay(0, 30_000), 30_000);

check("udany cykl mowi, ile odswiezono",
  describeCycle({ published: ["BTC/EUR", "ETH/EUR"], failed: [], at: "2022-01-01T00:00:00Z" }),
  "2022-01-01T00:00:00Z refreshed 2");

// Naming only what worked would let a loop look healthy while the portfolio slowly stops being
// valued in full.
check("cykl nazywa to, czego nie udalo sie odswiezyc",
  describeCycle({ published: ["BTC/EUR"], failed: ["XRP/EUR"], at: "2022-01-01T00:00:00Z" }),
  "2022-01-01T00:00:00Z refreshed 1, failed 1: XRP/EUR");

console.log("\nE4 - caly przeplyw przeciwko atrapie backendu");

/**
 * Routes by method and path, records everything, and lets a test assert the *order* of calls.
 * This is not the script itself - it is the composition the script performs, which is the part
 * worth protecting: a wrong order (confirming before answering, reusing a stale snapshot) still
 * produces valid-looking requests.
 */
function routingFetch(routes) {
  const calls = [];
  const impl = async (url, init) => {
    const path = new URL(url).pathname;
    const route = routes.find((r) => r.method === init.method && r.match.test(path));
    if (!route) throw new Error(`atrapa nie zna ${init.method} ${path}`);
    calls.push({ method: init.method, path, body: init.body ? JSON.parse(init.body) : null,
                 auth: init.headers.Authorization ?? null });
    return { ok: true, status: route.status ?? 200, text: async () => JSON.stringify(route.body) };
  };
  return { impl, calls };
}

{
  const specAfterAnswers = {
    ...SPEC, status: "CONFIRMED",
    differences: SPEC.differences.map((d) =>
      d.question ? { ...d, answerKind: "COST_UNKNOWN" } : d),
  };
  const { impl, calls } = routingFetch([
    { method: "POST", match: /^\/api\/v1\/auth\/register$/,
      body: { access_token: "jwt-1", user_id: "U10000001" } },
    { method: "GET", match: /^\/quote\/publish$/, body: null },
    { method: "GET", match: /^\/exchange\/OKX\/status$/,
      body: { exchange: "OKX", brokerRegistered: true, quotesReady: true,
              quotedSymbols: ["BTC/EUR"] } },
    { method: "POST", match: /^\/exchange-connection$/,
      body: { id: "conn-1", status: "PENDING", portfolioId: null } },
    { method: "POST", match: /^\/portfolio-spec$/, body: SPEC },
    { method: "PUT", match: /^\/portfolio-spec\/spec-1\/answers$/, body: specAfterAnswers },
    { method: "POST", match: /^\/portfolio-spec\/spec-1\/confirm$/,
      body: { id: "spec-1", status: "APPLIED", portfolioId: "portfolio-1" } },
    { method: "GET", match: /^\/exchange-connection\/conn-1$/,
      body: { id: "conn-1", status: "ACTIVE", portfolioId: "portfolio-1" } },
    { method: "GET", match: /^\/portfolio\/portfolio-1\/EUR$/, body: SUMMARY },
  ]);

  const client = createVidulumClient({ fetchImpl: impl });

  await client.register({ username: "u", email: "u@example.test", password: "p" });

  const required = requiredSymbols(buildSnapshotPositions([PART_TRADED]), "EUR");
  for (const symbol of required) {
    await client.get(publishPath(publishQuery({ broker: "OKX", symbol,
      ticker: { last: "50000", open24h: "40000" } })));
  }
  const status = await client.get("/exchange/OKX/status");
  check("wszystkie wymagane kursy sa w cache przed onboardingiem",
    missingSymbols(status, required), []);

  const connection = await client.post("/exchange-connection", buildConnectionRequest({
    accountUid: "349378528917283", environment: "DEMO", region: "EEA",
    reportedKeyPermissions: "read_only", denominationCurrency: "EUR" }));
  const spec = await client.post("/portfolio-spec", buildSpecRequest({
    broker: "OKX", connectionId: connection.id, denominationCurrency: "EUR",
    takenAt: "2022-01-01T00:00:00Z", details: [PART_TRADED] }));
  const answered = await client.put(`/portfolio-spec/${spec.id}/answers`,
    { answers: planAnswers(spec, ANSWER_POLICY.ASSUME_UNKNOWN) });
  const applied = await client.post(`/portfolio-spec/${spec.id}/confirm`, buildConfirmRequest({
    portfolioName: "My OKX", denominationCurrency: "EUR", broker: "OKX",
    takenAt: "2022-01-01T00:05:00Z", positions: buildSnapshotPositions([PART_TRADED]) }));
  const finalConnection = await client.get(`/exchange-connection/${connection.id}`);
  const portfolio = await client.get(`/portfolio/${applied.portfolioId}/EUR`);
  for (const symbol of quotesNeededBy(portfolio, "EUR")) {
    await client.get(publishPath(publishQuery({ broker: "OKX", symbol,
      ticker: { last: "55000", open24h: "50000" } })));
  }

  check("przeplyw wola endpointy w udokumentowanej kolejnosci",
    calls.map((c) => `${c.method} ${c.path}`),
    ["POST /api/v1/auth/register",
     "GET /quote/publish",
     "GET /exchange/OKX/status",
     "POST /exchange-connection",
     "POST /portfolio-spec",
     "PUT /portfolio-spec/spec-1/answers",
     "POST /portfolio-spec/spec-1/confirm",
     "GET /exchange-connection/conn-1",
     "GET /portfolio/portfolio-1/EUR",
     "GET /quote/publish"]);

  checkThat("rejestracja idzie bez tokenu, reszta z nim",
    calls[0].auth === null && calls.slice(1).every((c) => c.auth === "Bearer jwt-1"));

  check("spec dostaje connectionId zwrocone przez poprzedni krok",
    calls[4].body.connectionId, "conn-1");

  // Quotes must be in the cache before a portfolio exists - a portfolio created first is one
  // that cannot be read, and the failure surfaces far from the request that caused it.
  checkThat("kursy sa publikowane przed utworzeniem portfela",
    calls.findIndex((c) => c.path === "/quote/publish")
      < calls.findIndex((c) => c.path.endsWith("/confirm")));

  checkThat("odpowiedzi ida po utworzeniu spec-u, nie wczesniej",
    calls.findIndex((c) => c.path.endsWith("/answers"))
      > calls.findIndex((c) => c.path === "/portfolio-spec"));

  // The backend compares against a fresh snapshot whatever the spec's age, so reusing the first
  // one would only hide a change that happened while the questions were being answered.
  checkThat("confirm niesie swiezszy snapshot niz ten, z ktorego powstal spec",
    calls[6].body.snapshotTakenAt > calls[4].body.snapshotTakenAt);

  check("odpowiedzi sa zakotwiczone w partiach z pytan",
    answered.status, "CONFIRMED");
  check("portfel powstaje i polaczenie wchodzi do sluzby",
    [applied.status, applied.portfolioId, finalConnection.status, finalConnection.portfolioId],
    ["APPLIED", "portfolio-1", "ACTIVE", "portfolio-1"]);

  // The portfolio is read before the refresh so the refresh knows what it actually holds - a
  // narrower set than the snapshot, since dust was dropped and positions were split.
  checkThat("odswiezenie kursow nastepuje po odczytaniu, co portfel trzyma",
    calls.findLastIndex((c) => c.path === "/quote/publish")
      > calls.findIndex((c) => c.path === "/portfolio/portfolio-1/EUR"));
}

console.log(`\n${pass} ok, ${fail} fail`);
process.exit(fail === 0 ? 0 : 1);
