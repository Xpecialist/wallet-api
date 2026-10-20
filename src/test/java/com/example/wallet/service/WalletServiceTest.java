package com.example.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.wallet.TestData;
import com.example.wallet.dto.WalletResponse;
import com.example.wallet.entity.Role;
import com.example.wallet.entity.Wallet;
import com.example.wallet.exception.ApiException;
import com.example.wallet.repository.WalletRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;

class WalletServiceTest {
    WalletRepository wallets = mock(WalletRepository.class);
    WalletService service = new WalletService(wallets);
    Wallet aliceWallet;

    @BeforeEach
    void setUp() {
        aliceWallet = TestData.wallet(1, TestData.user(10, "alice", Role.USER), "12.3400", "USD");
        when(wallets.findByUserUsername("alice")).thenReturn(Optional.of(aliceWallet));
        when(wallets.findById(1L)).thenReturn(Optional.of(aliceWallet));
    }

    @Test
    void getOwnReturnsWallet() {
        WalletResponse r = service.getOwn("alice");
        assertThat(r.id()).isEqualTo(1L);
        assertThat(r.userId()).isEqualTo(10L);
        assertThat(r.username()).isEqualTo("alice");
        assertThat(r.balance()).isEqualByComparingTo("12.34");
        assertThat(r.currency()).isEqualTo("USD");
    }

    @Test
    void getOwnUnknownUserIs404() {
        when(wallets.findByUserUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getOwn("ghost"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getByIdAllowsOwnerAndAdminButNotOthers() {
        assertThat(service.getById(1L, new TestingAuthenticationToken("alice", "x", "ROLE_USER"))).isNotNull();
        assertThat(service.getById(1L, new TestingAuthenticationToken("root", "x", "ROLE_ADMIN"))).isNotNull();
        assertThatThrownBy(() -> service.getById(1L, new TestingAuthenticationToken("bob", "x", "ROLE_USER")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getByIdUnknownWalletIs404() {
        when(wallets.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(99L, new TestingAuthenticationToken("alice", "x", "ROLE_USER")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
