package com.example.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.wallet.dto.TransactionFilter;
import com.example.wallet.exception.ApiException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

class RequestLimitsTest {
    private static TransactionFilter dates(LocalDate from, LocalDate to) {
        return new TransactionFilter(from, to, null, null, null, null);
    }

    private static void assert400(Throwable t) {
        assertThat(t).isInstanceOf(ApiException.class);
        assertThat(((ApiException) t).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void pagesWhoseOffsetFitsAnIntAreAccepted() {
        assertThatCode(() -> RequestLimits.checkPage(PageRequest.of(0, 100))).doesNotThrowAnyException();
        assertThatCode(() -> RequestLimits.checkPage(PageRequest.of(100_000_000, 20))).doesNotThrowAnyException();
        assertThatCode(() -> RequestLimits.checkPage(PageRequest.of(21_474_836, 100))).doesNotThrowAnyException();
        assertThatCode(() -> RequestLimits.checkPage(Pageable.unpaged())).doesNotThrowAnyException();
    }

    @Test
    void pagesWhoseOffsetOverflowAnIntAre400() {
        for (Pageable p : new Pageable[] {PageRequest.of(99_999_999, 100), PageRequest.of(Integer.MAX_VALUE, 20),
                PageRequest.of(1_073_741_824, 2), PageRequest.of(21_474_837, 100)}) {
            assertThatThrownBy(() -> RequestLimits.checkPage(p)).satisfies(RequestLimitsTest::assert400)
                    .hasMessageContaining("page");
        }
    }

    @Test
    void datesInsideTheBoundsAreAccepted() {
        assertThatCode(() -> RequestLimits.checkDates(null)).doesNotThrowAnyException();
        assertThatCode(() -> RequestLimits.checkDates(dates(null, null))).doesNotThrowAnyException();
        assertThatCode(() -> RequestLimits.checkDates(dates(LocalDate.of(1900, 1, 1), LocalDate.of(2200, 12, 31))))
                .doesNotThrowAnyException();
        assertThatCode(() -> RequestLimits.checkDates(dates(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))))
                .doesNotThrowAnyException();
    }

    @Test
    void datesOutsideTheBoundsAre400() {
        LocalDate[] bad = {LocalDate.MAX, LocalDate.MIN, LocalDate.of(999999999, 12, 31), LocalDate.of(1899, 12, 31),
                LocalDate.of(2201, 1, 1), LocalDate.of(9999, 12, 31)};
        for (LocalDate d : bad) {
            assertThatThrownBy(() -> RequestLimits.checkDates(dates(d, null)))
                    .satisfies(RequestLimitsTest::assert400).hasMessageContaining("from");
            assertThatThrownBy(() -> RequestLimits.checkDates(dates(null, d)))
                    .satisfies(RequestLimitsTest::assert400).hasMessageContaining("to");
        }
    }
}
