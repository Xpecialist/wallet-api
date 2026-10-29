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
        when(transactions.findByOwnerAndKey(anyLong(), any())).thenReturn(Optional.empty());
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

    private Transaction existingTransfer(long sender, long receiver, String amount, String note) {
        Transaction existing = new Transaction();
        existing.setSenderWalletId(sender);
        existing.setReceiverWalletId(receiver);
        existing.setAmount(new BigDecimal(amount));
        existing.setNote(note);
        existing.setType(TransactionType.TRANSFER);
        existing.setStatus(TransactionStatus.COMPLETED);
        existing.setIdempotencyKey("k1");
        return existing;
    }

    @Test
    void transferWithRepeatedIdempotencyKeyAndSameRequestReturnsExistingWithoutMovingMoney() {
        when(transactions.findByOwnerAndKey(1L, "k1")).thenReturn(Optional.of(existingTransfer(1, 2, "40.0000", "hi")));

        TransactionResponse r = service.transfer("alice",
                new TransferRequest("bob", null, new BigDecimal("40"), "hi"), "k1");

        assertThat(r.amount()).isEqualByComparingTo("40");
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        verify(transactions, never()).save(any());
    }

    @Test
    void transferReplayByWalletIdMatchesTheSameReceiver() {
        when(transactions.findByOwnerAndKey(1L, "k1")).thenReturn(Optional.of(existingTransfer(1, 2, "5", null)));
        service.transfer("alice", new TransferRequest(null, 2L, new BigDecimal("5"), null), "k1");
        verify(transactions, never()).save(any());
    }

    @Test
    void transferReplayWithDifferentAmountReceiverOrNoteIs409AndMovesNothing() {
        when(transactions.findByOwnerAndKey(1L, "k1")).thenReturn(Optional.of(existingTransfer(1, 2, "5", "a")));
        Wallet carol = TestData.wallet(3, TestData.user(30, "carol", Role.USER), "0", "USD");
        when(wallets.findByUserUsername("carol")).thenReturn(Optional.of(carol));
        when(wallets.findByIdForUpdate(3L)).thenReturn(Optional.of(carol)); // the key is now checked under the locks

        assertThatThrownBy(() -> service.transfer("alice",
                new TransferRequest("bob", null, new BigDecimal("6"), "a"), "k1"))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.transfer("alice",
                new TransferRequest("carol", null, new BigDecimal("5"), "a"), "k1"))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.transfer("alice",
                new TransferRequest("bob", null, new BigDecimal("5"), "b"), "k1"))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.transfer("alice",
                new TransferRequest("bob", null, new BigDecimal("5"), null), "k1"))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        verify(transactions, never()).save(any());
    }

@Test
void theIdempotencyKeyIsLookedUpOnlyAfterTheWalletLocksAreHeld() {
    when(transactions.findByOwnerAndKey(1L, "k1")).thenReturn(Optional.of(existingTransfer(1, 2, "5", null)));
    service.transfer("alice", new TransferRequest(null, 2L, new BigDecimal("5"), null), "k1");
    InOrder order = Mockito.inOrder(wallets, transactions);
    order.verify(wallets).findByIdForUpdate(1L);
    order.verify(wallets).findByIdForUpdate(2L);
    order.verify(transactions).findByOwnerAndKey(1L, "k1");

    Transaction deposit = new Transaction();
    deposit.setReceiverWalletId(1L);
    deposit.setAmount(new BigDecimal("5"));
    deposit.setType(TransactionType.DEPOSIT);
    deposit.setStatus(TransactionStatus.COMPLETED);
    when(transactions.findByOwnerAndKey(1L, "k2")).thenReturn(Optional.of(deposit));
    service.deposit(1L, new BigDecimal("5"), "k2");
    InOrder depositOrder = Mockito.inOrder(wallets, transactions);
    depositOrder.verify(wallets).findByIdForUpdate(1L);
    depositOrder.verify(transactions).findByOwnerAndKey(1L, "k2");
    assertThat(alice.getBalance()).isEqualByComparingTo("100");
    verify(transactions, never()).save(any());
}

