package com.multi.vidulum.user_financial_profile.app;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import com.multi.vidulum.user_financial_profile.api.AddOwnedAccountRequest;
import com.multi.vidulum.user_financial_profile.api.BulkAddOwnedAccountsRequest;
import com.multi.vidulum.user_financial_profile.api.BulkAddOwnedAccountsResponse;
import com.multi.vidulum.user_financial_profile.api.OwnedAccountJson;
import com.multi.vidulum.user_financial_profile.api.OwnedAccountsListJson;
import com.multi.vidulum.user_financial_profile.api.UserFinancialProfileApi;
import com.multi.vidulum.user_financial_profile.domain.AccountSource;
import com.multi.vidulum.user_financial_profile.domain.OwnedBankAccount;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@AllArgsConstructor
public class UserFinancialProfileRestController implements UserFinancialProfileApi {

    private final UserFinancialProfileService service;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    @Override
    public OwnedAccountsListJson list() {
        UserId userId = authenticatedUserProvider.getCurrentUserId();
        return new OwnedAccountsListJson(
                userId.getId(),
                mapAccounts(service.listAccounts(userId))
        );
    }

    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public OwnedAccountJson add(AddOwnedAccountRequest request) {
        UserId userId = authenticatedUserProvider.getCurrentUserId();
        OwnedBankAccount account = service.addAccount(
                userId,
                request.iban(),
                request.currency(),
                request.bankName(),
                request.label(),
                AccountSource.MANUAL,
                null
        );
        return mapAccount(account);
    }

    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public BulkAddOwnedAccountsResponse addBulk(BulkAddOwnedAccountsRequest request) {
        UserId userId = authenticatedUserProvider.getCurrentUserId();
        List<UserFinancialProfileService.BulkAccountRequest> bulkReqs = request.accounts().stream()
                .map(r -> new UserFinancialProfileService.BulkAccountRequest(
                        r.iban(), r.currency(), r.bankName(), r.label()))
                .toList();
        List<OwnedBankAccount> added = service.addAccounts(userId, bulkReqs, AccountSource.ONBOARDING);
        return new BulkAddOwnedAccountsResponse(mapAccounts(added));
    }

    @Override
    public OwnedAccountsListJson availableForCashFlow() {
        UserId userId = authenticatedUserProvider.getCurrentUserId();
        return new OwnedAccountsListJson(
                userId.getId(),
                mapAccounts(service.listAccountsAvailableForCashFlow(userId))
        );
    }

    @Override
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(String iban) {
        UserId userId = authenticatedUserProvider.getCurrentUserId();
        service.removeAccount(userId, iban);
    }

    private List<OwnedAccountJson> mapAccounts(List<OwnedBankAccount> accounts) {
        return accounts.stream().map(this::mapAccount).toList();
    }

    private OwnedAccountJson mapAccount(OwnedBankAccount account) {
        return new OwnedAccountJson(
                account.bankAccountNumber().fetchRawIban(),
                account.bankAccountNumber().denomination().getId(),
                account.bankName() != null ? account.bankName().name() : null,
                account.label(),
                account.status().name(),
                account.source().name(),
                account.linkedCashFlowId() != null ? account.linkedCashFlowId().id() : null,
                account.addedAt(),
                account.closedAt()
        );
    }
}
