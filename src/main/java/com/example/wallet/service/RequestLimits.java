package com.example.wallet.service;

import com.example.wallet.dto.TransactionFilter;
import com.example.wallet.exception.ApiException;
import java.time.LocalDate;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

/** Bounds on paging and date filters, so extreme values give a 400 instead of an arithmetic overflow (HTTP 500). */
final class RequestLimits {
    /** Earliest accepted {@code from}/{@code to} filter date. */
    static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);
    /** Latest accepted {@code from}/{@code to} filter date. */
    static final LocalDate MAX_DATE = LocalDate.of(2200, 12, 31);

    private RequestLimits() {}

    /**
     * Rejects a page whose row offset ({@code page * size}) does not fit in an int, which is what the persistence
     * layer and the database offset need.
     *
     * @param pageable the requested page
     * @throws ApiException 400 if the page is too far out
     */
    static void checkPage(Pageable pageable) {
        if (pageable.isPaged() && pageable.getOffset() > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "page is too large");
        }
    }

    /**
     * Rejects {@code from}/{@code to} dates outside 1900-01-01 .. 2200-12-31 ({@code to.plusDays(1)} would otherwise
     * overflow for the largest {@link LocalDate}).
     *
     * @param f the filter, may be {@code null}
     * @throws ApiException 400 if a date is out of range
     */
    static void checkDates(TransactionFilter f) {
        if (f == null) return;
        checkDate("from", f.from());
        checkDate("to", f.to());
    }

    private static void checkDate(String name, LocalDate d) {
        if (d != null && (d.isBefore(MIN_DATE) || d.isAfter(MAX_DATE))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    name + " must be between " + MIN_DATE + " and " + MAX_DATE);
        }
    }
}
