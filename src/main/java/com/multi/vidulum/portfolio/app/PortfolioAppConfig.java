package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PortfolioAppConfig {

    @Bean
    public PortfolioFactory portfolioFactory() {
        return new PortfolioFactory();
    }
}
