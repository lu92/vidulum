package com.multi.vidulum.trading.app.commands.orders.create;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Side;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.AssetNotFoundException;
import com.multi.vidulum.portfolio.domain.NotSufficientBalance;
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

        if (!isAssetBalanceSufficient(command)) {
            Money money = Money.of(order.getParameters().quantity().getQty(), order.getParameters().side().equals(Side.BUY) ? order.getSymbol().getDestination().getId() : order.getSymbol().getOrigin().getId());
            throw new NotSufficientBalance(order.getParameters().side(), money);
        }

        lockAssetInPortfolio(order);

        Order savedOrder = orderRepository.save(order);
        log.info("Order [{}] has been stored!", savedOrder);
        return savedOrder;
    }

    private boolean isAssetBalanceSufficient(PlaceOrderCommand command) {
        PortfolioDto.PortfolioSummaryJson portfolio = portfolioRestClient.getPortfolio(command.getPortfolioId());
        Ticker expectedAssetToLock = command.getSide() == Side.BUY ? command.getSymbol().getDestination() : command.getSymbol().getOrigin();
        PortfolioDto.AssetSummaryJson expectedAsset = portfolio.getAssets().stream()
                .filter(assetSummaryJson -> expectedAssetToLock.equals(Ticker.of(assetSummaryJson.getTicker())))
                .findFirst()
                .orElseThrow(() -> new AssetNotFoundException(expectedAssetToLock));

        Quantity requiredAmountToLock = requiredAssetQuantityToLock(command);
        Quantity freeQtyAfterRequiredLock = expectedAsset.getFree().minus(requiredAmountToLock);
        return freeQtyAfterRequiredLock.isZero() || freeQtyAfterRequiredLock.isPositive();
    }

    private Quantity requiredAssetQuantityToLock(PlaceOrderCommand command) {
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
                order.getParameters().quantity()
        );
    }
}
