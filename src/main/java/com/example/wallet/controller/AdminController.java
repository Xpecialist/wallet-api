package com.example.wallet.controller;

import com.example.wallet.dto.TransactionResponse;
import com.example.wallet.dto.UserResponse;
import com.example.wallet.repository.UserRepository;
import com.example.wallet.service.TransactionService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** Endpoints under {@code /api/admin}, restricted to users with the ADMIN role by {@code SecurityConfig}. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final UserRepository users;
    private final TransactionService transactions;

    /**
     * Creates the controller.
     *
     * @param users        user repository
     * @param transactions transaction service
     */
    public AdminController(UserRepository users, TransactionService transactions) {
        this.users = users;
        this.transactions = transactions;
    }

    /**
     * {@code GET /api/admin/users}. Admin only. Lists all users (without password hashes).
     *
     * @return all users (errors: 401 if not authenticated, 403 if not an admin)
     */
    @GetMapping("/users")
    public List<UserResponse> users() { return users.findAll().stream().map(UserResponse::from).toList(); }

    /**
     * {@code GET /api/admin/transactions}. Admin only. Lists all transactions.
     *
     * @return all transactions (errors: 401 if not authenticated, 403 if not an admin)
     */
    @GetMapping("/transactions")
    public List<TransactionResponse> transactions() { return transactions.listAll(); }
}
