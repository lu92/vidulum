package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.Ticker;

import java.util.Locale;
import java.util.Set;

/**
 * Assets taken at par — one unit cost one unit.
 *
 * <p>Fiat and the stablecoins pegged to it need no question: their acquisition cost is their face
 * value. Asking "how much did your 500 EUR cost you?" is noise, and the whole point of
 * {@link ResolutionRules} is to ask only where the user is the only possible source.
 *
 * <p>A hardcoded set is the POC's answer. It is wrong in the long run — a depegged stablecoin is
 * not at par, and the list grows — but the alternative is a configuration surface nobody has
 * asked for yet.
 */
public final class ParAssets {

    private static final Set<String> AT_PAR =
            Set.of("USD", "EUR", "PLN", "GBP", "CHF", "USDT", "USDC", "DAI");

    private ParAssets() {
    }

    public static boolean isAtPar(Ticker ticker) {
        return AT_PAR.contains(ticker.getId().toUpperCase(Locale.ROOT));
    }
}
