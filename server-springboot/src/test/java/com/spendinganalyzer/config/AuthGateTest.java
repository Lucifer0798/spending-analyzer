package com.spendinganalyzer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gate with a password configured. The open case — no password — is covered by every other
 * test in the suite, all of which run without one and would fail if the gate closed on them.
 */
@SpringBootTest(properties = "app.auth.password=correct-horse-battery")
@AutoConfigureMockMvc
@ActiveProfiles("test")
// The CSRF cases below write a budget. MockMvc runs in the test's own thread, so this rolls
// them back — without it they persist into the file-backed test database and break whichever
// test asserts on that category next.
@Transactional
class AuthGateTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

    /** Signs in and returns the session, so a caller can make requests as a signed-in browser. */
    private MockHttpSession signIn() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        return (MockHttpSession) result.getRequest().getSession(false);
    }

    // --- the door ---------------------------------------------------------------

    @Test
    @DisplayName("the API is closed to a browser that has not signed in")
    void apiIsClosedWhenSignedOut() throws Exception {
        mockMvc.perform(get("/api/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/transactions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/export/transactions.csv")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/budgets")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("answers 401 rather than redirecting to a login page the app does not serve")
    void refusesWithJsonNotARedirect() throws Exception {
        mockMvc.perform(get("/api/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("the static shell still loads, or there would be nothing to draw a login box with")
    void shellIsReachableWhenSignedOut() throws Exception {
        mockMvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authRequired").value(true))
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    // --- signing in -------------------------------------------------------------

    @Test
    @DisplayName("the wrong password is refused")
    void wrongPasswordRefused() throws Exception {
        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"not-it\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an empty password is refused rather than treated as a match")
    void emptyPasswordRefused() throws Exception {
        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the right password opens the API for that session")
    void correctPasswordOpensTheApi() throws Exception {
        MockHttpSession session = signIn();

        mockMvc.perform(get("/api/summary").session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/status").session(session))
                .andExpect(jsonPath("$.authenticated").value(true));
    }

    @Test
    @DisplayName("signing in does not open the API for a different session")
    void signInDoesNotLeakToOtherSessions() throws Exception {
        signIn();

        // A fresh browser, no session cookie.
        mockMvc.perform(get("/api/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("signing out closes it again")
    void logoutClosesTheApi() throws Exception {
        MockHttpSession session = signIn();

        mockMvc.perform(post("/api/auth/logout").with(csrf()).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/summary").session(session))
                .andExpect(status().isUnauthorized());
    }

    // --- cross-site protection --------------------------------------------------

    @Test
    @DisplayName("a write without a CSRF token is rejected even when signed in")
    void writeWithoutCsrfIsRejected() throws Exception {
        MockHttpSession session = signIn();

        // Without this, any page on the internet could delete the user's data using their
        // session cookie just by submitting a form at us.
        mockMvc.perform(post("/api/budgets").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"Groceries\",\"monthly_limit\":100}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a write with a CSRF token goes through")
    void writeWithCsrfSucceeds() throws Exception {
        MockHttpSession session = signIn();

        mockMvc.perform(post("/api/budgets").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"Groceries\",\"monthly_limit\":100}"))
                .andExpect(status().isOk());
    }
}
