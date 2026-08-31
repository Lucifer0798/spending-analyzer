package com.spendinganalyzer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthSettingsTest {

    @Test
    @DisplayName("no password means authentication is off")
    void blankPasswordDisablesAuth() {
        assertThat(new AuthSettings("", false).enabled()).isFalse();
        assertThat(new AuthSettings(null, false).enabled()).isFalse();
        assertThat(new AuthSettings("   ", false).enabled()).isFalse();
    }

    @Test
    @DisplayName("a password turns authentication on")
    void passwordEnablesAuth() {
        assertThat(new AuthSettings("correct horse", false).enabled()).isTrue();
    }

    @Test
    @DisplayName("trims the password, so a stray newline in an env file is not part of the secret")
    void trimsPassword() {
        assertThat(new AuthSettings("  hunter2\n", false).password()).isEqualTo("hunter2");
    }

    @Test
    @DisplayName("refuses to start when told to require a password but given none")
    void failsFastWhenRequiredButMissing() {
        // The whole point of the required flag: a deployed instance must not silently fall back
        // to serving everything openly.
        assertThatThrownBy(() -> new AuthSettings("", true))
                .isInstanceOf(MissingPasswordException.class)
                .hasMessageContaining("APP_PASSWORD");
    }

    @Test
    @DisplayName("starts when the requirement is met")
    void startsWhenRequiredAndSet() {
        assertThat(new AuthSettings("hunter2", true).enabled()).isTrue();
    }
}
