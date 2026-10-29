package com.payshield.security;

import com.payshield.config.AppProperties;
import com.payshield.domain.Role;
import com.payshield.domain.UserAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtTokenService {

    private final AppProperties properties;
    private final SecretKey signingKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public JwtTokenService(AppProperties properties) {
        this.properties = properties;
        String secret = properties.getSecurity().getJwtSecret();
        if (secret == null || secret.trim().length() < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 characters (256 bits)");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(UserAccount user) {
        return generateAccessToken(user, properties.getSecurity().getAccessTokenTtlSeconds());
    }

    public String generateAccessToken(UserAccount user, long ttlSeconds) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(ttlSeconds);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("name", user.getFullName())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    public Claims parseAndValidateAccessToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public AuthenticatedUser toAuthenticatedUser(Claims claims, boolean cookieAuthenticated) {
        UUID userId = UUID.fromString(claims.getSubject());
        String email = claims.get("email", String.class);
        String name = claims.get("name", String.class);
        Role role = Role.valueOf(claims.get("role", String.class));
        return new AuthenticatedUser(userId, email, name, role, cookieAuthenticated);
    }

    public String generateSecureOpaqueToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
