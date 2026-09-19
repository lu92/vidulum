package com.multi.vidulum.exchange_connection;

import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.exchange_connection.app.ExchangeConnectionDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Encapsulates every HTTP call to {@code /exchange-connection}, following the actor pattern the
 * project's other integration tests use ({@code UserFinancialProfileHttpActor},
 * {@code BankDataIngestionHttpActor}).
 *
 * <p>Responses come back as typed objects rather than raw JSON, so tests can compare whole
 * objects instead of picking fields out of a tree — the difference between a new field being
 * caught and being silently untested.
 *
 * <p>Each call has an {@code …ExpectingError} twin returning {@link ApiError}, because the
 * refusals are as much a part of this API as the successes.
 */
@Slf4j
public class ExchangeConnectionHttpActor {

    private static final String PATH = "/exchange-connection";

    private final TestRestTemplate restTemplate;
    private final String baseUrl;

    public ExchangeConnectionHttpActor(TestRestTemplate restTemplate, int port) {
        this.restTemplate = restTemplate;
        this.baseUrl = "http://localhost:" + port;
    }

    public ResponseEntity<ExchangeConnectionDto.ExchangeConnectionJson> connect(ExchangeConnectionDto.ConnectExchangeJson request) {
        return restTemplate.exchange(
                baseUrl + PATH, HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()), ExchangeConnectionDto.ExchangeConnectionJson.class);
    }

    public ResponseEntity<ApiError> connectExpectingError(ExchangeConnectionDto.ConnectExchangeJson request) {
        return restTemplate.exchange(
                baseUrl + PATH, HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()), ApiError.class);
    }

    /**
     * Sends a hand-written body. Needed for payloads the typed request cannot express — a blank
     * field survives here but would be normalised away by building a {@code ConnectExchangeJson}
     * in the test.
     */
    public ResponseEntity<ApiError> connectRawExpectingError(String json) {
        return restTemplate.exchange(
                baseUrl + PATH, HttpMethod.POST,
                new HttpEntity<>(json, jsonHeaders()), ApiError.class);
    }

    public ResponseEntity<ExchangeConnectionDto.ExchangeConnectionJson> get(String connectionId) {
        return restTemplate.exchange(
                baseUrl + PATH + "/" + connectionId, HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()), ExchangeConnectionDto.ExchangeConnectionJson.class);
    }

    public ResponseEntity<ApiError> getExpectingError(String connectionId) {
        return restTemplate.exchange(
                baseUrl + PATH + "/" + connectionId, HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()), ApiError.class);
    }

    public ResponseEntity<ExchangeConnectionDto.ExchangeConnectionsListJson> list() {
        return restTemplate.exchange(
                baseUrl + PATH, HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()), ExchangeConnectionDto.ExchangeConnectionsListJson.class);
    }

    public ResponseEntity<ExchangeConnectionDto.ExchangeConnectionJson> reconnect(String connectionId) {
        return restTemplate.exchange(
                baseUrl + PATH + "/" + connectionId + "/reconnect", HttpMethod.POST,
                new HttpEntity<>(jsonHeaders()), ExchangeConnectionDto.ExchangeConnectionJson.class);
    }

    public ResponseEntity<ApiError> reconnectExpectingError(String connectionId) {
        return restTemplate.exchange(
                baseUrl + PATH + "/" + connectionId + "/reconnect", HttpMethod.POST,
                new HttpEntity<>(jsonHeaders()), ApiError.class);
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
