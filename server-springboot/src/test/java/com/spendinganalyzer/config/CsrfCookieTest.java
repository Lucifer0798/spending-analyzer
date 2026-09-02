package com.spendinganalyzer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * That the CSRF cookie is actually issued.
 *
 * <p>Deliberately its own class, with no use of the {@code csrf()} request post-processor: that
 * helper primes a token for the request it decorates, which masks whether the application would
 * have issued one on its own. This is the case that broke — a client doing nothing but GETs held
 * no token, and its first write was refused with a status that read like a wrong password.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CsrfCookieTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("a plain GET is enough to be handed a CSRF token")
    void statusIssuesTheCsrfCookie() throws Exception {
        mockMvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }
}
