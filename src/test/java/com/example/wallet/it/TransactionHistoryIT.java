package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * History / admin listing: pagination, sorting and every filter of the Specification, against real rows with
 * controlled timestamps and statuses.
 *
 * <p>Dataset (alice = A, bob = B, carol = C); ids are assigned in this order:
 * <pre>
 *  #1 A->B  10  TRANSFER   COMPLETED 2024-01-10 10:00:00
 *  #2 B->A  20  TRANSFER   PENDING   2024-01-15 23:59:59
 *  #3 A     30  DEPOSIT    COMPLETED 2024-02-01 00:00:00
 *  #4 A     40  WITHDRAWAL FAILED    2024-02-10 12:00:00
 *  #5 C->B   5  TRANSFER   COMPLETED 2024-01-20 08:00:00   (alice not involved)
 *  #6 B->A  50  TRANSFER   COMPLETED 2024-03-01 09:00:00
 * </pre>
 */
class TransactionHistoryIT extends ItBase {
    String alice, bob, carol, admin;
    List<Long> ids;

    @BeforeEach
    void seed() throws Exception {
        alice = register("alice");
        bob = register("bob");
        carol = register("carol");
        admin = adminToken();
        transfer(alice, "bob", "10", null);
        transfer(bob, "alice", "20", null);
        adminDeposit(admin, "alice", "30", null);
        withdraw(alice, "40", null);
        transfer(carol, "bob", "5", null);
        transfer(bob, "alice", "50", null);
        ids = jdbc.queryForList("select id from transactions order by id", Long.class);
        assertThat(ids).hasSize(6);
        setMeta(0, "2024-01-10 10:00:00", "COMPLETED");
        setMeta(1, "2024-01-15 23:59:59", "PENDING");
        setMeta(2, "2024-02-01 00:00:00", "COMPLETED");
        setMeta(3, "2024-02-10 12:00:00", "FAILED");
        setMeta(4, "2024-01-20 08:00:00", "COMPLETED");
        setMeta(5, "2024-03-01 09:00:00", "COMPLETED");
    }

    private void setMeta(int idx, String ts, String status) {
        jdbc.update("update transactions set created_at = ?::timestamp, status = ? where id = ?",
                ts, status, ids.get(idx));
    }

    /** Ids (as dataset numbers 1..6) of the content of a response, in order. */
    private List<Integer> nums(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        List<Integer> out = new ArrayList<>();
        for (JsonNode n : body(r).get("content")) out.add(ids.indexOf(n.get("id").asLong()) + 1);
        return out;
    }

    private List<Integer> mine(String query) throws Exception {
        return nums(getJson("/api/transactions" + query, alice));
    }

    private List<Integer> all(String query) throws Exception {
        return nums(getJson("/api/admin/transactions" + query, admin));
    }

    // ---- scoping -------------------------------------------------------------------------------------------------

    @Test
    void userSeesOnlyTransactionsOfOwnWalletNewestFirstByDefault() throws Exception {
        assertThat(mine("")).containsExactly(6, 4, 3, 2, 1);
    }

    @Test
    void otherUserSeesTheirOwnScope() throws Exception {
        assertThat(nums(getJson("/api/transactions", carol))).containsExactly(5);
        assertThat(nums(getJson("/api/transactions", bob))).containsExactly(6, 5, 2, 1);
    }

    @Test
    void adminSeesEverythingNewestFirst() throws Exception {
        assertThat(all("")).containsExactly(6, 4, 3, 5, 2, 1);
    }

    @Test
    void newUserWithNoActivityGetsEmptyPage() throws Exception {
        String dave = register("dave");
        JsonNode b = body(getJson("/api/transactions", dave));
        assertThat(b.get("content")).isEmpty();
        assertThat(b.get("totalElements").asLong()).isZero();
        assertThat(b.get("totalPages").asInt()).isZero();
    }

    // ---- pagination -----------------------------------------------------------------------------------------------

    @Test
    void explicitPageSizeSplitsResultsAndReportsTotals() throws Exception {
        JsonNode p0 = body(getJson("/api/transactions?size=2&page=0", alice));
        assertThat(p0.get("size").asInt()).isEqualTo(2);
        assertThat(p0.get("page").asInt()).isZero();
        assertThat(p0.get("totalElements").asLong()).isEqualTo(5);
        assertThat(p0.get("totalPages").asInt()).isEqualTo(3);
        assertThat(mine("?size=2&page=0")).containsExactly(6, 4);
        assertThat(mine("?size=2&page=1")).containsExactly(3, 2);
        assertThat(mine("?size=2&page=2")).containsExactly(1);
        assertThat(mine("?size=2&page=3")).isEmpty();
    }

