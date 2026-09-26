package com.multi.vidulum.pnl.app.queries;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.pnl.domain.PortfolioValuation;
import com.multi.vidulum.pnl.domain.WealthChangeOverWindow;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioRestClient;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static com.multi.vidulum.pnl.domain.WealthChangeOverWindow.WealthChangeStatus.CONTRIBUTIONS_UNKNOWN;
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
    private final PortfolioRestClient portfolioRestClient;

    @Override
    public WealthChangeOverWindow query(GetWealthChangeQuery query) {
        Optional<PortfolioValuation> start = repository.findByUser(query.userId())
                .flatMap(history -> history.valuationAt(query.portfolioId(), query.since()));
        if (start.isEmpty()) {
            return WealthChangeOverWindow.absent(query.since(), NOT_WATCHING_YET);
        }

        PortfolioValuation then = start.get();
        PortfolioDto.PortfolioSummaryJson now = portfolioRestClient.getPortfolio(query.portfolioId());

        // Both ends have to know what had been put in by their date, or the deposits inside the
        // window cannot be netted out — and an unnetted figure reports a transfer as growth (C5).
        if (then.netContributions() == null || now.getNetContributions() == null) {
            return WealthChangeOverWindow.absent(query.since(), CONTRIBUTIONS_UNKNOWN);
        }

        Money grew = now.getCurrentValue().minus(then.currentValue());
        Money paidIn = now.getNetContributions().minus(then.netContributions());
        Money change = grew.minus(paidIn).withScale(4);

        // Measured against what the portfolio was worth at the start of the window, which is what
        // "ten per cent this month" means. Withheld when there was nothing to grow from.
        double base = then.currentValue().getAmount().doubleValue();
        Double pct = base > 0 ? change.getAmount().doubleValue() / base : null;

        return new WealthChangeOverWindow(query.since(), then.takenAt(), change, pct, COMPUTED);
    }
}
