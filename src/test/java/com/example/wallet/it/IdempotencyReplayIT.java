package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Simultaneous and sequential Idempotency-Key replays: the original result every time, one money movement, 409 only for
 * a different payload, owners independent, and no deadlock when replays are mixed with opposite-direction transfers.
 */
class IdempotencyReplayIT extends ItBase {
    private static final int N = 40;

    private void assertAllOriginal(List<MvcResult> results) {
        List<Integer> statuses = statusesOf(results);
        assertThat(statuses).as("statuses").allMatch(s -> s == 201, "only 201 allowed: " + statuses);
        assertThat(distinctBodies(results)).as("every response must be the original one").isEqualTo(1);
    }

    private long countTx(String where) {
        return jdbc.queryForObject("select count(*) from transactions where " + where, Long.class);
    }

    // ---- N identical simultaneous requests -------------------------------------------------------------------

    @Test
    void simultaneousIdenticalTransfersReturnTheOriginalAndMoveMoneyOnce() throws Exception {
        String a = register("alice");
        register("bob");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < N; i++) tasks.add(() -> postJson("/api/transactions/transfer", a,
                map("receiverUsername", "bob", "amount", bd("10"), "note", "rent"), "k-transfer"));
        List<MvcResult> results = runParallelResults(tasks);

