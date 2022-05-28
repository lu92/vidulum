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
    private PortfolioStatus status;
    private Money investedBalance;
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
                            assetSnapshot.getAvgPurchasePrice(),
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
                .investedBalance(snapshot.getInvestedBalance())
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
                            assetEntity.avgPurchasePrice(),
                            assetEntity.quantity(),
                            assetEntity.locked(),
                            assetEntity.free(),
                            activeLocks
                    );
                })
                .collect(Collectors.toList());

        return new PortfolioSnapshot(
                PortfolioId.of(portfolioId),
                UserId.of(userId),
                name,
                Broker.of(broker),
                assetSnapshots,
                status,
                investedBalance,
                Currency.of(allowedDepositCurrency)
        );
    }

    public record AssetEntity(
            String ticker,
            String subName,
            Price avgPurchasePrice,
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
