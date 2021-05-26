package com.multi.vidulum.quotation;


import com.multi.vidulum.common.events.TradeAppliedToPortfolioEvent;
import com.multi.vidulum.common.events.TradeStoredEvent;
import com.multi.vidulum.quotation.app.BinanceBrokerQuotationProvider;
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

    @Bean
    public NewTopic tradeExecutedTopic() {
        return new NewTopic("executed_trades", 1, (short) 1);
    }

    @Bean
    public ProducerFactory<String, TradeAppliedToPortfolioEvent> tradeExecutedProducerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, TradeAppliedToPortfolioEvent> tradeExecutedKafkaTemplate() {
        return new KafkaTemplate<>(tradeExecutedProducerFactory());
    }

    @Bean
    public ConsumerFactory<String, TradeAppliedToPortfolioEvent> tradeExecutedConsumerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        return new DefaultKafkaConsumerFactory<>(
                configProps,
                new StringDeserializer(),
                new JsonDeserializer<>(TradeAppliedToPortfolioEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TradeAppliedToPortfolioEvent> tradeExecutedContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, TradeAppliedToPortfolioEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(tradeExecutedConsumerFactory());
        return factory;
    }


    //    *******
    @Bean
    public NewTopic tradeStoredTopic() {
        return new NewTopic("trade_stored", 1, (short) 1);
    }

    @Bean
    public ProducerFactory<String, TradeStoredEvent> tradeStoredProducerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, TradeStoredEvent> tradeStoredKafkaTemplate() {
        return new KafkaTemplate<>(tradeStoredProducerFactory());
    }

    @Bean
    public ConsumerFactory<String, TradeStoredEvent> tradeStoredConsumerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        return new DefaultKafkaConsumerFactory<>(
                configProps,
                new StringDeserializer(),
                new JsonDeserializer<>(TradeStoredEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TradeStoredEvent> tradeStoredContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, TradeStoredEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(tradeStoredConsumerFactory());
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
}
