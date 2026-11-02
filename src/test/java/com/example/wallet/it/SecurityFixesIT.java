package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.wallet.entity.Role;
import com.example.wallet.repository.UserRepository;
import com.example.wallet.service.AdminBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MvcResult;

/** Round 1 security fixes (L1, L3, L5, L6, L7, H2) end to end on real SQL. */
class SecurityFixesIT extends ItBase {
    @Autowired UserRepository userRepository;

    private MvcResult registerRaw(String username, String email, String password) throws Exception {
        return postJson("/api/auth/register", null, map("username", username, "email", email, "password", password),
                null);
    }

    // ---- L1: case/whitespace-insensitive usernames and emails -------------------------------------------------

    @Test
    void usernameDuplicatesIgnoringCaseAndWhitespaceAre409() throws Exception {
        register("alice");
        for (String dup : new String[] {"ALICE", "Alice", " alice", "alice ", "  aLiCe  "}) {
            MvcResult r = registerRaw(dup, "other-" + dup.trim() + "@example.com", PASSWORD);
            assertThat(r.getResponse().getStatus()).as("username '" + dup + "'").isEqualTo(409);
            assertThat(body(r).get("error").asText()).contains("Username");
        }
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(1);
    }

    @Test
    void emailDuplicatesIgnoringCaseAndWhitespaceAre409() throws Exception {
        register("alice");
        for (String dup : new String[] {"ALICE@example.com", "Alice@EXAMPLE.com"}) {
            MvcResult r = registerRaw("someoneelse", dup, PASSWORD);
            assertThat(r.getResponse().getStatus()).as("email '" + dup + "'").isEqualTo(409);
            assertThat(body(r).get("error").asText()).contains("Email");
        }
        // an email padded with spaces is not a valid address at all (bean validation), so it cannot sneak past either
        assertThat(registerRaw("someoneelse", " alice@example.com ", PASSWORD).getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(1);
    }

    @Test
    void registrationTrimsButKeepsDisplayCasing() throws Exception {
        MvcResult r = registerRaw("  Carol  ", "Carol@Example.com", PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select username from users", String.class)).isEqualTo("Carol");
        assertThat(jdbc.queryForObject("select email from users", String.class)).isEqualTo("Carol@Example.com");
        String token = body(r).get("token").asText();
        assertThat(body(getJson("/api/users/me", token)).get("username").asText()).isEqualTo("Carol");
    }

    @Test
    void usernameThatIsTooShortAfterTrimmingIs400() throws Exception {
        assertThat(registerRaw("  ab  ", "x@example.com", PASSWORD).getResponse().getStatus()).isEqualTo(400);
        assertThat(registerRaw("     ", "x@example.com", PASSWORD).getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();
    }

    @Test
    void loginAndLookupsIgnoreCaseAndWhitespace() throws Exception {
        registerRaw("Bob", "bob@example.com", PASSWORD);
        register("alice");
        for (String name : new String[] {"bob", "BOB", " Bob ", "bOb"}) {
            MvcResult r = postJson("/api/auth/login", null, map("username", name, "password", PASSWORD), null);
            assertThat(r.getResponse().getStatus()).as("login as '" + name + "'").isEqualTo(200);
            // the token carries the stored username, so it works everywhere
            assertThat(body(getJson("/api/users/me", body(r).get("token").asText())).get("username").asText())
                    .isEqualTo("Bob");
        }
        // transfer receivers are matched the same way
        String alice = login("alice", PASSWORD);
        setBalance("alice", "50");
        assertThat(transfer(alice, "  BOB ", "5", null).getResponse().getStatus()).isEqualTo(201);
        assertThat(balance("Bob")).isEqualByComparingTo("105");
    }

    @Test
    void databaseRejectsCaseInsensitiveDuplicatesEvenBypassingTheService() throws Exception {
        register("alice");
        assertThatThrownBy(() -> jdbc.update("insert into users(username,email,password_hash) values "
                + "('ALICE','z@example.com','x')")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into users(username,email,password_hash) values "
                + "('zed','ALICE@example.com','x')")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void profileEmailChangeIsCaseInsensitiveToo() throws Exception {
        String t = register("alice");
        register("bob");
        assertThat(patchJson("/api/users/me", t, map("email", "BOB@example.com")).getResponse().getStatus())
                .isEqualTo(409);
        MvcResult ok = patchJson("/api/users/me", t, map("email", "New@Example.com"));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(ok).get("email").asText()).isEqualTo("New@Example.com");
    }

    // ---- L3: password length in bytes, new != current ---------------------------------------------------------

    @Test
    void passwordsOver72BytesAreRejectedExactly72AreAccepted() throws Exception {
        String ok72 = "é".repeat(36); // 36 characters, 72 bytes
        String bad74 = "é".repeat(37); // 37 characters, 74 bytes: BCrypt would silently ignore the tail
        MvcResult bad = registerRaw("alice", "alice@example.com", bad74);
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(bad).get("error").asText()).contains("password").contains("72 bytes");
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();

        MvcResult good = registerRaw("alice", "alice@example.com", ok72);
        assertThat(good.getResponse().getStatus()).isEqualTo(201);
        String token = body(good).get("token").asText();
        assertThat(login("alice", ok72)).isNotBlank();

        assertThat(postJson("/api/users/me/password", token, map("currentPassword", ok72, "newPassword", bad74), null)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/api/users/me/password", token, map("currentPassword", ok72, "newPassword", ok72), null)
                .getResponse().getStatus()).as("new equals current").isEqualTo(400);
        assertThat(login("alice", ok72)).isNotBlank(); // still the old password
        assertThat(postJson("/api/users/me/password", token, map("currentPassword", ok72, "newPassword", "Another-Pass1"),
                null).getResponse().getStatus()).isEqualTo(200);
    }

    // ---- L5: reserved admin identity and bootstrap ------------------------------------------------------------

    @Test
    void adminUsernameAndEmailCannotBeSquattedThroughRegistration() throws Exception {
        for (String name : new String[] {"admin", "ADMIN", " Admin "}) {
            assertThat(registerRaw(name, "evil@example.com", PASSWORD).getResponse().getStatus())
                    .as("username '" + name + "'").isEqualTo(409);
        }
        for (String mail : new String[] {"admin@example.com", "Admin@Example.COM"}) {
            assertThat(registerRaw("mallory", mail, PASSWORD).getResponse().getStatus())
                    .as("email '" + mail + "'").isEqualTo(409);
        }
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();
    }

    @Test
    void bootstrapCreatesTheAdminOnceWithAStrongPassword() throws Exception {
        new AdminBootstrap(authService, userRepository, "admin", "admin@example.com", "a-strong-admin-pass").run();
        assertThat(jdbc.queryForObject("select role from users where username='admin'", String.class)).isEqualTo("ADMIN");
        assertThat(login("admin", "a-strong-admin-pass")).isNotBlank();
        // second start: nothing changes, no error
        new AdminBootstrap(authService, userRepository, "admin", "admin@example.com", "a-strong-admin-pass").run();
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(1);
    }

    @Test
    void bootstrapWithAWeakPasswordCreatesNoAdminAndDoesNotThrow() {
        assertThatCode(() -> new AdminBootstrap(authService, userRepository, "admin", "admin@example.com", "admin123")
                .run()).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();
    }

    @Test
    void bootstrapDoesNotAbortWhenTheAdminUsernameOrEmailIsAlreadyTakenByAnotherAccount() throws Exception {
        // accounts that predate the reservation (inserted directly, as an old database could contain)
        jdbc.update("insert into users(username,email,password_hash) values ('Admin','x@example.com','h')");
        assertThatCode(() -> new AdminBootstrap(authService, userRepository, "admin", "admin@example.com",
                "a-strong-admin-pass").run()).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select role from users", String.class)).isEqualTo("USER");

        jdbc.execute("TRUNCATE TABLE transactions, wallets, users RESTART IDENTITY CASCADE");
        jdbc.update("insert into users(username,email,password_hash) values ('someone','ADMIN@example.com','h')");
        assertThatCode(() -> new AdminBootstrap(authService, userRepository, "admin", "admin@example.com",
                "a-strong-admin-pass").run()).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(1);
    }

    @Test
    void adminRoleCanStillUseTheReservedName() {
        assertThat(authService.createUser("admin", "admin@example.com", PASSWORD, Role.ADMIN).getRole())
                .isEqualTo(Role.ADMIN);
    }

    // ---- L6: ids of other users' resources look like missing ones ---------------------------------------------

    @Test
    void foreignAndMissingWalletsAndTransactionsAreIndistinguishable() throws Exception {
        String alice = register("alice");
        String bob = register("bob");
        String carol = register("carol");
        long txId = body(transfer(alice, "bob", "10", null)).get("id").asLong();
        long bobWallet = walletId("bob");

        MvcResult foreignWallet = getJson("/api/wallets/" + bobWallet, alice);
        MvcResult missingWallet = getJson("/api/wallets/987654", alice);
        assertThat(foreignWallet.getResponse().getStatus()).isEqualTo(404);
        assertThat(foreignWallet.getResponse().getContentAsString().replaceAll("\"timestamp\":\"[^\"]*\"", ""))
                .isEqualTo(missingWallet.getResponse().getContentAsString().replaceAll("\"timestamp\":\"[^\"]*\"", ""));

        MvcResult foreignTx = getJson("/api/transactions/" + txId, carol);
        MvcResult missingTx = getJson("/api/transactions/987654", carol);
        assertThat(foreignTx.getResponse().getStatus()).isEqualTo(404);
        assertThat(foreignTx.getResponse().getContentAsString().replaceAll("\"timestamp\":\"[^\"]*\"", ""))
                .isEqualTo(missingTx.getResponse().getContentAsString().replaceAll("\"timestamp\":\"[^\"]*\"", ""));

        // legitimate access is unchanged
        assertThat(getJson("/api/wallets/" + bobWallet, bob).getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson("/api/transactions/" + txId, bob).getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson("/api/transactions/" + txId, adminToken()).getResponse().getStatus()).isEqualTo(200);
    }

    // ---- L7: sort whitelist -----------------------------------------------------------------------------------

    @Test
    void sortingBySensitiveOrUnknownPropertiesIs400OnEveryPagedEndpoint() throws Exception {
        String admin = adminToken();
        String alice = register("alice");
        for (String sort : new String[] {"passwordHash", "password_hash", "wallet", "nope", "idempotencyKey",
                "user.passwordHash"}) {
            assertThat(getJson("/api/admin/users?sort=" + sort + ",asc", admin).getResponse().getStatus())
                    .as("admin users sort=" + sort).isEqualTo(400);
            assertThat(getJson("/api/admin/transactions?sort=" + sort, admin).getResponse().getStatus())
                    .as("admin transactions sort=" + sort).isEqualTo(400);
            assertThat(getJson("/api/transactions?sort=" + sort + ",desc", alice).getResponse().getStatus())
                    .as("history sort=" + sort).isEqualTo(400);
        }
        // a bad second sort key is caught too
        assertThat(getJson("/api/admin/users?sort=id,asc&sort=passwordHash,desc", admin).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void whitelistedSortPropertiesStillWork() throws Exception {
        String admin = adminToken();
        String alice = register("alice");
        register("bob");
        transfer(alice, "bob", "1", null);
        for (String sort : new String[] {"id", "username", "email", "role", "enabled", "createdAt"}) {
            assertThat(getJson("/api/admin/users?sort=" + sort + ",desc", admin).getResponse().getStatus())
                    .as("users sort=" + sort).isEqualTo(200);
        }
        for (String sort : new String[] {"id", "createdAt", "amount", "type", "status", "senderWalletId",
                "receiverWalletId"}) {
            assertThat(getJson("/api/admin/transactions?sort=" + sort + ",asc", admin).getResponse().getStatus())
                    .as("admin tx sort=" + sort).isEqualTo(200);
            assertThat(getJson("/api/transactions?sort=" + sort + ",asc", alice).getResponse().getStatus())
                    .as("history sort=" + sort).isEqualTo(200);
        }
        // default sort (no parameter) is unaffected
        assertThat(getJson("/api/admin/users", admin).getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson("/api/transactions", alice).getResponse().getStatus()).isEqualTo(200);
    }

    // ---- H2: no self-service deposit ---------------------------------------------------------------------------

    @Test
    void selfServiceDepositEndpointIsGoneForUsersAndAdmins() throws Exception {
        String alice = register("alice");
        String admin = adminToken();
        for (String token : new String[] {alice, admin}) {
            MvcResult r = postJson("/api/wallets/me/deposit", token, map("amount", 50), null);
            assertThat(r.getResponse().getStatus()).isEqualTo(404);
        }
        assertThat(postJson("/api/wallets/me/deposit", null, map("amount", 50), null).getResponse().getStatus())
                .isEqualTo(401);
        assertThat(balance("alice")).isEqualByComparingTo("100");
        assertThat(txCount()).isZero();
    }

    @Test
    void onlyTheAdminDepositEndpointCreditsMoney() throws Exception {
        String alice = register("alice");
        String admin = adminToken();
        assertThat(postJson("/api/admin/wallets/" + walletId("alice") + "/deposit", alice, map("amount", 5), null)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(adminDeposit(admin, "alice", "5", null).getResponse().getStatus()).isEqualTo(201);
        assertThat(balance("alice")).isEqualByComparingTo("105");
    }
}
