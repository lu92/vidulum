package com.multi.vidulum.bank_data_ingestion.infrastructure.entity;

import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Builder
@Getter
@ToString
@Document("import_jobs")
@CompoundIndex(name = "cashflow_status_idx", def = "{'cashFlowId': 1, 'status': 1}")
public class ImportJobEntity {

    @Id
    private String jobId;

    @Indexed
    private String cashFlowId;

    @Indexed
    private String stagingSessionId;

    private String status;

    private TimestampsDocument timestamps;
    private InputDocument input;
    private ProgressDocument progress;
    private ResultDocument result;
    private RollbackDataDocument rollbackData;
    private SummaryDocument summary;

    // ============ Nested Documents ============

    @Builder
    @Getter
    public static class TimestampsDocument {
        private Date createdAt;
        private Date startedAt;
        private Date completedAt;
        private Date rolledBackAt;
        private Date finalizedAt;

        public static TimestampsDocument fromDomain(ImportJob.ImportTimestamps timestamps) {
            return TimestampsDocument.builder()
                    .createdAt(toDate(timestamps.createdAt()))
                    .startedAt(toDate(timestamps.startedAt()))
                    .completedAt(toDate(timestamps.completedAt()))
                    .rolledBackAt(toDate(timestamps.rolledBackAt()))
                    .finalizedAt(toDate(timestamps.finalizedAt()))
                    .build();
        }

        public ImportJob.ImportTimestamps toDomain() {
            return new ImportJob.ImportTimestamps(
                    toZonedDateTime(createdAt),
                    toZonedDateTime(startedAt),
                    toZonedDateTime(completedAt),
                    toZonedDateTime(rolledBackAt),
                    toZonedDateTime(finalizedAt)
            );
        }
    }

    @Builder
    @Getter
    public static class InputDocument {
        private int totalTransactions;
        private int validTransactions;
        private int duplicateTransactions;
        private int categoriesToCreate;

        public static InputDocument fromDomain(ImportJob.ImportInput input) {
            return InputDocument.builder()
                    .totalTransactions(input.totalTransactions())
                    .validTransactions(input.validTransactions())
                    .duplicateTransactions(input.duplicateTransactions())
                    .categoriesToCreate(input.categoriesToCreate())
                    .build();
        }

        public ImportJob.ImportInput toDomain() {
            return new ImportJob.ImportInput(
                    totalTransactions,
                    validTransactions,
                    duplicateTransactions,
                    categoriesToCreate
            );
        }
    }

    @Builder
    @Getter
    public static class ProgressDocument {
        private int percentage;
        private String currentPhase;
        private List<PhaseProgressDocument> phases;

        public static ProgressDocument fromDomain(ImportJob.ImportProgress progress) {
            List<PhaseProgressDocument> phasesDocs = progress.phases() != null
                    ? progress.phases().stream().map(PhaseProgressDocument::fromDomain).toList()
                    : new ArrayList<>();

            return ProgressDocument.builder()
                    .percentage(progress.percentage())
                    .currentPhase(progress.currentPhase() != null ? progress.currentPhase().name() : null)
                    .phases(phasesDocs)
                    .build();
        }

        public ImportJob.ImportProgress toDomain() {
            List<ImportJob.PhaseProgress> domainPhases = phases != null
                    ? phases.stream().map(PhaseProgressDocument::toDomain).toList()
                    : new ArrayList<>();

            return new ImportJob.ImportProgress(
                    percentage,
                    currentPhase != null ? ImportPhase.valueOf(currentPhase) : null,
                    domainPhases
            );
        }
    }

    @Builder
    @Getter
    public static class PhaseProgressDocument {
        private String name;
        private String status;
        private int processed;
        private int total;
        private Date startedAt;
        private Date completedAt;
        private Long durationMs;

        public static PhaseProgressDocument fromDomain(ImportJob.PhaseProgress phase) {
            return PhaseProgressDocument.builder()
                    .name(phase.name().name())
                    .status(phase.status().name())
                    .processed(phase.processed())
                    .total(phase.total())
                    .startedAt(toDate(phase.startedAt()))
                    .completedAt(toDate(phase.completedAt()))
                    .durationMs(phase.durationMs())
                    .build();
        }

        public ImportJob.PhaseProgress toDomain() {
            return new ImportJob.PhaseProgress(
                    ImportPhase.valueOf(name),
                    PhaseStatus.valueOf(status),
                    processed,
                    total,
                    toZonedDateTime(startedAt),
                    toZonedDateTime(completedAt),
                    durationMs
            );
        }
    }

    @Builder
    @Getter
    public static class ResultDocument {
        private List<String> categoriesCreated;
        private List<String> cashChangesCreated;
        private int transactionsImported;
        private int transactionsFailed;
        private List<ImportErrorDocument> errors;

