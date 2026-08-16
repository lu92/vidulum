package com.multi.vidulum.security.config;

import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;
import com.multi.vidulum.common.error.FieldError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.List;

@Slf4j
@ControllerAdvice
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ErrorHttpHandler {

    // ============ Business Exceptions (single handler for all domain errors) ============

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        log.debug("{}: {}", errorCode.name(), ex.getMessage());
        ApiError error = ApiError.of(errorCode, ex.getMessage());
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    // ============ Spring Framework Exceptions ============

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
        log.error("JSON parse error: {}", ex.getMessage(), ex);
        ApiError error = ApiError.of(ErrorCode.VALIDATION_INVALID_JSON);
        return ResponseEntity.status(error.httpStatus()).body(error);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingServletRequestParameter(MissingServletRequestParameterException ex) {
        log.debug("Missing required parameter: {}", ex.getParameterName());
        String message = String.format("Required parameter '%s' is missing", ex.getParameterName());
        ApiError error = ApiError.of(ErrorCode.RECURRING_RULE_MISSING_CASHFLOW_ID, message);
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
