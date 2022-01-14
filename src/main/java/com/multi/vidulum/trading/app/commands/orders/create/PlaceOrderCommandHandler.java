package com.multi.vidulum.trading.app.commands.orders.create;

import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Status;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.trading.domain.DomainOrderRepository;
import com.multi.vidulum.trading.domain.Order;
import com.multi.vidulum.user.domain.PortfolioRestClient;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class PlaceOrderCommandHandler implements CommandHandler<PlaceOrderCommand, Order> {

    private final DomainOrderRepository orderRepository;
    private final PortfolioRestClient portfolioRestClient;

    @Override
    public Order handle(PlaceOrderCommand command) {

        // validate if quantity is sufficient
        // validate if all parameters based on OrderType are present

        Order order = Order.builder()
                .originOrderId(command.getOriginOrderId())
                .portfolioId(command.getPortfolioId())
                .broker(command.getBroker())
                .symbol(command.getSymbol())
                .type(command.getType())
                .side(command.getSide())
                .targetPrice(command.getTargetPrice())
                .stopPrice(command.getStopPrice())
                .limitPrice(command.getLimitPrice())
                .quantity(command.getQuantity())
                .occurredDateTime(command.getOccurredDateTime())
                .status(Status.OPEN)
                .build();

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
