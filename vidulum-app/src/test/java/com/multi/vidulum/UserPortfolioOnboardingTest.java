package com.multi.vidulum;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.trading.domain.IntegrationTest;
import com.multi.vidulum.user.app.UserDto;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the cross-module onboarding flow: register user → activate → create portfolio → deposit.
 * Verifies integration between user domain (vidulum-app) and wealth domain (vidulum-wealth).
 */
@Slf4j
class UserPortfolioOnboardingTest extends IntegrationTest {

    @Test
    void shouldRegisterUserActivateAndCreatePortfolioWithDeposit() {
        // given — setup quotation for USD (needed for portfolio valuation)
        quoteRestController.changePrice("BINANCE", "USD", "USD", 1, "USD", 0);

        // given — register user
        UserDto.UserSummaryJson createdUser = createUser("onboarding_user", "secret12", "onboarding@email.com");
        assertThat(createdUser.getUserId()).startsWith("U");

        // when — activate
        activateUser(createdUser.getUserId());

        // then — user is active with no portfolios
        UserDto.UserSummaryJson activeUser = userRestController.getUser(createdUser.getUserId());
        assertThat(activeUser.isActive()).isTrue();
        assertThat(activeUser.getPortfolioIds()).isEmpty();

        // when — register portfolio
        UserDto.PortfolioRegistrationSummaryJson registeredPortfolio =
                registerPortfolio("My Portfolio", "BINANCE", createdUser.getUserId(), "USD");
        assertThat(registeredPortfolio.getPortfolioId()).isNotBlank();

        // then — user has portfolio assigned
        UserDto.UserSummaryJson userWithPortfolio = userRestController.getUser(createdUser.getUserId());
        assertThat(userWithPortfolio.getPortfolioIds())
                .containsExactly(registeredPortfolio.getPortfolioId());

        // when — deposit money
        PortfolioId portfolioId = PortfolioId.of(registeredPortfolio.getPortfolioId());
        depositMoney(portfolioId, Money.of(50000, "USD"));

        // then — portfolio has correct balance
        PortfolioDto.PortfolioSummaryJson portfolio =
                portfolioRestController.getPortfolio(registeredPortfolio.getPortfolioId(), "USD");
        assertThat(portfolio.getAssets()).hasSize(1);
        assertThat(portfolio.getAssets().get(0).getTicker()).isEqualTo("USD");
        assertThat(portfolio.getAssets().get(0).getQuantity()).isEqualTo(Quantity.of(50000));
        assertThat(portfolio.getAssets().get(0).getFree()).isEqualTo(Quantity.of(50000));
        assertThat(portfolio.getAssets().get(0).getLocked()).isEqualTo(Quantity.zero());
        assertThat(portfolio.getInvestedBalance()).isEqualTo(Money.of(50000, "USD"));
    }
}
