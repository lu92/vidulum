package com.multi.vidulum.trading.app;

import com.multi.vidulum.trading.domain.OrderFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TradingAppConfig {

    @Bean
    public OrderFactory orderFactory() {
        return new OrderFactory();
    }
}
