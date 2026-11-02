package com.example.wallet.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.wallet.entity.Role;
import com.example.wallet.service.AuthService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base for integration tests: full Spring context, real filter chain (JWT), real PostgreSQL ({@code walletdb_test}).
 * Every test starts from empty tables.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@ContextConfiguration(initializers = TestDbGuard.class)
abstract class ItBase {
    static final String PASSWORD = "Passw0rd!123";

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected DataSource dataSource;
    @Autowired protected JsonMapper json;
    @Autowired protected AuthService authService;

    @BeforeEach
    void cleanDatabase() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            TestDbGuard.assertConnectedToTestDb(c); // never truncate anything but walletdb_test
        }
        jdbc.execute("TRUNCATE TABLE deposit_requests, transactions, wallets, users RESTART IDENTITY CASCADE");
    }

    // ---- request helpers -------------------------------------------------------------------------------------

    protected MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder b, String token) {
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }

    protected MvcResult postJson(String url, String token, Object body, String idemKey) throws Exception {
        MockHttpServletRequestBuilder b = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        if (idemKey != null) b.header("Idempotency-Key", idemKey);
        return mvc.perform(withAuth(b, token)).andReturn();
    }

    protected MvcResult patchJson(String url, String token, Object body) throws Exception {
        return mvc.perform(withAuth(patch(url).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)), token)).andReturn();
    }

    protected MvcResult getJson(String url, String token) throws Exception {
        return mvc.perform(withAuth(get(url), token)).andReturn();
    }

    protected JsonNode body(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString();
        return s.isEmpty() ? null : json.readTree(s);
    }

    protected static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ---- domain helpers --------------------------------------------------------------------------------------

    /** Registers through the real endpoint and returns the JWT. */
    protected String register(String username) throws Exception {
        MvcResult r = postJson("/api/auth/register", null,
                map("username", username, "email", username + "@example.com", "password", PASSWORD), null);
        if (r.getResponse().getStatus() != 201) {
            throw new AssertionError("register failed: " + r.getResponse().getStatus() + " "
                    + r.getResponse().getContentAsString());
        }
        return body(r).get("token").asText();
    }

    protected String login(String username, String password) throws Exception {
        MvcResult r = postJson("/api/auth/login", null, map("username", username, "password", password), null);
        if (r.getResponse().getStatus() != 200) throw new AssertionError("login failed " + r.getResponse().getStatus());
        return body(r).get("token").asText();
    }

    /** Creates an ADMIN directly through the service (the bootstrap only runs at startup) and logs in. */
    protected String adminToken() throws Exception {
        boolean exists = jdbc.queryForObject("select count(*) from users where username='admin'", Long.class) > 0;
        if (!exists) authService.createUser("admin", "admin@example.com", PASSWORD, Role.ADMIN);
        return login("admin", PASSWORD);
    }

    protected long walletId(String username) {
        return jdbc.queryForObject("select w.id from wallets w join users u on u.id=w.user_id where u.username=?",
                Long.class, username);
    }

    protected BigDecimal balance(String username) {
        return jdbc.queryForObject("select w.balance from wallets w join users u on u.id=w.user_id "
                + "where u.username=?", BigDecimal.class, username);
    }

    protected void setBalance(String username, String amount) {
        jdbc.update("update wallets set balance=? where id=?", new BigDecimal(amount), walletId(username));
    }

    protected long txCount() {
        return jdbc.queryForObject("select count(*) from transactions", Long.class);
    }

    protected static BigDecimal bd(String s) { return new BigDecimal(s); }

    protected MvcResult transfer(String token, String toUsername, String amount, String idemKey) throws Exception {
        return postJson("/api/transactions/transfer", token,
                map("receiverUsername", toUsername, "amount", new BigDecimal(amount)), idemKey);
    }

    /** Money enters only through the admin deposit endpoint; credits the wallet of {@code username}. */
    protected MvcResult adminDeposit(String adminToken, String username, String amount, String idemKey)
            throws Exception {
        return postJson("/api/admin/wallets/" + walletId(username) + "/deposit", adminToken,
                map("amount", new BigDecimal(amount)), idemKey);
    }

    protected MvcResult withdraw(String token, String amount, String idemKey) throws Exception {
        return postJson("/api/wallets/me/withdraw", token, map("amount", new BigDecimal(amount)), idemKey);
    }

    /**
     * Runs all tasks at the same instant on separate threads and returns the full responses (status and body), in
     * task order. A task that throws fails the test, since a real client would see a 5xx.
     */
    protected List<MvcResult> runParallelResults(List<Callable<MvcResult>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (Callable<MvcResult> t : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return t.call();
            }));
        }
        ready.await();
        go.countDown();
        List<MvcResult> results = new ArrayList<>();
        try {
            for (Future<MvcResult> f : futures) results.add(f.get(90, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            throw new AssertionError("Parallel operations did not finish in 90s: DEADLOCK/HANG", e);
        } finally {
            pool.shutdownNow();
        }
        return results;
    }

    protected static List<Integer> statusesOf(List<MvcResult> results) {
        return results.stream().map(r -> r.getResponse().getStatus()).toList();
    }

    protected static long distinctBodies(List<MvcResult> results) {
        return results.stream().map(r -> {
            try {
                return r.getResponse().getContentAsString();
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }).distinct().count();
    }
}
