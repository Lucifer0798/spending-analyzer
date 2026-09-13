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

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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

    /** A fixed instant so a pace projection can be asserted exactly instead of racing the clock. */
    private static GoalService serviceAsOf(GoalRepository goals, GoalContributionRepository contributions, LocalDate today) {
        Clock clock = Clock.fixed(today.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        return new GoalService(goals, contributions, clock);
    }

    @Test
    @DisplayName("a goal with no contributions shows zero saved, not achieved, and no pace to project from")
    void goalWithNoContributions() {
        goals.create("Emergency fund", 1000.0, null, "USD");

        GoalProgress progress = service.progress().get(0);

        assertThat(progress.saved()).isEqualTo(0.0);
        assertThat(progress.remaining()).isEqualTo(1000.0);
        assertThat(progress.percentComplete()).isEqualTo(0.0);
        assertThat(progress.achieved()).isFalse();
        assertThat(progress.contributionCount()).isEqualTo(0);
        assertThat(progress.monthlyPace()).isEqualTo(0.0);
        assertThat(progress.projectedCompletionDate()).isNull();
    }

    @Test
    @DisplayName("projects a completion date from the pace since the first contribution")
    void projectsCompletionDateFromPace() {
        long id = goals.create("Vacation", 1000.0, null, "USD").id();
        contributions.add(id, 300.0, "2026-06-01", null);

        // $300 over 30 days is $10/day, i.e. $304.40/month; $700 left to go at that rate takes
        // exactly 70 days (the 30.44-day-per-month conversion cancels out algebraically).
        LocalDate today = LocalDate.of(2026, 7, 1);
        GoalProgress progress = serviceAsOf(goals, contributions, today).progress().get(0);

        assertThat(progress.monthlyPace()).isEqualTo(304.4);
        assertThat(progress.projectedCompletionDate()).isEqualTo(today.plusDays(70).toString());
    }

    @Test
    @DisplayName("an achieved goal has nothing left to project")
    void achievedGoalHasNoProjection() {
        long id = goals.create("Small target", 100.0, null, "USD").id();
        contributions.add(id, 150.0, "2026-06-01", null);

        LocalDate today = LocalDate.of(2026, 7, 1);
        GoalProgress progress = serviceAsOf(goals, contributions, today).progress().get(0);

        assertThat(progress.achieved()).isTrue();
        assertThat(progress.projectedCompletionDate()).isNull();
    }

    @Test
    @DisplayName("a net-negative pace has no projection, since it would never arrive")
    void negativePaceHasNoProjection() {
        long id = goals.create("Vacation", 1000.0, null, "USD").id();
        contributions.add(id, 100.0, "2026-06-01", null);
        contributions.add(id, -150.0, "2026-06-15", "had to dip in");

        LocalDate today = LocalDate.of(2026, 7, 1);
        GoalProgress progress = serviceAsOf(goals, contributions, today).progress().get(0);

        assertThat(progress.monthlyPace()).isLessThan(0);
        assertThat(progress.projectedCompletionDate()).isNull();
    }

    @Test
    @DisplayName("a contribution made today doesn't divide by zero days elapsed")
    void contributionMadeTodayDoesNotDivideByZero() {
        long id = goals.create("Vacation", 1000.0, null, "USD").id();
        LocalDate today = LocalDate.of(2026, 7, 1);
        contributions.add(id, 100.0, today.toString(), null);

        GoalProgress progress = serviceAsOf(goals, contributions, today).progress().get(0);

        assertThat(progress.monthlyPace()).isGreaterThan(0);
        assertThat(progress.projectedCompletionDate()).isNotNull();
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
