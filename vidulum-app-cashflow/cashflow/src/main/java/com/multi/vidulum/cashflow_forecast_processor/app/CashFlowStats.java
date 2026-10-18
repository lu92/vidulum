package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CashFlowStats {
    /**
     * Amount of money from the end of previous month
     */
    private Money start;

    /**
     * Amount of money expected on the end of month
     */
    private Money end;

    /**
     * Diff between {@value start} and {@value end}
     */
    private Money netChange;
    private CashSummary inflowStats;
    private CashSummary outflowStats;


    public static CashFlowStats justBalance(Money balance) {
        String currency = balance.getCurrency();

        return new CashFlowStats(
                balance,
                balance,
                Money.zero(currency),
                new CashSummary(
                        Money.zero(currency),
                        Money.zero(currency),
                        Money.zero(currency)
                ),
                new CashSummary(
                        Money.zero(currency),
                        Money.zero(currency),
                        Money.zero(currency)
                )
        );
    }
}
