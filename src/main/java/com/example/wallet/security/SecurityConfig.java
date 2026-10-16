package com.example.wallet.security;

import com.example.wallet.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration: stateless JWT authentication, role-based access rules, and the beans needed for
 * password checking and login.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Defines the HTTP security rules.
     * <ul>
     *   <li>CSRF and server-side sessions are disabled (stateless, token-based API).</li>
     *   <li>{@code /api/auth/**} and {@code /error} are public. {@code /error} must be public because container
     *       error forwards are not re-authenticated by the JWT filter; without it a 403 would turn into a 401.</li>
     *   <li>{@code /api/admin/**} requires the ADMIN role.</li>
     *   <li>Everything else requires authentication.</li>
     *   <li>Unauthenticated access to a protected endpoint returns 401 with no body. An authenticated user
     *       lacking the required role gets the default 403.</li>
     * </ul>
     *
     * @param http          the HTTP security builder
     * @param jwtAuthFilter the JWT filter, registered before the username/password filter
     * @return the configured filter chain
     * @throws Exception if the configuration cannot be built
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/**", "/error").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Loads users from the database for authentication. The role becomes the authority {@code ROLE_<role>}, and a
     * disabled user is reported as disabled.
     *
     * @param users the user repository
     * @return a service that throws {@link UsernameNotFoundException} for unknown usernames
     */
    @Bean
    public UserDetailsService userDetailsService(UserRepository users) {
        return username -> users.findByUsername(username)
                .map(u -> org.springframework.security.core.userdetails.User.withUsername(u.getUsername())
                        .password(u.getPasswordHash())
                        .roles(u.getRole().name())
                        .disabled(!u.isEnabled())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    /** @return a BCrypt password encoder used to hash and verify passwords */
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    /**
     * Exposes the authentication manager used by the login endpoint.
     *
     * @param config Spring's authentication configuration
     * @return the authentication manager
     * @throws Exception if it cannot be obtained
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
