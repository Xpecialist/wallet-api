package com.example.wallet.service;

import com.example.wallet.exception.ApiException;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

/** Restricts the {@code sort} parameter of paged endpoints to an explicit set of properties. */
final class SortWhitelist {
    static final Set<String> USERS = Set.of("id", "username", "email", "role", "enabled", "createdAt");
    static final Set<String> TRANSACTIONS = Set.of("id", "createdAt", "amount", "type", "status",
            "senderWalletId", "receiverWalletId");
    static final Set<String> DEPOSIT_REQUESTS = Set.of("id", "createdAt", "amount", "status", "decidedAt");

    private SortWhitelist() {}

    /**
     * Rejects a request that sorts by a property outside the whitelist (for example {@code passwordHash}).
     *
     * @param pageable the requested page, possibly sorted
     * @param allowed  the sortable property names
     * @throws ApiException 400 if any sort property is not allowed
     */
    static void check(Pageable pageable, Set<String> allowed) {
        for (var order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid sort parameter");
            }
        }
    }
}
