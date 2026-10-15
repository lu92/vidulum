package com.multi.vidulum.quotation;

import com.multi.vidulum.quotation.api.BrokerQuotationProvider;
import com.multi.vidulum.quotation.app.BinanceBrokerQuotationProvider;
import com.multi.vidulum.quotation.app.DegiroBrokerQuotationProvider;
import com.multi.vidulum.quotation.app.PMBrokerQuotationProvider;
import com.multi.vidulum.quotation.domain.QuotationService;
import com.multi.vidulum.quotation.domain.fx.FxRates;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Everything this module registers with the application — the equivalent of NPay's
 * {@code AddQuotationModule()}.
 *
 * <p>Split out of {@code KafkaTopicConfig}, which also held the application's only
 * {@link org.springframework.kafka.core.KafkaAdmin} and the whole {@code user_created} wiring.
 * Three unrelated concerns in one file is survivable while everything is one artifact and a
 * blocker the moment it is not.
 */
@Configuration
public class QuotationConfiguration {

    /**
     * Collects every provider on the classpath, including ones declared by other modules —
     * {@code OkxQuotationConfiguration} publishes OKX's this way, which is what keeps wealth free
     * of any reference to OKX.
     */
    @Bean
    public QuotationService quotationService(
            List<BrokerQuotationProvider> brokerQuotationProviders,
            FxRates fxRates) {
        QuotationService quotationService = new QuotationService(fxRates);
        brokerQuotationProviders.forEach(quotationService::registerBroker);
        return quotationService;
    }

    @Bean
    public BinanceBrokerQuotationProvider binanceBrokerQuotationProvider() {
        return new BinanceBrokerQuotationProvider();
    }

    @Bean
    public PMBrokerQuotationProvider pmBrokerQuotationProvider() {
        return new PMBrokerQuotationProvider();
    }

    @Bean
    public DegiroBrokerQuotationProvider degiroBrokerQuotationProvider() {
        return new DegiroBrokerQuotationProvider();
    }
}
