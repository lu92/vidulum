package com.multi.vidulum.quotation.domain;

import com.multi.vidulum.common.Broker;

public class BrokerNotFoundException extends RuntimeException {

    public BrokerNotFoundException(Broker broker) {
        super(String.format("Broker [%s] not found!", broker.getId()));
    }
}
