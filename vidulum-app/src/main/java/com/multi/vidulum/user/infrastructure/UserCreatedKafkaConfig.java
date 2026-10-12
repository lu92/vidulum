package com.multi.vidulum.user.infrastructure;

import com.multi.vidulum.common.events.UserCreatedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * The publishing side of {@code user_created}, next to {@link UserCreatedEventEmitter}, which is
 * the only thing that publishes it.
 *
 * <p>Where cashflow's {@code UserCreatedEventConsumerConfig} has always said it was — its javadoc
 * reads "the producer side (topic creation, KafkaTemplate) remains in vidulum-app" — while the
 * beans themselves sat in {@code quotation.KafkaTopicConfig} over in wealth. Consumers declare
 * their own listener factories; a producer belongs to whoever produces.
 */
@Configuration
public class UserCreatedKafkaConfig {

    @Value(value = "${spring.kafka.bootstrap-servers}")
    private String bootstrapAddress;

    @Bean
    public NewTopic userCreatedTopic() {
        return new NewTopic("user_created", 1, (short) 1);
    }

    @Bean
    public ProducerFactory<String, UserCreatedEvent> userCreatedProducerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, UserCreatedEvent> userCreatedKafkaTemplate() {
        return new KafkaTemplate<>(userCreatedProducerFactory());
    }
}
