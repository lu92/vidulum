package com.multi.vidulum.security.auth;

import com.multi.vidulum.AbstractHttpIntegrationTest;
import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.common.error.FieldError;
import com.multi.vidulum.security.token.Token;
import com.multi.vidulum.security.token.TokenRepository;
import com.multi.vidulum.user.infrastructure.UserMongoRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class AuthenticationControllerTest extends AbstractHttpIntegrationTest {

    @Autowired
    private UserMongoRepository userMongoRepository;

    @Autowired
    private TokenRepository tokenRepository;

    private AuthenticationHttpActor actor;

    @BeforeEach
    void setUp() {
        // Clean up in correct order: tokens first, then users
        tokenRepository.deleteAll();
        userMongoRepository.deleteAll();
        actor = new AuthenticationHttpActor(restTemplate, port);
    }

    @Nested
    @DisplayName("Registration - Success")
    class RegistrationSuccess {

        @Test
        @DisplayName("Should register user successfully with all valid fields")
        void shouldRegisterUserSuccessfully() {
            // given
            String username = "testuser_" + UUID.randomUUID().toString().substring(0, 8);
            String email = username + "@test.com";

            // when
            ResponseEntity<AuthenticationResponse> response = actor.register(
                    username, email, "password123");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getUserId()).startsWith("U");
            assertThat(response.getBody().getAccessToken()).isNotBlank();
            assertThat(response.getBody().getRefreshToken()).isNotBlank();

            // Verify both tokens are stored in database
            List<Token> tokens = tokenRepository.findByUserId(response.getBody().getUserId());
            assertThat(tokens).hasSize(2);

            log.info("User registered successfully: username={}, userId={}", username, response.getBody().getUserId());
        }
    }

    @Nested
    @DisplayName("Registration - Validation Errors")
    class RegistrationValidation {

        @Test
        @DisplayName("Should reject registration with empty request body")
        void shouldRejectRegistrationWithEmptyRequest() {
            // when
            ResponseEntity<ApiError> response = actor.registerWithRawJson(Map.of());

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(400);
            assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(response.getBody().message()).isEqualTo("Request validation failed");
            assertThat(response.getBody().fieldErrors()).isNotNull();
            assertThat(response.getBody().fieldErrors()).extracting(FieldError::field)
                    .containsExactlyInAnyOrder("username", "email", "password");

            log.info("Empty request rejected with {} field errors", response.getBody().fieldErrors().size());
        }

        @Test
        @DisplayName("Should reject registration without username")
        void shouldRejectRegistrationWithoutUsername() {
            // when
            ResponseEntity<ApiError> response = actor.registerWithRawJson(Map.of(
                    "email", "test@example.com",
                    "password", "password123"
            ));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(response.getBody().fieldErrors())
                    .anyMatch(e -> "username".equals(e.field()) && "Username is required".equals(e.message()));
        }

        @Test
        @DisplayName("Should reject registration with username too short")
        void shouldRejectRegistrationWithUsernameTooShort() {
            // when
            ResponseEntity<ApiError> response = actor.registerExpectingError(
                    "ab", "test@example.com", "password123");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(response.getBody().fieldErrors())
                    .anyMatch(e -> "username".equals(e.field()) &&
                            e.message().contains("between 3 and 50 characters"));
        }

        @Test
        @DisplayName("Should reject registration with invalid username characters")
        void shouldRejectRegistrationWithInvalidUsernameCharacters() {
            // when
            ResponseEntity<ApiError> response = actor.registerExpectingError(
                    "user@name!", "test@example.com", "password123");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(response.getBody().fieldErrors())
                    .anyMatch(e -> "username".equals(e.field()) &&
                            e.message().contains("letters, numbers and underscores"));
        }

        @Test
        @DisplayName("Should reject registration without email")
        void shouldRejectRegistrationWithoutEmail() {
            // when
            ResponseEntity<ApiError> response = actor.registerWithRawJson(Map.of(
                    "username", "testuser123",
                    "password", "password123"
            ));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(response.getBody().fieldErrors())
                    .anyMatch(e -> "email".equals(e.field()) && "Email is required".equals(e.message()));
        }

        @Test
        @DisplayName("Should reject registration with invalid email format")
        void shouldRejectRegistrationWithInvalidEmailFormat() {
            // when
            ResponseEntity<ApiError> response = actor.registerExpectingError(
                    "testuser123", "invalid-email", "password123");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(response.getBody().fieldErrors())
                    .anyMatch(e -> "email".equals(e.field()) && "Invalid email format".equals(e.message()));
        }

        @Test
        @DisplayName("Should reject registration without password")
        void shouldRejectRegistrationWithoutPassword() {
            // when
            ResponseEntity<ApiError> response = actor.registerWithRawJson(Map.of(
                    "username", "testuser123",
                    "email", "test@example.com"
            ));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(response.getBody().fieldErrors())
                    .anyMatch(e -> "password".equals(e.field()) && "Password is required".equals(e.message()));
        }

        @Test
        @DisplayName("Should reject registration with password too short")
        void shouldRejectRegistrationWithPasswordTooShort() {
            // when
            ResponseEntity<ApiError> response = actor.registerExpectingError(
                    "testuser123", "test@example.com", "short");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(response.getBody().fieldErrors())
                    .anyMatch(e -> "password".equals(e.field()) &&
                            e.message().contains("between 8 and 100 characters"));
        }
    }

    @Nested
    @DisplayName("Registration - Business Errors")
    class RegistrationBusinessErrors {

        @Test
        @DisplayName("Should reject registration with existing email")
        void shouldRejectRegistrationWithExistingEmail() {
            // given - first registration
            String email = "duplicate_" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
            actor.register("firstuser123", email, "password123");

            // when - second registration with same email
            ResponseEntity<ApiError> response = actor.registerExpectingError(
                    "seconduser12", email, "password456");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(409);
            assertThat(response.getBody().code()).isEqualTo("AUTH_EMAIL_TAKEN");
            assertThat(response.getBody().message()).contains(email);
            assertThat(response.getBody().fieldErrors()).isNull();

            log.info("Duplicate email rejected: {}", email);
        }
    }

    @Nested
    @DisplayName("Authentication - Success")
    class AuthenticationSuccess {

        @Test
        @DisplayName("Should authenticate user successfully")
        void shouldAuthenticateUserSuccessfully() {
            // given
            String username = "authuser_" + UUID.randomUUID().toString().substring(0, 8);
            String email = username + "@test.com";
            String password = "password123";
            actor.register(username, email, password);

            // when
            ResponseEntity<AuthenticationResponse> response = actor.authenticate(username, password);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getUserId()).startsWith("U");
            assertThat(response.getBody().getAccessToken()).isNotBlank();
            assertThat(response.getBody().getRefreshToken()).isNotBlank();

            log.info("User authenticated successfully: username={}", username);
        }

        @Test
        @DisplayName("Should revoke old tokens on new authentication")
        void shouldRevokeOldTokensOnNewAuthentication() {
            // given
            String username = "revoketest_" + UUID.randomUUID().toString().substring(0, 8);
            String email = username + "@test.com";
            String password = "password123";
            AuthenticationResponse firstLogin = actor.register(username, email, password).getBody();
            String oldAccessToken = firstLogin.getAccessToken();

            // when - authenticate again
            actor.authenticate(username, password);

            // then - old token should be revoked
            Token oldToken = tokenRepository.findByToken(oldAccessToken).orElse(null);
            assertThat(oldToken).isNotNull();
            assertThat(oldToken.isRevoked()).isTrue();
            assertThat(oldToken.isExpired()).isTrue();
        }
    }

    @Nested
    @DisplayName("Authentication - Validation Errors")
    class AuthenticationValidation {

        @Test
        @DisplayName("Should reject authentication with empty request")
        void shouldRejectAuthenticationWithEmptyRequest() {
            // when
            ResponseEntity<ApiError> response = actor.authenticateWithRawJson(Map.of());

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(response.getBody().fieldErrors()).extracting(FieldError::field)
                    .containsExactlyInAnyOrder("username", "password");
        }
    }

    @Nested
    @DisplayName("Authentication - Business Errors")
    class AuthenticationBusinessErrors {

        @Test
        @DisplayName("Should reject authentication with invalid credentials")
        void shouldRejectAuthenticationWithInvalidCredentials() {
            // when
            ResponseEntity<String> response = actor.authenticateExpectingErrorRaw(
                    "nonexistent_user", "wrongpassword");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            log.info("Invalid credentials rejected with status: {}", response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("Logout - Success")
    class LogoutSuccess {

        @Test
        @DisplayName("Should logout successfully with valid access token")
        void shouldLogoutSuccessfully() {
            // given
            String username = "logoutuser_" + UUID.randomUUID().toString().substring(0, 8);
            AuthenticationResponse authResponse = actor.register(username, username + "@test.com", "password123").getBody();

            // when
            ResponseEntity<LogoutResponse> response = actor.logout(authResponse.getAccessToken());

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo("Successfully logged out");
            assertThat(response.getBody().userId()).isEqualTo(authResponse.getUserId());

            // Verify token is revoked in database
            Token storedToken = tokenRepository.findByToken(authResponse.getAccessToken()).orElse(null);
            assertThat(storedToken).isNotNull();
            assertThat(storedToken.isRevoked()).isTrue();
            assertThat(storedToken.isExpired()).isTrue();

            log.info("User logged out successfully: userId={}", authResponse.getUserId());
        }

        @Test
        @DisplayName("Should revoke all user tokens on logout (including refresh token)")
        void shouldRevokeAllTokensOnLogout() {
            // given
            String username = "logoutall_" + UUID.randomUUID().toString().substring(0, 8);
            AuthenticationResponse authResponse = actor.register(username, username + "@test.com", "password123").getBody();

            // when
            actor.logout(authResponse.getAccessToken());

            // then - both access and refresh tokens should be revoked
            List<Token> userTokens = tokenRepository.findByUserId(authResponse.getUserId());
            assertThat(userTokens).allMatch(Token::isRevoked);
            assertThat(userTokens).allMatch(Token::isExpired);
        }

        @Test
        @DisplayName("Should not be able to use access token after logout")
        void shouldNotBeAbleToUseAccessTokenAfterLogout() {
            // given
            String username = "logoutaccess_" + UUID.randomUUID().toString().substring(0, 8);
            AuthenticationResponse authResponse = actor.register(username, username + "@test.com", "password123").getBody();
            actor.logout(authResponse.getAccessToken());

            // when - verify the access token is marked as revoked in DB
            Token revokedToken = tokenRepository.findByToken(authResponse.getAccessToken()).orElse(null);

            // then
            assertThat(revokedToken).isNotNull();
            assertThat(revokedToken.isRevoked()).isTrue();
            assertThat(revokedToken.isExpired()).isTrue();
        }

        @Test
        @DisplayName("Should not be able to refresh token after logout")
        void shouldNotBeAbleToRefreshAfterLogout() {
            // given
            String username = "logoutrefresh_" + UUID.randomUUID().toString().substring(0, 8);
            AuthenticationResponse authResponse = actor.register(username, username + "@test.com", "password123").getBody();
            actor.logout(authResponse.getAccessToken());

            // when - try to refresh
            ResponseEntity<ApiError> response = actor.refreshTokenExpectingError(authResponse.getRefreshToken());

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().code()).isEqualTo("AUTH_TOKEN_REVOKED");
        }
    }

    @Nested
    @DisplayName("Logout - Validation Errors")
    class LogoutValidation {

        @Test
        @DisplayName("Should reject logout without Authorization header")
        void shouldRejectLogoutWithoutAuthHeader() {
            // when
            ResponseEntity<ApiError> response = actor.logoutWithoutToken();

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().code()).isEqualTo("AUTH_MISSING_TOKEN");
        }
    }

    @Nested
    @DisplayName("Logout - Business Errors")
    class LogoutBusinessErrors {

        @Test
        @DisplayName("Should reject logout with already revoked token")
        void shouldRejectLogoutWithRevokedToken() {
            // given
            String username = "revokedlogout_" + UUID.randomUUID().toString().substring(0, 8);
            AuthenticationResponse authResponse = actor.register(username, username + "@test.com", "password123").getBody();

            // First logout
            actor.logout(authResponse.getAccessToken());

            // when - try to logout again with same token
            ResponseEntity<ApiError> response = actor.logoutExpectingError(authResponse.getAccessToken());

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().code()).isEqualTo("AUTH_TOKEN_REVOKED");
        }
    }

    @Nested
    @DisplayName("Logout All Devices")
    class LogoutAllDevices {

        @Test
        @DisplayName("Should logout from all devices")
        void shouldLogoutFromAllDevices() {
            // given
            String username = "multidevice_" + UUID.randomUUID().toString().substring(0, 8);
            String email = username + "@test.com";
            String password = "password123";

            actor.register(username, email, password);
            AuthenticationResponse lastLogin = actor.authenticate(username, password).getBody();

            // when
            ResponseEntity<LogoutAllResponse> response = actor.logoutAll(lastLogin.getAccessToken());

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().revokedSessionsCount()).isGreaterThanOrEqualTo(2);

            // All tokens should be revoked
            List<Token> allTokens = tokenRepository.findByUserId(lastLogin.getUserId());
            assertThat(allTokens).allMatch(Token::isRevoked);

            log.info("Logged out from all devices: userId={}, revokedSessions={}",
                    lastLogin.getUserId(), response.getBody().revokedSessionsCount());
        }
    }

    @Nested
    @DisplayName("Refresh Token - Success")
    class RefreshTokenSuccess {

        @Test
        @DisplayName("Should refresh access token successfully")
        void shouldRefreshAccessToken() {
            // given
            String username = "refresh_" + UUID.randomUUID().toString().substring(0, 8);
            AuthenticationResponse authResponse = actor.register(username, username + "@test.com", "password123").getBody();
            String oldAccessToken = authResponse.getAccessToken();

            // when
            ResponseEntity<AuthenticationResponse> response = actor.refreshToken(authResponse.getRefreshToken());

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getAccessToken()).isNotBlank();
            assertThat(response.getBody().getAccessToken()).isNotEqualTo(oldAccessToken);
            assertThat(response.getBody().getRefreshToken()).isNotBlank();

            log.info("Token refreshed successfully for user: {}", username);
        }

        @Test
        @DisplayName("Should rotate refresh token on refresh")
        void shouldRotateRefreshToken() {
            // given
            String username = "rotate_" + UUID.randomUUID().toString().substring(0, 8);
            AuthenticationResponse authResponse = actor.register(username, username + "@test.com", "password123").getBody();
            String oldRefreshToken = authResponse.getRefreshToken();

            // when
            ResponseEntity<AuthenticationResponse> response = actor.refreshToken(authResponse.getRefreshToken());

            // then - new refresh token should be different
            assertThat(response.getBody().getRefreshToken()).isNotEqualTo(oldRefreshToken);

            // Old refresh token should be revoked
            Token oldToken = tokenRepository.findByToken(oldRefreshToken).orElse(null);
            assertThat(oldToken).isNotNull();
            assertThat(oldToken.isRevoked()).isTrue();
        }

        @Test
        @DisplayName("Should revoke old access token after refresh")
        void shouldRevokeOldAccessTokenAfterRefresh() {
            // given
            String username = "revokeold_" + UUID.randomUUID().toString().substring(0, 8);
            AuthenticationResponse authResponse = actor.register(username, username + "@test.com", "password123").getBody();
            String oldAccessToken = authResponse.getAccessToken();

            // when
            actor.refreshToken(authResponse.getRefreshToken());

            // then - old access token should be revoked
            Token oldToken = tokenRepository.findByToken(oldAccessToken).orElse(null);
            assertThat(oldToken).isNotNull();
            assertThat(oldToken.isRevoked()).isTrue();
        }
    }

    @Nested
    @DisplayName("Refresh Token - Errors")
    class RefreshTokenErrors {

        @Test
        @DisplayName("Should reject refresh with access token instead of refresh token")
        void shouldRejectRefreshWithAccessToken() {
            // given
            String username = "wrongtoken_" + UUID.randomUUID().toString().substring(0, 8);
            AuthenticationResponse authResponse = actor.register(username, username + "@test.com", "password123").getBody();

            // when - try to use access token for refresh
            ResponseEntity<ApiError> response = actor.refreshTokenExpectingError(authResponse.getAccessToken());

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().code()).isEqualTo("AUTH_TOKEN_INVALID");
        }

        @Test
        @DisplayName("Should reject refresh without Authorization header")
        void shouldRejectRefreshWithoutAuthHeader() {
            // when
            ResponseEntity<ApiError> response = actor.refreshTokenWithoutHeader();

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().code()).isEqualTo("AUTH_MISSING_TOKEN");
        }

        @Test
        @DisplayName("Should reject refresh with revoked refresh token")
        void shouldRejectRefreshWithRevokedToken() {
            // given
            String username = "revokedrefresh_" + UUID.randomUUID().toString().substring(0, 8);
            AuthenticationResponse authResponse = actor.register(username, username + "@test.com", "password123").getBody();

            // Logout (revokes all tokens including refresh)
            actor.logout(authResponse.getAccessToken());

            // when - try to use revoked refresh token
            ResponseEntity<ApiError> response = actor.refreshTokenExpectingError(authResponse.getRefreshToken());

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().code()).isEqualTo("AUTH_TOKEN_REVOKED");
        }
    }
}
