package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.*;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.time.ZonedDateTime;

public final class PortfolioEvents {

    public record PortfolioOpenedEvent(
            PortfolioId portfolioId,
            String name,
            Broker broker) implements DomainEvent {
    }

    public record PortfolioClosedEvent(
            PortfolioId portfolioId,
            UserId userId) implements DomainEvent {
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
            OrderId orderId,
            Quantity quantity,
            ZonedDateTime dateTime) implements DomainEvent {
    }

    public record AssetUnlockedEvent(
            PortfolioId portfolioId,
            Ticker ticker,
            OrderId orderId,
            Quantity quantity,
            ZonedDateTime dateTime) implements DomainEvent {
    }

    public record TradeProcessedEvent(
            PortfolioId portfolioId,
            TradeId tradeId,
            OrderId orderId,
            Symbol symbol,
            SubName subName,
            Side side,
            Quantity quantity,
            Price price) implements DomainEvent {
    }
}
