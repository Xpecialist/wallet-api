package com.example.wallet.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Creates and validates JSON Web Tokens. Tokens are signed with an HMAC key derived from the Base64-encoded
 * {@code app.jwt.secret} property (the algorithm is chosen by jjwt from the key length) and carry only the
 * username as subject plus issued-at and expiry times.
 */
@Service
public class JwtService {
    private final SecretKey key;
    private final long expirationMs;

    /**
     * Builds the signing key from configuration.
     *
     * @param secret       Base64-encoded secret; must decode to at least 32 bytes
     * @param expirationMs token lifetime in milliseconds
     * @throws io.jsonwebtoken.security.WeakKeyException if the decoded secret is too short
     */
    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expirationMs = expirationMs;
    }

    /**
     * Creates a signed token for a user, valid from now until now plus the configured lifetime.
     *
     * @param username the username to store as the token subject
     * @return the compact serialized JWT
     */
    public String generateToken(String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies the token's signature and expiry and returns its subject.
     *
     * @param token the compact serialized JWT (without the {@code Bearer } prefix)
     * @return the username stored as the token subject
     * @throws io.jsonwebtoken.JwtException if the token is malformed, expired, or has an invalid signature
     * @throws IllegalArgumentException     if the token is null or empty
     */
    public String extractUsername(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
    }
}
