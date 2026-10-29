package com.example.wallet.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rate limiting that needs no authentication; it runs first in the security chain, so a rejected request costs no
 * token parsing, database lookup or BCrypt comparison. There is no gate on the request path: every request is counted
 * in the per-IP bucket, and the matchers below use the same decoded-path semantics as Spring MVC and the authorization
 * rules, so {@code /%61pi/auth/login} is the login endpoint here exactly as it is for the router.
 * <ul>
 *   <li>a generous cap per client IP on every request;</li>
 *   <li>{@code POST /api/auth/login}: per IP; per (username, IP) for every attempt; and per username across all IPs for
 *       FAILED attempts only, which holds back addresses that never logged in as that user but never an address the
 *       user has logged in from (see {@link KnownLogins}). Unknown usernames are counted exactly like known ones, so the
 *       limits reveal nothing about which accounts exist;</li>
 *   <li>{@code POST /api/auth/register}: per IP.</li>
 * </ul>
 * A request over a limit gets 429 with {@code Retry-After}. Limits that need the caller's identity are in
 * {@link UserRateLimitFilter}.
 * <p>
 * The login body is read here to find the username, so it must be read exactly as the controller will read it, and
 * anything ambiguous fails closed before a password is checked: a body over {@value #MAX_LOGIN_BODY_BYTES} bytes is 413,
 * a content type other than JSON or a charset other than UTF-8 is 415, and malformed JSON, duplicate keys, trailing
 * content and a username that is not a non-blank string are 400.
 */
public class RateLimitFilter extends OncePerRequestFilter {
    /** Login bodies are tiny (username up to 50 characters, password up to 72); anything larger is refused. */
    static final int MAX_LOGIN_BODY_BYTES = 8192;
    /** Longest username that can exist; longer ones share one bucket instead of each taking memory. */
    static final int MAX_USERNAME_LENGTH = 50;

    private static final RequestMatcher LOGIN = matcher(HttpMethod.POST, "/api/auth/login");
    private static final RequestMatcher REGISTER = matcher(HttpMethod.POST, "/api/auth/register");

    private final RateLimits limits;
    private final JsonMapper strictMapper;

    /**
     * Creates the filter.
     *
     * @param limits the buckets and settings
     * @param mapper reads the login body (the same mapper the controllers use, made strict about duplicate keys and
     *               trailing content, so the filter and the controller cannot see different usernames)
     */
    public RateLimitFilter(RateLimits limits, JsonMapper mapper) {
        this.limits = limits;
        this.strictMapper = mapper.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    static RequestMatcher matcher(HttpMethod method, String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(method, pattern);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !limits.enabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = limits.clientIp(request);
        long wait = limits.globalIp.tryAcquire(ip);
        if (wait == 0 && LOGIN.matches(request)) {
            login(request, response, chain, ip);
            return;
        }
        if (wait == 0 && REGISTER.matches(request)) wait = limits.registerIp.tryAcquire(ip);
        if (wait > 0) {
            limits.reject(response, wait);
            return;
        }
        chain.doFilter(request, response);
    }

    private void login(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String ip)
            throws ServletException, IOException {
        long wait = limits.loginIp.tryAcquire(ip);
        if (wait > 0) {
            limits.reject(response, wait);
            return;
        }
        if (request.getContentLengthLong() > MAX_LOGIN_BODY_BYTES) {
            limits.error(response, HttpStatus.CONTENT_TOO_LARGE, "Request body too large");
            return;
        }
        if (!isUtf8Json(request.getContentType())) {
            limits.error(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type must be application/json (UTF-8)");
            return;
        }
        ReplayableRequest replay = new ReplayableRequest(request, MAX_LOGIN_BODY_BYTES + 1);
        if (replay.prefix.length > MAX_LOGIN_BODY_BYTES) {
            limits.error(response, HttpStatus.CONTENT_TOO_LARGE, "Request body too large");
            return;
        }
        String username = usernameOf(replay.prefix);
        if (username == null) {
            limits.error(response, HttpStatus.BAD_REQUEST, "username: must be a non-blank string in a valid JSON object");
            return;
        }

        String pair = username + '\u0000' + ip;
        wait = limits.loginUsernameIp.tryAcquire(pair);
        if (wait > 0) {
            limits.reject(response, wait);
            return;
        }
        // An address that never logged in as this user must reserve one of the user's failure tokens first; the token
        // is given back below unless the attempt turns out to be a failed guess. Counting at admission (not after the
        // response) keeps parallel guesses from overshooting the limit.
        boolean known = limits.knownLogins.contains(pair);
        if (!known) {
            wait = limits.loginUsernameFailures.tryAcquire(username);
            if (wait > 0) {
                limits.reject(response, wait);
                return;
            }
        }
        chain.doFilter(replay, response);

        int status = response.getStatus();
        if (status >= 200 && status < 300) {
            limits.knownLogins.record(pair);
            if (!known) limits.loginUsernameFailures.refund(username);
        } else if (status == HttpStatus.UNAUTHORIZED.value()) {
            // a failed guess; from a known address it counts towards the total but can never block that address
            if (known) limits.loginUsernameFailures.tryAcquire(username);
        } else if (!known) {
            limits.loginUsernameFailures.refund(username); // validation error and the like: no password was checked
        }
    }

    /**
     * Returns the bucket key of the username in a login body, or null when the body is not a JSON object with a
     * non-blank string {@code username}, has duplicate keys or trailing content, or is not valid JSON at all. Such a
     * request would be rejected by the controller or, worse, read differently by it.
     */
    private String usernameOf(byte[] body) {
        try {
            JsonNode node = strictMapper.readTree(body);
            JsonNode username = node == null || !node.isObject() ? null : node.get("username");
            if (username == null || !username.isString() || username.asString().trim().isEmpty()) return null;
            return normalize(username.asString());
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

    /** JSON content types the message converter reads, with no charset or UTF-8. */
    private static boolean isUtf8Json(String contentType) {
        if (contentType == null) return false;
        try {
            MediaType t = MediaType.parseMediaType(contentType);
            boolean json = MediaType.APPLICATION_JSON.isCompatibleWith(t) || t.getSubtype().endsWith("+json");
            return json && (t.getCharset() == null || StandardCharsets.UTF_8.equals(t.getCharset()));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }
}
