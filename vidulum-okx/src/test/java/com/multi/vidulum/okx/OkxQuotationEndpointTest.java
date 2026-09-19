package com.multi.vidulum.okx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multi.vidulum.quotation.domain.BrokerQuotationProvider;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the wiring the component test cannot reach: that the bean declared in
 * {@link OkxQuotationConfiguration} is collected into {@code List<BrokerQuotationProvider>} by
 * {@code KafkaTopicConfig#quotationService}, and that the REST path
 * publish -> Kafka -> provider cache -> fetch works end to end for broker OKX.
 *
 * <p>Before this module existed, every one of these calls ended in
 * {@code BrokerNotFoundException}.
 */
@Slf4j
@SpringBootTest(classes = OkxTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OkxQuotationEndpointTest {

    private static final KafkaContainer KAFKA;

    static {
        KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.1"));
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private List<BrokerQuotationProvider> registeredProviders;

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private void publishQuote(String broker, String origin, String destination, double amount, String currency) {
        client().get()
                .uri("/quote/publish?broker={b}&origin={o}&destination={d}&amount={a}&currency={c}&pctChange=0",
                        broker, origin, destination, amount, currency)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Reads the quote as raw JSON rather than binding to {@code AssetPriceMetadata}: that class is
     * an immutable {@code @Value @Builder} with no no-arg constructor, so Jackson cannot
     * deserialize it client-side. Production only ever serialises it outward, so this is a
     * limitation of the test client, not of the endpoint.
     */
    private double fetchQuotedAmount(String broker, String origin, String destination) {
        String json = client().get()
                .uri("/quote/{b}/{o}/{d}", broker, origin, destination)
                .retrieve()
                .body(String.class);
        try {
            JsonNode node = MAPPER.readTree(json);
            return node.get("currentPrice").get("amount").asDouble();
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable quote payload: " + json, e);
        }
    }

    /**
     * The quote travels through Kafka, so it is not in the cache the instant publish returns.
     * {@code ignoreExceptions} matters: until the listener catches up the endpoint answers 500
     * from {@code QuoteNotFoundException}, and without it Awaitility would abort on the first try
     * instead of retrying.
     */
    private void awaitQuote(String origin, String destination, double expectedAmount) {
        Awaitility.await()
                .atMost(10, SECONDS)
                .ignoreExceptions()
                .untilAsserted(() -> assertThat(fetchQuotedAmount("OKX", origin, destination))
                        .isEqualTo(expectedAmount));
    }

    @Test
    void shouldRegisterOkxProviderInTheApplicationContext() {
        // this is the assertion the component test structurally cannot make
        assertThat(registeredProviders)
                .anyMatch(provider -> provider instanceof OkxBrokerQuotationProvider);
    }

    @Test
    void shouldAcceptQuotePublishedForOkx() {
        // before the module existed this returned 500 caused by BrokerNotFoundException
        publishQuote("OKX", "BTC", "EUR", 66532.9, "EUR");

        awaitQuote("BTC", "EUR", 66532.9);
    }

    @Test
    void shouldServeCashQuotedAgainstItself() {
        // GET /portfolio asks for a price of every asset, cash included - without EUR/EUR the
        // portfolio read throws on its first cash position
        publishQuote("OKX", "EUR", "EUR", 1.0, "EUR");

        awaitQuote("EUR", "EUR", 1.0);
    }

    @Test
    void shouldReflectARepublishedPrice() {
        publishQuote("OKX", "ETH", "EUR", 2400.0, "EUR");
        awaitQuote("ETH", "EUR", 2400.0);

        publishQuote("OKX", "ETH", "EUR", 2500.0, "EUR");

        awaitQuote("ETH", "EUR", 2500.0);
    }

    @Test
    void shouldStillRejectABrokerThatWasNeverRegistered() {
        // registering OKX must not make the system accept arbitrary brokers
        assertThatThrownBy(() -> fetchQuotedAmount("KRAKEN", "BTC", "EUR"))
                .hasMessageContaining("500");
    }
}
