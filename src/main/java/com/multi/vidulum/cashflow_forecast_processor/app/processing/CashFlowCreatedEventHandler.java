package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
@AllArgsConstructor
public class CashFlowCreatedEventHandler implements CashFlowEventHandler<CashFlowEvent.CashFlowCreatedEvent> {

    @Autowired
    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.CashFlowCreatedEvent event) {
        YearMonth current = YearMonth.from(event.created());
        Map<YearMonth, CashFlowMonthlyForecast> monthlyForecasts = IntStream.rangeClosed(0, 11)
                .mapToObj(current::plusMonths)
                .map(yearMonth -> {
                    List<CashCategory> categorizedInflows = new LinkedList<>();
                    categorizedInflows.add(
                            CashCategory.builder()
                                    .categoryName(new CategoryName("Uncategorized"))
                                    .category(new Category("Uncategorized"))
                                    .subCategories(List.of())
                                    .groupedTransactions(new GroupedTransactions())
                                    .totalPaidValue(Money.zero(event.bankAccount().balance().getCurrency()))
                                    .build()
                    );

                    List<CashCategory> categorizedOutflows = new LinkedList<>();
                    categorizedOutflows.add(
                            CashCategory.builder()
                                    .categoryName(new CategoryName("Uncategorized"))
                                    .category(new Category("Uncategorized"))
                                    .subCategories(List.of())
                                    .groupedTransactions(new GroupedTransactions())
                                    .totalPaidValue(Money.zero(event.bankAccount().balance().getCurrency()))
                                    .build()

                    );

                    return new CashFlowMonthlyForecast(
                            yearMonth,
                            CashFlowStats.justBalance(event.bankAccount().balance()),
                            categorizedInflows,
                            categorizedOutflows,
                            CashFlowMonthlyForecast.Status.FORECASTED,
                            null
                    );
                }).collect(Collectors.toMap(
                        CashFlowMonthlyForecast::getPeriod,
                        Function.identity()
                ));

        monthlyForecasts.get(current).setStatus(CashFlowMonthlyForecast.Status.ACTIVE);

        statementRepository.save(
                new CashFlowForecastStatement(
                        event.cashFlowId(),
                        monthlyForecasts,
                        event.bankAccount().bankAccountNumber(),
                        getChecksum(event)
                ));
    }

}
