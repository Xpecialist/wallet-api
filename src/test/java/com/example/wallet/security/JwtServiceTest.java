package com.example.wallet.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class JwtServiceTest {
    static final String SECRET = Base64.getEncoder().encodeToString("0123456789-0123456789-0123456789-AB".getBytes());
    static final String OTHER_SECRET = Base64.getEncoder().encodeToString("ZYXWVUTSRQ-ZYXWVUTSRQ-ZYXWVUTSRQ-ab".getBytes());

    JwtService jwt = new JwtService(SECRET, 60_000);

    @Test
    void roundTripReturnsSubject() {
        assertThat(jwt.extractUsername(jwt.generateToken("alice"))).isEqualTo("alice");
    }

    @Test
    void tokenHasIssuedAtAndExpiryMatchingConfiguredLifetime() {
        String token = jwt.generateToken("alice");
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(60_000);
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService shortLived = new JwtService(SECRET, -1_000);
        String token = shortLived.generateToken("alice");
        assertThatThrownBy(() -> jwt.extractUsername(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tokenSignedWithDifferentKeyIsRejected() {
        String token = new JwtService(OTHER_SECRET, 60_000).generateToken("alice");
        assertThatThrownBy(() -> jwt.extractUsername(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = jwt.generateToken("alice").split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"admin\",\"exp\":9999999999}".getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];
        assertThatThrownBy(() -> jwt.extractUsername(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void unsignedTokenIsRejected() {
        String unsigned = Jwts.builder().subject("alice").expiration(new Date(System.currentTimeMillis() + 60_000)).compact();
        assertThatThrownBy(() -> jwt.extractUsername(unsigned)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageNullAndEmptyTokensAreRejected() {
        assertThatThrownBy(() -> jwt.extractUsername("not-a-jwt")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwt.extractUsername(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwt.extractUsername("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secretShorterThan32BytesIsRejectedAtConstruction() {
        String weak = Base64.getEncoder().encodeToString("short".getBytes());
        assertThatThrownBy(() -> new JwtService(weak, 1000)).isInstanceOf(WeakKeyException.class);
    }
}
