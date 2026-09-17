#!/usr/bin/env node
/**
 * Tests for the order-rendering helpers in okx-common.mjs.
 *
 * Run with `npm test`. No credentials, no network, no dependencies - this is the only check
 * in tools/okx that works on a fresh clone.
 *
 * Two kinds of input, deliberately kept apart:
 *   REAL       - frames recorded from OKX demo on 2026-09-12 (fixtures/orders-lifecycle.json).
 *                These are ground truth; if a test here fails, the renderer is wrong.
 *   SYNTHETIC  - hand-built payloads for variants the account never produced (trailing stop,
 *                a rejected attached algo, legacy top-level stop-loss, partial protection size).
 *                They follow the real field shape but are guesses about values, and are
 *                labelled as such so nobody mistakes them for evidence.
 */

import { readFileSync } from "node:fs";
import { formatOrder, formatProtection, diffOrder, foldBalances,
         formatBalancePosition } from "./okx-common.mjs";
import { ACCOUNT_FIELDS, ACCOUNT_DETAIL_FIELDS, BALPOS_FIELDS, BALPOS_BALDATA_FIELDS,
         BALPOS_POSDATA_FIELDS, BALPOS_TRADES_FIELDS, ACCOUNT_EVENT_TYPES,
         auditAgainst } from "./okx-account-contract.mjs";
import { ORDER_FIELDS, auditOrderPayload, validateEnums, explainCode,
         CANCEL_SOURCE, TERMINAL_STATES, BALANCE_POSITION_EVENT_TYPES } from "./okx-order-contract.mjs";

const fx = JSON.parse(readFileSync(new URL("./fixtures/orders-lifecycle.json", import.meta.url), "utf8"));
const REAL = fx.frames;
const ACC = fx.account;
const BP = fx.balanceAndPosition;

let pass = 0, fail = 0;
function check(name, actual, expected) {
  const a = typeof actual === "string" ? actual : JSON.stringify(actual);
  const e = typeof expected === "string" ? expected : JSON.stringify(expected);
  if (a === e) { pass++; console.log(`  ok   ${name}`); }
  else { fail++; console.log(`  FAIL ${name}\n         oczekiwano: ${e}\n         otrzymano:  ${a}`); }
}
function checkThat(name, cond, detail = "") {
  if (cond) { pass++; console.log(`  ok   ${name}`); }
  else { fail++; console.log(`  FAIL ${name}${detail ? "\n         " + detail : ""}`); }
}

// ---------------------------------------------------------------- contract
console.log("\nKONTRAKT (dane prawdziwe)");
{
  const unknownAll = new Set();
  for (const f of REAL) for (const k of auditOrderPayload(f.data).unknown) unknownAll.add(k);
  checkThat("zadne pole ramki nie jest spoza kontraktu", unknownAll.size === 0,
    `nieznane pola: ${[...unknownAll].join(", ")} - przeladuj fixtures i uzupelnij kontrakt`);

  const shapes = new Set(REAL.map((f) => Object.keys(f.data).sort().join("|")));
  checkThat("kazda ramka ma identyczny zestaw kluczy (pelny stan, nie delta)", shapes.size === 1,
    `roznych zestawow: ${shapes.size}`);

  const n = Object.keys(REAL[0].data).length;
  check("liczba pol w ramce WS", n, 71);
  checkThat("kontrakt opisuje dokladnie tyle pol", Object.keys(ORDER_FIELDS).length === n);
  checkThat("cancelSourceReason NIE wystepuje po WS", !("cancelSourceReason" in REAL[0].data),
    "jesli sie pojawilo, OKX zmienil kontrakt - zaktualizuj dokumentacje");
  const enumProblems = REAL.flatMap((f) => validateEnums(f.data));
  checkThat("kazda zaobserwowana wartosc miesci sie w udokumentowanej enumeracji",
    enumProblems.length === 0, enumProblems.join("; "));

  const withEnum = Object.values(ORDER_FIELDS).filter((f) => f.documented).length;
  checkThat("kontrakt niesie enumeracje dla pol cyklu zycia", withEnum >= 8,
    `pol z enumeracja: ${withEnum}`);
  const undocumented = Object.entries(ORDER_FIELDS).filter(([, f]) => f.note.startsWith("UNDOCUMENTED"));
  check("pola obecne na lączu, a nieopisane przez OKX", undocumented.map(([k]) => k), ["slippage"]);
  check("brak opisu OKX dokladnie tam, gdzie brak dokumentacji",
    Object.entries(ORDER_FIELDS).filter(([, f]) => f.docs === null).map(([k]) => k), ["slippage"]);
  checkThat("kazde pole ma wlasny opis i wlasna notatke",
    Object.values(ORDER_FIELDS).every((f) => f.note && (f.docs === null || f.docs !== f.note)));

  check("kod anulowania tlumaczony na tekst", explainCode("cancelSource", "1"), "canceled by user");
  check("kod wyniku zmiany tlumaczony na tekst", explainCode("amendResult", "0"), "success");
  checkThat("tablica kodow anulowania jest kompletna wzgledem dokumentacji",
    Object.keys(CANCEL_SOURCE).length === 30, `kodow: ${Object.keys(CANCEL_SOURCE).length}`);
  check("nieznany kod nie jest zmyslany", explainCode("cancelSource", "999"), null);
}

