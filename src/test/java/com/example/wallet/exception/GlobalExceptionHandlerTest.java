package com.example.wallet.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.util.TypeInformation;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;

class GlobalExceptionHandlerTest {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static DataIntegrityViolationException violation(String sqlState) {
        return new DataIntegrityViolationException("x", new RuntimeException("wrapped", new SQLException("db", sqlState)));
    }

    @Test
    void disabledAndBadCredentialsGiveTheIdentical401() {
        var bad = handler.badCreds(new BadCredentialsException("x"));
        var off = handler.badCreds(new DisabledException("x"));
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED).isEqualTo(bad.getStatusCode());
        assertThat(off.getBody().get("error")).isEqualTo("Invalid username or password").isEqualTo(bad.getBody().get("error"));
    }

    @Test
    void uniqueViolationIs409() {
        assertThat(handler.integrity(violation("23505")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void violationWithoutSqlStateKeepsTheOldConflictBehaviour() {
        assertThat(handler.integrity(new DataIntegrityViolationException("no sql cause")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void outOfRangeOrTooLongValuesAre400() {
        assertThat(handler.integrity(violation("22003")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(handler.integrity(violation("22001")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void checkForeignKeyAndNotNullViolationsAreNotDisguisedAsConflicts() {
        for (String state : new String[] {"23514", "23503", "23502"}) {
            assertThat(handler.integrity(violation(state)).getStatusCode())
                    .as(state).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Test
    void propertyReferenceExceptionIs400() {
        var e = new PropertyReferenceException("nope", TypeInformation.of(Object.class), List.of());
        assertThat(handler.badSort(e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void otherInvalidDataAccessUsageIsNoLongerMappedToInvalidSort() throws Exception {
        var method = GlobalExceptionHandler.class.getMethod("badSort", PropertyReferenceException.class);
        var types = method.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class).value();
        assertThat(types).containsExactly(PropertyReferenceException.class)
                .doesNotContain(InvalidDataAccessApiUsageException.class);
    }
}
