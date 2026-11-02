package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.wallet.entity.User;
import com.example.wallet.repository.UserRepository;
import com.example.wallet.service.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M-2 / R4: {@code users} must never be written whole-row from a stale snapshot. Password change, email change and
 * admin disable each write only their own columns, so none can undo another, and a revoked token cannot finish a
 * password change. Races run through the real filter chain on {@code walletdb_test}.
 */
class UserUpdateRaceIT extends ItBase {
    private static final String NEW_PASSWORD = "BrandNew!456";
    private static final int ITERATIONS = 25;

    @Autowired UserRepository users;
    @Autowired UserService userService;

    private long id(String username) {
        return jdbc.queryForObject("select id from users where username=?", Long.class, username);
    }

    private boolean enabled(String username) {
        return jdbc.queryForObject("select enabled from users where username=?", Boolean.class, username);
    }

    private long version(String username) {
        return jdbc.queryForObject("select token_version from users where username=?", Long.class, username);
    }

    private String email(String username) {
        return jdbc.queryForObject("select email from users where username=?", String.class, username);
    }

    private MvcResult changePassword(String token, String current, String next) throws Exception {
        return postJson("/api/users/me/password", token, map("currentPassword", current, "newPassword", next), null);
    }

    private MvcResult setStatus(String adminToken, String username, boolean enabled) throws Exception {
        return patchJson("/api/admin/users/" + id(username) + "/status", adminToken, map("enabled", enabled));
    }

    private int me(String token) throws Exception { return getJson("/api/users/me", token).getResponse().getStatus(); }

    // ---- the root cause, deterministically ---------------------------------------------------------------------

    @Test
    void aStaleEntitySavedAfterADisableCannotReEnableOrOverwriteAnything() throws Exception {
        register("bob");
        User stale = users.findByUsername("bob").orElseThrow(); // detached snapshot: enabled, old hash, old email
        String oldHash = stale.getPasswordHash();
        String admin = adminToken();

        assertThat(setStatus(admin, "bob", false).getResponse().getStatus()).isEqualTo(200);
        stale.setEmail("stale@example.com");
        stale.setPasswordHash("stale-hash");
        stale.setEnabled(true);
        users.save(stale);

        assertThat(enabled("bob")).as("a stale whole-row save must not re-enable the user").isFalse();
        assertThat(jdbc.queryForObject("select password_hash from users where username='bob'", String.class))
                .isNotEqualTo("stale-hash").isEqualTo(oldHash);
        assertThat(version("bob")).as("the disable bump survives").isEqualTo(1);
    }

    // ---- password change racing an admin disable ------------------------------------------------------------------

    @Test
    void aPasswordChangeRacingADisableAlwaysEndsDisabledAndItsTokenIsRejected() throws Exception {
        String admin = adminToken();
        for (int i = 0; i < ITERATIONS; i++) {
            String name = "user" + i;
            String token = register(name);
            String[] fresh = new String[1];
            List<Callable<MvcResult>> tasks = List.of(
                    () -> setStatus(admin, name, false),
                    () -> {
                        MvcResult r = changePassword(token, PASSWORD, NEW_PASSWORD);
                        if (r.getResponse().getStatus() == 200) fresh[0] = body(r).get("token").asText();
                        return r;
                    });
            List<Integer> statuses = statusesOf(runParallelResults(tasks));

            assertThat(statuses.get(0)).as(name + " disable").isEqualTo(200);
            assertThat(statuses.get(1)).as(name + " change").isIn(200, 401);
            assertThat(enabled(name)).as(name + ": the change must not re-enable the user").isFalse();
            assertThat(me(token)).isEqualTo(401);
            if (fresh[0] != null) {
                assertThat(me(fresh[0])).as(name + ": the token the change returned is rejected").isEqualTo(401);
            }
            assertThat(version(name)).as(name + ": no lost bump").isEqualTo(statuses.get(1) == 200 ? 2 : 1);
            assertThat(loginStatus(name, statuses.get(1) == 200 ? NEW_PASSWORD : PASSWORD)).as("disabled").isEqualTo(401);
        }
    }

    private int loginStatus(String name, String password) throws Exception {
        return postJson("/api/auth/login", null, map("username", name, "password", password), null)
                .getResponse().getStatus();
    }

