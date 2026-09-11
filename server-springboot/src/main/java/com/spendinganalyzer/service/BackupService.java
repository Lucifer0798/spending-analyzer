package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.BackupData;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.BudgetRepository;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.GoalContributionRepository;
import com.spendinganalyzer.repository.GoalRepository;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.repository.PredictionsCacheRepository;
import com.spendinganalyzer.repository.RecurringOverrideRepository;
import com.spendinganalyzer.repository.TagRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Everything worth backing up, as one JSON file, and the other direction: replacing every table
 * it covers with exactly what a file holds. A restore, not a merge — see {@link
 * com.spendinganalyzer.controller.BackupController} for why that's the deliberate choice.
 */
@Service
public class BackupService {

    /** Exports are unpaged for the same reason the CSV exports are: a partial backup is worse than none. */
    private static final int NO_LIMIT = Integer.MAX_VALUE;

    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final TransactionRepository transactions;
    private final MerchantCategoryRepository merchants;
    private final BudgetRepository budgets;
    private final RecurringOverrideRepository recurringOverrides;
    private final GoalRepository goals;
    private final GoalContributionRepository goalContributions;
    private final TagRepository tags;
    private final PredictionsCacheRepository predictionsCache;

    public BackupService(
            AccountRepository accounts,
            CategoryRepository categories,
            TransactionRepository transactions,
            MerchantCategoryRepository merchants,
            BudgetRepository budgets,
            RecurringOverrideRepository recurringOverrides,
            GoalRepository goals,
            GoalContributionRepository goalContributions,
            TagRepository tags,
            PredictionsCacheRepository predictionsCache
    ) {
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
        this.merchants = merchants;
        this.budgets = budgets;
        this.recurringOverrides = recurringOverrides;
        this.goals = goals;
        this.goalContributions = goalContributions;
        this.tags = tags;
        this.predictionsCache = predictionsCache;
    }

    public record BackupSummary(
            int accounts,
            int categories,
            int transactions,
            int merchantCategories,
            int budgets,
            int recurringOverrides,
            int goals,
            int goalContributions,
            int tags
    ) {}

    public BackupData export() {
        return new BackupData(
                BackupData.CURRENT_VERSION,
                Instant.now().toString(),
                accounts.findAll(true),
                categories.findAll(),
                transactions.find(null, null, null, DateRange.ALL, NO_LIMIT, 0),
                merchants.findAll(),
                budgets.findAll(),
                recurringOverrides.findAll(),
                goals.findAll(),
                goalContributions.findAll(),
                tags.findAll(),
                tags.findAllAssociations()
        );
    }

    /**
     * Wipes every table a backup covers and reloads it exactly as exported, ids included.
     * {@code predictions_cache} is cleared too even though it isn't part of the backup itself:
     * whatever forecast it held describes a dataset that, after this, may no longer exist.
     */
    @Transactional
    public BackupSummary restore(BackupData data) {
        categories.restoreAll(data.categories());
        accounts.restoreAll(data.accounts());
        transactions.restoreAll(data.transactions());
        merchants.restoreAll(data.merchantCategories());
        budgets.restoreAll(data.budgets());
        recurringOverrides.restoreAll(data.recurringOverrides());
        goals.restoreAll(data.goals());
        goalContributions.restoreAll(data.goalContributions());
        tags.restoreAll(data.tags(), data.transactionTags());
        predictionsCache.deleteAll();

        return new BackupSummary(
                data.accounts().size(),
                data.categories().size(),
                data.transactions().size(),
                data.merchantCategories().size(),
                data.budgets().size(),
                data.recurringOverrides().size(),
                data.goals().size(),
                data.goalContributions().size(),
                data.tags().size()
        );
    }
}
