package com.multi.vidulum.trading.app.commands.orders.cancel;

import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.OrderStatus;
import com.multi.vidulum.common.Ticker;
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
public class CancelOrderCommandHandler implements CommandHandler<CancelOrderCommand, Order> {

    private final DomainOrderRepository orderRepository;
    private final PortfolioRestClient portfolioRestClient;

    @Override
    public Order handle(CancelOrderCommand command) {
        Order order = orderRepository.findById(command.getOrderId())
                .orElseThrow(() -> new IllegalArgumentException(String.format("Order [%s] does not exist!", command.getOrderId())));

        if (!order.isOpen()) {
            throw new IllegalArgumentException(String.format("Order [%s] is not open!", order.getOrderId()));
        }

        unlockParticularAssetInPortfolio(order);

        order.setStatus(OrderStatus.CANCELLED);
        Order savedOrder = orderRepository.save(order);
        log.info("Order [{}] has been cancelled!", order.getOrderId());
        return savedOrder;
    }

    private void unlockParticularAssetInPortfolio(Order order) {
        Ticker ticker = order.isPurchaseAttempt() ? order.getSymbol().getDestination() : order.getSymbol().getOrigin();
        Quantity quantityToUnlocked = Quantity.of(order.getTotal().getAmount().doubleValue());
        portfolioRestClient.unlockAsset(
                order.getPortfolioId(),
                ticker,
                quantityToUnlocked);
    }
}
