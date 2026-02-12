package com.multi.vidulum.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Auth
    AUTH_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),
    AUTH_EMAIL_TAKEN(HttpStatus.CONFLICT, "Email is already registered"),
    AUTH_USERNAME_TAKEN(HttpStatus.CONFLICT, "Username is already taken"),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid username or password"),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token has expired"),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Token is invalid"),
    AUTH_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),

    // Validation
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),
    VALIDATION_INVALID_JSON(HttpStatus.BAD_REQUEST, "Invalid JSON format"),
    INVALID_USER_ID_FORMAT(HttpStatus.BAD_REQUEST, "Invalid User ID format. Expected: UXXXXXXXX"),

    // CashFlow - Resources
    CASHFLOW_NOT_FOUND(HttpStatus.NOT_FOUND, "CashFlow not found"),
    CASHCHANGE_NOT_FOUND(HttpStatus.NOT_FOUND, "CashChange not found"),
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "Category not found"),
    BUDGETING_NOT_FOUND(HttpStatus.NOT_FOUND, "Budgeting not found"),

    // CashFlow - Conflicts
    CASHFLOW_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access to CashFlow denied"),
    CASHFLOW_BALANCE_MISMATCH(HttpStatus.CONFLICT, "Balance mismatch during attestation"),
    CATEGORY_ALREADY_EXISTS(HttpStatus.CONFLICT, "Category already exists"),
    BUDGETING_ALREADY_EXISTS(HttpStatus.CONFLICT, "Budgeting already exists"),
    CATEGORY_UNARCHIVE_CONFLICT(HttpStatus.CONFLICT, "Cannot unarchive - active category exists"),

    // CashFlow - Invalid State
    CASHFLOW_INVALID_STATE(HttpStatus.BAD_REQUEST, "Operation not allowed in current state"),
    CASHFLOW_OPERATION_NOT_ALLOWED_IN_SETUP(HttpStatus.BAD_REQUEST, "Operation not allowed in SETUP mode"),
    CASHFLOW_IMPORT_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "Import only allowed in SETUP mode"),
    CASHFLOW_ATTESTATION_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "Attestation only allowed in SETUP mode"),
    CASHFLOW_ROLLBACK_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "Rollback only allowed in SETUP mode"),
    CASHFLOW_CLOSED(HttpStatus.BAD_REQUEST, "CashFlow is closed and read-only"),
    IMPORT_TO_FORECASTED_MONTH_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "Cannot import to FORECASTED month"),
    CASHCHANGE_NOT_PENDING(HttpStatus.BAD_REQUEST, "CashChange is not in PENDING status"),

    // CashFlow - Date Validation
    PAID_DATE_IN_FUTURE(HttpStatus.BAD_REQUEST, "Paid date cannot be in the future"),
    PAID_DATE_OUTSIDE_ACTIVE_PERIOD(HttpStatus.BAD_REQUEST, "Paid date must be in active period"),
    START_PERIOD_IN_FUTURE(HttpStatus.BAD_REQUEST, "Start period cannot be in the future"),
    IMPORT_DATE_IN_FUTURE(HttpStatus.BAD_REQUEST, "Import date cannot be in the future"),
    IMPORT_DATE_BEFORE_START(HttpStatus.BAD_REQUEST, "Import date before start period"),
    IMPORT_DATE_OUTSIDE_SETUP_PERIOD(HttpStatus.BAD_REQUEST, "Import date outside setup period"),
    DUE_DATE_OUTSIDE_ALLOWED_RANGE(HttpStatus.BAD_REQUEST, "Due date outside allowed range"),

    // CashFlow - Categories
    CATEGORY_IS_ARCHIVED(HttpStatus.BAD_REQUEST, "Category is archived"),
    CANNOT_ARCHIVE_SYSTEM_CATEGORY(HttpStatus.BAD_REQUEST, "Cannot archive system category"),

    // CashFlow - Bank Account Validation
    INVALID_BANK_ACCOUNT(HttpStatus.BAD_REQUEST, "Invalid bank account data"),

    // Bank Data Ingestion
    INGESTION_STAGING_NOT_FOUND(HttpStatus.NOT_FOUND, "Staging session not found"),
    INGESTION_UNMAPPED_CATEGORIES(HttpStatus.BAD_REQUEST, "Unmapped categories exist"),
    INGESTION_INVALID_CSV(HttpStatus.BAD_REQUEST, "Invalid CSV format"),

    // Internal
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found");

    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
