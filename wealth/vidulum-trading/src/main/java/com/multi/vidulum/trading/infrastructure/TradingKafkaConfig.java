package com.multi.vidulum.trading.infrastructure;

import com.multi.vidulum.trading.api.TradeConfirmedEvent;
import com.multi.vidulum.trading.api.TradeConfirmedHandler;
import com.multi.vidulum.trading.app.TradeCapturedEvent;
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
        return new NewTopic(TradeCapturedEvent.TOPIC, 1, (short) 1);
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
    public NewTopic tradeConfirmedTopic() {
        return new NewTopic(TradeConfirmedHandler.TOPIC, 1, (short) 1);
    }

    @Bean
    public ProducerFactory<String, TradeConfirmedEvent> tradeConfirmedProducerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, TradeConfirmedEvent> tradeConfirmedKafkaTemplate() {
        return new KafkaTemplate<>(tradeConfirmedProducerFactory());
    }

    @Bean
    public ConsumerFactory<String, TradeConfirmedEvent> tradeConfirmedConsumerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        return new DefaultKafkaConsumerFactory<>(
                configProps,
                new StringDeserializer(),
                new JsonDeserializer<>(TradeConfirmedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TradeConfirmedEvent> tradeConfirmedContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, TradeConfirmedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(tradeConfirmedConsumerFactory());
        return factory;
    }
}
