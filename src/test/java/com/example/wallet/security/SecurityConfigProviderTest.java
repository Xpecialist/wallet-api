package com.example.wallet.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

/** F3: the password is compared before the enabled flag, so a disabled account costs the same as an enabled one. */
class SecurityConfigProviderTest {
    UserDetailsService uds = mock(UserDetailsService.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    DaoAuthenticationProvider provider = new SecurityConfig().authenticationProvider(uds, encoder);

    private void user(boolean enabled) {
        when(uds.loadUserByUsername("alice")).thenReturn(
                User.withUsername("alice").password("HASH").roles("USER").disabled(!enabled).build());
    }

    private UsernamePasswordAuthenticationToken creds(String pw) {
        return new UsernamePasswordAuthenticationToken("alice", pw);
    }

    @Test
    void disabledUserWithWrongPasswordStillRunsThePasswordComparisonAndGetsBadCredentials() {
        user(false);
        when(encoder.matches("wrong", "HASH")).thenReturn(false);
        assertThatThrownBy(() -> provider.authenticate(creds("wrong"))).isInstanceOf(BadCredentialsException.class);
        verify(encoder).matches("wrong", "HASH");
    }

    @Test
    void disabledUserWithRightPasswordIsDisabledAfterTheComparison() {
        user(false);
        when(encoder.matches("right", "HASH")).thenReturn(true);
        assertThatThrownBy(() -> provider.authenticate(creds("right"))).isInstanceOf(DisabledException.class);
        verify(encoder).matches("right", "HASH");
    }

    @Test
    void enabledUserWithRightPasswordAuthenticates() {
        user(true);
        when(encoder.matches("right", "HASH")).thenReturn(true);
        assertThat(provider.authenticate(creds("right")).isAuthenticated()).isTrue();
    }

    @Test
    void enabledUserWithWrongPasswordIsRejected() {
        user(true);
        when(encoder.matches(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> provider.authenticate(creds("nope"))).isInstanceOf(BadCredentialsException.class);
    }
}