    @Test
    void anEmailChangeRacingADisableAlwaysEndsDisabled() throws Exception {
        String admin = adminToken();
        for (int i = 0; i < ITERATIONS; i++) {
            String name = "mail" + i;
            String token = register(name);
            String newEmail = "new" + i + "@example.com";
            List<Callable<MvcResult>> tasks = List.of(
                    () -> setStatus(admin, name, false),
                    () -> patchJson("/api/users/me", token, map("email", newEmail)));
            List<Integer> statuses = statusesOf(runParallelResults(tasks));

            assertThat(statuses.get(0)).isEqualTo(200);
            assertThat(statuses.get(1)).isIn(200, 401);
            assertThat(enabled(name)).as(name).isFalse();
            assertThat(version(name)).isEqualTo(1);
            assertThat(email(name)).isEqualTo(statuses.get(1) == 200 ? newEmail : name + "@example.com");
        }
    }

    @Test
    void aDisableDoesNotRevertAConcurrentPasswordOrEmailChange() throws Exception {
        String admin = adminToken();
        for (int i = 0; i < 10; i++) {
            String name = "both" + i;
            String token = register(name);
            List<Callable<MvcResult>> tasks = List.of(
                    () -> setStatus(admin, name, false),
                    () -> changePassword(token, PASSWORD, NEW_PASSWORD));
            List<Integer> statuses = statusesOf(runParallelResults(tasks));
            String hash = jdbc.queryForObject("select password_hash from users where username=?", String.class, name);
            if (statuses.get(1) == 200) {
                // the new password is what is stored, whatever the order was
                assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                        .matches(NEW_PASSWORD, hash)).as(name + " keeps the new password").isTrue();
            }
            assertThat(enabled(name)).isFalse();
        }
    }

    // ---- password change racing a logout (R4) -------------------------------------------------------------------

    @Test
    void aPasswordChangeRacingALogoutNeverHandsOutAUsableToken() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            String name = "out" + i;
            String token = register(name);
            String[] fresh = new String[1];
            List<Callable<MvcResult>> tasks = List.of(
                    () -> postJson("/api/auth/logout", token, map(), null),
                    () -> {
                        MvcResult r = changePassword(token, PASSWORD, NEW_PASSWORD);
                        if (r.getResponse().getStatus() == 200) fresh[0] = body(r).get("token").asText();
                        return r;
                    });
            List<Integer> statuses = statusesOf(runParallelResults(tasks));

            assertThat(statuses.get(0)).isEqualTo(204);
            assertThat(statuses.get(1)).as(name).isIn(200, 401);
            assertThat(me(token)).isEqualTo(401);
            if (fresh[0] != null) {
                assertThat(me(fresh[0])).as(name + ": a revoked token must not get a fresh usable token").isEqualTo(401);
            }
            assertThat(version(name)).isEqualTo(statuses.get(1) == 200 ? 2 : 1);
        }
    }

    @Test
    void aRevokedTokenThatPassedTheFilterCannotFinishAPasswordChange() throws Exception {
        register("alice");
        long staleVersion = version("alice");
        authService.logout("alice"); // the version moves on while the "in-flight" call still holds the old one

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> userService.changePassword("alice", staleVersion,
                new com.example.wallet.dto.ChangePasswordRequest(PASSWORD, NEW_PASSWORD)))
                .isInstanceOfSatisfying(com.example.wallet.exception.ApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(401));
        assertThat(loginStatus("alice", PASSWORD)).as("the password did not change").isEqualTo(200);
        assertThat(version("alice")).isEqualTo(1);
    }

    @Test
    void parallelPasswordChangesWithOneTokenHaveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            String name = "pw" + round;
            String token = register(name);
            List<Callable<MvcResult>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String next = "Different!Pass" + i + "x";
                tasks.add(() -> changePassword(token, PASSWORD, next));
            }
            List<Integer> statuses = statusesOf(runParallelResults(tasks));
            assertThat(statuses.stream().filter(s -> s == 200).count()).as(name + " " + statuses).isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s != 200)).as(statuses.toString()).allMatch(s -> s == 401);
            assertThat(version(name)).as("one bump, none lost or doubled").isEqualTo(1);
        }
    }

    @Test
    void passwordChangesLogoutsAndDisablesLoseNoBump() throws Exception {
        String admin = adminToken();
        String name = "bumps";
        String token = register(name);
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) tasks.add(() -> {
            authService.logout(name);
            return null;
        });
        tasks.add(() -> setStatus(admin, name, false));
        tasks.add(() -> changePassword(token, PASSWORD, NEW_PASSWORD));
        List<MvcResult> results = runParallelResults(tasks);
        int change = results.get(7).getResponse().getStatus();
        assertThat(version(name)).as("6 logouts + 1 disable (+1 if the change got through)")
                .isEqualTo(7 + (change == 200 ? 1 : 0));
        assertThat(enabled(name)).isFalse();
    }
}
