/**
 * The decisions the prototype makes while walking the specification (task E4).
 *
 * Kept apart from the script so they can be tested without a backend: everything here is a pure
 * function over what the API returned.
 */

/** What the prototype is allowed to answer on the user's behalf. */
export const ANSWER_POLICY = {
  /** Answer nothing. Print the questions and stop - the default, and the honest one. */
  ASK: "ask",
  /**
   * Answer every open question with "I do not know".
   *
   * Opt-in only. COST_UNKNOWN is a *decision*, and having a script make it by default would undo
   * the very distinction the backend insists on: silence and "I do not know" are different
   * things, and only one of them is an answer.
   */
  ASSUME_UNKNOWN: "assume-unknown",
};

export function openQuestions(spec) {
  return (spec.differences ?? []).filter((d) => d.question !== null && d.answerKind === null);
}

/**
 * Turns open questions into answers.
 *
 * Throws when a question cannot be answered by the chosen policy - better than sending a partial
 * set and having the confirmation refused later for a reason the user cannot connect to anything
 * they did.
 */
export function planAnswers(spec, policy, suppliedAnswers = []) {
  const supplied = new Map(suppliedAnswers.map((a) => [key(a.ticker, a.subName), a]));

  return openQuestions(spec).map((difference) => {
    const given = supplied.get(key(difference.ticker, difference.subName));
    if (given) {
      return {
        ticker: difference.ticker,
        subName: difference.subName,
        quantity: difference.quantity,
        kind: given.kind,
        avgPrice: given.avgPrice ?? null,
      };
    }
    if (policy === ANSWER_POLICY.ASSUME_UNKNOWN) {
      return {
        ticker: difference.ticker,
        subName: difference.subName,
        quantity: difference.quantity,
        kind: difference.question === "ACQUISITION_COST" ? "COST_UNKNOWN" : "WITHDRAWAL",
        avgPrice: null,
      };
    }
    throw new Error(
      `no answer for ${difference.ticker}/${difference.subName} (${difference.question}); ` +
      `supply --answers or --assume-unknown`);
  });
}

/** Human-readable rendering of what is still open, for a caller who has to decide. */
export function describeQuestions(spec) {
  return openQuestions(spec).map((d) =>
    `${d.ticker}/${d.subName} ${d.direction.toLowerCase()} by ${d.quantity.qty} -> ${d.question}`);
}

/**
 * Confirmation body.
 *
 * `broker` and `denominationCurrency` are repeated from the connection on purpose: the backend
 * takes them from the connection and rejects a request stating anything else, so sending them is
 * how the prototype says what it believes and gets corrected when it is wrong.
 */
export function buildConfirmRequest({ portfolioName, denominationCurrency, broker,
                                      takenAt, positions }) {
  return { portfolioName, denominationCurrency, broker, snapshotTakenAt: takenAt, positions };
}

/** Connection body for `POST /exchange-connection`. */
export function buildConnectionRequest({ accountUid, environment, region,
                                         reportedKeyPermissions, denominationCurrency }) {
  return {
    broker: "OKX",
    accountUid,
    environment,
    region,
    reportedKeyPermissions,
    denominationCurrency,
  };
}

function key(ticker, subName) {
  return `${ticker}/${subName}`;
}
