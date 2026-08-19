package com.multi.vidulum.quotation;


import com.multi.vidulum.common.events.UserCreatedEvent;
import com.multi.vidulum.quotation.app.BinanceBrokerQuotationProvider;
import com.multi.vidulum.quotation.app.DegiroBrokerQuotationProvider;
import com.multi.vidulum.quotation.app.PMBrokerQuotationProvider;
import com.multi.vidulum.quotation.domain.BrokerQuotationProvider;
import com.multi.vidulum.quotation.domain.QuotationService;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.List;
import java.util.Map;

@Configuration
public class KafkaTopicConfig {

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

    //    ******* User Created Events (user domain - stays in vidulum-app) *******

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

    //    ******* Quotation Service & Broker Providers *******

    @Bean
    public QuotationService quotationService(@Autowired List<BrokerQuotationProvider> brokerQuotationProviders) {
        QuotationService quotationService = new QuotationService();
        brokerQuotationProviders.forEach(quotationService::registerBroker);
        return quotationService;
    }

    @Bean
    public BinanceBrokerQuotationProvider binanceBrokerQuotationProvider() {
        return new BinanceBrokerQuotationProvider();
    }

    @Bean
    public PMBrokerQuotationProvider pmBrokerQuotationProvider() {
        return new PMBrokerQuotationProvider();
    }

    @Bean
    public DegiroBrokerQuotationProvider degiroBrokerQuotationProvider() {
        return new DegiroBrokerQuotationProvider();
    }
}
