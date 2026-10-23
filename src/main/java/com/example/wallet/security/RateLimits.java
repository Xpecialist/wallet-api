package com.example.wallet.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the rate-limit buckets, resolves the client address and writes the 429 response. Used by
 * {@link RateLimitFilter} (before authentication) and {@link UserRateLimitFilter} (after it).
 */
@Component
public class RateLimits {
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    /** Longest IP text accepted from X-Forwarded-For (an IPv6 address with an embedded IPv4 is 45 characters). */
    private static final int MAX_IP_LENGTH = 45;

    private final boolean enabled;
    private final boolean trustForwardedHeaders;
    private final int trustedProxies;
    private final JsonMapper mapper;
    final TokenBucketLimiter globalIp;
    final TokenBucketLimiter loginIp;
    final TokenBucketLimiter loginUsername;
    final TokenBucketLimiter registerIp;
    final TokenBucketLimiter passwordUser;
    final TokenBucketLimiter passwordIp;
    final TokenBucketLimiter moneyUser;
    final TokenBucketLimiter adminUser;

    /**
     * Creates the limiters from the settings.
     *
     * @param p      the settings
     * @param mapper writes the JSON error body
     */
    @Autowired
    public RateLimits(RateLimitProperties p, JsonMapper mapper) {
        this.enabled = p.isEnabled();
        this.trustForwardedHeaders = p.isTrustForwardedHeaders();
        this.trustedProxies = Math.max(1, p.getTrustedProxies());
        this.mapper = mapper;
        this.globalIp = limiter(p.getGlobalIp(), p);
        this.loginIp = limiter(p.getLoginIp(), p);
        this.loginUsername = limiter(p.getLoginUsername(), p);
        this.registerIp = limiter(p.getRegisterIp(), p);
        this.passwordUser = limiter(p.getPasswordUser(), p);
        this.passwordIp = limiter(p.getPasswordIp(), p);
        this.moneyUser = limiter(p.getMoneyUser(), p);
        this.adminUser = limiter(p.getAdminUser(), p);
    }

    private static TokenBucketLimiter limiter(RateLimitProperties.Rule r, RateLimitProperties p) {
        return new TokenBucketLimiter(r.getCapacity(), r.getWindow(), p.getMaxKeys());
    }

    /** @return false when {@code app.rate-limit.enabled=false}: the filters then do nothing */
    boolean enabled() { return enabled; }

    /** Forgets every counter (test helper). */
    public void clear() {
        for (TokenBucketLimiter l : List.of(globalIp, loginIp, loginUsername, registerIp, passwordUser, passwordIp,
                moneyUser, adminUser)) {
            l.clear();
        }
    }

    /**
     * The address a request is attributed to. By default this is the TCP peer address; a client cannot change it.
     * With {@code trust-forwarded-headers=true} it is taken from {@code X-Forwarded-For}, counting from the RIGHT:
     * every trusted proxy appends the address it received the request from, so the entry {@code trusted-proxies}
     * positions from the right is the last one a trusted proxy vouches for, and anything further left (which the client
     * can write freely) is ignored. If the header has fewer entries than there are trusted proxies, or the entry is not
     * address-like, the peer address is used.
     *
     * @param request the request
     * @return the address used as rate-limit key
     */
    String clientIp(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (!trustForwardedHeaders) return peer;
        List<String> entries = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders(FORWARDED_FOR))) {
            for (String part : header.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) entries.add(t);
            }
        }
        int index = entries.size() - trustedProxies;
        if (index < 0) return peer;
        String candidate = entries.get(index);
        return looksLikeIp(candidate) ? candidate.toLowerCase(Locale.ROOT) : peer;
    }

    private static boolean looksLikeIp(String s) {
        if (s.isEmpty() || s.length() > MAX_IP_LENGTH) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                    || c == '.' || c == ':';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Writes the 429 response: a {@code Retry-After} header and the project's JSON error shape.
     *
     * @param response          the response
     * @param retryAfterSeconds seconds until the client may try again
     * @throws IOException if writing fails
     */
    void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        String message = "Too many requests, try again in " + retryAfterSeconds
                + (retryAfterSeconds == 1 ? " second" : " seconds");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("error", message);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
