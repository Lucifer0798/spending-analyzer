package com.spendinganalyzer.dto;

import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.AccountBalance;
import com.spendinganalyzer.model.Budget;
import com.spendinganalyzer.model.Category;
import com.spendinganalyzer.model.FilterPreset;
import com.spendinganalyzer.model.Goal;
import com.spendinganalyzer.model.GoalContribution;
import com.spendinganalyzer.model.KeywordRule;
import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.model.NetWorthTarget;
import com.spendinganalyzer.model.RecurringOverride;
import com.spendinganalyzer.model.Tag;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.model.TransactionReceipt;
import com.spendinganalyzer.model.TransactionTag;

import java.util.List;

/**
 * Everything worth backing up or moving to a new instance. {@code predictions_cache} is
 * deliberately left out — it's a regenerable cache, not data, the same reasoning
 * {@code V8__predictions_cache_per_account.sql} used to justify dropping the old row rather than
 * migrating it. Receipts are the opposite of a cache — an attached photo can't be regenerated —
 * so unlike predictions they round-trip here; {@code TransactionReceipt.data} is a {@code byte[]},
 * which Jackson already serializes as a base64 string with no extra code needed on either side.
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
        List<GoalContribution> goalContributions,
        List<Tag> tags,
        List<TransactionTag> transactionTags,
        List<AccountBalance> accountBalances,
        List<FilterPreset> filterPresets,
        List<TransactionReceipt> transactionReceipts,
        /** Null when no target is set -- a singleton, not a list, since there is only ever one. */
        NetWorthTarget netWorthTarget,
        List<KeywordRule> keywordRules
) {
    // Bumped from 7: keywordRules is a new field a version-7 file has no value for, and there
    // is no migration path between backup versions -- see BackupController.
    public static final int CURRENT_VERSION = 8;
}
