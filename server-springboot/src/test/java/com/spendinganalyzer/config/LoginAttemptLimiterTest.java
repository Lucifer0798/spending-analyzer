package com.spendinganalyzer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure logic, no Spring: the counting/lockout/expiry rules in isolation. The HTTP-level wiring
 *  (which status, which header, which caller address) is LoginRateLimitTest's job. */
class LoginAttemptLimiterTest {

    /** A Clock that only moves when the test tells it to, so "the lockout expires" needs no
     *  real sleep. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("a caller under the limit may keep trying")
    void underTheLimitIsNeverLocked() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, Duration.ofMinutes(15),
                new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));

        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");

        assertThat(limiter.lockedFor("1.2.3.4")).isEmpty();
    }

    @Test
    @DisplayName("the caller is locked out once failures reach the limit")
    void limitTripsTheLockout() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, Duration.ofMinutes(15),
                new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));

        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");

        assertThat(limiter.lockedFor("1.2.3.4")).isPresent();
    }

    @Test
    @DisplayName("the lockout expires on its own once the window passes")
    void lockoutExpiresAfterTheWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, Duration.ofMinutes(15), clock);

        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        assertThat(limiter.lockedFor("1.2.3.4")).isPresent();

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        assertThat(limiter.lockedFor("1.2.3.4")).isEmpty();
    }

    @Test
    @DisplayName("after the lockout expires, the count starts over rather than resuming where it left off")
    void countResetsOnceTheLockoutHasExpired() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, Duration.ofMinutes(15), clock);

        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        clock.advance(Duration.ofMinutes(16));
        assertThat(limiter.lockedFor("1.2.3.4")).isEmpty();

        // One fresh failure after the reset should not re-trip the lock immediately.
        limiter.recordFailure("1.2.3.4");
        assertThat(limiter.lockedFor("1.2.3.4")).isEmpty();
    }

    @Test
    @DisplayName("a success clears the count, even if it arrived right at the limit")
    void successClearsTheCount() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, Duration.ofMinutes(15),
                new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));

        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        limiter.recordSuccess("1.2.3.4");

        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        assertThat(limiter.lockedFor("1.2.3.4"))
                .as("two failures after a reset should not be at a limit of three")
                .isEmpty();
    }

    @Test
    @DisplayName("callers are tracked independently")
    void differentCallersDoNotAffectEachOther() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, Duration.ofMinutes(15),
                new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));

        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");

        assertThat(limiter.lockedFor("1.2.3.4")).isPresent();
        assertThat(limiter.lockedFor("5.6.7.8")).isEmpty();
    }
}
