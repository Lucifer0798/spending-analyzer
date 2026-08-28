package com.spendinganalyzer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one credential the app has: a shared password read from the environment.
 *
 * <p>There is no user model here on purpose. This app stores one person's statements in a local
 * SQLite file; per-user accounts would mean an owner column on every table and a scoping clause
 * in every query, to solve a problem it does not have. What it does need is a lock on the door
 * once it is reachable from anywhere but localhost.
 */
@Component
public class AuthSettings {

    private static final Logger log = LoggerFactory.getLogger(AuthSettings.class);

    private final String password;

    public AuthSettings(
            @Value("${app.auth.password:}") String password,
            @Value("${app.auth.required:false}") boolean required
    ) {
        this.password = password == null ? "" : password.trim();

        // Refusing to start is the point: an instance told to require a password should never
        // fall back to serving everything openly, which is the failure nobody would notice.
        if (required && this.password.isEmpty()) {
            throw new MissingPasswordException("""
                    This instance is configured to require authentication (APP_AUTH_REQUIRED=true) \
                    but APP_PASSWORD is not set, so it will not start. Set APP_PASSWORD to a \
                    password of your choosing.""");
        }

        if (this.password.isEmpty()) {
            log.warn("No APP_PASSWORD set — running with authentication disabled. "
                    + "Anyone who can reach this port can read and delete every transaction. "
                    + "Fine on localhost; set APP_PASSWORD before exposing it anywhere else.");
        }
    }

    public boolean enabled() {
        return !password.isEmpty();
    }

    String password() {
        return password;
    }
}
