package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.KeywordRuleRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.KeywordRuleMatcher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The user's own categorization rules: "a description containing this word goes in this
 * category". Creating one doesn't touch existing transactions by itself -- the client follows up
 * with {@code POST /api/categorize}, which applies memory, these rules and the built-in rules to
 * whatever is still uncategorized. Categories someone already set are never overwritten.
 */
@RestController
@RequestMapping("/api/keyword-rules")
public class KeywordRuleController {

    static final int MIN_LENGTH = 2;
    static final int MAX_LENGTH = 60;
    static final int MAX_EXAMPLES = 3;

    private final KeywordRuleRepository rules;
    private final CategoryRepository categories;
    private final TransactionRepository transactions;

    public KeywordRuleController(KeywordRuleRepository rules, CategoryRepository categories, TransactionRepository transactions) {
        this.rules = rules;
        this.categories = categories;
        this.transactions = transactions;
    }

    /** In the order they're tried -- longest keyword first. */
    @GetMapping
    public Object list() {
        return rules.findAll();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String keyword = body.get("keyword") instanceof String s ? s.trim().replaceAll("\\s+", " ") : "";
        String category = body.get("category") instanceof String s ? s.trim() : "";

        String problem = validateKeyword(keyword);
        if (problem != null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(problem));
        }
        if (!categories.exists(category)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "category must be one of: " + String.join(", ", categories.findAllNames())));
        }
        if (rules.keywordExists(keyword)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("There's already a rule for \"" + keyword + "\" -- delete it first to change its category."));
        }
        return ResponseEntity.ok(rules.create(keyword, category));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        if (!rules.delete(id)) {
            return ResponseEntity.status(404).body(new ErrorResponse("Rule not found."));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * What a keyword would match, before saving it: how many transactions in all, how many of
     * those are still uncategorized (the ones a new rule would actually fill in), and a few
     * example descriptions so a too-broad word is obvious before it's a rule.
     */
    @GetMapping("/preview")
    public ResponseEntity<?> preview(@RequestParam String keyword) {
        String trimmed = keyword.trim().replaceAll("\\s+", " ");
        String problem = validateKeyword(trimmed);
        if (problem != null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(problem));
        }

        int matches = 0;
        int uncategorized = 0;
        Set<String> examples = new LinkedHashSet<>();
        for (Transaction t : transactions.find(null, null, null, DateRange.ALL, Integer.MAX_VALUE, 0)) {
            if (!KeywordRuleMatcher.matches(trimmed, t.description())) continue;
            matches++;
            if (t.category() == null) uncategorized++;
            if (examples.size() < MAX_EXAMPLES) examples.add(t.description());
        }
        return ResponseEntity.ok(Map.of(
                "keyword", trimmed,
                "matches", matches,
                "uncategorized", uncategorized,
                "examples", new ArrayList<>(examples)));
    }

    /** Null when fine. A keyword must contain a letter or digit: "&" or "--" alone would match noise. */
    static String validateKeyword(String keyword) {
        if (keyword.length() < MIN_LENGTH || keyword.length() > MAX_LENGTH) {
            return "keyword must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters.";
        }
        if (!keyword.matches(".*[\\p{L}\\p{N}].*")) {
            return "keyword must contain at least one letter or digit.";
        }
        return null;
    }
}
