package com.multi.vidulum.trading.app.commands.orders.create;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Side;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.api.error.AssetNotFoundException;
import com.multi.vidulum.portfolio.api.PortfolioModuleApi;
import com.multi.vidulum.portfolio.api.view.PortfolioView;
import com.multi.vidulum.portfolio.api.view.PositionView;
import com.multi.vidulum.portfolio.api.error.NotSufficientBalance;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.trading.domain.DomainOrderRepository;
import com.multi.vidulum.trading.domain.Order;
import com.multi.vidulum.trading.domain.OrderFactory;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class PlaceOrderCommandHandler implements CommandHandler<PlaceOrderCommand, Order> {

    private final DomainOrderRepository orderRepository;
    private final OrderFactory orderFactory;
    private final PortfolioModuleApi portfolioApi;

    @Override
    public Order handle(PlaceOrderCommand command) {

        // validate if all parameters based on OrderType are present

        if (!isAssetBalanceSufficient(command)) {
            Money money = Money.of(command.getQuantity().getQty(), command.getSide().equals(Side.BUY) ? command.getSymbol().getDestination().getId() : command.getSymbol().getOrigin().getId());
            throw new NotSufficientBalance(command.getSide(), money);
        }

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

    private boolean isAssetBalanceSufficient(PlaceOrderCommand command) {
        PortfolioView portfolio = portfolioApi.get(command.getPortfolioId());
        Ticker expectedAssetToLock = command.getSide() == Side.BUY ? command.getSymbol().getDestination() : command.getSymbol().getOrigin();
        PositionView expectedAsset = portfolio.position(expectedAssetToLock)
                .orElseThrow(() -> new AssetNotFoundException(expectedAssetToLock));

        Quantity requiredAmountToLock = requiredAssetQuantityToLock(command);
        Quantity freeQtyAfterRequiredLock = expectedAsset.free().minus(requiredAmountToLock);
        return freeQtyAfterRequiredLock.isZero() || freeQtyAfterRequiredLock.isPositive();
    }

    private Quantity requiredAssetQuantityToLock(PlaceOrderCommand command) {
        if (command.getSide().equals(Side.SELL)) {
            return command.getQuantity();
        }

        if (command.getQuantity().getUnit().equals("Number")) {
            return command.getQuantity();
        } else {
            return Quantity.of(command.getLimitPrice().multiply(command.getQuantity()).getAmount().doubleValue());
        }
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
        portfolioApi.lock(
                order.getPortfolioId(),
                order.getSymbol().getDestination(),
                order.getOrderId(),
                quantityToLocked
        );
    }

    private void lockAssetForSale(Order order) {
        portfolioApi.lock(
                order.getPortfolioId(),
                order.getSymbol().getOrigin(),
                order.getOrderId(),
                order.getParameters().quantity()
        );
    }
}
