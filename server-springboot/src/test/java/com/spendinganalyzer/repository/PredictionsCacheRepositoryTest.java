package com.spendinganalyzer.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The actual bug this exists to fix: two accounts' cached forecasts must never collide.
 * {@link com.spendinganalyzer.service.InsightsServiceTest} proves the account id is threaded
 * through correctly with mocks; this proves the real SQLite upsert genuinely keeps them apart.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PredictionsCacheRepositoryTest {

    @Autowired
    private PredictionsCacheRepository repository;

    @Test
    @DisplayName("no cache for an account gives an empty result, not another account's")
    void emptyForAnUncachedAccount() {
        assertThat(repository.find(1L)).isEmpty();
    }

    @Test
    @DisplayName("two accounts' cached forecasts are independent")
    void twoAccountsDoNotCollide() {
        repository.upsert(1L, "{\"account\":1}", "2026-06-01T00:00:00Z");
        repository.upsert(2L, "{\"account\":2}", "2026-06-02T00:00:00Z");

        // This is the whole bug: before this table had an account_id column, the second upsert
        // would have overwritten the first, and account 1 would see account 2's forecast.
        assertThat(repository.find(1L)).get().extracting("payload").isEqualTo("{\"account\":1}");
        assertThat(repository.find(2L)).get().extracting("payload").isEqualTo("{\"account\":2}");
    }

    @Test
    @DisplayName("upserting the same account twice replaces it rather than duplicating")
    void sameAccountReplacesNotDuplicates() {
        repository.upsert(1L, "{\"v\":1}", "2026-06-01T00:00:00Z");
        repository.upsert(1L, "{\"v\":2}", "2026-06-02T00:00:00Z");

        assertThat(repository.find(1L)).get().extracting("payload").isEqualTo("{\"v\":2}");
    }

    @Test
    @DisplayName("null (all accounts) is its own scope, distinct from any real account")
    void nullIsItsOwnScope() {
        repository.upsert(null, "{\"scope\":\"all\"}", "2026-06-01T00:00:00Z");
        repository.upsert(5L, "{\"scope\":\"five\"}", "2026-06-02T00:00:00Z");

        assertThat(repository.find(null)).get().extracting("payload").isEqualTo("{\"scope\":\"all\"}");
        assertThat(repository.find(5L)).get().extracting("payload").isEqualTo("{\"scope\":\"five\"}");
    }

    @Test
    @DisplayName("upserting null twice replaces rather than duplicates")
    void nullScopeReplacesNotDuplicates() {
        // The repository maps a null accountId to the 0 sentinel before it ever reaches SQL —
        // this is what proves that mapping actually lands on one conflict-able row rather than,
        // say, silently generating a fresh key each call.
        repository.upsert(null, "{\"v\":1}", "2026-06-01T00:00:00Z");
        repository.upsert(null, "{\"v\":2}", "2026-06-02T00:00:00Z");

        assertThat(repository.find(null)).get().extracting("payload").isEqualTo("{\"v\":2}");
    }
}
