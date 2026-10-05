package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryMonthlySeries;
import com.spendinganalyzer.dto.MonthlyTotal;
import com.spendinganalyzer.dto.Prediction;
import com.spendinganalyzer.dto.PredictionsPayload;
import com.spendinganalyzer.dto.Recommendation;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Next month's spend per category, savings suggestions, and a plain-language summary -- worked out
 * in code from the spending history, with no network call. Produces the same
 * {@link PredictionsPayload} shape the dashboard, the forecast cache and the CSV exports already use.
 *
 * <p>Every number is reproducible: the same history always gives the same forecast, and every
 * rationale and suggestion quotes the figures it was computed from.
 */
@Service
public class ForecastService {

    /** Months of history a forecast looks at -- long enough to see a trend, short enough to follow a change. */
    static final int WINDOW = 6;
    /** A trend this many percent of the window's mean per month counts as rising or falling. */
    static final double TREND_THRESHOLD = 0.05;
    /** The share of a discretionary category a suggestion proposes trimming. */
    static final double DISCRETIONARY_CUT = 0.15;
    /** Below this a monthly figure isn't worth a suggestion. */
    static final double MIN_SUGGESTION = 10.0;
    static final int MAX_RECOMMENDATIONS = 4;

    private static final Set<String> DISCRETIONARY = Set.of(
            "Dining & Coffee", "Shopping", "Entertainment", "Subscriptions", "Personal Care", "Travel");
    /** Not something to budget down: the rent is the rent. Fees get their own suggestion. */
    private static final Set<String> NOT_FOR_TRIMMING = Set.of("Rent/Mortgage", "Fees & Charges");
    private static final String FEES = "Fees & Charges";

    private static final Map<String, String> CUT_ACTIONS = Map.of(
            "Subscriptions", "Go through your subscriptions and cancel the ones you haven't used lately — the Recurring page lists every regular charge.",
            "Dining & Coffee", "Cook or brew at home a couple more times a week, and set a monthly limit for eating out.",
            "Shopping", "Wait 48 hours before non-essential purchases, and set a monthly shopping budget.",
            "Entertainment", "Set a monthly entertainment budget and look for free or cheaper alternatives for some outings.",
            "Personal Care", "Compare memberships and appointment frequency against how much you actually use them.",
            "Travel", "Book further ahead, travel off-peak, and set a travel budget for the year.");

    /** One category's monthly history, aligned to the shared timeline with zeros where it had no spend. */
    private record Series(String category, List<Double> values) {}

    private record Stats(double recentAverage, double earlierAverage, double slope, double mean,
                         double projection, int months, String trend, String confidence) {}

    public PredictionsPayload generate(List<CategoryMonthlySeries> series, List<MonthlyTotal> monthlyTotals, String currency) {
        NumberFormat money = moneyFormat(currency);

        // The shared timeline is every month with any spend at all. A month missing entirely is a
        // gap in what was imported, not a month where nothing was spent, so it isn't counted as
        // zero -- but within the timeline, a category absent in a month really did spend nothing.
        List<String> timeline = monthlyTotals.stream().map(MonthlyTotal::month).sorted().toList();
        List<String> window = timeline.subList(Math.max(0, timeline.size() - WINDOW), timeline.size());

        List<Prediction> predictions = new ArrayList<>();
        Map<String, Stats> statsByCategory = new LinkedHashMap<>();
        for (CategoryMonthlySeries s : series) {
            Map<String, Double> byMonth = new HashMap<>();
            for (MonthlyTotal m : s.months()) byMonth.put(m.month(), m.total());
            List<Double> values = window.stream().map(m -> byMonth.getOrDefault(m, 0.0)).toList();
            if (values.stream().mapToDouble(Double::doubleValue).sum() == 0) continue; // dormant lately

            Stats stats = stats(values);
            statsByCategory.put(s.category(), stats);
            predictions.add(new Prediction(s.category(), round2(stats.projection()), stats.trend(),
                    stats.confidence(), rationale(stats, money)));
        }
        predictions.sort(Comparator.comparingDouble(Prediction::predictedNextMonth).reversed());

        return new PredictionsPayload(
                summary(monthlyTotals, predictions, money),
                predictions,
                recommendations(statsByCategory, money));
    }

