package com.multi.vidulum.security.config;

import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A losing writer has to learn about it as a 409, not a 500.
 *
 * <p>The distinction is the whole point: 409 says "read it again and retry", which is something the
 * caller can act on, while 500 says "we broke" and invites nobody to do anything. Without this
 * handler the exception would fall through to the generic one - and before the aggregates carried a
 * {@code @Version}, there was no exception at all and the write silently won (task F35).
 */
class ErrorHttpHandlerConcurrencyTest {

    private final ErrorHttpHandler handler = new ErrorHttpHandler();

    @Test
    void aConcurrentModificationAnswers409WithACodeTheCallerCanBranchOn() {
        ResponseEntity<ApiError> response = handler.handleConcurrentModification(
                new OptimisticLockingFailureException("version 3 expected, found 4"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION.name());
        assertThat(response.getBody().status()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void theMessageDoesNotLeakTheStorageDetailThatCausedIt() {
        ResponseEntity<ApiError> response = handler.handleConcurrentModification(
                new OptimisticLockingFailureException("Cannot save entity CashFlowEntity with version 3"));

        assertThat(response.getBody().message())
                .as("the caller is told what to do, not which document moved on")
                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION.getDefaultMessage())
                .doesNotContain("CashFlowEntity");
    }
}
