package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.DomainRepository;

import java.util.List;
import java.util.Optional;

public interface DomainOrderRepository extends DomainRepository<OrderId, Order> {
    List<Order> findOpenedOrdersForPortfolio(PortfolioId portfolioId);

    Optional<Order> findByOriginOrderId(OrderId orderId);
}