    private static Stats stats(List<Double> values) {
        int n = values.size();
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        List<Double> recent = values.subList(Math.max(0, n - 3), n);
        double recentAverage = recent.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        List<Double> earlier = values.subList(0, Math.max(0, n - 3));
        double earlierAverage = earlier.isEmpty() ? recentAverage
                : earlier.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        double slope = 0;
        double intercept = mean;
        if (n >= 2) {
            double meanX = (n - 1) / 2.0;
            double num = 0;
            double den = 0;
            for (int i = 0; i < n; i++) {
                num += (i - meanX) * (values.get(i) - mean);
                den += (i - meanX) * (i - meanX);
            }
            slope = num / den;
            intercept = mean - slope * meanX;
        }

        // With four or more months, blend the recent average with where the trend line points --
        // but keep the trend within half to one-and-a-half times that average, so one spike or
        // one empty month can't swing the forecast wildly. With less history, just the average.
        double projection = recentAverage;
        if (n >= 4) {
            double trendPoint = Math.max(0, intercept + slope * n);
            double bounded = Math.min(Math.max(trendPoint, recentAverage * 0.5), recentAverage * 1.5);
            projection = (recentAverage + bounded) / 2;
        }

        String trend = "stable";
        if (n >= 3 && mean > 0) {
            double relative = slope / mean;
            if (relative > TREND_THRESHOLD) trend = "increasing";
            else if (relative < -TREND_THRESHOLD) trend = "decreasing";
        }

        // Confidence is how steady the history is (coefficient of variation) and how much of it
        // there is: nothing under three months is better than "low", nothing under six is "high".
        double variance = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / n;
        double cv = mean > 0 ? Math.sqrt(variance) / mean : Double.MAX_VALUE;
        String confidence;
        if (n < 3 || cv > 0.5) confidence = "low";
        else if (n >= WINDOW && cv <= 0.25) confidence = "high";
        else confidence = "medium";

        return new Stats(recentAverage, earlierAverage, slope, mean, projection, n, trend, confidence);
    }

    private static String rationale(Stats s, NumberFormat money) {
        if (s.months() < 3) {
            return "Only " + s.months() + " month" + (s.months() == 1 ? "" : "s")
                    + " of history, so this is the average so far: " + money.format(s.recentAverage()) + " a month.";
        }
        String window = "over the last " + s.months() + " months";
        return switch (s.trend()) {
            case "increasing" -> "Rising by about " + money.format(s.slope()) + " a month " + window
                    + "; the last three averaged " + money.format(s.recentAverage()) + ".";
            case "decreasing" -> "Falling by about " + money.format(-s.slope()) + " a month " + window
                    + "; the last three averaged " + money.format(s.recentAverage()) + ".";
            default -> "Steady at around " + money.format(s.recentAverage()) + " a month " + window + ".";
        };
    }

