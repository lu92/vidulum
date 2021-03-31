package com.multi.vidulum.portfolio.infrastructure.portfolio.entities;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
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
    private String userId;
    private String name;
    private List<AssetEntity> assets;
    private Money investedBalance;

    public static PortfolioEntity fromSnapshot(PortfolioSnapshot snapshot) {
        String id = Optional.ofNullable(snapshot.getPortfolioId())
                .map(PortfolioId::getId).orElse(null);

        List<AssetEntity> assetEntities = snapshot.getAssets().stream()
                .map(assetSnapshot -> new AssetEntity(
                        assetSnapshot.getTicker().getId(),
                        assetSnapshot.getFullName(),
                        assetSnapshot.getAvgPurchasePrice(),
                        assetSnapshot.getQuantity(),
                        assetSnapshot.getTags()
                ))
                .collect(Collectors.toList());

        return PortfolioEntity.builder()
                .id(id)
                .userId(snapshot.getUserId().getId())
                .name(snapshot.getName())
                .assets(assetEntities)
                .investedBalance(snapshot.getInvestedBalance())
                .build();
    }

    public PortfolioSnapshot toSnapshot() {
        List<PortfolioSnapshot.AssetSnapshot> assetSnapshots = assets.stream()
                .map(assetEntity -> new PortfolioSnapshot.AssetSnapshot(
                        Ticker.of(assetEntity.getTicker()),
                        assetEntity.getFullName(),
                        assetEntity.getAvgPurchasePrice(),
                        assetEntity.getQuantity(),
                        assetEntity.getTags()
                ))
                .collect(Collectors.toList());

        return new PortfolioSnapshot(
                PortfolioId.of(id),
                UserId.of(userId),
                name,
                assetSnapshots,
                investedBalance
        );
    }
}
