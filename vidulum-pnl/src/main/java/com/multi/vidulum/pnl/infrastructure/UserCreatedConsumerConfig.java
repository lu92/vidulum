package com.multi.vidulum.pnl.infrastructure;

import com.multi.vidulum.common.events.UserCreatedEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * How wealth listens to {@code user_created}. Needed by
 * {@link com.multi.vidulum.pnl.app.listeners.UserCreatedEventListener}, which is the only thing in
 * this module that consumes it.
 *
 * <p>Sibling of cashflow's {@code UserCreatedEventConsumerConfig}: each module that listens brings
 * its own factories, so none of them needs another module's configuration class to be on the
 * classpath. Cashflow's are declared {@code @ConditionalOnMissingBean} and therefore stand down
 * when these are present, exactly as they did when these beans lived in
 * {@code quotation.KafkaTopicConfig}.
 */
@Configuration
public class UserCreatedConsumerConfig {

    @Value(value = "${spring.kafka.bootstrap-servers}")
    private String bootstrapAddress;

    @Bean
    public ConsumerFactory<String, UserCreatedEvent> userCreatedConsumerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        return new DefaultKafkaConsumerFactory<>(
                configProps,
                new StringDeserializer(),
                new JsonDeserializer<>(UserCreatedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, UserCreatedEvent> userCreatedContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, UserCreatedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(userCreatedConsumerFactory());
        return factory;
    }
}
