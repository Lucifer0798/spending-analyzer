package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategorizeResponse;
import com.spendinganalyzer.model.KeywordRule;
import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.KeywordRuleRepository;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Assigns categories to uncategorized transactions, entirely in-process: merchant memory first,
 * then the user's own keyword rules, then {@link RuleBasedCategorizer}'s built-in ones. Nothing
 * leaves the machine.
 *
 * <p>Memory comes first because it's the most specific thing the user has said -- this exact
 * merchant goes here -- so it outranks a keyword rule, which outranks a built-in one: the user's
 * word beats the app's guess. Rule matches are <em>not</em> written back into memory: rules are re-evaluated on
 * every run, so improving a rule improves every later import, whereas a remembered copy would
 * freeze today's answer. Memory stays exactly what its name says: what the user taught.
 *
 * <p>Whatever neither recognises stays uncategorized for the user to set by hand, which is what
 * teaches memory -- see {@link RuleBasedCategorizer} for why a blank beats a guess.
 */
@Service
public class CategorizationService {

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

    public CategorizeResponse categorizeAll() {
        List<Transaction> uncategorized = transactionRepository.findUncategorized();
        if (uncategorized.isEmpty()) {
            return CategorizeResponse.noneFound();
        }

        Set<String> validCategories = new HashSet<>(categoryRepository.findAllNames());
        Map<String, List<MerchantCategory>> memory = merchantCategoryRepository.loadAll();
        KeywordRuleMatcher keywordRules = new KeywordRuleMatcher(keywordRuleRepository.findAll());

        int fromMemory = 0;
        int fromKeywordRules = 0;
        int fromRules = 0;
        // Keyed by rule id, not merchant: a merchant with several bands should only credit the
        // band that actually answered.
        Map<Long, Integer> memoryHits = new HashMap<>();

        for (Transaction t : uncategorized) {
            String merchantKey = MerchantNormalizer.normalize(t.description());

            // Which memory rule applies depends on the amount, so this is resolved per transaction
            // rather than per merchant.
            MerchantCategory remembered = MerchantCategory
                    .bestMatch(memory.getOrDefault(merchantKey, List.of()), t.amount())
                    .orElse(null);

            // A remembered category is only usable while that category still exists; it can
            // have been deleted or renamed since the entry was written.
            if (remembered != null && validCategories.contains(remembered.category())) {
                transactionRepository.updateCategory(t.id(), remembered.category(), "cache");
                memoryHits.merge(remembered.id(), 1, Integer::sum);
                fromMemory++;
                continue;
            }

            // Labelled 'rule' like a built-in match: both are rules rather than a per-merchant
            // correction, and CategorizeResponse already reports which kind answered.
            Optional<KeywordRule> keyword = keywordRules.match(t.description(), validCategories);
            if (keyword.isPresent()) {
                transactionRepository.updateCategory(t.id(), keyword.get().category(), "rule");
                fromKeywordRules++;
                continue;
            }

            Optional<String> ruled = RuleBasedCategorizer.categorize(t.description(), t.type(), validCategories);
            if (ruled.isPresent()) {
                transactionRepository.updateCategory(t.id(), ruled.get(), "rule");
                fromRules++;
            }
        }
        merchantCategoryRepository.recordHits(memoryHits);

        return CategorizeResponse.of(fromMemory, fromKeywordRules, fromRules, uncategorized.size());
    }
}
