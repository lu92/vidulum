package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RiskRewardRatio {
    private double risk;
    private double reward;

    public static RiskRewardRatio of(double risk, double reward) {
        return new RiskRewardRatio(risk, reward);
    }

    public static RiskRewardRatio of(Money risk, Money reward) {
        Money rewardRatio = reward.divide(risk);
        return new RiskRewardRatio(1.0, rewardRatio.getAmount().doubleValue());
    }
}
