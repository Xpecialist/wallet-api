package com.example.wallet.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.wallet.TestData;
import com.example.wallet.entity.Role;
import com.example.wallet.exception.ApiException;
import com.example.wallet.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AdminBootstrapTest {
    static final String STRONG = "a-strong-admin-pass";
    AuthService auth = mock(AuthService.class);
    UserRepository users = mock(UserRepository.class);

    private AdminBootstrap bootstrap(String password) {
        return new AdminBootstrap(auth, users, "admin", "a@x.com", password);
    }

    @Test
    void createsAdminWhenPasswordSetAndUserAbsent() {
        bootstrap(STRONG).run();
        verify(auth).createUser("admin", "a@x.com", STRONG, Role.ADMIN);
    }

    @Test
    void doesNothingWhenPasswordBlankOrNull() {
        bootstrap("").run();
        bootstrap("  ").run();
        bootstrap(null).run();
        verifyNoInteractions(auth);
    }

    @Test
    void doesNothingWhenAdminAlreadyExists() {
        when(users.findByUsername("admin")).thenReturn(Optional.of(TestData.user(1, "admin", Role.ADMIN)));
        bootstrap(STRONG).run();
        verify(auth, never()).createUser(any(), any(), any(), any());
    }

    @Test
    void weakAdminPasswordCreatesNoAdminAndDoesNotThrow() {
        for (String weak : new String[] {"secret", "short-pass", "elevenchars"}) {
            assertThatCode(() -> bootstrap(weak).run()).doesNotThrowAnyException();
        }
        verifyNoInteractions(auth);
    }

    @Test
    void twelveCharactersIsEnoughButMoreThan72BytesIsNot() {
        bootstrap("twelve-chars").run();
        verify(auth).createUser("admin", "a@x.com", "twelve-chars", Role.ADMIN);
        org.mockito.Mockito.clearInvocations(auth);
        bootstrap("x".repeat(73)).run();
        verifyNoInteractions(auth);
    }

    @Test
    void squattedAdminUsernameIsLoggedNotFatalAndNothingIsCreated() {
        when(users.findByUsername("admin")).thenReturn(Optional.of(TestData.user(5, "admin", Role.USER)));
        assertThatCode(() -> bootstrap(STRONG).run()).doesNotThrowAnyException();
        verify(auth, never()).createUser(any(), any(), any(), any());
    }

    @Test
    void takenAdminEmailIsLoggedNotFatalAndNothingIsCreated() {
        when(users.existsByEmail("a@x.com")).thenReturn(true);
        assertThatCode(() -> bootstrap(STRONG).run()).doesNotThrowAnyException();
        verify(auth, never()).createUser(any(), any(), any(), any());
    }

    @Test
    void failureWhileCreatingTheAdminDoesNotAbortStartup() {
        when(auth.createUser(any(), any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "Email already registered"));
        assertThatCode(() -> bootstrap(STRONG).run()).doesNotThrowAnyException();
    }
}
