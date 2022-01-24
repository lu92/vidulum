package com.multi.vidulum.portfolio.infrastructure.portfolio.entities;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.portfolio.domain.portfolio.snapshots.PortfolioSnapshot;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

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
    private Money investedBalance;

    public static PortfolioEntity fromSnapshot(PortfolioSnapshot snapshot) {
        String id = Optional.ofNullable(snapshot.getPortfolioId())
                .map(PortfolioId::getId).orElse(null);

        List<AssetEntity> assetEntities = snapshot.getAssets().stream()
                .map(assetSnapshot -> {
                    Segment segment = assetSnapshot.getSegment() == null ? Segment.unknown() : assetSnapshot.getSegment();

                    return new AssetEntity(
                            assetSnapshot.getTicker().getId(),
                            segment.getName(),
                            assetSnapshot.getSubName().getName(),
                            assetSnapshot.getAvgPurchasePrice(),
                            assetSnapshot.getQuantity(),
                            assetSnapshot.getLocked(),
                            assetSnapshot.getFree()
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
                .investedBalance(snapshot.getInvestedBalance())
                .build();
    }

    public PortfolioSnapshot toSnapshot() {
        List<PortfolioSnapshot.AssetSnapshot> assetSnapshots = assets.stream()
                .map(assetEntity -> new PortfolioSnapshot.AssetSnapshot(
                        Ticker.of(assetEntity.getTicker()),
                        Segment.of(assetEntity.getSegment()),
                        SubName.of(assetEntity.getSubName()),
                        assetEntity.getAvgPurchasePrice(),
                        assetEntity.getQuantity(),
                        assetEntity.getLocked(),
                        assetEntity.getFree()
                ))
                .collect(Collectors.toList());

        return new PortfolioSnapshot(
                PortfolioId.of(portfolioId),
                UserId.of(userId),
                name,
                Broker.of(broker),
                assetSnapshots,
                investedBalance
        );
    }
}
