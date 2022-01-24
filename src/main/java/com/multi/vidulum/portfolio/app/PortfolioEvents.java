package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.util.List;

public final class PortfolioEvents {

    public record PortfolioOpenedEvent(
            PortfolioId portfolioId,
            String name,
            Broker broker) implements DomainEvent {
    }

    public record MoneyDepositedEvent(
            AssetBasicInfo assetBasicInfo,
            Money deposit) implements DomainEvent {
    }

    public record MoneyDepositedEvent2(
            PortfolioId portfolioId,
            Ticker ticker,
            Segment segment,
            Money deposit) implements DomainEvent {
    }

    public record MoneyWithdrawEvent(
            PortfolioId portfolioId,
            Money withdrawal) implements DomainEvent {
    }

    public record AssetLockedEvent(
            PortfolioId portfolioId,
            Ticker ticker,
            Quantity quantity) implements DomainEvent {
    }

    public record AssetUnlockedEvent(
            PortfolioId portfolioId,
            Ticker ticker,
            Quantity quantity) implements DomainEvent {
    }

    public record TradeProcessedEvent(
            TradeId tradeId,
            PortfolioId portfolioId,
            Symbol symbol,
            Side side,
            Quantity quantity,
            Price price) implements DomainEvent {
    }

}
