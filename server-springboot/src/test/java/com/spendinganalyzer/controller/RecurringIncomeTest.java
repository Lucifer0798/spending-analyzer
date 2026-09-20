package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.RecurringSeries;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /recurring-income} reuses the exact same {@link com.spendinganalyzer.service.RecurringDetectionService}
 * as {@code /recurring} -- the cadence/consistent-amount math itself is {@code RecurringDetectionServiceTest}'s
 * job. This covers the endpoint's own wiring: which transactions it feeds in, the expected-vs-actual figures,
 * and the mixed-currency/no-data envelopes.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecurringIncomeTest {

    @Autowired
    private InsightsController controller;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    @BeforeEach
    void seed() {
        transactions.insertBatch(List.of(
                new ParsedTransaction("2026-04-01", "ACME CORP PAYROLL", 3000.00, "credit", "Income"),
                new ParsedTransaction("2026-05-01", "ACME CORP PAYROLL", 3000.00, "credit", "Income"),
                new ParsedTransaction("2026-06-01", "ACME CORP PAYROLL", 3000.00, "credit", "Income"),
                // A one-off deposit -- not recurring, and must not leak into the totals either.
                new ParsedTransaction("2026-06-15", "TAX REFUND", 500.00, "credit", "Income"),
                // A debit in an income category (a refund paid back, say) must never count as income.
                new ParsedTransaction("2026-06-20", "PAYROLL CORRECTION", 50.00, "debit", "Income"),
                // Spend in a non-income category must never leak into recurring income either.
                new ParsedTransaction("2026-06-05", "GROCERY STORE", 80.00, "debit", "Groceries")
        ), "income-test-batch", 1L);
    }

    @SuppressWarnings("unchecked")
    private List<RecurringSeries> series(Map<String, Object> response) {
        return (List<RecurringSeries>) (List<?>) response.get("recurring");
    }

    private Map<String, Object> recurringIncome(Long accountId, String month) {
        return controller.recurringIncome(accountId, null, null, month);
    }

    @Test
    @DisplayName("detects a recurring paycheck the same way a recurring charge is detected")
    void detectsRecurringPaycheck() {
        Map<String, Object> response = recurringIncome(null, null);

        assertThat(series(response)).singleElement().satisfies(s -> {
            assertThat(s.merchant()).isEqualTo("ACME CORP PAYROLL");
            assertThat(s.cadence()).isEqualTo("monthly");
            assertThat(s.averageAmount()).isEqualTo(3000.00);
        });
    }

    @Test
    @DisplayName("a one-off deposit isn't flagged as recurring")
    void oneOffDepositIsNotRecurring() {
        Map<String, Object> response = recurringIncome(null, null);

        assertThat(series(response)).extracting(RecurringSeries::merchant).doesNotContain("TAX REFUND");
    }

    @Test
    @DisplayName("a debit in an income category is not treated as income")
    void debitInIncomeCategoryIsExcluded() {
        Map<String, Object> response = recurringIncome(null, "2026-06");

        // 3000 (payroll) + 500 (refund) = 3500 -- the 50 debit correction must not net against it.
        assertThat(response.get("actualThisMonth")).isEqualTo(3500.00);
    }

    @Test
    @DisplayName("spend in a non-income category never counts as income")
    void spendCategoryNeverCountsAsIncome() {
        Map<String, Object> response = recurringIncome(null, "2026-06");

        assertThat(series(response)).extracting(RecurringSeries::merchant).doesNotContain("GROCERY STORE");
    }

    @Test
    @DisplayName("defaults to the newest month with income data, not the current calendar month")
    void defaultsToNewestMonthWithIncomeData() {
        Map<String, Object> response = recurringIncome(null, null);

        assertThat(response.get("month")).isEqualTo("2026-06");
    }

    @Test
    @DisplayName("actualThisMonth counts only the requested month")
    void actualCountsOnlyTheRequestedMonth() {
        assertThat(recurringIncome(null, "2026-04").get("actualThisMonth")).isEqualTo(3000.00);
        assertThat(recurringIncome(null, "2026-06").get("actualThisMonth")).isEqualTo(3500.00);
    }

    @Test
    @DisplayName("totalMonthlyEquivalent is the annualized cost divided by twelve, same as /recurring")
    void monthlyEquivalentIsAnnualizedOverTwelve() {
        Map<String, Object> response = recurringIncome(null, null);

        double annualized = (double) response.get("totalAnnualizedIncome");
        assertThat((double) response.get("totalMonthlyEquivalent"))
                .isEqualTo(Math.round((annualized / 12.0) * 100.0) / 100.0);
    }

    @Test
    @DisplayName("not applicable once \"all accounts\" spans more than one currency")
    void mixedCurrenciesReturnsEmptyEnvelope() {
        accounts.create("Euro account", "checking", "EUR");

        Map<String, Object> response = recurringIncome(null, null);

        assertThat(series(response)).isEmpty();
        assertThat(response.get("mixedCurrencies")).isEqualTo(true);

        // A single account is unaffected -- the mismatch only exists across "all accounts".
        assertThat((Boolean) recurringIncome(1L, null).get("mixedCurrencies")).isFalse();
    }

    @Test
    @DisplayName("rejects a malformed month")
    void rejectsMalformedMonth() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> recurringIncome(null, "not-a-month")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a from/to filter narrows which income transactions feed detection, same as /recurring")
    void dateRangeFiltersDetectionInput() {
        // Only May and June's payrolls fall in range -- two occurrences is below MIN_OCCURRENCES,
        // so the pattern that was detected over full history disappears once narrowed this far.
        Map<String, Object> response = controller.recurringIncome(null, "2026-05-01", "2026-06-30", null);

        assertThat(series(response)).isEmpty();
    }
}
