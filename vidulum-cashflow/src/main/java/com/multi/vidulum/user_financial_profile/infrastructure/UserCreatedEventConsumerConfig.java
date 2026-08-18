package com.multi.vidulum.user_financial_profile.infrastructure;

import com.multi.vidulum.common.events.UserCreatedEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;

/**
 * Kafka consumer configuration for user_created topic.
 * Needed by {@link com.multi.vidulum.user_financial_profile.app.UserFinancialProfileUserCreatedListener}.
 *
 * <p>The producer side (topic creation, KafkaTemplate) remains in vidulum-app
 * where UserCreatedEventEmitter lives.</p>
 */
@Configuration
public class UserCreatedEventConsumerConfig {

    @Value(value = "${spring.kafka.bootstrap-servers}")
    private String bootstrapAddress;

    @Bean
    @ConditionalOnMissingBean(name = "userCreatedConsumerFactory")
    public ConsumerFactory<String, UserCreatedEvent> userCreatedConsumerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        return new DefaultKafkaConsumerFactory<>(
                configProps,
                new StringDeserializer(),
                new JsonDeserializer<>(UserCreatedEvent.class));
    }

    @Bean
    @ConditionalOnMissingBean(name = "userCreatedContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, UserCreatedEvent> userCreatedContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, UserCreatedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(userCreatedConsumerFactory());
        return factory;
    }
}
