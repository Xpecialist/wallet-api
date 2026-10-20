package com.example.wallet.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
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

    private void run() throws Exception {
        filter.doFilter(req, res, chain);
        verify(chain).doFilter(req, res); // chain always continues
    }

    @Test
    void validTokenAuthenticatesWithRolesFromUserDetails() throws Exception {
        when(uds.loadUserByUsername("alice")).thenReturn(User.withUsername("alice").password("x").roles("ADMIN").build());
        req.addHeader("Authorization", "Bearer " + jwt.generateToken("alice"));
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
        req.addHeader("Authorization", "Bearer " + jwt.generateToken("alice"));
        run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validTokenForDisabledUserIsRejected() throws Exception {
        when(uds.loadUserByUsername("alice"))
                .thenReturn(User.withUsername("alice").password("x").roles("USER").disabled(true).build());
        req.addHeader("Authorization", "Bearer " + jwt.generateToken("alice"));
        run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
