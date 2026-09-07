package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.model.RecurringOverride;
import com.spendinganalyzer.repository.RecurringOverrideRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Manages the "cancel" and "exclude" flags a merchant can carry on the Recurring page — set
 * there, in context next to the series they apply to, but listed and cleared from Manage, the
 * same split merchant memory's rules follow.
 */
@RestController
@RequestMapping("/api/recurring/overrides")
public class RecurringOverrideController {

    private final RecurringOverrideRepository repository;

    public RecurringOverrideController(RecurringOverrideRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<RecurringOverride> list() {
        return repository.findAll();
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody Map<String, String> body) {
        String merchantKey = body.get("merchant_key") == null ? "" : body.get("merchant_key").trim();
        String action = body.get("action");

        if (merchantKey.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("merchant_key is required."));
        }
        if (!RecurringOverride.ACTION_CANCEL.equals(action) && !RecurringOverride.ACTION_EXCLUDE.equals(action)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "action must be '" + RecurringOverride.ACTION_CANCEL + "' or '" + RecurringOverride.ACTION_EXCLUDE + "'."));
        }

        return ResponseEntity.ok(repository.upsert(merchantKey, action));
    }

    /** Clears an override — un-flags a cancellation, or brings an excluded merchant back into view. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        if (!repository.delete(id)) {
            return ResponseEntity.status(404).body(new ErrorResponse("Override not found."));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
