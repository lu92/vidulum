package com.multi.vidulum;

import com.multi.vidulum.security.auth.AuthenticationResponse;
import com.multi.vidulum.security.auth.RegisterRequest;
import com.multi.vidulum.trading.domain.IntegrationTest;
import com.multi.vidulum.user.app.UserDto;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class UserServiceTest extends IntegrationTest {

    @Test
    void shouldRegisterUser() {
        // given
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String username = "lu92_" + uniqueId;
        String email = uniqueId + "_lu92@email.com";

        // when
        RegisterRequest registerRequest = RegisterRequest.builder()
                .username(username)
                .password("secret12")
                .email(email)
                .build();

        AuthenticationResponse response = authenticationController.register(registerRequest).getBody();

        // then
        Assertions.assertThat(response.getUserId()).startsWith("U");
        Assertions.assertThat(response.getAccessToken()).isNotBlank();
        Assertions.assertThat(response.getRefreshToken()).isNotBlank();

        UserDto.UserSummaryJson persistedUser = userRestController.getUser(response.getUserId());

        UserDto.UserSummaryJson expectedUserSummary = UserDto.UserSummaryJson.builder()
                .userId(response.getUserId())
                .username(username)
                .email(email)
                .isActive(true)
                .portolioIds(List.of())
                .build();

        Assertions.assertThat(persistedUser).isEqualTo(expectedUserSummary);
    }

    @Test
    void shouldRegisterNewPortfolio() {
        // given
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String username = "lu92_" + uniqueId;
        String email = uniqueId + "_lu92@email.com";

        RegisterRequest registerRequest = RegisterRequest.builder()
                .username(username)
                .password("secret12")
                .email(email)
                .build();

        AuthenticationResponse response = authenticationController.register(registerRequest).getBody();
        String userId = response.getUserId();

        // when
        UserDto.PortfolioRegistrationSummaryJson portfolioRegistrationSummaryJson = userRestController.registerPortfolio(
                UserDto.RegisterPortfolioJson.builder()
                        .name("new portfolio")
                        .userId(userId)
                        .broker("BINANCE")
                        .build()
        );

        // then
        UserDto.UserSummaryJson persistedUser = userRestController.getUser(userId);

        UserDto.UserSummaryJson expectedUserSummary = UserDto.UserSummaryJson.builder()
                .userId(userId)
                .username(username)
                .email(email)
                .isActive(true)
                .portolioIds(List.of(portfolioRegistrationSummaryJson.getPortfolioId()))
                .build();

        Assertions.assertThat(persistedUser).isEqualTo(expectedUserSummary);
    }
}
