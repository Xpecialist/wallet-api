package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MvcResult;

/** Registration, login, JWT, constraints, authorization (user vs admin) and profile management on real SQL. */
class AuthAndAccessIT extends ItBase {

    // ---- register / login / JWT -------------------------------------------------------------------------------

    @Test
    void registerCreatesUserAndWalletRowsAndReturnsWorkingJwt() throws Exception {
        MvcResult r = postJson("/api/auth/register", null,
                map("username", "alice", "email", "alice@example.com", "password", PASSWORD), null);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        String token = body(r).get("token").asText();
        assertThat(body(r).get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(token.split("\\.")).hasSize(3);

        assertThat(jdbc.queryForObject("select count(*) from users where username='alice'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select role from users where username='alice'", String.class))
                .isEqualTo("USER");
        assertThat(balance("alice")).isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("select currency from wallets", String.class)).isEqualTo("USD");
        // password is stored hashed, never in clear
        String hash = jdbc.queryForObject("select password_hash from users where username='alice'", String.class);
        assertThat(hash).isNotEqualTo(PASSWORD).startsWith("$2");

        MvcResult me = getJson("/api/wallets/me", token);
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(me).get("username").asText()).isEqualTo("alice");
        assertThat(body(me).get("balance").decimalValue()).isEqualByComparingTo("100");
    }

    @Test
    void loginIssuesJwtThatAuthenticatesEndToEnd() throws Exception {
        register("bob");
        String token = login("bob", PASSWORD);
        MvcResult r = getJson("/api/users/me", token);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).get("username").asText()).isEqualTo("bob");
        assertThat(body(r).has("passwordHash")).isFalse();
        assertThat(body(r).has("password")).isFalse();
    }

    @Test
    void loginWithWrongPasswordOrUnknownUserIs401() throws Exception {
        register("bob");
        assertThat(postJson("/api/auth/login", null, map("username", "bob", "password", "nope-nope-1"), null)
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(postJson("/api/auth/login", null, map("username", "ghost", "password", PASSWORD), null)
                .getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void protectedEndpointsRejectMissingAndGarbageAndTamperedTokens() throws Exception {
        String token = register("bob");
        assertThat(getJson("/api/wallets/me", null).getResponse().getStatus()).isEqualTo(401);
        assertThat(getJson("/api/wallets/me", "garbage").getResponse().getStatus()).isEqualTo(401);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
        assertThat(getJson("/api/wallets/me", tampered).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void tokenOfDeletedUserIsRejected() throws Exception {
        String token = register("bob");
        jdbc.execute("TRUNCATE TABLE transactions, wallets, users RESTART IDENTITY CASCADE");
        assertThat(getJson("/api/wallets/me", token).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void registrationValidationFailuresAre400AndPersistNothing() throws Exception {
        assertThat(postJson("/api/auth/register", null,
                map("username", "ab", "email", "x@example.com", "password", PASSWORD), null)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/api/auth/register", null,
                map("username", "carol", "email", "not-an-email", "password", PASSWORD), null)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/api/auth/register", null,
                map("username", "carol", "email", "c@example.com", "password", "short"), null)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();
    }

    // ---- duplicate username / email ---------------------------------------------------------------------------

    @Test
    void duplicateUsernameIs409AndCreatesNoExtraRows() throws Exception {
        register("alice");
        MvcResult r = postJson("/api/auth/register", null,
                map("username", "alice", "email", "other@example.com", "password", PASSWORD), null);
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r).get("error").asText()).contains("Username");
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wallets", Long.class)).isEqualTo(1);
    }

    @Test
    void duplicateEmailIs409AndCreatesNoExtraRows() throws Exception {
        register("alice");
        MvcResult r = postJson("/api/auth/register", null,
                map("username", "alice2", "email", "alice@example.com", "password", PASSWORD), null);
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r).get("error").asText()).contains("Email");
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wallets", Long.class)).isEqualTo(1);
    }

    @Test
    void databaseUniqueConstraintsRejectDuplicatesEvenBypassingTheService() throws Exception {
        register("alice");
        assertThatThrownBy(() -> jdbc.update("insert into users(username,email,password_hash) values "
                + "('alice','z@example.com','x')")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into users(username,email,password_hash) values "
                + "('zed','alice@example.com','x')")).isInstanceOf(DataIntegrityViolationException.class);
        // one wallet per user
        assertThatThrownBy(() -> jdbc.update("insert into wallets(user_id,balance) select id,0 from users"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentRegistrationsOfSameUsernameYieldExactlyOneUserAndNoServerError() throws Exception {
        int n = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int k = i;
            fs.add(pool.submit(() -> {
                start.await();
                return postJson("/api/auth/register", null,
                        map("username", "racer", "email", "racer" + k + "@example.com", "password", PASSWORD), null)
                        .getResponse().getStatus();
            }));
        }
        start.countDown();
        int created = 0;
        for (var f : fs) {
            int s = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(s).as("no 5xx on racing registration").isIn(201, 409);
            if (s == 201) created++;
        }
        pool.shutdownNow();
        assertThat(created).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from users where username='racer'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wallets", Long.class)).isEqualTo(1);
    }

    // ---- disabled accounts -------------------------------------------------------------------------------------

    @Test
    void disabledUserCannotLoginAndExistingTokenStopsWorking() throws Exception {
        String admin = adminToken();
        String token = register("bob");
        long bobId = jdbc.queryForObject("select id from users where username='bob'", Long.class);

        MvcResult r = patchJson("/api/admin/users/" + bobId + "/status", admin, map("enabled", false));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select enabled from users where id=?", Boolean.class, bobId)).isFalse();

        // a disabled account is indistinguishable from a wrong password: same status, same body
        MvcResult disabledRight = postJson("/api/auth/login", null, map("username", "bob", "password", PASSWORD), null);
        MvcResult disabledWrong = postJson("/api/auth/login", null, map("username", "bob", "password", "nope-nope-1"), null);
        MvcResult unknown = postJson("/api/auth/login", null, map("username", "ghost", "password", PASSWORD), null);
        for (MvcResult r2 : new MvcResult[] {disabledRight, disabledWrong, unknown}) {
            assertThat(r2.getResponse().getStatus()).isEqualTo(401);
            assertThat(body(r2).get("error").asText()).isEqualTo("Invalid username or password");
        }
        assertThat(getJson("/api/wallets/me", token).getResponse().getStatus()).isEqualTo(401);

        assertThat(patchJson("/api/admin/users/" + bobId + "/status", admin, map("enabled", true))
                .getResponse().getStatus()).isEqualTo(200);
        // disabling bumped the token version: the old token stays dead after re-enabling, a fresh login works
        assertThat(getJson("/api/wallets/me", token).getResponse().getStatus()).isEqualTo(401);
        assertThat(getJson("/api/wallets/me", login("bob", PASSWORD)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void adminCannotDisableSelfAndUnknownUserIs404() throws Exception {
        String admin = adminToken();
        long adminId = jdbc.queryForObject("select id from users where username='admin'", Long.class);
        assertThat(patchJson("/api/admin/users/" + adminId + "/status", admin, map("enabled", false))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select enabled from users where id=?", Boolean.class, adminId)).isTrue();
        assertThat(patchJson("/api/admin/users/99999/status", admin, map("enabled", false))
                .getResponse().getStatus()).isEqualTo(404);
    }

    // ---- admin vs user authorization ---------------------------------------------------------------------------

    @Test
    void adminEndpointsAreForbiddenForUsersAndUnauthenticatedButOpenToAdmin() throws Exception {
        String admin = adminToken();
        String user = register("bob");
        long bobWallet = walletId("bob");

        String[] gets = {"/api/admin/users", "/api/admin/transactions"};
        for (String url : gets) {
            assertThat(getJson(url, null).getResponse().getStatus()).as(url + " anon").isEqualTo(401);
            assertThat(getJson(url, user).getResponse().getStatus()).as(url + " user").isEqualTo(403);
            assertThat(getJson(url, admin).getResponse().getStatus()).as(url + " admin").isEqualTo(200);
        }
        assertThat(postJson("/api/admin/wallets/" + bobWallet + "/deposit", user, map("amount", 5), null)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(patchJson("/api/admin/users/1/status", user, map("enabled", false))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(balance("bob")).isEqualByComparingTo("100");
        assertThat(txCount()).isZero();
    }

    @Test
    void adminDepositCreditsAnyWalletAndRecordsDepositRow() throws Exception {
        String admin = adminToken();
        register("bob");
        MvcResult r = postJson("/api/admin/wallets/" + walletId("bob") + "/deposit", admin, map("amount", 25.5), null);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(balance("bob")).isEqualByComparingTo("125.5");
        assertThat(jdbc.queryForObject("select type from transactions", String.class)).isEqualTo("DEPOSIT");
        assertThat(postJson("/api/admin/wallets/99999/deposit", admin, map("amount", 1), null)
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void adminListsUsersPagedWithoutPasswordHashes() throws Exception {
        String admin = adminToken();
        register("user1");
        register("user2");
        MvcResult r = getJson("/api/admin/users?size=2&page=0", admin);
        assertThat(body(r).get("totalElements").asLong()).isEqualTo(3);
        assertThat(body(r).get("totalPages").asInt()).isEqualTo(2);
        assertThat(body(r).get("content")).hasSize(2);
        assertThat(r.getResponse().getContentAsString()).doesNotContain("password");
        // default sort is id ascending
        assertThat(body(r).get("content").get(0).get("username").asText()).isEqualTo("admin");
    }

    @Test
    void usersCanOnlyReadTheirOwnWalletByIdAdminCanReadAny() throws Exception {
        String admin = adminToken();
        String alice = register("alice");
        register("bob");
        long aliceW = walletId("alice");
        long bobW = walletId("bob");
        assertThat(getJson("/api/wallets/" + aliceW, alice).getResponse().getStatus()).isEqualTo(200);
        // a wallet that exists but is not yours looks exactly like one that does not exist
        MvcResult foreign = getJson("/api/wallets/" + bobW, alice);
        MvcResult missing = getJson("/api/wallets/99999", alice);
        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(foreign).get("error").asText()).isEqualTo(body(missing).get("error").asText());
        assertThat(getJson("/api/wallets/" + bobW, admin).getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson("/api/wallets/99999", alice).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void usersCanOnlyReadTransactionsTheyTakePartInAdminCanReadAny() throws Exception {
        String admin = adminToken();
        String alice = register("alice");
        String bob = register("bob");
        String carol = register("carol");
        long id = body(transfer(alice, "bob", "10", null)).get("id").asLong();
        assertThat(getJson("/api/transactions/" + id, alice).getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson("/api/transactions/" + id, bob).getResponse().getStatus()).isEqualTo(200);
        MvcResult foreign = getJson("/api/transactions/" + id, carol);
        MvcResult missing = getJson("/api/transactions/99999", carol);
        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(foreign).get("error").asText()).isEqualTo(body(missing).get("error").asText());
        assertThat(getJson("/api/transactions/" + id, admin).getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson("/api/transactions/99999", alice).getResponse().getStatus()).isEqualTo(404);
    }

    // ---- profile ------------------------------------------------------------------------------------------------

    @Test
    void profileEmailUpdateIsPersisted() throws Exception {
        String t = register("alice");
        MvcResult r = patchJson("/api/users/me", t, map("email", "new@example.com"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).get("email").asText()).isEqualTo("new@example.com");
        assertThat(jdbc.queryForObject("select email from users where username='alice'", String.class))
                .isEqualTo("new@example.com");
    }

    @Test
    void profileEmailUpdateToSameEmailIsOkAndToTakenEmailIs409() throws Exception {
        String t = register("alice");
        register("bob");
        assertThat(patchJson("/api/users/me", t, map("email", "alice@example.com")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(patchJson("/api/users/me", t, map("email", "bob@example.com")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(jdbc.queryForObject("select email from users where username='alice'", String.class))
                .isEqualTo("alice@example.com");
        assertThat(patchJson("/api/users/me", t, map("email", "garbage")).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void changePasswordRotatesCredentialsInDatabase() throws Exception {
        String t = register("alice");
        String oldHash = jdbc.queryForObject("select password_hash from users", String.class);

        assertThat(postJson("/api/users/me/password", t,
                map("currentPassword", "wrong-wrong-1", "newPassword", "BrandNew!456"), null)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select password_hash from users", String.class)).isEqualTo(oldHash);

        MvcResult changed = postJson("/api/users/me/password", t,
                map("currentPassword", PASSWORD, "newPassword", "BrandNew!456"), null);
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        String fresh = body(changed).get("token").asText();
        assertThat(jdbc.queryForObject("select password_hash from users", String.class)).isNotEqualTo(oldHash);

        assertThat(postJson("/api/auth/login", null, map("username", "alice", "password", PASSWORD), null)
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(login("alice", "BrandNew!456")).isNotBlank();

        assertThat(postJson("/api/users/me/password", fresh,
                map("currentPassword", "BrandNew!456", "newPassword", "short"), null)
                .getResponse().getStatus()).isEqualTo(400);
    }
}
