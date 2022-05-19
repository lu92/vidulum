package com.multi.vidulum.user.app;

import lombok.Builder;
import lombok.Data;

import java.util.List;

public class UserDto {

    @Data
    @Builder
    public static class CreateUserJson {
        private String username;
        private String password;
        private String email;
    }

    @Data
    @Builder
    public static class UserSummaryJson {
        private String userId;
        private String username;
        private String email;
        private boolean isActive;
        private List<String> portolioIds;
    }

    @Data
    @Builder
    public static class RegisterPortfolioJson {
        private String userId;
        private String name;
        private String broker;
        private String allowedDepositCurrency;
    }

    @Data
    @Builder
    public static class PortfolioRegistrationSummaryJson {
        private String userId;
        private String name;
        private String broker;
        private String portfolioId;
    }
}
