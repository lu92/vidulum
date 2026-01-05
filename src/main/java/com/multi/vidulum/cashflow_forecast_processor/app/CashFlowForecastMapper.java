package com.multi.vidulum.cashflow_forecast_processor.app;

import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

@Component
public class CashFlowForecastMapper {

    public CashFlowForecastDto.CashFlowForecastStatementJson map(CashFlowForecastStatement statement) {
        return CashFlowForecastDto.CashFlowForecastStatementJson.builder()
                .cashFlowId(statement.getCashFlowId().id())
                .forecasts(mapForecasts(statement.getForecasts()))
                .bankAccountNumber(statement.getBankAccountNumber())
                .categoryStructure(mapCategoryStructure(statement.getCategoryStructure()))
                .lastModification(statement.getLastModification())
                .lastMessageChecksum(ofNullable(statement.getLastMessageChecksum())
                        .map(checksum -> checksum.checksum())
                        .orElse(null))
                .build();
    }

    private Map<String, CashFlowForecastDto.CashFlowMonthlyForecastJson> mapForecasts(
            Map<YearMonth, CashFlowMonthlyForecast> forecasts) {
        return forecasts.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toString(),
                        entry -> mapMonthlyForecast(entry.getValue())
                ));
    }

    private CashFlowForecastDto.CashFlowMonthlyForecastJson mapMonthlyForecast(CashFlowMonthlyForecast forecast) {
        return CashFlowForecastDto.CashFlowMonthlyForecastJson.builder()
                .period(forecast.getPeriod().toString())
                .cashFlowStats(mapCashFlowStats(forecast.getCashFlowStats()))
                .categorizedInFlows(mapCashCategories(forecast.getCategorizedInFlows()))
                .categorizedOutFlows(mapCashCategories(forecast.getCategorizedOutFlows()))
                .status(forecast.getStatus().name())
                .attestation(mapAttestation(forecast.getAttestation()))
                .build();
    }

    private CashFlowForecastDto.CashFlowStatsJson mapCashFlowStats(CashFlowStats stats) {
        return CashFlowForecastDto.CashFlowStatsJson.builder()
                .start(stats.getStart())
                .end(stats.getEnd())
                .netChange(stats.getNetChange())
                .inflowStats(mapCashSummary(stats.getInflowStats()))
                .outflowStats(mapCashSummary(stats.getOutflowStats()))
                .build();
    }

    private CashFlowForecastDto.CashSummaryJson mapCashSummary(CashSummary summary) {
        return CashFlowForecastDto.CashSummaryJson.builder()
                .actual(summary.actual())
                .expected(summary.expected())
                .gapToForecast(summary.gapToForecast())
                .build();
    }

    private List<CashFlowForecastDto.CashCategoryJson> mapCashCategories(List<CashCategory> categories) {
        return categories.stream()
                .map(this::mapCashCategory)
                .collect(Collectors.toList());
    }

    private CashFlowForecastDto.CashCategoryJson mapCashCategory(CashCategory category) {
        return CashFlowForecastDto.CashCategoryJson.builder()
                .categoryName(category.getCategoryName().name())
                .category(category.getCategory().category())
                .subCategories(mapCashCategories(category.getSubCategories()))
                .groupedTransactions(mapGroupedTransactions(category.getGroupedTransactions()))
                .totalPaidValue(category.getTotalPaidValue())
                .budgeting(mapBudgeting(category.getBudgeting()))
                .build();
    }

    private CashFlowForecastDto.GroupedTransactionsJson mapGroupedTransactions(GroupedTransactions groupedTransactions) {
        Map<String, List<CashFlowForecastDto.TransactionDetailsJson>> transactions =
                groupedTransactions.getTransactions().entrySet().stream()
                        .collect(Collectors.toMap(
                                entry -> entry.getKey().name(),
                                entry -> entry.getValue().stream()
                                        .map(this::mapTransactionDetails)
                                        .collect(Collectors.toList())
                        ));
        return CashFlowForecastDto.GroupedTransactionsJson.builder()
                .transactions(transactions)
                .build();
    }

    private CashFlowForecastDto.TransactionDetailsJson mapTransactionDetails(TransactionDetails details) {
        return CashFlowForecastDto.TransactionDetailsJson.builder()
                .cashChangeId(details.getCashChangeId().id())
                .name(details.getName().name())
                .money(details.getMoney())
                .created(details.getCreated())
                .dueDate(details.getDueDate())
                .endDate(details.getEndDate())
                .build();
    }

    private CashFlowForecastDto.BudgetingJson mapBudgeting(Budgeting budgeting) {
        if (budgeting == null) {
            return null;
        }
        return CashFlowForecastDto.BudgetingJson.builder()
                .budget(budgeting.budget())
                .created(budgeting.created())
                .lastUpdated(budgeting.lastUpdated())
                .build();
    }

    private CashFlowForecastDto.AttestationJson mapAttestation(Attestation attestation) {
        if (attestation == null) {
            return null;
        }
        return CashFlowForecastDto.AttestationJson.builder()
                .bankAccountBalance(attestation.bankAccountBalance())
                .type(attestation.type().name())
                .dateTime(attestation.dateTime())
                .build();
    }

    private CashFlowForecastDto.CurrentCategoryStructureJson mapCategoryStructure(
            CurrentCategoryStructure categoryStructure) {
        return CashFlowForecastDto.CurrentCategoryStructureJson.builder()
                .inflowCategoryStructure(mapCategoryNodes(categoryStructure.inflowCategoryStructure()))
                .outflowCategoryStructure(mapCategoryNodes(categoryStructure.outflowCategoryStructure()))
                .lastUpdated(categoryStructure.lastUpdated())
                .build();
    }

    private List<CashFlowForecastDto.CategoryNodeJson> mapCategoryNodes(List<CategoryNode> nodes) {
        return nodes.stream()
                .map(this::mapCategoryNode)
                .collect(Collectors.toList());
    }

    private CashFlowForecastDto.CategoryNodeJson mapCategoryNode(CategoryNode node) {
        return CashFlowForecastDto.CategoryNodeJson.builder()
                .categoryName(node.getCategoryName().name())
                .nodes(mapCategoryNodes(node.getNodes()))
                .budgeting(mapBudgeting(node.getBudgeting()))
                .build();
    }
}
