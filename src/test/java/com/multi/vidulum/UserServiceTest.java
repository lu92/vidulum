package com.multi.vidulum;

import com.multi.vidulum.trading.domain.IntegrationTest;
import com.multi.vidulum.user.app.UserDto;
import com.multi.vidulum.user.app.UserNotActiveException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class UserServiceTest extends IntegrationTest {

    @Test
    void shouldCreateUser() {

        // when
        UserDto.UserSummaryJson createdUserJson = userRestController.createUser(
                UserDto.CreateUserJson.builder()
                        .username("lu92")
                        .password("secret")
                        .email("lu92@email.com")
                        .build());

        // then
        UserDto.UserSummaryJson persistedUser = userRestController.getUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson expectedUserSummary = UserDto.UserSummaryJson.builder()
                .userId(persistedUser.getUserId())
                .username(persistedUser.getUsername())
                .email(persistedUser.getEmail())
                .isActive(false)
                .portolioIds(List.of())
                .build();

        Assertions.assertThat(persistedUser).isEqualTo(expectedUserSummary);
    }

    @Test
    void shouldActivateUser() {

        // given
        UserDto.UserSummaryJson createdUserJson = userRestController.createUser(
                UserDto.CreateUserJson.builder()
                        .username("lu92")
                        .password("secret")
                        .email("lu92@email.com")
                        .build());

        // when
        userRestController.activateUser(createdUserJson.getUserId());

        // then
        UserDto.UserSummaryJson persistedUser = userRestController.getUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson expectedUserSummary = UserDto.UserSummaryJson.builder()
                .userId(persistedUser.getUserId())
                .username(persistedUser.getUsername())
                .email(persistedUser.getEmail())
                .isActive(true)
                .portolioIds(List.of())
                .build();

        Assertions.assertThat(persistedUser).isEqualTo(expectedUserSummary);
    }

    @Test
    void shouldRegisterNewPortfolio() {

        // given
        UserDto.UserSummaryJson createdUserJson = userRestController.createUser(
                UserDto.CreateUserJson.builder()
                        .username("lu92")
                        .password("secret")
                        .email("lu92@email.com")
                        .build());

        userRestController.activateUser(createdUserJson.getUserId());

        UserDto.PortfolioRegistrationSummaryJson portfolioRegistrationSummaryJson = userRestController.registerPortfolio(
                UserDto.RegisterPortfolioJson.builder()
                        .name("new portfolio")
                        .userId(createdUserJson.getUserId())
                        .broker("BINANCE")
                        .build()
        );

        // then
        UserDto.UserSummaryJson persistedUser = userRestController.getUser(createdUserJson.getUserId());

        UserDto.UserSummaryJson expectedUserSummary = UserDto.UserSummaryJson.builder()
                .userId(persistedUser.getUserId())
                .username(persistedUser.getUsername())
                .email(persistedUser.getEmail())
                .isActive(true)
                .portolioIds(List.of(portfolioRegistrationSummaryJson.getPortfolioId()))
                .build();

        Assertions.assertThat(persistedUser).isEqualTo(expectedUserSummary);
    }

    @Test
    void tryToRegisterPortfolioForInactiveUser_errorExpected() {

        // given
        UserDto.UserSummaryJson createdUserJson = userRestController.createUser(
                UserDto.CreateUserJson.builder()
                        .username("lu92")
                        .password("secret")
                        .email("lu92@email.com")
                        .build());

        // when and then
        Assertions.assertThatThrownBy(() -> userRestController.registerPortfolio(
                UserDto.RegisterPortfolioJson.builder()
                        .name("new portfolio")
                        .userId(createdUserJson.getUserId())
                        .broker("BINANCE")
                        .build())).isInstanceOf(UserNotActiveException.class);
    }
}
