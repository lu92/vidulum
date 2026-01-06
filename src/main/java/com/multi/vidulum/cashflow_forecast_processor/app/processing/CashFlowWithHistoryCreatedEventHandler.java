package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handler for CashFlowWithHistoryCreatedEvent.
 * Creates monthly forecasts with:
 * - IMPORT_PENDING for historical months (from startPeriod to month before activePeriod)
 * - ACTIVE for the current month (activePeriod)
 * - FORECASTED for future months (11 months after activePeriod)
 */
@Component
@AllArgsConstructor
public class CashFlowWithHistoryCreatedEventHandler implements CashFlowEventHandler<CashFlowEvent.CashFlowWithHistoryCreatedEvent> {

    @Autowired
    private Clock clock;

    @Autowired
    private final CashFlowForecastStatementRepository statementRepository;

    @Override
    public void handle(CashFlowEvent.CashFlowWithHistoryCreatedEvent event) {
        YearMonth startPeriod = event.startPeriod();
        YearMonth activePeriod = event.activePeriod();
        Money currency = event.bankAccount().balance();

        // Create monthly forecasts for all periods
        Map<YearMonth, CashFlowMonthlyForecast> monthlyForecasts = Stream.concat(
                // Historical months (IMPORT_PENDING): from startPeriod to month before activePeriod
                generateMonths(startPeriod, activePeriod.minusMonths(1))
                        .map(yearMonth -> createMonthlyForecast(yearMonth, currency, CashFlowMonthlyForecast.Status.IMPORT_PENDING)),
                // Current and future months: activePeriod + 11 months
                generateMonths(activePeriod, activePeriod.plusMonths(11))
                        .map(yearMonth -> {
                            CashFlowMonthlyForecast.Status status = yearMonth.equals(activePeriod)
                                    ? CashFlowMonthlyForecast.Status.ACTIVE
                                    : CashFlowMonthlyForecast.Status.FORECASTED;
                            return createMonthlyForecast(yearMonth, currency, status);
                        })
        ).collect(Collectors.toMap(
                CashFlowMonthlyForecast::getPeriod,
                Function.identity()
        ));

        // Create category structure
        List<CategoryNode> inflowCategoryStructure = new LinkedList<>();
        inflowCategoryStructure.add(new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>()));
        List<CategoryNode> outflowCategoryStructure = new LinkedList<>();
        outflowCategoryStructure.add(new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>()));
        CurrentCategoryStructure currentCategoryStructure = new CurrentCategoryStructure(
                inflowCategoryStructure,
                outflowCategoryStructure,
                ZonedDateTime.now(this.clock)
        );

        statementRepository.save(
                new CashFlowForecastStatement(
                        event.cashFlowId(),
                        monthlyForecasts,
                        event.bankAccount().bankAccountNumber(),
                        currentCategoryStructure,
                        event.created(),
                        getChecksum(event)
                ));
    }

    /**
     * Generate a stream of YearMonths from start to end (inclusive).
     */
    private Stream<YearMonth> generateMonths(YearMonth start, YearMonth end) {
        if (start.isAfter(end)) {
            return Stream.empty();
        }
        List<YearMonth> months = new LinkedList<>();
        YearMonth current = start;
        while (!current.isAfter(end)) {
            months.add(current);
            current = current.plusMonths(1);
        }
        return months.stream();
    }

    private CashFlowMonthlyForecast createMonthlyForecast(YearMonth yearMonth, Money currency, CashFlowMonthlyForecast.Status status) {
        List<CashCategory> categorizedInflows = new LinkedList<>();
        categorizedInflows.add(
                CashCategory.builder()
                        .categoryName(new CategoryName("Uncategorized"))
                        .category(new Category("Uncategorized"))
                        .subCategories(List.of())
                        .groupedTransactions(new GroupedTransactions())
                        .totalPaidValue(Money.zero(currency.getCurrency()))
                        .build()
        );

        List<CashCategory> categorizedOutflows = new LinkedList<>();
        categorizedOutflows.add(
                CashCategory.builder()
                        .categoryName(new CategoryName("Uncategorized"))
                        .category(new Category("Uncategorized"))
                        .subCategories(List.of())
                        .groupedTransactions(new GroupedTransactions())
                        .totalPaidValue(Money.zero(currency.getCurrency()))
                        .build()
        );

        return new CashFlowMonthlyForecast(
                yearMonth,
                CashFlowStats.justBalance(currency),
                categorizedInflows,
                categorizedOutflows,
                status,
                null
        );
    }
}
