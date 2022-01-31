package com.multi.vidulum.trading.app.commands.orders.create;

import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.OrderStatus;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.trading.domain.DomainOrderRepository;
import com.multi.vidulum.trading.domain.Order;
import com.multi.vidulum.trading.domain.OrderFactory;
import com.multi.vidulum.user.domain.PortfolioRestClient;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class PlaceOrderCommandHandler implements CommandHandler<PlaceOrderCommand, Order> {

    private final DomainOrderRepository orderRepository;
    private final OrderFactory orderFactory;
    private final PortfolioRestClient portfolioRestClient;

    @Override
    public Order handle(PlaceOrderCommand command) {

        // validate if quantity is sufficient
        // validate if all parameters based on OrderType are present

        Order order = orderFactory.empty(
                command.getOrderId(),
                command.getOriginOrderId(),
                command.getPortfolioId(),
                command.getBroker(),
                command.getSymbol(),
                command.getType(),
                command.getSide(),
                command.getTargetPrice(),
                command.getStopPrice(),
                command.getLimitPrice(),
                command.getQuantity(),
                command.getOccurredDateTime()
        );

        lockAssetInPortfolio(order);

        Order savedOrder = orderRepository.save(order);
        log.info("Order [{}] has been stored!", savedOrder);
        return savedOrder;
    }

    private void lockAssetInPortfolio(Order order) {
        if (order.isPurchaseAttempt()) {
            lockBalanceForPurchaseOfAsset(order);
        } else {
            lockAssetForSale(order);
        }
    }

    private void lockBalanceForPurchaseOfAsset(Order order) {
        Quantity quantityToLocked = Quantity.of(order.getTotal().getAmount().doubleValue());
        portfolioRestClient.lockAsset(
                order.getPortfolioId(),
                order.getSymbol().getDestination(),
                quantityToLocked
        );
    }

    private void lockAssetForSale(Order order) {
        portfolioRestClient.lockAsset(
                order.getPortfolioId(),
                order.getSymbol().getOrigin(),
                order.getQuantity()
        );
    }
}
