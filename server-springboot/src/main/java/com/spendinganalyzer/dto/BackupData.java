package com.spendinganalyzer.dto;

import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.Budget;
import com.spendinganalyzer.model.Category;
import com.spendinganalyzer.model.Goal;
import com.spendinganalyzer.model.GoalContribution;
import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.model.RecurringOverride;
import com.spendinganalyzer.model.Transaction;

import java.util.List;

/**
 * Everything worth backing up or moving to a new instance. {@code predictions_cache} is
 * deliberately left out — it's a regenerable cache, not data, the same reasoning
 * {@code V8__predictions_cache_per_account.sql} used to justify dropping the old row rather than
 * migrating it.
 */
public record BackupData(
        int version,
        String exportedAt,
        List<Account> accounts,
        List<Category> categories,
        List<Transaction> transactions,
        List<MerchantCategory> merchantCategories,
        List<Budget> budgets,
        List<RecurringOverride> recurringOverrides,
        List<Goal> goals,
        List<GoalContribution> goalContributions
) {
    // Bumped from 1: goals and goalContributions are new fields a version-1 file has no values
    // for, and there is no migration path between backup versions -- see BackupController.
    public static final int CURRENT_VERSION = 2;
}
