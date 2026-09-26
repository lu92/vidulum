package com.multi.vidulum.pnl.app.queries;

import com.multi.vidulum.pnl.domain.DomainPnlRepository;
import com.multi.vidulum.pnl.domain.PortfolioValuation;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reads a past valuation out of the owner's PnL history (task F9).
 *
 * <p>Answers {@link Optional#empty()} rather than throwing when nothing was recorded that early.
 * Absence is an ordinary state here — every portfolio has a first snapshot, and every window that
 * starts before it has no reference point — so the caller decides what to say about it, which in
 * practice means withholding a figure instead of computing one from today's prices.
 */
@Component
@AllArgsConstructor
public class GetPortfolioValuationQueryHandler
        implements QueryHandler<GetPortfolioValuationQuery, Optional<PortfolioValuation>> {

    private final DomainPnlRepository repository;

    @Override
    public Optional<PortfolioValuation> query(GetPortfolioValuationQuery query) {
        return repository.findByUser(query.userId())
                .flatMap(history -> history.valuationAt(query.portfolioId(), query.at()));
    }
}
