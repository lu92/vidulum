package com.multi.vidulum.shared.module;

/**
 * Reacting to what another module <b>announces</b>.
 *
 * <p>The handler interface lives in the <b>producer's</b> {@code *-api} artifact, together with the
 * subscription facts the producer owns — the topic and the container factory — as constants:
 *
 * <pre>
 * public interface TradeConfirmedHandler extends ModuleEventHandler {
 *     String TOPIC = "trade_confirmed";
 *     String CONTAINER_FACTORY = "tradeConfirmedContainerFactory";
 *     void onTradeConfirmed(TradeConfirmedEvent event);
 * }
 * </pre>
 *
 * <p>The consumer declares what it listens to by <b>implementing</b> this, and keeps its own
 * {@code groupId} — each consumer chooses its own, and naming it after the module is what makes lag
 * readable per module.
 *
 * <p><b>What this deliberately is not:</b> a declaration in the consumer's own contract. Putting
 * "portfolio handles X" into {@code portfolio-api} would tell everyone who depends on portfolio
 * about trading's event — the dependency leaks sideways, and the one real benefit of events, that a
 * producer does not know its consumers, is gone.
 *
 * <p>What it buys: the topic and the factory stop being strings the compiler never checks, and
 * "who listens to X" becomes answerable — it is the set of implementations of X's handler.
 */
public interface ModuleEventHandler {
}
