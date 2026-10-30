package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CashFlowForecast;
import com.spendinganalyzer.dto.CashFlowForecast.AccountForecast;
import com.spendinganalyzer.dto.CashFlowForecast.Event;
import com.spendinganalyzer.dto.CashFlowForecast.Point;
import com.spendinganalyzer.dto.RecurringSeries;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.AccountBalance;
import com.spendinganalyzer.model.RecurringOverride;
import com.spendinganalyzer.repository.AccountBalanceRepository;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.MerchantNameRepository;
import com.spendinganalyzer.repository.RecurringOverrideRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where each account's balance is heading over the next few weeks.
 *
 * <p>It starts from what's known: the account's latest logged balance, moved by every transaction
 * imported since (credits minus debits at full amount -- a split share is what you owe, not what
 * left the account). That's the balance as of the newest data, {@code asOf}. From there it adds
 * what's expected: the account's own recurring debits and credits, found by the same {@link
 * RecurringDetectionService} as the Recurring page but fed every transaction in the account, not
 * just spending -- a fixed monthly transfer to savings moves this balance as surely as rent does.
 *
 * <p>Deliberately cautious, since the point is to warn before an account runs dry:
 * <ul>
 *   <li>A charge that was due by {@code asOf} but hasn't appeared is counted once, on the first
 *       projected day -- it may just be late. A late <em>deposit</em> isn't counted: better to be
 *       pleasantly surprised than to plan on money that might not come.</li>
 *   <li>A series that has missed a whole further cycle is taken to have stopped.</li>
 *   <li>Irregular spending (groceries, eating out) isn't guessed at -- only what repeats on a
 *       schedule for a steady amount. The forecast says so rather than inventing a daily burn.</li>
 * </ul>
 */
@Service
public class CashFlowService {

    /** After this long without new data, a projection is mostly guesswork, and says so. */
    static final int STALE_AFTER_DAYS = 45;

    /** Account types where going below zero is a problem worth a warning. A card is supposed to be owed on. */
    private static final Set<String> WARN_TYPES = Set.of("checking", "savings", "cash");

    private final AccountRepository accounts;
    private final AccountBalanceRepository balances;
    private final TransactionRepository transactions;
    private final RecurringDetectionService recurring;
    private final RecurringOverrideRepository overrides;
    private final MerchantNameRepository merchantNames;
    private final Clock clock;

    @Autowired
    public CashFlowService(AccountRepository accounts, AccountBalanceRepository balances,
                           TransactionRepository transactions, RecurringDetectionService recurring,
                           RecurringOverrideRepository overrides, MerchantNameRepository merchantNames) {
        this(accounts, balances, transactions, recurring, overrides, merchantNames, Clock.systemDefaultZone());
    }

    /** Package-private: lets tests fix "today". */
    CashFlowService(AccountRepository accounts, AccountBalanceRepository balances,
                    TransactionRepository transactions, RecurringDetectionService recurring,
                    RecurringOverrideRepository overrides, MerchantNameRepository merchantNames, Clock clock) {
        this.accounts = accounts;
        this.balances = balances;
        this.transactions = transactions;
        this.merchantNames = merchantNames;
        this.recurring = recurring;
        this.overrides = overrides;
        this.clock = clock;
    }

    public CashFlowForecast forecast(int horizonDays) {
        LocalDate today = LocalDate.now(clock);
        LocalDate end = today.plusDays(horizonDays);
        Map<String, RecurringOverride> flags = overrides.loadAll();

        List<AccountForecast> forecasts = new ArrayList<>();
        List<String> withoutBalance = new ArrayList<>();
        for (Account account : accounts.findAll(false)) {
            List<AccountBalance> history = balances.findByAccountId(account.id());
            if (history.isEmpty()) {
                withoutBalance.add(account.name());
                continue;
            }
            forecasts.add(forecastAccount(account, history.get(0), today, end, flags));
        }
        return new CashFlowForecast(today.toString(), horizonDays, forecasts, withoutBalance);
    }

