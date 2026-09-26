package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio_spec.app.PortfolioSpecDto;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Every HTTP call the specification lifecycle makes, in one place — the actor pattern
 * {@code CLAUDE.md} requires of integration tests.
 *
 * <p>Each call has an {@code …ExpectingError} twin returning {@link ApiError}. That is not
 * symmetry for its own sake: half of what D10 added <b>is</b> a refusal, and a refusal is only
 * correct if it reaches the client as the right status with a code they can act on.
 */
public class PortfolioSpecHttpActor {

    private static final String PATH = "/portfolio-spec";

    private final TestRestTemplate restTemplate;
    private final String baseUrl;

    public PortfolioSpecHttpActor(TestRestTemplate restTemplate, int port) {
        this.restTemplate = restTemplate;
        this.baseUrl = "http://localhost:" + port;
    }

    public ResponseEntity<PortfolioSpecDto.PortfolioSpecJson> create(PortfolioSpecDto.CreateSpecJson request) {
        return post(PATH, request, PortfolioSpecDto.PortfolioSpecJson.class);
    }

    public ResponseEntity<ApiError> createExpectingError(PortfolioSpecDto.CreateSpecJson request) {
        return post(PATH, request, ApiError.class);
    }

    public ResponseEntity<PortfolioSpecDto.PortfolioSpecJson> get(String specId) {
        return restTemplate.exchange(baseUrl + PATH + "/" + specId, HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()), PortfolioSpecDto.PortfolioSpecJson.class);
    }

    public ResponseEntity<ApiError> getExpectingError(String specId) {
        return restTemplate.exchange(baseUrl + PATH + "/" + specId, HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()), ApiError.class);
    }

    public ResponseEntity<PortfolioSpecDto.PortfolioSpecJson> answer(
            String specId, PortfolioSpecDto.AnswerSpecJson request) {
        return restTemplate.exchange(baseUrl + PATH + "/" + specId + "/answers", HttpMethod.PUT,
                new HttpEntity<>(request, jsonHeaders()), PortfolioSpecDto.PortfolioSpecJson.class);
    }

    public ResponseEntity<ApiError> answerExpectingError(
            String specId, PortfolioSpecDto.AnswerSpecJson request) {
        return restTemplate.exchange(baseUrl + PATH + "/" + specId + "/answers", HttpMethod.PUT,
                new HttpEntity<>(request, jsonHeaders()), ApiError.class);
    }

    public ResponseEntity<PortfolioSpecDto.PortfolioSpecJson> cancel(String specId) {
        return post(PATH + "/" + specId + "/cancel", null, PortfolioSpecDto.PortfolioSpecJson.class);
    }

    public ResponseEntity<ApiError> cancelExpectingError(String specId) {
        return post(PATH + "/" + specId + "/cancel", null, ApiError.class);
    }

    public ResponseEntity<PortfolioSpecDto.PortfolioSpecJson> confirm(
            String specId, PortfolioSpecDto.ConfirmSpecJson request) {
        return post(PATH + "/" + specId + "/confirm", request, PortfolioSpecDto.PortfolioSpecJson.class);
    }

    public ResponseEntity<ApiError> confirmExpectingError(
            String specId, PortfolioSpecDto.ConfirmSpecJson request) {
        return post(PATH + "/" + specId + "/confirm", request, ApiError.class);
    }

    /** The portfolio the specification produced, read the way any client would. */
    public ResponseEntity<PortfolioDto.PortfolioSummaryJson> readPortfolio(String portfolioId, String currency) {
        return restTemplate.exchange(
                baseUrl + "/portfolio/" + portfolioId + "/" + currency, HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()), PortfolioDto.PortfolioSummaryJson.class);
    }

    /** Quotes have to be in the cache before a portfolio can be created or read (E8, C12). */
    public void publishQuote(String broker, String origin, String destination, double amount) {
        restTemplate.exchange(
                baseUrl + "/quote/publish?broker=" + broker + "&origin=" + origin
                        + "&destination=" + destination + "&amount=" + amount
                        + "&currency=" + destination + "&pctChange=0",
                HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
    }

    /** The raw body of a portfolio read — used when the typed one cannot explain a refusal. */
    public ResponseEntity<String> readPortfolioRaw(String portfolioId, String currency) {
        return restTemplate.exchange(
                baseUrl + "/portfolio/" + portfolioId + "/" + currency, HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()), String.class);
    }

    /** Names an asset, so reading the portfolio has something to put in {@code fullName}. */
    public void registerAssetInfo(String broker, com.multi.vidulum.quotation.app.QuotationDto.AssetBasicInfoJson info) {
        restTemplate.exchange(baseUrl + "/quote/" + broker + "/", HttpMethod.PUT,
                new HttpEntity<>(info, jsonHeaders()), String.class);
    }

    /** Reads a quote back, so a test can wait for the one it published to reach the cache. */
    public ResponseEntity<String> readQuote(String broker, String origin, String destination) {
        return restTemplate.exchange(
                baseUrl + "/quote/" + broker + "/" + origin + "/" + destination,
                HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
    }

    private <T> ResponseEntity<T> post(String path, Object body, Class<T> type) {
        return restTemplate.exchange(baseUrl + path, HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), type);
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
