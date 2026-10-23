package com.example.wallet.security;

import com.example.wallet.repository.UserRepository;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

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
     *   <li>{@code /api/auth/logout} requires authentication; the rest of {@code /api/auth/**} is public. The container's error forward to {@code /error} (dispatcher type ERROR) must
     *       be public because it is not re-authenticated by the JWT filter; without it a 403 would turn into a 401.
     *       A direct request to {@code /error} is denied (401 anonymous, 403 otherwise) instead of reaching Boot's
     *       error controller, which has no error to report and answers 500 with status 999.</li>
     *   <li>{@code /api/admin/**} requires the ADMIN role.</li>
     *   <li>Everything else requires authentication.</li>
     *   <li>Unauthenticated access to a protected endpoint returns 401 with no body. An authenticated user
     *       lacking the required role gets the default 403.</li>
     *   <li>Rate limiting ({@link RateLimitFilter}) runs first in the chain, before any token parsing or BCrypt work;
     *       the per-user limits ({@link UserRateLimitFilter}) run right after {@link JwtAuthFilter}. Both are plain
     *       objects, not beans, so Boot does not also register them as servlet filters.</li>
     * </ul>
     *
     * @param http          the HTTP security builder
     * @param jwtAuthFilter the JWT filter, registered before the username/password filter
     * @param rateLimits    the rate limiter state; absent only in web-layer test slices that do not load it
     * @param mapper        reads login bodies for the per-username limit
     * @return the configured filter chain
     * @throws Exception if the configuration cannot be built
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
                                           ObjectProvider<RateLimits> rateLimits, JsonMapper mapper)
            throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/error").denyAll()
                .requestMatchers("/api/auth/logout").authenticated()
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        RateLimits limits = rateLimits.getIfAvailable();
        if (limits != null) {
            http.addFilterBefore(new RateLimitFilter(limits, mapper), JwtAuthFilter.class)
                .addFilterAfter(new UserRateLimitFilter(limits), JwtAuthFilter.class);
        }
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
                .<UserDetails>map(u -> new AuthUser(u.getUsername(), u.getPasswordHash(), u.isEnabled(),
                        AuthorityUtils.createAuthorityList("ROLE_" + u.getRole().name()), u.getTokenVersion()))
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    /**
     * Stops Spring Boot from also registering {@link JwtAuthFilter} (a {@code @Component}) as a plain servlet filter.
     * It must only run inside the security filter chain: outside it, the authentication it sets is discarded when the
     * chain starts a fresh security context, and in tests (MockMvc) every authenticated request would answer 401.
     *
     * @param filter the JWT filter bean
     * @return a disabled servlet registration for it
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Password check for login. Spring's default provider tests the enabled flag BEFORE the password, so a wrong
     * password on a disabled account answered in about 2 ms instead of the ~75 ms of a BCrypt comparison (a timing
     * oracle for disabled accounts). Here the pre-checks are empty and the disabled check runs after the password
     * comparison; a disabled user with the right password still gets the same 401 (see {@code GlobalExceptionHandler}).
     *
     * @param userDetailsService loads the user
     * @param passwordEncoder    verifies the password
     * @return the provider used by the authentication manager
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider(UserDetailsService userDetailsService,
                                                            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider(userDetailsService);
        p.setPasswordEncoder(passwordEncoder);
        p.setPreAuthenticationChecks(user -> { });
        p.setPostAuthenticationChecks(user -> {
            if (!user.isEnabled()) throw new DisabledException("User is disabled");
        });
        return p;
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
