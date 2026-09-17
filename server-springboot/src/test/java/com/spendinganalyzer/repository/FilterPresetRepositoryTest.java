package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.FilterPreset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FilterPresetRepositoryTest {

    @Autowired
    private FilterPresetRepository repository;

    @Autowired
    private AccountRepository accounts;

    @Test
    @DisplayName("saves a preset with every field set")
    void savesAFullPreset() {
        FilterPreset saved = repository.upsert(
                "Business trips", "Travel", "business", "uber", Account.DEFAULT_ID, "2026-01-01", "2026-12-31");

        assertThat(saved.name()).isEqualTo("Business trips");
        assertThat(saved.category()).isEqualTo("Travel");
        assertThat(saved.tag()).isEqualTo("business");
        assertThat(saved.search()).isEqualTo("uber");
        assertThat(saved.accountId()).isEqualTo(Account.DEFAULT_ID);
        assertThat(saved.dateFrom()).isEqualTo("2026-01-01");
        assertThat(saved.dateTo()).isEqualTo("2026-12-31");
    }

    @Test
    @DisplayName("every field but the name can be left unset")
    void mostFieldsAreOptional() {
        FilterPreset saved = repository.upsert("Everything", null, null, null, null, null, null);

        assertThat(saved.category()).isNull();
        assertThat(saved.tag()).isNull();
        assertThat(saved.search()).isNull();
        assertThat(saved.accountId()).isNull();
        assertThat(saved.dateFrom()).isNull();
        assertThat(saved.dateTo()).isNull();
    }

    @Test
    @DisplayName("saving under an existing name replaces it rather than duplicating")
    void upsertReplacesRatherThanDuplicates() {
        repository.upsert("My preset", "Groceries", null, null, null, null, null);
        FilterPreset updated = repository.upsert("My preset", "Travel", null, null, null, null, null);

        assertThat(updated.category()).isEqualTo("Travel");
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("a name is case-insensitive, matching an existing preset under different casing")
    void nameIsCaseInsensitive() {
        repository.upsert("Business Trips", "Travel", null, null, null, null, null);
        repository.upsert("business trips", "Groceries", null, null, null, null, null);

        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("deletes a preset, and reports a missing one as false")
    void deletesPreset() {
        FilterPreset saved = repository.upsert("Temp", null, null, null, null, null, null);

        assertThat(repository.delete(saved.id())).isTrue();
        assertThat(repository.findAll()).isEmpty();
        assertThat(repository.delete(saved.id())).isFalse();
    }

    @Test
    @DisplayName("clearAccountReference resets the account to null, leaving other fields alone")
    void clearAccountReferenceResetsToNull() {
        Account other = accounts.create("Savings", "savings", "USD");
        FilterPreset saved = repository.upsert("Savings only", "Groceries", null, null, other.id(), null, null);

        repository.clearAccountReference(other.id());

        FilterPreset reloaded = repository.findById(saved.id()).orElseThrow();
        assertThat(reloaded.accountId()).isNull();
        assertThat(reloaded.category()).isEqualTo("Groceries");
    }

    @Test
    @DisplayName("restoreAll replaces every preset, ids included")
    void restoreAllReplacesEverything() {
        repository.upsert("Old preset", null, null, null, null, null, null);

        List<FilterPreset> toRestore = List.of(
                new FilterPreset(1, "Restored", "Travel", "business", "uber",
                        Account.DEFAULT_ID, "2026-01-01", "2026-12-31", "2026-01-01 00:00:00"));

        repository.restoreAll(toRestore);

        assertThat(repository.findAll()).extracting("name").containsExactly("Restored");
    }
}