    @Test
    void sizeAboveMaxIsCappedAt100() throws Exception {
        JsonNode b = body(getJson("/api/transactions?size=500", alice));
        assertThat(b.get("size").asInt()).isEqualTo(100);
        assertThat(b.get("content")).hasSize(5);
    }

    /**
     * Regression test (formerly KNOWN_BUG_...): documented default page size is 20 (spring.data.web.pageable.default-page-size and
     * README) but the controllers' {@code @PageableDefault} resolves to Spring's own default of 10.
     * Asserts the DOCUMENTED behaviour; failed before the @PageableDefault size fix.
     */
    @Test
    void defaultPageSizeIs20_userHistory() throws Exception {
        for (int i = 0; i < 20; i++) adminDeposit(admin, "alice", "1", null); // alice now has 25 transactions
        JsonNode b = body(getJson("/api/transactions", alice));
        assertThat(b.get("totalElements").asLong()).isEqualTo(25);
        assertThat(b.get("size").asInt()).as("default page size (documented: 20)").isEqualTo(20);
        assertThat(b.get("content")).as("rows on first page with default size").hasSize(20);
    }

    /** Regression test (formerly KNOWN_BUG_..., same root cause), admin transactions list. */
    @Test
    void defaultPageSizeIs20_adminTransactions() throws Exception {
        for (int i = 0; i < 20; i++) adminDeposit(admin, "alice", "1", null); // 26 transactions in total
        JsonNode b = body(getJson("/api/admin/transactions", admin));
        assertThat(b.get("size").asInt()).as("default page size (documented: 20)").isEqualTo(20);
        assertThat(b.get("content")).hasSize(20);
    }

    /** Regression test (formerly KNOWN_BUG_..., same root cause), admin users list. */
    @Test
    void defaultPageSizeIs20_adminUsers() throws Exception {
        for (int i = 0; i < 12; i++) register("extra" + i); // 4 + 12 = 16 users... plus 6 more below
        for (int i = 12; i < 18; i++) register("extra" + i); // 22 users total
        JsonNode b = body(getJson("/api/admin/users", admin));
        assertThat(b.get("totalElements").asLong()).isEqualTo(22);
        assertThat(b.get("size").asInt()).as("default page size (documented: 20)").isEqualTo(20);
        assertThat(b.get("content")).hasSize(20);
    }

    // ---- sorting ---------------------------------------------------------------------------------------------------

    @Test
    void sortByCreatedAtAscAndDesc() throws Exception {
        assertThat(mine("?sort=createdAt,asc")).containsExactly(1, 2, 3, 4, 6);
        assertThat(mine("?sort=createdAt,desc")).containsExactly(6, 4, 3, 2, 1);
    }

    @Test
    void sortByAmountAscAndDesc() throws Exception {
        assertThat(mine("?sort=amount,asc")).containsExactly(1, 2, 3, 4, 6);
        assertThat(mine("?sort=amount,desc")).containsExactly(6, 4, 3, 2, 1);
        assertThat(all("?sort=amount,asc")).containsExactly(5, 1, 2, 3, 4, 6);
    }

    @Test
    void sortByTypeThenAmountDesc() throws Exception {
        // DEPOSIT < TRANSFER < WITHDRAWAL alphabetically
        assertThat(mine("?sort=type,asc&sort=amount,desc")).containsExactly(3, 6, 2, 1, 4);
    }

    @Test
    void sortingIsAppliedBeforePaging() throws Exception {
        assertThat(mine("?sort=amount,desc&size=2&page=0")).containsExactly(6, 4);
        assertThat(mine("?sort=amount,desc&size=2&page=1")).containsExactly(3, 2);
    }

    @Test
    void invalidSortPropertyIs400NotServerError() throws Exception {
        assertThat(getJson("/api/transactions?sort=doesNotExist,asc", alice).getResponse().getStatus()).isEqualTo(400);
        assertThat(getJson("/api/admin/transactions?sort=nope", admin).getResponse().getStatus()).isEqualTo(400);
    }

    // ---- filters (each alone) --------------------------------------------------------------------------------------

    @Test
    void filterByType() throws Exception {
        assertThat(mine("?type=TRANSFER")).containsExactly(6, 2, 1);
        assertThat(mine("?type=DEPOSIT")).containsExactly(3);
        assertThat(mine("?type=WITHDRAWAL")).containsExactly(4);
        assertThat(all("?type=TRANSFER")).containsExactly(6, 5, 2, 1);
    }

    @Test
    void filterByStatus() throws Exception {
        assertThat(mine("?status=COMPLETED")).containsExactly(6, 3, 1);
        assertThat(mine("?status=PENDING")).containsExactly(2);
        assertThat(mine("?status=FAILED")).containsExactly(4);
        assertThat(all("?status=COMPLETED")).containsExactly(6, 3, 5, 1);
    }

