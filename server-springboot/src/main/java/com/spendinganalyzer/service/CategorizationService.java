package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategorizeResponse;
import com.spendinganalyzer.dto.ReapplyRulesResult;
import com.spendinganalyzer.model.KeywordRule;
import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.KeywordRuleRepository;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Assigns categories to uncategorized transactions, entirely in-process: merchant memory first,
 * then the user's own keyword rules, then {@link RuleBasedCategorizer}'s built-in ones. Nothing
 * leaves the machine.
 *
 * <p>Memory comes first because it's the most specific thing the user has said -- this exact
 * merchant goes here -- so it outranks a keyword rule, which outranks a built-in one: the user's
 * word beats the app's guess. Rule matches are <em>not</em> written back into memory: rules are
 * re-evaluated on every run, so improving a rule improves every later import, whereas a remembered
 * copy would freeze today's answer. Memory stays exactly what its name says: what the user taught.
 *
 * <p>Whatever neither recognises stays uncategorized for the user to set by hand, which is what
 * teaches memory -- see {@link RuleBasedCategorizer} for why a blank beats a guess.
 */
@Service
public class CategorizationService {

    /** How many example changes a re-apply reports -- enough to judge it, not a full listing. */
    static final int MAX_REAPPLY_EXAMPLES = 10;

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantCategoryRepository merchantCategoryRepository;
    private final KeywordRuleRepository keywordRuleRepository;

    public CategorizationService(
            TransactionRepository transactionRepository,
            CategoryRepository categoryRepository,
            MerchantCategoryRepository merchantCategoryRepository,
            KeywordRuleRepository keywordRuleRepository
    ) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.merchantCategoryRepository = merchantCategoryRepository;
        this.keywordRuleRepository = keywordRuleRepository;
    }

    private enum Kind { MEMORY, KEYWORD, BUILT_IN }

    /** @param memoryRuleId the merchant-memory row that answered, for hit counting; null otherwise */
    private record Decision(String category, String source, Kind kind, Long memoryRuleId) {}

    /**
     * Memory, then keyword rules, then built-in rules -- the one place that order is defined, so
     * categorizing new rows and re-applying rules to old ones can never disagree about it. Built
     * once per run: memory and rules are loaded once, not per transaction.
     */
    private class Decider {
        private final Set<String> validCategories = new HashSet<>(categoryRepository.findAllNames());
        private final Map<String, List<MerchantCategory>> memory = merchantCategoryRepository.loadAll();
        private final KeywordRuleMatcher keywordRules = new KeywordRuleMatcher(keywordRuleRepository.findAll());

        Optional<Decision> decide(Transaction t) {
            // Which memory rule applies depends on the amount, so this is per transaction.
            MerchantCategory remembered = MerchantCategory
                    .bestMatch(memory.getOrDefault(MerchantNormalizer.normalize(t.description()), List.of()), t.amount())
                    .orElse(null);
            // A remembered category is only usable while that category still exists.
            if (remembered != null && validCategories.contains(remembered.category())) {
                return Optional.of(new Decision(remembered.category(), "cache", Kind.MEMORY, remembered.id()));
            }

            // Labelled 'rule' like a built-in match: both are rules rather than a per-merchant
            // correction, and the responses report which kind answered.
            Optional<KeywordRule> keyword = keywordRules.match(t.description(), validCategories);
            if (keyword.isPresent()) {
                return Optional.of(new Decision(keyword.get().category(), "rule", Kind.KEYWORD, null));
            }

            return RuleBasedCategorizer.categorize(t.description(), t.type(), validCategories)
                    .map(category -> new Decision(category, "rule", Kind.BUILT_IN, null));
        }
    }

    public CategorizeResponse categorizeAll() {
        List<Transaction> uncategorized = transactionRepository.findUncategorized();
        if (uncategorized.isEmpty()) {
            return CategorizeResponse.noneFound();
        }

        Decider decider = new Decider();
        int fromMemory = 0;
        int fromKeywordRules = 0;
        int fromRules = 0;
        // Keyed by rule id, not merchant: a merchant with several bands should only credit the
        // band that actually answered.
        Map<Long, Integer> memoryHits = new HashMap<>();

        for (Transaction t : uncategorized) {
            Optional<Decision> decision = decider.decide(t);
            if (decision.isEmpty()) continue;

            Decision d = decision.get();
            transactionRepository.updateCategory(t.id(), d.category(), d.source());
            switch (d.kind()) {
                case MEMORY -> {
                    memoryHits.merge(d.memoryRuleId(), 1, Integer::sum);
                    fromMemory++;
                }
                case KEYWORD -> fromKeywordRules++;
                case BUILT_IN -> fromRules++;
            }
        }
        merchantCategoryRepository.recordHits(memoryHits);

        return CategorizeResponse.of(fromMemory, fromKeywordRules, fromRules, uncategorized.size());
    }

    /**
     * Re-decides every transaction a <em>rule</em> categorized ({@code category_source = 'rule'})
     * against today's memory and rules -- after adding, changing or deleting a keyword rule, or a
     * built-in rule improving in an update. Nothing else is touched: a category the user set
     * ('user'), one merchant memory applied ('cache'), one read from the imported file ('import'),
     * or one a language model chose before the built-in rules existed ('ai') all stay exactly as
     * they are.
     *
     * <p>A rule-set category no rule supports any more goes back to uncategorized rather than
     * keeping a label nothing now justifies -- the review queue then asks about it.
     *
     * @param dryRun report what would change without writing anything, for a confirm step
     */
    @Transactional
    public ReapplyRulesResult reapplyRules(boolean dryRun) {
        List<Transaction> ruleSet = transactionRepository.findByCategorySource("rule");
        Decider decider = new Decider();

        int changed = 0;
        int cleared = 0;
        Map<Long, Integer> memoryHits = new HashMap<>();
        List<ReapplyRulesResult.Change> examples = new ArrayList<>();

        for (Transaction t : ruleSet) {
            Optional<Decision> decision = decider.decide(t);
            String newCategory = decision.map(Decision::category).orElse(null);
            String newSource = decision.map(Decision::source).orElse(null);
            if (Objects.equals(newCategory, t.category()) && Objects.equals(newSource, t.categorySource())) {
                continue;
            }

            if (newCategory == null) cleared++; else changed++;
            if (examples.size() < MAX_REAPPLY_EXAMPLES) {
                examples.add(new ReapplyRulesResult.Change(t.id(), t.description(), t.category(), newCategory));
            }
            if (!dryRun) {
                transactionRepository.updateCategory(t.id(), newCategory, newSource);
                decision.filter(d -> d.kind() == Kind.MEMORY)
                        .ifPresent(d -> memoryHits.merge(d.memoryRuleId(), 1, Integer::sum));
            }
        }
        if (!dryRun) merchantCategoryRepository.recordHits(memoryHits);

        return new ReapplyRulesResult(dryRun, ruleSet.size(), changed, cleared,
                ruleSet.size() - changed - cleared, examples);
    }
}