    private static List<Recommendation> recommendations(Map<String, Stats> statsByCategory, NumberFormat money) {
        List<Recommendation> candidates = new ArrayList<>();
        double totalRecent = statsByCategory.values().stream().mapToDouble(Stats::recentAverage).sum();

        Stats fees = statsByCategory.get(FEES);
        if (fees != null && fees.recentAverage() >= 1) {
            candidates.add(new Recommendation(FEES,
                    "You've paid about " + money.format(fees.recentAverage()) + " a month in fees and charges recently.",
                    "Check what triggered them — overdraft, late-payment and account fees can often be avoided, or waived if you ask.",
                    round2(fees.recentAverage())));
        }

        for (var entry : statsByCategory.entrySet()) {
            String category = entry.getKey();
            Stats s = entry.getValue();
            if (NOT_FOR_TRIMMING.contains(category) || s.months() < 4) continue;
            double rise = s.recentAverage() - s.earlierAverage();
            if ("increasing".equals(s.trend()) && s.recentAverage() > s.earlierAverage() * 1.15 && rise >= MIN_SUGGESTION) {
                candidates.add(new Recommendation(category,
                        category + " went from about " + money.format(s.earlierAverage()) + " to "
                                + money.format(s.recentAverage()) + " a month over the last " + s.months() + " months.",
                        "Set a " + category + " budget near your earlier level of " + money.format(s.earlierAverage())
                                + " a month to stop the climb.",
                        round2(rise)));
            }
        }

        for (var entry : statsByCategory.entrySet()) {
            String category = entry.getKey();
            Stats s = entry.getValue();
            double cut = s.recentAverage() * DISCRETIONARY_CUT;
            if (!DISCRETIONARY.contains(category) || cut < MIN_SUGGESTION) continue;
            double share = totalRecent > 0 ? s.recentAverage() / totalRecent * 100 : 0;
            candidates.add(new Recommendation(category,
                    category + " is about " + money.format(s.recentAverage()) + " a month — "
                            + Math.round(share) + "% of your categorized spending.",
                    CUT_ACTIONS.get(category) + " Trimming it by " + Math.round(DISCRETIONARY_CUT * 100)
                            + "% would save about " + money.format(cut) + " a month.",
                    round2(cut)));
        }

        // Biggest saving first, one suggestion per category (the stronger one wins).
        candidates.sort(Comparator.comparingDouble(Recommendation::potentialMonthlySavings).reversed());
        List<Recommendation> chosen = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Recommendation r : candidates) {
            if (chosen.size() == MAX_RECOMMENDATIONS) break;
            if (seen.add(r.category())) chosen.add(r);
        }
        return chosen;
    }

    private static String summary(List<MonthlyTotal> monthlyTotals, List<Prediction> predictions, NumberFormat money) {
        List<Double> totals = monthlyTotals.stream()
                .sorted(Comparator.comparing(MonthlyTotal::month))
                .map(MonthlyTotal::total).toList();
        int n = totals.size();
        if (n < 2) {
            return "Only " + n + " month of spending so far — forecasts get firmer as more months are imported.";
        }

        List<Double> recent = totals.subList(Math.max(0, n - 3), n);
        double recentAverage = recent.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        StringBuilder text = new StringBuilder("You've spent an average of ")
                .append(money.format(recentAverage)).append(" a month over the last ")
                .append(recent.size()).append(" months");

        if (n >= 6) {
            double before = totals.subList(n - 6, n - 3).stream().mapToDouble(Double::doubleValue).average().orElse(0);
            if (before > 0) {
                double change = (recentAverage - before) / before * 100;
                text.append(Math.abs(change) < 2 ? ", about the same as the three months before"
                        : (change > 0 ? ", up " : ", down ") + Math.round(Math.abs(change)) + "% on the three months before");
            }
        }
        text.append(".");

        if (!predictions.isEmpty()) {
            Prediction biggest = predictions.get(0);
            double forecastTotal = predictions.stream().mapToDouble(Prediction::predictedNextMonth).sum();
            text.append(" Your biggest category is ").append(biggest.category()).append(", forecast at about ")
                    .append(money.format(biggest.predictedNextMonth())).append(" next month, and categorized spending overall at about ")
                    .append(money.format(forecastTotal)).append(".");
        }
        return text.toString();
    }

    private static NumberFormat moneyFormat(String currencyCode) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.US);
        try {
            format.setCurrency(Currency.getInstance(currencyCode != null ? currencyCode : "USD"));
        } catch (IllegalArgumentException e) {
            format.setCurrency(Currency.getInstance("USD"));
        }
        format.setMaximumFractionDigits(0);
        format.setMinimumFractionDigits(0);
        return format;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
