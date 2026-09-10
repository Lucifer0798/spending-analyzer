package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.GoalContribution;
import com.spendinganalyzer.repository.GoalContributionRepository.GoalTotals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GoalContributionRepositoryTest {

    @Autowired
    private GoalRepository goals;

    @Autowired
    private GoalContributionRepository repository;

    private long goalId;

    @BeforeEach
    void createGoal() {
        goalId = goals.create("Emergency fund", 5000.0, null, "USD").id();
    }

    @Test
    @DisplayName("adds a contribution")
    void addsContribution() {
        GoalContribution saved = repository.add(goalId, 200.0, "2026-06-01", "first deposit");

        assertThat(saved.goalId()).isEqualTo(goalId);
        assertThat(saved.amount()).isEqualTo(200.0);
        assertThat(saved.note()).isEqualTo("first deposit");
        assertThat(repository.findByGoalId(goalId)).hasSize(1);
    }

    @Test
    @DisplayName("a negative amount is a withdrawal, and still counts toward the total")
    void negativeAmountIsAWithdrawal() {
        repository.add(goalId, 200.0, "2026-06-01", null);
        repository.add(goalId, -50.0, "2026-06-15", "oops, needed it back");

        Map<Long, GoalTotals> totals = repository.totalsByGoal();

        assertThat(totals.get(goalId).sum()).isEqualTo(150.0);
        assertThat(totals.get(goalId).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("totalsByGoal omits a goal with no contributions rather than reporting zero")
    void totalsByGoalOmitsGoalsWithNoContributions() {
        assertThat(repository.totalsByGoal()).doesNotContainKey(goalId);
    }

    @Test
    @DisplayName("findByGoalId is newest first")
    void findByGoalIdIsNewestFirst() {
        repository.add(goalId, 100.0, "2026-06-01", null);
        repository.add(goalId, 100.0, "2026-06-15", null);

        assertThat(repository.findByGoalId(goalId)).extracting("date").containsExactly("2026-06-15", "2026-06-01");
    }

    @Test
    @DisplayName("deletes a contribution, and reports a missing one as false")
    void deletesContribution() {
        GoalContribution saved = repository.add(goalId, 100.0, "2026-06-01", null);

        assertThat(repository.delete(saved.id())).isTrue();
        assertThat(repository.findByGoalId(goalId)).isEmpty();
        assertThat(repository.delete(saved.id())).isFalse();
    }

    @Test
    @DisplayName("deleteByGoalId removes every contribution for that goal only")
    void deleteByGoalIdRemovesOnlyThatGoalsContributions() {
        long otherGoalId = goals.create("Vacation", 1000.0, null, "USD").id();
        repository.add(goalId, 100.0, "2026-06-01", null);
        repository.add(otherGoalId, 50.0, "2026-06-01", null);

        repository.deleteByGoalId(goalId);

        assertThat(repository.findByGoalId(goalId)).isEmpty();
        assertThat(repository.findByGoalId(otherGoalId)).hasSize(1);
    }
}
