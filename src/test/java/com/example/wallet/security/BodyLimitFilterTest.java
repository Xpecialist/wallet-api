package com.example.wallet.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class BodyLimitFilterTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final BodyLimitFilter filter = new BodyLimitFilter(100, mapper);

    private static MockHttpServletRequest post(String path, int bytes, boolean chunked) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.setContentType("application/json");
        r.setContent("x".repeat(bytes).getBytes(StandardCharsets.UTF_8));
        if (chunked) r.addHeader("Transfer-Encoding", "chunked");
        return r;
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, AtomicReference<String> seen,
                                        AtomicBoolean reached) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(request, resp, new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest rq, ServletResponse rs) throws IOException, ServletException {
                reached.set(true);
                seen.set(new String(rq.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                super.doFilter(rq, rs);
            }
        });
        return resp;
    }

    @Test
    void aBodyAtTheCapPassesAndIsReplayedIntact() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicBoolean reached = new AtomicBoolean();
        assertThat(run(post("/api/auth/register", 100, false), seen, reached).getStatus()).isEqualTo(200);
        assertThat(reached).isTrue();
        assertThat(seen.get()).isEqualTo("x".repeat(100));
        assertThat(run(post("/api/auth/register", 100, true), seen, reached).getStatus()).isEqualTo(200);
        assertThat(seen.get()).isEqualTo("x".repeat(100));
    }

    @Test
    void aBodyOverTheCapIs413InTheJsonShapeWithDeclaredLengthOrChunked() throws Exception {
        for (boolean chunked : new boolean[] {false, true}) {
            AtomicBoolean reached = new AtomicBoolean();
            MockHttpServletResponse resp = run(post("/api/transactions/transfer", 101, chunked),
                    new AtomicReference<>(), reached);
            assertThat(resp.getStatus()).as("chunked=" + chunked).isEqualTo(413);
            assertThat(reached).isFalse();
            assertThat(resp.getContentType()).startsWith("application/json");
            assertThat(mapper.readTree(resp.getContentAsString()).get("status").asInt()).isEqualTo(413);
            assertThat(mapper.readTree(resp.getContentAsString()).size()).isEqualTo(3);
        }
    }

    @Test
    void encodedAndPlainApiPathsAreCappedAndOtherPathsAreNot() throws Exception {
        for (String path : new String[] {"/api/x", "/%61pi/x", "/ap%69/auth/login", "/api"}) {
            assertThat(run(post(path, 500, false), new AtomicReference<>(), new AtomicBoolean()).getStatus())
                    .as(path).isEqualTo(413);
        }
        for (String path : new String[] {"/other", "/apix/y", "/error"}) {
            assertThat(run(post(path, 500, false), new AtomicReference<>(), new AtomicBoolean()).getStatus())
                    .as(path).isEqualTo(200);
        }
    }

    @Test
    void requestsWithoutABodyPassUntouched() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        assertThat(run(new MockHttpServletRequest("GET", "/api/wallets/me"), new AtomicReference<>(), reached)
                .getStatus()).isEqualTo(200);
        assertThat(reached).isTrue();
    }
}
