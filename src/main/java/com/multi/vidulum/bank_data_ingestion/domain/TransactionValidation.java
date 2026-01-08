package com.multi.vidulum.bank_data_ingestion.domain;

import java.util.List;

/**
 * Validation result for a staged transaction.
 *
 * @param status      validation status (VALID, INVALID, DUPLICATE)
 * @param errors      list of validation error messages
 * @param isDuplicate true if this transaction is a duplicate
 * @param duplicateOf ID of the existing transaction this is a duplicate of (nullable)
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

    public static TransactionValidation duplicate(String duplicateOf) {
        return new TransactionValidation(
                ValidationStatus.DUPLICATE,
                List.of("Transaction with this bankTransactionId already exists"),
                true,
                duplicateOf
        );
    }
}
