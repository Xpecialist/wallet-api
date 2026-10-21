package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Deposit requests end to end: user endpoints, admin decisions, history link, access control. */
class DepositRequestIT extends ItBase {
    String alice, bob, admin;

    @BeforeEach
    void users() throws Exception {
        alice = register("alice");
        bob = register("bob");
        admin = adminToken();
    }

    MvcResult request(String token, String amount, String note, String idemKey) throws Exception {
        return postJson("/api/deposit-requests", token,
                map("amount", new BigDecimal(amount), "note", note), idemKey);
    }

    long create(String token, String amount) throws Exception {
        MvcResult r = request(token, amount, "ref-" + amount, null);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).get("id").asLong();
    }

    MvcResult approve(String token, long id, String note) throws Exception {
        return postJson("/api/admin/deposit-requests/" + id + "/approve", token,
                note == null ? map() : map("decisionNote", note), null);
    }

    MvcResult reject(String token, long id, String note) throws Exception {
        return postJson("/api/admin/deposit-requests/" + id + "/reject", token, map("decisionNote", note), null);
    }

    int status(MvcResult r) { return r.getResponse().getStatus(); }

    // ---- create / list / get ----

    @Test
    void createReturns201PendingAndDoesNotTouchTheBalance() throws Exception {
        MvcResult r = request(alice, "25.5", "bank ref 123", null);
        assertThat(status(r)).isEqualTo(201);
        JsonNode b = body(r);
        assertThat(b.get("status").asText()).isEqualTo("PENDING");
        assertThat(b.get("amount").decimalValue()).isEqualByComparingTo("25.5");
        assertThat(b.get("note").asText()).isEqualTo("bank ref 123");
        assertThat(b.get("walletId").asLong()).isEqualTo(walletId("alice"));
        assertThat(b.get("transactionId").isNull()).isTrue();
        assertThat(b.has("idempotencyKey")).isFalse();
        assertThat(balance("alice")).isEqualByComparingTo("100");
        assertThat(txCount()).isZero();
    }

    @Test
    void listAndGetShowOnlyOwnRequests() throws Exception {
        long a1 = create(alice, "1");
        long a2 = create(alice, "2");
        long b1 = create(bob, "3");

        JsonNode list = body(getJson("/api/deposit-requests", alice));
        assertThat(list.get("totalElements").asLong()).isEqualTo(2);
        assertThat(list.get("size").asInt()).isEqualTo(20);
        assertThat(list.get("content").get(0).get("id").asLong()).isEqualTo(a2); // newest first
        assertThat(list.get("content").get(1).get("id").asLong()).isEqualTo(a1);

        assertThat(status(getJson("/api/deposit-requests/" + a1, alice))).isEqualTo(200);
        MvcResult foreign = getJson("/api/deposit-requests/" + b1, alice);
        MvcResult missing = getJson("/api/deposit-requests/99999", alice);
        assertThat(status(foreign)).isEqualTo(404);
        assertThat(status(missing)).isEqualTo(404);
        assertThat(foreign.getResponse().getContentAsString().replaceAll("\"timestamp\":\"[^\"]*\",", ""))
                .isEqualTo(missing.getResponse().getContentAsString().replaceAll("\"timestamp\":\"[^\"]*\",", ""));
    }

    @Test
    void listFiltersByStatusSortsAndCapsPageSize() throws Exception {
        long id1 = create(alice, "1");
        create(alice, "2");
        assertThat(status(approve(admin, id1, null))).isEqualTo(200);

        assertThat(body(getJson("/api/deposit-requests?status=APPROVED", alice)).get("totalElements").asLong()).isEqualTo(1);
        assertThat(body(getJson("/api/deposit-requests?status=PENDING", alice)).get("totalElements").asLong()).isEqualTo(1);
        assertThat(body(getJson("/api/deposit-requests?status=REJECTED", alice)).get("totalElements").asLong()).isZero();
        JsonNode asc = body(getJson("/api/deposit-requests?sort=amount,asc", alice));
        assertThat(asc.get("content").get(0).get("amount").decimalValue()).isEqualByComparingTo("1");
        assertThat(body(getJson("/api/deposit-requests?size=1000", alice)).get("size").asInt()).isEqualTo(100);
        assertThat(status(getJson("/api/deposit-requests?sort=idempotencyKey", alice))).isEqualTo(400);
        assertThat(status(getJson("/api/deposit-requests?sort=user.passwordHash", alice))).isEqualTo(400);
        assertThat(status(getJson("/api/deposit-requests?status=BOGUS", alice))).isEqualTo(400);
    }

    @Test
    void validation() throws Exception {
        assertThat(status(request(alice, "0", null, null))).isEqualTo(400);
        assertThat(status(request(alice, "-5", null, null))).isEqualTo(400);
        assertThat(status(request(alice, "0.00001", null, null))).isEqualTo(400);
        assertThat(status(request(alice, "1.12345", null, null))).isEqualTo(400);
        assertThat(status(request(alice, "1234567890123456", null, null))).isEqualTo(400);
        assertThat(status(request(alice, "1", "x".repeat(256), null))).isEqualTo(400);
        assertThat(status(postJson("/api/deposit-requests", alice, map("note", "n"), null))).isEqualTo(400);
        assertThat(status(request(alice, "999999999999999.9999", "x".repeat(255), null))).isEqualTo(201);
        assertThat(status(request(alice, "0.0001", null, null))).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from deposit_requests", Long.class)).isEqualTo(2);
    }

    @Test
    void pendingCapIsPerUserAndFreedByADecision() throws Exception {
        long first = create(alice, "1");
        for (int i = 2; i <= 5; i++) create(alice, String.valueOf(i));
        MvcResult over = request(alice, "6", null, null);
        assertThat(status(over)).isEqualTo(409);
        assertThat(body(over).get("error").asText()).contains("Too many pending");
        assertThat(status(request(bob, "6", null, null))).isEqualTo(201); // other users unaffected

        assertThat(status(reject(admin, first, "no"))).isEqualTo(200);
        assertThat(status(request(alice, "7", null, null))).isEqualTo(201);
        assertThat(status(request(alice, "8", null, null))).isEqualTo(409);
    }

    // ---- idempotency ----

    @Test
    void idempotencyReplayReturnsOriginalAndCreatesNothingNew() throws Exception {
        MvcResult r1 = request(alice, "10", "n", "key-1");
        MvcResult r2 = request(alice, "10.0000", "n", "key-1");
        assertThat(status(r1)).isEqualTo(201);
        assertThat(status(r2)).isEqualTo(201);
        assertThat(body(r2).get("id").asLong()).isEqualTo(body(r1).get("id").asLong());
        assertThat(jdbc.queryForObject("select count(*) from deposit_requests", Long.class)).isEqualTo(1);
    }

    @Test
    void idempotencyPayloadMismatchIs409() throws Exception {
        request(alice, "10", "n", "key-1");
        assertThat(status(request(alice, "11", "n", "key-1"))).isEqualTo(409);
        assertThat(status(request(alice, "10", "other", "key-1"))).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from deposit_requests", Long.class)).isEqualTo(1);
    }

    @Test
    void idempotencyKeysAreScopedPerOwnerAndValidated() throws Exception {
        long a = body(request(alice, "10", "n", "shared")).get("id").asLong();
        MvcResult b = request(bob, "99", "other", "shared");
        assertThat(status(b)).isEqualTo(201);
        assertThat(body(b).get("id").asLong()).isNotEqualTo(a);
        assertThat(status(request(alice, "1", null, " "))).isEqualTo(400);
        assertThat(status(request(alice, "1", null, "k".repeat(101)))).isEqualTo(400);
        assertThat(status(request(alice, "1", null, "k".repeat(100)))).isEqualTo(201);
    }

    @Test
    void replayStillWorksWhenTheCapIsReached() throws Exception {
        request(alice, "1", null, "first");
        for (int i = 2; i <= 5; i++) create(alice, String.valueOf(i));
        assertThat(status(request(alice, "1", null, "first"))).isEqualTo(201);
        assertThat(status(request(alice, "9", null, "new"))).isEqualTo(409);
    }

    // ---- approve / reject ----

    @Test
    void approveCreditsOnceAndShowsInHistoryLinkedToTheRequest() throws Exception {
        long id = create(alice, "25.5");
        MvcResult r = approve(admin, id, "received bank transfer");
        assertThat(status(r)).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("status").asText()).isEqualTo("APPROVED");
        assertThat(b.get("username").asText()).isEqualTo("alice");
        assertThat(b.get("decisionNote").asText()).isEqualTo("received bank transfer");
        assertThat(b.get("decidedAt").isNull()).isFalse();
        assertThat(b.get("decidedBy").asLong()).isEqualTo(
                jdbc.queryForObject("select id from users where username='admin'", Long.class));
        long txId = b.get("transactionId").asLong();

        assertThat(balance("alice")).isEqualByComparingTo("125.5");
        assertThat(txCount()).isEqualTo(1);
        var row = jdbc.queryForMap("select type, status, sender_wallet_id, receiver_wallet_id, amount, note "
                + "from transactions where id=?", txId);
        assertThat(row.get("type")).isEqualTo("DEPOSIT");
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("sender_wallet_id")).isNull();
        assertThat(((Number) row.get("receiver_wallet_id")).longValue()).isEqualTo(walletId("alice"));
        assertThat((String) row.get("note")).startsWith("Deposit request #" + id);

        JsonNode history = body(getJson("/api/transactions", alice));
        assertThat(history.get("totalElements").asLong()).isEqualTo(1);
        assertThat(history.get("content").get(0).get("id").asLong()).isEqualTo(txId);
        assertThat(history.get("content").get(0).get("type").asText()).isEqualTo("DEPOSIT");
        assertThat(body(getJson("/api/deposit-requests/" + id, alice)).get("transactionId").asLong()).isEqualTo(txId);
        assertThat(body(getJson("/api/transactions", bob)).get("totalElements").asLong()).isZero();
    }

    @Test
    void approveWithoutBodyWorks() throws Exception {
        long id = create(alice, "5");
        MvcResult r = mvc.perform(withAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/admin/deposit-requests/" + id + "/approve"), admin)).andReturn();
        assertThat(status(r)).isEqualTo(200);
        assertThat(balance("alice")).isEqualByComparingTo("105");
    }

    @Test
    void rejectLeavesBalanceUnchangedAndRequiresANote() throws Exception {
        long id = create(alice, "25");
        assertThat(status(reject(admin, id, ""))).isEqualTo(400);
        assertThat(status(reject(admin, id, "x".repeat(256)))).isEqualTo(400);
        assertThat(status(postJson("/api/admin/deposit-requests/" + id + "/reject", admin, map(), null))).isEqualTo(400);
        assertThat(body(getJson("/api/deposit-requests/" + id, alice)).get("status").asText()).isEqualTo("PENDING");

        MvcResult r = reject(admin, id, "transfer not received");
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("status").asText()).isEqualTo("REJECTED");
        assertThat(body(r).get("transactionId").isNull()).isTrue();
        assertThat(balance("alice")).isEqualByComparingTo("100");
        assertThat(txCount()).isZero();
        JsonNode own = body(getJson("/api/deposit-requests/" + id, alice));
        assertThat(own.get("decisionNote").asText()).isEqualTo("transfer not received");
        assertThat(own.has("decidedBy")).isFalse();
    }

    @Test
    void decidingTwiceIs409WithoutMovingMoney() throws Exception {
        long id = create(alice, "10");
        assertThat(status(approve(admin, id, null))).isEqualTo(200);
        MvcResult again = approve(admin, id, null);
        assertThat(status(again)).isEqualTo(409);
        assertThat(body(again).get("error").asText()).isEqualTo("Deposit request already APPROVED");
        assertThat(status(reject(admin, id, "late"))).isEqualTo(409);
        assertThat(balance("alice")).isEqualByComparingTo("110");
        assertThat(txCount()).isEqualTo(1);

        long id2 = create(alice, "10");
        assertThat(status(reject(admin, id2, "no"))).isEqualTo(200);
        assertThat(status(reject(admin, id2, "no again"))).isEqualTo(409);
        assertThat(status(approve(admin, id2, null))).isEqualTo(409); // approve after reject
        assertThat(balance("alice")).isEqualByComparingTo("110");
        assertThat(txCount()).isEqualTo(1);
    }

    @Test
    void unknownRequestIs404() throws Exception {
        assertThat(status(approve(admin, 99999, null))).isEqualTo(404);
        assertThat(status(reject(admin, 99999, "x"))).isEqualTo(404);
    }

    @Test
    void disabledUserRequestCannotBeApprovedButCanBeRejected() throws Exception {
        long id = create(alice, "10");
        long id2 = create(alice, "20");
        long aliceId = jdbc.queryForObject("select id from users where username='alice'", Long.class);
        assertThat(status(patchJson("/api/admin/users/" + aliceId + "/status", admin, map("enabled", false))))
                .isEqualTo(200);

        MvcResult r = approve(admin, id, null);
        assertThat(status(r)).isEqualTo(409);
        assertThat(body(r).get("error").asText()).contains("disabled");
        assertThat(balance("alice")).isEqualByComparingTo("100");
        assertThat(status(reject(admin, id2, "account disabled"))).isEqualTo(200);

        // a disabled user's own token is already rejected
        assertThat(status(getJson("/api/deposit-requests", alice))).isEqualTo(401);

        assertThat(status(patchJson("/api/admin/users/" + aliceId + "/status", admin, map("enabled", true))))
                .isEqualTo(200);
        assertThat(status(approve(admin, id, null))).isEqualTo(200);
        assertThat(balance("alice")).isEqualByComparingTo("110");
    }

    @Test
    void adminMayRequestForThemselves() throws Exception {
        long id = create(admin, "7");
        assertThat(status(approve(admin, id, null))).isEqualTo(200);
        assertThat(balance("admin")).isEqualByComparingTo("107");
    }

    // ---- admin list ----

    @Test
    void adminListFiltersByStatusAndUserAndPutsPendingFirst() throws Exception {
        long a1 = create(alice, "1");
        long b1 = create(bob, "2");
        long a2 = create(alice, "3");
        approve(admin, a1, null);
        reject(admin, b1, "no");

        JsonNode all = body(getJson("/api/admin/deposit-requests", admin));
        assertThat(all.get("totalElements").asLong()).isEqualTo(3);
        assertThat(all.get("content").get(0).get("id").asLong()).isEqualTo(a2); // PENDING first
        assertThat(all.get("content").get(0).get("username").asText()).isEqualTo("alice");

        assertThat(body(getJson("/api/admin/deposit-requests?status=REJECTED", admin)).get("totalElements").asLong())
                .isEqualTo(1);
        assertThat(body(getJson("/api/admin/deposit-requests?username=BOB", admin)).get("totalElements").asLong())
                .isEqualTo(1);
        long bobId = jdbc.queryForObject("select id from users where username='bob'", Long.class);
        assertThat(body(getJson("/api/admin/deposit-requests?userId=" + bobId, admin)).get("totalElements").asLong())
                .isEqualTo(1);
        JsonNode asc = body(getJson("/api/admin/deposit-requests?sort=amount,asc&size=2", admin));
        assertThat(asc.get("content")).hasSize(2);
        assertThat(asc.get("totalPages").asInt()).isEqualTo(2);
        assertThat(asc.get("content").get(0).get("amount").decimalValue()).isEqualByComparingTo("1");
        assertThat(status(getJson("/api/admin/deposit-requests?sort=user.passwordHash", admin))).isEqualTo(400);
        assertThat(all.toString()).doesNotContain("idempotency").doesNotContain("passwordHash");
    }

    // ---- access control ----

    @Test
    void userCannotReachAdminEndpointsAndAnonymousGets401() throws Exception {
        long id = create(alice, "10");
        assertThat(status(getJson("/api/admin/deposit-requests", alice))).isEqualTo(403);
        assertThat(status(approve(alice, id, null))).isEqualTo(403);
        assertThat(status(reject(alice, id, "x"))).isEqualTo(403);
        assertThat(balance("alice")).isEqualByComparingTo("100");

        assertThat(status(getJson("/api/admin/deposit-requests", null))).isEqualTo(401);
        assertThat(status(approve(null, id, null))).isEqualTo(401);
        assertThat(status(reject(null, id, "x"))).isEqualTo(401);
        assertThat(status(getJson("/api/deposit-requests", null))).isEqualTo(401);
        assertThat(status(getJson("/api/deposit-requests/" + id, null))).isEqualTo(401);
        assertThat(status(request(null, "5", null, null))).isEqualTo(401);
        assertThat(body(getJson("/api/deposit-requests/" + id, alice)).get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void requestIsAlwaysForTheCallersOwnWallet() throws Exception {
        // a walletId/userId in the body is ignored: the wallet comes from the token
        MvcResult r = postJson("/api/deposit-requests", alice,
                map("amount", new BigDecimal("5"), "walletId", walletId("bob"), "userId", 2), null);
        assertThat(status(r)).isEqualTo(201);
        assertThat(body(r).get("walletId").asLong()).isEqualTo(walletId("alice"));
    }
}
