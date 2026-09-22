package com.multi.vidulum.trading.app.commands.trades.execute;
import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Side;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.events.TradeCapturedEvent;
import com.multi.vidulum.trading.infrastructure.TradeCapturedEventEmitter;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.trading.domain.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class MakeTradeCommandHandler implements CommandHandler<MakeTradeCommand, Trade> {

    private final DomainTradeRepository repository;
    private final DomainOrderRepository orderRepository;
    private final TradeCapturedEventEmitter tradeCapturedEventEmitter;

    @Override
    public Trade handle(MakeTradeCommand command) {

        // A trade may arrive two ways. Filled on an exchange, it belongs to an order that told us
        // what was bought and which way - and that order still has to be found, because the fill
        // has to be applied to it. Entered by hand, there is no order to find, and refusing the
        // trade for its absence was the reason a manually kept portfolio could not be used at all.
        TradeTerms terms = termsOf(command);

        Trade.Fee fee = new Trade.Fee(
                command.getFee().exchangeCurrencyFee(),
                command.getFee().transactionFee(),
                command.getFee().exchangeCurrencyFee().plus(command.getFee().transactionFee())
        );
        Money value = command.getPrice().multiply(command.getQuantity());
        Trade newTrade = Trade.builder()
                .userId(command.getUserId())
                .portfolioId(command.getPortfolioId())
                .originTradeId(command.getOriginTradeId())
                .symbol(terms.symbol())
                .subName(command.getSubName())
                .side(terms.side())
                .quantity(command.getQuantity())
                .price(command.getPrice())
                .fee(fee)
                .localValue(value)
                .value(value)
                .totalValue(value.plus(fee.totalFee()))
                .dateTime(command.getOriginDateTime())
                .build();
        Trade savedTrade = repository.save(newTrade);

        // One event for both routes; the listener decides which way it goes. Carrying the symbol
        // and side rather than leaving them to be looked up is what makes the order optional.
        tradeCapturedEventEmitter.emit(
                TradeCapturedEvent.builder()
                        .orderId(terms.orderId())
                        .portfolioId(savedTrade.getPortfolioId())
                        .tradeId(savedTrade.getTradeId())
                        .symbol(savedTrade.getSymbol())
                        .subName(savedTrade.getSubName())
                        .side(savedTrade.getSide())
                        .quantity(savedTrade.getQuantity())
                        .price(savedTrade.getPrice())
                        .dateTime(savedTrade.getDateTime())
                        .build()
        );

        log.info("Trade [{}] has been stored!", savedTrade);
        return savedTrade;
    }

    /**
     * Where the trade's symbol and side come from.
     *
     * <p>The order wins when there is one: it is the record of what was actually asked for, and
     * letting the request restate it would give the same fact two sources. Without an order the
     * request is the only source, and must say both.
     */
    private TradeTerms termsOf(MakeTradeCommand command) {
        if (!OrderId.isDefined(command.getOrderId())) {
            if (command.getSymbol() == null || command.getSide() == null) {
                throw new IllegalArgumentException(
                        "A trade without an order must state its symbol and side");
            }
            return new TradeTerms(OrderId.notDefined(), command.getSymbol(), command.getSide());
        }
        Order order = orderRepository.findById(command.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException(command.getOrderId()));
        return new TradeTerms(order.getOrderId(), order.getSymbol(), order.getParameters().side());
    }

    private record TradeTerms(OrderId orderId, Symbol symbol, Side side) {
    }
}
