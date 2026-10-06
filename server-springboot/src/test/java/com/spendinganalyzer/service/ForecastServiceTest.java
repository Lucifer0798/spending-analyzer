package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryMonthlySeries;
import com.spendinganalyzer.dto.MonthlyTotal;
import com.spendinganalyzer.dto.Prediction;
import com.spendinganalyzer.dto.PredictionsPayload;
import com.spendinganalyzer.dto.Recommendation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** The in-app forecast: deterministic numbers, and wording that quotes them. */
class ForecastServiceTest {

    private final ForecastService service = new ForecastService();

    private static final List<String> SIX_MONTHS = List.of("2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06");

    /** Builds the series and overall totals the way StatsService would, from category -> month -> amount. */
    private PredictionsPayload forecast(Map<String, Map<String, Double>> spend, String currency) {
        List<CategoryMonthlySeries> series = new ArrayList<>();
        Map<String, Double> totals = new TreeMap<>();
        for (var category : spend.entrySet()) {
            List<MonthlyTotal> months = new TreeMap<>(category.getValue()).entrySet().stream()
                    .map(e -> new MonthlyTotal(e.getKey(), e.getValue())).toList();
            series.add(new CategoryMonthlySeries(category.getKey(), months, 0, 0, 0, 0));
            category.getValue().forEach((m, v) -> totals.merge(m, v, Double::sum));
        }
        List<MonthlyTotal> monthly = totals.entrySet().stream().map(e -> new MonthlyTotal(e.getKey(), e.getValue())).toList();
        return service.generate(series, monthly, currency);
    }

    private static Map<String, Double> months(List<String> keys, double... values) {
        Map<String, Double> map = new HashMap<>();
        for (int i = 0; i < values.length; i++) map.put(keys.get(i), values[i]);
        return map;
    }

    private static Prediction prediction(PredictionsPayload p, String category) {
        return p.predictions().stream().filter(x -> x.category().equals(category)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a steady category forecasts its usual amount, flat, with high confidence")
    void steadyCategory() {
        var p = forecast(Map.of("Groceries", months(SIX_MONTHS, 400, 400, 400, 400, 400, 400)), "USD");

        Prediction groceries = prediction(p, "Groceries");
        assertThat(groceries.predictedNextMonth()).isEqualTo(400.0);
        assertThat(groceries.trend()).isEqualTo("stable");
        assertThat(groceries.confidence()).isEqualTo("high");
        assertThat(groceries.rationale()).isEqualTo("Steady at around $400 a month over the last 6 months.");
    }

    @Test
    @DisplayName("a rising category blends its recent average with the trend line, and says how fast it's rising")
    void risingCategory() {
        // Recent three average 180; the trend line points at 220; the forecast blends to 200.
        var p = forecast(Map.of("Dining & Coffee", months(SIX_MONTHS, 100, 120, 140, 160, 180, 200)), "USD");

        Prediction dining = prediction(p, "Dining & Coffee");
        assertThat(dining.predictedNextMonth()).isEqualTo(200.0);
        assertThat(dining.trend()).isEqualTo("increasing");
        assertThat(dining.rationale()).isEqualTo("Rising by about $20 a month over the last 6 months; the last three averaged $180.");
    }

    @Test
    @DisplayName("one spike can't drag the forecast past one-and-a-half times the recent average")
    void spikeIsBounded() {
        var p = forecast(Map.of("Shopping", months(SIX_MONTHS, 50, 50, 50, 50, 50, 1000)), "USD");

        // Recent average is (50+50+1000)/3 = 366.67; the trend point is capped at 550, so the
        // forecast is (366.67 + 550) / 2 -- not the 1000-plus a raw trend line would give.
        assertThat(prediction(p, "Shopping").predictedNextMonth()).isEqualTo(458.33);
        assertThat(prediction(p, "Shopping").confidence()).isEqualTo("low");
    }

    @Test
    @DisplayName("short history is just the average so far, with low confidence and a rationale that says so")
    void shortHistory() {
        var p = forecast(Map.of("Groceries", months(SIX_MONTHS, 300, 500)), "USD");

        Prediction groceries = prediction(p, "Groceries");
        assertThat(groceries.predictedNextMonth()).isEqualTo(400.0);
        assertThat(groceries.confidence()).isEqualTo("low");
        assertThat(groceries.rationale()).startsWith("Only 2 months of history");
    }

    @Test
    @DisplayName("a month a category skipped counts as zero for it, but a category with nothing lately is left out")
    void zeroMonthsAndDormantCategories() {
        List<String> eight = List.of("2025-11", "2025-12", "2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06");
        Map<String, Map<String, Double>> spend = new HashMap<>();
        spend.put("Groceries", months(eight, 100, 100, 100, 100, 100, 100, 100, 100));
        spend.put("Travel", months(eight, 900, 900));                       // only before the 6-month window
        spend.put("Entertainment", Map.of("2026-06", 90.0));                // once, in the latest month

        var p = forecast(spend, "USD");

        assertThat(p.predictions()).extracting(Prediction::category).doesNotContain("Travel");
        // 0, 0, 90 over the last three months averages 30 -- not the 90 an average of only the
        // months it appeared in would give -- blended with the trend point, capped at 1.5 x 30 = 45.
        assertThat(prediction(p, "Entertainment").predictedNextMonth()).isEqualTo(37.5);
    }

    @Test
    @DisplayName("suggestions: fees in full, a rising category back to its earlier level, and trims of discretionary spend")
    void recommendations() {
        Map<String, Map<String, Double>> spend = new HashMap<>();
        spend.put("Fees & Charges", months(SIX_MONTHS, 35, 35, 35, 35, 35, 35));
        spend.put("Dining & Coffee", months(SIX_MONTHS, 100, 120, 140, 160, 180, 200));
        spend.put("Subscriptions", months(SIX_MONTHS, 120, 120, 120, 120, 120, 120));
        spend.put("Rent/Mortgage", months(SIX_MONTHS, 2000, 2000, 2000, 2000, 2000, 2000));

        var p = forecast(spend, "USD");

        assertThat(p.recommendations())
                .extracting(Recommendation::category, Recommendation::potentialMonthlySavings)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Dining & Coffee", 60.0),   // 120 -> 180
                        org.assertj.core.groups.Tuple.tuple("Fees & Charges", 35.0),
                        org.assertj.core.groups.Tuple.tuple("Subscriptions", 18.0));    // 15% of 120
        assertThat(p.recommendations().get(0).insight()).isEqualTo(
                "Dining & Coffee went from about $120 to $180 a month over the last 6 months.");
        // Rent is the rent: never suggested as something to cut.
        assertThat(p.recommendations()).extracting(Recommendation::category).doesNotContain("Rent/Mortgage");
    }

    @Test
    @DisplayName("the summary compares the last three months with the three before and names the biggest category")
    void summary() {
        var p = forecast(Map.of("Groceries", months(SIX_MONTHS, 400, 400, 400, 500, 500, 500)), "EUR");

        assertThat(p.summary()).startsWith("You've spent an average of €500 a month over the last 3 months, up 25% on the three months before.");
        assertThat(p.summary()).contains("Your biggest category is Groceries");
    }

    @Test
    @DisplayName("the same history always gives the same forecast")
    void deterministic() {
        Map<String, Map<String, Double>> spend = Map.of(
                "Groceries", months(SIX_MONTHS, 380, 410, 395, 420, 405, 430),
                "Shopping", months(SIX_MONTHS, 90, 300, 40, 220, 130, 80));

        assertThat(forecast(spend, "USD")).isEqualTo(forecast(spend, "USD"));
    }
}
