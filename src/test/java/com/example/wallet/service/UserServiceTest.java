package com.example.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.wallet.TestData;
import com.example.wallet.dto.ChangePasswordRequest;
import com.example.wallet.dto.PageResponse;
import com.example.wallet.dto.UpdateProfileRequest;
import com.example.wallet.dto.UserResponse;
import com.example.wallet.entity.Role;
import com.example.wallet.entity.User;
import com.example.wallet.exception.ApiException;
import com.example.wallet.repository.UserRepository;
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
    UserService service;
    User alice;

    @BeforeEach
    void setUp() {
        service = new UserService(users, encoder);
        alice = TestData.user(1, "alice", Role.USER);
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
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
        service.changePassword("alice", new ChangePasswordRequest("old", "newpassword"));
        assertThat(alice.getPasswordHash()).isEqualTo("new-hash");
        verify(users).save(alice);
    }

    @Test
    void changePasswordWithWrongCurrentIs400AndKeepsHash() {
        when(encoder.matches("bad", "hash-alice")).thenReturn(false);
        assertThatThrownBy(() -> service.changePassword("alice", new ChangePasswordRequest("bad", "newpassword")))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThat(alice.getPasswordHash()).isEqualTo("hash-alice");
        verify(users, never()).save(any());
    }

    @Test
    void changePasswordUnknownUserIs404() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changePassword("ghost", new ChangePasswordRequest("a", "bbbbbbbb")))
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
}
