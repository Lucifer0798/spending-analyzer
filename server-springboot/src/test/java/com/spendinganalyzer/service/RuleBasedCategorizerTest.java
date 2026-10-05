package com.spendinganalyzer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedCategorizerTest {

    private static final Set<String> BUILT_INS = Set.of(
            "Groceries", "Dining & Coffee", "Transportation", "Shopping", "Entertainment", "Utilities",
            "Rent/Mortgage", "Healthcare", "Subscriptions", "Travel", "Fees & Charges", "Personal Care",
            "Education", "Income", "Transfer", "Other");

    private static Optional<String> debit(String description) {
        return RuleBasedCategorizer.categorize(description, "debit", BUILT_INS);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "WHOLE FOODS MARKET #10234       | Groceries",
            "TRADER JOE'S #552 QPS           | Groceries",
            "STARBUCKS STORE 4521            | Dining & Coffee",
            "UBER EATS PENDING               | Dining & Coffee",
            "UBER *TRIP HELP.UBER.COM        | Transportation",
            "SHELL OIL 57444                 | Transportation",
            "NETFLIX.COM                     | Subscriptions",
            "AMAZON PRIME*2K4L               | Subscriptions",
            "AMAZON.COM*MK1AB2               | Shopping",
            "AMZN MKTP US                    | Shopping",
            "DELTA AIR LINES 006             | Travel",
            "AIRBNB * HMQ2                   | Travel",
            "PG&E WEB ONLINE                 | Utilities",
            "VERIZON WIRELESS                | Utilities",
            "MONTHLY RENT - OAK APARTMENTS   | Rent/Mortgage",
            "CVS/PHARMACY #0912              | Healthcare",
            "AMC THEATRES 4432               | Entertainment",
            "PLANET FITNESS                  | Personal Care",
            "COURSERA.ORG                    | Education",
            "OVERDRAFT FEE                   | Fees & Charges",
            "ZELLE TO JOHN SMITH             | Transfer",
            "CHASE CREDIT CARD PAYMENT       | Transfer",
    })
    @DisplayName("recognises common merchants by keyword")
    void recognisesCommonMerchants(String description, String expected) {
        assertThat(debit(description.trim())).contains(expected);
    }

    @Test
    @DisplayName("keywords match whole words only")
    void wholeWordsOnly() {
        // "BP" is a fuel brand; it must not fire inside another word.
        assertThat(debit("BP 8834 STATION")).contains("Transportation");
        assertThat(debit("BPAY BILLER 1234")).isEmpty();
        // "FEE" inside COFFEE isn't a fee.
        assertThat(debit("BLUE BOTTLE COFFEE")).contains("Dining & Coffee");
    }

    @Test
    @DisplayName("a fee on a transfer is still money spent")
    void feeBeatsTransferOnDebit() {
        assertThat(debit("WIRE TRANSFER FEE")).contains("Fees & Charges");
    }

    @Test
    @DisplayName("money coming in is income unless it's a transfer")
    void creditsAreIncomeOrTransfer() {
        assertThat(RuleBasedCategorizer.categorize("ACME CORP PAYROLL", "credit", BUILT_INS)).contains("Income");
        assertThat(RuleBasedCategorizer.categorize("REFUND AMAZON.COM", "credit", BUILT_INS)).contains("Income");
        assertThat(RuleBasedCategorizer.categorize("TRANSFER FROM SAVINGS", "credit", BUILT_INS)).contains("Transfer");
    }

    @Test
    @DisplayName("an unrecognised debit gets no category rather than a guess")
    void unknownDebitIsEmpty() {
        assertThat(debit("ZQX HOLDINGS 4471")).isEmpty();
    }

    @Test
    @DisplayName("a rule is skipped when its category no longer exists")
    void skipsMissingCategories() {
        assertThat(RuleBasedCategorizer.categorize("STARBUCKS", "debit", Set.of("Groceries"))).isEmpty();
        assertThat(RuleBasedCategorizer.categorize("PAYROLL", "credit", Set.of("Groceries"))).isEmpty();
    }
}
