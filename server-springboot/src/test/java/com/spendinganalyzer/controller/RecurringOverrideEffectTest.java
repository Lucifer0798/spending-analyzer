package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.RecurringSeries;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.RecurringOverrideRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code /recurring} actually applies overrides -- {@link RecurringOverrideControllerTest}
 * covers the CRUD side in isolation, this covers the two things that CRUD exists to change.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecurringOverrideEffectTest {

    @Autowired
    private InsightsController insightsController;

    @Autowired
    private RecurringOverrideController overrideController;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private RecurringOverrideRepository overrides;

    @BeforeEach
    void seed() {
        transactions.insertBatch(List.of(
                new ParsedTransaction("2026-05-04", "NETFLIX.COM", 15.49, "debit", "Subscriptions"),
                new ParsedTransaction("2026-06-04", "NETFLIX.COM", 15.49, "debit", "Subscriptions"),
                new ParsedTransaction("2026-07-04", "NETFLIX.COM", 15.49, "debit", "Subscriptions")
        ), "override-effect-batch", 1L);
    }

    @SuppressWarnings("unchecked")
    private List<RecurringSeries> fetchRecurring() {
        return (List<RecurringSeries>) (List<?>) insightsController.recurring(null, null, null).get("recurring");
    }

    @Test
    @DisplayName("with no override, a detected series carries no flag")
    void noOverrideByDefault() {
        assertThat(fetchRecurring()).singleElement().satisfies(s -> {
            assertThat(s.flaggedForCancellation()).isFalse();
            assertThat(s.overrideId()).isNull();
        });
    }

    @Test
    @DisplayName("a \"cancel\" override marks the series without hiding it")
    void cancelFlagsWithoutHiding() {
        overrideController.save(Map.of("merchant_key", "NETFLIX.COM", "action", "cancel"));

        assertThat(fetchRecurring()).singleElement().satisfies(s -> {
            assertThat(s.flaggedForCancellation()).isTrue();
            assertThat(s.overrideId()).isNotNull();
        });
    }

    @Test
    @DisplayName("an \"exclude\" override removes the series entirely, and out of the totals")
    void excludeHidesEntirely() {
        overrideController.save(Map.of("merchant_key", "NETFLIX.COM", "action", "exclude"));

        Map<String, Object> response = insightsController.recurring(null, null, null);

        assertThat((List<?>) response.get("recurring")).isEmpty();
        assertThat(response.get("totalAnnualizedCost")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("clearing the override brings the series back, unflagged")
    void clearingOverrideRestoresTheSeries() {
        var saved = overrideController.save(Map.of("merchant_key", "NETFLIX.COM", "action", "exclude"));
        long id = ((com.spendinganalyzer.model.RecurringOverride) saved.getBody()).id();

        overrideController.delete(id);

        assertThat(fetchRecurring()).singleElement().satisfies(s -> {
            assertThat(s.flaggedForCancellation()).isFalse();
            assertThat(s.overrideId()).isNull();
        });
    }
}
