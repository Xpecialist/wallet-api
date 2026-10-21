package com.example.wallet.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.wallet.TestData;
import com.example.wallet.dto.AuthResponse;
import com.example.wallet.dto.PageResponse;
import com.example.wallet.dto.TransactionFilter;
import com.example.wallet.dto.TransactionResponse;
import com.example.wallet.dto.UserResponse;
import com.example.wallet.entity.Role;
import com.example.wallet.exception.ApiException;
import com.example.wallet.repository.UserRepository;
import com.example.wallet.security.JwtService;
import com.example.wallet.security.SecurityConfig;
import com.example.wallet.service.AuthService;
import com.example.wallet.service.TransactionService;
import com.example.wallet.service.UserService;
import com.example.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer tests with the real security configuration, real JWT service and real exception handler; services and
 * repositories are mocked, so no database is involved.
 */
@WebMvcTest(controllers = {AuthController.class, AdminController.class, TransactionController.class,
        UserController.class, WalletController.class},
        properties = {
                "app.jwt.secret=MDEyMzQ1Njc4OS0wMTIzNDU2Nzg5LTAxMjM0NTY3ODktQUI=",
                "app.jwt.expiration-ms=60000",
                "spring.data.web.pageable.default-page-size=20", "spring.data.web.pageable.max-page-size=100"})
