package com.example.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
        service = new AuthService(users, wallets, encoder, authManager, jwt, new BigDecimal("100.00"), "USD");
        when(encoder.encode("password1")).thenReturn("ENC");
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(jwt.generateToken("alice")).thenReturn("tok");
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
    void loginPropagatesBadCredentialsAndIssuesNoToken() {
        doThrow(new BadCredentialsException("bad")).when(authManager).authenticate(any());
        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "pw")))
                .isInstanceOf(BadCredentialsException.class);
        verify(jwt, never()).generateToken(any());
    }
}
