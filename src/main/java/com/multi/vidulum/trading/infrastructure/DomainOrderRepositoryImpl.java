package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.Status;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.trading.domain.DomainOrderRepository;
import com.multi.vidulum.trading.domain.Order;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static java.util.stream.Collectors.toList;

@Component
@AllArgsConstructor
public class DomainOrderRepositoryImpl implements DomainOrderRepository {

    private final OrderMongoRepository repository;

    @Override
    public Optional<Order> findById(OrderId orderId) {
        return repository.findById(orderId.getId())
                .map(OrderEntity::toSnapshot)
                .map(Order::from);
    }

    @Override
    public Order save(Order aggregate) {
        return Order.from(
                repository.save(OrderEntity.fromSnapshot(aggregate.getSnapshot()))
                        .toSnapshot());
    }

    @Override
    public List<Order> findOpenedOrdersForPortfolio(PortfolioId portfolioId) {
        return repository.findByPortfolioIdAndStatus(portfolioId.getId(), Status.OPEN)
                .stream()
                .map(OrderEntity::toSnapshot)
                .map(Order::from)
                .collect(toList());
    }

    @Override
    public Optional<Order> findByOriginOrderId(OrderId orderId) {
        return repository.findByOriginOrderId(orderId.getId())
                .map(OrderEntity::toSnapshot)
                .map(Order::from);
    }
}
