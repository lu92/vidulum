package com.multi.vidulum.user_financial_profile.infrastructure;

import com.multi.vidulum.common.events.UserFinancialProfileUnifiedEvent;
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
public class UserFinancialProfileKafkaConfig {

    @Value(value = "${spring.kafka.bootstrap-servers}")
    private String bootstrapAddress;

    @Bean
    public NewTopic userFinancialProfileTopic() {
        return new NewTopic("user_financial_profile", 1, (short) 1);
    }

    @Bean
    public ProducerFactory<String, UserFinancialProfileUnifiedEvent> userFinancialProfileProducerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, UserFinancialProfileUnifiedEvent> userFinancialProfileKafkaTemplate() {
        return new KafkaTemplate<>(userFinancialProfileProducerFactory());
    }

    @Bean
    public ConsumerFactory<String, UserFinancialProfileUnifiedEvent> userFinancialProfileConsumerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        return new DefaultKafkaConsumerFactory<>(
                configProps,
                new StringDeserializer(),
                new JsonDeserializer<>(UserFinancialProfileUnifiedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, UserFinancialProfileUnifiedEvent> userFinancialProfileContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, UserFinancialProfileUnifiedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(userFinancialProfileConsumerFactory());
        return factory;
    }
}
