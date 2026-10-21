package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MvcResult;

/** Deposits, withdrawals, transfers, rollback and idempotency verified against the real tables. */
class MoneyMovementIT extends ItBase {

    private Map<String, Object> lastTx() {
        return jdbc.queryForMap("select * from transactions order by id desc limit 1");
    }

    // ---- deposit / withdraw ------------------------------------------------------------------------------------

    @Test
    void depositIncreasesBalanceAndPersistsDepositRow() throws Exception {
        register("alice");
        MvcResult r = adminDeposit(adminToken(), "alice", "50.25", null);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(r).get("type").asText()).isEqualTo("DEPOSIT");
        assertThat(body(r).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(balance("alice")).isEqualByComparingTo("150.25");

        assertThat(txCount()).isEqualTo(1);
        Map<String, Object> row = lastTx();
        assertThat(row.get("type")).isEqualTo("DEPOSIT");
        assertThat(row.get("sender_wallet_id")).isNull();
        assertThat(row.get("receiver_wallet_id")).isEqualTo(walletId("alice"));
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("50.25");
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void withdrawDecreasesBalanceAndPersistsWithdrawalRow() throws Exception {
        String t = register("alice");
        MvcResult r = withdraw(t, "40", null);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(balance("alice")).isEqualByComparingTo("60");
        Map<String, Object> row = lastTx();
        assertThat(row.get("type")).isEqualTo("WITHDRAWAL");
        assertThat(row.get("sender_wallet_id")).isEqualTo(walletId("alice"));
        assertThat(row.get("receiver_wallet_id")).isNull();
    }

    @Test
    void withdrawingEntireBalanceIsAllowedAndLeavesZero() throws Exception {
        String t = register("alice");
        assertThat(withdraw(t, "100", null).getResponse().getStatus()).isEqualTo(201);
        assertThat(balance("alice")).isEqualByComparingTo("0");
        assertThat(withdraw(t, "0.0001", null).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void overdrawingWithdrawalIs422AndRollsBack() throws Exception {
        String t = register("alice");
        MvcResult r = withdraw(t, "100.0001", null);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(balance("alice")).isEqualByComparingTo("100");
        assertThat(txCount()).isZero();
    }

    @Test
    void invalidAmountsAreRejectedWith400AndChangeNothing() throws Exception {
        String t = register("alice");
        register("bob");
        String adm = adminToken();
        for (String amt : new String[] {"0", "-5", "0.00001", "1.23456"}) {
            assertThat(adminDeposit(adm, "alice", amt, null).getResponse().getStatus()).as("deposit " + amt).isEqualTo(400);
            assertThat(withdraw(t, amt, null).getResponse().getStatus()).as("withdraw " + amt).isEqualTo(400);
            assertThat(transfer(t, "bob", amt, null).getResponse().getStatus()).as("transfer " + amt).isEqualTo(400);
        }
        assertThat(balance("alice")).isEqualByComparingTo("100");
        assertThat(balance("bob")).isEqualByComparingTo("100");
        assertThat(txCount()).isZero();
    }

    @Test
    void smallestUnitAmountsKeepFourDecimalPrecision() throws Exception {
        String t = register("alice");
        register("bob");
        assertThat(transfer(t, "bob", "0.0001", null).getResponse().getStatus()).isEqualTo(201);
        assertThat(balance("alice")).isEqualByComparingTo("99.9999");
        assertThat(balance("bob")).isEqualByComparingTo("100.0001");
    }

    // ---- transfer ------------------------------------------------------------------------------------------------

    @Test
    void transferMovesMoneyAndPersistsOneTransferRowWithNote() throws Exception {
        String a = register("alice");
        register("bob");
        MvcResult r = postJson("/api/transactions/transfer", a,
                map("receiverUsername", "bob", "amount", bd("30.5"), "note", "lunch"), null);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(balance("alice")).isEqualByComparingTo("69.5");
        assertThat(balance("bob")).isEqualByComparingTo("130.5");

        assertThat(txCount()).isEqualTo(1);
        Map<String, Object> row = lastTx();
        assertThat(row.get("type")).isEqualTo("TRANSFER");
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("note")).isEqualTo("lunch");
        assertThat(row.get("sender_wallet_id")).isEqualTo(walletId("alice"));
        assertThat(row.get("receiver_wallet_id")).isEqualTo(walletId("bob"));
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("30.5");
        assertThat(((Number) body(r).get("id").numberValue()).longValue()).isEqualTo(((Number) row.get("id")).longValue());
    }

    @Test
    void transferByWalletIdWorksAndBumpsOptimisticVersion() throws Exception {
        String a = register("alice");
        register("bob");
        long v0 = jdbc.queryForObject("select version from wallets where id=?", Long.class, walletId("alice"));
        MvcResult r = postJson("/api/transactions/transfer", a,
                map("receiverWalletId", walletId("bob"), "amount", bd("10")), null);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(balance("bob")).isEqualByComparingTo("110");
        long v1 = jdbc.queryForObject("select version from wallets where id=?", Long.class, walletId("alice"));
        assertThat(v1).isGreaterThan(v0);
    }

    @Test
    void transferWithInsufficientFundsIs422AndLeavesBalancesAndRowsUnchanged() throws Exception {
        String a = register("alice");
        register("bob");
        MvcResult r = transfer(a, "bob", "100.01", "key-insufficient");
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(balance("alice")).isEqualByComparingTo("100");
        assertThat(balance("bob")).isEqualByComparingTo("100");
        assertThat(txCount()).isZero();
        // the failed attempt must not burn the idempotency key
        assertThat(adminDeposit(adminToken(), "alice", "10", null).getResponse().getStatus()).isEqualTo(201);
        assertThat(transfer(a, "bob", "100.01", "key-insufficient").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void transferOfExactlyTheWholeBalanceSucceeds() throws Exception {
        String a = register("alice");
        register("bob");
        assertThat(transfer(a, "bob", "100", null).getResponse().getStatus()).isEqualTo(201);
        assertThat(balance("alice")).isEqualByComparingTo("0");
        assertThat(balance("bob")).isEqualByComparingTo("200");
    }

    @Test
    void transferErrorsLeaveEverythingUntouched() throws Exception {
        String a = register("alice");
        register("bob");
        assertThat(transfer(a, "alice", "1", null).getResponse().getStatus()).as("self").isEqualTo(400);
        assertThat(postJson("/api/transactions/transfer", a, map("receiverWalletId", walletId("alice"),
                "amount", bd("1")), null).getResponse().getStatus()).as("self by id").isEqualTo(400);
        assertThat(transfer(a, "nobody", "1", null).getResponse().getStatus()).as("unknown user").isEqualTo(404);
        assertThat(postJson("/api/transactions/transfer", a, map("receiverWalletId", 987654, "amount", bd("1")), null)
                .getResponse().getStatus()).as("unknown wallet").isEqualTo(404);
        assertThat(postJson("/api/transactions/transfer", a, map("amount", bd("1")), null)
                .getResponse().getStatus()).as("no receiver").isEqualTo(400);
        assertThat(balance("alice")).isEqualByComparingTo("100");
        assertThat(balance("bob")).isEqualByComparingTo("100");
        assertThat(txCount()).isZero();
    }

    @Test
    void currencyMismatchIs400AndRollsBack() throws Exception {
        String a = register("alice");
        register("bob");
        jdbc.update("update wallets set currency='EUR' where id=?", walletId("bob"));
        assertThat(transfer(a, "bob", "5", null).getResponse().getStatus()).isEqualTo(400);
        assertThat(balance("alice")).isEqualByComparingTo("100");
        assertThat(balance("bob")).isEqualByComparingTo("100");
        assertThat(txCount()).isZero();
    }

    @Test
    void overlongNoteIs400() throws Exception {
        String a = register("alice");
        register("bob");
        MvcResult r = postJson("/api/transactions/transfer", a,
                map("receiverUsername", "bob", "amount", bd("1"), "note", "x".repeat(256)), null);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(txCount()).isZero();
    }

    @Test
    void transferStoredAndReturnedTimestampsAreConsistent() throws Exception {
        String a = register("alice");
        register("bob");
        MvcResult r = transfer(a, "bob", "1", null);
        assertThat(body(r).get("createdAt").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from transactions where created_at is not null", Long.class))
                .isEqualTo(1);
    }

    // ---- idempotency ---------------------------------------------------------------------------------------------

    @Test
    void repeatedTransferWithSameKeyReturnsSameTransactionAndMovesMoneyOnce() throws Exception {
        String a = register("alice");
        register("bob");
        MvcResult first = transfer(a, "bob", "10", "k-1");
        MvcResult second = transfer(a, "bob", "10", "k-1");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(second).get("id").asLong()).isEqualTo(body(first).get("id").asLong());
        assertThat(body(second).has("idempotencyKey")).as("the key is never echoed").isFalse();
        assertThat(balance("alice")).isEqualByComparingTo("90");
        assertThat(balance("bob")).isEqualByComparingTo("110");
        assertThat(txCount()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentAmountReceiverOrNoteIs409AndDoesNotMoveMoreMoney() throws Exception {
        String a = register("alice");
        register("bob");
        register("carol");
        MvcResult first = transfer(a, "bob", "10", "k-2");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        MvcResult amount = transfer(a, "bob", "99", "k-2");
        assertThat(amount.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(amount).get("error").asText()).contains("different request");
        assertThat(transfer(a, "carol", "10", "k-2").getResponse().getStatus()).as("other receiver").isEqualTo(409);
        assertThat(postJson("/api/transactions/transfer", a,
                map("receiverUsername", "bob", "amount", bd("10"), "note", "changed"), "k-2")
                .getResponse().getStatus()).as("other note").isEqualTo(409);
        assertThat(balance("alice")).isEqualByComparingTo("90");
        assertThat(balance("carol")).isEqualByComparingTo("100");
        assertThat(txCount()).isEqualTo(1);
        // the identical request is still a replay of the original
        MvcResult same = transfer(a, "bob", "10", "k-2");
        assertThat(same.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(same).get("id").asLong()).isEqualTo(body(first).get("id").asLong());
    }

    @Test
    void replayByWalletIdAndByUsernameOfTheSameReceiverIsTheSameRequest() throws Exception {
        String a = register("alice");
        register("bob");
        long first = body(transfer(a, "bob", "10", "k-3")).get("id").asLong();
        MvcResult byId = postJson("/api/transactions/transfer", a,
                map("receiverWalletId", walletId("bob"), "amount", bd("10.0000")), "k-3");
        assertThat(byId.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(byId).get("id").asLong()).isEqualTo(first);
        assertThat(txCount()).isEqualTo(1);
    }

    @Test
    void sameKeyFromDifferentSendersIsIndependent() throws Exception {
        String a = register("alice");
        String b = register("bob");
        register("carol");
        MvcResult ra = transfer(a, "carol", "10", "shared");
        MvcResult rb = transfer(b, "carol", "10", "shared");
        assertThat(ra.getResponse().getStatus()).isEqualTo(201);
        assertThat(rb.getResponse().getStatus()).as("another users key must not block or leak").isEqualTo(201);
        assertThat(body(rb).get("id").asLong()).isNotEqualTo(body(ra).get("id").asLong());
        assertThat(balance("bob")).isEqualByComparingTo("90");
        assertThat(txCount()).isEqualTo(2);
        // each sender's own replay still returns their own transaction
        assertThat(body(transfer(b, "carol", "10", "shared")).get("id").asLong()).isEqualTo(body(rb).get("id").asLong());
        assertThat(txCount()).isEqualTo(2);
    }

    @Test
    void receiverCannotSeeOrCollideWithTheSendersKey() throws Exception {
        String a = register("alice");
        String b = register("bob");
        long id = body(transfer(a, "bob", "10", "alice-secret-key")).get("id").asLong();
        MvcResult asReceiver = getJson("/api/transactions/" + id, b);
        assertThat(asReceiver.getResponse().getStatus()).isEqualTo(200);
        assertThat(asReceiver.getResponse().getContentAsString()).doesNotContain("alice-secret-key");
        assertThat(body(asReceiver).has("idempotencyKey")).isFalse();
        MvcResult list = getJson("/api/transactions", b);
        assertThat(list.getResponse().getContentAsString()).doesNotContain("alice-secret-key");
        // the receiver can use the same string for their own request
        assertThat(transfer(b, "alice", "5", "alice-secret-key").getResponse().getStatus()).isEqualTo(201);
        assertThat(txCount()).isEqualTo(2);
    }

    @Test
    void blankIdempotencyKeyIs400ForEveryOperationAndNothingIsStored() throws Exception {
        String a = register("alice");
        register("bob");
        String adm = adminToken();
        assertThat(transfer(a, "bob", "1", "  ").getResponse().getStatus()).isEqualTo(400);
        assertThat(withdraw(a, "1", "  ").getResponse().getStatus()).isEqualTo(400);
        assertThat(adminDeposit(adm, "alice", "1", "  ").getResponse().getStatus()).isEqualTo(400);
        assertThat(txCount()).isZero();
        assertThat(balance("alice")).isEqualByComparingTo("100");
    }

    @Test
    void depositAndWithdrawIdempotencyReplayDoesNotDoubleApply() throws Exception {
        String t = register("alice");
        String adm = adminToken();
        MvcResult d1 = adminDeposit(adm, "alice", "20", "dep-1");
        MvcResult d2 = adminDeposit(adm, "alice", "20", "dep-1");
        assertThat(body(d2).get("id").asLong()).isEqualTo(body(d1).get("id").asLong());
        assertThat(balance("alice")).isEqualByComparingTo("120");

        MvcResult w1 = withdraw(t, "20", "wd-1");
        MvcResult w2 = withdraw(t, "20", "wd-1");
        assertThat(body(w2).get("id").asLong()).isEqualTo(body(w1).get("id").asLong());
        assertThat(balance("alice")).isEqualByComparingTo("100");
        assertThat(txCount()).isEqualTo(2);
        // same key, different amount: refused, nothing applied
        assertThat(adminDeposit(adm, "alice", "21", "dep-1").getResponse().getStatus()).isEqualTo(409);
        assertThat(withdraw(t, "21", "wd-1").getResponse().getStatus()).isEqualTo(409);
        assertThat(balance("alice")).isEqualByComparingTo("100");
        assertThat(txCount()).isEqualTo(2);
    }

    @Test
    void keyReusedForADifferentOperationTypeIs409() throws Exception {
        String t = register("alice");
        register("bob");
        assertThat(adminDeposit(adminToken(), "alice", "5", "op-key").getResponse().getStatus()).isEqualTo(201);
        assertThat(withdraw(t, "5", "op-key").getResponse().getStatus()).isEqualTo(409);
        assertThat(balance("alice")).isEqualByComparingTo("105");
        // transfer replay with a key that belongs to a deposit by the same wallet: deposit has null sender, so conflict
        assertThat(transfer(t, "bob", "5", "op-key").getResponse().getStatus()).isEqualTo(409);
        assertThat(balance("alice")).isEqualByComparingTo("105");
        assertThat(txCount()).isEqualTo(1);
    }

    @Test
    void depositKeysAreScopedPerWalletAndAWithdrawalReplayIsNeverReturnedAsATransfer() throws Exception {
        register("alice");
        String b = register("bob");
        String adm = adminToken();
        assertThat(adminDeposit(adm, "alice", "5", "dk").getResponse().getStatus()).isEqualTo(201);
        assertThat(adminDeposit(adm, "bob", "5", "dk").getResponse().getStatus()).isEqualTo(201);
        assertThat(balance("bob")).isEqualByComparingTo("105");
        // bob withdraws with key "wk"; replaying "wk" as a TRANSFER must be refused, not answered with the withdrawal
        assertThat(withdraw(b, "5", "wk").getResponse().getStatus()).isEqualTo(201);
        MvcResult asTransfer = transfer(b, "alice", "5", "wk");
        assertThat(asTransfer.getResponse().getStatus()).isEqualTo(409);
        assertThat(balance("bob")).isEqualByComparingTo("100");
        assertThat(balance("alice")).isEqualByComparingTo("105");
    }

    @Test
    void idempotencyKeyUniqueConstraintIsEnforcedByTheDatabase() throws Exception {
        String a = register("alice");
        register("bob");
        transfer(a, "bob", "1", "dup");
        long w1 = walletId("alice");
        long w2 = walletId("bob");
        assertThatThrownBy(() -> jdbc.update("insert into transactions(sender_wallet_id,receiver_wallet_id,amount,"
                + "type,status,idempotency_key) values (?,?,1,'TRANSFER','COMPLETED','dup')", w1, w2))
                .isInstanceOf(DataIntegrityViolationException.class);
        // the same key under another owner is allowed (keys are unique per owning wallet, not globally)
        jdbc.update("insert into transactions(sender_wallet_id,receiver_wallet_id,amount,type,status,idempotency_key) "
                + "values (?,?,1,'TRANSFER','COMPLETED','dup')", w2, w1);
        // NULL keys may repeat
        jdbc.update("insert into transactions(sender_wallet_id,receiver_wallet_id,amount,type,status) "
                + "values (?,?,1,'TRANSFER','COMPLETED')", w1, w2);
        jdbc.update("insert into transactions(sender_wallet_id,receiver_wallet_id,amount,type,status) "
                + "values (?,?,1,'TRANSFER','COMPLETED')", w1, w2);
        assertThat(txCount()).isEqualTo(4);
        // and the old global UNIQUE constraint is gone
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid='transactions'::regclass "
                + "and contype='u'", Long.class)).isZero();
    }

    @Test
    void overlongIdempotencyKeyDoesNotCauseServerError() throws Exception {
        String a = register("alice");
        register("bob");
        MvcResult r = transfer(a, "bob", "1", "k".repeat(101));
        assertThat(r.getResponse().getStatus()).as("key longer than the VARCHAR(100) column").isEqualTo(400);
        assertThat(body(r).get("error").asText()).contains("Idempotency-Key");
        assertThat(balance("alice")).isEqualByComparingTo("100");
        assertThat(txCount()).isZero();
    }

    // ---- schema constraints (defence in depth) -------------------------------------------------------------------

    @Test
    void databaseRejectsNegativeBalanceNonPositiveAmountAndInconsistentTypeWalletCombos() throws Exception {
        String a = register("alice");
        register("bob");
        long wa = walletId("alice");
        long wb = walletId("bob");
        assertThatThrownBy(() -> jdbc.update("update wallets set balance=-0.0001 where id=?", wa))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into transactions(sender_wallet_id,receiver_wallet_id,amount,"
                + "type,status) values (?,?,0,'TRANSFER','COMPLETED')", wa, wb))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into transactions(sender_wallet_id,receiver_wallet_id,amount,"
                + "type,status) values (?,?,1,'DEPOSIT','COMPLETED')", wa, wb))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into transactions(sender_wallet_id,receiver_wallet_id,amount,"
                + "type,status) values (null,null,1,'TRANSFER','COMPLETED')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into transactions(sender_wallet_id,receiver_wallet_id,amount,"
                + "type,status) values (?,999999,1,'TRANSFER','COMPLETED')", wa))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(a).isNotNull();
    }
}
