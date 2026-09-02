package com.spendinganalyzer.controller;

import com.spendinganalyzer.config.AuthSettings;
import com.spendinganalyzer.config.SecurityConfig;
import com.spendinganalyzer.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthSettings auth;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthSettings auth, AuthenticationManager authenticationManager) {
        this.auth = auth;
        this.authenticationManager = authenticationManager;
    }

    /**
     * What the frontend needs before it renders anything: whether there is a lock at all, and
     * whether this browser is past it.
     *
     * <p>Injecting {@link CsrfToken} is not enough on its own: the token is deferred, and only
     * reading its value materialises it and writes the cookie. Merely declaring the parameter
     * leaves the client with no token and every later write refused — so the {@code getToken()}
     * call below is load-bearing, not a leftover.
     */
    @GetMapping("/status")
    public Map<String, Object> status(CsrfToken csrfToken) {
        // Deliberate: this is what issues the cookie the frontend needs before any write.
        csrfToken.getToken();

        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = current != null && current.isAuthenticated()
                && SecurityConfig.USERNAME.equals(current.getName());

        return Map.of(
                "authRequired", auth.enabled(),
                // With no password configured everything is open, so the frontend should treat
                // the user as already through the door rather than showing a pointless login box.
                "authenticated", !auth.enabled() || signedIn
        );
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (!auth.enabled()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("This instance has no password set, so there is nothing to sign in to."));
        }

        String password = body.get("password") instanceof String s ? s : "";
        if (password.isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Password is required."));
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(SecurityConfig.USERNAME, password));
        } catch (AuthenticationException e) {
            // Deliberately vague: there is one account, so the only thing a caller can be told
            // apart is whether they guessed the password.
            return ResponseEntity.status(401).body(new ErrorResponse("Incorrect password."));
        }

        // Rotate the session id on login, so one an attacker planted beforehand does not become
        // an authenticated session. Only when a session already exists — changeSessionId throws
        // otherwise, and with a cookie-based CSRF token a first sign-in often has none yet. In
        // that case saveContext creates the session below, with an id the client never saw.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
