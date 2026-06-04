package com.multi.vidulum.user_financial_profile.app;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.security.config.JwtService;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import com.multi.vidulum.user.app.queries.GetUserByUsernameQuery;
import com.multi.vidulum.user.domain.User;
import com.multi.vidulum.user_financial_profile.domain.AccountSource;
import com.multi.vidulum.user_financial_profile.domain.OwnedBankAccount;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/user/owned-accounts")
@AllArgsConstructor
public class UserFinancialProfileRestController {

    private final UserFinancialProfileService service;
    private final JwtService jwtService;
    private final QueryGateway queryGateway;

    @GetMapping
    public UserFinancialProfileDto.OwnedAccountsListJson list(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader
    ) {
        UserId userId = resolveUserIdFromToken(authHeader);
        return UserFinancialProfileDto.OwnedAccountsListJson.of(
                userId.getId(),
                service.listAccounts(userId)
        );
    }

    @PostMapping
    public ResponseEntity<UserFinancialProfileDto.OwnedAccountJson> add(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @Valid @RequestBody UserFinancialProfileDto.AddOwnedAccountRequest request
    ) {
        UserId userId = resolveUserIdFromToken(authHeader);
        OwnedBankAccount account = service.addAccount(
                userId,
                request.getIban(),
                request.getCurrency(),
                request.getBankName(),
                request.getLabel(),
                AccountSource.MANUAL,
                null
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(UserFinancialProfileDto.OwnedAccountJson.from(account));
    }

    @PostMapping("/bulk")
    public ResponseEntity<UserFinancialProfileDto.BulkAddOwnedAccountsResponse> addBulk(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @Valid @RequestBody UserFinancialProfileDto.BulkAddOwnedAccountsRequest request
    ) {
        UserId userId = resolveUserIdFromToken(authHeader);
        List<UserFinancialProfileService.BulkAccountRequest> bulkReqs = request.getAccounts().stream()
                .map(r -> new UserFinancialProfileService.BulkAccountRequest(
                        r.getIban(), r.getCurrency(), r.getBankName(), r.getLabel()))
                .toList();
        List<OwnedBankAccount> added = service.addAccounts(userId, bulkReqs, AccountSource.ONBOARDING);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(UserFinancialProfileDto.BulkAddOwnedAccountsResponse.of(added));
    }

    @GetMapping("/available-for-cashflow")
    public UserFinancialProfileDto.OwnedAccountsListJson availableForCashFlow(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader
    ) {
        UserId userId = resolveUserIdFromToken(authHeader);
        return UserFinancialProfileDto.OwnedAccountsListJson.of(
                userId.getId(),
                service.listAccountsAvailableForCashFlow(userId)
        );
    }

    @DeleteMapping("/{iban}")
    public ResponseEntity<Void> delete(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable("iban") String iban
    ) {
        UserId userId = resolveUserIdFromToken(authHeader);
        service.removeAccount(userId, iban);
        return ResponseEntity.noContent().build();
    }

    private UserId resolveUserIdFromToken(String authHeader) {
        String jwt = authHeader.substring(7);
        String username = jwtService.extractUsername(jwt);
        User user = queryGateway.send(GetUserByUsernameQuery.of(username));
        return user.getUserId();
    }
}
