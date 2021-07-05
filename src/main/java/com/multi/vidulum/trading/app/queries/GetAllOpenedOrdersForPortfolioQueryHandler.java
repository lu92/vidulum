package com.multi.vidulum.trading.app.queries;

import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import com.multi.vidulum.trading.domain.DomainOrderRepository;
import com.multi.vidulum.trading.domain.Order;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@AllArgsConstructor
public class GetAllOpenedOrdersForPortfolioQueryHandler implements QueryHandler<GetAllOpenedOrdersForPortfolioQuery, List<Order>> {

    private final DomainOrderRepository orderRepository;

    @Override
    public List<Order> query(GetAllOpenedOrdersForPortfolioQuery query) {
        return orderRepository.findOpenedOrdersForPortfolio(query.getPortfolioId());
    }
}
