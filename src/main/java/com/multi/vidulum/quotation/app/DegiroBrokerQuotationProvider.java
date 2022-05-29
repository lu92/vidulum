package com.multi.vidulum.quotation.app;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.quotation.domain.BrokerQuotationProvider;

public class DegiroBrokerQuotationProvider extends BrokerQuotationProvider {
    public DegiroBrokerQuotationProvider() {
        super(Broker.of("DEGIRO"));
    }
}
