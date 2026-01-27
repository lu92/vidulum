package com.multi.vidulum.security.config;

import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.common.error.ErrorCode;
import com.multi.vidulum.common.error.FieldError;
import com.multi.vidulum.security.auth.EmailAlreadyTakenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import org.springframework.core.annotation.Order;

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        ApiError error = ApiError.of(ErrorCode.INTERNAL_ERROR);
        return ResponseEntity.status(error.httpStatus()).body(error);
    }
}
