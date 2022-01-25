package com.multi.vidulum;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

@TestConfiguration
public class FixedClockConfig {

    @Primary
    @Bean
    public Clock clock() {
        return Clock.fixed(
                Instant.parse("2020-01-01T10:00:00Z"),
                ZoneOffset.UTC);
    }
}
