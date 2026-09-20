package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.AnomaliesResponse;
import com.spendinganalyzer.dto.ComparisonResponse;
import com.spendinganalyzer.dto.CurrencyBreakdown;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.RecurringSeries;
import com.spendinganalyzer.dto.SpendingAnomaly;
import com.spendinganalyzer.dto.SummaryResponse;
import com.spendinganalyzer.model.RecurringOverride;
import com.spendinganalyzer.repository.RecurringOverrideRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.AnomalyDetectionService;
import com.spendinganalyzer.service.InsightsService;
import com.spendinganalyzer.service.RecurringDetectionService;
import com.spendinganalyzer.service.StatsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class InsightsController {

    private final StatsService statsService;
    private final InsightsService insightsService;
    private final RecurringDetectionService recurringDetectionService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final TransactionRepository transactionRepository;
    private final RecurringOverrideRepository recurringOverrideRepository;

    public InsightsController(
            StatsService statsService,
            InsightsService insightsService,
            RecurringDetectionService recurringDetectionService,
            AnomalyDetectionService anomalyDetectionService,
            TransactionRepository transactionRepository,
            RecurringOverrideRepository recurringOverrideRepository
    ) {
        this.statsService = statsService;
        this.insightsService = insightsService;
        this.recurringDetectionService = recurringDetectionService;
        this.anomalyDetectionService = anomalyDetectionService;
        this.transactionRepository = transactionRepository;
        this.recurringOverrideRepository = recurringOverrideRepository;
    }

    @GetMapping("/summary")
    public SummaryResponse summary(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        DateRange range = DateRange.of(from, to);
        String currency = statsService.resolveCurrency(accountId);

        if (currency != null) {
            return new SummaryResponse(
                    statsService.computeCategoryTotals(accountId, range),
                    statsService.computeMonthlyTotals(accountId, range),
                    statsService.computeMonthlyCategorySeries(accountId, range),
                    currency,
                    null
            );
        }

        // accountId is null here — resolveCurrency only returns null for "all accounts" once
        // more than one currency is in play. A combined total would mix them, so each currency
        // gets its own slice instead, and the combined lists above are left empty.
        List<CurrencyBreakdown> perCurrency = statsService.currenciesInUse().stream()
                .map(c -> new CurrencyBreakdown(
                        c,
                        statsService.computeCategoryTotals(null, range, c),
                        statsService.computeMonthlyTotals(null, range, c)))
                .filter(b -> !b.categoryTotals().isEmpty())
                .toList();

        return new SummaryResponse(List.of(), List.of(), List.of(), null, perCurrency);
    }

    /**
     * The active range against another period -- by default the one immediately before it, of
     * the same length. Passing {@code compareFrom}/{@code compareTo} compares against that exact
     * range instead, picked independently of {@code from}/{@code to} and not required to share
     * its length. Only meaningful for fully-bounded ranges -- {@code applicable} is false for
     * "all time", a half-open primary filter, or a half-open custom one, rather than the endpoint
     * guessing at a period nobody asked for. Also not applicable when "all accounts" spans more
     * than one currency: the totals it computes would mix them, the same reason {@code /recurring}
     * and budgets refuse in that case.
     */
    @GetMapping("/summary/comparison")
    public ComparisonResponse comparison(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String compareFrom,
            @RequestParam(required = false) String compareTo
    ) {
        String currency = statsService.resolveCurrency(accountId);
        if (currency == null) {
            return ComparisonResponse.NOT_APPLICABLE;
        }

        DateRange range = DateRange.of(from, to);
        // Only build an explicit comparison range when the caller actually asked for one --
        // neither param given means "use the default, auto-derived previous period" -- but once
        // either is given, DateRange.of validates and the half-open case is handled the same way
        // computeComparison already handles a half-open primary range: not applicable, not a guess.
        DateRange explicitPreviousRange = (compareFrom != null || compareTo != null)
                ? DateRange.of(compareFrom, compareTo)
                : null;

        return statsService.computeComparison(accountId, range, explicitPreviousRange)
                .map(comparison -> ComparisonResponse.of(comparison, currency))
                .orElse(ComparisonResponse.NOT_APPLICABLE);
    }

    /** Earliest and latest dates on record, so the UI can bound its date pickers. */
    @GetMapping("/date-bounds")
    public Map<String, Object> dateBounds(@RequestParam(required = false) Long accountId) {
        DateRange available = statsService.availableRange(accountId);
        Map<String, Object> body = new HashMap<>();
        body.put("earliest", available.from());
        body.put("latest", available.to());
        return body;
    }

    @GetMapping("/recurring")
    public Map<String, Object> recurring(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        String currency = statsService.resolveCurrency(accountId);

        // "All accounts" spanning more than one currency has no single unit for a merchant's
        // typical charge or the totals below — recurring detection doesn't group by currency the
        // way the dashboard's stats do, so this asks for one account instead of guessing.
        if (currency == null) {
            return Map.of(
                    "recurring", List.of(),
                    "totalAnnualizedCost", 0,
                    "totalMonthlyEquivalent", 0,
                    "currency", "",
                    "mixedCurrencies", true
            );
        }

        DateRange range = DateRange.of(from, to);
        Map<String, RecurringOverride> overrides = recurringOverrideRepository.loadAll();

        List<RecurringSeries> series = recurringDetectionService.detect(
                        transactionRepository.findSpendingTransactions(accountId, range))
                .stream()
                // Excluded merchants are dropped outright -- never really a subscription, so
                // there's nothing here for them to contribute to the totals below either.
                .filter(s -> !isExcluded(overrides.get(s.merchant())))
                .map(s -> {
                    RecurringOverride o = overrides.get(s.merchant());
                    boolean cancelling = o != null && RecurringOverride.ACTION_CANCEL.equals(o.action());
                    return s.withOverride(cancelling, cancelling ? o.id() : null);
                })
                .toList();

        double totalAnnualized = series.stream().mapToDouble(RecurringSeries::annualizedCost).sum();

        return Map.of(
                "recurring", series,
                "totalAnnualizedCost", Math.round(totalAnnualized * 100.0) / 100.0,
                "totalMonthlyEquivalent", Math.round((totalAnnualized / 12.0) * 100.0) / 100.0,
                "currency", currency,
                "mixedCurrencies", false
        );
    }

    /**
     * Regular deposits — paychecks, and anything else that lands on a steady schedule for a
     * steady amount — detected with the exact same {@link RecurringDetectionService} used for
     * spending, just fed income transactions instead. Unlike {@code /recurring}, there is no
     * cancel/exclude override here: "cancel" describes a subscription you're dropping, which has
     * no income equivalent, and a false-positive income source is rare enough on a personal
     * account not to need its own management screen yet.
     *
     * <p>{@code totalMonthlyEquivalent} is the same average-per-month figure {@code /recurring}
     * already reports (annualized cost divided by twelve) — not a prediction that this specific
     * month will see that much, which is what {@code actualThisMonth} is for. The two are put
     * side by side rather than compared outright, since a quarterly bonus averaged into a
     * monthly figure will honestly disagree with most months by design.
     */
    @GetMapping("/recurring-income")
    public Map<String, Object> recurringIncome(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String month
    ) {
        String currency = statsService.resolveCurrency(accountId);
        if (currency == null) {
            return Map.of(
                    "recurring", List.of(),
                    "totalAnnualizedIncome", 0,
                    "totalMonthlyEquivalent", 0,
                    "month", "",
                    "actualThisMonth", 0,
                    "currency", "",
                    "mixedCurrencies", true
            );
        }

        DateRange range = DateRange.of(from, to);
        List<RecurringSeries> series = recurringDetectionService.detect(
                transactionRepository.findIncomeTransactions(accountId, range));
        double totalAnnualized = series.stream().mapToDouble(RecurringSeries::annualizedCost).sum();

        String resolvedMonth = resolveIncomeMonth(accountId, month);
        double actualThisMonth = statsService.computeIncomeTotal(accountId, monthRange(resolvedMonth));

        return Map.of(
                "recurring", series,
                "totalAnnualizedIncome", Math.round(totalAnnualized * 100.0) / 100.0,
                "totalMonthlyEquivalent", Math.round((totalAnnualized / 12.0) * 100.0) / 100.0,
                "month", resolvedMonth,
                "actualThisMonth", actualThisMonth,
                "currency", currency,
                "mixedCurrencies", false
        );
    }

    /** Same "newest month with data, not today" default {@code BudgetService.resolveMonth} uses. */
    private String resolveIncomeMonth(Long accountId, String requested) {
        if (requested != null && !requested.isBlank()) {
            try {
                return YearMonth.parse(requested.trim()).toString();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("'month' must be in YYYY-MM form, got: " + requested);
            }
        }
        String latest = statsService.latestIncomeDate(accountId);
        return latest != null ? latest.substring(0, 7) : YearMonth.now().toString();
    }

    private static DateRange monthRange(String month) {
        YearMonth ym = YearMonth.parse(month);
        return new DateRange(ym.atDay(1).toString(), ym.atEndOfMonth().toString());
    }

    /**
     * Transactions whose amount stood out against their own category's typical spend. Refuses
     * to compare across currencies the same way {@code /recurring} does — a category's "typical"
     * amount has no single unit once "all accounts" spans more than one currency.
     */
    @GetMapping("/anomalies")
    public AnomaliesResponse anomalies(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        String currency = statsService.resolveCurrency(accountId);
        if (currency == null) {
            return new AnomaliesResponse(List.of(), "", true);
        }

        DateRange range = DateRange.of(from, to);
        List<SpendingAnomaly> anomalies = anomalyDetectionService.detect(
                transactionRepository.findSpendingTransactions(accountId, range));
        return new AnomaliesResponse(anomalies, currency, false);
    }

    /** The cached forecast for the given account, or for every account combined if omitted. */
    @GetMapping("/predictions")
    public ResponseEntity<?> getPredictions(@RequestParam(required = false) Long accountId) {
        return ResponseEntity.ok(insightsService.getCachedPredictions(accountId));
    }

    /**
     * Forecasts deliberately ignore any date filter: a projection built from a narrow window
     * would be worse. They are scoped by account, so generating one while looking at a single
     * account never overwrites — or gets shown under — a different account's forecast.
     */
    @PostMapping("/predictions/refresh")
    public ResponseEntity<?> refreshPredictions(@RequestParam(required = false) Long accountId) {
        if (statsService.resolveCurrency(accountId) == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "Accounts use different currencies — select one account to generate a forecast."));
        }
        try {
            return ResponseEntity.ok(insightsService.refreshPredictions(accountId));
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .body(new ErrorResponse("Prediction generation failed: " + e.getMessage()));
        }
    }

    private static boolean isExcluded(RecurringOverride override) {
        return override != null && RecurringOverride.ACTION_EXCLUDE.equals(override.action());
    }
}
