package com.multi.vidulum.user_financial_profile.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Contract for user-financial-profile REST API.
 *
 * <p>Implemented by the REST controller (server side) and used by
 * {@link org.springframework.web.client.support.RestClientAdapter HttpServiceProxyFactory}
 * to generate an HTTP client (consumer side).
 */
@HttpExchange("/api/v1/user/owned-accounts")
public interface UserFinancialProfileApi {

    @GetExchange
    OwnedAccountsListJson list();

    @PostExchange
    OwnedAccountJson add(@Valid @RequestBody AddOwnedAccountRequest request);

    @PostExchange("/bulk")
    BulkAddOwnedAccountsResponse addBulk(@Valid @RequestBody BulkAddOwnedAccountsRequest request);

    @GetExchange("/available-for-cashflow")
    OwnedAccountsListJson availableForCashFlow();

    @DeleteExchange("/{iban}")
    void delete(@PathVariable("iban") String iban);
}
