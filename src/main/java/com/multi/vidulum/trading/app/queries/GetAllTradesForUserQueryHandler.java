package com.multi.vidulum.trading.app.queries;

import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import com.multi.vidulum.trading.domain.DomainTradeRepository;
import com.multi.vidulum.trading.domain.Trade;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@AllArgsConstructor
public class GetAllTradesForUserQueryHandler implements QueryHandler<GetAllTradesForUserQuery, List<Trade>> {
    private final DomainTradeRepository repository;

    @Override
    public List<Trade> query(GetAllTradesForUserQuery query) {
        return repository.findByUserIdAndPortfolioId(query.getUserId(), query.getPortfolioId());
    }
}
