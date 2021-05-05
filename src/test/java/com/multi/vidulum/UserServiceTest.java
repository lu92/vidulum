package com.multi.vidulum;

import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.user.app.UserDto;
import com.multi.vidulum.user.app.UserNotActiveException;
import com.multi.vidulum.user.app.UserRestController;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

@SpringBootTest
@Import(PortfolioAppConfig.class)
@Testcontainers
@DirtiesContext
@EmbeddedKafka(partitions = 1, brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"})
class UserServiceTest {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.4.2");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired
    private UserRestController userRestController;

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
