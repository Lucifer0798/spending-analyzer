package com.spendinganalyzer.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoint-level wiring: does the wrong number of failures actually lock the HTTP endpoint,
 * with the right status and headers. The counting/expiry rules themselves are
 * LoginAttemptLimiterTest's job, tested without Spring so they run in milliseconds.
 */
@SpringBootTest(properties = {
        "app.auth.password=correct-horse-battery",
        "app.auth.max-attempts=3"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginRateLimitTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String OTHER_CALLER = "10.0.0.5";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoginAttemptLimiter attemptLimiter;

    // The limiter is a singleton bean that outlives each @Test method, so a lockout left behind
    // by one test would otherwise leak into the next -- MockMvc's default caller address is
    // always 127.0.0.1, and recordSuccess is also this class's way to clear it between cases.
    @BeforeEach
    void clearAnyLockoutFromAPreviousTest() {
        attemptLimiter.recordSuccess("127.0.0.1");
        attemptLimiter.recordSuccess(OTHER_CALLER);
    }

    private ResultActions attempt(String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + password + "\"}"));
    }

    private ResultActions attemptAs(String remoteAddr, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(csrf())
                .with(request -> {
                    request.setRemoteAddr(remoteAddr);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + password + "\"}"));
    }

    @Test
    @DisplayName("locks out after the configured number of consecutive wrong passwords")
    void locksOutAfterTooManyFailures() throws Exception {
        attempt("wrong").andExpect(status().isUnauthorized());
        attempt("wrong").andExpect(status().isUnauthorized());
        attempt("wrong").andExpect(status().isUnauthorized());

        attempt("wrong")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value(containsString("Too many failed attempts")));

        // The lock is on the caller, not on which password they typed -- even the right one is
        // refused until the lockout passes.
        attempt(PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("a correct password before the limit clears the count")
    void successResetsTheCount() throws Exception {
        attempt("wrong").andExpect(status().isUnauthorized());
        attempt("wrong").andExpect(status().isUnauthorized());

        attempt(PASSWORD).andExpect(status().isOk());

        // Two more wrong guesses would trip a 3-attempt lockout if the earlier failures still
        // counted; they don't, because the successful sign-in cleared them.
        attempt("wrong").andExpect(status().isUnauthorized());
        attempt("wrong").andExpect(status().isUnauthorized());
        attempt(PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a lockout for one caller does not affect a different one")
    void lockoutIsPerCaller() throws Exception {
        attempt("wrong").andExpect(status().isUnauthorized());
        attempt("wrong").andExpect(status().isUnauthorized());
        attempt("wrong").andExpect(status().isUnauthorized());
        attempt("wrong").andExpect(status().isTooManyRequests());

        attemptAs(OTHER_CALLER, PASSWORD).andExpect(status().isOk());
    }
}
