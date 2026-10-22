package com.multi.vidulum.bank_data_ingestion.staging.domain;

import com.multi.vidulum.common.CashChangeId;

import java.util.List;

/**
 * Validation result for a staged transaction.
 *
 * @param status      validation status (VALID, INVALID, DUPLICATE)
 * @param errors      list of validation error messages
 * @param isDuplicate true if this transaction is a duplicate
 * @param duplicateOf the cash change this row collides with, as cashflow recorded it (nullable).
 *                    It used to be filled with the <i>incoming</i> row's bankTransactionId at all
 *                    three call sites - a field documented as the answer, holding the question -
 *                    because {@code CashFlowModuleApi} returned a bare set of ids and the existing
 *                    cash change's id never crossed the boundary.
 */
public record TransactionValidation(
        ValidationStatus status,
        List<String> errors,
        boolean isDuplicate,
        String duplicateOf
) {

    public static TransactionValidation valid() {
        return new TransactionValidation(ValidationStatus.VALID, List.of(), false, null);
    }

    public static TransactionValidation invalid(List<String> errors) {
        return new TransactionValidation(ValidationStatus.INVALID, errors, false, null);
    }

    public static TransactionValidation invalid(String error) {
        return new TransactionValidation(ValidationStatus.INVALID, List.of(error), false, null);
    }

    public static TransactionValidation duplicate(CashChangeId duplicateOf) {
        return new TransactionValidation(
                ValidationStatus.DUPLICATE,
                List.of("Transaction with this bankTransactionId already exists as [%s]"
                        .formatted(duplicateOf.id())),
                true,
                duplicateOf.id()
        );
    }

    public static TransactionValidation pendingMapping(String bankCategory) {
        return new TransactionValidation(
                ValidationStatus.PENDING_MAPPING,
                List.of("No mapping configured for bank category: " + bankCategory),
                false,
                null
        );
    }
}