        public static ResultDocument fromDomain(ImportJob.ImportResult result) {
            List<ImportErrorDocument> errorDocs = result.errors() != null
                    ? result.errors().stream().map(ImportErrorDocument::fromDomain).toList()
                    : new ArrayList<>();

            return ResultDocument.builder()
                    .categoriesCreated(result.categoriesCreated() != null ? new ArrayList<>(result.categoriesCreated()) : new ArrayList<>())
                    .cashChangesCreated(result.cashChangesCreated() != null ? new ArrayList<>(result.cashChangesCreated()) : new ArrayList<>())
                    .transactionsImported(result.transactionsImported())
                    .transactionsFailed(result.transactionsFailed())
                    .errors(errorDocs)
                    .build();
        }

        public ImportJob.ImportResult toDomain() {
            List<ImportJob.ImportError> domainErrors = errors != null
                    ? errors.stream().map(ImportErrorDocument::toDomain).toList()
                    : new ArrayList<>();

            return new ImportJob.ImportResult(
                    categoriesCreated != null ? new ArrayList<>(categoriesCreated) : new ArrayList<>(),
                    cashChangesCreated != null ? new ArrayList<>(cashChangesCreated) : new ArrayList<>(),
                    transactionsImported,
                    transactionsFailed,
                    domainErrors
            );
        }
    }

    @Builder
    @Getter
    public static class ImportErrorDocument {
        private String bankTransactionId;
        private String error;

        public static ImportErrorDocument fromDomain(ImportJob.ImportError error) {
            return ImportErrorDocument.builder()
                    .bankTransactionId(error.bankTransactionId())
                    .error(error.error())
                    .build();
        }

        public ImportJob.ImportError toDomain() {
            return new ImportJob.ImportError(bankTransactionId, error);
        }
    }

    @Builder
    @Getter
    public static class RollbackDataDocument {
        private boolean canRollback;
        private Date rollbackDeadline;
        private List<String> createdCashChangeIds;
        private List<String> createdCategoryNames;

        public static RollbackDataDocument fromDomain(ImportJob.RollbackData rollbackData) {
            return RollbackDataDocument.builder()
                    .canRollback(rollbackData.canRollback())
                    .rollbackDeadline(toDate(rollbackData.rollbackDeadline()))
                    .createdCashChangeIds(rollbackData.createdCashChangeIds() != null ? new ArrayList<>(rollbackData.createdCashChangeIds()) : new ArrayList<>())
                    .createdCategoryNames(rollbackData.createdCategoryNames() != null ? new ArrayList<>(rollbackData.createdCategoryNames()) : new ArrayList<>())
                    .build();
        }

        public ImportJob.RollbackData toDomain() {
            return new ImportJob.RollbackData(
                    canRollback,
                    toZonedDateTime(rollbackDeadline),
                    createdCashChangeIds != null ? new ArrayList<>(createdCashChangeIds) : new ArrayList<>(),
                    createdCategoryNames != null ? new ArrayList<>(createdCategoryNames) : new ArrayList<>()
            );
        }
    }

    @Builder
    @Getter
    public static class SummaryDocument {
        private List<CategoryBreakdownDocument> categoryBreakdown;
        private List<MonthlyBreakdownDocument> monthlyBreakdown;
        private long totalDurationMs;
        private RollbackSummaryDocument rollbackSummary;

        public static SummaryDocument fromDomain(ImportJob.ImportSummary summary) {
            if (summary == null) return null;

            List<CategoryBreakdownDocument> catDocs = summary.categoryBreakdown() != null
                    ? summary.categoryBreakdown().stream().map(CategoryBreakdownDocument::fromDomain).toList()
                    : new ArrayList<>();

            List<MonthlyBreakdownDocument> monthDocs = summary.monthlyBreakdown() != null
                    ? summary.monthlyBreakdown().stream().map(MonthlyBreakdownDocument::fromDomain).toList()
                    : new ArrayList<>();

            return SummaryDocument.builder()
                    .categoryBreakdown(catDocs)
                    .monthlyBreakdown(monthDocs)
                    .totalDurationMs(summary.totalDurationMs())
                    .rollbackSummary(RollbackSummaryDocument.fromDomain(summary.rollbackSummary()))
                    .build();
        }

        public ImportJob.ImportSummary toDomain() {
            List<ImportJob.CategoryBreakdown> catBreakdown = categoryBreakdown != null
                    ? categoryBreakdown.stream().map(CategoryBreakdownDocument::toDomain).toList()
                    : new ArrayList<>();

            List<ImportJob.MonthlyBreakdown> monthBreakdown = monthlyBreakdown != null
                    ? monthlyBreakdown.stream().map(MonthlyBreakdownDocument::toDomain).toList()
                    : new ArrayList<>();

            return new ImportJob.ImportSummary(
                    catBreakdown,
                    monthBreakdown,
                    totalDurationMs,
                    rollbackSummary != null ? rollbackSummary.toDomain() : null
            );
        }
    }

