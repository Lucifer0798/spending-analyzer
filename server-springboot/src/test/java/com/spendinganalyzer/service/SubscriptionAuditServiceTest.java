package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.RecurringSeries;
import com.spendinganalyzer.model.RecurringOverride;
import com.spendinganalyzer.service.SubscriptionAuditService.Finding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class SubscriptionAuditServiceTest {

    private final SubscriptionAuditService audit = new SubscriptionAuditService();

    private static RecurringSeries series(String merchant, String category, String cadence, double last, String lastDate,
                                          Double previous) {
        int interval = switch (cadence) {
            case "weekly" -> 7;
            case "yearly" -> 365;
            default -> 30;
        };
        return new RecurringSeries(merchant, category, cadence, last, last, lastDate, "2026-11-01", 6, interval,
                last * 12, "high", false, null, 7, previous != null, previous, merchant);
    }

    private static RecurringOverride cancelFlag(String merchant, String createdAt) {
        return new RecurringOverride(1, merchant, RecurringOverride.ACTION_CANCEL, createdAt);
    }

    @Test
    @DisplayName("a price rise is costed as the difference over a year at its own cadence; a drop isn't a finding")
    void priceIncrease() {
        List<Finding> findings = audit.audit(List.of(
                series("NETFLIX", "Subscriptions", "monthly", 17.99, "2026-10-12", 15.49),
                series("GYM", "Healthcare", "monthly", 55.00, "2026-10-01", 60.00),
                series("NEWSPAPER", "Subscriptions", "weekly", 6.00, "2026-10-20", 5.00)), Map.of());

        assertThat(findings).filteredOn(f -> f.kind().equals(Finding.PRICE_INCREASE))
                .extracting(Finding::merchants, Finding::fromAmount, Finding::toAmount, Finding::yearlyAmount)
                .containsExactly(
                        tuple(List.of("NEWSPAPER"), 5.00, 6.00, 52.0),   // $1 more, 52 times a year
                        tuple(List.of("NETFLIX"), 15.49, 17.99, 30.0));  // $2.50 more, 12 times
    }

    @Test
    @DisplayName("a rise on something already flagged to cancel isn't flagged again")
    void noPriceIncreaseOnceCancelling() {
        List<Finding> findings = audit.audit(List.of(
                series("NETFLIX", "Subscriptions", "monthly", 17.99, "2026-10-12", 15.49)),
                Map.of("NETFLIX", cancelFlag("NETFLIX", "2026-10-20 08:00:00")));

        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("a charge after the cancel flag's day is 'still charging'; one on or before it isn't")
    void stillCharging() {
        List<Finding> findings = audit.audit(List.of(
                series("HULU", "Subscriptions", "monthly", 7.99, "2026-10-15", null),
                series("SPOTIFY", "Subscriptions", "monthly", 11.99, "2026-10-01", null)),
                Map.of("HULU", cancelFlag("HULU", "2026-09-20 18:02:11"),
                        "SPOTIFY", cancelFlag("SPOTIFY", "2026-10-01 09:00:00")));

        assertThat(findings).filteredOn(f -> f.kind().equals(Finding.STILL_CHARGING)).singleElement().satisfies(f -> {
            assertThat(f.merchants()).containsExactly("HULU");
            assertThat(f.chargedOn()).isEqualTo("2026-10-15");
            assertThat(f.flaggedOn()).isEqualTo("2026-09-20");
            assertThat(f.yearlyAmount()).isEqualTo(95.88);
        });
    }

    @Test
    @DisplayName("two or more small same-category, same-cadence charges are an overlap; big bills and flagged ones are left out")
    void overlap() {
        List<Finding> findings = audit.audit(List.of(
                series("NETFLIX", "Subscriptions", "monthly", 15.49, "2026-10-12", null),
                series("DISNEY PLUS", "Subscriptions", "monthly", 10.99, "2026-10-08", null),
                series("HULU", "Subscriptions", "monthly", 7.99, "2026-09-15", null),          // flagged below
                series("ADOBE", "Subscriptions", "yearly", 19.99, "2026-03-01", null),         // other cadence
                series("CITY RENT", "Rent/Mortgage", "monthly", 1500, "2026-10-01", null),
                series("POWER CO", "Utilities", "monthly", 120, "2026-10-05", null),
                series("WATER", "Utilities", "monthly", 30, "2026-10-06", null)),            // alone once Power is out
                Map.of("HULU", cancelFlag("HULU", "2026-10-01 10:00:00")));

        assertThat(findings).filteredOn(f -> f.kind().equals(Finding.OVERLAP)).singleElement().satisfies(f -> {
            assertThat(f.merchants()).containsExactly("NETFLIX", "DISNEY PLUS");
            assertThat(f.category()).isEqualTo("Subscriptions");
            assertThat(f.cadence()).isEqualTo("monthly");
            assertThat(f.yearlyAmount()).isEqualTo(317.76);   // (15.49 + 10.99) * 12
        });
    }

    @Test
    @DisplayName("still-charging comes first, then the rest by what's at stake a year; nothing to say gives nothing")
    void ordering() {
        List<Finding> findings = audit.audit(List.of(
                series("NETFLIX", "Subscriptions", "monthly", 17.99, "2026-10-12", 15.49),
                series("DISNEY PLUS", "Subscriptions", "monthly", 10.99, "2026-10-08", null),
                series("HULU", "Entertainment", "monthly", 7.99, "2026-10-15", null)),
                Map.of("HULU", cancelFlag("HULU", "2026-09-20 18:02:11")));

        assertThat(findings).extracting(Finding::kind)
                .containsExactly(Finding.STILL_CHARGING, Finding.OVERLAP, Finding.PRICE_INCREASE);
        assertThat(audit.audit(List.of(series("RENT", "Rent/Mortgage", "monthly", 1500, "2026-10-01", null)), Map.of()))
                .isEmpty();
    }
}
