package com.multi.vidulum.portfolio.domain.portfolio;
import com.multi.vidulum.common.PortfolioId;
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

    /**
     * Carries the contribution's identity and time rather than letting the aggregate invent them
     * (task C9). Everything else here takes its moment from the caller — see
     * {@code AssetLockedEvent} — and an aggregate reading the wall clock cannot be tested against
     * a fixed one, which is what {@code FixedClockConfig} exists for.
     */
    public record MoneyDepositedEvent(
            PortfolioId portfolioId,
            Money deposit,
            ContributionId contributionId,
            ZonedDateTime dateTime) implements DomainEvent {
    }

    public record MoneyWithdrawEvent(
            PortfolioId portfolioId,
            Money withdrawal,
            ContributionId contributionId,
            ZonedDateTime dateTime) implements DomainEvent {
    }

    public record AssetLockedEvent(
            PortfolioId portfolioId,
            Ticker ticker,
            SubName subName,
            OrderId orderId,
            Quantity quantity,
            ZonedDateTime dateTime) implements DomainEvent {
    }

    public record AssetUnlockedEvent(
            PortfolioId portfolioId,
            Ticker ticker,
            SubName subName,
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
