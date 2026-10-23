package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.wallet.entity.Role;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Round 2 security fixes (F2, F3, F4, F5, F8, F9, F10) end to end on real SQL. */
class SecurityRound2IT extends ItBase {
    private static final String NUL = "\u0000";

    private MvcResult registerRaw(String username, String email, String password) throws Exception {
        return postJson("/api/auth/register", null, map("username", username, "email", email, "password", password),
                null);
    }

    private MvcResult loginRaw(String username, String password) throws Exception {
        return postJson("/api/auth/login", null, map("username", username, "password", password), null);
    }

    private void assertStatus(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
    }

    private long userCount() {
        return jdbc.queryForObject("select count(*) from users", Long.class);
    }

    // ---- F2: NUL character -> 400, never 500 ------------------------------------------------------------------

    @Test
    void nulInUsernameIs400OnRegisterAndLogin() throws Exception {
        MvcResult reg = registerRaw("nul" + NUL + "x", "n@example.com", PASSWORD);
        assertStatus(reg, 400);
        assertThat(body(reg).get("error").asText()).contains("username");
        assertThat(userCount()).isZero();
        register("alice");
        assertStatus(loginRaw("al" + NUL + "ice", PASSWORD), 400);
        assertStatus(loginRaw("alice", PASSWORD), 200);
    }

