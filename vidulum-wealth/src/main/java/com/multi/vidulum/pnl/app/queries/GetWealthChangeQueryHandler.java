package com.multi.vidulum.pnl.app.queries;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.pnl.domain.PortfolioValuation;
import com.multi.vidulum.pnl.domain.WealthChangeOverWindow;
import com.multi.vidulum.portfolio.api.PortfolioModuleApi;
import com.multi.vidulum.portfolio.api.view.PortfolioView;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static com.multi.vidulum.pnl.domain.WealthChangeOverWindow.WealthChangeStatus.CONTRIBUTIONS_UNKNOWN;
import static com.multi.vidulum.pnl.domain.WealthChangeOverWindow.WealthChangeStatus.CURRENCY_CHANGED;
import static com.multi.vidulum.pnl.domain.WealthChangeOverWindow.WealthChangeStatus.COMPUTED;
import static com.multi.vidulum.pnl.domain.WealthChangeOverWindow.WealthChangeStatus.NOT_WATCHING_YET;

/**
 * Answers "and how much did I make this month" (task C14).
 *
 * <p>Both ends of the window come from figures that already exist: today's from the portfolio
 * summary, the earlier one from a recorded valuation (F9). Nothing here values an old holding at a
 * new price, which is the one thing that would make the number easy and wrong.
 */
@Component
@AllArgsConstructor
public class GetWealthChangeQueryHandler
        implements QueryHandler<GetWealthChangeQuery, WealthChangeOverWindow> {

    private final DomainPnlRepository repository;
    private final PortfolioModuleApi portfolioApi;

    @Override
    public WealthChangeOverWindow query(GetWealthChangeQuery query) {
        Optional<PortfolioValuation> start = repository.findByUser(query.userId())
                .flatMap(history -> history.valuationAt(query.portfolioId(), query.since()));
        if (start.isEmpty()) {
            return WealthChangeOverWindow.absent(query.since(), NOT_WATCHING_YET);
        }

        PortfolioValuation then = start.get();

        // Read in the currency the recorded end is in, so the two are comparable by construction
        // rather than by luck. Before C17 the stored end was always dollars and this one was the
        // portfolio's own currency, so a portfolio settling in zloty was compared against dollars
        // and the difference was reported as growth.
        PortfolioView now = portfolioApi.get(
                query.portfolioId(), Currency.of(then.currentValue().getCurrency()));

        // Both ends have to know what had been put in by their date, or the deposits inside the
        // window cannot be netted out — and an unnetted figure reports a transfer as growth (C5).
        // Asked before the currency, because an end that says nothing has no currency to disagree
        // about, and answering CURRENCY_CHANGED there would name the wrong reason.
        if (then.netContributions() == null || now.netContributions() == null) {
            return WealthChangeOverWindow.absent(query.since(), CONTRIBUTIONS_UNKNOWN);
        }

        if (!sameCurrency(then, now)) {
            return WealthChangeOverWindow.absent(query.since(), CURRENCY_CHANGED);
        }

        Money grew = now.currentValue().minus(then.currentValue());
        Money paidIn = now.netContributions().minus(then.netContributions());
        Money change = grew.minus(paidIn).withScale(4);

        // Measured against what the portfolio was worth at the start of the window, which is what
        // "ten per cent this month" means. Withheld when there was nothing to grow from.
        double base = then.currentValue().getAmount().doubleValue();
        Double pct = base > 0 ? change.getAmount().doubleValue() / base : null;

        return new WealthChangeOverWindow(query.since(), then.takenAt(), change, pct, COMPUTED);
    }

    /**
     * Whether the two ends of the window speak of the same thing.
     *
     * <p>{@code Money.minus} keeps the left-hand currency and ignores the right, so unlike ends
     * would subtract silently and answer in whichever currency happened to come first — the defect
     * F6 already paid for once, where 2 200 EUR minus 2 000 USD produced "200 EUR".
     */
    private static boolean sameCurrency(PortfolioValuation then, PortfolioView now) {
        if (now.currentValue() == null) {
            // Nothing could be priced today (C16) — a different silence, decided further down.
            return true;
        }
        return then.currentValue().getCurrency().equals(now.currentValue().getCurrency())
                && then.netContributions().getCurrency().equals(now.netContributions().getCurrency());
    }
}
