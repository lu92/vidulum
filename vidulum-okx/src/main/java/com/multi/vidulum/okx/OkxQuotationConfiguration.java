package com.multi.vidulum.okx;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the OKX quotation provider as a bean.
 *
 * <p>The registration itself happens in {@code KafkaTopicConfig#quotationService}, which injects
 * {@code List<BrokerQuotationProvider>} and registers every bean it finds. Declaring the bean here
 * rather than there keeps {@code vidulum-wealth} free of any reference to OKX - a bean method in
 * wealth would make wealth depend on this module, which already depends on wealth.
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
