package com.spendinganalyzer.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateRangeTest {

    @Test
    @DisplayName("accepts ISO dates and keeps both bounds")
    void acceptsIsoDates() {
        DateRange range = DateRange.of("2026-05-01", "2026-07-31");

        assertThat(range.from()).isEqualTo("2026-05-01");
        assertThat(range.to()).isEqualTo("2026-07-31");
        assertThat(range.isUnbounded()).isFalse();
    }

    @Test
    @DisplayName("either bound may be omitted, giving an open-ended range")
    void allowsOpenEndedRanges() {
        assertThat(DateRange.of("2026-05-01", null).to()).isNull();
        assertThat(DateRange.of(null, "2026-07-31").from()).isNull();
    }

    @Test
    @DisplayName("missing or blank bounds mean no filtering at all")
    void treatsBlanksAsUnbounded() {
        assertThat(DateRange.of(null, null).isUnbounded()).isTrue();
        assertThat(DateRange.of("", "  ").isUnbounded()).isTrue();
        assertThat(DateRange.ALL.isUnbounded()).isTrue();
    }

    @Test
    @DisplayName("a malformed date is rejected rather than silently matching nothing")
    void rejectsMalformedDates() {
        // Passed straight into SQL a bad value would filter everything out and look like
        // "you have no transactions", which is far harder to diagnose than an error.
        assertThatThrownBy(() -> DateRange.of("last-tuesday", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM-DD")
                .hasMessageContaining("from");

        assertThatThrownBy(() -> DateRange.of("01/05/2026", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> DateRange.of(null, "2026-13-45"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("to");
    }

    @Test
    @DisplayName("a backwards range is rejected")
    void rejectsBackwardsRange() {
        assertThatThrownBy(() -> DateRange.of("2026-07-31", "2026-05-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("after");
    }

    @Test
    @DisplayName("a single-day range is allowed")
    void allowsSingleDayRange() {
        assertThat(DateRange.of("2026-05-01", "2026-05-01").isUnbounded()).isFalse();
    }

    @Test
    @DisplayName("surrounding whitespace is tolerated")
    void trimsInput() {
        assertThat(DateRange.of("  2026-05-01  ", null).from()).isEqualTo("2026-05-01");
    }
}
