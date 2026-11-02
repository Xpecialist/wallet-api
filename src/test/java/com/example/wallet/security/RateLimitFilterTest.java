package com.example.wallet.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
        p.getLoginUsernameIp().setCapacity(loginCapacity);
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
        assertThat(one.clientIp(req("10.0.0.1", "2001:DB8::1"))).isEqualTo("2001:db8:0:0:0:0:0:0"); // IPv6 is keyed on its /64 prefix
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
        return login(ip, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest login(String ip, String contentType, byte[] body) {
        MockHttpServletRequest r = req(ip);
        r.setContentType(contentType);
        r.setContent(body);
        return r;
    }

    /** Runs the request; the chain (the "controller") answers {@code chainStatus} and records the body it saw. */
    private int run(RateLimitFilter f, MockHttpServletRequest request, int chainStatus, AtomicReference<String> seenBody)
            throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest rq, ServletResponse rs) throws IOException, ServletException {
                seenBody.set(new String(rq.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(chainStatus);
                super.doFilter(rq, rs);
            }
        };
        f.doFilter(request, resp, chain);
        return resp.getStatus();
    }

    private int status(RateLimitFilter f, String ip, String body, AtomicReference<String> seenBody) throws Exception {
        return run(f, login(ip, body), 200, seenBody);
    }

    private int attempt(RateLimitFilter f, String ip, String username, int controllerStatus) throws Exception {
        return run(f, login(ip, "{\"username\":\"" + username + "\",\"password\":\"x\"}"), controllerStatus,
                new AtomicReference<>());
    }

    private RateLimitFilter filterWith(Consumer<RateLimitProperties> tweak) {
        RateLimitProperties p = new RateLimitProperties();
        tweak.accept(p);
        return new RateLimitFilter(new RateLimits(p, mapper), mapper);
    }

    @Test
    void theControllerStillSeesTheWholeLoginBodyAfterTheFilterReadIt() throws Exception {
        RateLimitFilter f = new RateLimitFilter(limits(false, 1, 5), mapper);
        AtomicReference<String> seen = new AtomicReference<>();
        String body = "{\"username\":\"Alice\",\"password\":\"x\"}";
        assertThat(status(f, "1.1.1.1", body, seen)).isEqualTo(200);
        assertThat(seen.get()).isEqualTo(body);

        String atLimit = "{\"username\":\"alice\",\"password\":\""
                + "p".repeat(RateLimitFilter.MAX_LOGIN_BODY_BYTES - 40) + "\"}";
        assertThat(atLimit.length()).isLessThanOrEqualTo(RateLimitFilter.MAX_LOGIN_BODY_BYTES);
        assertThat(status(f, "1.1.1.2", atLimit, seen)).isEqualTo(200);
        assertThat(seen.get()).as("a body at the limit is passed on intact").isEqualTo(atLimit);
    }

    @Test
    void aLoginBodyOverTheLimitIs413AndNeverReachesTheController() throws Exception {
        RateLimitFilter f = new RateLimitFilter(limits(false, 1, 5), mapper);
        AtomicReference<String> seen = new AtomicReference<>("untouched");
        String big = "{\"username\":\"alice\",\"password\":\"x\",\"pad\":\"" + "A".repeat(9000) + "\"}";
        MockHttpServletResponse resp = new MockHttpServletResponse();
        f.doFilter(login("1.1.1.1", big), resp, new MockFilterChain());
        assertThat(resp.getStatus()).isEqualTo(413);
        assertThat(mapper.readTree(resp.getContentAsString()).get("status").asInt()).isEqualTo(413);
        assertThat(status(f, "1.1.1.2", "{\"username\":\"alice\",\"password\":\"x\",\"pad\":\""
                + "A".repeat(RateLimitFilter.MAX_LOGIN_BODY_BYTES) + "\"}", seen)).isEqualTo(413);
        assertThat(seen.get()).isEqualTo("untouched");
    }

    @Test
    void usernamesThatAreNotNonBlankStringsOrBodiesThatAreAmbiguousAreRejectedWith400() throws Exception {
        RateLimitFilter f = new RateLimitFilter(limits(false, 1, 100), mapper);
        AtomicReference<String> seen = new AtomicReference<>("untouched");
        for (String body : new String[] {"{not json", "", "[]", "\"alice\"", "null", "{}", "{\"username\":42}",
                "{\"username\":true}", "{\"username\":null}", "{\"username\":[\"a\"]}", "{\"username\":{}}",
                "{\"username\":\"\"}", "{\"username\":\"  \\t \"}",
                "{\"username\":\"a\",\"username\":\"b\"}", "{\"username\":\"a\",\"password\":\"x\",\"password\":\"y\"}",
                "{\"username\":\"a\",\"password\":\"x\"} {\"username\":\"b\"}", "{\"username\":\"a\"} x"}) {
            assertThat(status(f, "1.1.1.1", body, seen)).as(body).isEqualTo(400);
        }
        assertThat(seen.get()).as("none reached the controller").isEqualTo("untouched");
    }

    @Test
    void onlyUtf8JsonContentTypesAreAccepted() throws Exception {
        RateLimitFilter f = new RateLimitFilter(limits(false, 1, 100), mapper);
        byte[] body = "{\"username\":\"alice\",\"password\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        for (String type : new String[] {null, "text/plain", "application/x-www-form-urlencoded", "application/xml",
                "garbage", "application/json;charset=ISO-8859-1", "application/json; charset=utf-16"}) {
            MockHttpServletRequest r = req("1.1.1.1");
            if (type != null) r.setContentType(type);
            r.setContent(body);
            assertThat(run(f, r, 200, new AtomicReference<>())).as(String.valueOf(type)).isEqualTo(415);
        }
        for (String type : new String[] {"application/json", "application/json;charset=UTF-8",
                "application/json; charset=\"utf-8\"", "application/vnd.api+json", "APPLICATION/JSON"}) {
            assertThat(run(f, login("1.1.1.1", type, body), 200, new AtomicReference<>())).as(type).isEqualTo(200);
        }
    }

    @Test
    void aUtf8ByteOrderMarkAndJsonEscapesGiveTheSameBucketKeyAsThePlainName() throws Exception {
        RateLimitFilter f = filterWith(p -> p.getLoginUsernameIp().setCapacity(3));
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] json = "{\"username\":\"alice\",\"password\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + json.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(json, 0, withBom, bom.length, json.length);
        assertThat(run(f, login("1.1.1.1", "application/json", withBom), 401, new AtomicReference<>())).isEqualTo(401);
        assertThat(attempt(f, "1.1.1.1", "\\u0061lice", 401)).isEqualTo(401);
        assertThat(attempt(f, "1.1.1.1", "ALICE", 401)).isEqualTo(401);
        assertThat(attempt(f, "1.1.1.1", "alice", 401)).as("fourth attempt for the same name and IP").isEqualTo(429);
    }

    @Test
    void chunkedBodiesWithoutAContentLengthAreStillReadAndLimited() throws Exception {
        RateLimitFilter f = new RateLimitFilter(limits(false, 1, 100), mapper);
        MockHttpServletRequest small = login("1.1.1.1", "{\"username\":\"alice\",\"password\":\"x\"}");
        small.addHeader("Transfer-Encoding", "chunked");
        assertThat(run(f, small, 401, new AtomicReference<>())).isEqualTo(401);
        MockHttpServletRequest big = login("1.1.1.1", "{\"username\":\"alice\",\"pad\":\""
                + "A".repeat(10_000) + "\"}");
        big.addHeader("Transfer-Encoding", "chunked");
        assertThat(run(f, big, 401, new AtomicReference<>())).isEqualTo(413);
    }

    // ---- per (username, IP) and the per-username failure aggregate -------------------------------------------------

    @Test
    void theUsernameAndIpBucketIsSharedByCaseVariantsOfOneIpAndNotAcrossIps() throws Exception {
        RateLimitFilter f = filterWith(p -> p.getLoginUsernameIp().setCapacity(2));
        assertThat(attempt(f, "1.1.1.1", "alice", 401)).isEqualTo(401);
        assertThat(attempt(f, "1.1.1.1", "ALICE", 401)).isEqualTo(401);
        assertThat(attempt(f, "1.1.1.1", " Alice ", 401)).isEqualTo(429);
        assertThat(attempt(f, "1.1.1.2", "alice", 401)).as("another IP, own bucket").isEqualTo(401);
        assertThat(attempt(f, "1.1.1.1", "bob", 401)).as("another name").isEqualTo(401);
    }

    @Test
    void failedGuessesFromNewIpsAreCappedPerUsernameButSuccessesAndValidationErrorsAreNot() throws Exception {
        RateLimitFilter f = filterWith(p -> {
            p.getLoginUsernameFailures().setCapacity(3);
            p.getLoginIp().setCapacity(100);
        });
        for (int i = 1; i <= 10; i++) assertThat(attempt(f, "2.2.2." + i, "alice", 200)).isEqualTo(200);
        for (int i = 11; i <= 20; i++) assertThat(attempt(f, "2.2.2." + i, "alice", 400)).isEqualTo(400);
        for (int i = 21; i <= 23; i++) assertThat(attempt(f, "2.2.2." + i, "alice", 401)).isEqualTo(401);
        assertThat(attempt(f, "2.2.2.24", "alice", 401)).as("fourth failure from a never-seen IP").isEqualTo(429);
        assertThat(attempt(f, "2.2.2.25", "alice", 200)).as("even a correct password from a new IP waits").isEqualTo(429);
        assertThat(attempt(f, "2.2.2.1", "alice", 200)).as("an IP that logged in before").isEqualTo(200);
        assertThat(attempt(f, "2.2.2.1", "alice", 401)).as("its typo is a plain 401, never held").isEqualTo(401);
        assertThat(attempt(f, "2.2.2.26", "bob", 401)).as("other names are separate").isEqualTo(401);
    }

    @Test
    void anIpKnownForOneUserIsNotKnownForAnother() throws Exception {
        RateLimitFilter f = filterWith(p -> {
            p.getLoginUsernameFailures().setCapacity(1);
            p.getLoginIp().setCapacity(100);
        });
        assertThat(attempt(f, "3.3.3.3", "bob", 200)).isEqualTo(200);
        assertThat(attempt(f, "3.3.3.4", "alice", 401)).isEqualTo(401);
        assertThat(attempt(f, "3.3.3.3", "alice", 401)).as("known for bob, not for alice").isEqualTo(429);
        assertThat(attempt(f, "3.3.3.3", "bob", 401)).isEqualTo(401);
    }

    @Test
    void unknownAndKnownUsernamesAreCountedIdentically() throws Exception {
        RateLimitFilter f = filterWith(p -> {
            p.getLoginUsernameFailures().setCapacity(4);
            p.getLoginUsernameIp().setCapacity(2);
        });
        List<Integer> real = new ArrayList<>();
        List<Integer> ghost = new ArrayList<>();
        for (int i = 1; i <= 6; i++) real.add(attempt(f, "4.4.4." + i, "alice", 401));
        for (int i = 1; i <= 6; i++) ghost.add(attempt(f, "5.5.5." + i, "no-such-user", 401));
        assertThat(ghost).isEqualTo(real).containsExactly(401, 401, 401, 401, 429, 429);
    }

    // ---- no gating on the raw path ---------------------------------------------------------------------------------

    @Test
    void anyPathCountsInTheGlobalBucketAndEncodedSpellingsAreTheSameEndpoint() throws Exception {
        RateLimitFilter f = filterWith(p -> p.getGlobalIp().setCapacity(3));
        for (String path : new String[] {"/error", "/%61pi/anything", "/ap%69/wallets/me"}) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            f.doFilter(new MockHttpServletRequest("GET", path), resp, new MockFilterChain());
            assertThat(resp.getStatus()).as(path).isEqualTo(200);
        }
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        f.doFilter(new MockHttpServletRequest("GET", "/whatever"), blocked, new MockFilterChain());
        assertThat(blocked.getStatus()).isEqualTo(429);

        RateLimitFilter logins = filterWith(p -> p.getLoginIp().setCapacity(2));
        for (String path : new String[] {"/%61pi/auth/login", "/ap%69/auth/%6cogin", "/api/auth/login"}) {
            MockHttpServletRequest r = login("1.1.1.1", "{\"username\":\"u" + path.length() + "\",\"password\":\"x\"}");
            r.setRequestURI(path);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            logins.doFilter(r, resp, new MockFilterChain());
            assertThat(resp.getStatus()).as(path).isEqualTo(path.equals("/api/auth/login") ? 429 : 200);
        }
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
        assertThat(status(f, "1.1.1.1", "{not json", seen)).as("not even the body is looked at").isEqualTo(200);
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
}
