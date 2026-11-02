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

/** Exactly-once approval under parallel load: same request, two admins, many requests of one wallet, mixed work. */
class DepositRequestConcurrencyIT extends ItBase {
    private static final int TIMEOUT_SECONDS = 90; // a deadlock/hang would blow through this

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

    private long create(String token, String amount) throws Exception {
        MvcResult r = postJson("/api/deposit-requests", token, map("amount", new BigDecimal(amount)), null);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return body(r).get("id").asLong();
    }

    private MvcResult approve(String token, long id) throws Exception {
        return postJson("/api/admin/deposit-requests/" + id + "/approve", token, map(), null);
    }

    private MvcResult reject(String token, long id) throws Exception {
        return postJson("/api/admin/deposit-requests/" + id + "/reject", token, map("decisionNote", "no"), null);
    }

    private long count(List<Integer> statuses, int s) { return statuses.stream().filter(x -> x == s).count(); }

    @Test
    void manyParallelApprovalsOfOneRequestCreditExactlyOnce() throws Exception {
        String alice = register("alice");
        String adm = adminToken();
        long id = create(alice, "40");

        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) tasks.add(() -> approve(adm, id));
        List<Integer> statuses = runParallel(tasks);

        assertThat(count(statuses, 200)).as(statuses.toString()).isEqualTo(1);
        assertThat(count(statuses, 409)).as(statuses.toString()).isEqualTo(29);
        assertThat(balance("alice")).isEqualByComparingTo("140");
        assertThat(txCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from deposit_requests where status='APPROVED' "
                + "and transaction_id is not null", Long.class)).isEqualTo(1);
    }

    @Test
    void twoAdminsApprovingAndRejectingTheSameRequestAtOnceDecideItExactlyOnce() throws Exception {
        String alice = register("alice");
        String adm1 = adminToken();
        authService.createUser("admin2", "admin2@example.com", PASSWORD, com.example.wallet.entity.Role.ADMIN);
        String adm2 = login("admin2", PASSWORD);
        for (int round = 0; round < 5; round++) {
            long id = create(alice, "10");
            List<Callable<MvcResult>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> approve(adm1, id));
                tasks.add(() -> approve(adm2, id));
                tasks.add(() -> reject(adm2, id));
            }
            List<Integer> statuses = runParallel(tasks);
            assertThat(count(statuses, 200)).as(statuses.toString()).isEqualTo(1);
            assertThat(count(statuses, 409)).as(statuses.toString()).isEqualTo(11);
            String finalStatus = jdbc.queryForObject("select status from deposit_requests where id=?", String.class, id);
            BigDecimal expected = new BigDecimal("100").add(
                    new BigDecimal("10").multiply(new BigDecimal(
                            jdbc.queryForObject("select count(*) from deposit_requests where status='APPROVED'", Long.class))));
            assertThat(balance("alice")).as(finalStatus).isEqualByComparingTo(expected);
            assertThat(txCount()).isEqualTo(jdbc.queryForObject(
                    "select count(*) from deposit_requests where status='APPROVED'", Long.class));
        }
    }

    @Test
    void parallelApprovalsOfDifferentRequestsOfOneWalletAllSucceedWithTheExactSum() throws Exception {
        String alice = register("alice");
        String adm = adminToken();
        List<Long> ids = new ArrayList<>();
        for (int round = 0; round < 4; round++) {
            for (int i = 1; i <= 5; i++) {
                ids.add(create(alice, "1." + i)); // 1.1 .. 1.5
            }
            List<Callable<MvcResult>> tasks = new ArrayList<>();
            for (long id : ids.subList(round * 5, round * 5 + 5)) tasks.add(() -> approve(adm, id));
            // each request is approved twice in parallel as well (double click)
            for (long id : ids.subList(round * 5, round * 5 + 5)) tasks.add(() -> approve(adm, id));
            List<Integer> statuses = runParallel(tasks);
            assertThat(statuses).as(statuses.toString()).allMatch(s -> s == 200 || s == 409);
            assertThat(count(statuses, 200)).isEqualTo(5);
        }
        // 4 rounds * (1.1+1.2+1.3+1.4+1.5 = 6.5) = 26
        assertThat(balance("alice")).isEqualByComparingTo("126");
        assertThat(txCount()).isEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from deposit_requests where status='APPROVED'", Long.class))
                .isEqualTo(20);
        assertThat(jdbc.queryForObject("select count(distinct transaction_id) from deposit_requests", Long.class))
                .isEqualTo(20);
    }

    @Test
    void approvalsMixedWithTransfersAndAdminDepositsDoNotDeadlockAndKeepTheLedger() throws Exception {
        String alice = register("alice");
        String bob = register("bob");
        String adm = adminToken();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(create(alice, "10"));
            ids.add(create(bob, "10"));
        }
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (long id : ids) tasks.add(() -> approve(adm, id));
        for (int i = 0; i < 15; i++) {
            tasks.add(() -> transfer(alice, "bob", "1", null));
            tasks.add(() -> transfer(bob, "alice", "1", null));
            tasks.add(() -> adminDeposit(adm, "alice", "1", null));
            tasks.add(() -> postJson("/api/deposit-requests", bob, map("amount", new BigDecimal("2")), null));
        }
        List<Integer> statuses = runParallel(tasks);

        assertThat(statuses).as(statuses.toString()).allMatch(s -> s == 200 || s == 201 || s == 409);
        assertThat(count(statuses, 500)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from deposit_requests where status='APPROVED'", Long.class))
                .isEqualTo(10);
        // 2 wallets * 100 initial + 10 approvals * 10 + 15 admin deposits * 1 (transfers move money between them)
        assertThat(jdbc.queryForObject("select sum(w.balance) from wallets w join users u on u.id=w.user_id "
                + "where u.username in ('alice','bob')", BigDecimal.class))
                .isEqualByComparingTo("315");
        assertThat(jdbc.queryForObject("select count(*) from wallets where balance < 0", Long.class)).isZero();
    }

    @Test
    void parallelCreateRequestsNeverExceedThePendingCapAndReplayCreatesOne() throws Exception {
        String alice = register("alice");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> postJson("/api/deposit-requests", alice, map("amount", new BigDecimal("3")), null));
        }
        List<Integer> statuses = runParallel(tasks);
        assertThat(count(statuses, 201)).as(statuses.toString()).isEqualTo(5);
        assertThat(count(statuses, 409)).isEqualTo(15);
        assertThat(jdbc.queryForObject("select count(*) from deposit_requests", Long.class)).isEqualTo(5);

        String bob = register("bob");
        tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> postJson("/api/deposit-requests", bob, map("amount", new BigDecimal("4")), "same-key"));
        }
        List<MvcResult> results = runParallelResults(tasks);
        statuses = statusesOf(results);
        assertThat(statuses).as(statuses.toString()).allMatch(s -> s == 201);
        assertThat(distinctBodies(results)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from deposit_requests where user_id = "
                + "(select id from users where username='bob')", Long.class)).isEqualTo(1);
    }
}
