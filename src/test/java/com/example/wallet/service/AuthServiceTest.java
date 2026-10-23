package com.example.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.wallet.dto.AuthResponse;
import com.example.wallet.dto.LoginRequest;
import com.example.wallet.dto.RegisterRequest;
import com.example.wallet.entity.Role;
import com.example.wallet.entity.User;
import com.example.wallet.entity.Wallet;
import com.example.wallet.exception.ApiException;
import com.example.wallet.repository.UserRepository;
import com.example.wallet.repository.WalletRepository;
import com.example.wallet.security.JwtService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {
    UserRepository users = mock(UserRepository.class);
    WalletRepository wallets = mock(WalletRepository.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    AuthenticationManager authManager = mock(AuthenticationManager.class);
    JwtService jwt = mock(JwtService.class);
    AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, wallets, encoder, authManager, jwt, new BigDecimal("100.00"), "USD", "admin", "admin@example.com");
        when(encoder.encode("password1")).thenReturn("ENC");
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(jwt.generateToken("alice")).thenReturn("tok");
        when(authManager.authenticate(any())).thenReturn(new TestingAuthenticationToken("alice", "pw"));
    }

    @Test
    void registerCreatesUserWithHashedPasswordAndFundedWalletAndReturnsToken() {
        AuthResponse r = service.register(new RegisterRequest("alice", "a@example.com", "password1"));

        assertThat(r.token()).isEqualTo("tok");
        assertThat(r.tokenType()).isEqualTo("Bearer");
        ArgumentCaptor<User> u = ArgumentCaptor.forClass(User.class);
        verify(users).save(u.capture());
        assertThat(u.getValue().getPasswordHash()).isEqualTo("ENC");
        assertThat(u.getValue().getRole()).isEqualTo(Role.USER);
        ArgumentCaptor<Wallet> w = ArgumentCaptor.forClass(Wallet.class);
        verify(wallets).save(w.capture());
        assertThat(w.getValue().getBalance()).isEqualByComparingTo("100");
        assertThat(w.getValue().getCurrency()).isEqualTo("USD");
        assertThat(w.getValue().getUser().getUsername()).isEqualTo("alice");
    }

    @Test
    void registerDuplicateUsernameIs409() {
        when(users.existsByUsername("alice")).thenReturn(true);
        assertThatThrownBy(() -> service.register(new RegisterRequest("alice", "a@example.com", "password1")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(users, never()).save(any());
        verify(wallets, never()).save(any());
    }

    @Test
    void registerDuplicateEmailIs409() {
        when(users.existsByEmail("a@example.com")).thenReturn(true);
        assertThatThrownBy(() -> service.register(new RegisterRequest("alice", "a@example.com", "password1")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(wallets, never()).save(any());
    }

    @Test
    void createUserCanCreateAdmin() {
        User u = service.createUser("alice", "a@example.com", "password1", Role.ADMIN);
        assertThat(u.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void loginReturnsTokenWhenAuthenticationSucceeds() {
        assertThat(service.login(new LoginRequest("alice", "pw")).token()).isEqualTo("tok");
    }

    @Test
    void loginTrimsUsernameAndIssuesTokenForTheStoredUsername() {
        when(authManager.authenticate(any())).thenReturn(new TestingAuthenticationToken("Alice", "pw"));
        when(jwt.generateToken("Alice")).thenReturn("tok-stored");
        assertThat(service.login(new LoginRequest("  alice ", "pw")).token()).isEqualTo("tok-stored");
        ArgumentCaptor<org.springframework.security.core.Authentication> a =
                ArgumentCaptor.forClass(org.springframework.security.core.Authentication.class);
        verify(authManager).authenticate(a.capture());
        assertThat(a.getValue().getPrincipal()).isEqualTo("alice");
    }

    @Test
    void loginRejectsPasswordsOver72BytesWithBadCredentialsAndNeverTruncates() {
        // 73 ASCII bytes, and 37 two-byte characters (74 bytes but only 37 chars)
        for (String pw : new String[] {"a".repeat(73), "\u00E9".repeat(37)}) {
            assertThatThrownBy(() -> service.login(new LoginRequest("alice", pw)))
                    .isInstanceOf(BadCredentialsException.class);
        }
        verify(authManager, never()).authenticate(any());
        verify(jwt, never()).generateToken(any());
        // exactly 72 bytes still goes to the authentication manager
        assertThat(service.login(new LoginRequest("alice", "a".repeat(72))).token()).isEqualTo("tok");
        assertThat(service.login(new LoginRequest("alice", "\u00E9".repeat(36))).token()).isEqualTo("tok");
    }

    @Test
    void loginPropagatesDisabledAccountAndIssuesNoToken() {
        doThrow(new DisabledException("off")).when(authManager).authenticate(any());
        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "pw"))).isInstanceOf(DisabledException.class);
        verify(jwt, never()).generateToken(any());
    }

    @Test
    void registerTrimsUsernameAndEmailButKeepsCasing() {
        when(jwt.generateToken("Alice")).thenReturn("tok2");
        AuthResponse r = service.register(new RegisterRequest("  Alice ", " A@Example.com ", "password1"));
        assertThat(r.token()).isEqualTo("tok2");
        ArgumentCaptor<User> u = ArgumentCaptor.forClass(User.class);
        verify(users).save(u.capture());
        assertThat(u.getValue().getUsername()).isEqualTo("Alice");
        assertThat(u.getValue().getEmail()).isEqualTo("A@Example.com");
        verify(users).existsByUsername("Alice");
        verify(users).existsByEmail("A@Example.com");
    }

    @Test
    void registerRejectsUsernameThatIsTooShortAfterTrimming() {
        assertThatThrownBy(() -> service.register(new RegisterRequest("  ab  ", "a@example.com", "password1")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(users, never()).save(any());
    }

    @Test
    void registrationCannotTakeTheReservedAdminUsernameOrEmailInAnyCasing() {
        for (var req : new RegisterRequest[] {
                new RegisterRequest("admin", "x@example.com", "password1"),
                new RegisterRequest(" ADMIN ", "x@example.com", "password1"),
                new RegisterRequest("mallory", "Admin@Example.com", "password1")}) {
            assertThatThrownBy(() -> service.register(req))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }
        verify(users, never()).save(any());
        verify(wallets, never()).save(any());
    }

    @Test
    void adminRoleMayUseTheReservedNameAndNewWalletsStartAtTheConfiguredBalance() {
        AuthService zero = new AuthService(users, wallets, encoder, authManager, jwt, BigDecimal.ZERO, "USD",
                "admin", "admin@example.com");
        when(encoder.encode("password1")).thenReturn("ENC");
        User u = zero.createUser("admin", "admin@example.com", "password1", Role.ADMIN);
        assertThat(u.getRole()).isEqualTo(Role.ADMIN);
        ArgumentCaptor<Wallet> w = ArgumentCaptor.forClass(Wallet.class);
        verify(wallets).save(w.capture());
        assertThat(w.getValue().getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void loginPropagatesBadCredentialsAndIssuesNoToken() {
        doThrow(new BadCredentialsException("bad")).when(authManager).authenticate(any());
        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "pw")))
                .isInstanceOf(BadCredentialsException.class);
        verify(jwt, never()).generateToken(any());
    }
}
