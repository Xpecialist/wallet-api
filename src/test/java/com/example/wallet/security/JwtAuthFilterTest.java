package com.example.wallet.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class JwtAuthFilterTest {
    JwtService jwt = new JwtService(
            Base64.getEncoder().encodeToString("0123456789-0123456789-0123456789-AB".getBytes()), 60_000);
    UserDetailsService uds = mock(UserDetailsService.class);
    JwtAuthFilter filter = new JwtAuthFilter(jwt, uds);
    FilterChain chain = mock(FilterChain.class);
    MockHttpServletRequest req = new MockHttpServletRequest();
    MockHttpServletResponse res = new MockHttpServletResponse();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static AuthUser stored(String role, boolean enabled, long tokenVersion) {
        return new AuthUser("alice", "x", enabled, List.of(new SimpleGrantedAuthority(role)), tokenVersion);
    }

    private void run() throws Exception {
        filter.doFilter(req, res, chain);
        verify(chain).doFilter(req, res); // chain always continues
    }

    @Test
    void validTokenAuthenticatesWithRolesFromUserDetails() throws Exception {
        when(uds.loadUserByUsername("alice")).thenReturn(stored("ROLE_ADMIN", true, 0));
        req.addHeader("Authorization", "Bearer " + jwt.generateToken("alice", 0L));
        run();
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("alice");
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
    }

    @Test
    void noHeaderLeavesUnauthenticated() throws Exception {
        run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void nonBearerSchemeIsIgnored() throws Exception {
        req.addHeader("Authorization", "Basic abc");
        run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void invalidTokenIsIgnoredWithoutError() throws Exception {
        req.addHeader("Authorization", "Bearer garbage");
        run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void emptyBearerTokenIsIgnoredWithoutError() throws Exception {
        req.addHeader("Authorization", "Bearer ");
        run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validTokenForDeletedUserIsIgnored() throws Exception {
        when(uds.loadUserByUsername("alice")).thenThrow(new UsernameNotFoundException("gone"));
        req.addHeader("Authorization", "Bearer " + jwt.generateToken("alice", 0L));
        run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validTokenForDisabledUserIsRejected() throws Exception {
        when(uds.loadUserByUsername("alice"))
                .thenReturn(stored("ROLE_USER", false, 0));
        req.addHeader("Authorization", "Bearer " + jwt.generateToken("alice", 0L));
        run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void bearer(String token) { req.addHeader("Authorization", "Bearer " + token); }

    @Test
    void tokenWithMatchingVersionAuthenticates() throws Exception {
        when(uds.loadUserByUsername("alice")).thenReturn(stored("ROLE_USER", true, 5));
        bearer(jwt.generateToken("alice", 5));
        run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void tokenWithOlderOrNewerVersionIsRejected() throws Exception {
        when(uds.loadUserByUsername("alice")).thenReturn(stored("ROLE_USER", true, 5));
        bearer(jwt.generateToken("alice", 4));
        run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        req = new MockHttpServletRequest();
        bearer(jwt.generateToken("alice", 6));
        filter.doFilter(req, res, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void tokenWithoutVersionClaimIsRejected() throws Exception {
        when(uds.loadUserByUsername("alice")).thenReturn(stored("ROLE_USER", true, 0));
        var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(
                Base64.getEncoder().encodeToString("0123456789-0123456789-0123456789-AB".getBytes())));
        bearer(io.jsonwebtoken.Jwts.builder().subject("alice").issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000)).signWith(key).compact());
        run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).as("legacy token, version 0 user").isNull();
    }

    @Test
    void principalThatCarriesNoVersionIsRejectedFailClosed() throws Exception {
        when(uds.loadUserByUsername("alice"))
                .thenReturn(org.springframework.security.core.userdetails.User.withUsername("alice").password("x")
                        .roles("USER").build());
        bearer(jwt.generateToken("alice", 0));
        run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void matchingVersionOfADisabledUserIsStillRejected() throws Exception {
        when(uds.loadUserByUsername("alice")).thenReturn(stored("ROLE_USER", false, 3));
        bearer(jwt.generateToken("alice", 3));
        run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
