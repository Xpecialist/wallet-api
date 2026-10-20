package com.example.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.wallet.TestData;
import com.example.wallet.dto.PageResponse;
import com.example.wallet.dto.TransactionFilter;
import com.example.wallet.dto.TransactionResponse;
import com.example.wallet.dto.TransferRequest;
import com.example.wallet.entity.Role;
import com.example.wallet.entity.Transaction;
import com.example.wallet.entity.TransactionStatus;
import com.example.wallet.entity.TransactionType;
import com.example.wallet.entity.Wallet;
import com.example.wallet.exception.ApiException;
import com.example.wallet.repository.TransactionRepository;
import com.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionServiceTest {
    @Mock TransactionRepository transactions;
    @Mock WalletRepository wallets;
    TransactionService service;

    Wallet alice;
    Wallet bob;

    @BeforeEach
    void setUp() {
        service = new TransactionService(transactions, wallets);
        alice = TestData.wallet(1, TestData.user(10, "alice", Role.USER), "100.0000", "USD");
        bob = TestData.wallet(2, TestData.user(20, "bob", Role.USER), "50.0000", "USD");
        when(wallets.findByUserUsername("alice")).thenReturn(Optional.of(alice));
        when(wallets.findByUserUsername("bob")).thenReturn(Optional.of(bob));
        when(wallets.findByIdForUpdate(1L)).thenReturn(Optional.of(alice));
        when(wallets.findByIdForUpdate(2L)).thenReturn(Optional.of(bob));
        when(transactions.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));
        when(transactions.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    }

    private static TransferRequest toUser(String user, String amount) {
        return new TransferRequest(user, null, new BigDecimal(amount), null);
    }

    private static void assertStatus(Throwable t, HttpStatus status) {
        assertThat(t).isInstanceOf(ApiException.class);
        assertThat(((ApiException) t).getStatus()).isEqualTo(status);
    }

    // ---- transfer ----

    @Test
    void transferMovesFundsAndRecordsCompletedTransfer() {
        TransactionResponse r = service.transfer("alice", new TransferRequest("bob", null, new BigDecimal("30"), "lunch"), null);

        assertThat(alice.getBalance()).isEqualByComparingTo("70");
        assertThat(bob.getBalance()).isEqualByComparingTo("80");
        assertThat(r.type()).isEqualTo("TRANSFER");
        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.senderWalletId()).isEqualTo(1L);
        assertThat(r.receiverWalletId()).isEqualTo(2L);
        assertThat(r.amount()).isEqualByComparingTo("30");
        assertThat(r.note()).isEqualTo("lunch");
        assertThat(r.idempotencyKey()).isNull();
    }

    @Test
    void transferByReceiverWalletIdTakesPrecedenceOverUsername() {
        service.transfer("alice", new TransferRequest("nobody", 2L, new BigDecimal("10"), null), null);
        assertThat(bob.getBalance()).isEqualByComparingTo("60");
        verify(wallets, never()).findByUserUsername("nobody");
    }

    @Test
    void transferOfExactBalanceIsAllowed() {
        service.transfer("alice", toUser("bob", "100"), null);
        assertThat(alice.getBalance()).isEqualByComparingTo("0");
        assertThat(bob.getBalance()).isEqualByComparingTo("150");
    }

    @Test
    void transferWithInsufficientFundsIs422AndLeavesBalancesUnchanged() {
        assertThatThrownBy(() -> service.transfer("alice", toUser("bob", "100.0001"), null))
                .satisfies(t -> assertStatus(t, HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        assertThat(bob.getBalance()).isEqualByComparingTo("50");
        verify(transactions, never()).save(any());
    }

    @Test
    void transferFromUnknownSenderIs404() {
        when(wallets.findByUserUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transfer("ghost", toUser("bob", "1"), null))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    void transferToUnknownReceiverUsernameIs404() {
        when(wallets.findByUserUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transfer("alice", toUser("ghost", "1"), null))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
    }

    @Test
    void transferToUnknownReceiverWalletIdIs404() {
        when(wallets.findByIdForUpdate(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transfer("alice", new TransferRequest(null, 99L, BigDecimal.ONE, null), null))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        verify(transactions, never()).save(any());
    }

    @Test
    void transferWithoutReceiverIs400() {
        assertThatThrownBy(() -> service.transfer("alice", new TransferRequest(null, null, BigDecimal.ONE, null), null))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.transfer("alice", new TransferRequest("  ", null, BigDecimal.ONE, null), null))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    @Test
    void transferToSelfIs400ByUsernameAndById() {
        assertThatThrownBy(() -> service.transfer("alice", toUser("alice", "1"), null))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.transfer("alice", new TransferRequest(null, 1L, BigDecimal.ONE, null), null))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
    }

    @Test
    void transferBetweenDifferentCurrenciesIs400() {
        bob.setCurrency("EUR");
        assertThatThrownBy(() -> service.transfer("alice", toUser("bob", "1"), null))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        assertThat(bob.getBalance()).isEqualByComparingTo("50");
    }

    @Test
    void transferLocksWalletsInAscendingIdOrderRegardlessOfDirection() {
        // sender id 1 -> receiver id 2
        service.transfer("alice", toUser("bob", "1"), null);
        InOrder o1 = Mockito.inOrder(wallets);
        o1.verify(wallets).findByIdForUpdate(1L);
        o1.verify(wallets).findByIdForUpdate(2L);

        Mockito.clearInvocations(wallets);
        // sender id 2 -> receiver id 1
        service.transfer("bob", toUser("alice", "1"), null);
        InOrder o2 = Mockito.inOrder(wallets);
        o2.verify(wallets).findByIdForUpdate(1L);
        o2.verify(wallets).findByIdForUpdate(2L);
        // balances net out correctly when sender has the larger id
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        assertThat(bob.getBalance()).isEqualByComparingTo("50");
    }

    @Test
    void transferWithRepeatedIdempotencyKeyFromSameSenderReturnsExistingWithoutMovingMoney() {
        Transaction existing = new Transaction();
        existing.setSenderWalletId(1L);
        existing.setReceiverWalletId(2L);
        existing.setAmount(new BigDecimal("5"));
        existing.setType(TransactionType.TRANSFER);
        existing.setStatus(TransactionStatus.COMPLETED);
        existing.setIdempotencyKey("k1");
        when(transactions.findByIdempotencyKey("k1")).thenReturn(Optional.of(existing));

        TransactionResponse r = service.transfer("alice", toUser("bob", "40"), "k1");

        assertThat(r.amount()).isEqualByComparingTo("5");
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        verify(transactions, never()).save(any());
    }

    @Test
    void transferWithIdempotencyKeyOfAnotherSenderIs409() {
        Transaction existing = new Transaction();
        existing.setSenderWalletId(2L);
        existing.setType(TransactionType.TRANSFER);
        existing.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByIdempotencyKey("k1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.transfer("alice", toUser("bob", "1"), "k1"))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
    }

    @Test
    void transferStoresNewIdempotencyKeyAndTreatsBlankAsNone() {
        TransactionResponse withKey = service.transfer("alice", toUser("bob", "1"), "abc");
        assertThat(withKey.idempotencyKey()).isEqualTo("abc");

        TransactionResponse blank = service.transfer("alice", toUser("bob", "1"), "   ");
        assertThat(blank.idempotencyKey()).isNull();
        verify(transactions, never()).findByIdempotencyKey("   ");
    }

    // ---- deposit ----

    @Test
    void depositCreditsWalletAndRecordsDeposit() {
        TransactionResponse r = service.deposit(1L, new BigDecimal("25.5"), null);
        assertThat(alice.getBalance()).isEqualByComparingTo("125.5");
        assertThat(r.type()).isEqualTo("DEPOSIT");
        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.receiverWalletId()).isEqualTo(1L);
        assertThat(r.senderWalletId()).isNull();
    }

    @Test
    void depositToUnknownWalletIs404() {
        when(wallets.findByIdForUpdate(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deposit(99L, BigDecimal.ONE, null))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        verify(transactions, never()).save(any());
    }

    @Test
    void depositWithRepeatedKeyForSameWalletReturnsExistingAndDoesNotCredit() {
        Transaction existing = new Transaction();
        existing.setReceiverWalletId(1L);
        existing.setAmount(new BigDecimal("7"));
        existing.setType(TransactionType.DEPOSIT);
        existing.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByIdempotencyKey("d1")).thenReturn(Optional.of(existing));

        service.deposit(1L, new BigDecimal("50"), "d1");
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        verify(transactions, never()).save(any());
    }

    @Test
    void depositWithKeyUsedByDifferentWalletOrTypeIs409() {
        Transaction otherWallet = new Transaction();
        otherWallet.setReceiverWalletId(2L);
        otherWallet.setType(TransactionType.DEPOSIT);
        otherWallet.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByIdempotencyKey("d1")).thenReturn(Optional.of(otherWallet));
        assertThatThrownBy(() -> service.deposit(1L, BigDecimal.ONE, "d1"))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));

        Transaction wrongType = new Transaction();
        wrongType.setReceiverWalletId(1L);
        wrongType.setType(TransactionType.TRANSFER);
        wrongType.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByIdempotencyKey("d2")).thenReturn(Optional.of(wrongType));
        assertThatThrownBy(() -> service.deposit(1L, BigDecimal.ONE, "d2"))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
    }

    @Test
    void depositToOwnResolvesWalletOfUser() {
        service.depositToOwn("bob", new BigDecimal("10"), null);
        assertThat(bob.getBalance()).isEqualByComparingTo("60");
    }

    @Test
    void depositToOwnForUserWithoutWalletIs404() {
        when(wallets.findByUserUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.depositToOwn("ghost", BigDecimal.ONE, null))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    // ---- withdraw ----

    @Test
    void withdrawDebitsWalletAndRecordsWithdrawal() {
        TransactionResponse r = service.withdraw("alice", new BigDecimal("40"), null);
        assertThat(alice.getBalance()).isEqualByComparingTo("60");
        assertThat(r.type()).isEqualTo("WITHDRAWAL");
        assertThat(r.senderWalletId()).isEqualTo(1L);
        assertThat(r.receiverWalletId()).isNull();
    }

    @Test
    void withdrawEntireBalanceIsAllowedButMoreIs422() {
        service.withdraw("alice", new BigDecimal("100"), null);
        assertThat(alice.getBalance()).isEqualByComparingTo("0");

        assertThatThrownBy(() -> service.withdraw("alice", new BigDecimal("0.0001"), null))
                .satisfies(t -> assertStatus(t, HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(alice.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void withdrawForUnknownUserIs404() {
        when(wallets.findByUserUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.withdraw("ghost", BigDecimal.ONE, null))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    void withdrawWithRepeatedKeyReturnsExistingAndDoesNotDebit() {
        Transaction existing = new Transaction();
        existing.setSenderWalletId(1L);
        existing.setAmount(new BigDecimal("3"));
        existing.setType(TransactionType.WITHDRAWAL);
        existing.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByIdempotencyKey("w1")).thenReturn(Optional.of(existing));

        service.withdraw("alice", new BigDecimal("50"), "w1");
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        verify(transactions, never()).save(any());
    }

    @Test
    void withdrawWithKeyUsedByOtherOperationIs409() {
        Transaction existing = new Transaction();
        existing.setSenderWalletId(1L);
        existing.setType(TransactionType.TRANSFER);
        existing.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByIdempotencyKey("w1")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.withdraw("alice", BigDecimal.ONE, "w1"))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
    }

    // ---- history / getById / listAll ----

    @Test
    void historyForUserWithoutWalletIs404() {
        when(wallets.findByUserUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.history("ghost", emptyFilter(), PageRequest.of(0, 10)))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    @SuppressWarnings("unchecked")
    void historyMapsPageMetadataAndPassesPageable() {
        Transaction t = new Transaction();
        t.setSenderWalletId(1L);
        t.setAmount(BigDecimal.TEN);
        t.setType(TransactionType.WITHDRAWAL);
        t.setStatus(TransactionStatus.COMPLETED);
        Pageable pageable = PageRequest.of(2, 5);
        Page<Transaction> page = new PageImpl<>(List.of(t), pageable, 11);
        when(transactions.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        PageResponse<TransactionResponse> r = service.history("alice", emptyFilter(), pageable);

        assertThat(r.content()).hasSize(1);
        assertThat(r.page()).isEqualTo(2);
        assertThat(r.size()).isEqualTo(5);
        assertThat(r.totalElements()).isEqualTo(11);
        assertThat(r.totalPages()).isEqualTo(3);
        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        verify(transactions).findAll(any(Specification.class), cap.capture());
        assertThat(cap.getValue()).isEqualTo(pageable);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listAllReturnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 20);
        when(transactions.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<Transaction>(List.of(), pageable, 0));
        PageResponse<TransactionResponse> r = service.listAll(emptyFilter(), pageable);
        assertThat(r.content()).isEmpty();
        assertThat(r.totalElements()).isZero();
    }

    private static TransactionFilter emptyFilter() {
        return new TransactionFilter(null, null, null, null, null, null);
    }

    private static Authentication auth(String name, String... roles) {
        return new TestingAuthenticationToken(name, "pw", roles);
    }

    private Transaction between(long sender, long receiver) {
        Transaction t = new Transaction();
        t.setSenderWalletId(sender);
        t.setReceiverWalletId(receiver);
        t.setAmount(BigDecimal.ONE);
        t.setType(TransactionType.TRANSFER);
        t.setStatus(TransactionStatus.COMPLETED);
        return t;
    }

    @Test
    void getByIdAllowsSenderAndReceiver() {
        when(transactions.findById(5L)).thenReturn(Optional.of(between(1, 2)));
        assertThat(service.getById(5L, auth("alice", "ROLE_USER"))).isNotNull();
        assertThat(service.getById(5L, auth("bob", "ROLE_USER"))).isNotNull();
    }

    @Test
    void getByIdForbiddenForNonParticipant() {
        Wallet carol = TestData.wallet(3, TestData.user(30, "carol", Role.USER), "0", "USD");
        when(wallets.findByUserUsername("carol")).thenReturn(Optional.of(carol));
        when(transactions.findById(5L)).thenReturn(Optional.of(between(1, 2)));
        assertThatThrownBy(() -> service.getById(5L, auth("carol", "ROLE_USER")))
                .satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN));
    }

    @Test
    void getByIdAllowsAdminWithoutWalletLookup() {
        when(transactions.findById(5L)).thenReturn(Optional.of(between(1, 2)));
        assertThat(service.getById(5L, auth("root", "ROLE_ADMIN"))).isNotNull();
        verify(wallets, never()).findByUserUsername("root");
    }

    @Test
    void getByIdUnknownTransactionIs404() {
        when(transactions.findById(anyLong())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(5L, auth("alice", "ROLE_USER")))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }
}
