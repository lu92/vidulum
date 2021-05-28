package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Segment;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregatedPortfolio {
    private UserId userId;
    private Map<Segment, Map<Broker, List<Asset>>> segmentedAssets = new HashMap<>();
    private Money investedBalance;

    public void addAssets(Segment segment, Broker broker, List<Asset> assets) {
        segmentedAssets.compute(segment, (foundSegment, alreadyAssignedAssets) -> {
            if (alreadyAssignedAssets == null) {
                alreadyAssignedAssets = new HashMap<>();
                alreadyAssignedAssets.put(broker, assets);
            } else {
                alreadyAssignedAssets.put(broker, assets);
            }
            return alreadyAssignedAssets;
        });
//        segmentedAssets  dodac do segmentu assety np PM ma byc gdxj i zlote monety

    }

    public void appendInvestedMoney(Money money) {
        investedBalance = investedBalance.plus(money);
    }
}