    @Builder
    @Getter
    public static class CategoryBreakdownDocument {
        private String categoryName;
        private String parentCategory;
        private int transactionCount;
        private BigDecimal totalAmount;
        private String currency;
        private String type;
        private boolean isNewCategory;

        public static CategoryBreakdownDocument fromDomain(ImportJob.CategoryBreakdown breakdown) {
            return CategoryBreakdownDocument.builder()
                    .categoryName(breakdown.categoryName())
                    .parentCategory(breakdown.parentCategory())
                    .transactionCount(breakdown.transactionCount())
                    .totalAmount(breakdown.totalAmount().getAmount())
                    .currency(breakdown.totalAmount().getCurrency())
                    .type(breakdown.type().name())
                    .isNewCategory(breakdown.isNewCategory())
                    .build();
        }

        public ImportJob.CategoryBreakdown toDomain() {
            return new ImportJob.CategoryBreakdown(
                    categoryName,
                    parentCategory,
                    transactionCount,
                    Money.of(totalAmount.doubleValue(), currency),
                    Type.valueOf(type),
                    isNewCategory
            );
        }
    }

    @Builder
    @Getter
    public static class MonthlyBreakdownDocument {
        private String month;
        private BigDecimal inflowTotal;
        private BigDecimal outflowTotal;
        private String currency;
        private int transactionCount;

        public static MonthlyBreakdownDocument fromDomain(ImportJob.MonthlyBreakdown breakdown) {
            return MonthlyBreakdownDocument.builder()
                    .month(breakdown.month())
                    .inflowTotal(breakdown.inflowTotal().getAmount())
                    .outflowTotal(breakdown.outflowTotal().getAmount())
                    .currency(breakdown.inflowTotal().getCurrency())
                    .transactionCount(breakdown.transactionCount())
                    .build();
        }

        public ImportJob.MonthlyBreakdown toDomain() {
            return new ImportJob.MonthlyBreakdown(
                    month,
                    Money.of(inflowTotal.doubleValue(), currency),
                    Money.of(outflowTotal.doubleValue(), currency),
                    transactionCount
            );
        }
    }

    @Builder
    @Getter
    public static class RollbackSummaryDocument {
        private int transactionsDeleted;
        private int categoriesDeleted;
        private long rollbackDurationMs;

        public static RollbackSummaryDocument fromDomain(ImportJob.RollbackSummary summary) {
            if (summary == null) return null;

            return RollbackSummaryDocument.builder()
                    .transactionsDeleted(summary.transactionsDeleted())
                    .categoriesDeleted(summary.categoriesDeleted())
                    .rollbackDurationMs(summary.rollbackDurationMs())
                    .build();
        }

        public ImportJob.RollbackSummary toDomain() {
            return new ImportJob.RollbackSummary(
                    transactionsDeleted,
                    categoriesDeleted,
                    rollbackDurationMs
            );
        }
    }

    // ============ Conversion Methods ============

    public static ImportJobEntity fromDomain(ImportJob job) {
        return ImportJobEntity.builder()
                .jobId(job.jobId().id())
                .cashFlowId(job.cashFlowId().id())
                .stagingSessionId(job.stagingSessionId().id())
                .status(job.status().name())
                .timestamps(TimestampsDocument.fromDomain(job.timestamps()))
                .input(InputDocument.fromDomain(job.input()))
                .progress(ProgressDocument.fromDomain(job.progress()))
                .result(ResultDocument.fromDomain(job.result()))
                .rollbackData(RollbackDataDocument.fromDomain(job.rollbackData()))
                .summary(SummaryDocument.fromDomain(job.summary()))
                .build();
    }

    public ImportJob toDomain() {
        return new ImportJob(
                ImportJobId.of(jobId),
                new CashFlowId(cashFlowId),
                StagingSessionId.of(stagingSessionId),
                ImportJobStatus.valueOf(status),
                timestamps.toDomain(),
                input.toDomain(),
                progress.toDomain(),
                result.toDomain(),
                rollbackData.toDomain(),
                summary != null ? summary.toDomain() : null
        );
    }

    // ============ Helper Methods ============

    private static Date toDate(ZonedDateTime zdt) {
        return zdt != null ? Date.from(zdt.toInstant()) : null;
    }

    private static ZonedDateTime toZonedDateTime(Date date) {
        return date != null ? ZonedDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC) : null;
    }
}
