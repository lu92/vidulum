package com.multi.vidulum.gateway.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

@Slf4j
@Service
public class JwtService {

    private final SecretKey secretKey;

    public JwtService(@Value("${gateway.jwt.secret}") String secret) {
        // Use BASE64 decoding to match vidulum's JwtService
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String extractUserId(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(secretKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            // Check expiration
            if (claims.getExpiration() != null && claims.getExpiration().before(new Date())) {
                log.warn("Token expired");
                return null;
            }

            return claims.getSubject();

        } catch (Exception e) {
            log.warn("Failed to parse JWT: {}", e.getMessage());
            return null;
        }
    }

    public boolean isTokenValid(String token) {
        return extractUserId(token) != null;
    }
}
