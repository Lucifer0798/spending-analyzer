package com.spendinganalyzer;

import com.spendinganalyzer.controller.TransactionController;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.StatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: boots the full application context with no ANTHROPIC_API_KEY present,
 * which is how CI runs. Catches broken bean wiring, a bad schema.sql, and datasource
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
    private StatsService statsService;

    @Test
    void contextLoads() {
        assertThat(transactionController).isNotNull();
    }

    @Test
    void schemaIsAppliedAndQueryable() {
        // Exercises schema.sql having run against the datasource.
        assertThat(transactionRepository.count(null, null)).isNotNegative();
        assertThat(statsService.computeMonthlyTotals()).isNotNull();
        assertThat(statsService.computeMonthlyCategorySeries()).isNotNull();
    }
}
