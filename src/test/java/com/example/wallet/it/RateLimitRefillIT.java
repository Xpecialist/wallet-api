package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** Buckets refill by themselves (no permanent lockout) and the global per-IP cap. Short windows keep the test fast. */
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.login-ip.capacity=2", "app.rate-limit.login-ip.window=2s",
        "app.rate-limit.login-username.capacity=2", "app.rate-limit.login-username.window=2s",
        "app.rate-limit.global-ip.capacity=6", "app.rate-limit.global-ip.window=1m"})
class RateLimitRefillIT extends RateLimitBase {

    @Test
    void aBlockedClientIsLetInAgainAfterTheBucketRefills() throws Exception {
        register("alice");
        limits.clear();
        assertThat(status(loginFrom("10.20.0.1", "alice", "wrong-Passw0rd!"))).isEqualTo(401);
        assertThat(status(loginFrom("10.20.0.1", "alice", "wrong-Passw0rd!"))).isEqualTo(401);
        MvcResult blocked = loginFrom("10.20.0.1", "alice", PASSWORD);
        assertThat(status(blocked)).isEqualTo(429);
        long retryAfter = Long.parseLong(blocked.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 2L);

        Thread.sleep(retryAfter * 1000 + 300);
        MvcResult after = loginFrom("10.20.0.1", "alice", PASSWORD);
        assertThat(status(after)).as("valid login after the window").isEqualTo(200);
        assertThat(body(after).get("token").asString()).isNotBlank();
    }

    @Test
    void theGlobalPerIpCapCoversEverythingUnderApiEvenUnauthenticatedRequests() throws Exception {
        String alice = register("alice");
        limits.clear();
        for (int i = 0; i < 6; i++) assertThat(status(getFrom("10.21.0.1", "/api/wallets/me", alice))).isEqualTo(200);
        MvcResult blocked = getFrom("10.21.0.1", "/api/wallets/me", alice);
        assertThat(status(blocked)).isEqualTo(429);
        assertThat(blocked.getResponse().getHeader("Retry-After")).isNotBlank();
        assertThat(status(getFrom("10.21.0.1", "/api/wallets/me", null))).as("before authentication").isEqualTo(429);
        assertThat(status(getFrom("10.21.0.2", "/api/wallets/me", alice))).as("other IP").isEqualTo(200);
    }
}
