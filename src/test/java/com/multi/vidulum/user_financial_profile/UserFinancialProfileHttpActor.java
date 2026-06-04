package com.multi.vidulum.user_financial_profile;

import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.user_financial_profile.app.UserFinancialProfileDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Slf4j
public class UserFinancialProfileHttpActor {

    private final TestRestTemplate restTemplate;
    private final String baseUrl;
    private String jwtToken;

    public UserFinancialProfileHttpActor(TestRestTemplate restTemplate, int port) {
        this.restTemplate = restTemplate;
        this.baseUrl = "http://localhost:" + port;
    }

    public void setJwtToken(String token) {
        this.jwtToken = token;
    }

    public UserFinancialProfileDto.OwnedAccountsListJson listAccounts() {
        ResponseEntity<UserFinancialProfileDto.OwnedAccountsListJson> response = restTemplate.exchange(
                baseUrl + "/api/v1/user/owned-accounts",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                UserFinancialProfileDto.OwnedAccountsListJson.class
        );
        return response.getBody();
    }

    public ResponseEntity<UserFinancialProfileDto.OwnedAccountsListJson> tryListAccounts(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(
                baseUrl + "/api/v1/user/owned-accounts",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                UserFinancialProfileDto.OwnedAccountsListJson.class
        );
    }

    public ResponseEntity<UserFinancialProfileDto.OwnedAccountJson> addAccount(
            UserFinancialProfileDto.AddOwnedAccountRequest request
    ) {
        return restTemplate.exchange(
                baseUrl + "/api/v1/user/owned-accounts",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()),
                UserFinancialProfileDto.OwnedAccountJson.class
        );
    }

    public ResponseEntity<ApiError> addAccountExpectingError(
            UserFinancialProfileDto.AddOwnedAccountRequest request
    ) {
        return restTemplate.exchange(
                baseUrl + "/api/v1/user/owned-accounts",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()),
                ApiError.class
        );
    }

    public ResponseEntity<Void> deleteAccount(String iban) {
        return restTemplate.exchange(
                baseUrl + "/api/v1/user/owned-accounts/" + iban,
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()),
                Void.class
        );
    }

    public ResponseEntity<ApiError> deleteAccountExpectingError(String iban) {
        return restTemplate.exchange(
                baseUrl + "/api/v1/user/owned-accounts/" + iban,
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders()),
                ApiError.class
        );
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwtToken != null) {
            headers.setBearerAuth(jwtToken);
        }
        return headers;
    }
}