    @Test
    void nulInNotesAndReceiverIs400OnEveryEndpointAndChangesNothing() throws Exception {
        String alice = register("alice");
        register("bob");
        String admin = adminToken();
        setBalance("alice", "50");

        assertStatus(postJson("/api/transactions/transfer", alice,
                map("receiverUsername", "bob", "amount", BigDecimal.ONE, "note", "a" + NUL + "b"), null), 400);
        assertStatus(postJson("/api/transactions/transfer", alice,
                map("receiverUsername", "bo" + NUL + "b", "amount", BigDecimal.ONE), null), 400);
        assertStatus(postJson("/api/deposit-requests", alice,
                map("amount", BigDecimal.ONE, "note", "a" + NUL + "b"), null), 400);
        assertThat(txCount()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from deposit_requests", Long.class)).isZero();

        long id = body(postJson("/api/deposit-requests", alice, map("amount", BigDecimal.ONE, "note", "ok"), null))
                .get("id").asLong();
        assertStatus(postJson("/api/admin/deposit-requests/" + id + "/reject", admin,
                map("decisionNote", "no" + NUL + "pe"), null), 400);
        assertStatus(postJson("/api/admin/deposit-requests/" + id + "/approve", admin,
                map("decisionNote", "ye" + NUL + "s"), null), 400);
        assertThat(jdbc.queryForObject("select status from deposit_requests where id=?", String.class, id))
                .isEqualTo("PENDING");
    }

    @Test
    void nulInAQueryParameterIs400NotA500() throws Exception {
        String admin = adminToken();
        MvcResult r = mvc.perform(withAuth(get("/api/admin/deposit-requests").param("username", "a" + NUL + "b"), admin))
                .andReturn();
        assertStatus(r, 400);
        assertThat(r.getResponse().getContentAsString()).doesNotContain("Exception").doesNotContain("at com.");
    }

    @Test
    void nulInProfileEmailOrPasswordsNeverGives500() throws Exception {
        String alice = register("alice");
        assertStatus(patchJson("/api/users/me", alice, map("email", "a" + NUL + "@example.com")), 400);
        assertThat(registerRaw("carol", "carol@example.com", "pass" + NUL + "word123").getResponse().getStatus())
                .isLessThan(500);
        assertThat(loginRaw("carol", "pass" + NUL + "word123").getResponse().getStatus()).isLessThan(500);
        assertThat(loginRaw("alice", "x" + NUL + "y").getResponse().getStatus()).isEqualTo(401);
    }

    // ---- F8: control, invisible, bidi and look-alike characters ------------------------------------------------

    @Test
    void usernamesWithInvisibleControlOrLookAlikeCharactersAreRejected() throws Exception {
        String[] bad = {"alice​", "ali­ce", "bad\nuser", "bad\tuser", "аlice", "ａlice", "al ice",
                "alice‮", "ali‍ce", "al ice", "ADMİN", "admın", "x@y", "a/b"};
        for (String name : bad) {
            MvcResult r = registerRaw(name, "m" + Math.abs(name.hashCode()) + "@example.com", PASSWORD);
            assertThat(r.getResponse().getStatus()).as("username " + name.codePoints().boxed().toList()).isEqualTo(400);
        }
        assertThat(userCount()).isZero();
    }

    @Test
    void ordinaryUsernamesStillWorkAndStayCaseInsensitive() throws Exception {
        for (String name : new String[] {"alice", "Bob.Smith", "carol_1", "dave-2", "  Erin  ", "x1y"}) {
            assertStatus(registerRaw(name, name.trim() + "@example.com", PASSWORD), 201);
        }
        assertStatus(registerRaw("ALICE", "other@example.com", PASSWORD), 409);
        assertStatus(registerRaw("bob.smith", "other2@example.com", PASSWORD), 409);
        assertStatus(loginRaw("BOB.SMITH", PASSWORD), 200);
        assertThat(userCount()).isEqualTo(6);
    }

    @Test
    void reservedAdminNameStillCannotBeTaken() throws Exception {
        authService.createUser("admin", "admin@example.com", PASSWORD, Role.ADMIN);
        for (String name : new String[] {"admin", "ADMIN", " Admin ", "aDmIn"}) {
            assertStatus(registerRaw(name, "x" + name.trim() + "@example.com", PASSWORD), 409);
        }
        // look-alikes of admin never get that far any more
        assertStatus(registerRaw("ADMİN", "t@example.com", PASSWORD), 400);
        assertStatus(registerRaw("admın", "t2@example.com", PASSWORD), 400);
        assertThat(userCount()).isEqualTo(1);
    }

    @Test
    void notesRejectNewlineControlAndBidiCharactersButAcceptUnicodeText() throws Exception {
        String alice = register("alice");
        register("bob");
        setBalance("alice", "50");
        for (String note : new String[] {"a\nb", "a\tb", "a‮b", "a​b", "a­b", "a b", "a\rb"}) {
            assertStatus(postJson("/api/transactions/transfer", alice,
                    map("receiverUsername", "bob", "amount", BigDecimal.ONE, "note", note), null), 400);
            assertStatus(postJson("/api/deposit-requests", alice, map("amount", BigDecimal.ONE, "note", note), null), 400);
        }
        assertThat(txCount()).isZero();
        assertStatus(postJson("/api/transactions/transfer", alice,
                map("receiverUsername", "bob", "amount", BigDecimal.ONE, "note", "Café 日本語 ref #1"),
                null), 201);
    }

    @Test
    void profileEmailWithInvisibleCharactersIs400() throws Exception {
        String alice = register("alice");
        for (String mail : new String[] {"a​@example.com", "a@exam­ple.com", "a@example.com\n"}) {
            assertThat(patchJson("/api/users/me", alice, map("email", mail)).getResponse().getStatus())
                    .as(mail).isEqualTo(400);
        }
        assertStatus(patchJson("/api/users/me", alice, map("email", "new@example.com")), 200);
    }

    @Test
    void legacyUsernamesWithOddCharactersCanStillLogInAndReceiveTransfers() throws Exception {
        String legacy = "le​gacy";
        authService.createUser(legacy, "legacy@example.com", PASSWORD, Role.USER); // as an old database could contain
        String alice = register("alice");
        setBalance("alice", "10");
        assertStatus(loginRaw(legacy, PASSWORD), 200);
        assertStatus(transfer(alice, legacy, "1", null), 201);
    }

    // ---- F3: disabled-account timing --------------------------------------------------------------------------

    @Test
    void wrongPasswordOnADisabledAccountTakesAsLongAsOnAnEnabledOne() throws Exception {
        register("alice");
        register("bob");
        jdbc.update("update users set enabled=false where username='bob'");
        loginRaw("alice", "wrong-pass-1"); // warm-up
        long enabledNs = timeLogins("alice", "wrong-pass-1", 4);
        long disabledNs = timeLogins("bob", "wrong-pass-1", 4);
        // The old early exit answered in ~2 ms against ~75 ms; require at least half of the BCrypt time.
        assertThat(disabledNs).as("disabled=%d ms, enabled=%d ms", disabledNs / 1_000_000, enabledNs / 1_000_000)
                .isGreaterThan(enabledNs / 2);
        // same response for both, and for the right password on the disabled account
        MvcResult a = loginRaw("alice", "wrong-pass-1");
        MvcResult b = loginRaw("bob", "wrong-pass-1");
        MvcResult c = loginRaw("bob", PASSWORD);
        for (MvcResult r : new MvcResult[] {a, b, c}) {
            assertStatus(r, 401);
            assertThat(body(r).get("error").asText()).isEqualTo("Invalid username or password");
        }
    }

    private long timeLogins(String user, String pw, int n) throws Exception {
        long total = 0;
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            assertStatus(loginRaw(user, pw), 401);
            total += System.nanoTime() - t0;
        }
        return total / n;
    }

    // ---- F4: page overflow ------------------------------------------------------------------------------------

