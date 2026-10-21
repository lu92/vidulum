package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.app.UpcomingTransactionsCalculator;
import com.multi.vidulum.recurring_rules.app.dto.UpcomingTransactionsResponse;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Hands the question straight to {@link UpcomingTransactionsCalculator}, because the dashboard needs
 * the same answer and neither reader may own it.
 */
@Component
@AllArgsConstructor
public class GetUpcomingTransactionsQueryHandler
        implements QueryHandler<GetUpcomingTransactionsQuery, UpcomingTransactionsResponse> {

    private final UpcomingTransactionsCalculator calculator;

    @Override
    public UpcomingTransactionsResponse query(GetUpcomingTransactionsQuery query) {
        return calculator.upcomingFor(
                UserId.of(query.userId()),
                CashFlowId.of(query.cashFlowId()),
                query.days(),
                query.limit());
    }
}
