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

    // ---- round 2: F2 (NUL) and F8 (invisible/control characters) ----

    @ParameterizedTest
    @ValueSource(strings = {"alice", "Alice.B-c_d", "user1", "a.b", "  Carol  ", "x-y"})
    void usernameAcceptsLettersDigitsAndDotUnderscoreDash(String name) {
        assertThat(v.validate(new RegisterRequest(name, "a@b.co", "12345678"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"al\u0000ice", "alice​", "ali­ce", "bad\nuser", "bad\tuser", "ali ce",
            "аlice", "ａlice", "alice‮", "al ice", "a@b.c", "al'ice", "alice\n", "‍abc"})
    void usernameRejectsControlFormatLookAlikeAndOtherCharacters(String name) {
        assertThat(fields(new RegisterRequest(name, "a@b.co", "12345678"))).containsExactly("username");
    }

    @ParameterizedTest
    @ValueSource(strings = {"al\u0000ice", "alice​", "ali­ce", "line1\nline2", "tab\there", "x‮y",
            "a b", "a\u0007b", "lone\uD800surrogate", "‍x"})
    void notesRejectNulControlFormatAndBidiCharacters(String note) {
        assertThat(fields(new TransferRequest("bob", null, BigDecimal.ONE, note))).containsExactly("note");
        assertThat(fields(new CreateDepositRequest(BigDecimal.ONE, note))).containsExactly("note");
        assertThat(fields(new DepositDecisionRequest(note))).containsExactly("decisionNote");
    }

    @ParameterizedTest
    @ValueSource(strings = {"lunch with Bob", "Café — 日本語 😀", "ref #123: 50.00", " padded "})
    void notesAcceptOrdinaryTextIncludingUnicodeLettersAndSpaces(String note) {
        assertThat(v.validate(new TransferRequest("bob", null, BigDecimal.ONE, note))).isEmpty();
        assertThat(v.validate(new CreateDepositRequest(BigDecimal.ONE, note))).isEmpty();
        assertThat(v.validate(new DepositDecisionRequest(note))).isEmpty();
    }

    @Test
    void loginAndReceiverUsernameOnlyRejectNulSoLegacyNamesStayReachable() {
        assertThat(fields(new LoginRequest("al\u0000ice", "pw"))).containsExactly("username");
        assertThat(v.validate(new LoginRequest("ali​ce", "pw"))).isEmpty();
        assertThat(v.validate(new LoginRequest("Some Legacy Name", "pw"))).isEmpty();
        assertThat(fields(new TransferRequest("bo\u0000b", null, BigDecimal.ONE, null))).containsExactly("receiverUsername");
        assertThat(v.validate(new TransferRequest("bob​", null, BigDecimal.ONE, null))).isEmpty();
    }

    @Test
    void emailRejectsControlAndInvisibleCharacters() {
        for (String mail : new String[] {"a\u0000@b.co", "a@b​.co", "a­@b.co", "a@b.co\n", "a‮@b.co"}) {
            assertThat(fields(new RegisterRequest("abc", mail, "12345678"))).as(mail).contains("email");
            assertThat(fields(new UpdateProfileRequest(mail))).as(mail).contains("email");
        }
    }

    @Test
    void currentPasswordOver72BytesIsRejectedOnPasswordChange() {
        assertThat(v.validate(new ChangePasswordRequest("x".repeat(72), "12345678"))).isEmpty();
        assertThat(fields(new ChangePasswordRequest("x".repeat(73), "12345678"))).containsExactly("currentPassword");
    }

    @Test
    void loginAndReceiverUsernamesAreLimitedTo50CharactersTheLongestARegisteredNameCanBe() {
        assertThat(v.validate(new LoginRequest("a".repeat(50), "pw"))).isEmpty();
        assertThat(fields(new LoginRequest("a".repeat(51), "pw"))).containsExactly("username");
        assertThat(fields(new LoginRequest("a".repeat(100_000), "pw"))).containsExactly("username");
        assertThat(v.validate(new TransferRequest("b".repeat(50), null, BigDecimal.ONE, null))).isEmpty();
        assertThat(fields(new TransferRequest("b".repeat(51), null, BigDecimal.ONE, null)))
                .containsExactly("receiverUsername");
        assertThat(v.validate(new TransferRequest(null, 3L, BigDecimal.ONE, null))).as("receiver by id").isEmpty();
    }
}
