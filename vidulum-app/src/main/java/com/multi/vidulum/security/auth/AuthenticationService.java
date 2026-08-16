package com.multi.vidulum.security.auth;

import com.multi.vidulum.security.config.JwtService;
import com.multi.vidulum.security.token.Token;
import com.multi.vidulum.security.token.TokenRepository;
import com.multi.vidulum.security.token.TokenType;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.user.app.commands.register.RegisterUserCommand;
import com.multi.vidulum.user.domain.DomainUserRepository;
import com.multi.vidulum.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationService {
    private final DomainUserRepository userRepository;
    private final TokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final CommandGateway commandGateway;

    public AuthenticationResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyTakenException(request.getEmail());
        }

        RegisterUserCommand command = RegisterUserCommand.builder()
                .username(request.getUsername())
                .hashedPassword(passwordEncoder.encode(request.getPassword()))
                .email(request.getEmail())
                .build();

        User savedUser = commandGateway.send(command);

        var jwtToken = jwtService.generateToken(savedUser);
        var refreshToken = jwtService.generateRefreshToken(savedUser);

        saveUserToken(savedUser, jwtToken, TokenType.BEARER);
        saveUserToken(savedUser, refreshToken, TokenType.REFRESH);

        log.info("User registered: userId={}, username={}",
                savedUser.getUserId().getId(), savedUser.getUsername());

        return AuthenticationResponse.builder()
                .userId(savedUser.getUserId().getId())
                .accessToken(jwtToken)
                .refreshToken(refreshToken)
                .build();
    }

    public AuthenticationResponse authenticate(AuthenticationRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(),
                        request.getPassword()
                )
        );

        var user = userRepository.findByUsername(request.getUsername())
                .orElseThrow();

        var jwtToken = jwtService.generateToken(user);
        var refreshToken = jwtService.generateRefreshToken(user);

        revokeAllUserTokens(user.getUserId().getId());
        saveUserToken(user, jwtToken, TokenType.BEARER);
        saveUserToken(user, refreshToken, TokenType.REFRESH);

        log.info("User authenticated: userId={}, username={}",
                user.getUserId().getId(), user.getUsername());

        return AuthenticationResponse.builder()
                .userId(user.getUserId().getId())
                .accessToken(jwtToken)
                .refreshToken(refreshToken)
                .build();
    }

    /**
     * Logout user - revokes all tokens for the user.
     *
     * @param accessToken the access token from Authorization header
     * @return LogoutResponse with user info
     * @throws TokenNotFoundException if token not found in database
     * @throws TokenAlreadyRevokedException if token already revoked
     */
    public LogoutResponse logout(String accessToken) {
        var storedToken = tokenRepository.findByToken(accessToken)
                .orElseThrow(() -> new TokenNotFoundException(accessToken));

        if (storedToken.isRevoked() || storedToken.isExpired()) {
            throw new TokenAlreadyRevokedException(storedToken.getId());
        }

        String userId = storedToken.getUserId();
        int revokedCount = revokeAllUserTokens(userId);

        log.info("User logged out: userId={}, revokedTokens={}", userId, revokedCount);

        return LogoutResponse.success(userId);
    }

    /**
     * Logout from all devices - revokes all tokens for the user.
     *
     * @param accessToken the access token from Authorization header
     * @return LogoutAllResponse with count of revoked sessions
     */
    public LogoutAllResponse logoutAllDevices(String accessToken) {
        var storedToken = tokenRepository.findByToken(accessToken)
                .orElseThrow(() -> new TokenNotFoundException(accessToken));

        if (storedToken.isRevoked() || storedToken.isExpired()) {
            throw new TokenAlreadyRevokedException(storedToken.getId());
        }

        String userId = storedToken.getUserId();
        int revokedCount = revokeAllUserTokens(userId);

        log.warn("User logged out from ALL devices: userId={}, revokedTokens={}",
                userId, revokedCount);

        return LogoutAllResponse.success(userId, revokedCount);
    }

    /**
     * Refresh access token using refresh token.
     * Implements token rotation - old tokens are revoked, new ones are issued.
     */
    public AuthenticationResponse refreshToken(String refreshToken) {
        // Validate refresh token exists in database
        var storedRefreshToken = tokenRepository.findByToken(refreshToken)
                .orElseThrow(() -> new TokenNotFoundException(refreshToken));

        // Check if token is revoked
        if (storedRefreshToken.isRevoked() || storedRefreshToken.isExpired()) {
            throw new TokenAlreadyRevokedException(storedRefreshToken.getId());
        }

        // Verify it's a refresh token
        if (storedRefreshToken.getTokenType() != TokenType.REFRESH) {
            throw new InvalidTokenException("Expected refresh token, got access token");
        }

        // Extract username and validate JWT
        String username = jwtService.extractUsername(refreshToken);
        if (username == null) {
            throw new InvalidTokenException("Cannot extract username from token");
        }

        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new InvalidTokenException("User not found"));

        // Validate JWT signature and expiration
        if (!jwtService.isTokenValid(refreshToken, user.getUsername())) {
            throw new RefreshTokenExpiredException();
        }

        // Token rotation - generate new tokens
        var newAccessToken = jwtService.generateToken(user);
        var newRefreshToken = jwtService.generateRefreshToken(user);

        // Revoke all old tokens
        revokeAllUserTokens(user.getUserId().getId());

        // Save new tokens
        saveUserToken(user, newAccessToken, TokenType.BEARER);
        saveUserToken(user, newRefreshToken, TokenType.REFRESH);

        log.info("Token refreshed: userId={}, username={}",
                user.getUserId().getId(), user.getUsername());

        return AuthenticationResponse.builder()
                .userId(user.getUserId().getId())
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();
    }


    private void saveUserToken(User user, String token, TokenType tokenType) {
        var tokenEntity = Token.builder()
                .userId(user.getUserId().getId())
                .token(token)
                .tokenType(tokenType)
                .expired(false)
                .revoked(false)
                .createdAt(Instant.now())
                .build();
        tokenRepository.save(tokenEntity);
    }

    /**
     * Revokes all tokens for a user.
     *
     * @param userId the user ID
     * @return number of tokens revoked
     */
    private int revokeAllUserTokens(String userId) {
        List<Token> allUserTokens = tokenRepository.findByUserId(userId);
        if (allUserTokens.isEmpty()) {
            return 0;
        }

        int revokedCount = 0;
        for (Token token : allUserTokens) {
            if (!token.isRevoked()) {
                token.setExpired(true);
                token.setRevoked(true);
                revokedCount++;
            }
        }
        tokenRepository.saveAll(allUserTokens);
        return revokedCount;
    }

    /**
     * Extract token from Authorization header.
     *
     * @param authHeader the Authorization header value
     * @return the token string
     * @throws MissingAuthorizationHeaderException if header is missing or invalid
     */
    public String extractTokenFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new MissingAuthorizationHeaderException();
        }
        return authHeader.substring(7);
    }
}
