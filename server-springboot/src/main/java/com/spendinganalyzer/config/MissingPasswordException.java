package com.spendinganalyzer.config;

/**
 * Thrown at startup when the instance is told to require authentication but has no password.
 *
 * <p>A dedicated type rather than a plain {@link IllegalStateException} so
 * {@link MissingPasswordFailureAnalyzer} can recognise it and print something an operator can
 * act on. It still extends {@code IllegalStateException}, since that is what it is.
 */
public class MissingPasswordException extends IllegalStateException {

    public MissingPasswordException(String message) {
        super(message);
    }
}
