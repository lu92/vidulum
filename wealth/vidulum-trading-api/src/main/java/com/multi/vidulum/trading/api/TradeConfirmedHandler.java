package com.multi.vidulum.trading.api;

import com.multi.vidulum.shared.module.ModuleEventHandler;

/**
 * Trading announces a settled trade; whoever wants to act on it implements this.
 *
 * <p>The topic and the listener factory are trading's to name, so they are constants here rather
 * than strings repeated in every consumer. Before this, a consumer wrote
 * {@code topics = "order_filled"} and {@code containerFactory = "orderFilledContainerFactory"} by
 * hand: the event class was type-checked, the subscription was not, and renaming either would have
 * silenced the consumer without a single compile error.
 *
 * <p>The consumer group is deliberately absent — each consumer picks its own, and naming it after
 * the module is what makes consumer lag readable per module instead of per {@code group_id6}.
 *
 * <p>Which also answers a question that had no answer before: who listens to this event is the set
 * of implementations of this interface.
 */
public interface TradeConfirmedHandler extends ModuleEventHandler {

    String TOPIC = "trade_confirmed";
    String CONTAINER_FACTORY = "tradeConfirmedContainerFactory";

    void onTradeConfirmed(TradeConfirmedEvent event);
}
