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
 * username as subject, the user's token version ({@code tv}, see {@link JwtAuthFilter}) plus issued-at and expiry times.
 */
@Service
public class JwtService {
    /** Name of the claim that holds the user's token version. */
    static final String VERSION_CLAIM = "tv";

    private final SecretKey key;
    private final long expirationMs;

    /**
     * Builds the signing key from configuration.
     *
     * @param secret       Base64-encoded secret; must decode to at least 32 bytes
     * @param expirationMs token lifetime in milliseconds
     * @throws MissingJwtSecretException                  if the secret is null or blank (there is no default)
     * @throws io.jsonwebtoken.security.WeakKeyException if the decoded secret is too short
     */
    public JwtService(@Value("${app.jwt.secret:}") String secret,
                      @Value("${app.jwt.expiration-ms}") long expirationMs) {
        if (secret == null || secret.isBlank()) throw new MissingJwtSecretException();
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expirationMs = expirationMs;
    }

    /**
     * What the filter needs from a verified token.
     *
     * @param username     the subject
     * @param tokenVersion the {@code tv} claim, or {@code null} if it is missing or not a number
     */
    public record TokenClaims(String username, Long tokenVersion) { }

    /**
     * Creates a signed token for a user, valid from now until now plus the configured lifetime. The token carries
     * the user's current token version in the {@code tv} claim; it stops being accepted as soon as the stored
     * version changes.
     *
     * @param username     the username to store as the token subject
     * @param tokenVersion the user's current {@code users.token_version}
     * @return the compact serialized JWT
     */
    public String generateToken(String username, long tokenVersion) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim(VERSION_CLAIM, tokenVersion)
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
        return parse(token).username();
    }

    /**
     * Verifies the token's signature and expiry and returns its subject and token version claim.
     *
     * @param token the compact serialized JWT (without the {@code Bearer } prefix)
     * @return the subject and the {@code tv} claim (null if absent: such tokens predate revocation)
     * @throws io.jsonwebtoken.JwtException if the token is malformed, expired, or has an invalid signature
     * @throws IllegalArgumentException     if the token is null or empty
     */
    public TokenClaims parse(String token) {
        var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        Object tv = claims.get(VERSION_CLAIM);
        return new TokenClaims(claims.getSubject(), tv instanceof Number n ? n.longValue() : null);
    }
}
