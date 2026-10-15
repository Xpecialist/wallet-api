package com.example.wallet.service;

import com.example.wallet.entity.Role;
import com.example.wallet.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the initial admin account at application startup. It does nothing if {@code app.admin.password}
 * (environment variable {@code ADMIN_PASSWORD}) is empty, or if a user with the configured admin username
 * already exists, so it never overwrites an existing account. The admin gets a wallet like any other user.
 */
@Component
public class AdminBootstrap implements CommandLineRunner {
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
     * Runs once after startup and creates the admin user if configured and absent.
     *
     * @param args command-line arguments (unused)
     */
    @Override
    public void run(String... args) {
        if (password == null || password.isBlank() || users.existsByUsername(username)) return;
        authService.createUser(username, email, password, Role.ADMIN);
    }
}
