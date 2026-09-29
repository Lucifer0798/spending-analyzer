package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CurrencyNetWorth;
import com.spendinganalyzer.dto.NetWorthAccount;
import com.spendinganalyzer.dto.NetWorthForecast;
import com.spendinganalyzer.dto.NetWorthPoint;
import com.spendinganalyzer.dto.NetWorthResponse;
import com.spendinganalyzer.repository.AccountBalanceRepository;
import com.spendinganalyzer.repository.AccountBalanceRepository.BalanceRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns logged balances into a net worth total and a history of it over time — carrying each
 * account's last-known balance forward until a newer one is logged, the way a running balance
 * sheet does. Archived accounts are left out entirely, current total and history alike.
 */
@Service
public class NetWorthService {

    /** A single logged point has no trend to fit a line through. */
    private static final int MIN_POINTS_FOR_TREND = 2;

    /** How far the 6-month projection has to move, relative to the current total, to count as a real trend rather than noise. */
    private static final double TREND_THRESHOLD = 0.01;

    private final AccountBalanceRepository balances;
    private final Clock clock;

    @Autowired
    public NetWorthService(AccountBalanceRepository balances) {
        this(balances, Clock.systemDefaultZone());
    }

    /** Package-private: lets tests fix "today" instead of asserting a projection against a moving target. */
    NetWorthService(AccountBalanceRepository balances, Clock clock) {
        this.balances = balances;
        this.clock = clock;
    }

    public NetWorthResponse compute() {
        List<BalanceRow> rows = balances.findAllForActiveAccounts();
        if (rows.isEmpty()) {
            return new NetWorthResponse(0, List.of(), List.of(), "USD", null, null);
        }

        Map<String, List<BalanceRow>> byCurrency = new LinkedHashMap<>();
        for (BalanceRow row : rows) {
            byCurrency.computeIfAbsent(row.currency(), k -> new ArrayList<>()).add(row);
        }

        if (byCurrency.size() == 1) {
            CurrencyNetWorth only = computeForCurrency(byCurrency.values().iterator().next());
            return new NetWorthResponse(
                    only.total(), only.accounts(), only.history(), only.currency(), null, only.forecast());
        }

        // More than one currency in play: summing balances across them would be as meaningless
        // as summing spend across them, so each gets its own slice instead — same split
        // SummaryResponse uses for the dashboard.
        List<CurrencyNetWorth> perCurrency = byCurrency.values().stream()
                .map(this::computeForCurrency)
                .toList();
        return new NetWorthResponse(0, List.of(), List.of(), null, perCurrency, null);
    }

    /** {@code rows} is already ordered by date then account id, from the repository query. */
    private CurrencyNetWorth computeForCurrency(List<BalanceRow> rows) {
        Map<Long, String> accountNames = new LinkedHashMap<>();
        Map<Long, Double> latestBalance = new LinkedHashMap<>();
        Map<Long, String> latestDate = new LinkedHashMap<>();
        List<NetWorthPoint> history = new ArrayList<>();
        String currentDate = null;

        for (BalanceRow row : rows) {
            // A new date means every account's balance is now known as of the previous date --
            // that's a point on the history chart before this row starts updating the running total.
            if (currentDate != null && !row.date().equals(currentDate)) {
                history.add(new NetWorthPoint(currentDate, round2(sum(latestBalance.values()))));
            }
            accountNames.put(row.accountId(), row.accountName());
            latestBalance.put(row.accountId(), row.balance());
            latestDate.put(row.accountId(), row.date());
            currentDate = row.date();
        }
        history.add(new NetWorthPoint(currentDate, round2(sum(latestBalance.values()))));

        List<NetWorthAccount> accounts = latestBalance.keySet().stream()
                .map(id -> new NetWorthAccount(id, accountNames.get(id), round2(latestBalance.get(id)), latestDate.get(id)))
                .toList();

        return new CurrencyNetWorth(
                rows.get(0).currency(), round2(sum(latestBalance.values())), accounts, history, forecast(history));
    }

    /**
     * Fits a least-squares line through every logged point (x = the date's epoch day, y = that
     * date's total) and evaluates it 1/3/6 months from today, alongside a 3-point moving average
     * of the most recently logged totals as a steadier alternative. Unlike {@link
     * StatsService#linearRegressionNext}, the projection is never clamped at zero — a net worth
     * trending toward more debt than assets is a real, meaningful answer, not a nonsensical one.
     */
    private NetWorthForecast forecast(List<NetWorthPoint> history) {
        int n = history.size();
        if (n < MIN_POINTS_FOR_TREND) return null;

        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = LocalDate.parse(history.get(i).date()).toEpochDay();
            ys[i] = history.get(i).total();
        }

        double meanX = average(xs);
        double meanY = average(ys);
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            num += (xs[i] - meanX) * (ys[i] - meanY);
            den += (xs[i] - meanX) * (xs[i] - meanX);
        }
        // history is built from distinct dates (see the loop above), so with n >= 2 there are
        // always at least two distinct x values and den is never zero.
        double slope = num / den;
        double intercept = meanY - slope * meanX;

        LocalDate today = LocalDate.now(clock);
        double in1Month = round2(intercept + slope * today.plusMonths(1).toEpochDay());
        double in3Months = round2(intercept + slope * today.plusMonths(3).toEpochDay());
        double in6Months = round2(intercept + slope * today.plusMonths(6).toEpochDay());

        int from = Math.max(0, n - 3);
        double movingAverage = round2(average(Arrays.copyOfRange(ys, from, n)));

        double currentTotal = ys[n - 1];
        String trend = classifyTrend(currentTotal, in6Months);

        return new NetWorthForecast(in1Month, in3Months, in6Months, movingAverage, trend);
    }

    private static String classifyTrend(double current, double projected) {
        // Math.max(..., 1.0) avoids dividing by (near) zero without changing the answer for any
        // realistic net worth total -- a current total under a dollar is already a degenerate case.
        double change = (projected - current) / Math.max(Math.abs(current), 1.0);
        if (change > TREND_THRESHOLD) return "increasing";
        if (change < -TREND_THRESHOLD) return "decreasing";
        return "stable";
    }

    private static double average(double[] values) {
        double sum = 0;
        for (double v : values) sum += v;
        return values.length == 0 ? 0 : sum / values.length;
    }

    private static double sum(Collection<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sum();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
