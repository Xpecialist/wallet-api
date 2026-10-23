package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.wallet.security.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MvcResult;

/** L4: tokens are revoked by the per-user token version (password change, logout, admin disable). */
class TokenRevocationIT extends ItBase {
    private static final String NEW_PASSWORD = "BrandNew!456";

    @Autowired JwtService jwt;
    @Value("${app.jwt.secret}") String secret;

    private int status(String path, String token) throws Exception {
        return getJson(path, token).getResponse().getStatus();
    }

    private int me(String token) throws Exception { return status("/api/users/me", token); }

    private int logout(String token) throws Exception {
        return postJson("/api/auth/logout", token, map(), null).getResponse().getStatus();
    }

    private long version(String username) {
        return jdbc.queryForObject("select token_version from users where username=?", Long.class, username);
    }

    private long claim(String token) {
        var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().get("tv", Long.class);
    }

    private MvcResult changePassword(String token, String current, String next) throws Exception {
        return postJson("/api/users/me/password", token, map("currentPassword", current, "newPassword", next), null);
    }

    private List<Integer> runParallel(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> t : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return t.call();
            }));
        }
        ready.await();
        go.countDown();
        List<Integer> out = new ArrayList<>();
        try {
            for (Future<Integer> f : futures) out.add(f.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    // ---- tokens carry the version -----------------------------------------------------------------------------

    @Test
    void registerAndLoginTokensCarryTheCurrentVersionAndWork() throws Exception {
        String registered = register("alice");
        assertThat(claim(registered)).isZero();
        assertThat(me(registered)).isEqualTo(200);

        assertThat(logout(registered)).isEqualTo(204);
        assertThat(version("alice")).isEqualTo(1);
        String loggedIn = login("alice", PASSWORD);
        assertThat(claim(loggedIn)).isEqualTo(1);
        assertThat(me(loggedIn)).isEqualTo(200);
    }

    @Test
    void tokenWithoutTvClaimIsRejectedEverywhere() throws Exception {
        register("alice");
        var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        String legacy = Jwts.builder().subject("alice").issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(key).compact();

        assertThat(me(legacy)).isEqualTo(401);
        assertThat(status("/api/wallets/me", legacy)).isEqualTo(401);
        assertThat(logout(legacy)).isEqualTo(401);
        assertThat(version("alice")).as("a rejected token cannot bump anything").isZero();
    }

    @Test
    void tokenWithWrongVersionIsRejectedWhetherLowerOrHigher() throws Exception {
        String good = register("alice");
        logout(good);
        login("alice", PASSWORD); // stored version is now 1
        assertThat(me(jwt.generateToken("alice", 0))).as("older").isEqualTo(401);
        assertThat(me(jwt.generateToken("alice", 2))).as("newer").isEqualTo(401);
        assertThat(me(jwt.generateToken("alice", -1))).isEqualTo(401);
        assertThat(me(jwt.generateToken("alice", 1))).as("exact match").isEqualTo(200);
    }

    // ---- password change ----------------------------------------------------------------------------------------

    @Test
    void oldTokenIsRejectedAfterPasswordChangeWhileTheReturnedTokenWorks() throws Exception {
        String t1 = register("alice");
        String otherDevice = login("alice", PASSWORD);

        MvcResult changed = changePassword(t1, PASSWORD, NEW_PASSWORD);

        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        String fresh = body(changed).get("token").asText();
        assertThat(version("alice")).isEqualTo(1);
        assertThat(claim(fresh)).isEqualTo(1);
        assertThat(me(fresh)).isEqualTo(200);
        assertThat(me(t1)).as("the token that made the change").isEqualTo(401);
        assertThat(me(otherDevice)).as("other session").isEqualTo(401);
        assertThat(logout(otherDevice)).isEqualTo(401);
        assertThat(login("alice", NEW_PASSWORD)).isNotBlank();
    }

    @Test
    void failedPasswordChangeRevokesNothing() throws Exception {
        String t = register("alice");
        assertThat(changePassword(t, "wrong-wrong-1", NEW_PASSWORD).getResponse().getStatus()).isEqualTo(400);
        assertThat(changePassword(t, PASSWORD, PASSWORD).getResponse().getStatus()).isEqualTo(400);
        assertThat(changePassword(t, PASSWORD, "short").getResponse().getStatus()).isEqualTo(400);
        assertThat(version("alice")).isZero();
        assertThat(me(t)).isEqualTo(200);
    }

    @Test
    void passwordChangeOfOneUserDoesNotTouchAnother() throws Exception {
        String alice = register("alice");
        String bob = register("bob");
        changePassword(alice, PASSWORD, NEW_PASSWORD);
        assertThat(me(bob)).isEqualTo(200);
        assertThat(version("bob")).isZero();
    }

    // ---- logout -------------------------------------------------------------------------------------------------

    @Test
    void logoutKillsEveryTokenOfThatUserButNotOtherUsers() throws Exception {
        String a1 = register("alice");
        String a2 = login("alice", PASSWORD);
        String a3 = login("alice", PASSWORD);
        String bob = register("bob");
        assertThat(me(a1)).isEqualTo(200);
        assertThat(me(a2)).isEqualTo(200);

        assertThat(logout(a2)).isEqualTo(204);

        assertThat(me(a1)).as("session 1").isEqualTo(401);
        assertThat(me(a2)).as("the session that logged out").isEqualTo(401);
        assertThat(me(a3)).as("session 3").isEqualTo(401);
        assertThat(me(bob)).as("another user").isEqualTo(200);
        assertThat(version("alice")).isEqualTo(1);
        assertThat(version("bob")).isZero();
        assertThat(logout(a1)).as("revoked token cannot log out again").isEqualTo(401);
        assertThat(version("alice")).isEqualTo(1);
    }

    @Test
    void logoutNeedsAuthentication() throws Exception {
        register("alice");
        assertThat(logout(null)).isEqualTo(401);
        assertThat(logout("garbage")).isEqualTo(401);
        assertThat(version("alice")).isZero();
    }

    @Test
    void loginAfterLogoutIssuesAWorkingTokenAndWrongPasswordStillFails() throws Exception {
        String t = register("alice");
        logout(t);
        assertThat(postJson("/api/auth/login", null, map("username", "alice", "password", "nope-nope-1"), null)
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(me(login("alice", PASSWORD))).isEqualTo(200);
    }

    // ---- concurrency --------------------------------------------------------------------------------------------

    @Test
    void parallelServiceLogoutsLoseNoIncrement() throws Exception {
        register("alice");
        int n = 24;
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) tasks.add(() -> { authService.logout("alice"); return 204; });
        runParallel(tasks);
        assertThat(version("alice")).as("atomic UPDATE: exactly one increment per logout").isEqualTo(n);
    }

    @Test
    void parallelHttpLogoutsWithTheSameTokenAllEndRevokedAndNeverFailWith500() throws Exception {
        String t = register("alice");
        int n = 12;
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) tasks.add(() -> logout(t));
        List<Integer> statuses = runParallel(tasks);
        assertThat(statuses).allMatch(s -> s == 204 || s == 401);
        assertThat(statuses).contains(204);
        assertThat(version("alice")).isBetween(1L, (long) n);
        assertThat(me(t)).isEqualTo(401);
    }

    @Test
    void parallelSessionsAllDieAfterOneLogout() throws Exception {
        List<String> tokens = new ArrayList<>();
        tokens.add(register("alice"));
        for (int i = 0; i < 7; i++) tokens.add(login("alice", PASSWORD));
        List<Callable<Integer>> tasks = new ArrayList<>();
        tasks.add(() -> logout(tokens.get(0)));
        runParallel(tasks);
        for (String t : tokens) assertThat(me(t)).isEqualTo(401);
    }

    @Test
    void passwordChangeAndLogoutInParallelNeverLeaveAnOldTokenAlive() throws Exception {
        String t = register("alice");
        String other = login("alice", PASSWORD);
        String[] fresh = new String[1];
        List<Callable<Integer>> tasks = List.of(
                () -> { authService.logout("alice"); return 204; },
                () -> {
                    MvcResult r = changePassword(t, PASSWORD, NEW_PASSWORD);
                    if (r.getResponse().getStatus() == 200) fresh[0] = body(r).get("token").asText();
                    return r.getResponse().getStatus();
                });
        int changeStatus = runParallel(tasks).get(1);

        assertThat(me(t)).isEqualTo(401);
        assertThat(me(other)).isEqualTo(401);
        if (changeStatus == 200) { // the change ran before the logout revoked its token, or after it with a fresh read
            assertThat(version("alice")).as("both bumps counted").isEqualTo(2);
            // the returned token is valid exactly when its version is the stored one: never after the logout
            assertThat(me(fresh[0])).isEqualTo(claim(fresh[0]) == 2 ? 200 : 401);
        } else {
            assertThat(changeStatus).as("logout won, the change was rejected as revoked").isEqualTo(401);
            assertThat(version("alice")).isEqualTo(1);
        }
    }

    // ---- disabled users and admin disable -------------------------------------------------------------------------

    @Test
    void adminDisableBumpsTheVersionAndReEnablingNeedsAFreshLogin() throws Exception {
        String alice = register("alice");
        String admin = adminToken();
        long id = jdbc.queryForObject("select id from users where username='alice'", Long.class);
        assertThat(me(alice)).isEqualTo(200);

        assertThat(patchJson("/api/admin/users/" + id + "/status", admin, map("enabled", false))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(version("alice")).isEqualTo(1);
        assertThat(me(alice)).as("disabled").isEqualTo(401);
        assertThat(postJson("/api/auth/login", null, map("username", "alice", "password", PASSWORD), null)
                .getResponse().getStatus()).as("disabled login still 401").isEqualTo(401);

        assertThat(patchJson("/api/admin/users/" + id + "/status", admin, map("enabled", true))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(version("alice")).as("enabling does not bump").isEqualTo(1);
        assertThat(me(alice)).as("old token stays dead after re-enabling").isEqualTo(401);
        String fresh = login("alice", PASSWORD);
        assertThat(me(fresh)).isEqualTo(200);
    }

    @Test
    void disabledUserWithAMatchingVersionIsStillRejected() throws Exception {
        String alice = register("alice");
        jdbc.update("update users set enabled=false where username='alice'"); // no bump on purpose
        assertThat(version("alice")).isZero();
        assertThat(me(alice)).isEqualTo(401);
        assertThat(logout(alice)).isEqualTo(401);
        jdbc.update("update users set enabled=true where username='alice'");
        assertThat(me(alice)).as("same token works again, nothing was revoked").isEqualTo(200);
    }

    @Test
    void disablingOneUserLeavesOtherUsersTokensValid() throws Exception {
        String alice = register("alice");
        String bob = register("bob");
        String admin = adminToken();
        long id = jdbc.queryForObject("select id from users where username='alice'", Long.class);
        patchJson("/api/admin/users/" + id + "/status", admin, map("enabled", false));
        assertThat(me(alice)).isEqualTo(401);
        assertThat(me(bob)).isEqualTo(200);
        assertThat(status("/api/admin/users", admin)).as("the admin token is untouched").isEqualTo(200);
    }

    @Test
    void adminCannotDisableSelfAndNothingIsBumped() throws Exception {
        String admin = adminToken();
        long id = jdbc.queryForObject("select id from users where username='admin'", Long.class);
        assertThat(patchJson("/api/admin/users/" + id + "/status", admin, map("enabled", false))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(version("admin")).isZero();
        assertThat(status("/api/admin/users", admin)).isEqualTo(200);
    }

    @Test
    void revokedAdminTokenLosesAdminAccess() throws Exception {
        String admin = adminToken();
        assertThat(status("/api/admin/users", admin)).isEqualTo(200);
        logout(admin);
        assertThat(status("/api/admin/users", admin)).isEqualTo(401);
    }
}
