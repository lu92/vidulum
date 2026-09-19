package com.multi.vidulum.okx.exchange_connection;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only application for the persistence side of the OKX module.
 *
 * <p>Separate from {@code OkxTestApplication} on purpose. That one narrows the scan to keep
 * MongoDB out of the quotation tests — those need Kafka only, and a second container would slow
 * every run for nothing. This one is the mirror image: Mongo, no web layer, no Kafka.
 *
 * <p>It also lives in this package rather than {@code com.multi.vidulum.okx} for a concrete
 * reason: a component scan of the parent package would find {@code OkxTestApplication} and apply
 * its {@code @ComponentScan} too, dragging {@code KafkaTopicConfig} in and failing on a missing
 * broker address.
 */
@SpringBootApplication(scanBasePackages = "com.multi.vidulum.okx.exchange_connection")
public class OkxPersistenceTestApplication {
}
