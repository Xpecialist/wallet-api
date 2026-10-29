package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** With {@code trust-forwarded-headers=true} the client is the right-most X-Forwarded-For entry (one trusted proxy). */
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true", "app.rate-limit.trust-forwarded-headers=true",
        "app.rate-limit.trusted-proxies=1",
        "app.rate-limit.login-ip.capacity=3", "app.rate-limit.login-ip.window=1m",
        "app.rate-limit.login-username-ip.capacity=100", "app.rate-limit.global-ip.capacity=1000"})
class RateLimitTrustedProxyIT extends RateLimitBase {
    private static final String PROXY = "10.40.0.1";

    @Test
    void clientsBehindTheProxyGetTheirOwnBuckets() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(status(loginFrom(PROXY, "u" + i, "x-Passw0rd!", "203.0.113.1"))).isEqualTo(401);
        }
        assertThat(status(loginFrom(PROXY, "u4", "x-Passw0rd!", "203.0.113.1"))).isEqualTo(429);
        assertThat(status(loginFrom(PROXY, "u5", "x-Passw0rd!", "203.0.113.2"))).as("another client").isEqualTo(401);
    }

    @Test
    void entriesTheClientPrependedAreIgnored() throws Exception {
        for (int i = 0; i < 3; i++) {
            // the client invents a new left-hand address each time; the proxy appends the real one on the right
            assertThat(status(loginFrom(PROXY, "v" + i, "x-Passw0rd!", "9.9.9." + i + ", 203.0.113.9"))).isEqualTo(401);
        }
        assertThat(status(loginFrom(PROXY, "v9", "x-Passw0rd!", "9.9.9.99, 203.0.113.9"))).isEqualTo(429);
    }

    @Test
    void withoutTheHeaderTheProxyAddressItselfIsLimited() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(status(loginFrom(PROXY, "w" + i, "x-Passw0rd!"))).isEqualTo(401);
        assertThat(status(loginFrom(PROXY, "w9", "x-Passw0rd!"))).isEqualTo(429);
    }

    @Test
    void rotatingTheLow64BitsOfAForwardedIpv6ClientDoesNotResetTheBucket() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(status(loginFrom(PROXY, "x" + i, "x-Passw0rd!", "2001:db8:5:6::" + (i + 1)))).isEqualTo(401);
        }
        assertThat(status(loginFrom(PROXY, "x9", "x-Passw0rd!", "2001:db8:5:6:abcd::9"))).isEqualTo(429);
    }

    @Test
    void anInvalidForwardedAddressFallsBackToTheProxyBucket() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(status(loginFrom(PROXY, "y" + i, "x-Passw0rd!", "dead:beef:::::" + i))).isEqualTo(401);
        assertThat(status(loginFrom(PROXY, "y9", "x-Passw0rd!", "1.2.3.4.5.6"))).isEqualTo(429);
    }
}
