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
 * token whose user still exists puts an authentication (with the user's current role from the database) into the
 * security context. A missing or invalid token is silently ignored, leaving the request unauthenticated so that
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
                String username = jwtService.extractUsername(header.substring(7));
                UserDetails user = userDetailsService.loadUserByUsername(username);
                var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException | UsernameNotFoundException ignored) {
                // invalid token: leave unauthenticated; security config responds with 401
            }
        }
        chain.doFilter(request, response);
    }
}
