package com.multi.vidulum.cashflow_forecast_processor.app.processing;

import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;

/**
 * Handler for ImportRolledBackEvent.
 * Clears all transactions from IMPORT_PENDING months and resets their stats.
 * Optionally resets category structure to just Uncategorized.
 */
@Slf4j
@Component
@AllArgsConstructor
public class ImportRolledBackEventHandler implements CashFlowEventHandler<CashFlowEvent.ImportRolledBackEvent> {

    private final CashFlowForecastStatementRepository statementRepository;
    private final Clock clock;

    @Override
    public void handle(CashFlowEvent.ImportRolledBackEvent event) {
        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(event.cashFlowId())
                .orElseThrow(() -> new IllegalStateException(
                        "CashFlowForecastStatement not found for CashFlow: " + event.cashFlowId().id()));

        int clearedMonths = 0;
        int clearedTransactions = 0;

        // Get currency from existing statement
        String currency = getCurrencyFromStatement(statement);

        // Reset all IMPORT_PENDING months to empty state
        for (CashFlowMonthlyForecast forecast : statement.getForecasts().values()) {
            if (forecast.getStatus() == CashFlowMonthlyForecast.Status.IMPORT_PENDING) {
                int transactionsInMonth = countTransactionsInMonth(forecast);
                clearedTransactions += transactionsInMonth;

                // Reset month to empty state
                resetMonthToEmpty(forecast, currency, event.categoriesDeleted());
                clearedMonths++;
            }
        }

        // Optionally reset category structure to just Uncategorized
        if (event.categoriesDeleted()) {
            resetCategoryStructure(statement);
        }

        statement.updateStats();
        statement.setLastMessageChecksum(getChecksum(event));
        statementRepository.save(statement);

        log.info("Import rolled back for CashFlow [{}]. Cleared [{}] transactions from [{}] IMPORT_PENDING months. " +
                        "Categories reset: [{}]",
                event.cashFlowId().id(), clearedTransactions, clearedMonths, event.categoriesDeleted());
    }

    private String getCurrencyFromStatement(CashFlowForecastStatement statement) {
        // Get currency from bank account
        return statement.getBankAccountNumber().denomination().getId();
    }

    private int countTransactionsInMonth(CashFlowMonthlyForecast forecast) {
        int count = 0;
        for (CashCategory category : forecast.getCategorizedInFlows()) {
            count += countTransactionsInCategory(category);
        }
        for (CashCategory category : forecast.getCategorizedOutFlows()) {
            count += countTransactionsInCategory(category);
        }
        return count;
    }

    private int countTransactionsInCategory(CashCategory category) {
        int count = category.getGroupedTransactions().getTransactions().values().stream()
                .mapToInt(List::size)
                .sum();
        for (CashCategory subCategory : category.getSubCategories()) {
            count += countTransactionsInCategory(subCategory);
        }
        return count;
    }

    private void resetMonthToEmpty(CashFlowMonthlyForecast forecast, String currency, boolean resetCategories) {
        // Reset stats to zero
        forecast.setCashFlowStats(CashFlowStats.justBalance(Money.zero(currency)));

        if (resetCategories) {
            // Reset to just Uncategorized category
            forecast.setCategorizedInFlows(createEmptyUncategorizedList(currency));
            forecast.setCategorizedOutFlows(createEmptyUncategorizedList(currency));
        } else {
            // Keep existing categories but clear their transactions
            clearTransactionsFromCategories(forecast.getCategorizedInFlows(), currency);
            clearTransactionsFromCategories(forecast.getCategorizedOutFlows(), currency);
        }
    }

    private List<CashCategory> createEmptyUncategorizedList(String currency) {
        List<CashCategory> categories = new LinkedList<>();
        categories.add(
                CashCategory.builder()
                        .categoryName(new CategoryName("Uncategorized"))
                        .category(new Category("Uncategorized"))
                        .subCategories(List.of())
                        .groupedTransactions(new GroupedTransactions())
                        .totalPaidValue(Money.zero(currency))
                        .build()
        );
        return categories;
    }

    private void clearTransactionsFromCategories(List<CashCategory> categories, String currency) {
        for (CashCategory category : categories) {
            clearTransactionsFromCategory(category, currency);
        }
    }

    private void clearTransactionsFromCategory(CashCategory category, String currency) {
        // Clear all transactions
        category.setGroupedTransactions(new GroupedTransactions());
        category.setTotalPaidValue(Money.zero(currency));

        // Recursively clear subcategories
        for (CashCategory subCategory : category.getSubCategories()) {
            clearTransactionsFromCategory(subCategory, currency);
        }
    }

    private void resetCategoryStructure(CashFlowForecastStatement statement) {
        // Reset current category structure to just Uncategorized
        List<CategoryNode> inflowCategoryStructure = new LinkedList<>();
        inflowCategoryStructure.add(new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>()));
        List<CategoryNode> outflowCategoryStructure = new LinkedList<>();
        outflowCategoryStructure.add(new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>()));

        statement.setCategoryStructure(new CurrentCategoryStructure(
                inflowCategoryStructure,
                outflowCategoryStructure,
                ZonedDateTime.now(clock)
        ));
    }
}
