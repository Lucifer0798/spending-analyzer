package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.SpendingAnomaly;
import com.spendinganalyzer.model.Transaction;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flags a transaction that's unusually large for its own category — a $400 grocery run when the
 * typical one is $80, not just "the biggest grocery purchase this month."
 */
@Service
public class AnomalyDetectionService {

    /** Fewer than this many transactions in a category and there's no reliable "typical" to compare against. */
    private static final int MIN_SAMPLES = 5;

    /** How far above the category's median counts as unusual rather than just a bigger-than-normal trip. */
    private static final double THRESHOLD_MULTIPLIER = 3.0;

    public List<SpendingAnomaly> detect(List<Transaction> transactions) {
        Map<String, List<Transaction>> byCategory = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            if (t.category() == null) continue;
            byCategory.computeIfAbsent(t.category(), k -> new ArrayList<>()).add(t);
        }

        List<SpendingAnomaly> anomalies = new ArrayList<>();
        for (List<Transaction> group : byCategory.values()) {
            if (group.size() < MIN_SAMPLES) continue;

            // The median, not the mean: an outlier this large would drag a mean up toward
            // itself, making the very transaction being judged raise its own bar. The median
            // barely moves for one extreme value in an otherwise-typical group.
            double typical = median(group.stream().map(Transaction::amount).toList());
            if (typical <= 0) continue;

            for (Transaction t : group) {
                if (t.amount() > typical * THRESHOLD_MULTIPLIER) {
                    anomalies.add(new SpendingAnomaly(
                            t.id(),
                            t.date(),
                            t.description(),
                            t.category(),
                            round2(t.amount()),
                            round2(typical),
                            round2(t.amount() / typical)
                    ));
                }
            }
        }

        anomalies.sort((a, b) -> Double.compare(b.multiplier(), a.multiplier()));
        return anomalies;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int size = sorted.size();
        if (size % 2 == 1) return sorted.get(size / 2);
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
