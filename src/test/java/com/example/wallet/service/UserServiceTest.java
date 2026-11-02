package com.example.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.wallet.TestData;
import com.example.wallet.dto.AuthResponse;
import com.example.wallet.dto.ChangePasswordRequest;
import com.example.wallet.dto.PageResponse;
import com.example.wallet.dto.UpdateProfileRequest;
import com.example.wallet.dto.UserResponse;
import com.example.wallet.entity.Role;
import com.example.wallet.entity.User;
import com.example.wallet.exception.ApiException;
import com.example.wallet.repository.UserRepository;
import com.example.wallet.security.JwtService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {
    @Mock UserRepository users;
    @Mock PasswordEncoder encoder;
    @Mock JwtService jwt;
    UserService service;
    User alice;

    @BeforeEach
    void setUp() {
        service = new UserService(users, encoder, jwt);
        alice = TestData.user(1, "alice", Role.USER);
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        // the targeted UPDATEs, simulated on the entity so the reloaded user shows what the database would hold
        when(users.findById(1L)).thenReturn(Optional.of(alice));
        when(users.updateEmail(eq(1L), anyString())).thenAnswer(i -> {
            alice.setEmail(i.getArgument(1));
            return 1;
        });
        when(users.changePasswordIfCurrent(eq(1L), anyLong(), anyString())).thenAnswer(i -> {
            alice.setPasswordHash(i.getArgument(2));
            return 1;
        });
        when(users.enable(1L)).thenAnswer(i -> {
            alice.setEnabled(true);
            return 1;
        });
        when(users.disableAndRevoke(1L)).thenAnswer(i -> {
            alice.setEnabled(false);
            return 1;
        });
    }

    private static void assertStatus(Throwable t, HttpStatus s) {
        assertThat(t).isInstanceOf(ApiException.class);
        assertThat(((ApiException) t).getStatus()).isEqualTo(s);
    }

    @Test
    void getProfileReturnsUser() {
        UserResponse r = service.getProfile("alice");
        assertThat(r.username()).isEqualTo("alice");
        assertThat(r.role()).isEqualTo("USER");
        assertThat(r.enabled()).isTrue();
    }

    @Test
    void getProfileUnknownUserIs404() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProfile("ghost")).satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    void updateEmailChangesEmail() {
        when(users.existsByEmail("new@example.com")).thenReturn(false);
        UserResponse r = service.updateEmail("alice", new UpdateProfileRequest("new@example.com"));
        assertThat(r.email()).isEqualTo("new@example.com");
        assertThat(alice.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void updateEmailTakenByAnotherUserIs409() {
        when(users.existsByEmail("taken@example.com")).thenReturn(true);
        assertThatThrownBy(() -> service.updateEmail("alice", new UpdateProfileRequest("taken@example.com")))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
        assertThat(alice.getEmail()).isEqualTo("alice@example.com");
        verify(users, never()).save(any());
        verify(users, never()).changePasswordIfCurrent(any(), anyLong(), any());
        verify(users, never()).updateEmail(any(), any());
    }

    @Test
    void updateEmailToOwnCurrentEmailIgnoringCaseIsAllowed() {
        when(users.existsByEmail(any())).thenReturn(true);
        UserResponse r = service.updateEmail("alice", new UpdateProfileRequest("ALICE@example.com"));
        assertThat(r.email()).isEqualTo("ALICE@example.com");
    }

    @Test
    void updateEmailUnknownUserIs404() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateEmail("ghost", new UpdateProfileRequest("a@b.co")))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    void changePasswordHashesNewPasswordWhenCurrentMatches() {
        when(encoder.matches("old", "hash-alice")).thenReturn(true);
        when(encoder.encode("newpassword")).thenReturn("new-hash");
        service.changePassword("alice", 0L, new ChangePasswordRequest("old", "newpassword"));
        assertThat(alice.getPasswordHash()).isEqualTo("new-hash");
        verify(users).changePasswordIfCurrent(1L, 0L, "new-hash");
        verify(users, never()).save(any());
    }

    @Test
    void changePasswordWithWrongCurrentIs400AndKeepsHash() {
        when(encoder.matches("bad", "hash-alice")).thenReturn(false);
        assertThatThrownBy(() -> service.changePassword("alice", 0L, new ChangePasswordRequest("bad", "newpassword")))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThat(alice.getPasswordHash()).isEqualTo("hash-alice");
        verify(users, never()).save(any());
        verify(users, never()).changePasswordIfCurrent(any(), anyLong(), any());
        verify(users, never()).updateEmail(any(), any());
    }

    @Test
    void changePasswordUnknownUserIs404() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changePassword("ghost", 0L, new ChangePasswordRequest("a", "bbbbbbbb")))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    void listAllMapsPage() {
        var pageable = PageRequest.of(1, 2);
        when(users.findAll(pageable)).thenReturn(new PageImpl<>(List.of(alice), pageable, 3));
        PageResponse<UserResponse> r = service.listAll(pageable);
        assertThat(r.content()).hasSize(1);
        assertThat(r.page()).isEqualTo(1);
        assertThat(r.size()).isEqualTo(2);
        assertThat(r.totalElements()).isEqualTo(3);
        assertThat(r.totalPages()).isEqualTo(2);
    }

    @Test
    void setEnabledDisablesAndEnablesOtherUser() {
        when(users.findById(1L)).thenReturn(Optional.of(alice));
        assertThat(service.setEnabled(1L, false, "admin").enabled()).isFalse();
        assertThat(service.setEnabled(1L, true, "admin").enabled()).isTrue();
    }

    @Test
    void setEnabledUnknownUserIs404() {
        when(users.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setEnabled(99L, false, "admin"))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    void adminCannotDisableSelfButCanEnableSelf() {
        User admin = TestData.user(2, "admin", Role.ADMIN);
        when(users.findById(2L)).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> service.setEnabled(2L, false, "admin"))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThat(admin.isEnabled()).isTrue();
        assertThat(service.setEnabled(2L, true, "admin").enabled()).isTrue();
    }

    @Test
    void changePasswordRejectsNewPasswordEqualToCurrent() {
        when(encoder.matches("samepassword", "hash-alice")).thenReturn(true);
        assertThatThrownBy(() -> service.changePassword("alice", 0L,
                new ChangePasswordRequest("samepassword", "samepassword")))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThat(alice.getPasswordHash()).isEqualTo("hash-alice");
        verify(users, never()).save(any());
        verify(users, never()).changePasswordIfCurrent(any(), anyLong(), any());
        verify(users, never()).updateEmail(any(), any());
    }

    @Test
    void updateEmailTrimsBeforeCheckingAndStoring() {
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(users.existsByEmail("new@example.com")).thenReturn(false);
        when(users.save(alice)).thenReturn(alice);
        service.updateEmail("alice", new UpdateProfileRequest("  new@example.com "));
        assertThat(alice.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void listAllRejectsSortPropertiesOutsideTheWhitelist() {
        for (String bad : new String[] {"passwordHash", "wallet", "doesNotExist", "password_hash"}) {
            assertThatThrownBy(() -> service.listAll(PageRequest.of(0, 10, org.springframework.data.domain.Sort.by(bad))))
                    .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        }
        verify(users, never()).findAll(any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void listAllAllowsEveryWhitelistedSortProperty() {
        for (String ok : new String[] {"id", "username", "email", "role", "enabled", "createdAt"}) {
            var pageable = PageRequest.of(0, 10, org.springframework.data.domain.Sort.by(ok));
            when(users.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));
            assertThat(service.listAll(pageable).content()).isEmpty();
        }
    }

    @Test
    void changePasswordIsOneConditionalUpdateThenIssuesTheTokenForTheNextVersion() {
        when(encoder.matches("old", "hash-alice")).thenReturn(true);
        when(encoder.encode("newpassword")).thenReturn("new-hash");
        when(jwt.generateToken("alice", 1L)).thenReturn("fresh");

        AuthResponse r = service.changePassword("alice", 0L, new ChangePasswordRequest("old", "newpassword"));

        assertThat(r.token()).isEqualTo("fresh");
        var order = org.mockito.Mockito.inOrder(users, jwt);
        order.verify(users).changePasswordIfCurrent(1L, 0L, "new-hash");
        order.verify(jwt).generateToken("alice", 1L);
        verify(users, never()).bumpTokenVersion(any());
        verify(users, never()).save(any());
    }

    @Test
    void aTokenThatIsNoLongerCurrentCannotChangeThePasswordOrGetAFreshToken() {
        when(encoder.matches("old", "hash-alice")).thenReturn(true);
        // the version moved on after the request was authenticated (a logout, another change)
        assertThatThrownBy(() -> service.changePassword("alice", 7L, new ChangePasswordRequest("old", "newpassword")))
                .satisfies(t -> assertStatus(t, HttpStatus.UNAUTHORIZED));
        verify(encoder, never()).matches(any(), any());
        verify(users, never()).changePasswordIfCurrent(any(), anyLong(), any());
        verify(jwt, never()).generateToken(any(), anyLong());
    }

    @Test
    void aDisabledAccountCannotChangeThePassword() {
        alice.setEnabled(false);
        assertThatThrownBy(() -> service.changePassword("alice", 0L, new ChangePasswordRequest("old", "newpassword")))
                .satisfies(t -> assertStatus(t, HttpStatus.UNAUTHORIZED));
        verify(users, never()).changePasswordIfCurrent(any(), anyLong(), any());
        verify(jwt, never()).generateToken(any(), anyLong());
    }

    @Test
    void whenTheConditionalUpdateMatchesNoRowTheChangeFailsWith401AndNoToken() {
        when(encoder.matches("old", "hash-alice")).thenReturn(true);
        when(encoder.encode("newpassword")).thenReturn("new-hash");
        org.mockito.Mockito.doReturn(0).when(users).changePasswordIfCurrent(1L, 0L, "new-hash"); // lost the race at the database
        assertThatThrownBy(() -> service.changePassword("alice", 0L, new ChangePasswordRequest("old", "newpassword")))
                .satisfies(t -> assertStatus(t, HttpStatus.UNAUTHORIZED));
        verify(jwt, never()).generateToken(any(), anyLong());
    }

    @Test
    void failedPasswordChangesNeverTouchTheRowOrIssueATokenOrBump() {
        when(encoder.matches("bad", "hash-alice")).thenReturn(false);
        when(encoder.matches("same", "hash-alice")).thenReturn(true);
        assertThatThrownBy(() -> service.changePassword("alice", 0L, new ChangePasswordRequest("bad", "newpassword")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.changePassword("alice", 0L, new ChangePasswordRequest("same", "same")))
                .isInstanceOf(ApiException.class);
        verify(users, never()).bumpTokenVersion(any());
        verify(users, never()).changePasswordIfCurrent(any(), anyLong(), any());
        verify(jwt, never()).generateToken(any(), anyLong());
    }

    @Test
    void disablingRevokesInOneStatementAndEnablingAndSelfDisableDoNot() {
        when(users.findById(1L)).thenReturn(Optional.of(alice));
        service.setEnabled(1L, true, "admin");
        verify(users).enable(1L);
        verify(users, never()).disableAndRevoke(any());

        service.setEnabled(1L, false, "admin");
        verify(users).disableAndRevoke(1L);

        User admin = TestData.user(2, "admin", Role.ADMIN);
        when(users.findById(2L)).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> service.setEnabled(2L, false, "admin")).isInstanceOf(ApiException.class);
        verify(users, never()).disableAndRevoke(2L);
        verify(users, never()).save(any());
    }

    @Test
    void emailChangeWritesOnlyTheEmailColumn() {
        when(users.existsByEmail("new@example.com")).thenReturn(false);
        service.updateEmail("alice", new UpdateProfileRequest("new@example.com"));
        verify(users).updateEmail(1L, "new@example.com");
        verify(users, never()).save(any());
    }
}
