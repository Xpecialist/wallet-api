package com.example.wallet.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.wallet.entity.Role;
import com.example.wallet.repository.UserRepository;
import org.junit.jupiter.api.Test;

class AdminBootstrapTest {
    AuthService auth = mock(AuthService.class);
    UserRepository users = mock(UserRepository.class);

    @Test
    void createsAdminWhenPasswordSetAndUserAbsent() {
        new AdminBootstrap(auth, users, "admin", "a@x.com", "secret").run();
        verify(auth).createUser("admin", "a@x.com", "secret", Role.ADMIN);
    }

    @Test
    void doesNothingWhenPasswordBlankOrNull() {
        new AdminBootstrap(auth, users, "admin", "a@x.com", "").run();
        new AdminBootstrap(auth, users, "admin", "a@x.com", "  ").run();
        new AdminBootstrap(auth, users, "admin", "a@x.com", null).run();
        verifyNoInteractions(auth);
    }

    @Test
    void doesNothingWhenAdminAlreadyExists() {
        when(users.existsByUsername("admin")).thenReturn(true);
        new AdminBootstrap(auth, users, "admin", "a@x.com", "secret").run();
        verify(auth, org.mockito.Mockito.never()).createUser(any(), any(), any(), any());
    }
}
