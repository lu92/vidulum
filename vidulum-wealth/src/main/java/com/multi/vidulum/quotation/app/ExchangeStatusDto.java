package com.multi.vidulum.quotation.app;

import java.time.ZonedDateTime;
import java.util.List;

/** Shapes returned by the exchange status endpoints. */
public final class ExchangeStatusDto {

    private ExchangeStatusDto() {
    }

    /**
     * Whether an exchange can be used right now.
     *
     * <p>The three groups answer three different questions that collapse in practice into one —
     * "can I create a portfolio at this moment?" — and keeping them apart is the point:
     *
     * <ul>
     *   <li>{@code reachability} — does the exchange answer;
     *   <li>{@code brokerRegistered} — does {@code QuotationService} know this broker at all;
     *   <li>{@code quotesReady} / {@code quotedSymbols} — are prices in the cache.
     * </ul>
     *
     * <p>Without the last two the endpoint would say the exchange is fine while creating a
     * portfolio still failed — because it fails on our side, not theirs.
     *
     * <p>This is <b>not</b> the same question as {@code GET /exchange-connection/{id}}. That one
     * is personal: the state of <i>your</i> connection. This one knows nothing about users, and
     * the two disagree routinely — an exchange can be reachable while your key is revoked.
     */
    public record ExchangeStatusJson(
            String exchange,
            String reachability,
            boolean brokerRegistered,
            boolean quotesReady,
            List<String> quotedSymbols,
            String message,
            ZonedDateTime checkedAt) {
    }

    public record ExchangeStatusListJson(List<ExchangeStatusJson> exchanges) {
    }
}
