package com.multi.vidulum.okx.exchange_connection.domain;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * The natural key already exists. Raised instead of silently creating a second portfolio over
 * the same assets — and the message points at reconnecting, which is what the caller usually
 * wants when the account is already known.
 */
public class ExchangeAccountAlreadyConnectedException extends BusinessException {

    public ExchangeAccountAlreadyConnectedException(
            UserId userId, Exchange exchange, ExchangeEnvironment environment, String accountUid) {
        super("Account [" + accountUid + "] on " + exchange + " " + environment
                + " is already connected for user [" + userId.getId() + "]");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.EXCHANGE_ACCOUNT_ALREADY_CONNECTED;
    }
}
