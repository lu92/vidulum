package com.multi.vidulum.user_financial_profile.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BulkAddOwnedAccountsRequest(
        @NotEmpty(message = "accounts list must not be empty")
        @Valid
        List<AddOwnedAccountRequest> accounts
) {}
