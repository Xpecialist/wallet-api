package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Rate limiting end to end through the real filter chain on real SQL. Small limits of their own: login 5/min per IP
 * and per username, register 3/h, password change 3/15 min per user and 4 per IP, money 4/min, admin 4/min.
 * Requests come from chosen client addresses (the servlet remote address).
 */
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.login-ip.capacity=5", "app.rate-limit.login-ip.window=1m",
        "app.rate-limit.login-username.capacity=5", "app.rate-limit.login-username.window=1m",
        "app.rate-limit.register-ip.capacity=3", "app.rate-limit.register-ip.window=1h",
        "app.rate-limit.password-user.capacity=3", "app.rate-limit.password-user.window=15m",
        "app.rate-limit.password-ip.capacity=4", "app.rate-limit.password-ip.window=15m",
        "app.rate-limit.money-user.capacity=4", "app.rate-limit.money-user.window=1m",
        "app.rate-limit.admin-user.capacity=4", "app.rate-limit.admin-user.window=1m",
        "app.rate-limit.global-ip.capacity=1000", "app.rate-limit.global-ip.window=1m"})
class RateLimitIT extends RateLimitBase {
    private static final String WRONG = "wrong-Passw0rd!";

    // ---- login: per IP ----------------------------------------------------------------------------------------

    @Test
    void sixthLoginPerMinuteFromOneIpIs429AndAnotherIpIsUnaffected() throws Exception {
        register("alice");
        limits.clear();
        // different usernames so only the per-IP bucket is exercised
        for (int i = 1; i <= 5; i++) assertThat(status(loginFrom("10.1.1.1", "user" + i, WRONG))).isEqualTo(401);
        MvcResult blocked = loginFrom("10.1.1.1", "user6", WRONG);
        assertThat(status(blocked)).isEqualTo(429);
        assertThat(Long.parseLong(blocked.getResponse().getHeader("Retry-After"))).isBetween(1L, 12L);

        assertThat(status(loginFrom("10.1.1.2", "user7", WRONG))).as("other IP").isEqualTo(401);
        assertThat(status(loginFrom("10.1.1.2", "alice", PASSWORD))).as("other IP, real login").isEqualTo(200);
        assertThat(status(loginFrom("10.1.1.1", "alice", PASSWORD))).as("blocked IP, even a right password").isEqualTo(429);
    }

    @Test
    void the429HasTheProjectErrorShape() throws Exception {
        for (int i = 0; i < 5; i++) loginFrom("10.1.1.3", "u" + i, WRONG);
        MvcResult r = loginFrom("10.1.1.3", "u9", WRONG);
        assertThat(status(r)).isEqualTo(429);
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
        JsonNode b = body(r);
        assertThat(b.get("status").asInt()).isEqualTo(429);
        assertThat(b.get("error").asString()).matches("Too many requests, try again in \\d+ seconds?");
        assertThat(b.get("timestamp").asString()).isNotBlank();
        assertThat(b.size()).isEqualTo(3);
        assertThat(b.get("error").asString()).contains(r.getResponse().getHeader("Retry-After"));
    }

    // ---- login: per username ---------------------------------------------------------------------------------

    @Test
    void guessesSpreadAcrossManyIpsAreStillStoppedByTheUsernameBucket() throws Exception {
        register("alice");
        register("bob");
        limits.clear();
        for (int i = 1; i <= 5; i++) {
            assertThat(status(loginFrom("10.2.2." + i, "alice", WRONG))).isEqualTo(401);
        }
        assertThat(status(loginFrom("10.2.2.6", "alice", WRONG))).isEqualTo(429);
        assertThat(status(loginFrom("10.2.2.7", "alice", PASSWORD))).as("right password is held too").isEqualTo(429);
        assertThat(status(loginFrom("10.2.2.6", "bob", PASSWORD))).as("another account, same IP").isEqualTo(200);
    }

    @Test
    void caseAndWhitespaceVariantsShareOneUsernameBucket() throws Exception {
        register("alice");
        limits.clear();
        String[] variants = {"alice", "ALICE", "Alice", " alice ", "aLiCe"};
        for (int i = 0; i < variants.length; i++) {
            assertThat(status(loginFrom("10.3.3." + i, variants[i], WRONG))).isEqualTo(401);
        }
        assertThat(status(loginFrom("10.3.3.9", "alice  ", WRONG))).isEqualTo(429);
        assertThat(status(loginFrom("10.3.3.9", "\tALICE", WRONG))).isEqualTo(429);
    }

