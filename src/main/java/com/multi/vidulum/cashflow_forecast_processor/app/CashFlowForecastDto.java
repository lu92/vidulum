package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class CashFlowForecastDto {

    @Data
    @Builder
    public static class CashFlowForecastStatementJson {
        private String cashFlowId;
        private Map<String, CashFlowMonthlyForecastJson> forecasts;
        private BankAccountNumber bankAccountNumber;
        private CurrentCategoryStructureJson categoryStructure;
        private ZonedDateTime lastModification;
        private String lastMessageChecksum;
    }

    @Data
    @Builder
    public static class CashFlowMonthlyForecastJson {
        private String period;
        private CashFlowStatsJson cashFlowStats;
        private List<CashCategoryJson> categorizedInFlows;
        private List<CashCategoryJson> categorizedOutFlows;
        private String status;
        private AttestationJson attestation;
    }

    @Data
    @Builder
    public static class CashFlowStatsJson {
        private Money start;
        private Money end;
        private Money netChange;
        private CashSummaryJson inflowStats;
        private CashSummaryJson outflowStats;
    }

    @Data
    @Builder
    public static class CashSummaryJson {
        private Money actual;
        private Money expected;
        private Money gapToForecast;
    }

    @Data
    @Builder
    public static class CashCategoryJson {
        private String categoryName;
        private String category;
        private List<CashCategoryJson> subCategories;
        private GroupedTransactionsJson groupedTransactions;
        private Money totalPaidValue;
        private BudgetingJson budgeting;
    }

    @Data
    @Builder
    public static class GroupedTransactionsJson {
        private Map<String, List<TransactionDetailsJson>> transactions;
    }

    @Data
    @Builder
    public static class TransactionDetailsJson {
        private String cashChangeId;
        private String name;
        private Money money;
        private ZonedDateTime created;
        private ZonedDateTime dueDate;
        private ZonedDateTime endDate;
    }

    @Data
    @Builder
    public static class BudgetingJson {
        private Money budget;
        private ZonedDateTime created;
        private ZonedDateTime lastUpdated;
    }

    @Data
    @Builder
    public static class AttestationJson {
        private Money bankAccountBalance;
        private String type;
        private ZonedDateTime dateTime;
    }

    @Data
    @Builder
    public static class CurrentCategoryStructureJson {
        private List<CategoryNodeJson> inflowCategoryStructure;
        private List<CategoryNodeJson> outflowCategoryStructure;
        private ZonedDateTime lastUpdated;
    }

    @Data
    @Builder
    public static class CategoryNodeJson {
        private String categoryName;
        private List<CategoryNodeJson> nodes;
        private BudgetingJson budgeting;
    }
}
