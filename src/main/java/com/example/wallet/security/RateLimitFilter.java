package com.example.wallet.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rate limiting that needs no authentication; it runs first in the security chain, so a rejected request costs no
 * token parsing, database lookup or BCrypt comparison. It applies, for requests under {@code /api/}:
 * <ul>
 *   <li>a generous cap per client IP on everything;</li>
 *   <li>{@code POST /api/auth/login}: per IP and per target username (trimmed, lower case; unknown usernames are
 *       counted exactly like known ones, so the limit reveals nothing about which accounts exist);</li>
 *   <li>{@code POST /api/auth/register}: per IP.</li>
 * </ul>
 * Every attempt counts, successful or not, so there is nothing to tell a guess from a real login. A request over a
 * limit gets 429 with {@code Retry-After}. Limits that need the caller's identity are in {@link UserRateLimitFilter}.
 */
public class RateLimitFilter extends OncePerRequestFilter {
    /** Login bodies are tiny (username up to 50 characters, password up to 72); anything larger is not inspected. */
    static final int MAX_LOGIN_BODY_BYTES = 8192;
    /** Longest username that can exist; longer ones share one bucket instead of each taking memory. */
    static final int MAX_USERNAME_LENGTH = 50;

    private static final RequestMatcher LOGIN = matcher(HttpMethod.POST, "/api/auth/login");
    private static final RequestMatcher REGISTER = matcher(HttpMethod.POST, "/api/auth/register");

    private final RateLimits limits;
    private final JsonMapper mapper;

    /**
     * Creates the filter.
     *
     * @param limits the buckets and settings
     * @param mapper reads the login body (the same mapper the controllers use, so both see the same username)
     */
    public RateLimitFilter(RateLimits limits, JsonMapper mapper) {
        this.limits = limits;
        this.mapper = mapper;
    }

    static RequestMatcher matcher(HttpMethod method, String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(method, pattern);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !limits.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = limits.clientIp(request);
        long wait = limits.globalIp.tryAcquire(ip);
        HttpServletRequest forwarded = request;
        if (wait == 0 && LOGIN.matches(request)) {
            wait = limits.loginIp.tryAcquire(ip);
            if (wait == 0) {
                ReplayableRequest replay = new ReplayableRequest(request);
                forwarded = replay;
                String username = usernameOf(replay);
                if (username != null) wait = limits.loginUsername.tryAcquire(username);
            }
        } else if (wait == 0 && REGISTER.matches(request)) {
            wait = limits.registerIp.tryAcquire(ip);
        }
        if (wait > 0) {
            limits.reject(response, wait);
            return;
        }
        chain.doFilter(forwarded, response);
    }

    /**
     * Returns the normalized username of a login request, or null when the body is not a small JSON object with a
     * string {@code username} (such a request fails validation before any password is checked, so it needs no
     * per-username bucket).
     */
    private String usernameOf(ReplayableRequest request) {
        if (!isJson(request.getContentType()) || request.prefix.length > MAX_LOGIN_BODY_BYTES) return null;
        try {
            JsonNode node = mapper.readTree(request.prefix);
            JsonNode username = node == null ? null : node.get("username");
            return username != null && username.isString() ? normalize(username.asString()) : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    /**
     * The per-username bucket key: trimmed like {@code AuthService.login} and lower case, so case variants share one
     * bucket. Names longer than any real username (they cannot match an account) all share one key, which also bounds
     * the memory an attacker can occupy per key.
     */
    static String normalize(String username) {
        String n = username.trim().toLowerCase(Locale.ROOT);
        return n.length() > MAX_USERNAME_LENGTH ? "\u0000too-long" : n;
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) return false;
        try {
            MediaType t = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.isCompatibleWith(t) || t.getSubtype().endsWith("+json");
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    /**
     * A request whose first {@value #MAX_LOGIN_BODY_BYTES}+1 body bytes were read ahead, handing the same bytes to the
     * controller afterwards (and the rest of a larger body untouched).
     */
    private static final class ReplayableRequest extends HttpServletRequestWrapper {
        private final byte[] prefix;
        private final InputStream rest;

        ReplayableRequest(HttpServletRequest request) throws IOException {
            super(request);
            InputStream in = request.getInputStream();
            this.prefix = in.readNBytes(MAX_LOGIN_BODY_BYTES + 1);
            this.rest = in;
        }

        @Override
        public ServletInputStream getInputStream() {
            InputStream replay = prefix.length > MAX_LOGIN_BODY_BYTES
                    ? new SequenceInputStream(new ByteArrayInputStream(prefix), rest)
                    : new ByteArrayInputStream(prefix);
            return new ServletInputStream() {
                private boolean finished;

                @Override
                public int read() throws IOException {
                    int b = replay.read();
                    if (b < 0) finished = true;
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    int n = replay.read(buf, off, len);
                    if (n < 0) finished = true;
                    return n;
                }

                @Override
                public boolean isFinished() { return finished; }

                @Override
                public boolean isReady() { return true; }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("async reads are not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String enc = getCharacterEncoding();
            Charset cs = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
            return new BufferedReader(new InputStreamReader(getInputStream(), cs));
        }
    }
}
