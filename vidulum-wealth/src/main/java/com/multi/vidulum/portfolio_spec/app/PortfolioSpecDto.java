package com.multi.vidulum.portfolio_spec.app;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio_spec.app.commands.answer.AnswerPortfolioSpecCommand;
import com.multi.vidulum.portfolio_spec.domain.Answer;
import com.multi.vidulum.portfolio_spec.domain.AnswerKind;
import com.multi.vidulum.portfolio_spec.domain.Difference;
import com.multi.vidulum.portfolio_spec.domain.ExchangeSnapshot;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpec;
import com.multi.vidulum.portfolio_spec.domain.SnapshotPosition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.ZonedDateTime;
import java.util.List;

/** Every JSON shape the portfolio-spec endpoints accept or return. */
public final class PortfolioSpecDto {

    private PortfolioSpecDto() {
    }

    /**
     * Request body of {@code POST /portfolio-spec}.
     *
     * <p>The snapshot travels in the body because the backend holds no exchange credentials and
     * cannot fetch it. That makes every check against it one of consistency, not authenticity.
     */
    public record CreateSpecJson(
            @NotBlank(message = "broker is required")
            String broker,

            String connectionId,

            /**
             * What the portfolio will be valued in. Needed here and not only at confirmation:
             * it decides which line of the snapshot is cash, and so what the caller is asked (C10).
             */
            @NotBlank(message = "denominationCurrency is required")
            String denominationCurrency,

            /** Known state. Absent during onboarding, which is the case where nothing is known. */
            String portfolioId,

            @NotNull(message = "snapshotTakenAt is required")
            ZonedDateTime snapshotTakenAt,

            @NotEmpty(message = "at least one position is required")
            @Valid
            List<SnapshotPositionJson> positions) {

        public ExchangeSnapshot toSnapshot() {
            return new ExchangeSnapshot(
                    Broker.of(broker),
                    snapshotTakenAt,
                    positions.stream().map(SnapshotPositionJson::toDomain).toList());
        }
    }

    /**
     * One line of what the exchange reports: everything held, the traded part, and the price of
     * that traded part. OKX supplies exactly these three as {@code cashBal}, {@code spotBal} and
     * {@code openAvgPx}.
     */
    public record SnapshotPositionJson(
            @NotBlank(message = "ticker is required")
            String ticker,

            @NotNull(message = "total is required")
            Quantity total,

            @NotNull(message = "traded is required")
            Quantity traded,

            /** {@code null} when the exchange priced nothing — normal for transferred-in assets. */
            Price reportedAvgPrice) {

        SnapshotPosition toDomain() {
            return new SnapshotPosition(Ticker.of(ticker), total, traded, reportedAvgPrice);
        }
    }

    /**
     * Request body of {@code PUT /portfolio-spec/{id}/answers}.
     *
     * <p>Every answer is anchored to the batch it was given for, so a later purchase creates a
     * new question instead of invalidating this one.
     */
    public record AnswerSpecJson(
            @NotEmpty(message = "at least one answer is required")
            @Valid
            List<GivenAnswerJson> answers) {
    }

    /**
     * @param kind one of {@code COST_PROVIDED}, {@code COST_UNKNOWN}, {@code WITHDRAWAL},
     *             {@code MOVED_TO_OWN_ACCOUNT}. {@code COST_UNKNOWN} is a decision, not a gap:
     *             §4.7 requires "I do not know" to be stated rather than left as silence.
     */
    public record GivenAnswerJson(
            @NotBlank(message = "ticker is required")
            String ticker,

            @NotNull(message = "subName is required")
            String subName,

            @NotNull(message = "quantity is required")
            Quantity quantity,

            @NotNull(message = "kind is required")
            AnswerKind kind,

            /** Required by {@code COST_PROVIDED}, refused by every other kind. */
            Price avgPrice) {

        public AnswerPortfolioSpecCommand.GivenAnswer toDomain() {
            return new AnswerPortfolioSpecCommand.GivenAnswer(
                    Ticker.of(ticker), SubName.of(subName), quantity, new Answer(kind, avgPrice));
        }
    }

    /**
     * Request body of {@code POST /portfolio-spec/{id}/confirm}.
     *
     * <p>Carries a fresh snapshot: confirmation always compares against what the exchange says
     * now, whatever the specification's age (§4.5).
     */
    public record ConfirmSpecJson(
            @NotBlank(message = "portfolioName is required")
            String portfolioName,

            @NotBlank(message = "denominationCurrency is required")
            String denominationCurrency,

            @NotBlank(message = "broker is required")
            String broker,

            @NotNull(message = "snapshotTakenAt is required")
            ZonedDateTime snapshotTakenAt,

            @NotEmpty(message = "at least one position is required")
            @Valid
            List<SnapshotPositionJson> positions) {

        public ExchangeSnapshot toSnapshot() {
            return new ExchangeSnapshot(
                    Broker.of(broker),
                    snapshotTakenAt,
                    positions.stream().map(SnapshotPositionJson::toDomain).toList());
        }
    }

    /** A specification as the API returns it. */
    public record PortfolioSpecJson(
            String id,
            String userId,
            String connectionId,
            String denominationCurrency,
            String status,
            String portfolioId,
            ZonedDateTime snapshotTakenAt,
            boolean snapshotExpired,
            List<DifferenceJson> differences,
            ZonedDateTime createdAt,
            ZonedDateTime lastRecomputedAt) {

        public static PortfolioSpecJson from(PortfolioSpec spec, ZonedDateTime now) {
            return new PortfolioSpecJson(
                    spec.getId().getId(),
                    spec.getUserId().getId(),
                    spec.getConnectionId(),
                    spec.getDenominationCurrency().getId(),
                    spec.getStatus().name(),
                    spec.getPortfolioId() != null ? spec.getPortfolioId().getId() : null,
                    spec.getSnapshot().takenAt(),
                    spec.isSnapshotExpired(now),
                    spec.getDifferences().stream().map(DifferenceJson::from).toList(),
                    spec.getCreatedAt(),
                    spec.getLastRecomputedAt());
        }
    }

    /**
     * One thing that changed. Either {@code question} is set and the caller must answer, or
     * {@code cost} is set and it was settled without anyone being asked — never both.
     */
    public record DifferenceJson(
            String ticker,
            String subName,
            String direction,
            Quantity quantity,
            Quantity costQuantity,
            Price costAvgPrice,
            String costProvenance,
            String question,
            String answerKind) {

        static DifferenceJson from(Difference difference) {
            var cost = difference.resolvedCost();
            return new DifferenceJson(
                    difference.ticker().getId(),
                    difference.subName().getName(),
                    difference.direction().name(),
                    difference.quantity(),
                    cost != null ? cost.quantity() : null,
                    cost != null ? cost.avgPrice() : null,
                    cost != null ? cost.provenance().name() : null,
                    difference.question() != null ? difference.question().name() : null,
                    difference.answer() != null ? difference.answer().kind().name() : null);
        }
    }
}
