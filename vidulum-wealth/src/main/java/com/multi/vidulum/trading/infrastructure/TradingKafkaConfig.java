package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.common.events.OrderFilledEvent;
import com.multi.vidulum.common.events.TradeCapturedEvent;
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
public class TradingKafkaConfig {

    @Value(value = "${spring.kafka.bootstrap-servers}")
    private String bootstrapAddress;

    // ******* Trade Captured *******

    @Bean
    public NewTopic tradeCapturedTopic() {
        return new NewTopic("trade_captured", 1, (short) 1);
    }

    @Bean
    public ProducerFactory<String, TradeCapturedEvent> tradeCapturedProducerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, TradeCapturedEvent> tradeCapturedKafkaTemplate() {
        return new KafkaTemplate<>(tradeCapturedProducerFactory());
    }

    @Bean
    public ConsumerFactory<String, TradeCapturedEvent> tradeCapturedConsumerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        return new DefaultKafkaConsumerFactory<>(
                configProps,
                new StringDeserializer(),
                new JsonDeserializer<>(TradeCapturedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TradeCapturedEvent> tradeCapturedContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, TradeCapturedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(tradeCapturedConsumerFactory());
        return factory;
    }

    // ******* Order Filled *******

    @Bean
    public NewTopic orderFilledTopic() {
        return new NewTopic("order_filled", 1, (short) 1);
    }

    @Bean
    public ProducerFactory<String, OrderFilledEvent> orderFilledProducerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, OrderFilledEvent> orderFilledKafkaTemplate() {
        return new KafkaTemplate<>(orderFilledProducerFactory());
    }

    @Bean
    public ConsumerFactory<String, OrderFilledEvent> orderFilledConsumerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        return new DefaultKafkaConsumerFactory<>(
                configProps,
                new StringDeserializer(),
                new JsonDeserializer<>(OrderFilledEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderFilledEvent> orderFilledContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, OrderFilledEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(orderFilledConsumerFactory());
        return factory;
    }
}
