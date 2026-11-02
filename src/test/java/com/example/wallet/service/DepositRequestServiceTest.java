package com.example.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.wallet.TestData;
import com.example.wallet.dto.AdminDepositRequestResponse;
import com.example.wallet.dto.CreateDepositRequest;
import com.example.wallet.dto.DepositRequestResponse;
import com.example.wallet.entity.DepositRequest;
import com.example.wallet.entity.DepositRequestStatus;
import com.example.wallet.entity.Role;
import com.example.wallet.entity.Transaction;
import com.example.wallet.entity.TransactionStatus;
import com.example.wallet.entity.TransactionType;
import com.example.wallet.entity.User;
import com.example.wallet.entity.Wallet;
import com.example.wallet.exception.ApiException;
import com.example.wallet.repository.DepositRequestRepository;
import com.example.wallet.repository.TransactionRepository;
import com.example.wallet.repository.UserRepository;
import com.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DepositRequestServiceTest {
    @Mock DepositRequestRepository requests;
    @Mock TransactionRepository transactions;
    @Mock WalletRepository wallets;
    @Mock UserRepository users;
    DepositRequestService service;

    User aliceUser;
    User adminUser;
    Wallet alice;

    @BeforeEach
    void setUp() {
        service = new DepositRequestService(requests, transactions, wallets, users, 2);
        aliceUser = TestData.user(10, "alice", Role.USER);
        adminUser = TestData.user(99, "root", Role.ADMIN);
        alice = TestData.wallet(1, aliceUser, "100.0000", "USD");
        when(wallets.findByUserUsername("alice")).thenReturn(Optional.of(alice));
        when(wallets.findByIdForUpdate(1L)).thenReturn(Optional.of(alice));
        when(users.findByUsername("root")).thenReturn(Optional.of(adminUser));
        when(requests.save(any(DepositRequest.class))).thenAnswer(i -> i.getArgument(0));
        when(transactions.save(any(Transaction.class))).thenAnswer(i -> {
            Transaction t = i.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 500L);
            return t;
        });
        when(requests.findByWalletIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
    }

    private static void assertStatus(Throwable t, HttpStatus status) {
        assertThat(t).isInstanceOf(ApiException.class);
        assertThat(((ApiException) t).getStatus()).isEqualTo(status);
    }

    private DepositRequest pending(long id, String amount) {
        DepositRequest d = new DepositRequest();
        ReflectionTestUtils.setField(d, "id", id);
        d.setWalletId(1L);
        d.setUser(aliceUser);
        d.setAmount(new BigDecimal(amount));
        d.setNote("ref-1");
        when(requests.findByIdForUpdate(id)).thenReturn(Optional.of(d));
        return d;
    }

    // ---- create ----

    @Test
    void createStoresPendingRequestWithoutMovingMoney() {
        DepositRequestResponse r = service.create("alice", new CreateDepositRequest(new BigDecimal("25"), "  ref  "), null);

        assertThat(r.status()).isEqualTo("PENDING");
        assertThat(r.note()).isEqualTo("ref");
        assertThat(r.walletId()).isEqualTo(1L);
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        verify(transactions, never()).save(any());
    }

    @Test
    void createLocksTheWalletBeforeCountingPending() {
        service.create("alice", new CreateDepositRequest(new BigDecimal("1"), null), null);
        var order = inOrder(wallets, requests);
        order.verify(wallets).findByIdForUpdate(1L);
        order.verify(requests).countByWalletIdAndStatus(1L, DepositRequestStatus.PENDING);
    }

    @Test
    void createRejectsWhenPendingCapReached() {
        when(requests.countByWalletIdAndStatus(1L, DepositRequestStatus.PENDING)).thenReturn(2L);
        assertThatThrownBy(() -> service.create("alice", new CreateDepositRequest(BigDecimal.ONE, null), null))
                .satisfies(t -> {
                    assertStatus(t, HttpStatus.CONFLICT);
                    assertThat(t.getMessage()).contains("Too many pending");
                });
        verify(requests, never()).save(any());
    }

    @Test
    void createReplayWithSameKeyReturnsOriginalEvenAtCap() {
        DepositRequest old = pending(7, "25");
        old.setIdempotencyKey("k");
        ReflectionTestUtils.setField(old, "note", null);
        when(requests.findByWalletIdAndIdempotencyKey(1L, "k")).thenReturn(Optional.of(old));
        when(requests.countByWalletIdAndStatus(1L, DepositRequestStatus.PENDING)).thenReturn(2L);

        DepositRequestResponse r = service.create("alice", new CreateDepositRequest(new BigDecimal("25.0000"), null), "k");

        assertThat(r.id()).isEqualTo(7L);
        verify(requests, never()).save(any());
    }

    @Test
    void createKeyReuseWithDifferentAmountOrNoteIs409() {
        DepositRequest old = pending(7, "25");
        ReflectionTestUtils.setField(old, "note", null);
        when(requests.findByWalletIdAndIdempotencyKey(1L, "k")).thenReturn(Optional.of(old));

        assertThatThrownBy(() -> service.create("alice", new CreateDepositRequest(new BigDecimal("26"), null), "k"))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.create("alice", new CreateDepositRequest(new BigDecimal("25"), "x"), "k"))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
    }

    @Test
    void createRejectsBlankAndOverlongKeys() {
        assertThatThrownBy(() -> service.create("alice", new CreateDepositRequest(BigDecimal.ONE, null), " "))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.create("alice", new CreateDepositRequest(BigDecimal.ONE, null), "k".repeat(101)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        service.create("alice", new CreateDepositRequest(BigDecimal.ONE, null), "k".repeat(100));
    }

    // ---- get / list ----

    @Test
    void getOwnOfAnotherWalletIs404() {
        DepositRequest d = pending(7, "5");
        d.setWalletId(2L);
        when(requests.findById(7L)).thenReturn(Optional.of(d));
        assertThatThrownBy(() -> service.getOwn("alice", 7L)).satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.getOwn("alice", 8L)).satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    void listRejectsSortOutsideTheWhitelist() {
        var bad = PageRequest.of(0, 20, Sort.by("idempotencyKey"));
        assertThatThrownBy(() -> service.listOwn("alice", null, bad)).satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.listAll(null, null, null, bad)).satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    @Test
    void adminUsernameFilterIsBoundedTo50Characters() {
        var page = PageRequest.of(0, 20);
        assertThatThrownBy(() -> service.listAll(null, null, "a".repeat(51), page))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.listAll(null, null, " ".repeat(10_000), page))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    // ---- approve ----

    @Test
    void approveCreditsWalletOnceAndLinksCompletedDeposit() {
        DepositRequest d = pending(7, "25.5");

        AdminDepositRequestResponse r = service.approve("root", 7L, " ok ");

        assertThat(alice.getBalance()).isEqualByComparingTo("125.5");
        ArgumentCaptor<Transaction> tx = ArgumentCaptor.forClass(Transaction.class);
        verify(transactions).save(tx.capture());
        assertThat(tx.getValue().getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(tx.getValue().getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(tx.getValue().getSenderWalletId()).isNull();
        assertThat(tx.getValue().getReceiverWalletId()).isEqualTo(1L);
        assertThat(tx.getValue().getNote()).isEqualTo("Deposit request #7: ref-1");
        assertThat(r.status()).isEqualTo("APPROVED");
        assertThat(r.transactionId()).isEqualTo(500L);
        assertThat(r.decidedBy()).isEqualTo(99L);
        assertThat(r.decisionNote()).isEqualTo("ok");
        assertThat(d.getDecidedAt()).isNotNull();
    }

    @Test
    void approveLocksRequestBeforeWallet() {
        pending(7, "5");
        service.approve("root", 7L, null);
        var order = inOrder(requests, wallets);
        order.verify(requests).findByIdForUpdate(7L);
        order.verify(wallets).findByIdForUpdate(1L);
    }

    @Test
    void approveNonPendingIs409AndMovesNoMoney() {
        for (DepositRequestStatus s : new DepositRequestStatus[] {DepositRequestStatus.APPROVED, DepositRequestStatus.REJECTED}) {
            DepositRequest d = pending(7, "5");
            d.setStatus(s);
            assertThatThrownBy(() -> service.approve("root", 7L, null)).satisfies(t -> {
                assertStatus(t, HttpStatus.CONFLICT);
                assertThat(t.getMessage()).isEqualTo("Deposit request already " + s);
            });
        }
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        verify(transactions, never()).save(any());
    }

    @Test
    void approveOfDisabledUserIs409ButRejectWorks() {
        pending(7, "5");
        aliceUser.setEnabled(false);
        assertThatThrownBy(() -> service.approve("root", 7L, null)).satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        verify(transactions, never()).save(any());

        assertThat(service.reject("root", 7L, "disabled").status()).isEqualTo("REJECTED");
    }

    @Test
    void approveMissingIs404() {
        when(requests.findByIdForUpdate(8L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve("root", 8L, null)).satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    // ---- reject ----

    @Test
    void rejectOnlyChangesStatus() {
        pending(7, "5");
        AdminDepositRequestResponse r = service.reject("root", 7L, "no funds received");
        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.decisionNote()).isEqualTo("no funds received");
        assertThat(r.transactionId()).isNull();
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        verify(transactions, never()).save(any());
        verify(wallets, never()).findByIdForUpdate(any());
    }

    @Test
    void rejectRequiresNoteAndPendingStatus() {
        DepositRequest d = pending(7, "5");
        assertThatThrownBy(() -> service.reject("root", 7L, "  ")).satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.reject("root", 7L, null)).satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        d.setStatus(DepositRequestStatus.APPROVED);
        assertThatThrownBy(() -> service.reject("root", 7L, "late")).satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
    }
}
