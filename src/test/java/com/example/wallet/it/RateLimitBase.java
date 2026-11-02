package com.example.wallet.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.wallet.security.RateLimits;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Helpers for the rate-limit integration tests: requests from a chosen client address, and a reset of the counters. */
abstract class RateLimitBase extends ItBase {
    @Autowired protected RateLimits limits;

    @BeforeEach
    void resetCounters() {
        limits.clear();
    }

    protected MvcResult postFrom(String ip, String url, String token, Object body, String... forwardedFor)
            throws Exception {
        MockHttpServletRequestBuilder b = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        return perform(b, ip, token, forwardedFor);
    }

    protected MvcResult getFrom(String ip, String url, String token, String... forwardedFor) throws Exception {
        return perform(get(url), ip, token, forwardedFor);
    }

    private MvcResult perform(MockHttpServletRequestBuilder b, String ip, String token, String... forwardedFor)
            throws Exception {
        for (String h : forwardedFor) b.header("X-Forwarded-For", h);
        b.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
        return mvc.perform(withAuth(b, token)).andReturn();
    }

    protected MvcResult loginFrom(String ip, String username, String password, String... forwardedFor)
            throws Exception {
        return postFrom(ip, "/api/auth/login", null, map("username", username, "password", password), forwardedFor);
    }

    protected static int status(MvcResult r) { return r.getResponse().getStatus(); }
}
