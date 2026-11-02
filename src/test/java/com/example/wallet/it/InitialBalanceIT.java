package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * New accounts must start with nothing. The other integration tests pre-fund wallets through the test-only property
 * {@code app.wallet.initial-balance=100.00} in {@code application-it.properties}; this class runs with the
 * production value of that property ({@code 0.00}, asserted against the real {@code application.properties} in
 * {@code JwtSecretStartupTest}) so the effect of the default is tested end to end.
 */
@TestPropertySource(properties = "app.wallet.initial-balance=0.00")
class InitialBalanceIT extends ItBase {

    @Test
    void newAccountsStartAtZeroAndCannotSpendOrWithdrawAnything() throws Exception {
        String alice = register("alice");
        register("bob");
        assertThat(balance("alice")).isEqualByComparingTo("0");
        assertThat(body(getJson("/api/wallets/me", alice)).get("balance").decimalValue()).isEqualByComparingTo("0");

        assertThat(withdraw(alice, "0.0001", null).getResponse().getStatus()).isEqualTo(422);
        assertThat(transfer(alice, "bob", "0.0001", null).getResponse().getStatus()).isEqualTo(422);
        assertThat(txCount()).isZero();
    }

    @Test
    void moneyEntersOnlyThroughTheAdminDepositEndpoint() throws Exception {
        String alice = register("alice");
        register("bob");
        String admin = adminToken();
        assertThat(balance("admin")).isEqualByComparingTo("0");

        assertThat(postJson("/api/wallets/me/deposit", alice, map("amount", 100), null).getResponse().getStatus())
                .as("self-service deposit does not exist").isEqualTo(404);
        assertThat(balance("alice")).isEqualByComparingTo("0");

        assertThat(adminDeposit(admin, "alice", "100", "fund-1").getResponse().getStatus()).isEqualTo(201);
        assertThat(balance("alice")).isEqualByComparingTo("100");
        assertThat(transfer(alice, "bob", "30", null).getResponse().getStatus()).isEqualTo(201);
        assertThat(balance("bob")).isEqualByComparingTo("30");
    }
}