@Import({SecurityConfig.class, JwtService.class})
class ApiWebLayerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockBean UserRepository userRepository;
    @MockBean AuthService authService;
    @MockBean UserService userService;
    @MockBean WalletService walletService;
    @MockBean TransactionService transactionService;

    @BeforeEach
    void users() {
        when(userRepository.findByUsername("alice"))
                .thenReturn(Optional.of(TestData.user(1, "alice", Role.USER)));
        when(userRepository.findByUsername("root"))
                .thenReturn(Optional.of(TestData.user(2, "root", Role.ADMIN)));
        var disabled = TestData.user(3, "off", Role.USER);
        disabled.setEnabled(false);
        when(userRepository.findByUsername("off")).thenReturn(Optional.of(disabled));
    }

    private String bearer(String user) { return "Bearer " + jwt.generateToken(user); }

    private static TransactionResponse tx() {
        return new TransactionResponse(1L, 1L, 2L, new BigDecimal("5"), "TRANSFER", "COMPLETED", null, null);
    }

    private static final PageResponse<TransactionResponse> EMPTY_TX = new PageResponse<>(List.of(), 0, 20, 0, 0);

    // ---- authentication ----

    @Test
    void protectedEndpointsReturn401WithoutTokenOrWithBadToken() throws Exception {
        mvc.perform(get("/api/wallets/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/transactions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/wallets/me").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenOfDisabledOrDeletedUserIs401() throws Exception {
        mvc.perform(get("/api/wallets/me").header("Authorization", bearer("off"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/wallets/me").header("Authorization", bearer("ghost"))).andExpect(status().isUnauthorized());
    }

    @Test
    void authEndpointsArePublic() throws Exception {
        when(authService.login(any())).thenReturn(new AuthResponse("t"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("t"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void registerReturns201AndValidatesBody() throws Exception {
        when(authService.register(any())).thenReturn(new AuthResponse("t"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"email\":\"a@b.co\",\"password\":\"password1\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"al\",\"email\":\"bad\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void loginWithBadCredentialsAndDisabledAccountBothGiveTheSame401() throws Exception {
        org.mockito.Mockito.doThrow(new BadCredentialsException("x")).when(authService).login(any());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"pw\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password"));
        org.mockito.Mockito.doThrow(new DisabledException("x")).when(authService).login(any());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"pw\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username or password"));
    }

    @Test
    void duplicateRegistrationMapsTo409() throws Exception {
        when(authService.register(any())).thenThrow(new ApiException(HttpStatus.CONFLICT, "Username already taken"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"email\":\"a@b.co\",\"password\":\"password1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Username already taken"));
    }

    // ---- admin authorization ----

    @Test
    void adminEndpointsForbiddenForRegularUser() throws Exception {
        String h = bearer("alice");
        mvc.perform(get("/api/admin/users").header("Authorization", h)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/transactions").header("Authorization", h)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/users/2/status").header("Authorization", h)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/wallets/1/deposit").header("Authorization", h)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}")).andExpect(status().isForbidden());
        verifyNoInteractions(userService);
        verify(transactionService, never()).deposit(any(), any(), any());
    }

    @Test
    void adminEndpointsAllowedForAdminAndUseDefaultPaging() throws Exception {
        when(userService.listAll(any())).thenReturn(new PageResponse<UserResponse>(List.of(), 0, 20, 0, 0));
        mvc.perform(get("/api/admin/users").header("Authorization", bearer("root"))).andExpect(status().isOk());
        ArgumentCaptor<Pageable> p = ArgumentCaptor.forClass(Pageable.class);
        verify(userService).listAll(p.capture());
        org.assertj.core.api.Assertions.assertThat(p.getValue().getPageSize()).isEqualTo(20);
        org.assertj.core.api.Assertions.assertThat(p.getValue().getSort().getOrderFor("id")).isNotNull();
    }

    @Test
    void adminDepositReturns201AndRejectsNonPositiveAmount() throws Exception {
        when(transactionService.deposit(eq(1L), any(), any())).thenReturn(tx());
        String h = bearer("root");
        mvc.perform(post("/api/admin/wallets/1/deposit").header("Authorization", h).header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}")).andExpect(status().isCreated());
        verify(transactionService).deposit(eq(1L), eq(new BigDecimal("10")), eq("k"));
        mvc.perform(post("/api/admin/wallets/1/deposit").header("Authorization", h)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":0}")).andExpect(status().isBadRequest());
    }

    @Test
    void adminCannotDisableSelfErrorSurfacesAs400() throws Exception {
        when(userService.setEnabled(2L, false, "root"))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "You cannot disable your own account"));
        mvc.perform(patch("/api/admin/users/2/status").header("Authorization", bearer("root"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("You cannot disable your own account"));
    }

    @Test
    void adminStatusRequiresEnabledField() throws Exception {
        mvc.perform(patch("/api/admin/users/2/status").header("Authorization", bearer("root"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    // ---- transfers / deposits / withdrawals ----

    @Test
    void transferPassesAuthenticatedUserAndIdempotencyKeyAndReturns201() throws Exception {
        when(transactionService.transfer(anyString(), any(), any())).thenReturn(tx());
        mvc.perform(post("/api/transactions/transfer").header("Authorization", bearer("alice"))
                        .header("Idempotency-Key", "abc").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverUsername\":\"bob\",\"amount\":5,\"note\":\"hi\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TRANSFER"));
        verify(transactionService).transfer(eq("alice"), any(), eq("abc"));
    }

    @Test
    void transferValidationFailuresAre400AndNeverReachService() throws Exception {
        String h = bearer("alice");
        for (String body : List.of("{\"receiverUsername\":\"bob\",\"amount\":0}",
                "{\"receiverUsername\":\"bob\",\"amount\":-5}",
                "{\"receiverUsername\":\"bob\"}",
                "{\"receiverUsername\":\"bob\",\"amount\":1.00001}",
                "{\"receiverUsername\":\"bob\",\"amount\":\"abc\"}")) {
            mvc.perform(post("/api/transactions/transfer").header("Authorization", h)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/transactions/transfer").header("Authorization", h)
                .contentType(MediaType.APPLICATION_JSON).content("")).andExpect(status().isBadRequest());
        verifyNoInteractions(transactionService);
    }

    @Test
    void insufficientFundsMapsTo422() throws Exception {
        when(transactionService.transfer(anyString(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds"));
        mvc.perform(post("/api/transactions/transfer").header("Authorization", bearer("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"receiverUsername\":\"bob\",\"amount\":5}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Insufficient funds"));
    }

    @Test
    void unknownReceiverMapsTo404() throws Exception {
        when(transactionService.transfer(anyString(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Receiver not found"));
        mvc.perform(post("/api/transactions/transfer").header("Authorization", bearer("alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"receiverUsername\":\"zz\",\"amount\":5}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void walletWithdrawUsesCallerAndValidatesAmount() throws Exception {
        when(transactionService.withdraw(anyString(), any(), any())).thenReturn(tx());
        String h = bearer("alice");
        mvc.perform(post("/api/wallets/me/withdraw").header("Authorization", h)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}")).andExpect(status().isCreated());
        verify(transactionService).withdraw(eq("alice"), eq(new BigDecimal("10")), any());

        mvc.perform(post("/api/wallets/me/withdraw").header("Authorization", h)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":0}")).andExpect(status().isBadRequest());
    }

    // ---- pagination / filtering ----

    @Test
    void historyBindsFilterAndPagingParameters() throws Exception {
        when(transactionService.history(anyString(), any(), any())).thenReturn(EMPTY_TX);
        mvc.perform(get("/api/transactions").header("Authorization", bearer("alice"))
                        .param("from", "2024-01-01").param("to", "2024-01-31")
                        .param("minAmount", "1.5").param("maxAmount", "9")
                        .param("status", "COMPLETED").param("type", "DEPOSIT")
                        .param("page", "2").param("size", "5").param("sort", "amount,asc"))
                .andExpect(status().isOk());
        ArgumentCaptor<TransactionFilter> f = ArgumentCaptor.forClass(TransactionFilter.class);
        ArgumentCaptor<Pageable> p = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionService).history(eq("alice"), f.capture(), p.capture());
        org.assertj.core.api.Assertions.assertThat(f.getValue().from()).isEqualTo(LocalDate.of(2024, 1, 1));
        org.assertj.core.api.Assertions.assertThat(f.getValue().to()).isEqualTo(LocalDate.of(2024, 1, 31));
        org.assertj.core.api.Assertions.assertThat(f.getValue().minAmount()).isEqualByComparingTo("1.5");
        org.assertj.core.api.Assertions.assertThat(f.getValue().maxAmount()).isEqualByComparingTo("9");
        org.assertj.core.api.Assertions.assertThat(f.getValue().status().name()).isEqualTo("COMPLETED");
        org.assertj.core.api.Assertions.assertThat(f.getValue().type().name()).isEqualTo("DEPOSIT");
        org.assertj.core.api.Assertions.assertThat(p.getValue().getPageNumber()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(p.getValue().getPageSize()).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(p.getValue().getSort().getOrderFor("amount").isAscending()).isTrue();
    }

    @Test
    void historyDefaultsToNewestFirstAndCapsPageSizeAt100() throws Exception {
        when(transactionService.history(anyString(), any(), any())).thenReturn(EMPTY_TX);
        mvc.perform(get("/api/transactions").header("Authorization", bearer("alice")).param("size", "5000"))
                .andExpect(status().isOk());
        ArgumentCaptor<Pageable> p = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionService).history(eq("alice"), any(), p.capture());
        org.assertj.core.api.Assertions.assertThat(p.getValue().getPageSize()).isEqualTo(100);
        org.assertj.core.api.Assertions.assertThat(p.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
    }

    @Test
    void historyWithInvalidFilterValuesIs400() throws Exception {
        String h = bearer("alice");
        mvc.perform(get("/api/transactions").header("Authorization", h).param("status", "BOGUS")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/transactions").header("Authorization", h).param("from", "not-a-date")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/transactions").header("Authorization", h).param("minAmount", "abc")).andExpect(status().isBadRequest());
        verifyNoInteractions(transactionService);
    }

    @Test
    void invalidSortPropertyFromServiceMapsTo400() throws Exception {
        when(transactionService.history(anyString(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "Invalid sort parameter"));
        mvc.perform(get("/api/transactions").header("Authorization", bearer("alice")).param("sort", "nope,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid sort parameter"));
    }

    @Test
    void foreignTransactionViewMapsTo404() throws Exception {
        when(transactionService.getById(eq(9L), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Transaction not found"));
        mvc.perform(get("/api/transactions/9").header("Authorization", bearer("alice")))
                .andExpect(status().isNotFound());
    }

    // ---- profile ----

    @Test
    void profileGetUpdateAndPasswordChange() throws Exception {
        String h = bearer("alice");
        var resp = new UserResponse(1L, "alice", "new@b.co", "USER", true, null);
        when(userService.getProfile("alice")).thenReturn(resp);
        when(userService.updateEmail(eq("alice"), any())).thenReturn(resp);

        mvc.perform(get("/api/users/me").header("Authorization", h))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("alice"));
        mvc.perform(patch("/api/users/me").header("Authorization", h)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"new@b.co\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("new@b.co"));
        mvc.perform(post("/api/users/me/password").header("Authorization", h)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old\",\"newPassword\":\"newpassword\"}"))
                .andExpect(status().isNoContent());
        verify(userService).changePassword(eq("alice"), any());
    }

    @Test
    void profileValidationFailuresAre400() throws Exception {
        String h = bearer("alice");
        mvc.perform(patch("/api/users/me").header("Authorization", h)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nope\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/users/me/password").header("Authorization", h)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"old\",\"newPassword\":\"short\"}")).andExpect(status().isBadRequest());
        verify(userService, never()).changePassword(any(), any());
    }

    @Test
    void walletByIdForeignWalletMapsTo404() throws Exception {
        when(walletService.getById(eq(5L), any())).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Wallet not found"));
        mvc.perform(get("/api/wallets/5").header("Authorization", bearer("alice"))).andExpect(status().isNotFound());
    }

    @Test
    void selfServiceDepositEndpointIsGone() throws Exception {
        mvc.perform(post("/api/wallets/me/deposit").header("Authorization", bearer("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/wallets/me/deposit").header("Authorization", bearer("root"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10}")).andExpect(status().isNotFound());
        verify(transactionService, never()).deposit(any(), any(), any());
    }

    @Test
    void transactionResponseDoesNotExposeTheIdempotencyKey() throws Exception {
        when(transactionService.transfer(anyString(), any(), any())).thenReturn(tx());
        mvc.perform(post("/api/transactions/transfer").header("Authorization", bearer("alice"))
                        .header("Idempotency-Key", "secret-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverUsername\":\"bob\",\"amount\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist());
    }

    @Test
    void passwordOver72BytesIsRejectedOnRegisterAndChangePassword() throws Exception {
        String tooLong = "é".repeat(37); // 37 characters but 74 bytes
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"email\":\"a@b.co\",\"password\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/users/me/password").header("Authorization", bearer("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old\",\"newPassword\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(authService);
        verify(userService, never()).changePassword(any(), any());
    }
}
