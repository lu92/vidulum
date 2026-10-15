package com.multi.vidulum.okx;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the OKX quotation provider as a bean.
 *
 * <p>The registration itself happens in {@code QuotationConfiguration#quotationService}, which injects
 * {@code List<BrokerQuotationProvider>} and registers every bean it finds. Declaring the bean here
 * rather than there keeps {@code vidulum-quotation} free of any reference to OKX - a bean method
 * in quotation would make quotation depend on this module, which already depends on quotation.
 *
 * <p>Picked up by component scanning because {@code @SpringBootApplication} sits on
 * {@code com.multi.vidulum}; the only requirement is that {@code vidulum-app} depends on this
 * module so the jar reaches the classpath.
 */
@Configuration
public class OkxQuotationConfiguration {

    @Bean
    public OkxBrokerQuotationProvider okxBrokerQuotationProvider() {
        return new OkxBrokerQuotationProvider();
    }
}
