package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The master switch: with {@code app.rate-limit.enabled=false} nothing is limited, however small the limits are. */
@TestPropertySource(properties = {
        "app.rate-limit.enabled=false",
        "app.rate-limit.login-ip.capacity=1", "app.rate-limit.login-username-ip.capacity=1",
        "app.rate-limit.register-ip.capacity=1", "app.rate-limit.global-ip.capacity=1"})
class RateLimitDisabledIT extends RateLimitBase {

    @Test
    void nothingIsLimitedWhenTheSwitchIsOff() throws Exception {
        for (int i = 0; i < 15; i++) assertThat(status(loginFrom("10.30.0.1", "alice", "wrong-Passw0rd!"))).isEqualTo(401);
        for (int i = 0; i < 4; i++) {
            assertThat(status(postFrom("10.30.0.1", "/api/auth/register", null,
                    map("username", "bulk" + i, "email", "bulk" + i + "@example.com", "password", PASSWORD))))
                    .isEqualTo(201);
        }
        String token = login("bulk0", PASSWORD);
        for (int i = 0; i < 10; i++) assertThat(status(getFrom("10.30.0.1", "/api/wallets/me", token))).isEqualTo(200);
    }
}
