package com.multi.vidulum.user_financial_profile.api;

import jakarta.validation.constraints.NotBlank;

public record AddOwnedAccountRequest(
        @NotBlank(message = "iban is required") String iban,
        @NotBlank(message = "currency is required") String currency,
        @NotBlank(message = "bankName is required") String bankName,
        @NotBlank(message = "label is required") String label
) {}
