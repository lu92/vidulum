package com.multi.vidulum.pnl.domain;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PortfolioId;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * What a portfolio was worth at a moment that has passed (task F9).
 *
 * <p>The reference point every "in this window" measure needs and none of them had. C5 answers
 * "how much has my wealth changed" only <b>since inception</b>, because that is the one starting
 * point available without stored valuations: the contributions are dated, the holdings are not.
 * Asking about a month meant valuing last month's positions at today's prices — the same class of
 * untruth as the zero {@code investedBalance}.
 *
 * <p>Carries the moment it was taken, not the moment that was asked for. A valuation from
 * yesterday answering a question about last Tuesday is a different fact from one taken on
 * Tuesday, and the reader is entitled to know which it got.
 */
public record PortfolioValuation(
        PortfolioId portfolioId,
        ZonedDateTime takenAt,
        Money currentValue,
        Money netContributions) {

    public PortfolioValuation {
        Objects.requireNonNull(portfolioId, "portfolioId is required");
        Objects.requireNonNull(takenAt, "takenAt is required");
        Objects.requireNonNull(currentValue, "currentValue is required");
    }
}
