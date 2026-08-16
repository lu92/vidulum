package com.multi.vidulum.security.auth;

import com.multi.vidulum.common.auth.AuthenticatableUser;
import com.multi.vidulum.common.auth.AuthenticatableUserRepository;
import com.multi.vidulum.common.auth.RegisterUserCommand;
import com.multi.vidulum.security.config.JwtService;
import com.multi.vidulum.security.token.Token;
import com.multi.vidulum.security.token.TokenRepository;
import com.multi.vidulum.security.token.TokenType;
import com.multi.vidulum.shared.cqrs.CommandGateway;
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
    private final AuthenticatableUserRepository userRepository;
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

        AuthenticatableUser savedUser = commandGateway.send(command);

        var jwtToken = jwtService.generateToken(savedUser);
        var refreshToken = jwtService.generateRefreshToken(savedUser);

        saveUserToken(savedUser, jwtToken, TokenType.BEARER);
        saveUserToken(savedUser, refreshToken, TokenType.REFRESH);

        log.info("User registered: userId={}, username={}",
                savedUser.getAuthUserId(), savedUser.getUsername());

        return AuthenticationResponse.builder()
                .userId(savedUser.getAuthUserId())
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

        revokeAllUserTokens(user.getAuthUserId());
        saveUserToken(user, jwtToken, TokenType.BEARER);
        saveUserToken(user, refreshToken, TokenType.REFRESH);

        log.info("User authenticated: userId={}, username={}",
                user.getAuthUserId(), user.getUsername());

        return AuthenticationResponse.builder()
                .userId(user.getAuthUserId())
                .accessToken(jwtToken)
                .refreshToken(refreshToken)
                .build();
    }

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

    public AuthenticationResponse refreshToken(String refreshToken) {
        var storedRefreshToken = tokenRepository.findByToken(refreshToken)
                .orElseThrow(() -> new TokenNotFoundException(refreshToken));

        if (storedRefreshToken.isRevoked() || storedRefreshToken.isExpired()) {
            throw new TokenAlreadyRevokedException(storedRefreshToken.getId());
        }

        if (storedRefreshToken.getTokenType() != TokenType.REFRESH) {
            throw new InvalidTokenException("Expected refresh token, got access token");
        }

        String username = jwtService.extractUsername(refreshToken);
        if (username == null) {
            throw new InvalidTokenException("Cannot extract username from token");
        }

        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new InvalidTokenException("User not found"));

        if (!jwtService.isTokenValid(refreshToken, user.getUsername())) {
            throw new RefreshTokenExpiredException();
        }

        var newAccessToken = jwtService.generateToken(user);
        var newRefreshToken = jwtService.generateRefreshToken(user);

        revokeAllUserTokens(user.getAuthUserId());

        saveUserToken(user, newAccessToken, TokenType.BEARER);
        saveUserToken(user, newRefreshToken, TokenType.REFRESH);

        log.info("Token refreshed: userId={}, username={}",
                user.getAuthUserId(), user.getUsername());

        return AuthenticationResponse.builder()
                .userId(user.getAuthUserId())
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();
    }

    private void saveUserToken(AuthenticatableUser user, String token, TokenType tokenType) {
        var tokenEntity = Token.builder()
                .userId(user.getAuthUserId())
                .token(token)
                .tokenType(tokenType)
                .expired(false)
                .revoked(false)
                .createdAt(Instant.now())
                .build();
        tokenRepository.save(tokenEntity);
    }

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

    public String extractTokenFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new MissingAuthorizationHeaderException();
        }
        return authHeader.substring(7);
    }
}
