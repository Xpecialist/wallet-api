package com.example.wallet.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limits keyed by the authenticated user; it runs right after {@link JwtAuthFilter}. A request without a valid
 * authentication passes through untouched (the security rules answer 401 for it, which is cheap). Applies:
 * <ul>
 *   <li>{@code POST /api/users/me/password}: per user and per client IP;</li>
 *   <li>money-moving endpoints (transfer, withdraw, deposit request): per user;</li>
 *   <li>admin deposit / approve / reject: per user, with a much higher limit.</li>
 * </ul>
 */
public class UserRateLimitFilter extends OncePerRequestFilter {
    private static final RequestMatcher PASSWORD =
            RateLimitFilter.matcher(HttpMethod.POST, "/api/users/me/password");
    private static final RequestMatcher[] MONEY = {
        RateLimitFilter.matcher(HttpMethod.POST, "/api/transactions/transfer"),
        RateLimitFilter.matcher(HttpMethod.POST, "/api/wallets/me/withdraw"),
        RateLimitFilter.matcher(HttpMethod.POST, "/api/deposit-requests"),
    };
    private static final RequestMatcher[] ADMIN = {
        RateLimitFilter.matcher(HttpMethod.POST, "/api/admin/wallets/*/deposit"),
        RateLimitFilter.matcher(HttpMethod.POST, "/api/admin/deposit-requests/*/approve"),
        RateLimitFilter.matcher(HttpMethod.POST, "/api/admin/deposit-requests/*/reject"),
    };

    private final RateLimits limits;

    /** @param limits the buckets and settings */
    public UserRateLimitFilter(RateLimits limits) { this.limits = limits; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !limits.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            String user = auth.getName().trim().toLowerCase(Locale.ROOT);
            long wait = 0;
            if (PASSWORD.matches(request)) {
                wait = limits.passwordUser.tryAcquire(user);
                if (wait == 0) wait = limits.passwordIp.tryAcquire(limits.clientIp(request));
            } else if (matchesAny(MONEY, request)) {
                wait = limits.moneyUser.tryAcquire(user);
            } else if (matchesAny(ADMIN, request)) {
                wait = limits.adminUser.tryAcquire(user);
            }
            if (wait > 0) {
                limits.reject(response, wait);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean matchesAny(RequestMatcher[] matchers, HttpServletRequest request) {
        for (RequestMatcher m : matchers) {
            if (m.matches(request)) return true;
        }
        return false;
    }
}
