package com.multi.vidulum.exchange_connection.app;

import java.util.List;

/**
 * Every connection the caller owns.
 *
 * <p>{@code supportedExchanges} rides along because the caller needs it to build a "connect an
 * exchange" screen and would otherwise have to hardcode the list. It answers a different
 * question from {@code GET /exchange/{name}/status}: this is "which exchanges can be connected
 * at all", not "is that exchange reachable and are its quotes loaded right now".
 */
public record ExchangeConnectionsListJson(
        List<ExchangeConnectionJson> connections,
        List<String> supportedExchanges) {
}
