package com.multi.vidulum.exchange_connection;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only application for this module: MongoDB, no web layer, no Kafka. The module's whole
 * dependency surface is shared-kernel plus Spring Data Mongo, so the context is small enough to
 * start in a couple of seconds.
 */
@SpringBootApplication(scanBasePackages = "com.multi.vidulum.exchange_connection")
public class ExchangeTestApplication {
}
