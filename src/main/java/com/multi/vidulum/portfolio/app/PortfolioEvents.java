package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

public final class PortfolioEvents {

    public record PortfolioOpenedEvent(
            PortfolioId portfolioId,
            String name,
            Broker broker) implements DomainEvent {
    }

    public record MoneyDepositedEvent(
            PortfolioId portfolioId,
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
            PortfolioId portfolioId,
            TradeId tradeId,
            Symbol symbol,
            SubName subName,
            Side side,
            Quantity quantity,
            Price price) implements DomainEvent {
    }
}
