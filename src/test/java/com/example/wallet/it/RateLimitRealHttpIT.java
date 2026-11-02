package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Rate limits, login-body handling and the body size cap over REAL HTTP against the embedded Tomcat (random port).
 * MockMvc cannot show these: it builds the request path itself, so a percent-encoded path never reaches the filters
 * the way a real client sends it. The paths below are sent exactly as written (java.net.http.HttpClient does not
 * normalize or decode them). Small limits of their own: login 8 per IP and 5 per username and IP, register 3/h,
 * password change 3 per user, money 4/min, admin 4/min, global 60/min per IP, bodies up to 16384 bytes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.login-ip.capacity=8", "app.rate-limit.login-ip.window=1m",
        "app.rate-limit.login-username-ip.capacity=5", "app.rate-limit.login-username-ip.window=1m",
        "app.rate-limit.login-username-failures.capacity=30", "app.rate-limit.login-username-failures.window=1m",
        "app.rate-limit.register-ip.capacity=3", "app.rate-limit.register-ip.window=1h",
        "app.rate-limit.password-user.capacity=3", "app.rate-limit.password-user.window=15m",
        "app.rate-limit.password-ip.capacity=4", "app.rate-limit.password-ip.window=15m",
        "app.rate-limit.money-user.capacity=4", "app.rate-limit.money-user.window=1m",
        "app.rate-limit.admin-user.capacity=4", "app.rate-limit.admin-user.window=1m",
        "app.rate-limit.global-ip.capacity=60", "app.rate-limit.global-ip.window=1m",
        "app.max-body-bytes=16384"})
class RateLimitRealHttpIT extends RateLimitBase {
    private static final String WRONG = "wrong-Passw0rd!";
    private static final String JSON = "application/json";
    /** The same endpoint written with percent-encoded letters; Spring routes all of them to the plain path. */
    private static final List<String> LOGIN_PATHS = List.of("/api/auth/login", "/%61pi/auth/login",
            "/ap%69/auth/login", "/api/auth/%6cogin", "/%61%70%69/auth/%6c%6f%67%69%6e");

    @LocalServerPort int port;
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    record Resp(int status, String body, String retryAfter) {
        JsonNode json(RateLimitRealHttpIT t) { return t.json.readTree(body); }
    }

    // ---- raw HTTP helpers --------------------------------------------------------------------------------------