@Test
void withdrawReplayIsAnsweredUnderTheLockEvenWhenTheBalanceNoLongerCoversIt() {
    alice.setBalance(BigDecimal.ZERO);
    Transaction withdrawal = new Transaction();
    withdrawal.setSenderWalletId(1L);
    withdrawal.setAmount(new BigDecimal("50"));
    withdrawal.setType(TransactionType.WITHDRAWAL);
    withdrawal.setStatus(TransactionStatus.COMPLETED);
    when(transactions.findByOwnerAndKey(1L, "k3")).thenReturn(Optional.of(withdrawal));

    TransactionResponse r = service.withdraw("alice", new BigDecimal("50"), "k3");

    assertThat(r.type()).isEqualTo("WITHDRAWAL");
    assertThat(alice.getBalance()).isEqualByComparingTo("0");
    verify(transactions, never()).save(any());
}

@Test
void transferReplayOfAWithdrawalWithTheSameKeyIs409NotReturnedAsTransfer() {
        Transaction withdrawal = new Transaction();
        withdrawal.setSenderWalletId(1L);
        withdrawal.setAmount(new BigDecimal("5"));
        withdrawal.setType(TransactionType.WITHDRAWAL);
        withdrawal.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByOwnerAndKey(1L, "k1")).thenReturn(Optional.of(withdrawal));

        assertThatThrownBy(() -> service.transfer("alice", toUser("bob", "5"), "k1"))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
    }

    @Test
    void transferLooksTheKeyUpOnlyInTheSendersOwnScope() {
        service.transfer("alice", toUser("bob", "1"), "shared");
        verify(transactions).findByOwnerAndKey(1L, "shared");
        verify(transactions, never()).findByOwnerAndKey(2L, "shared");
    }

    @Test
    void transferTrimsReceiverUsername() {
        service.transfer("alice", toUser("  bob  ", "1"), null);
        assertThat(bob.getBalance()).isEqualByComparingTo("51");
    }

    @Test
    void transferStoresNewIdempotencyKeyAndNoHeaderMeansNoKey() {
        ArgumentCaptor<Transaction> cap = ArgumentCaptor.forClass(Transaction.class);
        service.transfer("alice", toUser("bob", "1"), "abc");
        service.transfer("alice", toUser("bob", "1"), null);
        verify(transactions, Mockito.times(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getIdempotencyKey()).isEqualTo("abc");
        assertThat(cap.getAllValues().get(1).getIdempotencyKey()).isNull();
    }

    @Test
    void blankOrOverlongIdempotencyKeyIs400ForEveryOperationAndChangesNothing() {
        String tooLong = "k".repeat(101);
        for (String bad : new String[] {"", "   ", tooLong}) {
            assertThatThrownBy(() -> service.transfer("alice", toUser("bob", "1"), bad))
                    .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
            assertThatThrownBy(() -> service.deposit(1L, BigDecimal.ONE, bad))
                    .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
            assertThatThrownBy(() -> service.withdraw("alice", BigDecimal.ONE, bad))
                    .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        }
        verify(transactions, never()).save(any());
        service.transfer("alice", toUser("bob", "1"), "k".repeat(100)); // exactly 100 is fine
        assertThat(alice.getBalance()).isEqualByComparingTo("99");
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
    void depositWithRepeatedKeyAndSameAmountReturnsExistingAndDoesNotCredit() {
        Transaction existing = new Transaction();
        existing.setReceiverWalletId(1L);
        existing.setAmount(new BigDecimal("50.0000"));
        existing.setType(TransactionType.DEPOSIT);
        existing.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByOwnerAndKey(1L, "d1")).thenReturn(Optional.of(existing));

        service.deposit(1L, new BigDecimal("50"), "d1");
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        verify(transactions, never()).save(any());
    }

    @Test
    void depositWithKeyOfDifferentAmountOrTypeIs409() {
        Transaction other = new Transaction();
        other.setReceiverWalletId(1L);
        other.setAmount(new BigDecimal("7"));
        other.setType(TransactionType.DEPOSIT);
        other.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByOwnerAndKey(1L, "d1")).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.deposit(1L, BigDecimal.ONE, "d1"))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));

        Transaction wrongType = new Transaction();
        wrongType.setSenderWalletId(1L);
        wrongType.setReceiverWalletId(2L);
        wrongType.setAmount(BigDecimal.ONE);
        wrongType.setType(TransactionType.TRANSFER);
        wrongType.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByOwnerAndKey(1L, "d2")).thenReturn(Optional.of(wrongType));
        assertThatThrownBy(() -> service.deposit(1L, BigDecimal.ONE, "d2"))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
        verify(transactions, never()).save(any());
    }

    @Test
    void depositKeysAreScopedPerWallet() {
        service.deposit(1L, BigDecimal.ONE, "same");
        service.deposit(2L, BigDecimal.ONE, "same");
        verify(transactions).findByOwnerAndKey(1L, "same");
        verify(transactions).findByOwnerAndKey(2L, "same");
        verify(transactions, Mockito.times(2)).save(any());
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
    void withdrawWithRepeatedKeyAndSameAmountReturnsExistingAndDoesNotDebit() {
        Transaction existing = new Transaction();
        existing.setSenderWalletId(1L);
        existing.setAmount(new BigDecimal("50.0000"));
        existing.setType(TransactionType.WITHDRAWAL);
        existing.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByOwnerAndKey(1L, "w1")).thenReturn(Optional.of(existing));

        service.withdraw("alice", new BigDecimal("50"), "w1");
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
        verify(transactions, never()).save(any());
    }

    @Test
    void withdrawWithKeyOfDifferentAmountOrOperationIs409() {
        Transaction existing = new Transaction();
        existing.setSenderWalletId(1L);
        existing.setReceiverWalletId(2L);
        existing.setAmount(BigDecimal.ONE);
        existing.setType(TransactionType.TRANSFER);
        existing.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByOwnerAndKey(1L, "w1")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.withdraw("alice", BigDecimal.ONE, "w1"))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));

        Transaction diffAmount = new Transaction();
        diffAmount.setSenderWalletId(1L);
        diffAmount.setAmount(new BigDecimal("3"));
        diffAmount.setType(TransactionType.WITHDRAWAL);
        diffAmount.setStatus(TransactionStatus.COMPLETED);
        when(transactions.findByOwnerAndKey(1L, "w2")).thenReturn(Optional.of(diffAmount));
        assertThatThrownBy(() -> service.withdraw("alice", new BigDecimal("4"), "w2"))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
        assertThat(alice.getBalance()).isEqualByComparingTo("100");
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
    void getByIdOfForeignTransactionIs404NotForbidden() {
        Wallet carol = TestData.wallet(3, TestData.user(30, "carol", Role.USER), "0", "USD");
        when(wallets.findByUserUsername("carol")).thenReturn(Optional.of(carol));
        when(transactions.findById(5L)).thenReturn(Optional.of(between(1, 2)));
        assertThatThrownBy(() -> service.getById(5L, auth("carol", "ROLE_USER")))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
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

    @Test
    void historyAndListAllRejectSortPropertiesOutsideTheWhitelist() {
        for (String bad : new String[] {"idempotencyKey", "note", "doesNotExist", "sender.id"}) {
            Pageable p = PageRequest.of(0, 10, org.springframework.data.domain.Sort.by(bad));
            assertThatThrownBy(() -> service.history("alice", emptyFilter(), p))
                    .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
            assertThatThrownBy(() -> service.listAll(emptyFilter(), p))
                    .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        }
        verify(transactions, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void historyAllowsEveryWhitelistedSortProperty() {
        for (String ok : new String[] {"id", "createdAt", "amount", "type", "status", "senderWalletId", "receiverWalletId"}) {
            Pageable p = PageRequest.of(0, 10, org.springframework.data.domain.Sort.by(ok));
            when(transactions.findAll(any(Specification.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<Transaction>(List.of(), p, 0));
            assertThat(service.history("alice", emptyFilter(), p).content()).isEmpty();
        }
    }
}
