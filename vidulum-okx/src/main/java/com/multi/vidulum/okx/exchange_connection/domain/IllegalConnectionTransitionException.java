package com.multi.vidulum.okx.exchange_connection.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

public class IllegalConnectionTransitionException extends BusinessException {

    public IllegalConnectionTransitionException(
            ExchangeConnectionId id, ConnectionStatus current, String operation) {
        super("Cannot " + operation + " connection [" + (id != null ? id.getId() : "?")
                + "] while it is " + current);
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.EXCHANGE_CONNECTION_INVALID_TRANSITION;
    }
}
