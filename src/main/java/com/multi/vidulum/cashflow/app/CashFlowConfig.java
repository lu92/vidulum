package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.domain.CashChangeFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CashFlowConfig {

    @Bean
    public CashChangeFactory cashChangeFactory() {
        return new CashChangeFactory();
    }
}
