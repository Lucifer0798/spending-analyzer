package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.Goal;
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
class GoalRepositoryTest {

    @Autowired
    private GoalRepository repository;

    @Test
    @DisplayName("creates a goal")
    void createsGoal() {
        Goal saved = repository.create("Emergency fund", 5000.0, "2026-12-31", "USD");

        assertThat(saved.name()).isEqualTo("Emergency fund");
        assertThat(saved.targetAmount()).isEqualTo(5000.0);
        assertThat(saved.targetDate()).isEqualTo("2026-12-31");
        assertThat(saved.currency()).isEqualTo("USD");
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("creates a goal with no target date")
    void createsGoalWithNoTargetDate() {
        Goal saved = repository.create("Rainy day", 1000.0, null, "USD");

        assertThat(saved.targetDate()).isNull();
    }

    @Test
    @DisplayName("updates only the fields supplied")
    void updatesOnlySuppliedFields() {
        Goal saved = repository.create("Vacation", 2000.0, "2026-08-01", "USD");

        boolean updated = repository.update(saved.id(), null, 2500.0, null);

        assertThat(updated).isTrue();
        Goal reloaded = repository.findById(saved.id()).orElseThrow();
        assertThat(reloaded.name()).isEqualTo("Vacation");
        assertThat(reloaded.targetAmount()).isEqualTo(2500.0);
        assertThat(reloaded.targetDate()).isEqualTo("2026-08-01");
    }

    @Test
    @DisplayName("update with nothing supplied reports false and changes nothing")
    void updateWithNothingSuppliedDoesNothing() {
        Goal saved = repository.create("Vacation", 2000.0, null, "USD");

        assertThat(repository.update(saved.id(), null, null, null)).isFalse();
    }

    @Test
    @DisplayName("deletes a goal, and reports a missing one as false")
    void deletesGoal() {
        Goal saved = repository.create("Vacation", 2000.0, null, "USD");

        assertThat(repository.delete(saved.id())).isTrue();
        assertThat(repository.findAll()).isEmpty();
        assertThat(repository.delete(saved.id())).isFalse();
    }
}
