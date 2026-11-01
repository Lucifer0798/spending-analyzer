package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.model.Category;
import com.spendinganalyzer.model.Challenge;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.ChallengeRepository;
import com.spendinganalyzer.service.ChallengeService;
import com.spendinganalyzer.service.ChangeLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Spending challenges -- see {@link ChallengeService}. */
@RestController
@RequestMapping("/api/challenges")
public class ChallengeController {

    static final int MAX_NAME_LENGTH = 80;

    private final ChallengeRepository challenges;
    private final ChallengeService service;
    private final CategoryRepository categories;
    private final AccountRepository accounts;
    private final ChangeLogService changeLog;

    public ChallengeController(ChallengeRepository challenges, ChallengeService service, CategoryRepository categories,
                               AccountRepository accounts, ChangeLogService changeLog) {
        this.challenges = challenges;
        this.service = service;
        this.categories = categories;
        this.accounts = accounts;
        this.changeLog = changeLog;
    }

    /** Every challenge with how it's going, newest start first. */
    @GetMapping
    public Map<String, Object> list() {
        return Map.of("challenges", service.all());
    }

    /**
     * Body: {@code {"category": "Dining & Coffee" | null, "limitAmount": 0, "startDate": "2026-11-01",
     * "endDate": "2026-11-14", "accountId": null, "name": optional}} -- a limit of 0 is "no spending".
     */
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String category = body.get("category") instanceof String s && !s.isBlank() ? s.trim() : null;
        Double limit = body.get("limitAmount") instanceof Number n ? n.doubleValue() : null;
        Long accountId = body.get("accountId") instanceof Number n ? n.longValue() : null;
        String name = body.get("name") instanceof String s ? s.trim() : "";

        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(String.valueOf(body.get("startDate")));
            end = LocalDate.parse(String.valueOf(body.get("endDate")));
        } catch (DateTimeParseException e) {
            return bad("startDate and endDate must be dates like 2026-11-01.");
        }
        if (end.isBefore(start)) return bad("endDate can't be before startDate.");
        if (ChronoUnit.DAYS.between(start, end) + 1 > ChallengeService.MAX_DAYS) {
            return bad("A challenge can run for at most " + ChallengeService.MAX_DAYS + " days.");
        }
        if (limit == null || limit < 0 || !Double.isFinite(limit)) {
            return bad("limitAmount must be zero (no spending) or more.");
        }
        if (category != null) {
            Optional<Category> found = categories.findAll().stream().filter(c -> c.name().equals(category)).findFirst();
            if (found.isEmpty()) return bad("No category called \"" + category + "\".");
            if (found.get().isIncome() || found.get().isTransfer()) {
                return bad("\"" + category + "\" isn't spending, so there's nothing to challenge.");
            }
        }
        if (accountId != null && accounts.findById(accountId).isEmpty()) return bad("No such account.");
        if (name.length() > MAX_NAME_LENGTH) return bad("name can be at most " + MAX_NAME_LENGTH + " characters.");
        if (name.isEmpty()) name = defaultName(category, limit);

        // AUTOINCREMENT ids only grow, so the new row is the one above today's highest.
        var watch = changeLog.watch("challenges", "id > :maxId", Map.of("maxId", challenges.maxId()));
        Challenge created = challenges.create(name, category, limit, start.toString(), end.toString(), accountId);
        changeLog.recordSettings("challenge", "Started the challenge \"" + name + "\"", watch);
        return ResponseEntity.ok(service.progress(created));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable long id) {
        var watch = changeLog.watch("challenges", "id = :id", Map.of("id", id));
        if (!challenges.delete(id)) return ResponseEntity.status(404).body(new ErrorResponse("Challenge not found."));
        changeLog.recordSettings("challenge", "Deleted the challenge \"" + watch.before().get(0).get("name") + "\"", watch);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** "No spending on Dining & Coffee", "Under 200 on Shopping", "No spending at all". */
    public static String defaultName(String category, double limit) {
        String amount = limit == Math.rint(limit) ? String.valueOf((long) limit) : String.format(Locale.ROOT, "%.2f", limit);
        if (limit == 0) return category == null ? "No spending at all" : "No spending on " + category;
        return "Under " + amount + (category == null ? " in total" : " on " + category);
    }

    private static ResponseEntity<ErrorResponse> bad(String message) {
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }
}
