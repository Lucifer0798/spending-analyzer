package com.spendinganalyzer.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a missing password into the same clean "APPLICATION FAILED TO START" block Spring Boot
 * prints for a port clash, rather than sixty lines of stack trace with the useful sentence
 * somewhere in the middle. This is the first thing a person deploying the image will see if
 * they forget the password, so it is worth it being readable.
 *
 * <p>Registered in {@code META-INF/spring.factories}.
 */
public class MissingPasswordFailureAnalyzer extends AbstractFailureAnalyzer<MissingPasswordException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingPasswordException cause) {
        return new FailureAnalysis(
                "This instance is configured to require authentication (APP_AUTH_REQUIRED=true), "
                        + "but no password was given, so it refused to start rather than come up "
                        + "open to anyone who can reach the port.",
                "Set APP_PASSWORD to a password of your choosing — in server-springboot/.env, or "
                        + "exported in the environment before starting. To run without any "
                        + "authentication (only sensible on localhost), unset APP_AUTH_REQUIRED.",
                cause);
    }
}
