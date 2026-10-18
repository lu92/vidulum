package com.multi.vidulum.recurring_rules.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration for the Recurring Rules module.
 */
@Configuration
public class RecurringRulesConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
