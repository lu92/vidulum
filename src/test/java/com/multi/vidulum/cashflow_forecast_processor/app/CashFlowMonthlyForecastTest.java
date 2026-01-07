package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.common.Money;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static com.multi.vidulum.cashflow_forecast_processor.app.PaymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class CashFlowMonthlyForecastTest {

    @Test
    void calcNetChange_shouldSumTransactionsFromMainCategories() {
        // given - transactions in main category (not subcategory)
        CashCategory inflowCategory = createCategoryWithTransactions("Sales", 100.0, 50.0);
        CashCategory outflowCategory = createCategoryWithTransactions("Expenses", 30.0, 20.0);

        CashFlowMonthlyForecast forecast = createForecast(
                List.of(inflowCategory),
                List.of(outflowCategory)
        );

        // when
        Money netChange = forecast.calcNetChange();

        // then
        // inflows: 100 + 50 = 150
        // outflows: 30 + 20 = 50
        // net change: 150 - 50 = 100
        assertThat(netChange.getAmount()).isEqualByComparingTo("100");
    }

    @Test
    void calcNetChange_shouldSumTransactionsFromSubCategories() {
        // given - transactions in subcategories only
        CashCategory incomeSubCategory = createCategoryWithTransactions("Salary", 5000.0);
        CashCategory bonusSubCategory = createCategoryWithTransactions("Bonus", 1000.0);
        CashCategory incomeMainCategory = createCategoryWithSubCategories("Income", List.of(incomeSubCategory, bonusSubCategory));

        CashCategory groceriesSubCategory = createCategoryWithTransactions("Groceries", 500.0);
        CashCategory fuelSubCategory = createCategoryWithTransactions("Fuel", 200.0);
        CashCategory expensesMainCategory = createCategoryWithSubCategories("Expenses", List.of(groceriesSubCategory, fuelSubCategory));

        CashFlowMonthlyForecast forecast = createForecast(
                List.of(incomeMainCategory),
                List.of(expensesMainCategory)
        );

        // when
        Money netChange = forecast.calcNetChange();

        // then
        // inflows: 5000 + 1000 = 6000
        // outflows: 500 + 200 = 700
        // net change: 6000 - 700 = 5300
        assertThat(netChange.getAmount()).isEqualByComparingTo("5300");
    }

    @Test
    void calcNetChange_shouldSumTransactionsFromBothMainAndSubCategories() {
        // given - transactions in both main category and subcategories
        CashCategory salarySubCategory = createCategoryWithTransactions("Salary", 5000.0);
        CashCategory incomeMainCategory = createCategoryWithSubCategoriesAndOwnTransactions(
                "Income",
                List.of(salarySubCategory),
                100.0  // direct transaction on main category (e.g., miscellaneous income)
        );

        CashCategory groceriesSubCategory = createCategoryWithTransactions("Groceries", 300.0);
        CashCategory expensesMainCategory = createCategoryWithSubCategoriesAndOwnTransactions(
                "Expenses",
                List.of(groceriesSubCategory),
                50.0  // direct transaction on main category
        );

        CashFlowMonthlyForecast forecast = createForecast(
                List.of(incomeMainCategory),
                List.of(expensesMainCategory)
        );

        // when
        Money netChange = forecast.calcNetChange();

        // then
        // inflows: 5000 (subcategory) + 100 (main) = 5100
        // outflows: 300 (subcategory) + 50 (main) = 350
        // net change: 5100 - 350 = 4750
        assertThat(netChange.getAmount()).isEqualByComparingTo("4750");
    }

    @Test
    void calcNetChange_shouldHandleNestedSubCategories() {
        // given - deeply nested subcategories
        CashCategory level3Category = createCategoryWithTransactions("Online Sales", 1000.0);
        CashCategory level2Category = createCategoryWithSubCategories("Product Sales", List.of(level3Category));
        CashCategory level1Category = createCategoryWithSubCategories("Revenue", List.of(level2Category));

        CashCategory uncategorizedOutflow = createCategoryWithTransactions("Uncategorized", 0.0);

        CashFlowMonthlyForecast forecast = createForecast(
                List.of(level1Category),
                List.of(uncategorizedOutflow)
        );

        // when
        Money netChange = forecast.calcNetChange();

        // then
        assertThat(netChange.getAmount()).isEqualByComparingTo("1000");
    }

    @Test
    void calcNetChange_shouldReturnZeroForEmptyCategories() {
        // given
        CashCategory emptyInflow = createCategoryWithTransactions("Uncategorized");
        CashCategory emptyOutflow = createCategoryWithTransactions("Uncategorized");

        CashFlowMonthlyForecast forecast = createForecast(
                List.of(emptyInflow),
                List.of(emptyOutflow)
        );

        // when
        Money netChange = forecast.calcNetChange();

        // then
        assertThat(netChange.getAmount()).isEqualByComparingTo("0");
    }

    private CashFlowMonthlyForecast createForecast(List<CashCategory> inflows, List<CashCategory> outflows) {
        return new CashFlowMonthlyForecast(
                YearMonth.of(2024, 1),
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

    private CashCategory createCategoryWithTransactions(String name, double... amounts) {
        GroupedTransactions groupedTransactions = createGroupedTransactions();
        for (double amount : amounts) {
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

    private CashCategory createCategoryWithSubCategories(String name, List<CashCategory> subCategories) {
        return CashCategory.builder()
                .categoryName(new CategoryName(name))
                .category(new Category(name))
                .subCategories(new ArrayList<>(subCategories))
                .groupedTransactions(createGroupedTransactions())
                .totalPaidValue(Money.zero("USD"))
                .budgeting(null)
                .build();
    }

    private CashCategory createCategoryWithSubCategoriesAndOwnTransactions(String name, List<CashCategory> subCategories, double... amounts) {
        GroupedTransactions groupedTransactions = createGroupedTransactions();
        for (double amount : amounts) {
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
                .subCategories(new ArrayList<>(subCategories))
                .groupedTransactions(groupedTransactions)
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
