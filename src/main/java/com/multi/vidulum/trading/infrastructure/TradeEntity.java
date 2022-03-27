package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.trading.domain.TradeSnapshot;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
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
    private String subName;
    private String symbol;
    private Side side;
    private Quantity quantity;
    private Price price;
    private Date originDateTime;

    public static TradeEntity fromSnapshot(TradeSnapshot snapshot) {
        String id = Optional.ofNullable(snapshot.getTradeId())
                .map(TradeId::getId).orElse(null);

        Date date = snapshot.getDateTime() != null ? Date.from(snapshot.getDateTime().toInstant()) : null;

        return TradeEntity.builder()
                .tradeId(id)
                .userId(snapshot.getUserId().getId())
                .portfolioId(snapshot.getPortfolioId().getId())
                .originTradeId(snapshot.getOriginTradeId().getId())
                .subName(snapshot.getSubName().getName())
                .symbol(snapshot.getSymbol().getId())
                .side(snapshot.getSide())
                .quantity(snapshot.getQuantity())
                .price(snapshot.getPrice())
                .originDateTime(date)
                .build();
    }

    public TradeSnapshot toSnapshot() {

        ZonedDateTime zonedDateTime = originDateTime != null ? ZonedDateTime.ofInstant(originDateTime.toInstant(), ZoneOffset.UTC) : null;

        return TradeSnapshot.builder()
                .tradeId(TradeId.of(tradeId))
                .userId(UserId.of(userId))
                .portfolioId(PortfolioId.of(portfolioId))
                .originTradeId(OriginTradeId.of(originTradeId))
                .subName(SubName.of(subName))
                .symbol(Symbol.of(symbol))
                .side(side)
                .quantity(quantity)
                .price(price)
                .dateTime(zonedDateTime)
                .build();
    }
}