// ---------------------------------------------------------------- renderowanie
console.log("\nRENDEROWANIE ZLECEN (dane prawdziwe)");
{
  const byScenario = (s) => REAL.find((f) => f.scenario.startsWith(s)).data;

  check("utworzenie bez ochrony", formatProtection(byScenario("created: limit buy placed")), "");
  check("stop loss doczepiony przy tworzeniu",
    formatProtection(byScenario("created: limit buy with a stop-loss")),
    " [sl@45000(last)->market algoId=3917087859435106306]");
  check("take profit obok stop lossa",
    formatProtection(byScenario("amended: take-profit added")),
    " [tp@80000(last)->market sl@44000(last)->market algoId=3917087859435106306]");
  checkThat("linkedAlgoOrd:{algoId:\"\"} nie renderuje sie jako ochrona",
    !formatProtection(byScenario("created: limit buy placed")).includes("linkedAlgo"));
  checkThat("rozmiar zlecenia nie wycieka jako ochrona",
    !formatProtection(byScenario("created: limit buy placed")).includes("sz=0.0002"));
  check("linia wykonania",
    formatOrder(byScenario("filled:")),
    "BTC-EUR buy limit state=filled filled=0.0002/0.0002 px=66700 avgPx=66588.7 ordId=3917088694739136513");
}

// ---------------------------------------------------------------- cykl zycia
console.log("\nCYKL ZYCIA - diff kolejnych ramek (dane prawdziwe)");
{
  const d = (a, b) => diffOrder(REAL[a].data, REAL[b].data);

  checkThat("amend samej ceny zglasza px", d(0, 1).some((c) => c.startsWith("px: 50000 -> 51000")));
  checkThat("amend samej ceny zglasza amendResult", d(0, 1).some((c) => c.startsWith("amendResult:")));
  checkThat("anulowanie zglasza state", d(1, 2).some((c) => c === "state: live -> canceled"));
  checkThat("anulowanie zglasza cancelSource z wyjasnieniem",
    d(1, 2).some((c) => c === "cancelSource: - -> 1 (canceled by user)"));
  checkThat("anulowanie NIE zglasza cancelSourceReason (nie ma go po WS)",
    !d(1, 2).some((c) => c.startsWith("cancelSourceReason")));
  checkThat("zmiana progu SL zglaszana jako protection",
    d(3, 4).some((c) => c.startsWith("protection:") && c.includes("45000") && c.includes("44000")));
  checkThat("dolozenie TP zglaszane jako protection",
    d(4, 5).some((c) => c.startsWith("protection:") && c.includes("tp@80000")));
  checkThat("wykonanie zglasza tradeId", d(7, 8).some((c) => c.startsWith("tradeId: - -> 1356365")));
  checkThat("wykonanie zglasza fillPx", d(7, 8).some((c) => c.startsWith("fillPx:")));
  checkThat("wykonanie zglasza oplate", d(7, 8).some((c) => c.startsWith("fee:")));
  check("ta sama ramka nie daje zadnych zmian", diffOrder(REAL[0].data, REAL[0].data), []);
  checkThat("stan koncowy jest terminalny", TERMINAL_STATES.has(REAL[8].data.state));

  // uTime nie jest podbijany przy zmianie doczepionej ochrony - zweryfikowane na zywo
  check("uTime niezmieniony przez trzy zmiany ochrony",
    new Set([REAL[3], REAL[4], REAL[5]].map((f) => f.data.uTime)).size, 1);
  checkThat("uTime JEST podbijany przy zmianie ceny", REAL[1].data.uTime !== REAL[0].data.uTime);
}

