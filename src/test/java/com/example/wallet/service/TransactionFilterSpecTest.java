package com.example.wallet.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.wallet.dto.TransactionFilter;
import com.example.wallet.entity.Transaction;
import com.example.wallet.entity.TransactionStatus;
import com.example.wallet.entity.TransactionType;
import com.example.wallet.repository.TransactionRepository;
import com.example.wallet.repository.WalletRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/** Verifies which criteria the transaction filter turns into, using a mocked JPA criteria API (no database). */
@SuppressWarnings({"unchecked", "rawtypes"})
class TransactionFilterSpecTest {
    TransactionRepository transactions = mock(TransactionRepository.class);
    WalletRepository wallets = mock(WalletRepository.class);
    TransactionService service = new TransactionService(transactions, wallets);
    Root<Transaction> root = mock(Root.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class);
    CriteriaQuery<?> query = mock(CriteriaQuery.class);
    Path path = mock(Path.class);

    @BeforeEach
    void setUp() {
        when(root.get(any(String.class))).thenReturn(path);
        when(cb.equal(any(), (Object) any())).thenReturn(mock(Predicate.class));
        when(cb.or(any(Predicate.class), any(Predicate.class))).thenReturn(mock(Predicate.class));
        when(cb.greaterThanOrEqualTo(any(), (Comparable) any())).thenReturn(mock(Predicate.class));
        when(cb.lessThan(any(), (Comparable) any())).thenReturn(mock(Predicate.class));
        when(cb.lessThanOrEqualTo(any(), (Comparable) any())).thenReturn(mock(Predicate.class));
        when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));
        when(transactions.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<Transaction>(List.of()));
    }

    private void apply(TransactionFilter f) {
        service.listAll(f, PageRequest.of(0, 10));
        ArgumentCaptor<Specification> cap = ArgumentCaptor.forClass(Specification.class);
        verify(transactions).findAll(cap.capture(), any(Pageable.class));
        cap.getValue().toPredicate(root, query, cb);
    }

    private int andArity() {
        ArgumentCaptor<Predicate[]> c = ArgumentCaptor.forClass(Predicate[].class);
        verify(cb).and(c.capture());
        return c.getValue().length;
    }

    @Test
    void emptyFilterAddsNoConditions() {
        apply(new TransactionFilter(null, null, null, null, null, null));
        org.junit.jupiter.api.Assertions.assertEquals(0, andArity());
    }

    @Test
    void everyFilterFieldAddsOneConditionAndAllAreAnded() {
        apply(new TransactionFilter(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31), BigDecimal.ONE,
                BigDecimal.TEN, TransactionStatus.COMPLETED, TransactionType.DEPOSIT));
        org.junit.jupiter.api.Assertions.assertEquals(6, andArity());
    }

    @Test
    void dateRangeIsInclusiveOfWholeToDay() {
        apply(new TransactionFilter(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31), null, null, null, null));
        verify(cb).greaterThanOrEqualTo(any(), (Comparable) org.mockito.ArgumentMatchers.eq(LocalDateTime.of(2024, 1, 1, 0, 0)));
        // "to" is exclusive of the start of the following day
        verify(cb).lessThan(any(), (Comparable) org.mockito.ArgumentMatchers.eq(LocalDateTime.of(2024, 2, 1, 0, 0)));
    }

    @Test
    void amountBoundsUseInclusiveComparisons() {
        apply(new TransactionFilter(null, null, new BigDecimal("5"), new BigDecimal("9"), null, null));
        verify(cb).greaterThanOrEqualTo(any(), (Comparable) org.mockito.ArgumentMatchers.eq(new BigDecimal("5")));
        verify(cb).lessThanOrEqualTo(any(), (Comparable) org.mockito.ArgumentMatchers.eq(new BigDecimal("9")));
        verify(cb, never()).lessThan(any(), (Comparable) any());
    }
}
