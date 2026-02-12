package com.multi.vidulum.security.auth;

import com.multi.vidulum.common.error.ApiError;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * HTTP Actor for authentication API testing.
 * Encapsulates all REST interactions with /api/v1/auth endpoints.
 */
public class AuthenticationHttpActor {

    private final TestRestTemplate restTemplate;
    private final RestTemplate rawRestTemplate;
    private final String baseUrl;

    public AuthenticationHttpActor(TestRestTemplate restTemplate, int port) {
        this.restTemplate = restTemplate;
        this.baseUrl = "http://localhost:" + port;
        this.rawRestTemplate = createRawRestTemplate();
    }

    private RestTemplate createRawRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false);
        return new RestTemplate(factory);
    }

    public ResponseEntity<AuthenticationResponse> register(String username, String email, String password) {
        RegisterRequest request = RegisterRequest.builder()
                .username(username)
                .email(email)
                .password(password)
                .build();

        return restTemplate.postForEntity(
                baseUrl + "/api/v1/auth/register",
                request,
                AuthenticationResponse.class
        );
    }

    public ResponseEntity<ApiError> registerExpectingError(String username, String email, String password) {
        RegisterRequest request = RegisterRequest.builder()
                .username(username)
                .email(email)
                .password(password)
                .build();

        return restTemplate.postForEntity(
                baseUrl + "/api/v1/auth/register",
                request,
                ApiError.class
        );
    }

    public ResponseEntity<ApiError> registerWithRawJson(Map<String, Object> jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(jsonBody, headers);

        return restTemplate.postForEntity(
                baseUrl + "/api/v1/auth/register",
                entity,
                ApiError.class
        );
    }

    public ResponseEntity<AuthenticationResponse> authenticate(String username, String password) {
        AuthenticationRequest request = AuthenticationRequest.builder()
                .username(username)
                .password(password)
                .build();

        return restTemplate.postForEntity(
                baseUrl + "/api/v1/auth/authenticate",
                request,
                AuthenticationResponse.class
        );
    }

    public ResponseEntity<ApiError> authenticateExpectingError(String username, String password) {
        AuthenticationRequest request = AuthenticationRequest.builder()
                .username(username)
                .password(password)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<AuthenticationRequest> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<ApiError> response = rawRestTemplate.exchange(
                    baseUrl + "/api/v1/auth/authenticate",
                    HttpMethod.POST,
                    entity,
                    ApiError.class
            );
            return response;
        } catch (HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(e.getResponseBodyAs(ApiError.class));
        }
    }

    public ResponseEntity<ApiError> authenticateWithRawJson(Map<String, Object> jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(jsonBody, headers);

        return restTemplate.postForEntity(
                baseUrl + "/api/v1/auth/authenticate",
                entity,
                ApiError.class
        );
    }

    public ResponseEntity<String> authenticateExpectingErrorRaw(String username, String password) {
        AuthenticationRequest request = AuthenticationRequest.builder()
                .username(username)
                .password(password)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<AuthenticationRequest> entity = new HttpEntity<>(request, headers);

        try {
            return rawRestTemplate.exchange(
                    baseUrl + "/api/v1/auth/authenticate",
                    HttpMethod.POST,
                    entity,
                    String.class
            );
        } catch (HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(e.getResponseBodyAsString());
        }
    }
}
