package com.multi.vidulum.user_financial_profile;

import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.user_financial_profile.api.*;
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

    public OwnedAccountsListJson listAccounts() {
        ResponseEntity<OwnedAccountsListJson> response = restTemplate.exchange(
                baseUrl + "/api/v1/user/owned-accounts",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                OwnedAccountsListJson.class
        );
        return response.getBody();
    }

    public ResponseEntity<OwnedAccountsListJson> tryListAccounts(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(
                baseUrl + "/api/v1/user/owned-accounts",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                OwnedAccountsListJson.class
        );
    }

    public ResponseEntity<OwnedAccountJson> addAccount(
            AddOwnedAccountRequest request
    ) {
        return restTemplate.exchange(
                baseUrl + "/api/v1/user/owned-accounts",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()),
                OwnedAccountJson.class
        );
    }

    public ResponseEntity<ApiError> addAccountExpectingError(
            AddOwnedAccountRequest request
    ) {
        return restTemplate.exchange(
                baseUrl + "/api/v1/user/owned-accounts",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()),
                ApiError.class
        );
    }

    public ResponseEntity<BulkAddOwnedAccountsResponse> bulkAddAccounts(
            BulkAddOwnedAccountsRequest request
    ) {
        return restTemplate.exchange(
                baseUrl + "/api/v1/user/owned-accounts/bulk",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()),
                BulkAddOwnedAccountsResponse.class
        );
    }

    public ResponseEntity<ApiError> bulkAddAccountsExpectingError(
            BulkAddOwnedAccountsRequest request
    ) {
        return restTemplate.exchange(
                baseUrl + "/api/v1/user/owned-accounts/bulk",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()),
                ApiError.class
        );
    }

    public OwnedAccountsListJson availableForCashFlow() {
        ResponseEntity<OwnedAccountsListJson> response = restTemplate.exchange(
                baseUrl + "/api/v1/user/owned-accounts/available-for-cashflow",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                OwnedAccountsListJson.class
        );
        return response.getBody();
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