// ---------------------------------------------------------------- warianty syntetyczne
console.log("\nWARIANTY SYNTETYCZNE (konto demo ich nie wyprodukowalo - wartosci zmyslone)");
{
  // Ksztalt wpisu skopiowany z prawdziwej ramki; zmieniane sa tylko wartosci.
  const realAttach = REAL[3].data.attachAlgoOrds[0];
  const attach = (o) => ({ ...Object.fromEntries(Object.keys(realAttach).map((k) => [k, ""])), ...o });
  const base = { ...REAL[0].data, attachAlgoOrds: [] };

  check("trailing stop",
    formatProtection({ ...base, attachAlgoOrds: [attach({ attachAlgoId: "1002", callbackRatio: "0.05", activePx: "62000" })] }),
    " [trailing 5.00% active@62000 algoId=1002]");
  check("czesciowy take profit",
    formatProtection({ ...base, attachAlgoOrds: [attach({ attachAlgoId: "1003", tpTriggerPx: "61000", tpOrdPx: "-1", sz: "0.002" })] }),
    " [tp@61000->market sz=0.002 algoId=1003]");
  check("stop loss w starym stylu (pola gornego poziomu)",
    formatProtection({ ...base, slTriggerPx: "58000", slOrdPx: "-1", slTriggerPxType: "last" }),
    " [sl@58000(last)->market]");
  check("dwa osobne wpisy doczepione",
    formatProtection({ ...base, attachAlgoOrds: [
      attach({ attachAlgoId: "a1", tpTriggerPx: "61000", tpOrdPx: "-1" }),
      attach({ attachAlgoId: "a2", slTriggerPx: "58000", slOrdPx: "-1" })] }),
    " [tp@61000->market algoId=a1 | sl@58000->market algoId=a2]");
  check("powiazane zlecenie algo",
    formatProtection({ ...base, linkedAlgoOrd: { algoId: "777" } }), " [linkedAlgo=777]");

  // failCode/failReason istnieja tylko w ksztalcie REST, nie w ramce WS
  check("odrzucona ochrona (ksztalt REST)",
    formatProtection({ ...base, attachAlgoOrds: [
      { ...attach({ attachAlgoId: "1004", slTriggerPx: "58000", slOrdPx: "-1" }),
        failCode: "51280", failReason: "price out of range" }] }),
    " [sl@58000->market FAILED 51280 (price out of range) algoId=1004]");
}

// ---------------------------------------------------------------- salda konta
console.log("\nSALDA KONTA (dane prawdziwe)");
{
  const pelny = ACC.find((a) => a.scenario.startsWith("full set")).data.details;
  const jedna = ACC.find((a) => a.scenario.startsWith("incremental push touching one")).data.details;

  const s1 = foldBalances(new Map(), pelny, { replace: true });
  check("snapshot wnosi szesc walut", s1.balances.size, 6);
  check("kazda waluta zglaszana jako nowa", s1.changes.length, 6);

  const s2 = foldBalances(s1.balances, jedna, { replace: false });
  check("przyrost nie gubi pozostalych walut", s2.balances.size, 6);
  checkThat("przyrost dotyczy jednej waluty", s2.changes.length <= 1);

  // Waluta, ktora spadla do zera, przestaje byc wysylana - snapshot musi ja usunac,
  // scalanie zostawiloby ja w mapie na zawsze.
  const bezEur = pelny.filter((d) => d.ccy !== "EUR");
  const s3 = foldBalances(s1.balances, bezEur, { replace: true });
  check("snapshot usuwa walute, ktorej juz nie ma", s3.balances.size, 5);
  checkThat("zniknieta waluta jest zgloszona",
    s3.changes.some((c) => c.ccy === "EUR" && c.next === null));

  const s4 = foldBalances(new Map(s1.balances), bezEur, { replace: false });
  check("scalanie NIE usunieloby jej (dlatego snapshot musi zastepowac)", s4.balances.size, 6);

  // Swieza para: s1.balances zostala juz zmutowana przez przyrost powyzej (foldBalances
  // z replace:false celowo pisze w miejscu), wiec porownanie musi startowac od zera.
  const a1 = foldBalances(new Map(), pelny, { replace: true });
  const a2 = foldBalances(a1.balances, pelny, { replace: true });
  check("powtorzony identyczny snapshot nie generuje zmian", a2.changes.length, 0);
}

