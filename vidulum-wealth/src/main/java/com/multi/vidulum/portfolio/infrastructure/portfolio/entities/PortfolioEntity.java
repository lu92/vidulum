package com.multi.vidulum.portfolio.infrastructure.portfolio.entities;

import com.multi.vidulum.common.*;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.portfolio.domain.portfolio.snapshots.PortfolioSnapshot;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.portfolio.domain.portfolio.Contribution;
import com.multi.vidulum.portfolio.domain.portfolio.ContributionId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Builder
@Getter
@ToString
@Document("portfolio")
public class PortfolioEntity {
    @Id
    private String id;
    private String portfolioId;
    private String userId;
    private String name;
    private String broker;
    private List<AssetEntity> assets;
    private PortfolioStatus status;
    private List<ContributionEntity> contributions;
    private String allowedDepositCurrency;


    public static PortfolioEntity fromSnapshot(PortfolioSnapshot snapshot) {
        String id = Optional.ofNullable(snapshot.getPortfolioId())
                .map(PortfolioId::getId).orElse(null);

        List<AssetEntity> assetEntities = snapshot.getAssets().stream()
                .map(assetSnapshot ->
                {
                    List<AssetLockEntity> activeLocks = assetSnapshot.getActiveLocks().stream()
                            .map(lockSnapshot -> new AssetLockEntity(
                                    lockSnapshot.orderId(),
                                    lockSnapshot.locked()
                            ))
                            .toList();

                    return new AssetEntity(
                            assetSnapshot.getTicker().getId(),
                            assetSnapshot.getSubName().getName(),
                            costQuantityOf(assetSnapshot.getCostBasis()),
                            costPriceOf(assetSnapshot.getCostBasis()),
                            costProvenanceOf(assetSnapshot.getCostBasis()),
                            assetSnapshot.getQuantity(),
                            assetSnapshot.getLocked(),
                            assetSnapshot.getFree(),
                            activeLocks
                    );
                })
                .collect(Collectors.toList());

        return PortfolioEntity.builder()
                .id(id)
                .portfolioId(snapshot.getPortfolioId().getId())
                .userId(snapshot.getUserId().getId())
                .name(snapshot.getName())
                .broker(snapshot.getBroker().getId())
                .assets(assetEntities)
                .status(snapshot.getStatus())
                .contributions(snapshot.getContributions().stream()
                        .map(ContributionEntity::from).toList())
                .allowedDepositCurrency(snapshot.getAllowedDepositCurrency().getId())
                .build();
    }

    public PortfolioSnapshot toSnapshot() {
        List<PortfolioSnapshot.AssetSnapshot> assetSnapshots = assets.stream()
                .map(assetEntity -> {
                    List<PortfolioSnapshot.AssetLockSnapshot> activeLocks = assetEntity.activeLocks.stream()
                            .map(assetLockEntity -> new PortfolioSnapshot.AssetLockSnapshot(
                                    assetLockEntity.orderId(),
                                    assetLockEntity.locked()
                            ))
                            .collect(Collectors.toList());
                    return new PortfolioSnapshot.AssetSnapshot(
                            Ticker.of(assetEntity.ticker()),
                            SubName.of(assetEntity.subName()),
                            costBasisOf(assetEntity),
                            assetEntity.quantity(),
                            assetEntity.locked(),
                            assetEntity.free(),
                            activeLocks
                    );
                })
                .collect(Collectors.toList());

        return new PortfolioSnapshot(
                PortfolioId.of(portfolioId),
                new UserId(userId),
                name,
                Broker.of(broker),
                assetSnapshots,
                status,
                contributions == null
                        ? List.of()
                        : contributions.stream().map(ContributionEntity::toDomain).toList(),
                Currency.of(allowedDepositCurrency)
        );
    }

    /**
     * One movement in or out (task C9).
     *
     * <p>{@code valueAtArrival} and {@code provenance} are stored together and are either both
     * present or both absent — a value without a source, or a source without a value, is a
     * half-written fact, and the domain record refuses it on construction.
     */
    public record ContributionEntity(
            String id,
            Date when,
            String direction,
            Money what,
            Money valueAtArrival,
            Provenance provenance) {

        static ContributionEntity from(Contribution contribution) {
            return new ContributionEntity(
                    contribution.id().getId(),
                    Date.from(contribution.when().toInstant()),
                    contribution.direction().name(),
                    contribution.what(),
                    contribution.valueAtArrival(),
                    contribution.provenance());
        }

        Contribution toDomain() {
            return new Contribution(
                    ContributionId.of(id),
                    ZonedDateTime.ofInstant(when.toInstant(), ZoneOffset.UTC),
                    Contribution.Direction.valueOf(direction),
                    what,
                    valueAtArrival,
                    provenance);
        }
    }

    /**
     * The cost basis is stored flattened into three fields rather than as a nested object, so the
     * document stays readable and "cost unknown" is unambiguously three nulls rather than a
     * partially filled sub-document.
     */
    private static Quantity costQuantityOf(CostBasis costBasis) {
        return costBasis != null ? costBasis.quantity() : null;
    }

    private static Price costPriceOf(CostBasis costBasis) {
        return costBasis != null ? costBasis.avgPrice() : null;
    }

    private static Provenance costProvenanceOf(CostBasis costBasis) {
        return costBasis != null ? costBasis.provenance() : null;
    }

    private static CostBasis costBasisOf(AssetEntity entity) {
        if (entity.costQuantity() == null || entity.costPrice() == null || entity.costProvenance() == null) {
            return null;
        }
        return CostBasis.of(entity.costQuantity(), entity.costPrice(), entity.costProvenance());
    }

    public record AssetEntity(
            String ticker,
            String subName,
            Quantity costQuantity,
            Price costPrice,
            Provenance costProvenance,
            Quantity quantity,
            Quantity locked,
            Quantity free,
            List<AssetLockEntity> activeLocks
    ) {
    }

    public record AssetLockEntity(
            OrderId orderId,
            Quantity locked) {
    }
}
