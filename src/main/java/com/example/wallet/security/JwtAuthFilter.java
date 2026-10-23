package com.example.wallet.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that authenticates requests carrying an {@code Authorization: Bearer <jwt>} header. A valid
 * token whose user still exists and is enabled puts an authentication (with the user's current role from the
 * database) into the security context; tokens of disabled users are rejected, and so are tokens whose {@code tv}
 * claim is missing or differs from the user's stored token version (revoked by logout, password change or
 * disabling). A missing or invalid token is silently ignored, leaving the request unauthenticated so that
 * {@link SecurityConfig} rejects it with 401 if the endpoint requires authentication.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    /**
     * Creates the filter.
     *
     * @param jwtService         used to validate tokens
     * @param userDetailsService used to load the user named in the token
     */
    public JwtAuthFilter(JwtService jwtService, UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    /**
     * Reads the bearer token, if any, and populates the security context; always continues the filter chain.
     *
     * @param request  the current request
     * @param response the current response
     * @param chain    the remaining filter chain
     * @throws ServletException if the rest of the chain fails
     * @throws IOException      if the rest of the chain fails with an I/O error
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                JwtService.TokenClaims claims = jwtService.parse(header.substring(7));
                UserDetails user = userDetailsService.loadUserByUsername(claims.username());
                // Revocation: the token is valid only while its tv claim equals the stored token version. A missing
                // claim (token issued before revocation existed) or any other value is rejected like an invalid token.
                if (user.isEnabled() && user instanceof AuthUser stored && claims.tokenVersion() != null
                        && claims.tokenVersion() == stored.getTokenVersion()) {
                    var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (JwtException | IllegalArgumentException | UsernameNotFoundException ignored) {
                // invalid token: leave unauthenticated; security config responds with 401
            }
        }
        chain.doFilter(request, response);
    }
}
