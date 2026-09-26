package com.multi.vidulum.quotation.app;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.quotation.domain.fx.FxRate;
import com.multi.vidulum.quotation.domain.fx.FxRateSource;
import com.multi.vidulum.quotation.domain.fx.FxRates;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Optional;

/**
 * Keeps {@link FxRates} current without anybody asking.
 *
 * <p>This is the half of the design that makes the currency chain usable at all. Exchange prices
 * arrive because a caller published them; a fixing has no such caller, and a portfolio that cannot
 * be valued until somebody remembers to send in a rate is a portfolio that will be unreadable on
 * the day it matters.
 *
 * <p>Failure is <b>per pair</b>, as in the daily valuation job, and for the same reason: one
 * unreachable currency must not cost the others their refresh. What it does not share with that job
 * is the consequence of failing — a missed valuation leaves a permanent hole, while a missed
 * refresh leaves yesterday's fixing in place, which is very nearly the right answer and is labelled
 * with its own date.
 */
@Slf4j
@AllArgsConstructor
public class FxRateRefresher {

    private final FxRateSource source;
    private final FxRates rates;

    /** The pairs worth holding — the currencies users are valued in, against the pivot. */
    private final List<Symbol> watched;

    @Scheduled(cron = "${vidulum.fx.cron:0 5 * * * *}")
    public void refresh() {
        log.info("Refreshing {} exchange rate(s) from [{}]", watched.size(), source.id());
        int recorded = 0;
        for (Symbol pair : watched) {
            if (refresh(pair)) {
                recorded++;
            }
        }
        log.info("Refreshed {} of {} exchange rate(s) from [{}]", recorded, watched.size(), source.id());
    }

    private boolean refresh(Symbol pair) {
        Currency base = Currency.of(pair.getOrigin().getId());
        Currency quote = Currency.of(pair.getDestination().getId());
        try {
            Optional<FxRate> rate = source.latest(base, quote);
            if (rate.isEmpty()) {
                log.warn("[{}] publishes no rate for [{}]", source.id(), pair.getId());
                return false;
            }
            rates.record(rate.get());
            return true;
        } catch (Exception e) {
            // Deliberately kept: the rate already held is older, not wrong, and losing it would
            // turn a failed refresh into an unreadable portfolio.
            log.warn("Could not refresh [{}] from [{}]: {} - keeping the rate already held",
                    pair.getId(), source.id(), e.getMessage());
            return false;
        }
    }
}
