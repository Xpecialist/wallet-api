package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * NOTE: most tests here currently FAIL because of a real bug (see report): concurrent requests on the same wallet end
 * in ObjectOptimisticLockingFailureException (HTTP 500) instead of queueing on the row lock. Money stays consistent
 * (the invariants test passes).
 *
 * Parallel money movement against real row locks: no overdraft, no lost update, conservation, no deadlock. */
class ConcurrencyIT extends ItBase {
    private static final int TIMEOUT_SECONDS = 90; // a deadlock/hang would blow through this

    /** Runs all tasks at the same instant on separate threads and returns the HTTP statuses. */
    private List<Integer> runParallel(List<Callable<MvcResult>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<MvcResult> t : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return t.call().getResponse().getStatus();
                } catch (Throwable ex) { // an unhandled server exception would be an HTTP 500 for a real client
                    System.err.println("REQUEST FAILED WITH EXCEPTION: " + ex);
                    return 500;
                }
            }));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> f : futures) statuses.add(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            throw new AssertionError("Parallel operations did not finish in " + TIMEOUT_SECONDS + "s: DEADLOCK/HANG", e);
        } finally {
            pool.shutdownNow();
        }
        return statuses;
    }

    private BigDecimal totalBalance() {
        return jdbc.queryForObject("select coalesce(sum(balance),0) from wallets", BigDecimal.class);
    }

    /**
     * Ledger check: for every wallet, balance == initial(100) + deposits/transfers in - withdrawals/transfers out,
     * computed purely from COMPLETED transaction rows. Proves no money was created or lost.
     */
    private void assertLedgerMatchesBalances(BigDecimal initialPerWallet) {
        List<String> bad = jdbc.query("""
                select w.id, w.balance,
                  ? + coalesce((select sum(amount) from transactions where receiver_wallet_id = w.id), 0)
                    - coalesce((select sum(amount) from transactions where sender_wallet_id = w.id), 0) as expected
                from wallets w
                """, (rs, i) -> rs.getBigDecimal("balance").compareTo(rs.getBigDecimal("expected")) == 0
                ? null : "wallet " + rs.getLong("id") + " balance " + rs.getBigDecimal("balance")
                + " but ledger says " + rs.getBigDecimal("expected"), initialPerWallet);
        bad.removeIf(java.util.Objects::isNull);
        assertThat(bad).as("ledger vs balance mismatches").isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from wallets where balance < 0", Long.class)).isZero();
    }

    private void assertNoServerErrors(List<Integer> statuses) {
        assertThat(statuses).as("statuses").allMatch(s -> s == 201 || s == 422, "only 201/422 expected, got " + statuses);
    }

    @Test
    void opposingParallelTransfersBetweenTwoWalletsDoNotDeadlockAndConserveMoney() throws Exception {
        String a = register("alice");
        String b = register("bob");
        setBalance("alice", "1000");
        setBalance("bob", "1000");

        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tasks.add(() -> transfer(a, "bob", "3", null));
            tasks.add(() -> transfer(b, "alice", "2", null));
        }
        List<Integer> statuses = runParallel(tasks);

        assertThat(statuses).allMatch(s -> s == 201, "all should succeed (ample funds): " + statuses);
        // net: alice -40*3 + 40*2 = -40 ; bob +40
        assertThat(balance("alice")).isEqualByComparingTo("960");
        assertThat(balance("bob")).isEqualByComparingTo("1040");
        assertThat(totalBalance()).isEqualByComparingTo("2000");
        assertThat(txCount()).isEqualTo(80);
        assertLedgerMatchesBalances(bd("1000"));
    }

    @Test
    void parallelTransfersFromOneWalletNeverOverdrawAndExactlyAffordableOnesSucceed() throws Exception {
        String a = register("alice");
        register("bob");
        register("carol");
        // alice has 100: 30 parallel transfers of 10 to two different receivers -> exactly 10 can succeed
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String to = i % 2 == 0 ? "bob" : "carol";
            tasks.add(() -> transfer(a, to, "10", null));
        }
        List<Integer> statuses = runParallel(tasks);

        assertNoServerErrors(statuses);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(10);
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(20);
        assertThat(balance("alice")).isEqualByComparingTo("0");
        assertThat(balance("bob").add(balance("carol"))).isEqualByComparingTo("300");
        assertThat(totalBalance()).isEqualByComparingTo("300");
        assertThat(txCount()).isEqualTo(10);
        assertLedgerMatchesBalances(bd("100"));
    }

    @Test
    void parallelWithdrawalsNeverOverdraw() throws Exception {
        String a = register("alice");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 25; i++) tasks.add(() -> withdraw(a, "10", null));
        List<Integer> statuses = runParallel(tasks);

        assertNoServerErrors(statuses);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(10);
        assertThat(balance("alice")).isEqualByComparingTo("0");
        assertThat(txCount()).isEqualTo(10);
    }

    @Test
    void mixedParallelDepositsAndWithdrawalsLoseNoUpdates() throws Exception {
        String a = register("alice");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(() -> deposit(a, "5", null));
            tasks.add(() -> withdraw(a, "2", null));
        }
        List<Integer> statuses = runParallel(tasks);
        assertThat(statuses).allMatch(s -> s == 201, "ample funds, all succeed: " + statuses);
        // 100 + 30*5 - 30*2 = 190
        assertThat(balance("alice")).isEqualByComparingTo("190");
        assertThat(txCount()).isEqualTo(60);
        assertLedgerMatchesBalances(bd("100"));
    }

    @Test
    void manyWalletsRandomParallelTransfersConserveTotalAndStayNonNegative() throws Exception {
        int users = 6;
        String[] names = new String[users];
        String[] tokens = new String[users];
        for (int i = 0; i < users; i++) {
            names[i] = "user" + i;
            tokens[i] = register(names[i]);
        }
        // Tight balances so some transfers genuinely fail with 422.
        for (String n : names) setBalance(n, "50");
        java.util.Random rnd = new java.util.Random(42);
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            int from = rnd.nextInt(users);
            int to = (from + 1 + rnd.nextInt(users - 1)) % users;
            String amount = String.valueOf(1 + rnd.nextInt(30));
            tasks.add(() -> transfer(tokens[from], names[to], amount, null));
        }
        List<Integer> statuses = runParallel(tasks);

        assertNoServerErrors(statuses);
        long ok = statuses.stream().filter(s -> s == 201).count();
        assertThat(ok).isPositive();
        assertThat(txCount()).isEqualTo(ok);
        assertThat(totalBalance()).isEqualByComparingTo("300");
        assertLedgerMatchesBalances(bd("50"));
    }

    @Test
    void cyclicParallelTransfersAcrossThreeWalletsDoNotDeadlock() throws Exception {
        String a = register("alice");
        String b = register("bob");
        String c = register("carol");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(() -> transfer(a, "bob", "1", null));
            tasks.add(() -> transfer(b, "carol", "1", null));
            tasks.add(() -> transfer(c, "alice", "1", null));
            tasks.add(() -> transfer(c, "bob", "1", null));
            tasks.add(() -> transfer(b, "alice", "1", null));
            tasks.add(() -> transfer(a, "carol", "1", null));
        }
        List<Integer> statuses = runParallel(tasks);
        assertThat(statuses).allMatch(s -> s == 201, "all affordable, expected only 201: " + statuses);
        assertThat(totalBalance()).isEqualByComparingTo("300");
        assertLedgerMatchesBalances(bd("100"));
    }

    @Test
    void parallelReplayOfSameIdempotencyKeyAppliesTheTransferExactlyOnce() throws Exception {
        String a = register("alice");
        register("bob");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) tasks.add(() -> transfer(a, "bob", "10", "race-key"));
        List<Integer> statuses = runParallel(tasks);

        // Money must move exactly once and nothing may blow up with a 5xx.
        assertThat(statuses).as("statuses").allMatch(s -> s == 201 || s == 409, "no 5xx allowed: " + statuses);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isGreaterThanOrEqualTo(1);
        assertThat(txCount()).isEqualTo(1);
        assertThat(balance("alice")).isEqualByComparingTo("90");
        assertThat(balance("bob")).isEqualByComparingTo("110");
    }

    /**
     * Safety-only variant: ignores HTTP statuses (so it passes even if some requests fail with 5xx) and checks the
     * hard money invariants plus that nothing hangs. Separates "money is safe" from "requests are well-behaved".
     */
    @Test
    void mixedParallelLoadKeepsMoneyInvariantsRegardlessOfIndividualRequestOutcome() throws Exception {
        String a = register("alice");
        String b = register("bob");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(() -> transfer(a, "bob", "7", null));
            tasks.add(() -> transfer(b, "alice", "5", null));
            tasks.add(() -> withdraw(a, "3", null));
            tasks.add(() -> deposit(b, "4", null));
        }
        List<Integer> statuses = runParallel(tasks);
        System.err.println("INVARIANT TEST status histogram: " + statuses.stream()
                .collect(java.util.stream.Collectors.groupingBy(s -> s, java.util.stream.Collectors.counting())));
        assertThat(jdbc.queryForObject("select count(*) from wallets where balance < 0", Long.class)).isZero();
        assertLedgerMatchesBalances(bd("100"));
        assertThat(txCount()).isEqualTo(statuses.stream().filter(s -> s == 201).count());
    }

    @Test
    void parallelDepositReplayWithSameKeyCreditsOnce() throws Exception {
        String a = register("alice");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) tasks.add(() -> deposit(a, "10", "dep-race"));
        List<Integer> statuses = runParallel(tasks);
        assertThat(statuses).allMatch(s -> s == 201 || s == 409, "no 5xx allowed: " + statuses);
        assertThat(txCount()).isEqualTo(1);
        assertThat(balance("alice")).isEqualByComparingTo("110");
    }
}
