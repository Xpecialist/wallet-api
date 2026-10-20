package com.example.wallet;

import com.example.wallet.entity.Role;
import com.example.wallet.entity.User;
import com.example.wallet.entity.Wallet;
import java.math.BigDecimal;
import org.springframework.test.util.ReflectionTestUtils;

/** Builders for entities whose ids are normally assigned by the database. */
public final class TestData {
    private TestData() {}

    public static User user(long id, String username, Role role) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setPasswordHash("hash-" + username);
        u.setRole(role);
        return u;
    }

    public static Wallet wallet(long id, User owner, String balance, String currency) {
        Wallet w = new Wallet();
        ReflectionTestUtils.setField(w, "id", id);
        w.setUser(owner);
        w.setBalance(new BigDecimal(balance));
        w.setCurrency(currency);
        return w;
    }
}
