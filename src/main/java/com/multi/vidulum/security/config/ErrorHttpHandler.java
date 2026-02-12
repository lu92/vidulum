package com.multi.vidulum.security.config;

import com.multi.vidulum.cashflow.app.commands.archive.CannotArchiveSystemCategoryException;
import com.multi.vidulum.cashflow.app.commands.archive.CategoryNotFoundException;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.InvalidUserIdFormatException;
import com.multi.vidulum.cashflow.domain.CashFlowNameAlreadyExistsException;
import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.common.error.ErrorCode;
import com.multi.vidulum.common.error.FieldError;
import com.multi.vidulum.security.auth.EmailAlreadyTakenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.List;

@Slf4j
@ControllerAdvice
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ErrorHttpHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(e -> FieldError.of(e.getField(), e.getDefaultMessage()))
                .toList();

        ApiError error = ApiError.withFieldErrors(ErrorCode.VALIDATION_ERROR, fieldErrors);
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(EmailAlreadyTakenException.class)
    public ResponseEntity<ApiError> handleEmailTaken(EmailAlreadyTakenException ex) {
        ApiError error = ApiError.of(ErrorCode.AUTH_EMAIL_TAKEN, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex) {
        log.debug("Bad credentials exception", ex);
        ApiError error = ApiError.of(ErrorCode.AUTH_INVALID_CREDENTIALS);
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthenticationException(AuthenticationException ex) {
        log.debug("Authentication exception", ex);
        ApiError error = ApiError.of(ErrorCode.AUTH_INVALID_CREDENTIALS);
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleJsonParse(HttpMessageNotReadableException ex) {
        ApiError error = ApiError.of(ErrorCode.VALIDATION_INVALID_JSON);
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ ID Format Validation (400) ============

    @ExceptionHandler(InvalidUserIdFormatException.class)
    public ResponseEntity<ApiError> handleInvalidUserIdFormat(InvalidUserIdFormatException ex) {
        log.debug("Invalid User ID format: {}", ex.getProvidedId());
        ApiError error = ApiError.of(ErrorCode.INVALID_USER_ID_FORMAT, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(BalanceMismatchException.class)
    public ResponseEntity<ApiError> handleBalanceMismatch(BalanceMismatchException ex) {
        log.warn("Balance mismatch for CashFlow [{}]: {}", ex.getCashFlowId().id(), ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CASHFLOW_BALANCE_MISMATCH, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ CashFlow - Resources Not Found (404) ============

    @ExceptionHandler(CashFlowDoesNotExistsException.class)
    public ResponseEntity<ApiError> handleCashFlowNotFound(CashFlowDoesNotExistsException ex) {
        log.debug("CashFlow not found: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CASHFLOW_NOT_FOUND, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(CashChangeDoesNotExistsException.class)
    public ResponseEntity<ApiError> handleCashChangeNotFound(CashChangeDoesNotExistsException ex) {
        log.debug("CashChange not found: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CASHCHANGE_NOT_FOUND, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(CategoryDoesNotExistsException.class)
    public ResponseEntity<ApiError> handleCategoryDoesNotExist(CategoryDoesNotExistsException ex) {
        log.debug("Category not found: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CATEGORY_NOT_FOUND, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    public ResponseEntity<ApiError> handleCategoryNotFound(CategoryNotFoundException ex) {
        log.debug("Category not found: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CATEGORY_NOT_FOUND, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(BudgetingDoesNotExistsException.class)
    public ResponseEntity<ApiError> handleBudgetingNotFound(BudgetingDoesNotExistsException ex) {
        log.debug("Budgeting not found: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.BUDGETING_NOT_FOUND, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ CashFlow - Conflicts (409) ============

    @ExceptionHandler(CashFlowNameAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleCashFlowNameAlreadyExists(CashFlowNameAlreadyExistsException ex) {
        log.debug("CashFlow name already exists: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CASHFLOW_NAME_ALREADY_EXISTS, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(CategoryAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleCategoryAlreadyExists(CategoryAlreadyExistsException ex) {
        log.debug("Category already exists: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CATEGORY_ALREADY_EXISTS, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(BudgetingAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleBudgetingAlreadyExists(BudgetingAlreadyExistsException ex) {
        log.debug("Budgeting already exists: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.BUDGETING_ALREADY_EXISTS, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(CannotUnarchiveCategoryException.class)
    public ResponseEntity<ApiError> handleCannotUnarchiveCategory(CannotUnarchiveCategoryException ex) {
        log.debug("Cannot unarchive category: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CATEGORY_UNARCHIVE_CONFLICT, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ CashFlow - Invalid State (400) ============

    @ExceptionHandler(OperationNotAllowedInSetupModeException.class)
    public ResponseEntity<ApiError> handleOperationNotAllowedInSetupMode(OperationNotAllowedInSetupModeException ex) {
        log.debug("Operation not allowed in SETUP mode: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CASHFLOW_OPERATION_NOT_ALLOWED_IN_SETUP, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(ImportNotAllowedInNonSetupModeException.class)
    public ResponseEntity<ApiError> handleImportNotAllowed(ImportNotAllowedInNonSetupModeException ex) {
        log.debug("Import not allowed: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CASHFLOW_IMPORT_NOT_ALLOWED, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(ImportToForecastedMonthNotAllowedException.class)
    public ResponseEntity<ApiError> handleImportToForecastedMonth(ImportToForecastedMonthNotAllowedException ex) {
        log.debug("Import to FORECASTED month not allowed: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.IMPORT_TO_FORECASTED_MONTH_NOT_ALLOWED, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(ImportNotAllowedInClosedModeException.class)
    public ResponseEntity<ApiError> handleImportToClosedCashFlow(ImportNotAllowedInClosedModeException ex) {
        log.debug("Import to CLOSED CashFlow not allowed: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CASHFLOW_CLOSED, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(AttestationNotAllowedInNonSetupModeException.class)
    public ResponseEntity<ApiError> handleAttestationNotAllowed(AttestationNotAllowedInNonSetupModeException ex) {
        log.debug("Attestation not allowed: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CASHFLOW_ATTESTATION_NOT_ALLOWED, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(RollbackNotAllowedInNonSetupModeException.class)
    public ResponseEntity<ApiError> handleRollbackNotAllowed(RollbackNotAllowedInNonSetupModeException ex) {
        log.debug("Rollback not allowed: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CASHFLOW_ROLLBACK_NOT_ALLOWED, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(CashChangeIsNotOpenedException.class)
    public ResponseEntity<ApiError> handleCashChangeNotPending(CashChangeIsNotOpenedException ex) {
        log.debug("CashChange not pending: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CASHCHANGE_NOT_PENDING, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ CashFlow - Date Validation (400) ============

    @ExceptionHandler(PaidDateInFutureException.class)
    public ResponseEntity<ApiError> handlePaidDateInFuture(PaidDateInFutureException ex) {
        log.debug("Paid date in future: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.PAID_DATE_IN_FUTURE, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(PaidDateNotInActivePeriodException.class)
    public ResponseEntity<ApiError> handlePaidDateNotInActivePeriod(PaidDateNotInActivePeriodException ex) {
        log.debug("Paid date not in active period: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.PAID_DATE_OUTSIDE_ACTIVE_PERIOD, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(StartPeriodInFutureException.class)
    public ResponseEntity<ApiError> handleStartPeriodInFuture(StartPeriodInFutureException ex) {
        log.debug("Start period in future: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.START_PERIOD_IN_FUTURE, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(ImportDateInFutureException.class)
    public ResponseEntity<ApiError> handleImportDateInFuture(ImportDateInFutureException ex) {
        log.debug("Import date in future: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.IMPORT_DATE_IN_FUTURE, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(ImportDateBeforeStartPeriodException.class)
    public ResponseEntity<ApiError> handleImportDateBeforeStartPeriod(ImportDateBeforeStartPeriodException ex) {
        log.debug("Import date before start period: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.IMPORT_DATE_BEFORE_START, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(ImportDateOutsideSetupPeriodException.class)
    public ResponseEntity<ApiError> handleImportDateOutsideSetupPeriod(ImportDateOutsideSetupPeriodException ex) {
        log.debug("Import date outside setup period: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.IMPORT_DATE_OUTSIDE_SETUP_PERIOD, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(DueDateOutsideAllowedRangeException.class)
    public ResponseEntity<ApiError> handleDueDateOutsideAllowedRange(DueDateOutsideAllowedRangeException ex) {
        log.debug("Due date outside allowed range: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.DUE_DATE_OUTSIDE_ALLOWED_RANGE, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ CashFlow - Category Operations (400) ============

    @ExceptionHandler(CategoryIsArchivedException.class)
    public ResponseEntity<ApiError> handleCategoryIsArchived(CategoryIsArchivedException ex) {
        log.debug("Category is archived: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CATEGORY_IS_ARCHIVED, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(CannotArchiveSystemCategoryException.class)
    public ResponseEntity<ApiError> handleCannotArchiveSystemCategory(CannotArchiveSystemCategoryException ex) {
        log.debug("Cannot archive system category: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CANNOT_ARCHIVE_SYSTEM_CATEGORY, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ General Error Handler ============

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        ApiError error = ApiError.of(ErrorCode.INTERNAL_ERROR);
        return ResponseEntity.status(error.httpStatus()).body(error);
    }
}
