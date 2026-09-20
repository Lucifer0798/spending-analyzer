package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@code computeIncomeTotal} and {@code latestIncomeDate} directly -- the two StatsService
 * methods {@code RecurringIncomeTest} exercises only through the controller. Rolled back after
 * each test so the shared test database is left as it was found.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatsServiceIncomeTest {

    @Autowired
    private StatsService statsService;

    @Autowired
    private TransactionRepository transactions;

    @BeforeEach
    void seed() {
        transactions.insertBatch(List.of(
                new ParsedTransaction("2026-06-01", "PAYROLL", 3000.00, "credit", "Income"),
                new ParsedTransaction("2026-06-15", "GROCERY STORE", 80.00, "debit", "Groceries")
        ), "income-stats-batch", 1L);
    }

    @Test
    @DisplayName("income total counts only credits in income categories")
    void incomeTotalCountsOnlyIncomeCredits() {
        assertThat(statsService.computeIncomeTotal(1L, DateRange.ALL)).isEqualTo(3000.00);
    }

    @Test
    @DisplayName("a split income deposit counts only your share")
    void splitIncomeCountsOnlyYourShare() {
        Transaction payroll = transactions.find("Income", null, 1L, DateRange.ALL, 200, 0).stream()
                .filter(t -> t.description().equals("PAYROLL")).findFirst().orElseThrow();
        transactions.updateSplit(payroll.id(), 1000.00, "Shared payroll account, 2/3 goes to my partner");

        assertThat(statsService.computeIncomeTotal(1L, DateRange.ALL)).isEqualTo(1000.00);
    }

    @Test
    @DisplayName("latestIncomeDate is the newest income date, not the newest transaction date overall")
    void latestIncomeDateIgnoresSpendTransactions() {
        // The grocery debit (2026-06-15) is newer than the payroll credit (2026-06-01) -- if this
        // picked up the wrong column, it would return the debit's date instead.
        assertThat(statsService.latestIncomeDate(1L)).isEqualTo("2026-06-01");
    }

    @Test
    @DisplayName("no income at all reports no latest date")
    void noIncomeReportsNoLatestDate() {
        assertThat(statsService.latestIncomeDate(999L)).isNull();
    }
}
