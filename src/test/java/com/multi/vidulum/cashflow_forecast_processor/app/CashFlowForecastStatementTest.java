package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.*;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class CashFlowForecastStatementTest {

    @Test
    void updateStats_shouldCalculateBalancesChronologically() {
        // given - forecasts added in non-chronological order
        Map<YearMonth, CashFlowMonthlyForecast> forecasts = new LinkedHashMap<>();

        // Add forecasts in random order (March, January, February)
        forecasts.put(YearMonth.of(2024, 3), createForecastWithNetChange(YearMonth.of(2024, 3), 300.0));
        forecasts.put(YearMonth.of(2024, 1), createForecastWithNetChange(YearMonth.of(2024, 1), 100.0));
        forecasts.put(YearMonth.of(2024, 2), createForecastWithNetChange(YearMonth.of(2024, 2), 200.0));

        CashFlowForecastStatement statement = createStatement(forecasts);

        // when
        statement.updateStats();

        // then - balances should be calculated in chronological order
        CashFlowMonthlyForecast jan = forecasts.get(YearMonth.of(2024, 1));
        CashFlowMonthlyForecast feb = forecasts.get(YearMonth.of(2024, 2));
        CashFlowMonthlyForecast mar = forecasts.get(YearMonth.of(2024, 3));

        // January: start=0, end=100 (netChange=100)
        assertThat(jan.getCashFlowStats().getStart().getAmount()).isEqualByComparingTo("0");
        assertThat(jan.getCashFlowStats().getEnd().getAmount()).isEqualByComparingTo("100");
        assertThat(jan.getCashFlowStats().getNetChange().getAmount()).isEqualByComparingTo("100");

        // February: start=100, end=300 (netChange=200)
        assertThat(feb.getCashFlowStats().getStart().getAmount()).isEqualByComparingTo("100");
        assertThat(feb.getCashFlowStats().getEnd().getAmount()).isEqualByComparingTo("300");
        assertThat(feb.getCashFlowStats().getNetChange().getAmount()).isEqualByComparingTo("200");

        // March: start=300, end=600 (netChange=300)
        assertThat(mar.getCashFlowStats().getStart().getAmount()).isEqualByComparingTo("300");
        assertThat(mar.getCashFlowStats().getEnd().getAmount()).isEqualByComparingTo("600");
        assertThat(mar.getCashFlowStats().getNetChange().getAmount()).isEqualByComparingTo("300");
    }

    @Test
    void updateStats_shouldHandleNegativeNetChange() {
        // given - months with negative net change (outflows > inflows)
        Map<YearMonth, CashFlowMonthlyForecast> forecasts = new LinkedHashMap<>();

        forecasts.put(YearMonth.of(2024, 1), createForecastWithNetChange(YearMonth.of(2024, 1), 1000.0));
        forecasts.put(YearMonth.of(2024, 2), createForecastWithNetChange(YearMonth.of(2024, 2), -300.0));  // negative
        forecasts.put(YearMonth.of(2024, 3), createForecastWithNetChange(YearMonth.of(2024, 3), 200.0));

        CashFlowForecastStatement statement = createStatement(forecasts);

        // when
        statement.updateStats();

        // then
        CashFlowMonthlyForecast jan = forecasts.get(YearMonth.of(2024, 1));
        CashFlowMonthlyForecast feb = forecasts.get(YearMonth.of(2024, 2));
        CashFlowMonthlyForecast mar = forecasts.get(YearMonth.of(2024, 3));

        // January: start=0, end=1000
        assertThat(jan.getCashFlowStats().getStart().getAmount()).isEqualByComparingTo("0");
        assertThat(jan.getCashFlowStats().getEnd().getAmount()).isEqualByComparingTo("1000");

        // February: start=1000, end=700 (lost 300)
        assertThat(feb.getCashFlowStats().getStart().getAmount()).isEqualByComparingTo("1000");
        assertThat(feb.getCashFlowStats().getEnd().getAmount()).isEqualByComparingTo("700");

        // March: start=700, end=900
        assertThat(mar.getCashFlowStats().getStart().getAmount()).isEqualByComparingTo("700");
        assertThat(mar.getCashFlowStats().getEnd().getAmount()).isEqualByComparingTo("900");
    }

    @Test
    void updateStats_shouldHandleYearTransition() {
        // given - forecasts spanning year boundary
        Map<YearMonth, CashFlowMonthlyForecast> forecasts = new LinkedHashMap<>();

        // Add in non-chronological order
        forecasts.put(YearMonth.of(2025, 1), createForecastWithNetChange(YearMonth.of(2025, 1), 500.0));
        forecasts.put(YearMonth.of(2024, 11), createForecastWithNetChange(YearMonth.of(2024, 11), 100.0));
        forecasts.put(YearMonth.of(2024, 12), createForecastWithNetChange(YearMonth.of(2024, 12), 200.0));

        CashFlowForecastStatement statement = createStatement(forecasts);

        // when
        statement.updateStats();

        // then - should be sorted: Nov 2024 -> Dec 2024 -> Jan 2025
        CashFlowMonthlyForecast nov = forecasts.get(YearMonth.of(2024, 11));
        CashFlowMonthlyForecast dec = forecasts.get(YearMonth.of(2024, 12));
        CashFlowMonthlyForecast jan = forecasts.get(YearMonth.of(2025, 1));

        assertThat(nov.getCashFlowStats().getStart().getAmount()).isEqualByComparingTo("0");
        assertThat(nov.getCashFlowStats().getEnd().getAmount()).isEqualByComparingTo("100");

        assertThat(dec.getCashFlowStats().getStart().getAmount()).isEqualByComparingTo("100");
        assertThat(dec.getCashFlowStats().getEnd().getAmount()).isEqualByComparingTo("300");

        assertThat(jan.getCashFlowStats().getStart().getAmount()).isEqualByComparingTo("300");
        assertThat(jan.getCashFlowStats().getEnd().getAmount()).isEqualByComparingTo("800");
    }

    @Test
    void updateStats_shouldPreserveInflowOutflowStats() {
        // given
        Map<YearMonth, CashFlowMonthlyForecast> forecasts = new LinkedHashMap<>();
        CashFlowMonthlyForecast forecast = createForecastWithNetChange(YearMonth.of(2024, 1), 500.0);

        // Set specific inflow/outflow stats
        CashSummary inflowStats = new CashSummary(Money.of(1000, "USD"), Money.of(500, "USD"), Money.zero("USD"));
        CashSummary outflowStats = new CashSummary(Money.of(500, "USD"), Money.of(200, "USD"), Money.zero("USD"));
        forecast.setCashFlowStats(new CashFlowStats(
                Money.zero("USD"),
                Money.zero("USD"),
                Money.zero("USD"),
                inflowStats,
                outflowStats
        ));

        forecasts.put(YearMonth.of(2024, 1), forecast);
        CashFlowForecastStatement statement = createStatement(forecasts);

        // when
        statement.updateStats();

        // then - inflow/outflow stats should be preserved
        CashFlowStats updatedStats = forecasts.get(YearMonth.of(2024, 1)).getCashFlowStats();
        assertThat(updatedStats.getInflowStats().actual().getAmount()).isEqualByComparingTo("1000");
        assertThat(updatedStats.getInflowStats().expected().getAmount()).isEqualByComparingTo("500");
        assertThat(updatedStats.getOutflowStats().actual().getAmount()).isEqualByComparingTo("500");
        assertThat(updatedStats.getOutflowStats().expected().getAmount()).isEqualByComparingTo("200");
    }

    private CashFlowForecastStatement createStatement(Map<YearMonth, CashFlowMonthlyForecast> forecasts) {
        return new CashFlowForecastStatement(
                new CashFlowId("test-cashflow-id"),
                forecasts,
                new BankAccountNumber("test-account", Currency.of("USD")),
                new CurrentCategoryStructure(new ArrayList<>(), new ArrayList<>(), ZonedDateTime.now()),
                null,
                null
        );
    }

    private CashFlowMonthlyForecast createForecastWithNetChange(YearMonth period, double netChangeAmount) {
        // Create categories with transactions that result in the specified net change
        List<CashCategory> inflows;
        List<CashCategory> outflows;

        if (netChangeAmount >= 0) {
            inflows = List.of(createCategoryWithTransactions("Income", netChangeAmount));
            outflows = List.of(createEmptyCategory("Expenses"));
        } else {
            inflows = List.of(createEmptyCategory("Income"));
            outflows = List.of(createCategoryWithTransactions("Expenses", Math.abs(netChangeAmount)));
        }

        return new CashFlowMonthlyForecast(
                period,
                new CashFlowStats(
                        Money.zero("USD"),
                        Money.zero("USD"),
                        Money.zero("USD"),
                        new CashSummary(Money.zero("USD"), Money.zero("USD"), Money.zero("USD")),
                        new CashSummary(Money.zero("USD"), Money.zero("USD"), Money.zero("USD"))
                ),
                new ArrayList<>(inflows),
                new ArrayList<>(outflows),
                CashFlowMonthlyForecast.Status.ACTIVE,
                null
        );
    }

    private CashCategory createCategoryWithTransactions(String name, double amount) {
        GroupedTransactions groupedTransactions = createGroupedTransactions();
        if (amount > 0) {
            TransactionDetails transaction = TransactionDetails.builder()
                    .cashChangeId(new CashChangeId(CashChangeId.generate().id()))
                    .name(new Name("Transaction"))
                    .money(Money.of(amount, "USD"))
                    .created(ZonedDateTime.now())
                    .dueDate(ZonedDateTime.now())
                    .endDate(ZonedDateTime.now())
                    .build();
            groupedTransactions.addTransaction(new Transaction(transaction, PAID));
        }

        return CashCategory.builder()
                .categoryName(new CategoryName(name))
                .category(new Category(name))
                .subCategories(new ArrayList<>())
                .groupedTransactions(groupedTransactions)
                .totalPaidValue(Money.zero("USD"))
                .budgeting(null)
                .build();
    }

    private CashCategory createEmptyCategory(String name) {
        return CashCategory.builder()
                .categoryName(new CategoryName(name))
                .category(new Category(name))
                .subCategories(new ArrayList<>())
                .groupedTransactions(createGroupedTransactions())
                .totalPaidValue(Money.zero("USD"))
                .budgeting(null)
                .build();
    }

    private GroupedTransactions createGroupedTransactions() {
        Map<PaymentStatus, List<TransactionDetails>> transactions = new LinkedHashMap<>();
        transactions.put(PAID, new LinkedList<>());
        transactions.put(EXPECTED, new LinkedList<>());
        transactions.put(FORECAST, new LinkedList<>());
        return new GroupedTransactions(transactions);
    }
}
