package com.multi.vidulum.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multi.vidulum.gateway.dto.ClientMessage;
import com.multi.vidulum.gateway.dto.KafkaEvent;
import com.multi.vidulum.gateway.dto.ServerMessage;
import com.multi.vidulum.gateway.service.KafkaEventConsumer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.SecretKey;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@org.springframework.context.annotation.Import(TestKafkaConfig.class)
class WebSocketGatewayIntegrationTest {

    @Container
    static KafkaContainer kafkaContainer = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.4.0")
    );

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${gateway.jwt.secret}")
    private String jwtSecret;

    private SecretKey secretKey;
    private TestWebSocketClient webSocketClient;

    @BeforeEach
    void setUp() {
        // Use BASE64 decoding to match JwtService
        byte[] keyBytes = io.jsonwebtoken.io.Decoders.BASE64.decode(jwtSecret);
        secretKey = Keys.hmacShaKeyFor(keyBytes);
        webSocketClient = new TestWebSocketClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (webSocketClient != null) {
            webSocketClient.close();
        }
    }

    @Test
    @DisplayName("Should establish WebSocket connection with valid JWT token")
    void shouldEstablishConnectionWithValidToken() throws Exception {
        // Given
        String token = generateToken("user-123");

        // When
        webSocketClient.connect(getWebSocketUrl(token));

        // Then
        assertThat(webSocketClient.isConnected()).isTrue();
    }

    @Test
    @DisplayName("Should reject WebSocket connection without token")
    void shouldRejectConnectionWithoutToken() throws Exception {
        // Given - no token in URL
        String wsUrl = "ws://localhost:" + port + "/ws";

        // When/Then
        webSocketClient.connectAndExpectFailure(wsUrl);
        assertThat(webSocketClient.isConnected()).isFalse();
    }

    @Test
    @DisplayName("Should reject WebSocket connection with invalid token")
    void shouldRejectConnectionWithInvalidToken() throws Exception {
        // Given
        String invalidToken = "invalid.jwt.token";

        // When/Then
        webSocketClient.connectAndExpectFailure(getWebSocketUrl(invalidToken));
        assertThat(webSocketClient.isConnected()).isFalse();
    }

    @Test
    @DisplayName("Should reject WebSocket connection with expired token")
    void shouldRejectConnectionWithExpiredToken() throws Exception {
        // Given
        String expiredToken = generateExpiredToken("user-123");

        // When/Then
        webSocketClient.connectAndExpectFailure(getWebSocketUrl(expiredToken));
        assertThat(webSocketClient.isConnected()).isFalse();
    }

    @Test
    @DisplayName("Should respond with pong when receiving ping")
    void shouldRespondWithPongOnPing() throws Exception {
        // Given
        String token = generateToken("user-123");
        webSocketClient.connect(getWebSocketUrl(token));

        ClientMessage pingMessage = ClientMessage.builder()
                .action(ClientMessage.Action.ping)
                .build();

        // When
        webSocketClient.send(objectMapper.writeValueAsString(pingMessage));

        // Then
        ServerMessage response = webSocketClient.awaitMessage(5, TimeUnit.SECONDS);
        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo(ServerMessage.Type.pong);
    }

    @Test
    @DisplayName("Should acknowledge subscription with success")
    void shouldAcknowledgeSubscription() throws Exception {
        // Given
        String token = generateToken("user-123");
        webSocketClient.connect(getWebSocketUrl(token));

        ClientMessage subscribeMessage = ClientMessage.builder()
                .action(ClientMessage.Action.subscribe)
                .topic(KafkaEventConsumer.TOPIC_BANK_DATA_INGESTION)
                .cashFlowId("cf-001")
                .build();

        // When
        webSocketClient.send(objectMapper.writeValueAsString(subscribeMessage));

        // Then
        ServerMessage response = webSocketClient.awaitMessage(5, TimeUnit.SECONDS);
        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo(ServerMessage.Type.ack);
        assertThat(response.getAction()).isEqualTo("subscribe");
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getMessage()).contains("Subscribed");
    }

    @Test
    @DisplayName("Should reject subscription without topic or cashFlowId")
    void shouldRejectIncompleteSubscription() throws Exception {
        // Given
        String token = generateToken("user-123");
        webSocketClient.connect(getWebSocketUrl(token));

        ClientMessage subscribeMessage = ClientMessage.builder()
                .action(ClientMessage.Action.subscribe)
                .topic(KafkaEventConsumer.TOPIC_BANK_DATA_INGESTION)
                // Missing cashFlowId
                .build();

        // When
        webSocketClient.send(objectMapper.writeValueAsString(subscribeMessage));

        // Then
        ServerMessage response = webSocketClient.awaitMessage(5, TimeUnit.SECONDS);
        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo(ServerMessage.Type.ack);
        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getMessage()).contains("Missing");
    }

    @Test
    @DisplayName("Should acknowledge unsubscription")
    void shouldAcknowledgeUnsubscription() throws Exception {
        // Given
        String token = generateToken("user-123");
        webSocketClient.connect(getWebSocketUrl(token));

        // First subscribe
        ClientMessage subscribeMessage = ClientMessage.builder()
                .action(ClientMessage.Action.subscribe)
                .topic(KafkaEventConsumer.TOPIC_BANK_DATA_INGESTION)
                .cashFlowId("cf-001")
                .build();
        webSocketClient.send(objectMapper.writeValueAsString(subscribeMessage));
        webSocketClient.awaitMessage(5, TimeUnit.SECONDS);

        // Then unsubscribe
        ClientMessage unsubscribeMessage = ClientMessage.builder()
                .action(ClientMessage.Action.unsubscribe)
                .topic(KafkaEventConsumer.TOPIC_BANK_DATA_INGESTION)
                .cashFlowId("cf-001")
                .build();

        // When
        webSocketClient.send(objectMapper.writeValueAsString(unsubscribeMessage));

        // Then
        ServerMessage response = webSocketClient.awaitMessage(5, TimeUnit.SECONDS);
        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo(ServerMessage.Type.ack);
        assertThat(response.getAction()).isEqualTo("unsubscribe");
        assertThat(response.getSuccess()).isTrue();
    }

    @Test
    @DisplayName("Should receive Kafka event after subscribing to topic")
    void shouldReceiveKafkaEventAfterSubscription() throws Exception {
        // Given
        String token = generateToken("user-123");
        String cashFlowId = "cf-kafka-test-001";
        webSocketClient.connect(getWebSocketUrl(token));

        // Subscribe to bank_data_ingestion topic
        ClientMessage subscribeMessage = ClientMessage.builder()
                .action(ClientMessage.Action.subscribe)
                .topic(KafkaEventConsumer.TOPIC_BANK_DATA_INGESTION)
                .cashFlowId(cashFlowId)
                .build();
        webSocketClient.send(objectMapper.writeValueAsString(subscribeMessage));
        webSocketClient.awaitMessage(5, TimeUnit.SECONDS); // Wait for ack

        // When - publish Kafka event
        KafkaEvent kafkaEvent = KafkaEvent.builder()
                .metadata(Map.of(
                        "eventType", "ImportProgressEvent",
                        "cashFlowId", cashFlowId,
                        "jobId", "job-123"
                ))
                .content(new KafkaEvent.JsonContent(
                        "{\"processedRecords\":50,\"totalRecords\":100}"
                ))
                .build();

        kafkaTemplate.send(KafkaEventConsumer.TOPIC_BANK_DATA_INGESTION, cashFlowId, kafkaEvent);

        // Then
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<ServerMessage> messages = webSocketClient.getAllMessages();
            assertThat(messages).anyMatch(msg ->
                    msg.getType() == ServerMessage.Type.event &&
                            "ImportProgressEvent".equals(msg.getEventType()) &&
                            cashFlowId.equals(msg.getCashFlowId())
            );
        });
    }

    @Test
    @DisplayName("Should NOT receive Kafka event for different cashFlowId")
    void shouldNotReceiveEventForDifferentCashFlowId() throws Exception {
        // Given
        String token = generateToken("user-123");
        String subscribedCashFlowId = "cf-subscribed";
        String otherCashFlowId = "cf-other";
        webSocketClient.connect(getWebSocketUrl(token));

        // Subscribe to specific cashFlowId
        ClientMessage subscribeMessage = ClientMessage.builder()
                .action(ClientMessage.Action.subscribe)
                .topic(KafkaEventConsumer.TOPIC_BANK_DATA_INGESTION)
                .cashFlowId(subscribedCashFlowId)
                .build();
        webSocketClient.send(objectMapper.writeValueAsString(subscribeMessage));
        webSocketClient.awaitMessage(5, TimeUnit.SECONDS); // Wait for ack

        // When - publish Kafka event for DIFFERENT cashFlowId
        KafkaEvent kafkaEvent = KafkaEvent.builder()
                .metadata(Map.of(
                        "eventType", "ImportProgressEvent",
                        "cashFlowId", otherCashFlowId,
                        "jobId", "job-123"
                ))
                .content(new KafkaEvent.JsonContent("{\"processedRecords\":50}"))
                .build();

        kafkaTemplate.send(KafkaEventConsumer.TOPIC_BANK_DATA_INGESTION, otherCashFlowId, kafkaEvent);

        // Then - should NOT receive the event
        Thread.sleep(2000); // Wait a bit
        List<ServerMessage> messages = webSocketClient.getAllMessages();
        assertThat(messages).noneMatch(msg ->
                msg.getType() == ServerMessage.Type.event &&
                        otherCashFlowId.equals(msg.getCashFlowId())
        );
    }

    @Test
    @DisplayName("Should broadcast Kafka event to multiple subscribers")
    void shouldBroadcastToMultipleSubscribers() throws Exception {
        // Given - two clients subscribed to same topic:cashFlowId
        String cashFlowId = "cf-multi-001";

        TestWebSocketClient client1 = new TestWebSocketClient();
        TestWebSocketClient client2 = new TestWebSocketClient();

        try {
            client1.connect(getWebSocketUrl(generateToken("user-1")));
            client2.connect(getWebSocketUrl(generateToken("user-2")));

            ClientMessage subscribeMessage = ClientMessage.builder()
                    .action(ClientMessage.Action.subscribe)
                    .topic(KafkaEventConsumer.TOPIC_BANK_DATA_INGESTION)
                    .cashFlowId(cashFlowId)
                    .build();

            client1.send(objectMapper.writeValueAsString(subscribeMessage));
            client2.send(objectMapper.writeValueAsString(subscribeMessage));

            client1.awaitMessage(5, TimeUnit.SECONDS); // ack
            client2.awaitMessage(5, TimeUnit.SECONDS); // ack

            // When - publish Kafka event
            KafkaEvent kafkaEvent = KafkaEvent.builder()
                    .metadata(Map.of(
                            "eventType", "ImportJobCompletedEvent",
                            "cashFlowId", cashFlowId,
                            "jobId", "job-broadcast"
                    ))
                    .content(new KafkaEvent.JsonContent("{\"success\":true}"))
                    .build();

            kafkaTemplate.send(KafkaEventConsumer.TOPIC_BANK_DATA_INGESTION, cashFlowId, kafkaEvent);

            // Then - both clients should receive the event
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                List<ServerMessage> messages1 = client1.getAllMessages();
                List<ServerMessage> messages2 = client2.getAllMessages();

                assertThat(messages1).anyMatch(msg ->
                        msg.getType() == ServerMessage.Type.event &&
                                "ImportJobCompletedEvent".equals(msg.getEventType())
                );
                assertThat(messages2).anyMatch(msg ->
                        msg.getType() == ServerMessage.Type.event &&
                                "ImportJobCompletedEvent".equals(msg.getEventType())
                );
            });
        } finally {
            client1.close();
            client2.close();
        }
    }

    @Test
    @DisplayName("Should receive cash_flow topic events")
    void shouldReceiveCashFlowTopicEvents() throws Exception {
        // Given
        String token = generateToken("user-123");
        String cashFlowId = "cf-cashflow-test-001";
        webSocketClient.connect(getWebSocketUrl(token));

        // Subscribe to cash_flow topic
        ClientMessage subscribeMessage = ClientMessage.builder()
                .action(ClientMessage.Action.subscribe)
                .topic(KafkaEventConsumer.TOPIC_CASH_FLOW)
                .cashFlowId(cashFlowId)
                .build();
        webSocketClient.send(objectMapper.writeValueAsString(subscribeMessage));
        webSocketClient.awaitMessage(5, TimeUnit.SECONDS); // Wait for ack

        // When - publish Kafka event to cash_flow topic
        KafkaEvent kafkaEvent = KafkaEvent.builder()
                .metadata(Map.of(
                        "eventType", "CashChangeAppendedEvent",
                        "cashFlowId", cashFlowId
                ))
                .content(new KafkaEvent.JsonContent(
                        "{\"amount\":150.00,\"currency\":\"USD\"}"
                ))
                .build();

        kafkaTemplate.send(KafkaEventConsumer.TOPIC_CASH_FLOW, cashFlowId, kafkaEvent);

        // Then
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<ServerMessage> messages = webSocketClient.getAllMessages();
            assertThat(messages).anyMatch(msg ->
                    msg.getType() == ServerMessage.Type.event &&
                            "CashChangeAppendedEvent".equals(msg.getEventType()) &&
                            KafkaEventConsumer.TOPIC_CASH_FLOW.equals(msg.getTopic())
            );
        });
    }

    @Test
    @DisplayName("Should stop receiving events after unsubscribe")
    void shouldStopReceivingEventsAfterUnsubscribe() throws Exception {
        // Given
        String token = generateToken("user-123");
        String cashFlowId = "cf-unsub-test";
        webSocketClient.connect(getWebSocketUrl(token));

        // Subscribe
        ClientMessage subscribeMessage = ClientMessage.builder()
                .action(ClientMessage.Action.subscribe)
                .topic(KafkaEventConsumer.TOPIC_BANK_DATA_INGESTION)
                .cashFlowId(cashFlowId)
                .build();
        webSocketClient.send(objectMapper.writeValueAsString(subscribeMessage));
        webSocketClient.awaitMessage(5, TimeUnit.SECONDS); // ack

        // Unsubscribe
        ClientMessage unsubscribeMessage = ClientMessage.builder()
                .action(ClientMessage.Action.unsubscribe)
                .topic(KafkaEventConsumer.TOPIC_BANK_DATA_INGESTION)
                .cashFlowId(cashFlowId)
                .build();
        webSocketClient.send(objectMapper.writeValueAsString(unsubscribeMessage));
        webSocketClient.awaitMessage(5, TimeUnit.SECONDS); // ack

        // Clear messages
        webSocketClient.clearMessages();

        // When - publish Kafka event after unsubscribe
        KafkaEvent kafkaEvent = KafkaEvent.builder()
                .metadata(Map.of(
                        "eventType", "ImportProgressEvent",
                        "cashFlowId", cashFlowId
                ))
                .content(new KafkaEvent.JsonContent("{}"))
                .build();

        kafkaTemplate.send(KafkaEventConsumer.TOPIC_BANK_DATA_INGESTION, cashFlowId, kafkaEvent);

        // Then - should NOT receive the event
        Thread.sleep(2000);
        List<ServerMessage> messages = webSocketClient.getAllMessages();
        assertThat(messages).noneMatch(msg -> msg.getType() == ServerMessage.Type.event);
    }

    @Test
    @DisplayName("Should return error for invalid message format")
    void shouldReturnErrorForInvalidMessageFormat() throws Exception {
        // Given
        String token = generateToken("user-123");
        webSocketClient.connect(getWebSocketUrl(token));

        // When - send invalid JSON
        webSocketClient.sendRaw("not a valid json");

        // Then
        ServerMessage response = webSocketClient.awaitMessage(5, TimeUnit.SECONDS);
        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo(ServerMessage.Type.error);
        assertThat(response.getMessage()).contains("Invalid message format");
    }

    @Test
    @DisplayName("Should return error for missing action")
    void shouldReturnErrorForMissingAction() throws Exception {
        // Given
        String token = generateToken("user-123");
        webSocketClient.connect(getWebSocketUrl(token));

        // When - send message without action
        webSocketClient.sendRaw("{\"topic\":\"test\"}");

        // Then
        ServerMessage response = webSocketClient.awaitMessage(5, TimeUnit.SECONDS);
        assertThat(response).isNotNull();
        assertThat(response.getType()).isEqualTo(ServerMessage.Type.error);
        assertThat(response.getMessage()).contains("Missing action");
    }

    // --- Helper methods ---

    private String getWebSocketUrl(String token) {
        return String.format("ws://localhost:%d/ws?token=%s", port, token);
    }

    private String generateToken(String userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + 3600000); // 1 hour

        return Jwts.builder()
                .setSubject(userId)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    private String generateExpiredToken(String userId) {
        Date past = new Date(System.currentTimeMillis() - 3600000); // 1 hour ago
        Date expiry = new Date(System.currentTimeMillis() - 1800000); // 30 min ago

        return Jwts.builder()
                .setSubject(userId)
                .setIssuedAt(past)
                .setExpiration(expiry)
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    // --- Test WebSocket Client ---

    private class TestWebSocketClient extends TextWebSocketHandler {

        private WebSocketSession session;
        private final BlockingQueue<ServerMessage> receivedMessages = new LinkedBlockingQueue<>();
        private final List<ServerMessage> allMessages = new ArrayList<>();
        private final CountDownLatch connectionLatch = new CountDownLatch(1);
        private volatile boolean connectionFailed = false;

        public void connect(String url) throws Exception {
            StandardWebSocketClient client = new StandardWebSocketClient();
            session = client.execute(this, new WebSocketHttpHeaders(), URI.create(url)).get(10, TimeUnit.SECONDS);
            connectionLatch.await(10, TimeUnit.SECONDS);
        }

        public void connectAndExpectFailure(String url) {
            try {
                StandardWebSocketClient client = new StandardWebSocketClient();
                session = client.execute(this, new WebSocketHttpHeaders(), URI.create(url)).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                connectionFailed = true;
            }
        }

        public void send(String message) throws Exception {
            if (session != null && session.isOpen()) {
                session.sendMessage(new TextMessage(message));
            }
        }

        public void sendRaw(String message) throws Exception {
            send(message);
        }

        public ServerMessage awaitMessage(long timeout, TimeUnit unit) throws Exception {
            return receivedMessages.poll(timeout, unit);
        }

        public List<ServerMessage> getAllMessages() {
            synchronized (allMessages) {
                return new ArrayList<>(allMessages);
            }
        }

        public void clearMessages() {
            receivedMessages.clear();
            synchronized (allMessages) {
                allMessages.clear();
            }
        }

        public boolean isConnected() {
            return session != null && session.isOpen() && !connectionFailed;
        }

        public void close() throws Exception {
            if (session != null && session.isOpen()) {
                session.close();
            }
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            connectionLatch.countDown();
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            try {
                ServerMessage serverMessage = objectMapper.readValue(message.getPayload(), ServerMessage.class);
                receivedMessages.offer(serverMessage);
                synchronized (allMessages) {
                    allMessages.add(serverMessage);
                }
            } catch (JsonProcessingException e) {
                // Ignore malformed messages in tests
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            connectionLatch.countDown();
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            connectionFailed = true;
            connectionLatch.countDown();
        }
    }
}
