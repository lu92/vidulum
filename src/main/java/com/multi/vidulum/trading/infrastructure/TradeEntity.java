package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.trading.domain.TradeSnapshot;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.ZonedDateTime;
import java.util.Optional;

@Builder
@Getter
@ToString
@Document("trade")
public class TradeEntity {

    @Id
    private String tradeId;
    private String userId;
    private String portfolioId;
    private String originTradeId; // generated by exchange
    private String symbol;
    private Side side;
    private Quantity quantity;
    private Money price;
//    private ZonedDateTime dateTime;

    public static TradeEntity fromSnapshot(TradeSnapshot snapshot) {
        String id = Optional.ofNullable(snapshot.getTradeId())
                .map(TradeId::getId).orElse(null);

        return TradeEntity.builder()
                .tradeId(id)
                .userId(snapshot.getUserId().getId())
                .portfolioId(snapshot.getPortfolioId().getId())
                .originTradeId(snapshot.getOriginTradeId().getId())
                .symbol(snapshot.getSymbol().getId())
                .side(snapshot.getSide())
                .quantity(snapshot.getQuantity())
                .price(snapshot.getPrice())
//                .dateTime(snapshot.getDateTime())
                .build();
    }

    public TradeSnapshot toSnapshot() {
        return TradeSnapshot.builder()
                .tradeId(TradeId.of(tradeId))
                .userId(UserId.of(userId))
                .portfolioId(PortfolioId.of(portfolioId))
                .originTradeId(OriginTradeId.of(portfolioId))
                .symbol(Symbol.of(symbol))
                .side(side)
                .quantity(quantity)
                .price(price)
//                .dateTime(dateTime)
                .build();
    }
}
