package com.spendinganalyzer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The app with no password configured — the default for a local run.
 *
 * <p>Open does not mean unprotected. CodeQL flagged an earlier version of this config that
 * switched CSRF off here, and it was right to: without a token check, any page the user happens
 * to have open could POST to a local instance and empty it. These cases pin that shut.
 */
@SpringBootTest(properties = "app.auth.password=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OpenModeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("reads need no sign-in")
    void readsAreOpen() throws Exception {
        mockMvc.perform(get("/api/summary")).andExpect(status().isOk());
        mockMvc.perform(get("/api/transactions")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("reports that there is nothing to sign in to")
    void statusReportsOpen() throws Exception {
        mockMvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authRequired").value(false))
                // Nothing to be signed in to, so the frontend should not show a login box.
                .andExpect(jsonPath("$.authenticated").value(true));
    }

    @Test
    @DisplayName("a cross-site write is still refused without a CSRF token")
    void writesStillNeedACsrfToken() throws Exception {
        mockMvc.perform(post("/api/budgets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"Groceries\",\"monthly_limit\":100}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the app's own writes go through, since it sends the token in both modes")
    void writesWithATokenSucceed() throws Exception {
        mockMvc.perform(post("/api/budgets").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"Groceries\",\"monthly_limit\":100}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("signing in is refused when there is no password to sign in with")
    void loginIsMeaninglessWhenOpen() throws Exception {
        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"anything\"}"))
                .andExpect(status().isBadRequest());
    }
}
