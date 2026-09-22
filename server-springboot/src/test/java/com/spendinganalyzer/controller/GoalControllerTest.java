package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.GoalProgress;
import com.spendinganalyzer.model.Goal;
import com.spendinganalyzer.model.GoalContribution;
import com.spendinganalyzer.repository.GoalContributionRepository;
import com.spendinganalyzer.repository.GoalRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GoalControllerTest {

    @Autowired
    private GoalController controller;

    @Autowired
    private GoalRepository goals;

    @Autowired
    private GoalContributionRepository contributions;

    @Test
    @DisplayName("creates a goal")
    void createsGoal() {
        ResponseEntity<?> response = controller.create(Map.of(
                "name", "Emergency fund", "target_amount", 5000.0, "target_date", "2026-12-31"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Goal saved = (Goal) response.getBody();
        assertThat(saved.name()).isEqualTo("Emergency fund");
        assertThat(saved.currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("rejects a missing name")
    void rejectsMissingName() {
        ResponseEntity<?> response = controller.create(Map.of("target_amount", 100.0));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects a zero or negative target_amount")
    void rejectsNonPositiveTargetAmount() {
        ResponseEntity<?> response = controller.create(Map.of("name", "Vacation", "target_amount", 0.0));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects a malformed target_date")
    void rejectsMalformedTargetDate() {
        ResponseEntity<?> response = controller.create(
                Map.of("name", "Vacation", "target_amount", 100.0, "target_date", "not-a-date"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects an invalid currency")
    void rejectsInvalidCurrency() {
        ResponseEntity<?> response = controller.create(
                Map.of("name", "Vacation", "target_amount", 100.0, "currency", "NOTREAL"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("lists progress for every goal")
    void listsProgress() {
        controller.create(Map.of("name", "Emergency fund", "target_amount", 1000.0));

        var list = controller.list();

        assertThat(list).hasSize(1);
        assertThat(list.get(0)).isInstanceOf(GoalProgress.class);
    }

    @Test
    @DisplayName("updates only the fields supplied, and reports a missing goal as 404")
    void updatesGoal() {
        Goal saved = goals.create("Vacation", 1000.0, null, "USD");

        ResponseEntity<?> response = controller.update(saved.id(), Map.of("target_amount", 1500.0));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((Goal) response.getBody()).targetAmount()).isEqualTo(1500.0);
        assertThat(controller.update(9999L, Map.of("target_amount", 1.0)).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("rejects an update with nothing to change")
    void rejectsEmptyUpdate() {
        Goal saved = goals.create("Vacation", 1000.0, null, "USD");

        ResponseEntity<?> response = controller.update(saved.id(), Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("deleting a goal also deletes its contributions, and reports a missing goal as 404")
    void deletingGoalDeletesContributions() {
        Goal saved = goals.create("Vacation", 1000.0, null, "USD");
        contributions.add(saved.id(), 100.0, "2026-06-01", null);

        assertThat(controller.delete(saved.id()).getStatusCode().value()).isEqualTo(200);
        assertThat(contributions.findByGoalId(saved.id())).isEmpty();
        assertThat(controller.delete(saved.id()).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("adds a contribution, and reports a missing goal as 404")
    void addsContribution() {
        Goal saved = goals.create("Vacation", 1000.0, null, "USD");

        ResponseEntity<?> response = controller.addContribution(
                saved.id(), Map.of("amount", 200.0, "date", "2026-06-01", "note", "first deposit"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((GoalContribution) response.getBody()).amount()).isEqualTo(200.0);
        assertThat(controller.addContribution(9999L, Map.of("amount", 1.0, "date", "2026-06-01"))
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("rejects a zero contribution amount and a missing date")
    void rejectsInvalidContribution() {
        Goal saved = goals.create("Vacation", 1000.0, null, "USD");

        assertThat(controller.addContribution(saved.id(), Map.of("amount", 0.0, "date", "2026-06-01"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.addContribution(saved.id(), Map.of("amount", 100.0))
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("lists a goal's contribution history, and reports a missing goal as 404")
    void listsContributions() {
        Goal saved = goals.create("Vacation", 1000.0, null, "USD");
        contributions.add(saved.id(), 100.0, "2026-06-01", null);

        ResponseEntity<?> response = controller.listContributions(saved.id());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((java.util.List<?>) response.getBody()).hasSize(1);
        assertThat(controller.listContributions(9999L).getStatusCode().value()).isEqualTo(404);
    }

    // --- split contribution ------------------------------------------------------

    @Test
    @DisplayName("splits one contribution across several goals in one request")
    void splitsAcrossGoals() {
        Goal a = goals.create("Vacation", 1000.0, null, "USD");
        Goal b = goals.create("Emergency fund", 5000.0, null, "USD");

        ResponseEntity<?> response = controller.addSplitContribution(Map.of(
                "date", "2026-06-01",
                "note", "Paycheck savings",
                "splits", java.util.List.of(
                        Map.of("goal_id", a.id(), "amount", 300.0),
                        Map.of("goal_id", b.id(), "amount", 200.0))));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var created = (List<GoalContribution>) response.getBody();
        assertThat(created).hasSize(2);
        assertThat(contributions.findByGoalId(a.id())).singleElement()
                .extracting(GoalContribution::amount, GoalContribution::note)
                .containsExactly(300.0, "Paycheck savings");
        assertThat(contributions.findByGoalId(b.id())).singleElement()
                .extracting(GoalContribution::amount, GoalContribution::note)
                .containsExactly(200.0, "Paycheck savings");
    }

    @Test
    @DisplayName("a split contribution needs no shared currency across goals -- each amount is its own goal's face value")
    void splitAcrossDifferentCurrenciesIsAllowed() {
        Goal usd = goals.create("US trip", 1000.0, null, "USD");
        Goal eur = goals.create("EU trip", 1000.0, null, "EUR");

        ResponseEntity<?> response = controller.addSplitContribution(Map.of(
                "date", "2026-06-01",
                "splits", java.util.List.of(
                        Map.of("goal_id", usd.id(), "amount", 300.0),
                        Map.of("goal_id", eur.id(), "amount", 200.0))));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(contributions.findByGoalId(usd.id())).singleElement()
                .extracting(GoalContribution::amount).isEqualTo(300.0);
        assertThat(contributions.findByGoalId(eur.id())).singleElement()
                .extracting(GoalContribution::amount).isEqualTo(200.0);
    }

    @Test
    @DisplayName("a note is optional on a split contribution, same as a single one")
    void splitContributionNoteIsOptional() {
        Goal a = goals.create("Vacation", 1000.0, null, "USD");

        controller.addSplitContribution(Map.of(
                "date", "2026-06-01",
                "splits", java.util.List.of(Map.of("goal_id", a.id(), "amount", 100.0))));

        assertThat(contributions.findByGoalId(a.id())).singleElement()
                .extracting(GoalContribution::note).isNull();
    }

    @Test
    @DisplayName("rejects a missing or malformed date")
    void rejectsSplitWithBadDate() {
        Goal a = goals.create("Vacation", 1000.0, null, "USD");
        var oneSplit = java.util.List.of(Map.of("goal_id", (Object) a.id(), "amount", (Object) 100.0));

        assertThat(controller.addSplitContribution(Map.of("splits", oneSplit)).getStatusCode().value())
                .isEqualTo(400);
        assertThat(controller.addSplitContribution(Map.of("date", "not-a-date", "splits", oneSplit))
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects an empty or missing splits list")
    void rejectsEmptySplits() {
        assertThat(controller.addSplitContribution(Map.of("date", "2026-06-01")).getStatusCode().value())
                .isEqualTo(400);
        assertThat(controller.addSplitContribution(Map.of("date", "2026-06-01", "splits", java.util.List.of()))
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects an entry with a zero amount or a missing goal_id, and writes nothing")
    void rejectsInvalidSplitEntry() {
        Goal a = goals.create("Vacation", 1000.0, null, "USD");

        ResponseEntity<?> zeroAmount = controller.addSplitContribution(Map.of(
                "date", "2026-06-01",
                "splits", java.util.List.of(Map.of("goal_id", a.id(), "amount", 0.0))));
        assertThat(zeroAmount.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<?> missingGoalId = controller.addSplitContribution(Map.of(
                "date", "2026-06-01",
                "splits", java.util.List.of(Map.of("amount", 100.0))));
        assertThat(missingGoalId.getStatusCode().value()).isEqualTo(400);

        assertThat(contributions.findByGoalId(a.id())).isEmpty();
    }

    @Test
    @DisplayName("rejects an unknown goal_id, and writes nothing for the goals that did exist")
    void rejectsUnknownGoalIdAndWritesNothing() {
        Goal a = goals.create("Vacation", 1000.0, null, "USD");

        ResponseEntity<?> response = controller.addSplitContribution(Map.of(
                "date", "2026-06-01",
                "splits", java.util.List.of(
                        Map.of("goal_id", a.id(), "amount", 100.0),
                        Map.of("goal_id", 999999L, "amount", 50.0))));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        // The valid entry must not have been written either -- a rejected request writes nothing.
        assertThat(contributions.findByGoalId(a.id())).isEmpty();
    }

    @Test
    @DisplayName("rejects the same goal_id appearing twice in one split")
    void rejectsDuplicateGoalIdInSplit() {
        Goal a = goals.create("Vacation", 1000.0, null, "USD");

        ResponseEntity<?> response = controller.addSplitContribution(Map.of(
                "date", "2026-06-01",
                "splits", java.util.List.of(
                        Map.of("goal_id", a.id(), "amount", 100.0),
                        Map.of("goal_id", a.id(), "amount", 50.0))));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(contributions.findByGoalId(a.id())).isEmpty();
    }

    @Test
    @DisplayName("each split entry becomes its own independently-removable contribution")
    void splitEntriesAreIndependentlyRemovable() {
        Goal a = goals.create("Vacation", 1000.0, null, "USD");
        Goal b = goals.create("Emergency fund", 5000.0, null, "USD");

        controller.addSplitContribution(Map.of(
                "date", "2026-06-01",
                "splits", java.util.List.of(
                        Map.of("goal_id", a.id(), "amount", 100.0),
                        Map.of("goal_id", b.id(), "amount", 50.0))));

        long aContributionId = contributions.findByGoalId(a.id()).get(0).id();
        controller.deleteContribution(a.id(), aContributionId);

        assertThat(contributions.findByGoalId(a.id())).isEmpty();
        assertThat(contributions.findByGoalId(b.id())).hasSize(1); // untouched
    }

    @Test
    @DisplayName("deletes a contribution, and reports a missing one as 404")
    void deletesContribution() {
        Goal saved = goals.create("Vacation", 1000.0, null, "USD");
        GoalContribution contribution = contributions.add(saved.id(), 100.0, "2026-06-01", null);

        assertThat(controller.deleteContribution(saved.id(), contribution.id()).getStatusCode().value())
                .isEqualTo(200);
        assertThat(controller.deleteContribution(saved.id(), contribution.id()).getStatusCode().value())
                .isEqualTo(404);
    }
}
