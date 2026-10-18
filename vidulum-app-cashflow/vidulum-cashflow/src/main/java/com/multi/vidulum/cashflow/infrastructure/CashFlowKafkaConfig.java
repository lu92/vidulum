package com.multi.vidulum.cashflow.infrastructure;

import com.multi.vidulum.common.events.CashFlowUnifiedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

@Configuration
public class CashFlowKafkaConfig {

    @Value(value = "${spring.kafka.bootstrap-servers}")
    private String bootstrapAddress;

    @Bean
    public NewTopic cashFlowTopic() {
        return new NewTopic("cash_flow", 1, (short) 1);
    }

    @Bean
    public ProducerFactory<String, CashFlowUnifiedEvent> cashFlowProducerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, CashFlowUnifiedEvent> cashFlowUnifiedEventKafkaTemplate() {
        return new KafkaTemplate<>(cashFlowProducerFactory());
    }

    @Bean
    public ConsumerFactory<String, CashFlowUnifiedEvent> cashFlowUnifiedEventConsumerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        return new DefaultKafkaConsumerFactory<>(
                configProps,
                new StringDeserializer(),
                new JsonDeserializer<>(CashFlowUnifiedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CashFlowUnifiedEvent> cashFlowUnifiedEventContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, CashFlowUnifiedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cashFlowUnifiedEventConsumerFactory());
        return factory;
    }
}
