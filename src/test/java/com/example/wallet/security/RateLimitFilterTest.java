package com.example.wallet.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Client IP resolution, username normalization and the filter logic without Spring or a database. */
class RateLimitFilterTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    private RateLimits limits(boolean trust, int proxies, int loginCapacity) {
        RateLimitProperties p = new RateLimitProperties();
        p.setTrustForwardedHeaders(trust);
        p.setTrustedProxies(proxies);
        p.getLoginIp().setCapacity(loginCapacity);
        p.getLoginUsername().setCapacity(loginCapacity);
        return new RateLimits(p, mapper);
    }

    private static MockHttpServletRequest req(String remote, String... forwardedFor) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/auth/login");
        r.setRemoteAddr(remote);
        for (String h : forwardedFor) r.addHeader("X-Forwarded-For", h);
        return r;
    }

    // ---- client IP ---------------------------------------------------------------------------------------------

    @Test
    void usesThePeerAddressByDefaultAndIgnoresASpoofedForwardedHeader() {
        RateLimits l = limits(false, 1, 5);
        assertThat(l.clientIp(req("10.0.0.1"))).isEqualTo("10.0.0.1");
        assertThat(l.clientIp(req("10.0.0.1", "6.6.6.6"))).isEqualTo("10.0.0.1");
        assertThat(l.clientIp(req("10.0.0.1", "1.1.1.1, 2.2.2.2"))).isEqualTo("10.0.0.1");
    }

    @Test
    void whenTrustedTakesTheRightMostEntryAndIgnoresWhatTheClientPrepended() {
        RateLimits l = limits(true, 1, 5);
        assertThat(l.clientIp(req("10.0.0.1", "203.0.113.7"))).isEqualTo("203.0.113.7");
        // the client forged the left entries; the proxy appended the real address on the right
        assertThat(l.clientIp(req("10.0.0.1", "6.6.6.6, 7.7.7.7, 203.0.113.7"))).isEqualTo("203.0.113.7");
        // several header lines count as one list
        assertThat(l.clientIp(req("10.0.0.1", "6.6.6.6", "203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void trustedProxyCountSelectsTheEntryFromTheRight() {
        RateLimits l = limits(true, 2, 5);
        assertThat(l.clientIp(req("10.0.0.1", "6.6.6.6, 203.0.113.7, 198.51.100.1"))).isEqualTo("203.0.113.7");
    }

    @Test
    void fallsBackToThePeerWhenTheHeaderIsMissingShortOrNotAnAddress() {
        RateLimits l = limits(true, 2, 5);
        assertThat(l.clientIp(req("10.0.0.1"))).isEqualTo("10.0.0.1");
        assertThat(l.clientIp(req("10.0.0.1", "203.0.113.7"))).isEqualTo("10.0.0.1");
        RateLimits one = limits(true, 1, 5);
        assertThat(one.clientIp(req("10.0.0.1", "not-an-ip"))).isEqualTo("10.0.0.1");
        assertThat(one.clientIp(req("10.0.0.1", "1.2.3.4" + "5".repeat(60)))).isEqualTo("10.0.0.1");
        assertThat(one.clientIp(req("10.0.0.1", "2001:DB8::1"))).isEqualTo("2001:db8::1");
    }

    // ---- username normalization --------------------------------------------------------------------------------

    @Test
    void usernamesAreTrimmedAndLowerCased() {
        assertThat(RateLimitFilter.normalize("  Alice ")).isEqualTo("alice");
        assertThat(RateLimitFilter.normalize("ALICE")).isEqualTo(RateLimitFilter.normalize("alice"));
    }

    @Test
    void overlongUsernamesShareOneKey() {
        assertThat(RateLimitFilter.normalize("a".repeat(51))).isEqualTo(RateLimitFilter.normalize("b".repeat(5000)));
        assertThat(RateLimitFilter.normalize("a".repeat(50))).isEqualTo("a".repeat(50));
    }

    // ---- the filter --------------------------------------------------------------------------------------------

    private static MockHttpServletRequest login(String ip, String body) {
        MockHttpServletRequest r = req(ip);
        r.setContentType("application/json");
        r.setContent(body.getBytes(StandardCharsets.UTF_8));
        return r;
    }

    private int status(RateLimitFilter f, String ip, String body, AtomicReference<String> seenBody) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response)
                    throws IOException, ServletException {
                seenBody.set(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                super.doFilter(request, response);
            }
        };
        f.doFilter(login(ip, body), resp, chain);
        return resp.getStatus();
    }

    @Test
    void theControllerStillSeesTheWholeLoginBodyAfterTheFilterReadIt() throws Exception {
        RateLimitFilter f = new RateLimitFilter(limits(false, 1, 5), mapper);
        AtomicReference<String> seen = new AtomicReference<>();
        String body = "{\"username\":\"Alice\",\"password\":\"x\"}";
        assertThat(status(f, "1.1.1.1", body, seen)).isEqualTo(200);
        assertThat(seen.get()).isEqualTo(body);

        String big = "{\"username\":\"alice\",\"password\":\"" + "p".repeat(20_000) + "\"}";
        assertThat(status(f, "1.1.1.2", big, seen)).isEqualTo(200);
        assertThat(seen.get()).as("a body over the inspection limit is passed on intact").isEqualTo(big);
    }

    @Test
    void usernameBucketIsSharedAcrossIpsAndCaseVariants() throws Exception {
        RateLimitFilter f = new RateLimitFilter(limits(false, 1, 2), mapper);
        AtomicReference<String> seen = new AtomicReference<>();
        assertThat(status(f, "1.1.1.1", "{\"username\":\"alice\",\"password\":\"x\"}", seen)).isEqualTo(200);
        assertThat(status(f, "1.1.1.2", "{\"username\":\"ALICE\",\"password\":\"x\"}", seen)).isEqualTo(200);
        assertThat(status(f, "1.1.1.3", "{\"username\":\" Alice \",\"password\":\"x\"}", seen)).isEqualTo(429);
        assertThat(status(f, "1.1.1.3", "{\"username\":\"bob\",\"password\":\"x\"}", seen)).isEqualTo(200);
    }

    @Test
    void malformedAndNonStringUsernamesAreNotCountedPerUsernameButStillPerIp() throws Exception {
        RateLimitFilter f = new RateLimitFilter(limits(false, 1, 2), mapper);
        AtomicReference<String> seen = new AtomicReference<>();
        assertThat(status(f, "1.1.1.1", "{not json", seen)).isEqualTo(200);
        assertThat(status(f, "1.1.1.1", "{\"username\":42}", seen)).isEqualTo(200);
        assertThat(status(f, "1.1.1.1", "{\"username\":\"a\"}", seen)).isEqualTo(429);
    }

    @Test
    void disabledFilterDoesNothing() throws Exception {
        RateLimitProperties p = new RateLimitProperties();
        p.setEnabled(false);
        p.getLoginIp().setCapacity(1);
        RateLimitFilter f = new RateLimitFilter(new RateLimits(p, mapper), mapper);
        AtomicReference<String> seen = new AtomicReference<>();
        for (int i = 0; i < 5; i++) {
            assertThat(status(f, "1.1.1.1", "{\"username\":\"a\",\"password\":\"x\"}", seen)).isEqualTo(200);
        }
    }

    @Test
    void rejectionIs429WithRetryAfterAndTheProjectErrorShape() throws Exception {
        RateLimitFilter f = new RateLimitFilter(limits(false, 1, 1), mapper);
        f.doFilter(login("1.1.1.1", "{\"username\":\"a\"}"), new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse resp = new MockHttpServletResponse();
        f.doFilter(login("1.1.1.1", "{\"username\":\"a\"}"), resp, new MockFilterChain());
        assertThat(resp.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(resp.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(resp.getContentType()).startsWith("application/json");
        JsonNode json = mapper.readTree(resp.getContentAsString());
        assertThat(json.get("status").asInt()).isEqualTo(429);
        assertThat(json.get("error").asString()).startsWith("Too many requests, try again in ");
        assertThat(json.get("timestamp").isString()).isTrue();
    }

    @Test
    void nonApiPathsAreNotLimited() throws Exception {
        RateLimitProperties p = new RateLimitProperties();
        p.getGlobalIp().setCapacity(1);
        RateLimitFilter f = new RateLimitFilter(new RateLimits(p, mapper), mapper);
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            f.doFilter(new MockHttpServletRequest("GET", "/error"), resp, new MockFilterChain());
            assertThat(resp.getStatus()).isEqualTo(200);
        }
    }
}