// ---------------------------------------------------------------- balance_and_position
console.log("\nBALANCE_AND_POSITION (dane prawdziwe)");
{
  const snap = BP.find((b) => b.data.eventType === "snapshot").data;
  const fill = BP.find((b) => b.data.eventType === "filled").data;

  checkThat("kazdy zaobserwowany eventType jest w udokumentowanym zbiorze",
    BP.every((b) => BALANCE_POSITION_EVENT_TYPES.includes(b.data.eventType)));
  check("zbior eventType z dokumentacji", BALANCE_POSITION_EVENT_TYPES.length, 13);

  check("snapshot: salda bez pustych tablic",
    formatBalancePosition(snap),
    "eventType=snapshot  bal: BTC=1 XRP=50000 ETH=1 USD=5000 USDC=5000 EUR=4600");
  checkThat("puste posData nie jest drukowane", !formatBalancePosition(snap).includes("pos:"));
  checkThat("puste trades nie jest drukowane", !formatBalancePosition(snap).includes("trades:"));

  checkThat("wykonanie pokazuje trades", formatBalancePosition(fill).includes("trades: BTC-EUR/1356365"));

  // trades laczy ten kanal z kanalem orders - ten sam tradeId musi wystapic po obu stronach
  const fillOrder = REAL.find((f) => f.scenario.startsWith("filled:")).data;
  check("tradeId zgadza sie z kanalem orders", fill.trades[0].tradeId, fillOrder.tradeId);
  check("instId zgadza sie z kanalem orders", fill.trades[0].instId, fillOrder.instId);

  // balData i posData sa opcjonalne - OKX wysyla tylko to, co sie zmienilo
  check("brak balData nie wywraca renderowania",
    formatBalancePosition({ eventType: "transferred" }), "eventType=transferred");
}

// ---------------------------------------------------------------- kontrakty pozostalych kanalow
console.log("\nKONTRAKTY account / balance_and_position");
{
  const nieznane = (tabela, próbki) =>
    [...new Set(próbki.flatMap((p) => auditAgainst(tabela, p)))];

  check("account: brak pol spoza kontraktu",
    nieznane(ACCOUNT_FIELDS, ACC.map((a) => a.data)), []);
  check("account.details: brak pol spoza kontraktu",
    nieznane(ACCOUNT_DETAIL_FIELDS, ACC.flatMap((a) => a.data.details)), []);
  check("balance_and_position: brak pol spoza kontraktu",
    nieznane(BALPOS_FIELDS, BP.map((b) => b.data)), []);
  check("balData: brak pol spoza kontraktu",
    nieznane(BALPOS_BALDATA_FIELDS, BP.flatMap((b) => b.data.balData ?? [])), []);
  check("trades: brak pol spoza kontraktu",
    nieznane(BALPOS_TRADES_FIELDS, BP.flatMap((b) => b.data.trades ?? [])), []);

  check("liczba pol account", Object.keys(ACCOUNT_FIELDS).length, 20);
  check("liczba pol account.details", Object.keys(ACCOUNT_DETAIL_FIELDS).length, 51);
  check("liczba pol balance_and_position", Object.keys(BALPOS_FIELDS).length, 5);
  checkThat("posData opisane mimo braku probki (konto spot)",
    Object.keys(BALPOS_POSDATA_FIELDS).length === 15
      && Object.values(BALPOS_POSDATA_FIELDS).every((f) => f.verified === false));

  check("pola details nieudokumentowane przez OKX",
    Object.entries(ACCOUNT_DETAIL_FIELDS).filter(([, f]) => f.docs === null).map(([k]) => k).sort(),
    ["autoLendAmt", "autoStakingStatus"]);

  const zaobserwowaneTypy = [...new Set(ACC.map((a) => a.eventType).filter(Boolean))];
  checkThat("zaobserwowane eventType kanalu account sa w kontrakcie",
    zaobserwowaneTypy.every((t) => ACCOUNT_EVENT_TYPES.includes(t)),
    `zaobserwowane: ${zaobserwowaneTypy.join(", ")}`);
  checkThat("mamy juz probke event_update", zaobserwowaneTypy.includes("event_update"));
  checkThat("mamy juz probke transferred",
    BP.some((b) => b.data.eventType === "transferred"));
}

console.log(`\n${fail ? "NIEPOWODZENIE" : "OK"}: ${pass} przeszlo, ${fail} nie przeszlo\n`);
process.exit(fail ? 1 : 0);
