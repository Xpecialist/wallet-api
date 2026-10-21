package com.example.wallet.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DtoValidationTest {
    static final Validator v = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<String> fields(Object o) {
        return v.validate(o).stream().map(c -> c.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0001", "1", "100.50", "999999999999999.9999"})
    void amountAcceptsPositiveValues(String amt) {
        assertThat(v.validate(new AmountRequest(new BigDecimal(amt)))).isEmpty();
        assertThat(v.validate(new TransferRequest("bob", null, new BigDecimal(amt), null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "0.00001", "-1", "-0.0001", "1.00001", "1000000000000000"})
    void amountRejectsZeroNegativeTooSmallAndTooPrecise(String amt) {
        assertThat(fields(new AmountRequest(new BigDecimal(amt)))).containsExactly("amount");
        assertThat(fields(new TransferRequest("bob", null, new BigDecimal(amt), null))).containsExactly("amount");
    }

    @Test
    void nullAmountRejected() {
        assertThat(fields(new AmountRequest(null))).containsExactly("amount");
        assertThat(fields(new TransferRequest("bob", null, null, null))).containsExactly("amount");
    }

    @Test
    void transferNoteLimitedTo255Chars() {
        assertThat(v.validate(new TransferRequest("bob", null, BigDecimal.ONE, "x".repeat(255)))).isEmpty();
        assertThat(fields(new TransferRequest("bob", null, BigDecimal.ONE, "x".repeat(256)))).containsExactly("note");
    }

    @Test
    void registerRequestRules() {
        assertThat(v.validate(new RegisterRequest("abc", "a@b.co", "12345678"))).isEmpty();
        assertThat(fields(new RegisterRequest("ab", "a@b.co", "12345678"))).containsExactly("username");
        assertThat(fields(new RegisterRequest("x".repeat(51), "a@b.co", "12345678"))).containsExactly("username");
        assertThat(fields(new RegisterRequest("", "a@b.co", "12345678"))).contains("username");
        assertThat(fields(new RegisterRequest("abc", "not-an-email", "12345678"))).containsExactly("email");
        assertThat(fields(new RegisterRequest("abc", "", "12345678"))).contains("email");
        assertThat(fields(new RegisterRequest("abc", "a@b.co", "1234567"))).containsExactly("password");
        assertThat(fields(new RegisterRequest("abc", "a@b.co", "x".repeat(73)))).containsExactly("password");
        assertThat(v.validate(new RegisterRequest("abc", "a@b.co", "x".repeat(72)))).isEmpty();
        assertThat(fields(new RegisterRequest(null, null, null))).containsExactlyInAnyOrder("username", "email", "password");
    }

    @Test
    void passwordLimitIsCountedInUtf8BytesNotCharacters() {
        String twoByte = "é"; // e with acute accent, 2 bytes in UTF-8
        String ok72Bytes = twoByte.repeat(36); // 36 chars, 72 bytes
        String bad73Bytes = twoByte.repeat(36) + "a"; // 37 chars, 73 bytes
        assertThat(v.validate(new RegisterRequest("abc", "a@b.co", ok72Bytes))).isEmpty();
        assertThat(fields(new RegisterRequest("abc", "a@b.co", bad73Bytes))).containsExactly("password");
        assertThat(v.validate(new ChangePasswordRequest("old", ok72Bytes))).isEmpty();
        assertThat(fields(new ChangePasswordRequest("old", bad73Bytes))).containsExactly("newPassword");
        // 4-byte emoji: 18 of them are exactly 72 bytes (36 UTF-16 chars), 19 are not
        String emoji = new String(Character.toChars(0x1F600));
        assertThat(v.validate(new RegisterRequest("abc", "a@b.co", emoji.repeat(18)))).isEmpty();
        assertThat(fields(new RegisterRequest("abc", "a@b.co", emoji.repeat(19)))).containsExactly("password");
    }

    @Test
    void loginRequestRequiresBothFields() {
        assertThat(v.validate(new LoginRequest("a", "b"))).isEmpty();
        assertThat(fields(new LoginRequest(" ", ""))).containsExactlyInAnyOrder("username", "password");
    }

    @Test
    void changePasswordRules() {
        assertThat(v.validate(new ChangePasswordRequest("old", "12345678"))).isEmpty();
        assertThat(fields(new ChangePasswordRequest("", "12345678"))).containsExactly("currentPassword");
        assertThat(fields(new ChangePasswordRequest("old", "short"))).containsExactly("newPassword");
        assertThat(fields(new ChangePasswordRequest("old", "x".repeat(73)))).containsExactly("newPassword");
    }

    @Test
    void updateProfileRequiresValidEmail() {
        assertThat(v.validate(new UpdateProfileRequest("a@b.co"))).isEmpty();
        assertThat(fields(new UpdateProfileRequest("nope"))).containsExactly("email");
        assertThat(fields(new UpdateProfileRequest(" "))).containsExactly("email");
        assertThat(fields(new UpdateProfileRequest(null))).containsExactly("email");
    }

    @Test
    void userStatusRequiresEnabledFlag() {
        assertThat(v.validate(new UserStatusRequest(false))).isEmpty();
        assertThat(fields(new UserStatusRequest(null))).containsExactly("enabled");
    }
}
