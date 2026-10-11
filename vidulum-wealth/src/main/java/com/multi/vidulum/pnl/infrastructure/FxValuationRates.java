package com.multi.vidulum.pnl.infrastructure;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.pnl.domain.ValuationBasis;
import com.multi.vidulum.pnl.domain.ValuationRates;
import com.multi.vidulum.quotation.domain.fx.FxRates;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reads the basis of a valuation off the rates the backend keeps (task B4, used by C17).
 *
 * <p>The pivot is named here rather than in the domain: which currency everything routes through
 * is a property of where prices come from, and the part of the model that records history has no
 * business knowing it.
 */
@Component
@AllArgsConstructor
public class FxValuationRates implements ValuationRates {

    private static final Currency PIVOT = Currency.of("USD");

    private final FxRates fxRates;

    @Override
    public Optional<ValuationBasis> basisFor(Currency currency) {
        if (PIVOT.equals(currency)) {
            return Optional.of(ValuationBasis.pivot(currency));
        }
        return fxRates.rateOf(currency, PIVOT).map(rate -> new ValuationBasis(currency, rate));
    }
}
