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

    // CashFlow
    CASHFLOW_NOT_FOUND(HttpStatus.NOT_FOUND, "CashFlow not found"),
    CASHFLOW_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access to CashFlow denied"),
    CASHFLOW_INVALID_STATE(HttpStatus.BAD_REQUEST, "Operation not allowed in current state"),
    CASHFLOW_BALANCE_MISMATCH(HttpStatus.CONFLICT, "Balance mismatch during attestation"),

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
