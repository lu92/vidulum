package com.multi.vidulum.user_financial_profile.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.multi.vidulum.user_financial_profile.domain.OwnedBankAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class UserFinancialProfileDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddOwnedAccountRequest {
        @NotBlank(message = "iban is required")
        private String iban;
        @NotBlank(message = "currency is required")
        private String currency;
        @NotBlank(message = "bankName is required")
        private String bankName;
        @NotBlank(message = "label is required")
        private String label;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class OwnedAccountJson {
        private String iban;
        private String currency;
        private String bankName;
        private String label;
        private String status;
        private String source;
        private String linkedCashFlowId;
        private ZonedDateTime addedAt;
        private ZonedDateTime closedAt;

        public static OwnedAccountJson from(OwnedBankAccount account) {
            return OwnedAccountJson.builder()
                    .iban(account.bankAccountNumber().fetchRawIban())
                    .currency(account.bankAccountNumber().denomination().getId())
                    .bankName(account.bankName() != null ? account.bankName().name() : null)
                    .label(account.label())
                    .status(account.status().name())
                    .source(account.source().name())
                    .linkedCashFlowId(account.linkedCashFlowId() != null ? account.linkedCashFlowId().id() : null)
                    .addedAt(account.addedAt())
                    .closedAt(account.closedAt())
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkAddOwnedAccountsRequest {
        @NotEmpty(message = "accounts list must not be empty")
        @Valid
        private List<AddOwnedAccountRequest> accounts;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkAddOwnedAccountsResponse {
        private List<OwnedAccountJson> added;

        public static BulkAddOwnedAccountsResponse of(List<OwnedBankAccount> accounts) {
            return BulkAddOwnedAccountsResponse.builder()
                    .added(accounts.stream().map(OwnedAccountJson::from).collect(Collectors.toList()))
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OwnedAccountsListJson {
        private String userId;
        private List<OwnedAccountJson> accounts;

        public static OwnedAccountsListJson of(String userId, List<OwnedBankAccount> accounts) {
            return OwnedAccountsListJson.builder()
                    .userId(userId)
                    .accounts(accounts.stream().map(OwnedAccountJson::from).collect(Collectors.toList()))
                    .build();
        }
    }
}
