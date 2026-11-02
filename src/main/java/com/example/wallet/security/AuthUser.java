package com.example.wallet.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** The principal loaded from the database: Spring's user plus the stored token version used to revoke tokens. */
public class AuthUser extends User {
    private final long tokenVersion;

    /**
     * @param username     the stored username
     * @param password     the password hash
     * @param enabled      {@code false} for a disabled account
     * @param authorities  granted authorities (ROLE_ prefixed)
     * @param tokenVersion the user's current token version
     */
    public AuthUser(String username, String password, boolean enabled,
                    Collection<? extends GrantedAuthority> authorities, long tokenVersion) {
        super(username, password, enabled, true, true, true, authorities);
        this.tokenVersion = tokenVersion;
    }

    /** @return the token version stored for the user when this object was loaded */
    public long getTokenVersion() { return tokenVersion; }
}
