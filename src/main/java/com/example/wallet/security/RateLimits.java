package com.example.wallet.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the rate-limit buckets, resolves the client address and writes the 429 response. Used by
 * {@link RateLimitFilter} (before authentication) and {@link UserRateLimitFilter} (after it).
 */
@Component
public class RateLimits {
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final boolean enabled;
    private final boolean trustForwardedHeaders;
    private final int trustedProxies;
    private final int ipv6PrefixBits;
    private final JsonMapper mapper;
    final TokenBucketLimiter globalIp;
    final TokenBucketLimiter loginIp;
    final TokenBucketLimiter loginUsernameIp;
    final TokenBucketLimiter loginUsernameFailures;
    final KnownLogins knownLogins;
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
        if (p.getIpv6PrefixBits() < 1 || p.getIpv6PrefixBits() > 128) {
            throw new IllegalArgumentException("app.rate-limit.ipv6-prefix-bits must be between 1 and 128");
        }
        this.ipv6PrefixBits = p.getIpv6PrefixBits();
        this.mapper = mapper;
        this.globalIp = limiter(p.getGlobalIp(), p);
        this.loginIp = limiter(p.getLoginIp(), p);
        this.loginUsernameIp = limiter(p.getLoginUsernameIp(), p);
        this.loginUsernameFailures = limiter(p.getLoginUsernameFailures(), p);
        this.knownLogins = new KnownLogins(p.getKnownLoginTtl(), p.getKnownLoginMax());
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
        knownLogins.clear();
        for (TokenBucketLimiter l : List.of(globalIp, loginIp, loginUsernameIp, loginUsernameFailures, registerIp,
                passwordUser, passwordIp, moneyUser, adminUser)) {
            l.clear();
        }
    }

    /**
     * The key a request is attributed to. By default the address is the TCP peer address; a client cannot change it.
     * With {@code trust-forwarded-headers=true} it is taken from {@code X-Forwarded-For}, counting from the RIGHT:
     * every trusted proxy appends the address it received the request from, so the entry {@code trusted-proxies}
     * positions from the right is the last one a trusted proxy vouches for, and anything further left (which the client
     * can write freely) is ignored. If the header has fewer entries than there are trusted proxies, or the entry is not
     * a valid IP literal, the peer address is used. The address is then normalized by {@link ClientAddress}: IPv6
     * clients share a key per {@code ipv6-prefix-bits} prefix (default /64), IPv4-mapped IPv6 counts as IPv4.
     *
     * @param request the request
     * @return the key used for the per-IP buckets
     */
    String clientIp(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (trustForwardedHeaders) {
            List<String> entries = new ArrayList<>();
            for (String header : Collections.list(request.getHeaders(FORWARDED_FOR))) {
                for (String part : header.split(",")) {
                    String t = part.trim();
                    if (!t.isEmpty()) entries.add(t);
                }
            }
            int index = entries.size() - trustedProxies;
            if (index >= 0) {
                String key = ClientAddress.key(entries.get(index), ipv6PrefixBits);
                if (key != null) return key;
            }
        }
        String key = ClientAddress.key(peer, ipv6PrefixBits);
        return key != null ? key : peer;
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
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        error(response, HttpStatus.TOO_MANY_REQUESTS, message);
    }

    /**
     * Writes any other error the filters produce (413, 415, 400) in the same JSON shape.
     *
     * @param response the response
     * @param status   the HTTP status
     * @param message  the generic message
     * @throws IOException if writing fails
     */
    void error(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        ApiErrors.write(response, mapper, status, message);
    }
}
