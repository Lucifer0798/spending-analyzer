package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategorizeResponse;
import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assigns categories to uncategorized transactions, consulting merchant memory before
 * the model.
 *
 * <p>Two things keep the model's workload down. Merchants already seen are answered from
 * memory without a request at all. Of what remains, the model is asked once per distinct
 * merchant rather than once per transaction — fifty coffees at the same shop are one
 * question, not fifty. Both rest on the same assumption the cache itself makes: a
 * merchant maps to a category.
 */
@Service
public class CategorizationService {

    /** Distinct merchants per request to the model. */
    private static final int BATCH_SIZE = 60;

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantCategoryRepository merchantCategoryRepository;
    private final AnthropicService anthropicService;

    public CategorizationService(
            TransactionRepository transactionRepository,
            CategoryRepository categoryRepository,
            MerchantCategoryRepository merchantCategoryRepository,
            AnthropicService anthropicService
    ) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.merchantCategoryRepository = merchantCategoryRepository;
        this.anthropicService = anthropicService;
    }

    public CategorizeResponse categorizeAll() {
        List<Transaction> uncategorized = transactionRepository.findUncategorized();
        if (uncategorized.isEmpty()) {
            return CategorizeResponse.noneFound();
        }

        Set<String> validCategories = new HashSet<>(categoryRepository.findAllNames());
        Map<String, MerchantCategory> memory = merchantCategoryRepository.loadAll();

        int fromMemory = 0;
        Map<String, Integer> memoryHits = new HashMap<>();
        // Preserves encounter order so the first transaction of each merchant represents it.
        Map<String, List<Transaction>> unknownByMerchant = new LinkedHashMap<>();

        for (Transaction t : uncategorized) {
            String merchantKey = MerchantNormalizer.normalize(t.description());
            MerchantCategory remembered = memory.get(merchantKey);

            // A remembered category is only usable while that category still exists; it can
            // have been deleted or renamed since the entry was written.
            if (remembered != null && validCategories.contains(remembered.category())) {
                transactionRepository.updateCategory(t.id(), remembered.category(), "cache");
                memoryHits.merge(merchantKey, 1, Integer::sum);
                fromMemory++;
            } else {
                unknownByMerchant.computeIfAbsent(merchantKey, k -> new ArrayList<>()).add(t);
            }
        }
        merchantCategoryRepository.recordHits(memoryHits);

        int fromModel = 0;
        List<String> merchantKeys = new ArrayList<>(unknownByMerchant.keySet());

        for (int i = 0; i < merchantKeys.size(); i += BATCH_SIZE) {
            List<String> keyBatch = merchantKeys.subList(i, Math.min(i + BATCH_SIZE, merchantKeys.size()));

            // One representative transaction per merchant carries the description the model
            // categorises on; the answer is then applied to every transaction sharing it.
            List<Transaction> representatives = keyBatch.stream()
                    .map(key -> unknownByMerchant.get(key).get(0))
                    .toList();

            AnthropicService.CategorizationResult result = anthropicService.categorizeBatch(representatives);

            Map<Long, String> answerByTransactionId = new HashMap<>();
            for (AnthropicService.CategorizationEntry entry : result.categorizations()) {
                if (validCategories.contains(entry.category())) {
                    answerByTransactionId.put(entry.id(), entry.category());
                }
            }

            for (String merchantKey : keyBatch) {
                List<Transaction> group = unknownByMerchant.get(merchantKey);
                String category = answerByTransactionId.get(group.get(0).id());
                if (category == null) continue;

                for (Transaction t : group) {
                    transactionRepository.updateCategory(t.id(), category, "ai");
                    fromModel++;
                }
                merchantCategoryRepository.remember(merchantKey, category, MerchantCategory.SOURCE_AI);
            }
        }

        return CategorizeResponse.of(fromMemory, fromModel, merchantKeys.size(), uncategorized.size());
    }
}
