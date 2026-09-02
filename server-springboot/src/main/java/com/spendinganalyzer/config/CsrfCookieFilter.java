package com.spendinganalyzer.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Makes sure the CSRF cookie is actually written.
 *
 * <p>Spring defers the token: {@code CookieCsrfTokenRepository} only writes the cookie when
 * something reads the token's value, and merely injecting a {@link CsrfToken} into a controller
 * does not do that. Without this, a browser that has only made GET requests holds no token, and
 * its first write is refused with no useful explanation — a 403 in the ordinary case, and a 401
 * for a caller who is not signed in yet, which reads exactly like a wrong password.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            // Reading the value is the whole point — it is what triggers the cookie being set.
            token.getToken();
        }

        filterChain.doFilter(request, response);
    }
}
