package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.GoalProgress;
import com.spendinganalyzer.repository.GoalContributionRepository;
import com.spendinganalyzer.repository.GoalRepository;
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
class GoalServiceTest {

    @Autowired
    private GoalService service;

    @Autowired
    private GoalRepository goals;

    @Autowired
    private GoalContributionRepository contributions;

    @Test
    @DisplayName("a goal with no contributions shows zero saved, not achieved")
    void goalWithNoContributions() {
        goals.create("Emergency fund", 1000.0, null, "USD");

        GoalProgress progress = service.progress().get(0);

        assertThat(progress.saved()).isEqualTo(0.0);
        assertThat(progress.remaining()).isEqualTo(1000.0);
        assertThat(progress.percentComplete()).isEqualTo(0.0);
        assertThat(progress.achieved()).isFalse();
        assertThat(progress.contributionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("sums contributions toward saved, including a withdrawal")
    void sumsContributions() {
        long id = goals.create("Vacation", 1000.0, null, "USD").id();
        contributions.add(id, 600.0, "2026-06-01", null);
        contributions.add(id, -100.0, "2026-06-10", "had to dip in");

        GoalProgress progress = service.progress().get(0);

        assertThat(progress.saved()).isEqualTo(500.0);
        assertThat(progress.remaining()).isEqualTo(500.0);
        assertThat(progress.percentComplete()).isEqualTo(50.0);
        assertThat(progress.contributionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a goal met or exceeded reports zero remaining and achieved, not a negative remaining")
    void goalExceeded() {
        long id = goals.create("Small target", 100.0, null, "USD").id();
        contributions.add(id, 150.0, "2026-06-01", null);

        GoalProgress progress = service.progress().get(0);

        assertThat(progress.remaining()).isEqualTo(0.0);
        assertThat(progress.percentComplete()).isEqualTo(150.0);
        assertThat(progress.achieved()).isTrue();
    }

    @Test
    @DisplayName("lists every goal, newest first")
    void listsEveryGoal() {
        goals.create("First", 100.0, null, "USD");
        goals.create("Second", 200.0, null, "USD");

        List<GoalProgress> progress = service.progress();

        assertThat(progress).hasSize(2);
        assertThat(progress).extracting("name").containsExactly("Second", "First");
    }
}