    @Test
    void hugePageNumbersAre400OnEveryPagedEndpointAndReasonableOnesStillWork() throws Exception {
        String alice = register("alice");
        String admin = adminToken();
        String[][] endpoints = {{"/api/transactions", alice}, {"/api/admin/transactions", admin},
                {"/api/admin/users", admin}, {"/api/deposit-requests", alice},
                {"/api/admin/deposit-requests", admin}};
        for (String[] e : endpoints) {
            for (String q : new String[] {"?page=99999999&size=100", "?page=2147483647", "?page=1073741824&size=2",
                    "?page=21474837&size=100"}) {
                MvcResult r = getJson(e[0] + q, e[1]);
                assertThat(r.getResponse().getStatus()).as(e[0] + q).isEqualTo(400);
                assertThat(body(r).get("error").asText()).contains("page");
            }
            MvcResult ok = getJson(e[0] + "?page=100000&size=20", e[1]);
            assertThat(ok.getResponse().getStatus()).as(e[0]).isEqualTo(200);
            assertThat(body(ok).get("content")).isEmpty();
            assertStatus(getJson(e[0] + "?page=0&size=100", e[1]), 200);
        }
    }

    // ---- F5: extreme dates ------------------------------------------------------------------------------------

    @Test
    void datesOutsideTheSupportedRangeAre400OnBothHistoryEndpoints() throws Exception {
        String alice = register("alice");
        String admin = adminToken();
        String[][] bad = {{"from", "+999999999-12-31"}, {"from", "-999999999-01-01"}, {"to", "+999999999-12-31"},
                {"to", "-999999999-01-01"}, {"from", "1899-12-31"}, {"to", "2201-01-01"}, {"to", "9999-12-31"},
                {"from", "+10000-01-01"}};
        for (String[] q : bad) {
            for (String[] e : new String[][] {{"/api/transactions", alice}, {"/api/admin/transactions", admin}}) {
                MvcResult r = mvc.perform(withAuth(get(e[0]).param(q[0], q[1]), e[1])).andReturn();
                assertThat(r.getResponse().getStatus()).as(e[0] + " " + q[0] + "=" + q[1]).isEqualTo(400);
                assertThat(r.getResponse().getContentAsString()).doesNotContain("Internal error");
            }
        }
        // a date that parses but is outside 1900..2200 gets the explicit message
        MvcResult r = mvc.perform(withAuth(get("/api/transactions").param("to", "2201-01-01"), alice)).andReturn();
        assertThat(body(r).get("error").asText()).contains("to must be between 1900-01-01 and 2200-12-31");
        for (String q : new String[] {"from=1900-01-01&to=2200-12-31", "from=2026-01-01", "to=2200-12-31"}) {
            assertStatus(getJson("/api/transactions?" + q, alice), 200);
            assertStatus(getJson("/api/admin/transactions?" + q, admin), 200);
        }
    }

    // ---- F9: unknown receiver looks the same by name and by wallet id -----------------------------------------

    @Test
    void unknownReceiverByUsernameOrByWalletIdGivesTheSameResponse() throws Exception {
        String alice = register("alice");
        register("bob");
        setBalance("alice", "10");
        MvcResult byName = transfer(alice, "nobody", "1", null);
        MvcResult low = postJson("/api/transactions/transfer", alice,
                map("receiverWalletId", 0, "amount", BigDecimal.ONE), null);
        MvcResult high = postJson("/api/transactions/transfer", alice,
                map("receiverWalletId", 987654, "amount", BigDecimal.ONE), null);
        for (MvcResult r : new MvcResult[] {byName, low, high}) {
            assertStatus(r, 404);
            assertThat(body(r).get("error").asText()).isEqualTo("Receiver not found");
            assertThat(body(r).fieldNames()).toIterable().containsExactly("timestamp", "status", "error");
        }
        assertThat(balance("alice")).isEqualByComparingTo("10");
    }

    // ---- F10: passwords over 72 bytes at login ----------------------------------------------------------------

    @Test
    void loginNeverMatchesALongPasswordByItsFirst72Bytes() throws Exception {
        String p72 = "a".repeat(72);
        assertStatus(registerRaw("alice", "alice@example.com", p72), 201);
        assertStatus(loginRaw("alice", p72), 200);
        for (String longer : new String[] {p72 + "a", p72 + "different tail", "a".repeat(200)}) {
            MvcResult r = loginRaw("alice", longer);
            assertStatus(r, 401);
            assertThat(body(r).get("error").asText()).isEqualTo("Invalid username or password");
        }
        // multi-byte: 36 two-byte characters are exactly 72 bytes
        String e36 = "é".repeat(36);
        assertStatus(registerRaw("bob", "bob@example.com", e36), 201);
        assertStatus(loginRaw("bob", e36), 200);
        assertStatus(loginRaw("bob", e36 + "é"), 401);
        // registration and password change keep refusing over 72 bytes
        assertStatus(registerRaw("carol", "carol@example.com", p72 + "a"), 400);
        String alice = login("alice", p72);
        assertStatus(postJson("/api/users/me/password", alice,
                map("currentPassword", p72 + "a", "newPassword", "Another-pass-1"), null), 400);
        assertStatus(postJson("/api/users/me/password", alice,
                map("currentPassword", p72, "newPassword", "a".repeat(73)), null), 400);
    }
}
