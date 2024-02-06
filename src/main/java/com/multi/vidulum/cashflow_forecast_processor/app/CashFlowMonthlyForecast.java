package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.Type;
import lombok.Builder;
import lombok.Data;

import java.time.YearMonth;
import java.util.List;
import java.util.stream.Stream;

@Data
@Builder
public class CashFlowMonthlyForecast {
    YearMonth period;
    CashFlowStats cashFlowStats;
    List<CashCategory> categorizedInFlows;
    List<CashCategory> categorizedOutFlows;

    public boolean hasInfoAbout(CashChangeId cashChangeId) {
        return Stream.concat(categorizedInFlows.stream(), categorizedOutFlows.stream())
                .anyMatch(cashCategory -> cashCategory.hasInfoAbout(cashChangeId));
    }

    record CashChangeLocation(CashChangeId cashChangeId, YearMonth yearMonth, Type type) {

    }

    ;
}
