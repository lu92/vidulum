package com.multi.vidulum.exchange_connection.domain;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

import java.util.Collection;

/**
 * No {@link ExchangeAdapter} is registered for the requested broker — either the name is wrong or
 * that exchange's module is not on the classpath.
 */
public class ExchangeNotSupportedException extends BusinessException {

    public ExchangeNotSupportedException(Broker broker, Collection<String> supported) {
        super("Exchange [" + broker.getId() + "] is not supported; available: "
                + String.join(", ", supported));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.EXCHANGE_NOT_SUPPORTED;
    }
}
