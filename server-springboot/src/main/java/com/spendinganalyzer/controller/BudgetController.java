package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.model.Budget;
import com.spendinganalyzer.repository.BudgetRepository;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.service.BudgetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class BudgetController {

    private final BudgetRepository budgets;
    private final BudgetService budgetService;
    private final CategoryRepository categories;

    public BudgetController(
            BudgetRepository budgets,
            BudgetService budgetService,
            CategoryRepository categories
    ) {
        this.budgets = budgets;
        this.budgetService = budgetService;
        this.categories = categories;
    }

    /**
     * Every budget with its spend for a month. Omit {@code month} to get the newest month on
     * record, which is what the dashboard shows.
     */
    @GetMapping("/budgets")
    public BudgetService.BudgetSummary list(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String month
    ) {
        return budgetService.progress(accountId, month);
    }

    private static final Set<String> ESCALATION_TYPES = Set.of("fixed", "percent");

    /**
     * Sets a category's monthly target, replacing any existing one -- escalation schedule
     * included, so leaving the escalation fields out clears whatever schedule was there before.
     * An upsert rather than separate create and update calls: "budget Groceries at 500" is a
     * single intent, and the caller should not have to discover whether a budget already exists
     * to express it.
     */
    @PostMapping("/budgets")
    public ResponseEntity<?> set(@RequestBody Map<String, Object> body) {
        String category = body.get("category") instanceof String s ? s.trim() : "";
        Double limit = body.get("monthly_limit") instanceof Number n ? n.doubleValue() : null;

        if (category.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("category is required."));
        }
        if (!categories.exists(category)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "category must be one of: " + String.join(", ", categories.findAllNames())));
        }
        if (limit == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("monthly_limit is required."));
        }
        // A zero or negative target is not a budget, and would make percent-used either
        // meaningless or a division by zero.
        if (limit <= 0) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("monthly_limit must be greater than zero."));
        }

        Object rawType = body.get("escalation_type");
        String escalationType = rawType instanceof String s && !s.isBlank() ? s.trim() : null;
        Integer escalationFrequencyMonths = null;
        Double escalationValue = null;
        String escalationStartMonth = null;

        if (escalationType != null) {
            if (!ESCALATION_TYPES.contains(escalationType)) {
                return ResponseEntity.badRequest().body(new ErrorResponse(
                        "escalation_type must be one of: " + String.join(", ", ESCALATION_TYPES)));
            }

            escalationValue = body.get("escalation_value") instanceof Number n ? n.doubleValue() : null;
            if (escalationValue == null || escalationValue <= 0) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("escalation_value must be greater than zero."));
            }

            escalationFrequencyMonths = body.get("escalation_frequency_months") instanceof Number n
                    ? n.intValue() : null;
            if (escalationFrequencyMonths == null || escalationFrequencyMonths <= 0) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("escalation_frequency_months must be greater than zero."));
            }

            Object rawStart = body.get("escalation_start_month");
            if (rawStart instanceof String s && !s.isBlank()) {
                try {
                    escalationStartMonth = YearMonth.parse(s.trim()).toString();
                } catch (DateTimeParseException e) {
                    return ResponseEntity.badRequest().body(new ErrorResponse(
                            "escalation_start_month must be in YYYY-MM form, got: " + s));
                }
            } else {
                // Unspecified means "starts now" -- the calendar month the schedule was set up in,
                // not the newest month with imported data, since setting up a schedule is a real-time
                // action independent of how far behind statement imports happen to be.
                escalationStartMonth = YearMonth.now().toString();
            }
        }

        // Unlike escalation, rollover has no value of its own to configure -- "rollover: true"
        // is the whole request. Re-enabling an already-enabled rollover preserves its original
        // start month rather than resetting to now, so a resave (changing the limit, say) never
        // discards months of already-accumulated carry-in; the client never needs to know or
        // resend that date itself.
        boolean rolloverRequested = Boolean.TRUE.equals(body.get("rollover"));
        String rolloverStartMonth = null;
        if (rolloverRequested) {
            String existingStart = budgets.findByCategory(category)
                    .map(Budget::rolloverStartMonth)
                    .orElse(null);
            rolloverStartMonth = existingStart != null ? existingStart : YearMonth.now().toString();
        }

        return ResponseEntity.ok(budgets.upsert(
                category, limit, escalationType, escalationValue, escalationFrequencyMonths, escalationStartMonth,
                rolloverStartMonth));
    }

    @DeleteMapping("/budgets/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        if (!budgets.deleteById(id)) {
            return ResponseEntity.status(404).body(new ErrorResponse("Budget not found."));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
