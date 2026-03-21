package com.multi.vidulum.security.config;

import com.multi.vidulum.bank_data_adapter.domain.exceptions.*;
import com.multi.vidulum.bank_data_ingestion.app.CsvParserService;
import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.cashflow.app.commands.archive.CannotArchiveSystemCategoryException;
import com.multi.vidulum.cashflow.app.commands.archive.CategoryNotFoundException;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.CannotMoveSystemCategoryException;
import com.multi.vidulum.cashflow.domain.CircularCategoryDependencyException;
import com.multi.vidulum.cashflow.domain.CategoryMoveToSameParentException;
import com.multi.vidulum.cashflow.domain.InvalidCashChangeIdFormatException;
import com.multi.vidulum.cashflow.domain.InvalidCashFlowIdFormatException;
import com.multi.vidulum.common.InvalidUserIdFormatException;
import com.multi.vidulum.cashflow.domain.CashFlowNameAlreadyExistsException;
import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.common.error.ErrorCode;
import com.multi.vidulum.common.error.FieldError;
import com.multi.vidulum.security.auth.*;
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

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ApiError> handleInvalidToken(InvalidTokenException ex) {
        log.debug("Invalid token: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.AUTH_TOKEN_INVALID, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(TokenNotFoundException.class)
    public ResponseEntity<ApiError> handleTokenNotFound(TokenNotFoundException ex) {
        log.debug("Token not found: {}", ex.getTokenPrefix());
        ApiError error = ApiError.of(ErrorCode.AUTH_TOKEN_NOT_FOUND);
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(TokenAlreadyRevokedException.class)
    public ResponseEntity<ApiError> handleTokenRevoked(TokenAlreadyRevokedException ex) {
        log.debug("Token already revoked: {}", ex.getTokenId());
        ApiError error = ApiError.of(ErrorCode.AUTH_TOKEN_REVOKED);
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(RefreshTokenExpiredException.class)
    public ResponseEntity<ApiError> handleRefreshTokenExpired(RefreshTokenExpiredException ex) {
        log.debug("Refresh token expired");
        ApiError error = ApiError.of(ErrorCode.AUTH_REFRESH_TOKEN_EXPIRED);
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(MissingAuthorizationHeaderException.class)
    public ResponseEntity<ApiError> handleMissingAuthHeader(MissingAuthorizationHeaderException ex) {
        log.debug("Missing authorization header: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.AUTH_MISSING_TOKEN);
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleJsonParse(HttpMessageNotReadableException ex) {
        log.error("JSON parse error: {}", ex.getMessage(), ex);
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

    @ExceptionHandler(InvalidCashFlowIdFormatException.class)
    public ResponseEntity<ApiError> handleInvalidCashFlowIdFormat(InvalidCashFlowIdFormatException ex) {
        log.debug("Invalid CashFlow ID format: {}", ex.getProvidedId());
        ApiError error = ApiError.of(ErrorCode.INVALID_CASHFLOW_ID_FORMAT, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(InvalidCashChangeIdFormatException.class)
    public ResponseEntity<ApiError> handleInvalidCashChangeIdFormat(InvalidCashChangeIdFormatException ex) {
        log.debug("Invalid CashChange ID format: {}", ex.getProvidedId());
        ApiError error = ApiError.of(ErrorCode.INVALID_CASHCHANGE_ID_FORMAT, ex.getMessage());
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

    // ============ CashFlow - Bank Account Validation (400) ============

    @ExceptionHandler(InvalidBankAccountNumberException.class)
    public ResponseEntity<ApiError> handleInvalidBankAccount(InvalidBankAccountNumberException ex) {
        log.debug("Invalid bank account: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.INVALID_BANK_ACCOUNT, ex.getMessage());
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

    // ============ CashFlow - Move Category Operations (400) ============

    @ExceptionHandler(CannotMoveSystemCategoryException.class)
    public ResponseEntity<ApiError> handleCannotMoveSystemCategory(CannotMoveSystemCategoryException ex) {
        log.debug("Cannot move system category: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CANNOT_MOVE_SYSTEM_CATEGORY, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(CircularCategoryDependencyException.class)
    public ResponseEntity<ApiError> handleCircularCategoryDependency(CircularCategoryDependencyException ex) {
        log.debug("Circular category dependency: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CATEGORY_CIRCULAR_DEPENDENCY, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(CategoryMoveToSameParentException.class)
    public ResponseEntity<ApiError> handleCategoryMoveToSameParent(CategoryMoveToSameParentException ex) {
        log.debug("Category move to same parent: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CATEGORY_MOVE_TO_SAME_PARENT, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(CannotChangeCategoryTypeException.class)
    public ResponseEntity<ApiError> handleCannotChangeCategoryType(CannotChangeCategoryTypeException ex) {
        log.debug("Cannot change category type: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CANNOT_CHANGE_CATEGORY_TYPE, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ CashFlow - Rollover (400) ============

    @ExceptionHandler(RolloverNotAllowedException.class)
    public ResponseEntity<ApiError> handleRolloverNotAllowed(RolloverNotAllowedException ex) {
        log.debug("Rollover not allowed for CashFlow [{}]: {}", ex.getCashFlowId().id(), ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.CASHFLOW_ROLLOVER_NOT_ALLOWED, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ Bank Data Ingestion - Resources Not Found (404) ============

    @ExceptionHandler(StagingSessionNotFoundException.class)
    public ResponseEntity<ApiError> handleStagingSessionNotFound(StagingSessionNotFoundException ex) {
        log.debug("Staging session not found: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.INGESTION_STAGING_NOT_FOUND, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(ImportJobNotFoundException.class)
    public ResponseEntity<ApiError> handleImportJobNotFound(ImportJobNotFoundException ex) {
        log.debug("Import job not found: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.INGESTION_IMPORT_JOB_NOT_FOUND, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(CategoryMappingNotFoundException.class)
    public ResponseEntity<ApiError> handleCategoryMappingNotFound(CategoryMappingNotFoundException ex) {
        log.debug("Category mapping not found: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.INGESTION_MAPPING_NOT_FOUND, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ Bank Data Ingestion - Validation Errors (400) ============

    @ExceptionHandler(UnmappedCategoriesException.class)
    public ResponseEntity<ApiError> handleUnmappedCategories(UnmappedCategoriesException ex) {
        log.debug("Unmapped categories: {}", ex.getUnmappedCategories());
        ApiError error = ApiError.of(ErrorCode.INGESTION_UNMAPPED_CATEGORIES, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(StagingSessionNotReadyException.class)
    public ResponseEntity<ApiError> handleStagingSessionNotReady(StagingSessionNotReadyException ex) {
        log.debug("Staging session not ready [{}]: {}", ex.getStagingSessionId().id(), ex.getReason());
        ApiError error = ApiError.of(ErrorCode.INGESTION_SESSION_NOT_READY, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(ImportJobNotCompletedException.class)
    public ResponseEntity<ApiError> handleImportJobNotCompleted(ImportJobNotCompletedException ex) {
        log.debug("Import job not completed [{}]: status={}", ex.getJobId().id(), ex.getCurrentStatus());
        ApiError error = ApiError.of(ErrorCode.INGESTION_JOB_NOT_COMPLETED, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(CsvParserService.CsvParseException.class)
    public ResponseEntity<ApiError> handleCsvParseException(CsvParserService.CsvParseException ex) {
        log.debug("CSV parse error: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.INGESTION_INVALID_CSV, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(RollbackNotAllowedException.class)
    public ResponseEntity<ApiError> handleIngestionRollbackNotAllowed(RollbackNotAllowedException ex) {
        log.debug("Rollback not allowed [{}]: {}", ex.getJobId().id(), ex.getReason());
        ApiError error = ApiError.of(ErrorCode.INGESTION_ROLLBACK_NOT_ALLOWED, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ Bank Data Ingestion - Conflicts (409) ============

    @ExceptionHandler(ImportJobAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleImportJobAlreadyExists(ImportJobAlreadyExistsException ex) {
        log.debug("Import job already exists for staging session: {}", ex.getStagingSessionId().id());
        ApiError error = ApiError.of(ErrorCode.INGESTION_JOB_ALREADY_EXISTS, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ Recurring Rules - ID Format Validation (400) ============

    @ExceptionHandler(com.multi.vidulum.recurring_rules.domain.InvalidRecurringRuleIdFormatException.class)
    public ResponseEntity<ApiError> handleInvalidRecurringRuleIdFormat(com.multi.vidulum.recurring_rules.domain.InvalidRecurringRuleIdFormatException ex) {
        log.debug("Invalid RecurringRule ID format: {}", ex.getProvidedId());
        ApiError error = ApiError.of(ErrorCode.INVALID_RECURRING_RULE_ID_FORMAT, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ Recurring Rules - Resources Not Found (404) ============

    @ExceptionHandler(com.multi.vidulum.recurring_rules.domain.exceptions.RuleNotFoundException.class)
    public ResponseEntity<ApiError> handleRecurringRuleNotFound(com.multi.vidulum.recurring_rules.domain.exceptions.RuleNotFoundException ex) {
        log.debug("Recurring rule not found: {}", ex.getRuleId().id());
        ApiError error = ApiError.of(ErrorCode.RECURRING_RULE_NOT_FOUND, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(com.multi.vidulum.recurring_rules.domain.exceptions.AmountChangeNotFoundException.class)
    public ResponseEntity<ApiError> handleAmountChangeNotFound(com.multi.vidulum.recurring_rules.domain.exceptions.AmountChangeNotFoundException ex) {
        log.debug("Amount change not found: {} in rule {}", ex.getAmountChangeId().id(), ex.getRuleId().id());
        ApiError error = ApiError.of(ErrorCode.AMOUNT_CHANGE_NOT_FOUND, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(com.multi.vidulum.recurring_rules.domain.exceptions.CashFlowNotFoundException.class)
    public ResponseEntity<ApiError> handleRecurringRuleCashFlowNotFound(com.multi.vidulum.recurring_rules.domain.exceptions.CashFlowNotFoundException ex) {
        log.debug("CashFlow not found for recurring rule: {}", ex.getCashFlowId().id());
        ApiError error = ApiError.of(ErrorCode.CASHFLOW_NOT_FOUND, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ Recurring Rules - Validation Errors (400) ============

    @ExceptionHandler(com.multi.vidulum.recurring_rules.domain.exceptions.InvalidDateRangeException.class)
    public ResponseEntity<ApiError> handleRecurringRuleInvalidDateRange(com.multi.vidulum.recurring_rules.domain.exceptions.InvalidDateRangeException ex) {
        log.debug("Invalid date range: {} to {}", ex.getStartDate(), ex.getEndDate());
        ApiError error = ApiError.of(ErrorCode.RECURRING_RULE_INVALID_DATE_RANGE, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(com.multi.vidulum.recurring_rules.domain.exceptions.CategoryNotFoundException.class)
    public ResponseEntity<ApiError> handleRecurringRuleCategoryNotFound(com.multi.vidulum.recurring_rules.domain.exceptions.CategoryNotFoundException ex) {
        log.debug("Category not found in CashFlow {}: {}", ex.getCashFlowId().id(), ex.getCategoryName().name());
        ApiError error = ApiError.of(ErrorCode.RECURRING_RULE_CATEGORY_NOT_FOUND, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(com.multi.vidulum.recurring_rules.domain.exceptions.InvalidRecurrencePatternException.class)
    public ResponseEntity<ApiError> handleInvalidRecurrencePattern(com.multi.vidulum.recurring_rules.domain.exceptions.InvalidRecurrencePatternException ex) {
        log.debug("Invalid recurrence pattern [{}]: {}", ex.getPatternType(), ex.getReason());
        ApiError error = ApiError.of(ErrorCode.RECURRING_RULE_INVALID_PATTERN, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ Recurring Rules - Invalid State (409) ============

    @ExceptionHandler(com.multi.vidulum.recurring_rules.domain.exceptions.InvalidRuleStateException.class)
    public ResponseEntity<ApiError> handleInvalidRuleState(com.multi.vidulum.recurring_rules.domain.exceptions.InvalidRuleStateException ex) {
        log.debug("Invalid rule state: rule {} in status {} cannot perform {}",
                ex.getRuleId().id(), ex.getCurrentStatus(), ex.getOperation());
        ApiError error = ApiError.of(ErrorCode.RECURRING_RULE_INVALID_STATE, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ Recurring Rules - Dashboard/Query Parameter Validation (400) ============

    @ExceptionHandler(com.multi.vidulum.recurring_rules.domain.exceptions.InvalidDashboardParameterException.class)
    public ResponseEntity<ApiError> handleInvalidDashboardParameter(com.multi.vidulum.recurring_rules.domain.exceptions.InvalidDashboardParameterException ex) {
        log.debug("Invalid dashboard parameter [{}]: value={}, allowed range=[{}-{}]",
                ex.getParameterName(), ex.getProvidedValue(), ex.getMinValue(), ex.getMaxValue());
        ApiError error = ApiError.of(ErrorCode.RECURRING_RULE_INVALID_PARAMETER, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingServletRequestParameter(org.springframework.web.bind.MissingServletRequestParameterException ex) {
        log.debug("Missing required parameter: {}", ex.getParameterName());
        String message = String.format("Required parameter '%s' is missing", ex.getParameterName());
        ApiError error = ApiError.of(ErrorCode.RECURRING_RULE_MISSING_CASHFLOW_ID, message);
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ Recurring Rules - Communication Errors (503) ============

    @ExceptionHandler(com.multi.vidulum.recurring_rules.domain.exceptions.CashFlowCommunicationException.class)
    public ResponseEntity<ApiError> handleRecurringRuleCashFlowCommunication(com.multi.vidulum.recurring_rules.domain.exceptions.CashFlowCommunicationException ex) {
        log.error("CashFlow communication error for recurring rule: {}", ex.getMessage(), ex);
        ApiError error = ApiError.of(ErrorCode.RECURRING_RULE_CASHFLOW_COMMUNICATION_ERROR, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ AI Bank CSV Adapter - Validation Errors (400) ============

    @ExceptionHandler(EmptyFileException.class)
    public ResponseEntity<ApiError> handleEmptyFile(EmptyFileException ex) {
        log.debug("Empty file uploaded");
        ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_EMPTY_FILE, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(FileTooLargeException.class)
    public ResponseEntity<ApiError> handleFileTooLarge(FileTooLargeException ex) {
        log.debug("File too large: {} bytes, max: {} bytes", ex.getFileSize(), ex.getMaxSize());
        ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_FILE_TOO_LARGE, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(InvalidFileTypeException.class)
    public ResponseEntity<ApiError> handleInvalidFileType(InvalidFileTypeException ex) {
        log.debug("Invalid file type: {}", ex.getDetectedType());
        ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_INVALID_FILE_TYPE, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(UnrecognizedCsvFormatException.class)
    public ResponseEntity<ApiError> handleUnrecognizedFormat(UnrecognizedCsvFormatException ex) {
        log.warn("Unrecognized CSV format. Headers: {}", ex.getDetectedHeaders());
        ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_UNRECOGNIZED_FORMAT, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(InvalidTransformationIdFormatException.class)
    public ResponseEntity<ApiError> handleInvalidTransformationId(InvalidTransformationIdFormatException ex) {
        log.debug("Invalid transformation ID format: {}", ex.getProvidedId());
        ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_INVALID_TRANSFORMATION_ID, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ AI Bank CSV Adapter - Not Found (404) ============

    @ExceptionHandler(TransformationNotFoundException.class)
    public ResponseEntity<ApiError> handleTransformationNotFound(TransformationNotFoundException ex) {
        log.debug("Transformation not found: {}", ex.getTransformationId());
        ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_TRANSFORMATION_NOT_FOUND, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ AI Bank CSV Adapter - Conflicts (409) ============

    @ExceptionHandler(DuplicateFileException.class)
    public ResponseEntity<ApiError> handleDuplicateFile(DuplicateFileException ex) {
        log.debug("Duplicate file detected. Hash: {}, existing: {}",
            ex.getFileHash(), ex.getExistingTransformationId());
        ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_DUPLICATE_FILE, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(TransformationAlreadyImportedException.class)
    public ResponseEntity<ApiError> handleAlreadyImported(TransformationAlreadyImportedException ex) {
        log.debug("Transformation already imported: {}", ex.getTransformationId());
        ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_ALREADY_IMPORTED, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ AI Bank CSV Adapter - External Service Errors ============

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ApiError> handleAiServiceError(AiServiceException ex) {
        log.error("AI service error [{}]: {} (retries: {})",
            ex.getAiErrorCode(), ex.getAiErrorMessage(), ex.getRetryCount());
        ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_AI_SERVICE_ERROR, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(AiServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleAiUnavailable(AiServiceUnavailableException ex) {
        log.error("AI service unavailable", ex);
        ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_AI_SERVICE_UNAVAILABLE, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(AiRateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimitExceeded(AiRateLimitExceededException ex) {
        log.warn("AI rate limit exceeded. Retry after: {} seconds", ex.getRetryAfterSeconds());
        ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_RATE_LIMIT_EXCEEDED, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(IngestionServiceException.class)
    public ResponseEntity<ApiError> handleIngestionServiceError(IngestionServiceException ex) {
        log.error("Ingestion service error: {}", ex.getMessage());
        ApiError error = ApiError.of(ErrorCode.AI_ADAPTER_INGESTION_SERVICE_ERROR, ex.getMessage());
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
