package com.multi.vidulum.portfolio_spec.infrastructure;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio_spec.domain.Answer;
import com.multi.vidulum.portfolio_spec.domain.AnswerKind;
import com.multi.vidulum.portfolio_spec.domain.Difference;
import com.multi.vidulum.portfolio_spec.domain.DifferenceDirection;
import com.multi.vidulum.portfolio_spec.domain.ExchangeSnapshot;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpec;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpecId;
import com.multi.vidulum.portfolio_spec.domain.QuestionKind;
import com.multi.vidulum.portfolio_spec.domain.SnapshotPosition;
import com.multi.vidulum.portfolio_spec.domain.SpecStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;

/**
 * Mongo representation of {@link PortfolioSpec}.
 *
 * <p>The cost basis is flattened into three fields wherever it appears, as in
 * {@code PortfolioEntity}: the document stays readable and "cost unknown" is unambiguously three
 * nulls rather than a half-filled sub-document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("portfolio_specs")
public class PortfolioSpecEntity {

    @Id
    private String id;

    private String userId;
    private String connectionId;
    private String broker;
    private Date snapshotTakenAt;
    private List<SnapshotPositionDocument> snapshotPositions;
    private List<DifferenceDocument> differences;
    private String status;
    private String portfolioId;
    private Date createdAt;
    private Date lastRecomputedAt;

    public static PortfolioSpecEntity from(PortfolioSpec spec) {
        return PortfolioSpecEntity.builder()
                .id(spec.getId().getId())
                .userId(spec.getUserId().getId())
                .connectionId(spec.getConnectionId())
                .broker(spec.getSnapshot().broker().getId())
                .snapshotTakenAt(toDate(spec.getSnapshot().takenAt()))
                .snapshotPositions(spec.getSnapshot().positions().stream()
                        .map(SnapshotPositionDocument::from).toList())
                .differences(spec.getDifferences().stream().map(DifferenceDocument::from).toList())
                .status(spec.getStatus().name())
                .portfolioId(spec.getPortfolioId() != null ? spec.getPortfolioId().getId() : null)
                .createdAt(toDate(spec.getCreatedAt()))
                .lastRecomputedAt(toDate(spec.getLastRecomputedAt()))
                .build();
    }

    public PortfolioSpec toDomain() {
        return PortfolioSpec.builder()
                .id(PortfolioSpecId.of(id))
                .userId(UserId.of(userId))
                .connectionId(connectionId)
                .snapshot(new ExchangeSnapshot(
                        Broker.of(broker),
                        toZonedDateTime(snapshotTakenAt),
                        snapshotPositions.stream().map(SnapshotPositionDocument::toDomain).toList()))
                .differences(differences.stream().map(DifferenceDocument::toDomain).toList())
                .status(SpecStatus.valueOf(status))
                .portfolioId(portfolioId != null ? PortfolioId.of(portfolioId) : null)
                .createdAt(toZonedDateTime(createdAt))
                .lastRecomputedAt(toZonedDateTime(lastRecomputedAt))
                .build();
    }

    private static Date toDate(ZonedDateTime zdt) {
        return zdt != null ? Date.from(zdt.toInstant()) : null;
    }

    private static ZonedDateTime toZonedDateTime(Date date) {
        return date != null ? ZonedDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC) : null;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SnapshotPositionDocument {
        private String ticker;
        private Quantity total;
        private Quantity traded;
        private Price reportedAvgPrice;

        static SnapshotPositionDocument from(SnapshotPosition position) {
            return SnapshotPositionDocument.builder()
                    .ticker(position.ticker().getId())
                    .total(position.total())
                    .traded(position.traded())
                    .reportedAvgPrice(position.reportedAvgPrice())
                    .build();
        }

        SnapshotPosition toDomain() {
            return new SnapshotPosition(Ticker.of(ticker), total, traded, reportedAvgPrice);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DifferenceDocument {
        private String ticker;
        private String subName;
        private String direction;
        private Quantity quantity;
        private Quantity costQuantity;
        private Price costPrice;
        private Provenance costProvenance;
        private String question;
        private String answerKind;
        private Price answerPrice;

        static DifferenceDocument from(Difference difference) {
            CostBasis cost = difference.resolvedCost();
            return DifferenceDocument.builder()
                    .ticker(difference.ticker().getId())
                    .subName(difference.subName().getName())
                    .direction(difference.direction().name())
                    .quantity(difference.quantity())
                    .costQuantity(cost != null ? cost.quantity() : null)
                    .costPrice(cost != null ? cost.avgPrice() : null)
                    .costProvenance(cost != null ? cost.provenance() : null)
                    .question(difference.question() != null ? difference.question().name() : null)
                    .answerKind(difference.answer() != null ? difference.answer().kind().name() : null)
                    .answerPrice(difference.answer() != null ? difference.answer().avgPrice() : null)
                    .build();
        }

        Difference toDomain() {
            CostBasis cost = costQuantity == null || costPrice == null || costProvenance == null
                    ? null
                    : CostBasis.of(costQuantity, costPrice, costProvenance);
            return new Difference(
                    Ticker.of(ticker),
                    SubName.of(subName),
                    DifferenceDirection.valueOf(direction),
                    quantity,
                    cost,
                    question != null ? QuestionKind.valueOf(question) : null,
                    answerKind != null ? new Answer(AnswerKind.valueOf(answerKind), answerPrice) : null);
        }
    }
}