    private AccountForecast forecastAccount(Account account, AccountBalance logged, LocalDate today, LocalDate end,
                                            Map<String, RecurringOverride> flags) {
        TransactionRepository.NetChange since = transactions.netChangeAfter(account.id(), logged.date());
        LocalDate asOf = LocalDate.parse(since.latestDate() != null ? since.latestDate() : logged.date());
        double starting = Money.round2(logged.balance() + since.net());

        List<Event> events = new ArrayList<>();
        MerchantNameRepository.Names names = merchantNames.load();
        for (RecurringSeries d : recurring.detect(transactions.findByAccountAndType(account.id(), "debit"))) {
            RecurringSeries s = d.withName(names.label(d.merchant()));
            RecurringOverride flag = flags.get(s.merchant());
            if (flag != null && RecurringOverride.ACTION_EXCLUDE.equals(flag.action())) continue;
            boolean cancelling = flag != null && RecurringOverride.ACTION_CANCEL.equals(flag.action());
            project(s, -amountOf(s), asOf, end, cancelling, true, events);
        }
        for (RecurringSeries d : recurring.detect(transactions.findByAccountAndType(account.id(), "credit"))) {
            project(d.withName(names.label(d.merchant())), amountOf(d), asOf, end, false, false, events);
        }
        // Same-day order: money in before money out, so a payday and a rent on one date don't show a
        // dip that wouldn't really happen.
        events.sort(Comparator.comparing(Event::date).thenComparing(e -> -e.amount()));

        List<Event> withBalances = new ArrayList<>();
        List<Point> daily = new ArrayList<>();
        double balance = starting;
        double lowest = starting;
        LocalDate lowestDate = asOf;
        LocalDate firstNegative = starting < 0 ? asOf : null;
        int next = 0;
        for (LocalDate day = asOf; !day.isAfter(end); day = day.plusDays(1)) {
            while (next < events.size() && events.get(next).date().equals(day.toString())) {
                Event e = events.get(next++);
                balance = Money.round2(balance + e.amount());
                withBalances.add(new Event(e.date(), e.description(), e.amount(), e.cadence(), balance, e.overdue(), e.cancelling()));
            }
            daily.add(new Point(day.toString(), balance));
            if (balance < lowest) {
                lowest = balance;
                lowestDate = day;
            }
            if (firstNegative == null && balance < 0) firstNegative = day;
        }

        return new AccountForecast(
                account.id(), account.name(), account.type(), account.currency(),
                logged.date(), logged.balance(), asOf.toString(), since.count(), starting,
                asOf.isBefore(today.minusDays(STALE_AFTER_DAYS)),
                withBalances, daily, balance, lowest, lowestDate.toString(),
                firstNegative == null ? null : firstNegative.toString(),
                firstNegative != null && WARN_TYPES.contains(account.type()));
    }

    /** What a series usually costs: its latest amount when the price changed, otherwise the average. */
    private static double amountOf(RecurringSeries s) {
        return Money.round2(s.priceChanged() ? s.lastAmount() : s.averageAmount());
    }

    /**
     * Adds a series' occurrences after {@code asOf} through {@code end}. Monthly and longer cadences
     * step by calendar month from the last real occurrence, so a payday on the 1st stays on the 1st
     * instead of drifting by a day or two each month the way stepping by a median day count would.
     */
    static void project(RecurringSeries s, double signedAmount, LocalDate asOf, LocalDate end,
                        boolean cancelling, boolean countIfLate, List<Event> into) {
        LocalDate last = LocalDate.parse(s.lastDate());
        int n = 1;
        if (!step(s, last, 1).isAfter(asOf)) {
            // Due by the time the data ends, yet not in it. One cycle late might just be late; two
            // cycles late means it has stopped.
            if (!step(s, last, 2).isAfter(asOf)) return;
            LocalDate firstDay = asOf.plusDays(1);
            if (countIfLate && !firstDay.isAfter(end)) {
                into.add(new Event(firstDay.toString(), s.name(), signedAmount, s.cadence(), 0, true, cancelling));
            }
            n = 2;
        }
        for (LocalDate due = step(s, last, n); !due.isAfter(end); due = step(s, last, ++n)) {
            into.add(new Event(due.toString(), s.name(), signedAmount, s.cadence(), 0, false, cancelling));
        }
    }

    /** The {@code n}th occurrence after {@code last}. */
    private static LocalDate step(RecurringSeries s, LocalDate last, int n) {
        return switch (s.cadence()) {
            case "monthly" -> last.plusMonths(n);
            case "quarterly" -> last.plusMonths(3L * n);
            case "yearly" -> last.plusYears(n);
            default -> last.plusDays((long) Math.max(1, s.medianIntervalDays()) * n);
        };
    }

}