        assertAllOriginal(results);
        assertThat(txCount()).isEqualTo(1);
        assertThat(body(results.get(0)).get("id").asLong())
                .isEqualTo(jdbc.queryForObject("select id from transactions", Long.class));
        assertThat(balance("alice")).isEqualByComparingTo("90");
        assertThat(balance("bob")).isEqualByComparingTo("110");
    }

    @Test
    void simultaneousIdenticalWithdrawalsReturnTheOriginalAndDebitOnce() throws Exception {
        String a = register("alice");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < N; i++) tasks.add(() -> withdraw(a, "30", "k-withdraw"));
        List<MvcResult> results = runParallelResults(tasks);

        assertAllOriginal(results);
        assertThat(txCount()).isEqualTo(1);
        assertThat(balance("alice")).isEqualByComparingTo("70");
    }

    @Test
    void simultaneousIdenticalAdminDepositsReturnTheOriginalAndCreditOnce() throws Exception {
        register("alice");
        String adm = adminToken();
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < N; i++) tasks.add(() -> adminDeposit(adm, "alice", "25", "k-deposit"));
        List<MvcResult> results = runParallelResults(tasks);

        assertAllOriginal(results);
        assertThat(txCount()).isEqualTo(1);
        assertThat(balance("alice")).isEqualByComparingTo("125");
    }

    @Test
    void simultaneousIdenticalDepositRequestsReturnTheOriginalAndCreateOne() throws Exception {
        String a = register("alice");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < N; i++) tasks.add(() -> postJson("/api/deposit-requests", a,
                map("amount", bd("12"), "note", "salary"), "k-request"));
        List<MvcResult> results = runParallelResults(tasks);

        assertAllOriginal(results);
        assertThat(jdbc.queryForObject("select count(*) from deposit_requests", Long.class)).isEqualTo(1);
        assertThat(balance("alice")).isEqualByComparingTo("100");
    }

    // ---- same key, different payload -------------------------------------------------------------------------

    /** Half the requests use amount 10, half 20: exactly one amount wins, the other amount is a 409 mismatch. */
    private void assertOneAmountWins(List<MvcResult> results, List<String> amounts) throws Exception {
        String winner = null;
        for (int i = 0; i < results.size(); i++) {
            if (status(results.get(i)) == 201) {
                String amount = amounts.get(i);
                if (winner == null) winner = amount;
                assertThat(amount).as("only requests with the winning payload may succeed").isEqualTo(winner);
            } else {
                assertThat(status(results.get(i))).isEqualTo(409);
                assertThat(body(results.get(i)).get("error").asText())
                        .isEqualTo("Idempotency key already used with a different request");
            }
        }
        assertThat(winner).isNotNull();
        assertThat(distinctBodies(results.stream().filter(r -> status(r) == 201).toList())).isEqualTo(1);
        assertThat(txCount()).isEqualTo(1);
        BigDecimal moved = jdbc.queryForObject("select amount from transactions", BigDecimal.class);
        assertThat(moved).isEqualByComparingTo(winner);
    }

    private static int status(MvcResult r) { return r.getResponse().getStatus(); }

    private static String text(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }

    @Test
    void simultaneousSameKeyDifferentAmountsAcceptExactlyOnePayload() throws Exception {
        String a = register("alice");
        register("bob");
        List<String> amounts = new ArrayList<>();
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            String amount = i % 2 == 0 ? "10" : "20";
            amounts.add(amount);
            tasks.add(() -> transfer(a, "bob", amount, "k-mixed"));
        }
        assertOneAmountWins(runParallelResults(tasks), amounts);
        assertThat(balance("alice").add(balance("bob"))).isEqualByComparingTo("200");
        assertThat(balance("alice").intValue()).isIn(90, 80);
    }

    @Test
    void simultaneousSameKeyDifferentReceiversAcceptExactlyOnePayload() throws Exception {
        String a = register("alice");
        register("bob");
        register("carol");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            String to = i % 2 == 0 ? "bob" : "carol";
            tasks.add(() -> transfer(a, to, "10", "k-receivers"));
        }
        List<MvcResult> results = runParallelResults(tasks);
        assertThat(statusesOf(results)).allMatch(s -> s == 201 || s == 409);
        assertThat(txCount()).isEqualTo(1);
        assertThat(balance("alice")).isEqualByComparingTo("90");
        assertThat(balance("bob").add(balance("carol"))).isEqualByComparingTo("210");
        assertThat(distinctBodies(results.stream().filter(r -> status(r) == 201).toList())).isEqualTo(1);
    }

    @Test
    void simultaneousSameKeyDifferentWithdrawAmountsAcceptExactlyOnePayload() throws Exception {
        String a = register("alice");
        List<String> amounts = new ArrayList<>();
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            String amount = i % 2 == 0 ? "10" : "20";
            amounts.add(amount);
            tasks.add(() -> withdraw(a, amount, "k-wd-mixed"));
        }
        assertOneAmountWins(runParallelResults(tasks), amounts);
    }

    @Test
    void simultaneousSameKeyDifferentDepositAmountsAcceptExactlyOnePayload() throws Exception {
        register("alice");
        String adm = adminToken();
        List<String> amounts = new ArrayList<>();
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            String amount = i % 2 == 0 ? "10" : "20";
            amounts.add(amount);
            tasks.add(() -> adminDeposit(adm, "alice", amount, "k-dep-mixed"));
        }
        assertOneAmountWins(runParallelResults(tasks), amounts);
    }

    @Test
    void simultaneousSameKeyDifferentDepositRequestPayloadsAcceptExactlyOne() throws Exception {
        String a = register("alice");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            Map<String, Object> payload = i % 2 == 0 ? map("amount", bd("5")) : map("amount", bd("5"), "note", "x");
            tasks.add(() -> postJson("/api/deposit-requests", a, payload, "k-dr-mixed"));
        }
        List<MvcResult> results = runParallelResults(tasks);
        assertThat(statusesOf(results)).allMatch(s -> s == 201 || s == 409);
        assertThat(statusesOf(results)).contains(201, 409);
        assertThat(distinctBodies(results.stream().filter(r -> status(r) == 201).toList())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from deposit_requests", Long.class)).isEqualTo(1);
    }

    @Test
    void simultaneousSameKeyDifferentOperationTypesAcceptExactlyOne() throws Exception {
        String a = register("alice");
        String adm = adminToken();
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            tasks.add(i % 2 == 0 ? () -> withdraw(a, "10", "k-types") : () -> adminDeposit(adm, "alice", "10", "k-types"));
        }
        List<MvcResult> results = runParallelResults(tasks);
        assertThat(statusesOf(results)).allMatch(s -> s == 201 || s == 409);
        assertThat(txCount()).isEqualTo(1);
        assertThat(balance("alice").intValue()).isIn(90, 110);
    }

    // ---- sequential behaviour is unchanged -------------------------------------------------------------------

    @Test
    void sequentialReplayReturnsTheSameBodyAndDifferentPayloadIsRejected() throws Exception {
        String a = register("alice");
        register("bob");
        MvcResult first = transfer(a, "bob", "10", "k-seq");
        MvcResult second = transfer(a, "bob", "10", "k-seq");
        assertThat(status(first)).isEqualTo(201);
        assertThat(status(second)).isEqualTo(201);
        assertThat(text(second)).isEqualTo(text(first));
        assertThat(txCount()).isEqualTo(1);

        MvcResult other = transfer(a, "bob", "11", "k-seq");
        assertThat(status(other)).isEqualTo(409);
        assertThat(body(other).get("error").asText()).isEqualTo("Idempotency key already used with a different request");
        MvcResult otherType = withdraw(a, "10", "k-seq");
        assertThat(status(otherType)).isEqualTo(409);
        assertThat(txCount()).isEqualTo(1);
        assertThat(balance("alice")).isEqualByComparingTo("90");
    }

    @Test
    void replayStillReturnsTheOriginalAfterTheBalanceCannotCoverItAnymore() throws Exception {
        String a = register("alice");
        register("bob");
        MvcResult first = transfer(a, "bob", "100", "k-broke");
        assertThat(status(first)).isEqualTo(201);
        assertThat(balance("alice")).isEqualByComparingTo("0");
        MvcResult again = transfer(a, "bob", "100", "k-broke");
        assertThat(status(again)).isEqualTo(201);
        assertThat(text(again)).isEqualTo(text(first));
        // a NEW key with the same payload is a genuine new request and is refused for funds, not mistaken for a replay
        assertThat(status(transfer(a, "bob", "100", "k-new"))).isEqualTo(422);
    }

    @Test
    void aFailedKeyedRequestLeavesTheKeyFreeForAValidRetry() throws Exception {
        String a = register("alice");
        register("bob");
        assertThat(status(transfer(a, "bob", "500", "k-retry"))).isEqualTo(422);
        assertThat(txCount()).isZero();
        setBalance("alice", "600");
        MvcResult ok = transfer(a, "bob", "500", "k-retry");
        assertThat(status(ok)).isEqualTo(201);
        assertThat(txCount()).isEqualTo(1);
    }

    // ---- owners are independent ------------------------------------------------------------------------------

    @Test
    void differentOwnersUsingTheSameKeySimultaneouslyStayIndependent() throws Exception {
        String a = register("alice");
        String b = register("bob");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            tasks.add(() -> transfer(a, "bob", "10", "shared-key"));
            tasks.add(() -> transfer(b, "alice", "7", "shared-key"));
        }
        List<MvcResult> results = runParallelResults(tasks);
        assertThat(statusesOf(results)).allMatch(s -> s == 201);
        List<MvcResult> fromAlice = new ArrayList<>();
        List<MvcResult> fromBob = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) (i % 2 == 0 ? fromAlice : fromBob).add(results.get(i));
        assertThat(distinctBodies(fromAlice)).isEqualTo(1);
        assertThat(distinctBodies(fromBob)).isEqualTo(1);
        assertThat(txCount()).isEqualTo(2);
        assertThat(balance("alice")).isEqualByComparingTo("97");
        assertThat(balance("bob")).isEqualByComparingTo("103");
    }

    // ---- no deadlock, money conservation -----------------------------------------------------------------------

    @Test
    void oppositeDirectionTransfersMixedWithReplaysDoNotDeadlock() throws Exception {
        String a = register("alice");
        String b = register("bob");
        setBalance("alice", "1000");
        setBalance("bob", "1000");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> transfer(a, "bob", "3", "a-to-b"));
            tasks.add(() -> transfer(b, "alice", "2", "b-to-a"));
            tasks.add(() -> transfer(a, "bob", "1", null));
            tasks.add(() -> transfer(b, "alice", "1", null));
        }
        List<MvcResult> results = runParallelResults(tasks);
        assertThat(statusesOf(results)).allMatch(s -> s == 201);
        // 2 keyed transfers + 20 + 20 unkeyed
        assertThat(txCount()).isEqualTo(42);
        assertThat(balance("alice").add(balance("bob"))).isEqualByComparingTo("2000");
        assertThat(countTx("sender_wallet_id = " + walletId("alice") + " and amount = 3")).isEqualTo(1);
        assertThat(countTx("sender_wallet_id = " + walletId("bob") + " and amount = 2")).isEqualTo(1);
    }

    @Test
    void randomisedMixedRunWithReplaysConservesMoneyAndAppliesEachKeyAtMostOnce() throws Exception {
        int users = 5;
        String[] names = new String[users];
        String[] tokens = new String[users];
        for (int i = 0; i < users; i++) {
            names[i] = "user" + i;
            tokens[i] = register(names[i]);
            setBalance(names[i], "60");
        }
        String adm = adminToken();
        BigDecimal before = jdbc.queryForObject("select sum(balance) from wallets", BigDecimal.class);
        Random rnd = new Random(7);
        // 25 distinct operations (fixed payload and key each); every one is fired 1 to 4 times, all in one burst
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (int op = 0; op < 25; op++) {
            String key = "rnd-" + op;
            int from = rnd.nextInt(users);
            int kind = rnd.nextInt(3);
            int to = (from + 1 + rnd.nextInt(users - 1)) % users;
            String amount = String.valueOf(1 + rnd.nextInt(40));
            int copies = 1 + rnd.nextInt(4);
            for (int c = 0; c < copies; c++) {
                keys.add(key + "|" + from);
                if (kind == 0) {
                    tasks.add(() -> transfer(tokens[from], names[to], amount, key));
                } else if (kind == 1) {
                    tasks.add(() -> withdraw(tokens[from], amount, key));
                } else {
                    tasks.add(() -> adminDeposit(adm, names[from], amount, key));
                }
            }
        }
        List<MvcResult> results = runParallelResults(tasks);

        Map<String, java.util.Set<Long>> idsPerKey = new HashMap<>();
        for (int i = 0; i < results.size(); i++) {
            int s = status(results.get(i));
            assertThat(s).as("request %d (%s)", i, keys.get(i)).isIn(201, 422);
            if (s == 201) {
                idsPerKey.computeIfAbsent(keys.get(i), k -> new java.util.HashSet<>())
                        .add(body(results.get(i)).get("id").asLong());
            }
        }
        assertThat(idsPerKey.values()).allMatch(ids -> ids.size() == 1, "a key produced several transactions");
        assertThat(txCount()).isEqualTo(idsPerKey.size());
        assertThat(jdbc.queryForObject("select count(*) from wallets where balance < 0", Long.class)).isZero();
        BigDecimal deposits = jdbc.queryForObject(
                "select coalesce(sum(amount),0) from transactions where type = 'DEPOSIT'", BigDecimal.class);
        BigDecimal withdrawals = jdbc.queryForObject(
                "select coalesce(sum(amount),0) from transactions where type = 'WITHDRAWAL'", BigDecimal.class);
        BigDecimal total = jdbc.queryForObject("select coalesce(sum(balance),0) from wallets", BigDecimal.class);
        assertThat(total).isEqualByComparingTo(before.add(deposits).subtract(withdrawals));
    }
}