    @Test
    void filterByMinAndMaxAmountAreInclusive() throws Exception {
        assertThat(mine("?minAmount=30")).containsExactly(6, 4, 3);
        assertThat(mine("?maxAmount=30")).containsExactly(3, 2, 1);
        assertThat(mine("?minAmount=20&maxAmount=40")).containsExactly(4, 3, 2);
        assertThat(mine("?minAmount=30&maxAmount=30")).containsExactly(3);
        assertThat(mine("?minAmount=51")).isEmpty();
        assertThat(mine("?minAmount=40&maxAmount=20")).isEmpty();
    }

    @Test
    void filterByFromDateIsInclusiveFromStartOfDay() throws Exception {
        assertThat(mine("?from=2024-02-01")).containsExactly(6, 4, 3);
        assertThat(mine("?from=2024-01-15")).containsExactly(6, 4, 3, 2);
        assertThat(mine("?from=2024-03-02")).isEmpty();
    }

    @Test
    void filterByToDateIsInclusiveThroughEndOfDay() throws Exception {
        // #2 is at 23:59:59 on the 15th and must be included by to=2024-01-15
        assertThat(mine("?to=2024-01-15")).containsExactly(2, 1);
        assertThat(mine("?to=2024-01-14")).containsExactly(1);
        // #3 is exactly at midnight starting 2024-02-01: excluded by to=2024-01-31, included by to=2024-02-01
        assertThat(mine("?to=2024-01-31")).containsExactly(2, 1);
        assertThat(mine("?to=2024-02-01")).containsExactly(3, 2, 1);
        assertThat(mine("?to=2024-01-09")).isEmpty();
    }

    @Test
    void filterByDateRange() throws Exception {
        assertThat(mine("?from=2024-01-15&to=2024-02-01")).containsExactly(3, 2);
        assertThat(mine("?from=2024-01-10&to=2024-01-10")).containsExactly(1);
        assertThat(mine("?from=2024-02-02&to=2024-01-01")).isEmpty();
    }

    // ---- filters combined ------------------------------------------------------------------------------------------

    @Test
    void allFiltersAreAndedTogether() throws Exception {
        assertThat(mine("?type=TRANSFER&status=COMPLETED")).containsExactly(6, 1);
        assertThat(mine("?type=TRANSFER&status=COMPLETED&minAmount=20")).containsExactly(6);
        assertThat(mine("?type=TRANSFER&status=COMPLETED&minAmount=5&maxAmount=15&from=2024-01-01&to=2024-01-31"))
                .containsExactly(1);
        assertThat(mine("?type=DEPOSIT&status=FAILED")).isEmpty();
        assertThat(all("?type=TRANSFER&minAmount=5&maxAmount=20&from=2024-01-15&to=2024-01-31"))
                .containsExactly(5, 2);
    }

    @Test
    void filtersCombineWithSortingAndPaging() throws Exception {
        assertThat(mine("?type=TRANSFER&sort=amount,desc&size=2&page=0")).containsExactly(6, 2);
        assertThat(mine("?type=TRANSFER&sort=amount,desc&size=2&page=1")).containsExactly(1);
        JsonNode b = body(getJson("/api/transactions?type=TRANSFER&size=2", alice));
        assertThat(b.get("totalElements").asLong()).isEqualTo(3);
        assertThat(b.get("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    void filterDoesNotLeakOtherUsersTransactions() throws Exception {
        // #5 (carol->bob, 5.00) matches the filter but alice is not a participant
        assertThat(mine("?maxAmount=5")).isEmpty();
        assertThat(all("?maxAmount=5")).containsExactly(5);
    }

    @Test
    void invalidFilterValuesAre400() throws Exception {
        assertThat(getJson("/api/transactions?status=BOGUS", alice).getResponse().getStatus()).isEqualTo(400);
        assertThat(getJson("/api/transactions?type=BOGUS", alice).getResponse().getStatus()).isEqualTo(400);
        assertThat(getJson("/api/transactions?from=not-a-date", alice).getResponse().getStatus()).isEqualTo(400);
        assertThat(getJson("/api/transactions?minAmount=abc", alice).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void responseFieldsMatchStoredRows() throws Exception {
        JsonNode first = body(getJson("/api/transactions?sort=createdAt,asc&size=1", alice)).get("content").get(0);
        assertThat(first.get("id").asLong()).isEqualTo(ids.get(0));
        assertThat(first.get("senderWalletId").asLong()).isEqualTo(walletId("alice"));
        assertThat(first.get("receiverWalletId").asLong()).isEqualTo(walletId("bob"));
        assertThat(first.get("amount").decimalValue()).isEqualByComparingTo("10");
        assertThat(first.get("type").asText()).isEqualTo("TRANSFER");
        assertThat(first.get("createdAt").asText()).startsWith("2024-01-10T10:00:00");
    }
}
