package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.common.events.BankDataIngestionUnifiedEvent;
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
public class BankDataIngestionKafkaConfig {

    @Value(value = "${spring.kafka.bootstrap-servers}")
    private String bootstrapAddress;

    @Bean
    public NewTopic bankDataIngestionTopic() {
        return new NewTopic("bank_data_ingestion", 1, (short) 1);
    }

    @Bean
    public ProducerFactory<String, BankDataIngestionUnifiedEvent> bankDataIngestionProducerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, BankDataIngestionUnifiedEvent> bankDataIngestionKafkaTemplate() {
        return new KafkaTemplate<>(bankDataIngestionProducerFactory());
    }

    @Bean
    public ConsumerFactory<String, BankDataIngestionUnifiedEvent> bankDataIngestionConsumerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        return new DefaultKafkaConsumerFactory<>(
                configProps,
                new StringDeserializer(),
                new JsonDeserializer<>(BankDataIngestionUnifiedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BankDataIngestionUnifiedEvent> bankDataIngestionContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, BankDataIngestionUnifiedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(bankDataIngestionConsumerFactory());
        return factory;
    }
}
