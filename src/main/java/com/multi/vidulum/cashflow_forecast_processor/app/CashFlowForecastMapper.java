package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.common.Money;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Stack;
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
                .cashFlowStats(mapCashFlowStats(forecast.getCashFlowStats(), forecast))
                .categorizedInFlows(mapCashCategories(forecast.getCategorizedInFlows()))
                .categorizedOutFlows(mapCashCategories(forecast.getCategorizedOutFlows()))
                .selfTransferInFlows(mapCashCategories(forecast.getSelfTransferInFlows()))
                .selfTransferOutFlows(mapCashCategories(forecast.getSelfTransferOutFlows()))
                .status(forecast.getStatus().name())
                .attestation(mapAttestation(forecast.getAttestation()))
                .build();
    }

    private CashFlowForecastDto.CashFlowStatsJson mapCashFlowStats(CashFlowStats stats, CashFlowMonthlyForecast forecast) {
        return CashFlowForecastDto.CashFlowStatsJson.builder()
                .start(stats.getStart())
                .end(stats.getEnd())
                .netChange(stats.getNetChange())
                .inflowStats(mapCashSummary(stats.getInflowStats()))
                .outflowStats(mapCashSummary(stats.getOutflowStats()))
                .selfTransferStats(computeSelfTransferStats(forecast, stats.getStart().getCurrency()))
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
        if (categories == null) {
            return new java.util.ArrayList<>();
        }
        List<CashFlowForecastDto.CashCategoryJson> result = new java.util.ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            result.add(mapCashCategory(categories.get(i), i));
        }
        return result;
    }

    private CashFlowForecastDto.CashCategoryJson mapCashCategory(CashCategory category, int position) {
        return CashFlowForecastDto.CashCategoryJson.builder()
                .position(position)
                .categoryName(category.getCategoryName().name())
                .category(category.getCategory().category())
                .subCategories(mapCashCategories(category.getSubCategories()))
                .groupedTransactions(mapGroupedTransactions(category.getGroupedTransactions()))
                .totalPaidValue(category.getTotalPaidValue())
                .budgeting(mapBudgeting(category.getBudgeting()))
                .archived(category.isArchived())
                .validFrom(category.getValidFrom())
                .validTo(category.getValidTo())
                .origin(ofNullable(category.getOrigin()).map(Enum::name).orElse(null))
                .selfTransferCategory(category.isSelfTransferCategory())
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
                .selfTransfer(details.isSelfTransfer())
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
        List<CashFlowForecastDto.CategoryNodeJson> result = new java.util.ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            result.add(mapCategoryNode(nodes.get(i), i));
        }
        return result;
    }

    private CashFlowForecastDto.CategoryNodeJson mapCategoryNode(CategoryNode node, int position) {
        return CashFlowForecastDto.CategoryNodeJson.builder()
                .position(position)
                .categoryName(node.getCategoryName().name())
                .nodes(mapCategoryNodes(node.getNodes()))
                .budgeting(mapBudgeting(node.getBudgeting()))
                .archived(node.isArchived())
                .validFrom(node.getValidFrom())
                .validTo(node.getValidTo())
                .origin(ofNullable(node.getOrigin()).map(Enum::name).orElse(null))
                .build();
    }

    private CashFlowForecastDto.SelfTransferStatsJson computeSelfTransferStats(CashFlowMonthlyForecast forecast, String currency) {
        Money outflow = flattenCategories(forecast.getCategorizedOutFlows()).stream()
                .filter(CashCategory::isSelfTransferCategory)
                .map(CashCategory::getTotalPaidValue)
                .reduce(Money.zero(currency), Money::plus);
        Money inflow = flattenCategories(forecast.getCategorizedInFlows()).stream()
                .filter(CashCategory::isSelfTransferCategory)
                .map(CashCategory::getTotalPaidValue)
                .reduce(Money.zero(currency), Money::plus);
        return new CashFlowForecastDto.SelfTransferStatsJson(outflow, inflow);
    }

    private List<CashCategory> flattenCategories(List<CashCategory> cashCategories) {
        if (cashCategories == null) {
            return new LinkedList<>();
        }
        Stack<CashCategory> stack = new Stack<>();
        List<CashCategory> outcome = new LinkedList<>();
        cashCategories.forEach(stack::push);
        while (!stack.isEmpty()) {
            CashCategory takenCashCategory = stack.pop();
            outcome.add(takenCashCategory);
            takenCashCategory.getSubCategories().forEach(stack::push);
        }
        return outcome;
    }
}
