package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.GoalProgress;
import com.spendinganalyzer.repository.GoalContributionRepository;
import com.spendinganalyzer.repository.GoalRepository;
import com.spendinganalyzer.service.GoalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Savings goals: a target amount, optionally a date, with progress measured from contributions
 * logged by hand rather than inferred from transactions — see the goals migration for why.
 */
@RestController
@RequestMapping("/api/goals")
public class GoalController {

    private final GoalRepository goals;
    private final GoalContributionRepository contributions;
    private final GoalService goalService;

    public GoalController(GoalRepository goals, GoalContributionRepository contributions, GoalService goalService) {
        this.goals = goals;
        this.contributions = contributions;
        this.goalService = goalService;
    }

    @GetMapping
    public List<GoalProgress> list() {
        return goalService.progress();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String name = body.get("name") instanceof String s ? s.trim() : "";
        Double targetAmount = body.get("target_amount") instanceof Number n ? n.doubleValue() : null;
        String targetDate = asTrimmedString(body.get("target_date"));
        String currency = body.get("currency") instanceof String s && !s.isBlank() ? s.trim() : "USD";

        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required."));
        }
        if (targetAmount == null || targetAmount <= 0) {
            return ResponseEntity.badRequest().body(new ErrorResponse("target_amount must be greater than zero."));
        }
        if (targetDate != null && !isValidDate(targetDate)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("target_date must be in YYYY-MM-DD form."));
        }
        if (!isValidCurrency(currency)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("currency must be a valid ISO 4217 code."));
        }

        return ResponseEntity.ok(goals.create(name, targetAmount, targetDate, currency.toUpperCase()));
    }

    /** Every field is optional; currency is fixed at creation — see {@link GoalRepository#update}. */
    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (goals.findById(id).isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Goal not found."));
        }

        String name = body.get("name") instanceof String s && !s.isBlank() ? s.trim() : null;
        Double targetAmount = body.get("target_amount") instanceof Number n ? n.doubleValue() : null;
        String targetDate = asTrimmedString(body.get("target_date"));

        if (targetAmount != null && targetAmount <= 0) {
            return ResponseEntity.badRequest().body(new ErrorResponse("target_amount must be greater than zero."));
        }
        if (targetDate != null && !isValidDate(targetDate)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("target_date must be in YYYY-MM-DD form."));
        }
        if (name == null && targetAmount == null && targetDate == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "Nothing to update. Supply at least one of: name, target_amount, target_date."));
        }

        goals.update(id, name, targetAmount, targetDate);
        return ResponseEntity.ok(goals.findById(id).orElseThrow());
    }

    /** Deletes a goal and everything logged toward it — there are no foreign keys in this schema to do that automatically. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        if (!goals.delete(id)) {
            return ResponseEntity.status(404).body(new ErrorResponse("Goal not found."));
        }
        contributions.deleteByGoalId(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/{id}/contributions")
    public ResponseEntity<?> listContributions(@PathVariable long id) {
        if (goals.findById(id).isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Goal not found."));
        }
        return ResponseEntity.ok(contributions.findByGoalId(id));
    }

    @PostMapping("/{id}/contributions")
    public ResponseEntity<?> addContribution(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (goals.findById(id).isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Goal not found."));
        }

        Double amount = body.get("amount") instanceof Number n ? n.doubleValue() : null;
        String date = asTrimmedString(body.get("date"));
        String note = body.get("note") instanceof String s && !s.isBlank() ? s.trim() : null;

        if (amount == null || amount == 0) {
            return ResponseEntity.badRequest().body(new ErrorResponse("amount is required and must not be zero."));
        }
        if (date == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("date is required."));
        }
        if (!isValidDate(date)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("date must be in YYYY-MM-DD form."));
        }

        return ResponseEntity.ok(contributions.add(id, amount, date, note));
    }

    /**
     * Logs one real-world contribution split across several goals in one request, instead of
     * calling {@code POST /{id}/contributions} once per goal. Every entry shares the same date
     * and (optional) note; each still becomes its own ordinary contribution row afterward -- this
     * endpoint only saves the repetition of typing the date and note N times, and of second-
     * guessing whether all N calls actually went through, since a validation failure on any entry
     * here rejects the whole request before anything is written.
     *
     * <p>There is no currency check across the selected goals: an entry's amount is that goal's
     * own contribution, taken at face value, the same as a single contribution always has been.
     * Splitting one figure into percentages only makes sense when the target goals share a
     * currency, but that is the client's concern when computing each entry's amount -- by the
     * time a request reaches here, "goal A gets $300, goal B gets €200" and "a $500 payday split
     * 60/40" are indistinguishable, and neither needs to be.
     */
    @PostMapping("/contributions/split")
    public ResponseEntity<?> addSplitContribution(@RequestBody Map<String, Object> body) {
        String date = asTrimmedString(body.get("date"));
        String note = body.get("note") instanceof String s && !s.isBlank() ? s.trim() : null;

        if (date == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("date is required."));
        }
        if (!isValidDate(date)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("date must be in YYYY-MM-DD form."));
        }
        if (!(body.get("splits") instanceof List<?> rawSplits) || rawSplits.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("splits is required and must not be empty."));
        }

        List<GoalService.SplitEntry> splits = new ArrayList<>();
        Set<Long> seenGoalIds = new HashSet<>();
        for (Object rawEntry : rawSplits) {
            if (!(rawEntry instanceof Map<?, ?> entry)) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("each split entry must be an object with goal_id and amount."));
            }
            Long goalId = entry.get("goal_id") instanceof Number n ? n.longValue() : null;
            Double amount = entry.get("amount") instanceof Number n ? n.doubleValue() : null;

            if (goalId == null) {
                return ResponseEntity.badRequest().body(new ErrorResponse("each split entry needs a goal_id."));
            }
            if (amount == null || amount == 0) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("each split entry needs a nonzero amount."));
            }
            if (!seenGoalIds.add(goalId)) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("goal_id " + goalId + " appears more than once in splits."));
            }
            if (goals.findById(goalId).isEmpty()) {
                return ResponseEntity.badRequest().body(new ErrorResponse("Goal not found: " + goalId));
            }
            splits.add(new GoalService.SplitEntry(goalId, amount));
        }

        return ResponseEntity.ok(goalService.addSplitContributions(splits, date, note));
    }

    /** Removes one logged contribution — undoing a mistaken entry rather than editing it in place. */
    @DeleteMapping("/{goalId}/contributions/{contributionId}")
    public ResponseEntity<?> deleteContribution(@PathVariable long goalId, @PathVariable long contributionId) {
        if (!contributions.delete(contributionId)) {
            return ResponseEntity.status(404).body(new ErrorResponse("Contribution not found."));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static String asTrimmedString(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static boolean isValidDate(String s) {
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isValidCurrency(String code) {
        try {
            Currency.getInstance(code.toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
