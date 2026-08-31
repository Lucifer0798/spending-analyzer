package com.spendinganalyzer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Locks the API behind {@link AuthSettings}' shared password when one is configured, and gets
 * out of the way entirely when one is not.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The browser has to load these before it can draw a login box, so they cannot themselves
     * be behind the login. They are the static shell only — every byte of actual data is served
     * from /api, which is not on this list.
     */
    private static final String[] SHELL = {
            "/", "/index.html", "/assets/**", "/favicon.svg", "/icons.svg", "/vite.svg"
    };

    /** The single account's username. Never typed by anyone; only the password is a secret. */
    public static final String USERNAME = "owner";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * One in-memory user holding the configured password. Going through Spring Security's
     * authentication rather than comparing strings ourselves means the comparison is BCrypt's
     * constant-time one, and login gets the same audited path any other Spring app uses.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthSettings auth, PasswordEncoder encoder) {
        var users = new InMemoryUserDetailsManager();
        if (auth.enabled()) {
            users.createUser(User.withUsername(USERNAME)
                    .password(encoder.encode(auth.password()))
                    .authorities("ROLE_USER")
                    .build());
        }
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AuthSettings auth) throws Exception {
        // CSRF applies in both modes. The tempting argument for switching it off when there is
        // no password — no login, so no ambient authority to ride — leans entirely on CORS to
        // stop a cross-site write, and CORS here is configurable. A page the user happens to
        // have open should not be able to reach a local instance and empty it. The frontend
        // already sends the token in both modes, so this costs nothing.
        //
        // The SPA reads the token from the cookie and echoes it in a header, so the cookie must
        // be readable by script, and the plain handler is required — the default XOR one gives
        // the client a value the server will not accept back.
        http.csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));

        if (!auth.enabled()) {
            return http
                    .authorizeHttpRequests(rules -> rules.anyRequest().permitAll())
                    .build();
        }

        return http
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers(SHELL).permitAll()
                        // Signing in cannot itself require being signed in.
                        .requestMatchers("/api/auth/**").permitAll()
                        .anyRequest().authenticated())
                // A browser hitting an API it is not signed in for should get a 401 it can act
                // on, not a redirect to a login page this app does not serve.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\":\"Not signed in.\"}");
                        }))
                .build();
    }
}
