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
import com.example.wallet.security.JwtService;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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

    /**
     * Creates the service.
     *
     * @param users          user repository
     * @param wallets        wallet repository
     * @param encoder        password encoder
     * @param authManager    authentication manager used for login
     * @param jwt            token service
     * @param initialBalance balance given to every new wallet ({@code app.wallet.initial-balance})
     * @param currency       currency of every new wallet ({@code app.wallet.currency})
     */
    public AuthService(UserRepository users, WalletRepository wallets, PasswordEncoder encoder,
                       AuthenticationManager authManager, JwtService jwt,
                       @Value("${app.wallet.initial-balance}") BigDecimal initialBalance,
                       @Value("${app.wallet.currency}") String currency) {
        this.users = users;
        this.wallets = wallets;
        this.encoder = encoder;
        this.authManager = authManager;
        this.jwt = jwt;
        this.initialBalance = initialBalance;
        this.currency = currency;
    }

    /**
     * Registers a new user with role {@link Role#USER}, creates their wallet, and returns a token so the user is
     * logged in immediately.
     *
     * @param req the registration data
     * @return a response containing a JWT for the new user
     * @throws ApiException 409 if the username or email is already in use
     */
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        createUser(req.username(), req.email(), req.password(), Role.USER);
        return new AuthResponse(jwt.generateToken(req.username()));
    }

    /**
     * Creates a user with a BCrypt-hashed password and a wallet holding the configured initial balance, in one
     * transaction. Also used by {@link AdminBootstrap} to create the admin.
     *
     * @param username the unique username
     * @param email    the unique email
     * @param password the raw password, hashed before storing
     * @param role     the role to assign
     * @return the saved user
     * @throws ApiException 409 if the username or email is already in use
     */
    @Transactional
    public User createUser(String username, String email, String password, Role role) {
        if (users.existsByUsername(username)) throw new ApiException(HttpStatus.CONFLICT, "Username already taken");
        if (users.existsByEmail(email)) throw new ApiException(HttpStatus.CONFLICT, "Email already registered");
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
     * Verifies the credentials and issues a token.
     *
     * @param req the login data
     * @return a response containing a JWT
     * @throws org.springframework.security.core.AuthenticationException if the credentials are wrong (mapped to 401
     *         by {@code GlobalExceptionHandler} for bad credentials)
     */
    public AuthResponse login(LoginRequest req) {
        authManager.authenticate(new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        return new AuthResponse(jwt.generateToken(req.username()));
    }
}
