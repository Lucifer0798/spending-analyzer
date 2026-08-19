package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.RecurringSeries;
import com.spendinganalyzer.model.Transaction;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Finds charges that repeat on a regular cadence for a consistent amount — subscriptions,
 * rent, utilities, memberships.
 *
 * <p>Both conditions are required. Cadence alone is not enough: a grocery run happens
 * often but for a different amount each time, and flagging it as a subscription would make
 * the view useless. Requiring stable amounts as well is what separates "Netflix, £15.49
 * every month" from "Whole Foods, roughly fortnightly, anywhere from £78 to £103".
 */
@Service
public class RecurringDetectionService {

    /** Minimum occurrences before a pattern is believable rather than coincidence. */
    private static final int MIN_OCCURRENCES = 3;

    /** Share of intervals that must sit close to the median for the cadence to count as regular. */
    private static final double MIN_REGULAR_INTERVAL_RATIO = 0.6;

    /** How far an individual interval may drift from the median and still count as regular. */
    private static final double INTERVAL_TOLERANCE = 0.25;

    /** Maximum coefficient of variation in amount for the charge to count as consistent. */
    private static final double MAX_AMOUNT_VARIATION = 0.15;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    // Store numbers, terminal ids, and order references differ per visit and would otherwise
    // split one merchant into many singletons.
    private static final Pattern STORE_SUFFIX = Pattern.compile("[*#]\\s*[A-Z0-9-]+\\s*$");
    private static final Pattern TRAILING_DIGITS = Pattern.compile("\\s+\\d{3,}\\s*$");
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\b\\d{4,}\\b");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");

    /**
     * Reduces a raw statement description to a stable merchant label.
     * Package-private so it can be unit tested directly.
     */
    static String normalizeMerchant(String description) {
        String s = description.toUpperCase(Locale.ROOT).trim();
        s = STORE_SUFFIX.matcher(s).replaceAll("");
        s = LONG_DIGIT_RUN.matcher(s).replaceAll("");
        s = TRAILING_DIGITS.matcher(s).replaceAll("");
        s = s.replaceAll("[*#]+\\s*$", "");
        s = MULTI_SPACE.matcher(s).replaceAll(" ").trim();
        return s.isEmpty() ? description.trim().toUpperCase(Locale.ROOT) : s;
    }

    public List<RecurringSeries> detect(List<Transaction> transactions) {
        Map<String, List<Transaction>> byMerchant = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            byMerchant.computeIfAbsent(normalizeMerchant(t.description()), k -> new ArrayList<>()).add(t);
        }

        List<RecurringSeries> results = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> entry : byMerchant.entrySet()) {
            analyseGroup(entry.getKey(), entry.getValue()).ifPresent(results::add);
        }

        results.sort((a, b) -> Double.compare(b.annualizedCost(), a.annualizedCost()));
        return results;
    }

    /** One billing event: a date, and everything charged by that merchant on it. */
    private record Occurrence(String date, double amount) {}

    private Optional<RecurringSeries> analyseGroup(String merchant, List<Transaction> group) {
        List<Transaction> sorted = new ArrayList<>(group);
        sorted.sort(Comparator.comparing(Transaction::date));

        // Collapse charges that share a date into a single occurrence, summing the amounts.
        // Two cards billed by the same merchant on the same day is one billing event costing
        // both charges; left uncollapsed it would produce a zero-day interval and drag the
        // median to zero, silently hiding the subscription altogether.
        LinkedHashMap<String, Double> byDate = new LinkedHashMap<>();
        for (Transaction t : sorted) {
            byDate.merge(t.date(), t.amount(), Double::sum);
        }
        List<Occurrence> occurrences = byDate.entrySet().stream()
                .map(e -> new Occurrence(e.getKey(), e.getValue()))
                .toList();

        if (occurrences.size() < MIN_OCCURRENCES) return Optional.empty();

        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < occurrences.size(); i++) {
            LocalDate previous = LocalDate.parse(occurrences.get(i - 1).date(), ISO);
            LocalDate current = LocalDate.parse(occurrences.get(i).date(), ISO);
            intervals.add(ChronoUnit.DAYS.between(previous, current));
        }
        if (intervals.isEmpty()) return Optional.empty();

        double medianInterval = median(intervals);
        if (medianInterval <= 0) return Optional.empty();

        long regular = intervals.stream()
                .filter(gap -> Math.abs(gap - medianInterval) <= medianInterval * INTERVAL_TOLERANCE)
                .count();
        if ((double) regular / intervals.size() < MIN_REGULAR_INTERVAL_RATIO) return Optional.empty();

        String cadence = classifyCadence(medianInterval);
        if (cadence == null) return Optional.empty();

        List<Double> amounts = occurrences.stream().map(Occurrence::amount).toList();
        double mean = amounts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (mean <= 0) return Optional.empty();

        double variance = amounts.stream().mapToDouble(a -> Math.pow(a - mean, 2)).average().orElse(0);
        double coefficientOfVariation = Math.sqrt(variance) / mean;
        if (coefficientOfVariation > MAX_AMOUNT_VARIATION) return Optional.empty();

        Occurrence last = occurrences.get(occurrences.size() - 1);
        String category = sorted.get(sorted.size() - 1).category();
        LocalDate nextExpected = LocalDate.parse(last.date(), ISO).plusDays(Math.round(medianInterval));

        return Optional.of(new RecurringSeries(
                merchant,
                category,
                cadence,
                round2(mean),
                round2(last.amount()),
                last.date(),
                nextExpected.format(ISO),
                occurrences.size(),
                (int) Math.round(medianInterval),
                round2(mean * (365.0 / medianInterval)),
                confidenceOf(occurrences.size(), coefficientOfVariation)
        ));
    }

    /** Null means the spacing does not match any cadence worth calling recurring. */
    private static String classifyCadence(double days) {
        if (days >= 6 && days <= 8) return "weekly";
        if (days >= 12 && days <= 16) return "biweekly";
        if (days >= 26 && days <= 35) return "monthly";
        if (days >= 84 && days <= 96) return "quarterly";
        if (days >= 350 && days <= 380) return "yearly";
        return null;
    }

    private static String confidenceOf(int occurrences, double amountVariation) {
        if (occurrences >= 6 && amountVariation <= 0.02) return "high";
        if (occurrences >= 4 || amountVariation <= 0.05) return "medium";
        return "low";
    }

    private static double median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int size = sorted.size();
        if (size % 2 == 1) return sorted.get(size / 2);
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
