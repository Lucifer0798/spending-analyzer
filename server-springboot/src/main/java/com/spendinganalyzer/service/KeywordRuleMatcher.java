package com.spendinganalyzer.service;

import com.spendinganalyzer.model.KeywordRule;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The user's keyword rules, compiled once per categorization run and tried in
 * {@link com.spendinganalyzer.repository.KeywordRuleRepository#findAll} order (longest keyword
 * first). Matching is {@link RuleBasedCategorizer#wholeWord}, the same as the built-in rules.
 */
public final class KeywordRuleMatcher {

    private record Compiled(KeywordRule rule, Pattern pattern) {}

    private final List<Compiled> rules;

    public KeywordRuleMatcher(List<KeywordRule> rules) {
        this.rules = rules.stream().map(r -> new Compiled(r, RuleBasedCategorizer.wholeWord(r.keyword()))).toList();
    }

    /**
     * The first rule matching {@code description} whose category still exists -- one pointing at a
     * category deleted since is skipped, though deletes and renames normally cascade to rules.
     */
    public Optional<KeywordRule> match(String description, Set<String> validCategories) {
        String text = RuleBasedCategorizer.matchText(description);
        for (Compiled c : rules) {
            if (validCategories.contains(c.rule().category()) && c.pattern().matcher(text).find()) {
                return Optional.of(c.rule());
            }
        }
        return Optional.empty();
    }

    /** Whether {@code keyword} alone matches {@code description}, for previewing a rule before saving it. */
    public static boolean matches(String keyword, String description) {
        return RuleBasedCategorizer.wholeWord(keyword).matcher(RuleBasedCategorizer.matchText(description)).find();
    }
}
