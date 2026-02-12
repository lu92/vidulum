package com.multi.vidulum.cashflow_forecast_processor.infrastructure.entity;

import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.CategoryOrigin;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Data
@Builder
public class CashCategoryEntity {

    private String categoryName;
    private String category;
    private List<CashCategoryEntity> subCategories;
    private GroupedTransactionsEntity groupedTransactions;
    private Money totalPaidValue;
    private BudgetingEntity budgeting;
    private boolean archived;
    private Date validFrom;
    private Date validTo;
    private String origin;

    public static CashCategoryEntity fromDomain(CashCategory cashCategory) {
        if (cashCategory == null) {
            return null;
        }

        List<CashCategoryEntity> subCategoryEntities = cashCategory.getSubCategories() != null
                ? cashCategory.getSubCategories().stream()
                    .map(CashCategoryEntity::fromDomain)
                    .collect(Collectors.toList())
                : new LinkedList<>();

        Date validFromDate = cashCategory.getValidFrom() != null
                ? Date.from(cashCategory.getValidFrom().toInstant())
                : null;

        Date validToDate = cashCategory.getValidTo() != null
                ? Date.from(cashCategory.getValidTo().toInstant())
                : null;

        return CashCategoryEntity.builder()
                .categoryName(cashCategory.getCategoryName() != null ? cashCategory.getCategoryName().name() : null)
                .category(cashCategory.getCategory() != null ? cashCategory.getCategory().category() : null)
                .subCategories(subCategoryEntities)
                .groupedTransactions(GroupedTransactionsEntity.fromDomain(cashCategory.getGroupedTransactions()))
                .totalPaidValue(cashCategory.getTotalPaidValue())
                .budgeting(BudgetingEntity.fromDomain(cashCategory.getBudgeting()))
                .archived(cashCategory.isArchived())
                .validFrom(validFromDate)
                .validTo(validToDate)
                .origin(cashCategory.getOrigin() != null ? cashCategory.getOrigin().name() : null)
                .build();
    }

    public CashCategory toDomain() {
        List<CashCategory> subCategoryDomains = subCategories != null
                ? subCategories.stream()
                    .map(CashCategoryEntity::toDomain)
                    .collect(Collectors.toCollection(LinkedList::new))
                : new LinkedList<>();

        ZonedDateTime validFromDateTime = validFrom != null
                ? ZonedDateTime.ofInstant(validFrom.toInstant(), ZoneOffset.UTC)
                : null;

        ZonedDateTime validToDateTime = validTo != null
                ? ZonedDateTime.ofInstant(validTo.toInstant(), ZoneOffset.UTC)
                : null;

        CategoryOrigin originValue = origin != null ? CategoryOrigin.valueOf(origin) : null;

        return CashCategory.builder()
                .categoryName(categoryName != null ? new CategoryName(categoryName) : null)
                .category(category != null ? new Category(category) : null)
                .subCategories(subCategoryDomains)
                .groupedTransactions(groupedTransactions != null ? groupedTransactions.toDomain() : new GroupedTransactions())
                .totalPaidValue(totalPaidValue)
                .budgeting(budgeting != null ? budgeting.toDomain() : null)
                .archived(archived)
                .validFrom(validFromDateTime)
                .validTo(validToDateTime)
                .origin(originValue)
                .build();
    }

    @Data
    @Builder
    public static class BudgetingEntity {
        private Money budget;
        private Date created;
        private Date lastUpdated;

        public static BudgetingEntity fromDomain(Budgeting budgeting) {
            if (budgeting == null) {
                return null;
            }
            return BudgetingEntity.builder()
                    .budget(budgeting.budget())
                    .created(budgeting.created() != null ? Date.from(budgeting.created().toInstant()) : null)
                    .lastUpdated(budgeting.lastUpdated() != null ? Date.from(budgeting.lastUpdated().toInstant()) : null)
                    .build();
        }

