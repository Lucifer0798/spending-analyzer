package com.spendinganalyzer;

import com.spendinganalyzer.controller.TransactionController;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.StatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: boots the full application context with no ANTHROPIC_API_KEY present,
 * which is how CI runs. Catches broken bean wiring, failed migrations, and datasource
 * misconfiguration — none of which a compile-only build would surface.
 */
@SpringBootTest
@ActiveProfiles("test")
class SpendingAnalyzerApplicationTests {

    @Autowired
    private TransactionController transactionController;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private StatsService statsService;

    @Test
    void contextLoads() {
        assertThat(transactionController).isNotNull();
    }

    @Test
    void migrationsApplyAndSchemaIsQueryable() {
        assertThat(transactionRepository.count(null, null, null, DateRange.ALL)).isNotNegative();
        assertThat(statsService.computeMonthlyTotals(null, DateRange.ALL)).isNotNull();
        assertThat(statsService.computeMonthlyCategorySeries(null, DateRange.ALL)).isNotNull();
        assertThat(statsService.computeCategoryTotals(null, DateRange.ALL)).isNotNull();
    }

    @Test
    void migrationSeedsDefaultAccount() {
        assertThat(accountRepository.findById(1L)).isPresent();
        assertThat(accountRepository.findAll(false)).isNotEmpty();
    }

    @Test
    void migrationSeedsBuiltinCategoriesWithFlags() {
        var categories = categoryRepository.findAll();
        assertThat(categories).hasSize(16);
        assertThat(categories).allMatch(c -> c.isBuiltin());

        // The stats queries rely on these flags rather than hardcoded names.
        assertThat(categories).filteredOn(c -> c.name().equals("Income")).singleElement()
                .matches(c -> c.isIncome() && !c.isTransfer());
        assertThat(categories).filteredOn(c -> c.name().equals("Transfer")).singleElement()
                .matches(c -> c.isTransfer() && !c.isIncome());
    }
}
