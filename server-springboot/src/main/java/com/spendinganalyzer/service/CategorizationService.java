package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategorizeResponse;
import com.spendinganalyzer.model.Categories;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategorizationService {

    private static final int BATCH_SIZE = 60;

    private final TransactionRepository transactionRepository;
    private final AnthropicService anthropicService;

    public CategorizationService(TransactionRepository transactionRepository, AnthropicService anthropicService) {
        this.transactionRepository = transactionRepository;
        this.anthropicService = anthropicService;
    }

    public CategorizeResponse categorizeAll() {
        List<Transaction> uncategorized = transactionRepository.findUncategorized();
        if (uncategorized.isEmpty()) {
            return CategorizeResponse.noneFound();
        }

        int categorized = 0;
        for (int i = 0; i < uncategorized.size(); i += BATCH_SIZE) {
            List<Transaction> batch = uncategorized.subList(i, Math.min(i + BATCH_SIZE, uncategorized.size()));
            AnthropicService.CategorizationResult result = anthropicService.categorizeBatch(batch);

            for (AnthropicService.CategorizationEntry entry : result.categorizations()) {
                if (Categories.ALL.contains(entry.category())) {
                    transactionRepository.updateCategory(entry.id(), entry.category(), "ai");
                    categorized++;
                }
            }
        }

        return CategorizeResponse.of(categorized, uncategorized.size());
    }
}
