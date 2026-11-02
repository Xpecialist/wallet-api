package com.example.wallet.service;

import com.example.wallet.entity.Role;
import com.example.wallet.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the initial admin account at application startup. It does nothing if {@code app.admin.password}
 * (environment variable {@code ADMIN_PASSWORD}) is empty, or if a user with the configured admin username
 * already exists, so it never overwrites an existing account. The admin gets a wallet like any other user.
 * The admin username and email are reserved (registration cannot take them). The password must be 12 to 72 bytes
 * long; a weaker one, or a conflicting account, is logged as an error and the admin is NOT created, but startup
 * continues.
 */
@Component
public class AdminBootstrap implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    static final int MIN_PASSWORD_LENGTH = 12;
    static final int MAX_PASSWORD_BYTES = 72;

    private final AuthService authService;
    private final UserRepository users;
    private final String username;
    private final String email;
    private final String password;

    /**
     * Creates the runner.
     *
     * @param authService used to create the admin user and wallet
     * @param users       used to check whether the admin already exists
     * @param username    admin username ({@code app.admin.username})
     * @param email       admin email ({@code app.admin.email})
     * @param password    admin password ({@code app.admin.password}); empty disables bootstrapping
     */
    public AdminBootstrap(AuthService authService, UserRepository users,
                          @Value("${app.admin.username}") String username,
                          @Value("${app.admin.email}") String email,
                          @Value("${app.admin.password:}") String password) {
        this.authService = authService;
        this.users = users;
        this.username = username;
        this.email = email;
        this.password = password;
    }

    /**
     * Runs once after startup and creates the admin user if configured and absent. Never throws for a configuration
     * or data problem; it logs an error naming the cause instead.
     *
     * @param args command-line arguments (unused)
     */
    @Override
    public void run(String... args) {
        if (password == null || password.isBlank()) return;
        if (password.length() < MIN_PASSWORD_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            log.error("Admin NOT created: ADMIN_PASSWORD must be at least {} characters and at most {} bytes",
                    MIN_PASSWORD_LENGTH, MAX_PASSWORD_BYTES);
            return;
        }
        var existing = users.findByUsername(username);
        if (existing.isPresent()) {
            if (existing.get().getRole() != Role.ADMIN) {
                log.error("Admin NOT created: the reserved admin username '{}' is already taken by a non-admin "
                        + "account. Rename or remove that account, or set app.admin.username to another name.",
                        username);
            }
            return;
        }
        if (users.existsByEmail(email)) {
            log.error("Admin NOT created: the admin email '{}' is already registered to another account. "
                    + "Change that account's email or set app.admin.email to another address.", email);
            return;
        }
        try {
            authService.createUser(username, email, password, Role.ADMIN);
        } catch (RuntimeException e) {
            log.error("Admin NOT created: {}", e.getMessage());
        }
    }
}
