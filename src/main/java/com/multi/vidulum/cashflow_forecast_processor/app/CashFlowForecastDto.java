package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class CashFlowForecastDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
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
    @NoArgsConstructor
    @AllArgsConstructor
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
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CashFlowStatsJson {
        private Money start;
        private Money end;
        private Money netChange;
        private CashSummaryJson inflowStats;
        private CashSummaryJson outflowStats;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CashSummaryJson {
        private Money actual;
        private Money expected;
        private Money gapToForecast;
    }

    /**
     * JSON representation of a category in a monthly forecast.
     * Contains archiving metadata for UI filtering (show active vs all categories).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CashCategoryJson {
        private String categoryName;
        private String category;
        private List<CashCategoryJson> subCategories;
        private GroupedTransactionsJson groupedTransactions;
        private Money totalPaidValue;
        private BudgetingJson budgeting;
        /** Whether this category is archived (hidden from new transaction creation) */
        private boolean archived;
        /** Start date of validity (null = valid from the beginning) */
        private ZonedDateTime validFrom;
        /** End date of validity (set when archived) */
        private ZonedDateTime validTo;
        /** Origin of this category (SYSTEM, IMPORTED, USER_CREATED) */
        private String origin;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupedTransactionsJson {
        private Map<String, List<TransactionDetailsJson>> transactions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
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
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetingJson {
        private Money budget;
        private ZonedDateTime created;
        private ZonedDateTime lastUpdated;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttestationJson {
        private Money bankAccountBalance;
        private String type;
        private ZonedDateTime dateTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrentCategoryStructureJson {
        private List<CategoryNodeJson> inflowCategoryStructure;
        private List<CategoryNodeJson> outflowCategoryStructure;
        private ZonedDateTime lastUpdated;
    }

    /**
     * JSON representation of a category node in the category structure.
     * Contains archiving metadata for UI filtering.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryNodeJson {
        private String categoryName;
        private List<CategoryNodeJson> nodes;
        private BudgetingJson budgeting;
        /** Whether this category is archived (hidden from new transaction creation) */
        private boolean archived;
        /** Start date of validity (null = valid from the beginning) */
        private ZonedDateTime validFrom;
        /** End date of validity (set when archived) */
        private ZonedDateTime validTo;
        /** Origin of this category (SYSTEM, IMPORTED, USER_CREATED) */
        private String origin;
    }

    /**
     * Response DTO for month statuses endpoint.
     * Used by bank-data-ingestion module to determine which months allow import.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthStatusesResponse {
        private String cashFlowId;
        private Map<YearMonth, ForecastMonthStatus> monthStatuses;
    }

    /**
     * Status of a month in the forecast.
     * Copy of CashFlowMonthlyForecast.Status for API response serialization.
     * Kept separate to enable easy separation into a microservice if needed.
     */
    public enum ForecastMonthStatus {
        /**
         * Historical month waiting for import (before attestation).
         * Created during createCashFlowWithHistory for months before activePeriod.
         * Allows importHistoricalCashChange operations.
         */
        IMPORT_PENDING,

        /**
         * Historical month with finalized imported data (after attestation).
         * Transitions from IMPORT_PENDING when attestHistoricalImport is called.
         * Allows gap filling - importing missed historical transactions.
         */
        IMPORTED,

        /**
         * Month closed through automatic rollover (scheduled job or manual trigger).
         * Transitions from ACTIVE at the beginning of the next month.
         * Allows gap filling - importing missed transactions from bank statements.
         */
        ROLLED_OVER,

        /**
         * Month closed through manual attestation (attestMonth).
         * Represents reconciled/finalized month data from regular usage.
         * @deprecated Use ROLLED_OVER instead.
         */
        @Deprecated
        ATTESTED,

        /**
         * Current month (the "now" month).
         * Only one month can have ACTIVE status at a time.
         * Allows normal operations and ongoing sync (importing from bank statements).
         */
        ACTIVE,

        /**
         * Future month with projected/planned transactions.
         * Does NOT allow importing transactions (blocked by validation).
         */
        FORECASTED
    }
}