    private Resp send(String method, String rawPath, String contentType, byte[] body, boolean chunked, String token)
            throws Exception {
        HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody()
                : chunked ? HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofByteArray(body))
                : HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath))
                .method(method, pub);
        if (contentType != null) b.header("Content-Type", contentType);
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body(), r.headers().firstValue("Retry-After").orElse(null));
    }

    private Resp postJsonRaw(String rawPath, String token, String jsonBody) throws Exception {
        return send("POST", rawPath, JSON, jsonBody.getBytes(StandardCharsets.UTF_8), false, token);
    }

    private Resp loginAt(String rawPath, String username, String password) throws Exception {
        return postJsonRaw(rawPath, null, "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    private Resp get(String rawPath, String token) throws Exception {
        return send("GET", rawPath, null, null, false, token);
    }

    private static List<Integer> statuses(List<Resp> rs) { return rs.stream().map(Resp::status).toList(); }

    // ---- A: percent-encoded /api paths -------------------------------------------------------------------------

    @Test
    void everyEncodedSpellingOfLoginIsLimitedPerIpLikeThePlainPath() throws Exception {
        for (String path : LOGIN_PATHS) {
            limits.clear();
            List<Integer> s = new ArrayList<>();
            for (int i = 1; i <= 9; i++) s.add(loginAt(path, "user" + i, WRONG).status());
            assertThat(s).as(path).containsExactly(401, 401, 401, 401, 401, 401, 401, 401, 429);
        }
    }

    @Test
    void encodedAndPlainLoginPathsShareTheUsernameBucket() throws Exception {
        register("alice");
        limits.clear();
        String[] paths = {LOGIN_PATHS.get(0), LOGIN_PATHS.get(1), LOGIN_PATHS.get(0), LOGIN_PATHS.get(2),
                LOGIN_PATHS.get(3)};
        for (String p : paths) assertThat(loginAt(p, "alice", WRONG).status()).as(p).isEqualTo(401);
        for (String p : LOGIN_PATHS) {
            Resp r = loginAt(p, "alice", PASSWORD);
            assertThat(r.status()).as("right password through " + p).isEqualTo(429);
            assertThat(r.retryAfter()).isNotBlank();
        }
    }

    @Test
    void encodedRegisterPathIsLimitedAndCreatesNothingOnceBlocked() throws Exception {
        String[] paths = {"/%61pi/auth/register", "/ap%69/auth/register", "/api/auth/regist%65r"};
        List<Integer> s = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            s.add(postJsonRaw(paths[i % 3], null, "{\"username\":\"flood" + i + "\",\"email\":\"flood" + i
                    + "@example.com\",\"password\":\"" + PASSWORD + "\"}").status());
        }
        assertThat(s).containsExactly(201, 201, 201, 429);
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(3);
    }

    @Test
    void encodedMoneyPathsAreLimitedPerUser() throws Exception {
        String alice = register("alice");
        register("bob");
        limits.clear();
        List<Integer> s = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String path = i % 2 == 0 ? "/%61pi/wallets/me/withdraw" : "/ap%69/transactions/transfer";
            String body = i % 2 == 0 ? "{\"amount\":1}" : "{\"receiverUsername\":\"bob\",\"amount\":1}";
            s.add(postJsonRaw(path, alice, body).status());
        }
        assertThat(s).containsExactly(201, 201, 201, 201, 429, 429);
        assertThat(balance("alice")).as("only the 4 allowed requests moved money").isEqualByComparingTo("96");
    }

    @Test
    void encodedPasswordChangePathIsLimitedPerUser() throws Exception {
        String alice = register("alice");
        limits.clear();
        String body = "{\"currentPassword\":\"" + WRONG + "\",\"newPassword\":\"N3w-Passw0rd!x\"}";
        List<Integer> s = new ArrayList<>();
        for (int i = 0; i < 5; i++) s.add(postJsonRaw("/%61pi/users/me/password", alice, body).status());
        assertThat(s).containsExactly(400, 400, 400, 429, 429);
    }

    @Test
    void encodedAdminDepositPathIsLimitedPerAdmin() throws Exception {
        register("alice");
        String admin = adminToken();
        limits.clear();
        String path = "/ap%69/admin/wallets/" + walletId("alice") + "/deposit";
        List<Integer> s = new ArrayList<>();
        for (int i = 0; i < 5; i++) s.add(postJsonRaw(path, admin, "{\"amount\":1}").status());
        assertThat(s).containsExactly(201, 201, 201, 201, 429);
    }

    @Test
    void theGlobalPerIpBucketCountsEncodedPathsToo() throws Exception {
        limits.clear();
        String[] paths = {"/api/wallets/me", "/%61pi/wallets/me", "/ap%69/wallets/me"};
        List<Integer> s = new ArrayList<>();
        for (int i = 0; i < 61; i++) s.add(get(paths[i % 3], null).status());
        assertThat(s.subList(0, 60)).as("anonymous, so 401 until the bucket is empty").containsOnly(401);
        assertThat(s.get(60)).isEqualTo(429);
    }

    @Test
    void encodedAdminPathsStayAdminOnly() throws Exception {
        String alice = register("alice");
        String admin = adminToken();
        limits.clear();
        for (String p : List.of("/ap%69/admin/users", "/%61pi/admin/users", "/api/admin/%75sers",
                "/%61pi/admin/transactions", "/%61pi/admin/deposit-requests")) {
            assertThat(get(p, alice).status()).as("USER " + p).isEqualTo(403);
            assertThat(get(p, null).status()).as("anonymous " + p).isEqualTo(401);
            assertThat(get(p, admin).status()).as("ADMIN " + p).isEqualTo(200);
        }
        assertThat(postJsonRaw("/%61pi/admin/wallets/" + walletId("alice") + "/deposit", alice, "{\"amount\":1}")
                .status()).isEqualTo(403);
        assertThat(send("PATCH", "/ap%69/admin/users/1/status", JSON, "{\"enabled\":false}".getBytes(), false, alice)
                .status()).isEqualTo(403);
        assertThat(balance("alice")).isEqualByComparingTo("100");
    }

    @Test
    void oddSpellingsOfTheLoginPathNeverReachTheLoginController() throws Exception {
        register("alice");
        limits.clear();
        // dot segments, path parameters, double slashes, a trailing slash, other case, encoded dots/slashes/NUL: the
        // firewall or the router rejects every one of them, so none can authenticate
        for (String p : List.of("/api/auth/./login", "/x/../api/auth/login", "/api/auth/../auth/login",
                "/api/./auth/login", "/api/auth/login;x=1", "/api/auth;x=1/login", "//api/auth/login",
                "/api//auth/login", "/api/auth/login/", "/API/AUTH/LOGIN", "/Api/Auth/Login", "/api/auth/login%2f",
                "/api/auth/login%00", "/api/auth/login%20", "/%2e/api/auth/login", "/api/%2e%2e/api/auth/login")) {
            Resp r = loginAt(p, "alice", PASSWORD);
            assertThat(r.status()).as(p).isNotEqualTo(200).isBetween(400, 499);
        }
    }

    @Test
    void headAndOptionsOnEncodedPathsNeverSucceedAsALoginOrAnAdminCall() throws Exception {
        limits.clear();
        for (String p : List.of("/%61pi/auth/login", "/ap%69/auth/login")) {
            assertThat(send("HEAD", p, null, null, false, null).status()).as("HEAD " + p).isBetween(400, 499);
            // OPTIONS of an existing route answers its Allow list (200) without running the handler
            assertThat(send("OPTIONS", p, null, null, false, null).status()).as("OPTIONS " + p).isIn(200, 204, 401, 403);
        }
        for (String m : List.of("HEAD", "OPTIONS")) {
            assertThat(send(m, "/ap%69/admin/users", null, null, false, null).status()).as(m + " admin").isEqualTo(401);
        }
    }

    // ---- B: the login body -----------------------------------------------------------------------------------

    private static String paddedLogin(String user, String password, int totalBytes) {
        String head = "{\"username\":\"" + user + "\",\"password\":\"" + password + "\",\"pad\":\"";
        String tail = "\"}";
        return head + "A".repeat(Math.max(0, totalBytes - head.length() - tail.length())) + tail;
    }

    @Test
    void aLoginBodyOverTheLimitIs413InsteadOfSkippingTheUsernameBucket() throws Exception {
        register("alice");
        limits.clear();
        Resp atLimit = postJsonRaw("/api/auth/login", null, paddedLogin("alice", WRONG, 8192));
        assertThat(atLimit.status()).as("exactly 8192 bytes is still a login").isEqualTo(401);

        for (int size : new int[] {8193, 9000, 15000}) {
            Resp over = postJsonRaw("/api/auth/login", null, paddedLogin("alice", PASSWORD, size));
            assertThat(over.status()).as("size " + size).isEqualTo(413);
            assertThat(over.json(this).get("status").asInt()).isEqualTo(413);
            assertThat(over.json(this).get("error").asString()).isNotBlank();
            assertThat(over.json(this).size()).isEqualTo(3);
        }
        Resp chunked = send("POST", "/api/auth/login", JSON,
                paddedLogin("alice", PASSWORD, 9000).getBytes(StandardCharsets.UTF_8), true, null);
        assertThat(chunked.status()).as("chunked, length unknown up front").isEqualTo(413);
        Resp encodedPath = postJsonRaw("/%61pi/auth/login", null, paddedLogin("alice", PASSWORD, 9000));
        assertThat(encodedPath.status()).isEqualTo(413);
    }

    @Test
    void paddedBodiesCannotBeUsedToGuessPastTheUsernameLimit() throws Exception {
        register("alice");
        limits.clear();
        int wrong = 0;
        for (int i = 0; i < 20; i++) {
            Resp r = postJsonRaw("/api/auth/login", null, paddedLogin("alice", WRONG, i % 2 == 0 ? 9000 : 400));
            if (r.status() == 401) wrong++;
        }
        assertThat(wrong).as("password checks for one username from one IP per minute").isLessThanOrEqualTo(5);
    }

    @Test
    void usernamesThatAreNotNonBlankStringsAreRejectedWith400() throws Exception {
        limits.clear();
        for (String username : new String[] {"123", "12.5", "true", "null", "[]", "{}", "[\"alice\"]", "\"\"",
                "\"   \"", "\"\\t\\n\""}) {
            limits.clear();
            Resp r = postJsonRaw("/api/auth/login", null, "{\"username\":" + username + ",\"password\":\"x\"}");
            assertThat(r.status()).as("username " + username).isEqualTo(400);
            assertThat(r.json(this).get("status").asInt()).isEqualTo(400);
        }
        limits.clear();
        assertThat(postJsonRaw("/api/auth/login", null, "{\"password\":\"x\"}").status()).as("missing").isEqualTo(400);
        assertThat(postJsonRaw("/api/auth/login", null, "[1,2]").status()).as("array body").isEqualTo(400);
        assertThat(postJsonRaw("/api/auth/login", null, "{not json").status()).as("malformed").isEqualTo(400);
        assertThat(postJsonRaw("/api/auth/login", null, "").status()).as("empty").isEqualTo(400);
    }

    @Test
    void duplicateJsonKeysAreRejectedSoTheFilterAndTheControllerCannotDisagree() throws Exception {
        register("alice");
        limits.clear();
        for (String body : new String[] {
                "{\"username\":\"nobody\",\"username\":\"alice\",\"password\":\"" + PASSWORD + "\"}",
                "{\"username\":\"alice\",\"username\":\"nobody\",\"password\":\"" + PASSWORD + "\"}",
                "{\"username\":\"alice\",\"password\":\"x\",\"password\":\"" + PASSWORD + "\"}"}) {
            assertThat(postJsonRaw("/api/auth/login", null, body).status()).as(body).isEqualTo(400);
        }
        assertThat(loginAt("/api/auth/login", "alice", PASSWORD).status()).as("a clean body still works").isEqualTo(200);
    }

    @Test
    void onlyUtf8BodiesAreAccepted() throws Exception {
        register("alice");
        limits.clear();
        byte[] body = ("{\"username\":\"alice\",\"password\":\"" + PASSWORD + "\"}").getBytes(StandardCharsets.UTF_8);
        for (String type : new String[] {"application/json;charset=ISO-8859-1", "application/json; charset=utf-16",
                "application/json;charset=windows-1252", "application/json;charset=US-ASCII"}) {
            assertThat(send("POST", "/api/auth/login", type, body, false, null).status()).as(type).isEqualTo(415);
        }
        for (String type : new String[] {"application/json", "application/json;charset=UTF-8",
                "application/json; charset=\"utf-8\"", "application/vnd.api+json"}) {
            limits.clear();
            assertThat(send("POST", "/api/auth/login", type, body, false, null).status()).as(type).isEqualTo(200);
        }
        for (String type : new String[] {null, "text/plain", "application/x-www-form-urlencoded", "application/xml"}) {
            assertThat(send("POST", "/api/auth/login", type, body, false, null).status()).as(String.valueOf(type))
                    .isEqualTo(415);
        }
    }

    @Test
    void bomAndUtf16BodiesShareTheSameBucketOrAreRejected() throws Exception {
        register("alice");
        limits.clear();
        String json = "{\"username\":\"alice\",\"password\":\"" + WRONG + "\"}";
        byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] utf8Bom = concat(bom, json.getBytes(StandardCharsets.UTF_8));
        byte[] utf16Bom = ("﻿" + json).getBytes(Charset.forName("UTF-16LE"));
        byte[] utf16Be = json.getBytes(StandardCharsets.UTF_16BE);
        List<byte[]> variants = List.of(json.getBytes(StandardCharsets.UTF_8), utf8Bom, utf16Bom, utf16Be, utf8Bom,
                json.getBytes(StandardCharsets.UTF_8), utf8Bom, utf16Bom, utf16Be, json.getBytes(StandardCharsets.UTF_8));
        int wrong = 0;
        for (byte[] v : variants) {
            int s = send("POST", "/api/auth/login", JSON, v, false, null).status();
            assertThat(s).isIn(400, 401, 415, 429);
            if (s == 401) wrong++;
        }
        assertThat(wrong).as("at most 5 password checks for alice from this IP").isLessThanOrEqualTo(5);
        assertThat(loginAt("/api/auth/login", "alice", PASSWORD).status()).as("and now the bucket is empty").isEqualTo(429);
    }

    @Test
    void chunkedAndCaseVariantBodiesCountInTheSameBucket() throws Exception {
        register("alice");
        limits.clear();
        String[] names = {"alice", "ALICE", " Alice ", "aLiCe\\t", "\\talice"};
        for (int i = 0; i < names.length; i++) {
            byte[] b = ("{\"username\":\"" + names[i] + "\",\"password\":\"" + WRONG + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            assertThat(send("POST", "/api/auth/login", JSON, b, i % 2 == 1, null).status()).as(names[i]).isEqualTo(401);
        }
        byte[] b = ("{\"username\":\"alice\",\"password\":\"" + PASSWORD + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThat(send("POST", "/api/auth/login", JSON, b, true, null).status()).isEqualTo(429);
        assertThat(send("POST", "/api/auth/login", JSON, b, false, null).status()).isEqualTo(429);
    }

    @Test
    void aNormalLoginOverRealHttpStillWorks() throws Exception {
        register("alice");
        limits.clear();
        Resp ok = loginAt("/api/auth/login", "  Alice ", PASSWORD);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.json(this).get("token").asString()).isNotBlank();
        assertThat(send("POST", "/api/auth/login", JSON,
                ("{\"username\":\"alice\",\"password\":\"" + PASSWORD + "\"}").getBytes(), true, null).status())
                .as("chunked").isEqualTo(200);
    }

    // ---- F: request body size cap ----------------------------------------------------------------------------

    private static String transferWithNote(int noteChars) {
        return "{\"receiverUsername\":\"bob\",\"amount\":1,\"note\":\"" + "n".repeat(noteChars) + "\"}";
    }

    @Test
    void bodiesOverTheCapAre413WithTheJsonErrorShape() throws Exception {
        String alice = register("alice");
        register("bob");
        limits.clear();
        Resp declared = postJsonRaw("/api/transactions/transfer", alice, transferWithNote(20_000));
        assertThat(declared.status()).isEqualTo(413);
        assertThat(declared.json(this).get("status").asInt()).isEqualTo(413);
        assertThat(declared.json(this).get("error").asString()).isNotBlank();
        assertThat(declared.json(this).size()).isEqualTo(3);

        Resp chunked = send("POST", "/api/transactions/transfer", JSON,
                transferWithNote(20_000).getBytes(StandardCharsets.UTF_8), true, alice);
        assertThat(chunked.status()).as("chunked, enforced while reading").isEqualTo(413);
        assertThat(txCount()).isZero();

        // unauthenticated and through an encoded path: the cap applies before authentication and by decoded path
        assertThat(postJsonRaw("/%61pi/auth/register", null, transferWithNote(20_000)).status()).isEqualTo(413);
        assertThat(send("POST", "/ap%69/auth/register", JSON, transferWithNote(20_000).getBytes(), true, null)
                .status()).isEqualTo(413);
        // an unknown chunked body size is counted, not trusted
        assertThat(send("PATCH", "/api/users/me", JSON, ("{\"email\":\"" + "e".repeat(20_000) + "\"}").getBytes(), true,
                alice).status()).isEqualTo(413);
    }

    @Test
    void bodiesAtOrUnderTheCapAreHandledNormally() throws Exception {
        String alice = register("alice");
        register("bob");
        limits.clear();
        // a 255 character note is valid and the body is far under the cap
        assertThat(postJsonRaw("/api/transactions/transfer", alice, transferWithNote(255)).status()).isEqualTo(201);
        // a body exactly at the cap reaches the validators (note too long: 400, not 413)
        String head = "{\"receiverUsername\":\"bob\",\"amount\":1,\"note\":\"";
        String tail = "\"}";
        String exact = head + "n".repeat(16384 - head.length() - tail.length()) + tail;
        assertThat(exact.getBytes(StandardCharsets.UTF_8)).hasSize(16384);
        assertThat(postJsonRaw("/api/transactions/transfer", alice, exact).status()).isEqualTo(400);
        assertThat(postJsonRaw("/api/transactions/transfer", alice, exact + " ").status()).isEqualTo(413);
        assertThat(get("/api/wallets/me", alice).status()).isEqualTo(200);
        assertThat(send("POST", "/api/auth/logout", null, new byte[0], false, alice).status()).isEqualTo(204);
    }

    @Test
    void theCapOnlyCoversApiPaths() throws Exception {
        limits.clear();
        assertThat(postJsonRaw("/not-api/anything", null, transferWithNote(20_000)).status()).isNotEqualTo(413);
    }

    @Autowired org.springframework.security.web.FilterChainProxy filterChainProxy;

    @Test
    void ourFiltersRunInTheOrderRateLimitThenBodyCapThenJwtThenUserLimits() {
        List<String> names = filterChainProxy.getFilterChains().get(0).getFilters().stream()
                .map(f -> f.getClass().getSimpleName()).toList();
        assertThat(names).containsSubsequence("RateLimitFilter", "BodyLimitFilter", "JwtAuthFilter", "UserRateLimitFilter");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
