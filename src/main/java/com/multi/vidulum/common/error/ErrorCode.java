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
    AUTH_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "Token not found"),
    AUTH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "Token has been revoked"),
    AUTH_REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Refresh token expired"),
    AUTH_MISSING_TOKEN(HttpStatus.BAD_REQUEST, "Authorization header is missing or invalid"),

    // Validation
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),
    VALIDATION_INVALID_JSON(HttpStatus.BAD_REQUEST, "Invalid JSON format"),
    INVALID_USER_ID_FORMAT(HttpStatus.BAD_REQUEST, "Invalid User ID format. Expected: UXXXXXXXX"),
    INVALID_CASHFLOW_ID_FORMAT(HttpStatus.BAD_REQUEST, "Invalid CashFlow ID format. Expected: CFXXXXXXXX"),
    INVALID_CASHCHANGE_ID_FORMAT(HttpStatus.BAD_REQUEST, "Invalid CashChange ID format. Expected: CCXXXXXXXXXX"),

    // CashFlow - Resources
    CASHFLOW_NOT_FOUND(HttpStatus.NOT_FOUND, "CashFlow not found"),
    CASHCHANGE_NOT_FOUND(HttpStatus.NOT_FOUND, "CashChange not found"),
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "Category not found"),
    BUDGETING_NOT_FOUND(HttpStatus.NOT_FOUND, "Budgeting not found"),

    // CashFlow - Conflicts
    CASHFLOW_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access to CashFlow denied"),
    CASHFLOW_BALANCE_MISMATCH(HttpStatus.CONFLICT, "Balance mismatch during attestation"),
    CASHFLOW_NAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "CashFlow with this name already exists"),
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
    CANNOT_MOVE_SYSTEM_CATEGORY(HttpStatus.BAD_REQUEST, "Cannot move system category"),
    CATEGORY_CIRCULAR_DEPENDENCY(HttpStatus.BAD_REQUEST, "Cannot move category to its own descendant"),
    CANNOT_CHANGE_CATEGORY_TYPE(HttpStatus.BAD_REQUEST, "Cannot move category between INFLOW and OUTFLOW"),
    CATEGORY_MOVE_TO_SAME_PARENT(HttpStatus.BAD_REQUEST, "Category is already under this parent"),

    // CashFlow - Bank Account Validation
    INVALID_BANK_ACCOUNT(HttpStatus.BAD_REQUEST, "Invalid bank account data"),

    // Bank Data Ingestion - Resources Not Found
    INGESTION_STAGING_NOT_FOUND(HttpStatus.NOT_FOUND, "Staging session not found"),
    INGESTION_IMPORT_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "Import job not found"),
    INGESTION_MAPPING_NOT_FOUND(HttpStatus.NOT_FOUND, "Category mapping not found"),

    // Bank Data Ingestion - Validation Errors
    INGESTION_UNMAPPED_CATEGORIES(HttpStatus.BAD_REQUEST, "Unmapped categories exist"),
    INGESTION_INVALID_CSV(HttpStatus.BAD_REQUEST, "Invalid CSV format"),
    INGESTION_SESSION_NOT_READY(HttpStatus.BAD_REQUEST, "Staging session not ready for import"),
    INGESTION_JOB_NOT_COMPLETED(HttpStatus.BAD_REQUEST, "Import job not completed"),
    INGESTION_ROLLBACK_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "Rollback not allowed for this import job"),

    // Bank Data Ingestion - Conflicts
    INGESTION_JOB_ALREADY_EXISTS(HttpStatus.CONFLICT, "Import job already exists for this staging session"),

    // CashFlow - Rollover
    CASHFLOW_ROLLOVER_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "Month rollover not allowed"),

    // Recurring Rules - Resources Not Found
    RECURRING_RULE_NOT_FOUND(HttpStatus.NOT_FOUND, "Recurring rule not found"),
    AMOUNT_CHANGE_NOT_FOUND(HttpStatus.NOT_FOUND, "Amount change not found"),

    // Recurring Rules - Validation Errors
    INVALID_RECURRING_RULE_ID_FORMAT(HttpStatus.BAD_REQUEST, "Invalid RecurringRule ID format. Expected: RRXXXXXXXX"),
    RECURRING_RULE_INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "End date must be after start date"),
    RECURRING_RULE_CATEGORY_NOT_FOUND(HttpStatus.BAD_REQUEST, "Category not found in CashFlow"),
    RECURRING_RULE_INVALID_PATTERN(HttpStatus.BAD_REQUEST, "Invalid recurrence pattern configuration"),

    // Recurring Rules - Invalid State
    RECURRING_RULE_INVALID_STATE(HttpStatus.CONFLICT, "Operation not allowed in current rule status"),

    // Recurring Rules - Dashboard/Query Parameters
    RECURRING_RULE_INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "Invalid query parameter value"),
    RECURRING_RULE_MISSING_CASHFLOW_ID(HttpStatus.BAD_REQUEST, "cashFlowId parameter is required"),

    // Recurring Rules - Communication Errors
    RECURRING_RULE_CASHFLOW_COMMUNICATION_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "Failed to communicate with CashFlow service"),

    // Internal
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),

    // ============ AI Bank CSV Adapter ============

    // Validation Errors (400)
    AI_ADAPTER_EMPTY_FILE(HttpStatus.BAD_REQUEST, "Uploaded file is empty"),
    AI_ADAPTER_FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "File exceeds maximum size limit"),
    AI_ADAPTER_INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "Invalid file type. Expected CSV"),
    AI_ADAPTER_UNRECOGNIZED_FORMAT(HttpStatus.BAD_REQUEST, "AI could not recognize bank CSV format"),
    AI_ADAPTER_INVALID_TRANSFORMATION_ID(HttpStatus.BAD_REQUEST, "Invalid transformation ID format"),

    // Resources Not Found (404)
    AI_ADAPTER_TRANSFORMATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Transformation not found"),

    // Conflicts (409)
    AI_ADAPTER_DUPLICATE_FILE(HttpStatus.CONFLICT, "This file has already been processed"),
    AI_ADAPTER_ALREADY_IMPORTED(HttpStatus.CONFLICT, "This transformation has already been imported"),

    // External Service Errors (502/503/429)
    AI_ADAPTER_AI_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "AI service returned an error"),
    AI_ADAPTER_AI_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI service is temporarily unavailable"),
    AI_ADAPTER_RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "AI API rate limit exceeded"),
    AI_ADAPTER_INGESTION_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "Bank data ingestion service returned an error");

    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
