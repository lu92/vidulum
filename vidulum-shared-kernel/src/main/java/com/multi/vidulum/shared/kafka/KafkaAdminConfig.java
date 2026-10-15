package com.multi.vidulum.shared.kafka;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * The one {@link KafkaAdmin} in the application, and the reason every {@code NewTopic} bean
 * anywhere in it actually becomes a topic.
 *
 * <p>Here rather than in a module because it belongs to no module and all of them need it. It used
 * to live in {@code quotation.KafkaTopicConfig}, which made the topics of <b>cashflow</b> —
 * {@code cash_flow}, {@code bank_data_ingestion}, {@code user_financial_profile} — depend on
 * the quotation module - then still part of {@code vidulum-wealth} - being on the classpath.
 * Nothing said so, and nothing would have failed
 * loudly if it stopped being true: a {@code NewTopic} with no admin to create it is silently
 * ignored, and the broker is configured with {@code KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'false'}, so
 * the first symptom would have been a producer timing out against a topic nobody made.
 */
@Configuration
public class KafkaAdminConfig {

    @Value(value = "${spring.kafka.bootstrap-servers}")
    private String bootstrapAddress;

    @Bean
    public KafkaAdmin kafkaAdmin() {
        return new KafkaAdmin(
                Map.of(
                        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class
                ));
    }
}
