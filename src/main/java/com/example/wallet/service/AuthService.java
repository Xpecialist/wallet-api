package com.example.wallet.service;

import com.example.wallet.dto.AuthResponse;
import com.example.wallet.dto.LoginRequest;
import com.example.wallet.dto.RegisterRequest;
import com.example.wallet.entity.Role;
import com.example.wallet.entity.User;
import com.example.wallet.entity.Wallet;
import com.example.wallet.exception.ApiException;
import com.example.wallet.repository.UserRepository;
import com.example.wallet.repository.WalletRepository;
import com.example.wallet.security.AuthUser;
import com.example.wallet.security.JwtService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Handles user registration (including wallet creation) and login. */
@Service
public class AuthService {
    private final UserRepository users;
    private final WalletRepository wallets;
    private final PasswordEncoder encoder;
    private final AuthenticationManager authManager;
    private final JwtService jwt;
    private final BigDecimal initialBalance;
    private final String currency;
    private final String adminUsername;
    private final String adminEmail;

    /**
     * Creates the service.
     *
     * @param users          user repository
     * @param wallets        wallet repository
     * @param encoder        password encoder
     * @param authManager    authentication manager used for login
     * @param jwt            token service
     * @param initialBalance balance given to every new wallet ({@code app.wallet.initial-balance}, default 0)
     * @param currency       currency of every new wallet ({@code app.wallet.currency})
     * @param adminUsername  reserved admin username ({@code app.admin.username}); registration cannot take it
     * @param adminEmail     reserved admin email ({@code app.admin.email}); registration cannot take it
     */
    public AuthService(UserRepository users, WalletRepository wallets, PasswordEncoder encoder,
                       AuthenticationManager authManager, JwtService jwt,
                       @Value("${app.wallet.initial-balance}") BigDecimal initialBalance,
                       @Value("${app.wallet.currency}") String currency,
                       @Value("${app.admin.username:}") String adminUsername,
                       @Value("${app.admin.email:}") String adminEmail) {
        this.users = users;
        this.wallets = wallets;
        this.encoder = encoder;
        this.authManager = authManager;
        this.jwt = jwt;
        this.initialBalance = initialBalance;
        this.currency = currency;
        this.adminUsername = adminUsername == null ? "" : adminUsername.trim();
        this.adminEmail = adminEmail == null ? "" : adminEmail.trim();
    }

    /**
     * Registers a new user with role {@link Role#USER}, creates their wallet, and returns a token so the user is
     * logged in immediately. Username and email are trimmed; they are unique ignoring case.
     *
     * @param req the registration data
     * @return a response containing a JWT for the new user
     * @throws ApiException 400 if the trimmed username is not 3 to 50 characters; 409 if the username or email is
     *         already in use or reserved for the admin
     */
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String username = req.username().trim();
        if (username.length() < 3 || username.length() > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "username: size must be between 3 and 50");
        }
        User u = createUser(username, req.email(), req.password(), Role.USER);
        return new AuthResponse(jwt.generateToken(u.getUsername(), u.getTokenVersion()));
    }

    /**
     * Creates a user with a BCrypt-hashed password and a wallet holding the configured initial balance, in one
     * transaction. Also used by {@link AdminBootstrap} to create the admin. Username and email are trimmed (the
     * given casing is kept for display). A non-admin cannot take the reserved admin username or email.
     *
     * @param username the unique username
     * @param email    the unique email
     * @param password the raw password, hashed before storing
     * @param role     the role to assign
     * @return the saved user
     * @throws ApiException 409 if the username or email is already in use (ignoring case) or reserved
     */
    @Transactional
    public User createUser(String username, String email, String password, Role role) {
        username = username.trim();
        email = email.trim();
        boolean reservedName = role != Role.ADMIN && username.equalsIgnoreCase(adminUsername);
        boolean reservedMail = role != Role.ADMIN && email.equalsIgnoreCase(adminEmail);
        if (reservedName || users.existsByUsername(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "Username already taken");
        }
        if (reservedMail || users.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "Email already registered");
        }
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(password));
        u.setRole(role);
        u = users.save(u);

        Wallet w = new Wallet();
        w.setUser(u);
        w.setBalance(initialBalance);
        w.setCurrency(currency);
        wallets.save(w);
        return u;
    }

    /**
     * Verifies the credentials and issues a token. The username is trimmed and matched ignoring case; the token
     * carries the stored username.
     *
     * @param req the login data
     * @return a response containing a JWT
     * @throws org.springframework.security.core.AuthenticationException if the credentials are wrong (including a
     *         password over 72 bytes) or the account is disabled (all mapped to the same 401 by {@code GlobalExceptionHandler})
     */
    public AuthResponse login(LoginRequest req) {
        // BCrypt only reads the first 72 bytes, so a longer password would match by its prefix. Passwords over 72 bytes
        // cannot be registered, so none can be correct: same 401 as any other failure, never truncated.
        if (req.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadCredentialsException("Password too long");
        }
        Authentication auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.username().trim(), req.password()));
        // the version comes from the same load that checked the password, so a change in between can only make the
        // new token stale (rejected), never valid for an old password
        if (!(auth.getPrincipal() instanceof AuthUser stored)) throw new IllegalStateException("Unexpected principal");
        long version = stored.getTokenVersion();
        return new AuthResponse(jwt.generateToken(auth.getName(), version));
    }

    /**
     * Logs the user out of every device: bumps the token version with one atomic UPDATE, so all tokens issued so
     * far (this one included) are rejected from the next request on.
     *
     * @param username the authenticated user
     */
    @Transactional
    public void logout(String username) {
        users.findByUsername(username).ifPresent(u -> users.bumpTokenVersion(u.getId()));
    }
}
