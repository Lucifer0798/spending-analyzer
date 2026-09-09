package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.RecurringOverride;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecurringOverrideRepositoryTest {

    @Autowired
    private RecurringOverrideRepository repository;

    @Test
    @DisplayName("creates an override for a merchant")
    void createsOverride() {
        RecurringOverride saved = repository.upsert("NETFLIX", RecurringOverride.ACTION_CANCEL);

        assertThat(saved.merchantKey()).isEqualTo("NETFLIX");
        assertThat(saved.action()).isEqualTo("cancel");
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("upserting the same merchant twice replaces the action instead of duplicating")
    void upsertReplacesRatherThanDuplicates() {
        repository.upsert("NETFLIX", RecurringOverride.ACTION_CANCEL);
        RecurringOverride updated = repository.upsert("NETFLIX", RecurringOverride.ACTION_EXCLUDE);

        assertThat(updated.action()).isEqualTo("exclude");
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("loadAll is keyed by merchant for a direct lookup")
    void loadAllIsKeyedByMerchant() {
        repository.upsert("NETFLIX", RecurringOverride.ACTION_CANCEL);
        repository.upsert("SPOTIFY", RecurringOverride.ACTION_EXCLUDE);

        var byKey = repository.loadAll();

        assertThat(byKey).hasSize(2);
        assertThat(byKey.get("NETFLIX").action()).isEqualTo("cancel");
        assertThat(byKey.get("SPOTIFY").action()).isEqualTo("exclude");
    }

    @Test
    @DisplayName("deletes an override, and reports a missing one as false")
    void deletesOverride() {
        RecurringOverride saved = repository.upsert("NETFLIX", RecurringOverride.ACTION_CANCEL);

        assertThat(repository.delete(saved.id())).isTrue();
        assertThat(repository.findAll()).isEmpty();
        assertThat(repository.delete(saved.id())).isFalse();
    }
}
