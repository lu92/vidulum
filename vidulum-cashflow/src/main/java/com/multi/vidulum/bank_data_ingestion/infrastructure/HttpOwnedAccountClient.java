package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.bank_data_ingestion.app.OwnedAccountClient;
import com.multi.vidulum.bank_data_ingestion.app.OwnedAccountRegistry;
import com.multi.vidulum.common.BankAccountId;
import com.multi.vidulum.common.UserId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HTTP implementation of OwnedAccountClient.
 * Calls the user-financial-profile REST API to load owned bank accounts.
 */
@Slf4j
public class HttpOwnedAccountClient implements OwnedAccountClient {

    private final RestClient restClient;

    public HttpOwnedAccountClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public OwnedAccountRegistry loadForUser(UserId userId) {
        try {
            OwnedAccountsResponse response = restClient.get()
                    .uri("/api/v1/user/owned-accounts")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(OwnedAccountsResponse.class);

            if (response == null || response.accounts == null) {
                log.debug("No owned accounts found for user [{}]", userId.getId());
                return OwnedAccountRegistry.EMPTY;
            }

            Set<BankAccountId> ownedIbans = response.accounts.stream()
                    .map(a -> new BankAccountId(a.iban))
                    .collect(Collectors.toSet());

            log.debug("Loaded {} owned accounts for user [{}]", ownedIbans.size(), userId.getId());
            return new OwnedAccountRegistry(ownedIbans);
        } catch (Exception e) {
            log.warn("Failed to load owned accounts for user [{}]: {}", userId.getId(), e.getMessage());
            return OwnedAccountRegistry.EMPTY;
        }
    }

    // DTOs for JSON deserialization — mirrors UserFinancialProfileDto.OwnedAccountsListJson
    record OwnedAccountsResponse(String userId, List<OwnedAccountResponse> accounts) {}
    record OwnedAccountResponse(String iban) {}
}
