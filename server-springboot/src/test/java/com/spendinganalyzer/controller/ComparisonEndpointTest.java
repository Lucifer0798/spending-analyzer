package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ComparisonResponse;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The endpoint's own wiring -- parameter parsing and the applicable/not-applicable envelope.
 * The comparison maths themselves are StatsServiceComparisonTest's job.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ComparisonEndpointTest {

    @Autowired
    private InsightsController controller;

    @Autowired
    private TransactionRepository transactions;

    @BeforeEach
    void seed() {
        transactions.insertBatch(List.of(
                new ParsedTransaction("2026-02-10", "FEBRUARY SHOP", 100.00, "debit", "Groceries"),
                new ParsedTransaction("2026-03-10", "MARCH SHOP", 150.00, "debit", "Groceries")
        ), "comparison-endpoint-batch", 1L);
    }

    @Test
    @DisplayName("a bounded range returns an applicable comparison")
    void boundedRangeIsApplicable() {
        ComparisonResponse response = controller.comparison(null, "2026-03-01", "2026-03-31");

        assertThat(response.applicable()).isTrue();
        assertThat(response.comparison()).isNotNull();
        assertThat(response.comparison().currentTotal()).isEqualTo(150.00);
        assertThat(response.comparison().previousTotal()).isEqualTo(100.00);
    }

    @Test
    @DisplayName("no date filter at all is not applicable")
    void noFilterIsNotApplicable() {
        ComparisonResponse response = controller.comparison(null, null, null);

        assertThat(response.applicable()).isFalse();
        assertThat(response.comparison()).isNull();
    }

    @Test
    @DisplayName("a half-open range is not applicable")
    void halfOpenRangeIsNotApplicable() {
        ComparisonResponse response = controller.comparison(null, "2026-03-01", null);

        assertThat(response.applicable()).isFalse();
    }

    @Test
    @DisplayName("an invalid date fails loudly, like every other date-filtered endpoint")
    void invalidDateIsRejected() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                        controller.comparison(null, "not-a-date", "2026-03-31")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
