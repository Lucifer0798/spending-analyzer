package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.RecurringSeries;
import com.spendinganalyzer.model.RecurringOverride;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Looks over the detected recurring charges for things worth acting on. Built only from what
 * detection already knows -- the same series the Recurring page lists -- so the audit can't
 * disagree with the table under it.
 *
 * <ul>
 *   <li><b>Price went up</b> ({@code price_increase}): the latest charge is above the average before
 *       it, and it isn't already flagged to cancel. Costed as the difference over a year at the
 *       series' own cadence.</li>
 *   <li><b>Still charging</b> ({@code still_charging}): flagged to cancel, yet charged again after
 *       the flag was set. Costed as a year more of it at the latest price.</li>
 *   <li><b>Several services in one category</b> ({@code overlap}): two or more small charges (each
 *       under {@link #SMALL_CHARGE}) in one category on the same cadence -- the streaming-plans case.
 *       A prompt to check, not a claim they're duplicates; costed as what they add up to.</li>
 * </ul>
 *
 * <p>Findings carry numbers, not sentences: the client words and formats them in the account's
 * currency, as it does everything else.
 */
@Service
public class SubscriptionAuditService {

    /** At or above this per charge it's rent, insurance or a utility bill, not a plan you might forget about. */
    static final double SMALL_CHARGE = 50.0;

    /**
     * @param merchants    one for price_increase and still_charging; the group for overlap
     * @param yearlyAmount what's at stake over a year -- see the class comment for each kind
     * @param fromAmount   price_increase only: the average before the change
     * @param toAmount     price_increase and still_charging: the latest charge
     * @param chargedOn    still_charging only: the latest charge's date
     * @param flaggedOn    still_charging only: when it was flagged to cancel
     */
    public record Finding(
            String kind,
            List<String> merchants,
            String category,
            String cadence,
            double yearlyAmount,
            Double fromAmount,
            Double toAmount,
            String chargedOn,
            String flaggedOn
    ) {
        public static final String PRICE_INCREASE = "price_increase";
        public static final String STILL_CHARGING = "still_charging";
        public static final String OVERLAP = "overlap";
    }

    public List<Finding> audit(List<RecurringSeries> series, Map<String, RecurringOverride> overrides) {
        List<Finding> findings = new ArrayList<>();
        Map<String, List<RecurringSeries>> groups = new LinkedHashMap<>();

        for (RecurringSeries s : series) {
            String flaggedOn = cancelFlagDate(overrides.get(s.merchant()));

            // Overrides store a timestamp; the charge only a date. Charged on a later day means the
            // flag didn't stop it -- the same day is ambiguous, so it isn't counted.
            if (flaggedOn != null && s.lastDate().compareTo(flaggedOn) > 0) {
                findings.add(new Finding(Finding.STILL_CHARGING, List.of(s.merchant()), s.category(), s.cadence(),
                        round(s.lastAmount() * perYear(s)), null, s.lastAmount(), s.lastDate(), flaggedOn));
            }

            // Already being cancelled, a rise is moot -- and if it keeps charging, that's the finding above.
            if (flaggedOn == null && s.priceChanged() && s.previousAmount() != null && s.lastAmount() > s.previousAmount()) {
                findings.add(new Finding(Finding.PRICE_INCREASE, List.of(s.merchant()), s.category(), s.cadence(),
                        round((s.lastAmount() - s.previousAmount()) * perYear(s)),
                        s.previousAmount(), s.lastAmount(), null, null));
            }

            // Something already on its way out isn't part of a pile to trim.
            if (s.category() != null && flaggedOn == null && s.lastAmount() < SMALL_CHARGE) {
                groups.computeIfAbsent(s.category() + "|" + s.cadence(), k -> new ArrayList<>()).add(s);
            }
        }

        for (List<RecurringSeries> group : groups.values()) {
            if (group.size() < 2) continue;
            RecurringSeries first = group.get(0);
            findings.add(new Finding(Finding.OVERLAP,
                    group.stream().map(RecurringSeries::merchant).toList(), first.category(), first.cadence(),
                    round(group.stream().mapToDouble(s -> s.lastAmount() * perYear(s)).sum()),
                    null, null, null, null));
        }

        // Money already going where it shouldn't first, then by how much a year is at stake.
        findings.sort(Comparator.comparing((Finding f) -> !f.kind().equals(Finding.STILL_CHARGING))
                .thenComparing(Finding::yearlyAmount, Comparator.reverseOrder()));
        return findings;
    }

    /** The date part of a "cancel" override's timestamp, or null for no override or an "exclude" one. */
    private static String cancelFlagDate(RecurringOverride flag) {
        if (flag == null || !RecurringOverride.ACTION_CANCEL.equals(flag.action()) || flag.createdAt() == null) return null;
        return flag.createdAt().length() >= 10 ? flag.createdAt().substring(0, 10) : flag.createdAt();
    }

    /** How many charges a year the series' cadence makes. */
    static double perYear(RecurringSeries s) {
        return switch (s.cadence()) {
            case "weekly" -> 52;
            case "biweekly" -> 26;
            case "monthly" -> 12;
            case "quarterly" -> 4;
            case "yearly" -> 1;
            default -> 365.0 / Math.max(1, s.medianIntervalDays());
        };
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
