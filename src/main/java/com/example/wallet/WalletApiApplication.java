package com.example.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the wallet REST API (Spring Boot application). */
@SpringBootApplication
public class WalletApiApplication {
    /**
     * Starts the application.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(WalletApiApplication.class, args);
    }
}
