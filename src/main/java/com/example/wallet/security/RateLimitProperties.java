package com.example.wallet.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Rate-limit settings ({@code app.rate-limit.*}). Every rule is {@code capacity} requests per {@code window}, refilled
 * continuously (see {@link TokenBucketLimiter}). The defaults are documented in README.md.
 */
@Component
@ConfigurationProperties("app.rate-limit")
public class RateLimitProperties {
    /** One limit: {@code capacity} requests per {@code window}. */
    public static class Rule {
        private int capacity;
        private Duration window;

        /**
         * Creates a rule.
         *
         * @param capacity requests allowed per window (burst size)
         * @param window   time to refill the whole capacity
         */
        public Rule(int capacity, Duration window) {
            this.capacity = capacity;
            this.window = window;
        }

        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
    }

    private boolean enabled = true;
    private boolean trustForwardedHeaders = false;
    private int trustedProxies = 1;
    private int maxKeys = 50_000;
    private Rule loginIp = new Rule(5, Duration.ofMinutes(1));
    private Rule loginUsernameIp = new Rule(5, Duration.ofMinutes(1));
    private Rule loginUsernameFailures = new Rule(30, Duration.ofMinutes(1));
    private Duration knownLoginTtl = Duration.ofDays(7);
    private int knownLoginMax = 10_000;
    private Rule registerIp = new Rule(5, Duration.ofHours(1));
    private Rule passwordUser = new Rule(5, Duration.ofMinutes(15));
    private Rule passwordIp = new Rule(5, Duration.ofMinutes(15));
    private Rule moneyUser = new Rule(30, Duration.ofMinutes(1));
    private Rule adminUser = new Rule(120, Duration.ofMinutes(1));
    private Rule globalIp = new Rule(300, Duration.ofMinutes(1));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isTrustForwardedHeaders() { return trustForwardedHeaders; }
    public void setTrustForwardedHeaders(boolean v) { this.trustForwardedHeaders = v; }
    public int getTrustedProxies() { return trustedProxies; }
    public void setTrustedProxies(int trustedProxies) { this.trustedProxies = trustedProxies; }
    public int getMaxKeys() { return maxKeys; }
    public void setMaxKeys(int maxKeys) { this.maxKeys = maxKeys; }
    public Rule getLoginIp() { return loginIp; }
    public void setLoginIp(Rule r) { this.loginIp = r; }
    public Rule getLoginUsernameIp() { return loginUsernameIp; }
    public void setLoginUsernameIp(Rule r) { this.loginUsernameIp = r; }
    public Rule getLoginUsernameFailures() { return loginUsernameFailures; }
    public void setLoginUsernameFailures(Rule r) { this.loginUsernameFailures = r; }
    public Duration getKnownLoginTtl() { return knownLoginTtl; }
    public void setKnownLoginTtl(Duration d) { this.knownLoginTtl = d; }
    public int getKnownLoginMax() { return knownLoginMax; }
    public void setKnownLoginMax(int n) { this.knownLoginMax = n; }
    public Rule getRegisterIp() { return registerIp; }
    public void setRegisterIp(Rule r) { this.registerIp = r; }
    public Rule getPasswordUser() { return passwordUser; }
    public void setPasswordUser(Rule r) { this.passwordUser = r; }
    public Rule getPasswordIp() { return passwordIp; }
    public void setPasswordIp(Rule r) { this.passwordIp = r; }
    public Rule getMoneyUser() { return moneyUser; }
    public void setMoneyUser(Rule r) { this.moneyUser = r; }
    public Rule getAdminUser() { return adminUser; }
    public void setAdminUser(Rule r) { this.adminUser = r; }
    public Rule getGlobalIp() { return globalIp; }
    public void setGlobalIp(Rule r) { this.globalIp = r; }
}
