package com.multi.vidulum.user_financial_profile.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * An IBAN this module was given does not parse.
 *
 * <p>Its own failure, because its own endpoint raised it. Cashflow has
 * {@code InvalidBankAccountNumberException} for the same shape of mistake at its own door, and the
 * two are not a duplicated concept - they are two modules each answering for what they accept.
 * Carries {@link ErrorCode#INVALID_BANK_ACCOUNT}, the code the previous behaviour produced, so an
 * endpoint behind this answers exactly as it did.
 */
public class InvalidOwnedAccountNumberException extends BusinessException {

    private final String rawIban;

    public InvalidOwnedAccountNumberException(String reason, String rawIban) {
        super("Invalid bank account number [%s]: %s".formatted(rawIban, reason));
        this.rawIban = rawIban;
    }

    public String getRawIban() {
        return rawIban;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.INVALID_BANK_ACCOUNT;
    }
}