        public Budgeting toDomain() {
            ZonedDateTime createdDateTime = created != null
                    ? ZonedDateTime.ofInstant(created.toInstant(), ZoneOffset.UTC)
                    : null;
            ZonedDateTime lastUpdatedDateTime = lastUpdated != null
                    ? ZonedDateTime.ofInstant(lastUpdated.toInstant(), ZoneOffset.UTC)
                    : null;
            return new Budgeting(budget, createdDateTime, lastUpdatedDateTime);
        }
    }

    @Data
    @Builder
    public static class GroupedTransactionsEntity {
        private List<TransactionEntry> paid;
        private List<TransactionEntry> expected;
        private List<TransactionEntry> forecast;

        public static GroupedTransactionsEntity fromDomain(GroupedTransactions groupedTransactions) {
            if (groupedTransactions == null) {
                return GroupedTransactionsEntity.builder()
                        .paid(new LinkedList<>())
                        .expected(new LinkedList<>())
                        .forecast(new LinkedList<>())
                        .build();
            }

            Map<PaymentStatus, List<TransactionDetails>> transactions = groupedTransactions.getTransactions();

            return GroupedTransactionsEntity.builder()
                    .paid(convertTransactions(transactions.get(PaymentStatus.PAID)))
                    .expected(convertTransactions(transactions.get(PaymentStatus.EXPECTED)))
                    .forecast(convertTransactions(transactions.get(PaymentStatus.FORECAST)))
                    .build();
        }

        private static List<TransactionEntry> convertTransactions(List<TransactionDetails> details) {
            if (details == null) {
                return new LinkedList<>();
            }
            return details.stream()
                    .map(TransactionEntry::fromDomain)
                    .collect(Collectors.toList());
        }

        public GroupedTransactions toDomain() {
            Map<PaymentStatus, List<TransactionDetails>> transactionsMap = new HashMap<>();

            transactionsMap.put(PaymentStatus.PAID, convertToDomain(paid));
            transactionsMap.put(PaymentStatus.EXPECTED, convertToDomain(expected));
            transactionsMap.put(PaymentStatus.FORECAST, convertToDomain(forecast));

            return new GroupedTransactions(transactionsMap);
        }

        private List<TransactionDetails> convertToDomain(List<TransactionEntry> entries) {
            if (entries == null) {
                return new LinkedList<>();
            }
            return entries.stream()
                    .map(TransactionEntry::toDomain)
                    .collect(Collectors.toCollection(LinkedList::new));
        }
    }

    @Data
    @Builder
    public static class TransactionEntry {
        private String cashChangeId;
        private String name;
        private Money money;
        private Date created;
        private Date dueDate;
        private Date endDate;

        public static TransactionEntry fromDomain(TransactionDetails details) {
            if (details == null) {
                return null;
            }
            return TransactionEntry.builder()
                    .cashChangeId(details.getCashChangeId() != null ? details.getCashChangeId().id() : null)
                    .name(details.getName() != null ? details.getName().name() : null)
                    .money(details.getMoney())
                    .created(details.getCreated() != null ? Date.from(details.getCreated().toInstant()) : null)
                    .dueDate(details.getDueDate() != null ? Date.from(details.getDueDate().toInstant()) : null)
                    .endDate(details.getEndDate() != null ? Date.from(details.getEndDate().toInstant()) : null)
                    .build();
        }

        public TransactionDetails toDomain() {
            ZonedDateTime createdDateTime = created != null
                    ? ZonedDateTime.ofInstant(created.toInstant(), ZoneOffset.UTC)
                    : null;
            ZonedDateTime dueDateDateTime = dueDate != null
                    ? ZonedDateTime.ofInstant(dueDate.toInstant(), ZoneOffset.UTC)
                    : null;
            ZonedDateTime endDateDateTime = endDate != null
                    ? ZonedDateTime.ofInstant(endDate.toInstant(), ZoneOffset.UTC)
                    : null;

            return TransactionDetails.builder()
                    .cashChangeId(cashChangeId != null ? new com.multi.vidulum.cashflow.domain.CashChangeId(cashChangeId) : null)
                    .name(name != null ? new com.multi.vidulum.cashflow.domain.Name(name) : null)
                    .money(money)
                    .created(createdDateTime)
                    .dueDate(dueDateDateTime)
                    .endDate(endDateDateTime)
                    .build();
        }
    }
}