    @Test
    void anUnknownUsernameIsLimitedExactlyLikeAKnownOne() throws Exception {
        register("alice");
        limits.clear();
        List<String> known = attempts("alice", "10.4.1.");
        limits.clear();
        List<String> unknown = attempts("nobody-here", "10.4.2.");
        assertThat(unknown).isEqualTo(known);
        assertThat(known).hasSize(7);
        assertThat(known.get(4)).startsWith("401 ");
        assertThat(known.get(5)).startsWith("429 ").contains("Too many requests, try again in 12 seconds");
    }

    /** Seven attempts from seven IPs; returns "status error Retry-After" per attempt. */
    private List<String> attempts(String username, String ipPrefix) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            MvcResult r = loginFrom(ipPrefix + i, username, WRONG);
            JsonNode b = body(r);
            out.add(status(r) + " " + b.get("error").asString() + " " + r.getResponse().getHeader("Retry-After"));
        }
        return out;
    }

    // ---- registration ------------------------------------------------------------------------------------------

    @Test
    void registrationFloodFromOneIpIsLimited() throws Exception {
        for (int i = 1; i <= 3; i++) {
            MvcResult r = postFrom("10.5.5.5", "/api/auth/register", null,
                    map("username", "flood" + i, "email", "flood" + i + "@example.com", "password", PASSWORD));
            assertThat(status(r)).isEqualTo(201);
        }
        MvcResult blocked = postFrom("10.5.5.5", "/api/auth/register", null,
                map("username", "flood4", "email", "flood4@example.com", "password", PASSWORD));
        assertThat(status(blocked)).isEqualTo(429);
        assertThat(Long.parseLong(blocked.getResponse().getHeader("Retry-After"))).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(3);

        MvcResult other = postFrom("10.5.5.6", "/api/auth/register", null,
                map("username", "flood4", "email", "flood4@example.com", "password", PASSWORD));
        assertThat(status(other)).as("another IP can still register").isEqualTo(201);
    }

    // ---- password change ---------------------------------------------------------------------------------------

    private MvcResult changePassword(String ip, String token, String current) throws Exception {
        return postFrom(ip, "/api/users/me/password", token, map("currentPassword", current, "newPassword", "N3w-Passw0rd!x"));
    }

    @Test
    void passwordChangeIsLimitedPerUser() throws Exception {
        String alice = register("alice");
        String bob = register("bob");
        limits.clear();
        for (int i = 0; i < 3; i++) assertThat(status(changePassword("10.6.6.1", alice, WRONG))).isEqualTo(400);
        assertThat(status(changePassword("10.6.6.2", alice, WRONG))).as("same user, other IP").isEqualTo(429);
        assertThat(status(changePassword("10.6.6.2", alice, PASSWORD))).as("even the right password").isEqualTo(429);
        assertThat(status(changePassword("10.6.6.3", bob, WRONG))).as("another user").isEqualTo(400);
    }

    @Test
    void passwordChangeIsAlsoLimitedPerIpAcrossUsers() throws Exception {
        String[] tokens = {register("user1"), register("user2"), register("user3")};
        limits.clear();
        assertThat(status(changePassword("10.7.7.7", tokens[0], WRONG))).isEqualTo(400);
        assertThat(status(changePassword("10.7.7.7", tokens[0], WRONG))).isEqualTo(400);
        assertThat(status(changePassword("10.7.7.7", tokens[1], WRONG))).isEqualTo(400);
        assertThat(status(changePassword("10.7.7.7", tokens[1], WRONG))).isEqualTo(400);
        assertThat(status(changePassword("10.7.7.7", tokens[2], WRONG))).as("5th from the IP").isEqualTo(429);
        assertThat(status(changePassword("10.7.7.8", tokens[2], WRONG))).isEqualTo(400);
    }

    @Test
    void anUnauthenticatedPasswordChangeIsStill401NotCountedAs429() throws Exception {
        for (int i = 0; i < 10; i++) assertThat(status(changePassword("10.7.8.1", null, WRONG))).isEqualTo(401);
    }

    // ---- money endpoints ---------------------------------------------------------------------------------------

    @Test
    void moneyEndpointsShareOnePerUserLimit() throws Exception {
        String alice = register("alice");
        String bob = register("bob");
        limits.clear();
        assertThat(status(postFrom("10.8.8.1", "/api/transactions/transfer", alice,
                map("receiverUsername", "bob", "amount", 1)))).isEqualTo(201);
        assertThat(status(postFrom("10.8.8.1", "/api/transactions/transfer", alice,
                map("receiverUsername", "bob", "amount", 1)))).isEqualTo(201);
        assertThat(status(postFrom("10.8.8.1", "/api/wallets/me/withdraw", alice, map("amount", 1)))).isEqualTo(201);
        assertThat(status(postFrom("10.8.8.1", "/api/deposit-requests", alice, map("amount", 5)))).isEqualTo(201);
        MvcResult blocked = postFrom("10.8.8.9", "/api/transactions/transfer", alice,
                map("receiverUsername", "bob", "amount", 1));
        assertThat(status(blocked)).isEqualTo(429);
        assertThat(balance("alice")).as("the blocked transfer moved nothing").isEqualByComparingTo("97");
        assertThat(status(postFrom("10.8.8.1", "/api/wallets/me/withdraw", alice, map("amount", 1)))).isEqualTo(429);
        assertThat(status(postFrom("10.8.8.1", "/api/wallets/me/withdraw", bob, map("amount", 1)))).as("bob").isEqualTo(201);
        assertThat(status(getFrom("10.8.8.1", "/api/wallets/me", alice))).as("reads are not money moves").isEqualTo(200);
    }

    @Test
    void adminEndpointsHaveTheirOwnPerUserLimit() throws Exception {
        register("alice");
        String admin = adminToken();
        limits.clear();
        for (int i = 0; i < 4; i++) {
            assertThat(status(postFrom("10.9.0.1", "/api/admin/wallets/" + walletId("alice") + "/deposit", admin,
                    map("amount", 1)))).isEqualTo(201);
        }
        assertThat(status(postFrom("10.9.0.1", "/api/admin/wallets/" + walletId("alice") + "/deposit", admin,
                map("amount", 1)))).isEqualTo(429);
        assertThat(status(getFrom("10.9.0.1", "/api/admin/users", admin))).isEqualTo(200);
    }

    // ---- X-Forwarded-For is ignored by default ----------------------------------------------------------------

    @Test
    void aSpoofedForwardedHeaderDoesNotGiveANewBucket() throws Exception {
        for (int i = 1; i <= 5; i++) {
            assertThat(status(loginFrom("10.9.9.9", "spoof" + i, WRONG, "198.51.100." + i))).isEqualTo(401);
        }
        assertThat(status(loginFrom("10.9.9.9", "spoof6", WRONG, "198.51.100.77"))).isEqualTo(429);
        assertThat(status(loginFrom("10.9.9.9", "spoof7", WRONG, "1.1.1.1, 2.2.2.2"))).isEqualTo(429);
    }

    // ---- normal use and earlier behaviour are unchanged ------------------------------------------------------

    @Test
    void normalUsageUnderTheLimitsIsUnaffected() throws Exception {
        String alice = register("alice");
        limits.clear();
        for (int i = 0; i < 3; i++) assertThat(status(loginFrom("10.10.0.1", "alice", PASSWORD))).isEqualTo(200);
        for (int i = 0; i < 50; i++) assertThat(status(getFrom("10.10.0.1", "/api/wallets/me", alice))).isEqualTo(200);
        assertThat(status(getFrom("10.10.0.1", "/api/users/me", alice))).isEqualTo(200);
    }

    @Test
    void validationUnsupportedMediaTypeDisabledAndRevokedBehaviourIsUnchanged() throws Exception {
        String alice = register("alice");
        String bob = register("bob");
        jdbc.update("update users set enabled=false where username='bob'");
        limits.clear();

        MvcResult blank = postFrom("10.11.0.1", "/api/auth/login", null, map("username", "alice", "password", ""));
        assertThat(status(blank)).isEqualTo(400);
        assertThat(body(blank).get("error").asString()).contains("password");

        MvcResult malformed = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/auth/login").contentType("application/json").content("{not json")).andReturn();
        assertThat(status(malformed)).isEqualTo(400);

        MvcResult wrongType = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/auth/login").contentType("text/plain").content("alice")).andReturn();
        assertThat(status(wrongType)).isEqualTo(415);

        MvcResult disabled = loginFrom("10.11.0.1", "bob", PASSWORD);
        MvcResult unknown = loginFrom("10.11.0.1", "ghost", PASSWORD);
        assertThat(status(disabled)).isEqualTo(401);
        assertThat(body(disabled).get("error").asString()).isEqualTo(body(unknown).get("error").asString())
                .isEqualTo("Invalid username or password");

        assertThat(status(postFrom("10.11.0.1", "/api/auth/logout", alice, map()))).isEqualTo(204);
        assertThat(status(getFrom("10.11.0.1", "/api/wallets/me", alice))).as("revoked token").isEqualTo(401);
        assertThat(status(getFrom("10.11.0.1", "/error", null))).isEqualTo(401);
        assertThat(bob).isNotBlank();
    }

    @Test
    void aSuccessfulLoginStillWorksAndTheBodyReachesTheController() throws Exception {
        register("alice");
        limits.clear();
        MvcResult ok = loginFrom("10.12.0.1", "  Alice ", PASSWORD);
        assertThat(status(ok)).isEqualTo(200);
        assertThat(body(ok).get("token").asString()).isNotBlank();
    }
}
