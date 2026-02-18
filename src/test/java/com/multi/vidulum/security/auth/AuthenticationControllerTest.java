package com.multi.vidulum.security.auth;

import com.multi.vidulum.AbstractHttpIntegrationTest;
import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.common.error.FieldError;
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
        actor = new AuthenticationHttpActor(restTemplate, port);
        tokenRepository.deleteAll();
        userMongoRepository.deleteAll();
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

            // then - Our ErrorHttpHandler returns 401 UNAUTHORIZED for BadCredentialsException
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            log.info("Invalid credentials rejected with status: {}", response.getStatusCode());
        }
    }
}
