package com.multi.vidulum.cashflow;

import com.multi.vidulum.common.Category;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;

import java.time.YearMonth;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.multi.vidulum.cashflow.MoneyFlowType.*;

class ForecastProcessor {

    public CashFlowForecast generateForecast(CashFlow cashFlow, YearMonth start, YearMonth end) {
        List<CashFlowMonthlyForecast> data = new LinkedList<>();
        YearMonth processingPeriod = start;
        do {
            data.add(processMonth(cashFlow, processingPeriod));
            processingPeriod = moveToNextMonth(processingPeriod);
        } while (processingPeriod.equals(end));
        return new CashFlowForecast(data, start, end);
    }

    private YearMonth moveToNextMonth(YearMonth processingPeriod) {
        return processingPeriod.plusMonths(1);
    }

    private CashFlowMonthlyForecast processMonth(CashFlow cashFlow, YearMonth processingPeriod) {
        Currency currency = Currency.of(cashFlow.getBankAccount().money().getCurrency());
        List<InFlow> inFlows = cashFlow.getInFlowData().getOrDefault(processingPeriod, List.of());
        List<CashCategory> categorizedInFlows = fun(currency, inFlows);

        List<OutFlow> outFlows = cashFlow.getOutFlowData().getOrDefault(processingPeriod, List.of());
        List<CashCategory> categorizedOutFlows = fun(currency, outFlows);

        CashSummary inflowCashSummary = summarizeCashForFlow(inFlows, currency);
        CashSummary outflowCashSummary = summarizeCashForFlow(outFlows, currency);

        return new CashFlowMonthlyForecast(
                processingPeriod,
                categorizedInFlows,
                categorizedOutFlows,
                Money.zero(currency.getId()),
                inflowCashSummary,
                outflowCashSummary
        );
    }

    private List<CashCategory> fun(Currency currency, List<? extends MoneyFlow> moneyFlows) {
        Function<List<? extends MoneyFlow>, Money> sumTotalValue = flows ->
                flows.stream()
                        .map(MoneyFlow::money)
                        .reduce(Money.zero(currency.getId()), Money::plus);

        Map<Category, ? extends List<? extends MoneyFlow>> categorizedMoneyFlows = moneyFlows
                .stream()
                .collect(Collectors.groupingBy(MoneyFlow::category));

        return categorizedMoneyFlows
                .entrySet().stream()
                .map(entry -> new CashCategory(
                        entry.getKey(),
                        sumTotalValue.apply(entry.getValue())
                ))
                .collect(Collectors.toList());
    }

    private CashSummary summarizeCashForFlow(List<? extends MoneyFlow> moneyFlows, Currency currency) {
        Map<MoneyFlowType, Money> typeMoneyMapForInflows = groupByCashFlowType(currency, moneyFlows);
        return new CashSummary(
                typeMoneyMapForInflows.get(ACTUAL),
                typeMoneyMapForInflows.get(EXPECTED),
                typeMoneyMapForInflows.get(GAP_TO_FORECAST)
        );
    }

    private Map<MoneyFlowType, Money> groupByCashFlowType(Currency currency, List<? extends MoneyFlow> moneyFlows) {
        Map<MoneyFlowType, List<MoneyFlow>> inFlowsByType = moneyFlows
                .stream()
                .collect(Collectors.groupingBy(MoneyFlow::type));

        return Arrays.stream(MoneyFlowType.values())
                .collect(
                        Collectors.toMap(
                                Function.identity(),
                                moneyFlowType -> inFlowsByType.getOrDefault(moneyFlowType, List.of())
                                        .stream()
                                        .map(MoneyFlow::money)
                                        .reduce(Money.zero(currency.getId()), Money::plus)));
    }

}
