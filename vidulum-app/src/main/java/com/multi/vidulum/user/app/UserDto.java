package com.multi.vidulum.user.app;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class UserDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserSummaryJson {
        private String userId;
        private String username;
        private String email;
        private boolean isActive;
        private List<String> portfolioIds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegisterPortfolioJson {
        private String userId;
        private String name;
        private String broker;
        private String allowedDepositCurrency;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PortfolioRegistrationSummaryJson {
        private String userId;
        private String name;
        private String broker;
        private String portfolioId;
        private String allowedDepositCurrency;
    }
}
