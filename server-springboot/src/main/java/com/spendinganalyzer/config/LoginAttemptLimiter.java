package com.spendinganalyzer.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Brute-force protection for the one login endpoint this app has. There is exactly one account,
 * so failures are tracked per caller address rather than per username -- someone guessing the
 * shared password gets slowed down without a legitimate owner on a different network ever being
 * affected by it.
 *
 * <p>In-memory and lost on restart, which is the right trade for a single-instance local app: a
 * restart already drops every other piece of session state, and persisting attempt counters
 * would need a table and a cleanup job to solve a problem that already resets itself for free.
 */
@Component
public class LoginAttemptLimiter {

    private final int maxAttempts;
    private final Duration lockoutDuration;
    private final Clock clock;
    private final ConcurrentMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptLimiter(
            @Value("${app.auth.max-attempts:5}") int maxAttempts,
            @Value("${app.auth.lockout-minutes:15}") long lockoutMinutes) {
        this(maxAttempts, Duration.ofMinutes(lockoutMinutes), Clock.systemUTC());
    }

    /** Package-private: lets tests supply a fixed clock instead of waiting out a real lockout. */
    LoginAttemptLimiter(int maxAttempts, Duration lockoutDuration, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.lockoutDuration = lockoutDuration;
        this.clock = clock;
    }

    /** Empty when {@code caller} may attempt to sign in; otherwise, how much longer they are locked out. */
    public Optional<Duration> lockedFor(String caller) {
        Attempt attempt = attempts.get(caller);
        if (attempt == null || attempt.count() < maxAttempts) {
            return Optional.empty();
        }

        Instant unlocksAt = attempt.lastFailure().plus(lockoutDuration);
        Instant now = clock.instant();
        if (now.isBefore(unlocksAt)) {
            return Optional.of(Duration.between(now, unlocksAt));
        }

        // The lockout has run out; forget it so this caller starts with a clean slate rather
        // than needing exactly one more success to clear a count that no longer means anything.
        attempts.remove(caller, attempt);
        return Optional.empty();
    }

    /**
     * Only called for a genuine wrong-password attempt: a blank password never reaches this, and
     * neither does an attempt {@link #lockedFor} already refused -- so probing while locked out
     * does not itself push the unlock time further back.
     */
    public void recordFailure(String caller) {
        attempts.compute(caller, (key, existing) ->
                new Attempt(existing == null ? 1 : existing.count() + 1, clock.instant()));
    }

    /** Also doubles as the test-side way to clear a caller's record between cases. */
    public void recordSuccess(String caller) {
        attempts.remove(caller);
    }

    private record Attempt(int count, Instant lastFailure) {
    }
}
