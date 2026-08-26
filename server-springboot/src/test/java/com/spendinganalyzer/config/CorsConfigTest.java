package com.spendinganalyzer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dev server is the only legitimate cross-origin caller, and its port is not fixed: Vite
 * moves to 5174 and upwards when 5173 is already taken. Pinning a single port silently breaks
 * every write from a second checkout, which is what these cases exist to prevent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsConfigTest {

    @Autowired
    private MockMvc mockMvc;

    private void preflightFrom(String origin, boolean expectAllowed) throws Exception {
        var result = mockMvc.perform(options("/api/budgets")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST"));

        if (expectAllowed) {
            result.andExpect(status().isOk())
                  .andExpect(header().string("Access-Control-Allow-Origin", origin));
        } else {
            result.andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("allows the default Vite port")
    void allowsDefaultDevPort() throws Exception {
        preflightFrom("http://localhost:5173", true);
    }

    @Test
    @DisplayName("allows the port Vite falls back to when 5173 is taken")
    void allowsFallbackDevPort() throws Exception {
        preflightFrom("http://localhost:5174", true);
    }

    @Test
    @DisplayName("allows the loopback address as well as the hostname")
    void allowsLoopbackAddress() throws Exception {
        preflightFrom("http://127.0.0.1:5173", true);
    }

    @Test
    @DisplayName("refuses an origin that is not local, so this is not the old wildcard")
    void refusesRemoteOrigin() throws Exception {
        preflightFrom("https://evil.example.com", false);
    }

    @Test
    @DisplayName("refuses a non-loopback host even on a dev port")
    void refusesNonLoopbackHost() throws Exception {
        preflightFrom("http://192.168.1.50:5173", false);
    }
}
