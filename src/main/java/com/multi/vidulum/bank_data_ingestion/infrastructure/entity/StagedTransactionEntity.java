package com.multi.vidulum.bank_data_ingestion.infrastructure.entity;

import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
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
import java.util.Date;
import java.util.List;

@Builder
@Getter
@ToString
@Document("staged_transactions")
@CompoundIndex(name = "cashflow_bank_txn_id_idx",
               def = "{'cashFlowId': 1, 'originalData.bankTransactionId': 1}")
public class StagedTransactionEntity {

    @Id
    private String stagedTransactionId;

    @Indexed
    private String cashFlowId;

    @Indexed
    private String stagingSessionId;

    private OriginalDataDocument originalData;

    private MappedDataDocument mappedData;

    private ValidationDocument validation;

    private Date createdAt;

    @Indexed(expireAfterSeconds = 0)
    private Date expiresAt;

    @Builder
    @Getter
    public static class OriginalDataDocument {
        private String bankTransactionId;
        private String name;
        private String description;
        private String bankCategory;
        private BigDecimal amount;
        private String currency;
        private Type type;
        private Date paidDate;

        public static OriginalDataDocument fromDomain(OriginalTransactionData data) {
            return OriginalDataDocument.builder()
                    .bankTransactionId(data.bankTransactionId())
                    .name(data.name())
                    .description(data.description())
                    .bankCategory(data.bankCategory())
                    .amount(data.money().getAmount())
                    .currency(data.money().getCurrency())
                    .type(data.type())
                    .paidDate(Date.from(data.paidDate().toInstant()))
                    .build();
        }

        public OriginalTransactionData toDomain() {
            return new OriginalTransactionData(
                    bankTransactionId,
                    name,
                    description,
                    bankCategory,
                    Money.of(amount.doubleValue(), currency),
                    type,
                    ZonedDateTime.ofInstant(paidDate.toInstant(), ZoneOffset.UTC)
            );
        }
    }

    @Builder
    @Getter
    public static class MappedDataDocument {
        private String name;
        private String description;
        private String categoryName;
        private String parentCategoryName;
        private BigDecimal amount;
        private String currency;
        private Type type;
        private Date paidDate;

        public static MappedDataDocument fromDomain(MappedTransactionData data) {
            return MappedDataDocument.builder()
                    .name(data.name())
                    .description(data.description())
                    .categoryName(data.categoryName().name())
                    .parentCategoryName(data.parentCategoryName() != null ? data.parentCategoryName().name() : null)
                    .amount(data.money().getAmount())
                    .currency(data.money().getCurrency())
                    .type(data.type())
                    .paidDate(Date.from(data.paidDate().toInstant()))
                    .build();
        }

        public MappedTransactionData toDomain() {
            return new MappedTransactionData(
                    name,
                    description,
                    new CategoryName(categoryName),
                    parentCategoryName != null ? new CategoryName(parentCategoryName) : null,
                    Money.of(amount.doubleValue(), currency),
                    type,
                    ZonedDateTime.ofInstant(paidDate.toInstant(), ZoneOffset.UTC)
            );
        }
    }

    @Builder
    @Getter
    public static class ValidationDocument {
        private ValidationStatus status;
        private List<String> errors;
        private boolean isDuplicate;
        private String duplicateOf;

        public static ValidationDocument fromDomain(TransactionValidation validation) {
            return ValidationDocument.builder()
                    .status(validation.status())
                    .errors(validation.errors())
                    .isDuplicate(validation.isDuplicate())
                    .duplicateOf(validation.duplicateOf())
                    .build();
        }

        public TransactionValidation toDomain() {
            return new TransactionValidation(status, errors, isDuplicate, duplicateOf);
        }
    }

    public static StagedTransactionEntity fromDomain(StagedTransaction transaction) {
        return StagedTransactionEntity.builder()
                .stagedTransactionId(transaction.stagedTransactionId().id())
                .cashFlowId(transaction.cashFlowId().id())
                .stagingSessionId(transaction.stagingSessionId().id())
                .originalData(OriginalDataDocument.fromDomain(transaction.originalData()))
                .mappedData(transaction.mappedData() != null
                        ? MappedDataDocument.fromDomain(transaction.mappedData())
                        : null)
                .validation(ValidationDocument.fromDomain(transaction.validation()))
                .createdAt(Date.from(transaction.createdAt().toInstant()))
                .expiresAt(Date.from(transaction.expiresAt().toInstant()))
                .build();
    }

    public StagedTransaction toDomain() {
        return new StagedTransaction(
                StagedTransactionId.of(stagedTransactionId),
                new CashFlowId(cashFlowId),
                StagingSessionId.of(stagingSessionId),
                originalData.toDomain(),
                mappedData != null ? mappedData.toDomain() : null,
                validation.toDomain(),
                ZonedDateTime.ofInstant(createdAt.toInstant(), ZoneOffset.UTC),
                ZonedDateTime.ofInstant(expiresAt.toInstant(), ZoneOffset.UTC)
        );
    }
}
