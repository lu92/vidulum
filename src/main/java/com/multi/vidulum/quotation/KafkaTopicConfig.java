package com.multi.vidulum.quotation;


import com.multi.vidulum.common.events.OrderFilledEvent;
import com.multi.vidulum.common.events.TradeCapturedEvent;
import com.multi.vidulum.common.events.UserCreatedEvent;
import com.multi.vidulum.quotation.app.BinanceBrokerQuotationProvider;
import com.multi.vidulum.quotation.app.DegiroBrokerQuotationProvider;
import com.multi.vidulum.quotation.app.PMBrokerQuotationProvider;
import com.multi.vidulum.quotation.domain.BrokerQuotationProvider;
import com.multi.vidulum.quotation.domain.PriceChangedEvent;
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

    @Value(value = "${kafka.bootstrapAddress}")
    private String bootstrapAddress;

    @Bean
    public KafkaAdmin kafkaAdmin() {
        return new KafkaAdmin(
                Map.of(
                        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class
                ));
    }

    @Bean
    public NewTopic quotesTopic() {
        return new NewTopic("quotes", 1, (short) 1);
    }

    @Bean
    public ProducerFactory<String, PriceChangedEvent> pricingProducerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, PriceChangedEvent> pricingKafkaTemplate() {
        return new KafkaTemplate<>(pricingProducerFactory());
    }

    @Bean
    public ConsumerFactory<String, PriceChangedEvent> pricingConsumerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        return new DefaultKafkaConsumerFactory<>(
                configProps,
                new StringDeserializer(),
                new JsonDeserializer<>(PriceChangedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PriceChangedEvent> priceChangingContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, PriceChangedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(pricingConsumerFactory());
        return factory;
    }

    @Bean
    public QuotationService quotationService(@Autowired List<BrokerQuotationProvider> brokerQuotationProviders) {
        QuotationService quotationService = new QuotationService();
        brokerQuotationProviders.forEach(quotationService::registerBroker);
        return quotationService;
    }

    //    *******
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

    //    *******
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


    //    *******

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

    //    *******

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
