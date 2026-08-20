package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategorizeResponse;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class CategorizationService {

    private static final int BATCH_SIZE = 60;

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AnthropicService anthropicService;

    public CategorizationService(
            TransactionRepository transactionRepository,
            CategoryRepository categoryRepository,
            AnthropicService anthropicService
    ) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.anthropicService = anthropicService;
    }

    public CategorizeResponse categorizeAll() {
        List<Transaction> uncategorized = transactionRepository.findUncategorized();
        if (uncategorized.isEmpty()) {
            return CategorizeResponse.noneFound();
        }

        // Snapshot the valid names once; the model is constrained to this set by the
        // response schema, but a mismatch would otherwise write an unknown category.
        Set<String> validCategories = new HashSet<>(categoryRepository.findAllNames());
        int categorized = 0;

        for (int i = 0; i < uncategorized.size(); i += BATCH_SIZE) {
            List<Transaction> batch = uncategorized.subList(i, Math.min(i + BATCH_SIZE, uncategorized.size()));
            AnthropicService.CategorizationResult result = anthropicService.categorizeBatch(batch);

            for (AnthropicService.CategorizationEntry entry : result.categorizations()) {
                if (validCategories.contains(entry.category())) {
                    transactionRepository.updateCategory(entry.id(), entry.category(), "ai");
                    categorized++;
                }
            }
        }

        return CategorizeResponse.of(categorized, uncategorized.size());
    }
}
