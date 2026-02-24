package com.multi.vidulum.security.auth;

import com.multi.vidulum.common.error.ApiError;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * HTTP Actor for authentication API testing.
 * Encapsulates all REST interactions with /api/v1/auth endpoints.
 */
public class AuthenticationHttpActor {

    private final TestRestTemplate restTemplate;
    private final String baseUrl;

    public AuthenticationHttpActor(TestRestTemplate restTemplate, int port) {
        this.restTemplate = restTemplate;
        this.baseUrl = "http://localhost:" + port;
    }

    // ==================== REGISTRATION ====================

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

    // ==================== AUTHENTICATION ====================

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

        return restTemplate.exchange(
                baseUrl + "/api/v1/auth/authenticate",
                HttpMethod.POST,
                entity,
                ApiError.class
        );
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

        return restTemplate.exchange(
                baseUrl + "/api/v1/auth/authenticate",
                HttpMethod.POST,
                entity,
                String.class
        );
    }

    // ==================== LOGOUT ====================

    public ResponseEntity<LogoutResponse> logout(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        return restTemplate.exchange(
                baseUrl + "/api/v1/auth/logout",
                HttpMethod.POST,
                entity,
                LogoutResponse.class
        );
    }

    public ResponseEntity<ApiError> logoutExpectingError(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        return restTemplate.exchange(
                baseUrl + "/api/v1/auth/logout",
                HttpMethod.POST,
                entity,
                ApiError.class
        );
    }

    public ResponseEntity<ApiError> logoutWithoutToken() {
        HttpEntity<Void> entity = new HttpEntity<>(new HttpHeaders());

        return restTemplate.exchange(
                baseUrl + "/api/v1/auth/logout",
                HttpMethod.POST,
                entity,
                ApiError.class
        );
    }

    // ==================== LOGOUT ALL ====================

    public ResponseEntity<LogoutAllResponse> logoutAll(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        return restTemplate.exchange(
                baseUrl + "/api/v1/auth/logout-all",
                HttpMethod.POST,
                entity,
                LogoutAllResponse.class
        );
    }

    public ResponseEntity<ApiError> logoutAllExpectingError(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        return restTemplate.exchange(
                baseUrl + "/api/v1/auth/logout-all",
                HttpMethod.POST,
                entity,
                ApiError.class
        );
    }

    // ==================== REFRESH TOKEN ====================

    public ResponseEntity<AuthenticationResponse> refreshToken(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(refreshToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        return restTemplate.exchange(
                baseUrl + "/api/v1/auth/refresh-token",
                HttpMethod.POST,
                entity,
                AuthenticationResponse.class
        );
    }

    public ResponseEntity<ApiError> refreshTokenExpectingError(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(refreshToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        return restTemplate.exchange(
                baseUrl + "/api/v1/auth/refresh-token",
                HttpMethod.POST,
                entity,
                ApiError.class
        );
    }

    public ResponseEntity<ApiError> refreshTokenWithoutHeader() {
        HttpEntity<Void> entity = new HttpEntity<>(new HttpHeaders());

        return restTemplate.exchange(
                baseUrl + "/api/v1/auth/refresh-token",
                HttpMethod.POST,
                entity,
                ApiError.class
        );
    }

    // ==================== PROTECTED RESOURCE ====================

    public ResponseEntity<String> getProtectedResource(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        return restTemplate.exchange(
                baseUrl + "/cash-flow",
                HttpMethod.GET,
                entity,
                String.class
        );
    }
}
