package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.DailyTotal;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.model.Challenge;
import com.spendinganalyzer.repository.ChallengeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How each spending challenge is going, worked out from transactions on every read -- so a statement
 * imported late moves the numbers, the way budgets do.
 *
 * <p>Spend is the app's usual definition (debits outside income and transfer categories, a split
 * counting only your share), in the challenge's category (or all of it) and account (or all of
 * them). "All accounts" across more than one currency can't be added up, so such a challenge reports
 * itself not applicable rather than mixing.
 *
 * <p><b>Saved so far</b> compares against your own habit: average daily spend in the same scope over
 * the {@link #BASELINE_DAYS} days before the challenge started, times the days elapsed, minus what was
 * actually spent. Null when there's no spend on record in that window to compare with.
 */
@Service
public class ChallengeService {

    static final int BASELINE_DAYS = 90;
    /** Longer than any challenge worth calling short; a guard, not a target. */
    public static final int MAX_DAYS = 366;

    public enum Status { UPCOMING, ACTIVE, COMPLETED, BROKEN }

    /** @param spent null for a day still to come */
    public record Day(String date, Double spent) {}

    /**
     * @param daysElapsed    days from the start through today (or the end, once it's over)
     * @param streak         consecutive days with nothing spent, counting back from today (or the end)
     * @param onPace         a cap only: spent no more than the limit's share of the days so far
     * @param remaining      a cap only: what's left of the limit, never below zero
     * @param saved          against your usual spend before it started; null with nothing to compare
     * @param dataThrough    the newest transaction on record in its scope -- spend after it may just
     *                       not be imported yet
     * @param brokenOn       the day spend first went over the limit; null unless broken
     */
    public record Progress(Challenge challenge, boolean applicable, String currency, Status status, double spent,
                           int daysTotal, int daysElapsed, int streak, Boolean onPace, Double remaining,
                           Double saved, String dataThrough, List<Day> days, String brokenOn) {}

    /**
     * Something about a challenge worth the header's alerts menu: a running cap ahead of pace, or one
     * that broke or was kept in the last {@link #RECENT_DAYS} days.
     */
    public enum AlertKind { OFF_PACE, BROKEN, KEPT }

    public record Alert(AlertKind kind, Progress progress) {}

    /** How long "just broke" and "just kept" stay in the alerts menu -- news, not a standing reminder. */
    static final int RECENT_DAYS = 3;

    private final ChallengeRepository challenges;
    private final StatsService stats;
    private final Clock clock;

    @Autowired
    public ChallengeService(ChallengeRepository challenges, StatsService stats) {
        this(challenges, stats, Clock.systemDefaultZone());
    }

    /** Package-private: lets tests fix "today". */
    ChallengeService(ChallengeRepository challenges, StatsService stats, Clock clock) {
        this.challenges = challenges;
        this.stats = stats;
        this.clock = clock;
    }

    public List<Progress> all() {
        return challenges.findAll().stream().map(this::progress).toList();
    }

    /** What the alerts menu shows: off pace first, then broken, then kept. */
    public List<Alert> alerts() {
        LocalDate today = LocalDate.now(clock);
        LocalDate recent = today.minusDays(RECENT_DAYS);
        List<Progress> progress = all();
        List<Alert> alerts = new ArrayList<>();
        for (AlertKind kind : AlertKind.values()) {
            for (Progress p : progress) {
                if (!p.applicable()) continue;
                boolean show = switch (kind) {
                    case OFF_PACE -> p.status() == Status.ACTIVE && Boolean.FALSE.equals(p.onPace());
                    case BROKEN -> p.status() == Status.BROKEN && !LocalDate.parse(p.brokenOn()).isBefore(recent);
                    case KEPT -> p.status() == Status.COMPLETED
                            && !LocalDate.parse(p.challenge().endDate()).isBefore(recent);
                };
                if (show) alerts.add(new Alert(kind, p));
            }
        }
        return alerts;
    }

    public Progress progress(Challenge c) {
        LocalDate today = LocalDate.now(clock);
        LocalDate start = LocalDate.parse(c.startDate());
        LocalDate end = LocalDate.parse(c.endDate());
        int daysTotal = (int) ChronoUnit.DAYS.between(start, end) + 1;

        String currency = stats.resolveCurrency(c.accountId());
        if (currency == null) {
            return new Progress(c, false, null, today.isBefore(start) ? Status.UPCOMING : Status.ACTIVE, 0,
                    daysTotal, 0, 0, null, null, null, null, List.of(), null);
        }
        String dataThrough = stats.availableRange(c.accountId()).to();

        // Through today, or the end once it's over; nothing yet before it starts.
        LocalDate through = today.isBefore(end) ? today : end;
        int daysElapsed = today.isBefore(start) ? 0 : (int) ChronoUnit.DAYS.between(start, through) + 1;

        Map<String, Double> byDay = new HashMap<>();
        if (daysElapsed > 0) {
            for (DailyTotal d : stats.computeDailyTotals(c.accountId(), new DateRange(c.startDate(), through.toString()),
                    currency, c.category())) {
                byDay.put(d.date(), d.total());
            }
        }
        double spent = Money.round2(byDay.values().stream().mapToDouble(Double::doubleValue).sum());

        List<Day> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            boolean counted = daysElapsed > 0 && !d.isAfter(through);
            days.add(new Day(d.toString(), counted ? byDay.getOrDefault(d.toString(), 0.0) : null));
        }

        int streak = 0;
        for (int i = daysElapsed - 1; i >= 0 && days.get(i).spent() == 0; i--) streak++;

        // Broken is the day the running total first passes the limit -- with a cent of slack, so
        // rounding never breaks a challenge that was kept. One calculation for both, so a broken
        // challenge always has the day it broke (the alerts menu reads it).
        String brokenOn = null;
        double running = 0;
        for (Day d : days) {
            if (d.spent() == null) break;
            running += d.spent();
            if (running > c.limitAmount() + 0.005) {
                brokenOn = d.date();
                break;
            }
        }
        boolean broken = brokenOn != null;
        Status status = broken ? Status.BROKEN
                : today.isBefore(start) ? Status.UPCOMING
                : today.isAfter(end) ? Status.COMPLETED
                : Status.ACTIVE;

        Boolean onPace = null;
        Double remaining = null;
        if (!c.noSpend()) {
            remaining = Money.round2(Math.max(0, c.limitAmount() - spent));
            onPace = spent <= c.limitAmount() * daysElapsed / daysTotal + 0.005;
        }

        return new Progress(c, true, currency, status, spent, daysTotal, daysElapsed, streak, onPace, remaining,
                saved(c, start, currency, daysElapsed, spent), dataThrough, days, brokenOn);
    }

    private Double saved(Challenge c, LocalDate start, String currency, int daysElapsed, double spent) {
        if (daysElapsed == 0) return null;
        DateRange before = new DateRange(start.minusDays(BASELINE_DAYS).toString(), start.minusDays(1).toString());
        double usual = stats.computeDailyTotals(c.accountId(), before, currency, c.category()).stream()
                .mapToDouble(DailyTotal::total).sum();
        if (usual <= 0) return null;
        return Money.round2(Math.max(0, usual / BASELINE_DAYS * daysElapsed - spent));
    }
}
