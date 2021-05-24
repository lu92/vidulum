package com.multi.vidulum.quotation.app;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.quotation.domain.BrokerQuotationProvider;

public class PMBrokerQuotationProvider extends BrokerQuotationProvider {
    public PMBrokerQuotationProvider() {
        super(Broker.of("PM"));
    }
}
