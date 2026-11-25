package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.OwedSplit;
import com.spendinganalyzer.model.SplitRepayment;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.RefundPairRepository;
import com.spendinganalyzer.repository.SplitRepaymentRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Who owes you: split charges (V17) with part of them still to come back, and the repayments
 * recorded against them (V33).
 *
 * <p>A charge's <em>owed</em> is its amount less your share; <em>outstanding</em> is owed less what's
 * been repaid. A repayment can be linked to the credit it arrived as (a Zelle from Sam), which then
 * stops counting as income -- {@code SplitRepaymentRepository.NOT_A_REPAYMENT}. Spending doesn't
 * change either way: it already counts only your share.
 */
@Service
public class OwedService {

    /** People usually pay you back within weeks; a few days early happens too (paying ahead). */
    static final int SUGGEST_DAYS_BEFORE = 3;
    static final int SUGGEST_DAYS_AFTER = 90;
    static final int MAX_SUGGESTIONS = 3;

    /** Words in a split note that aren't who owes it -- so "Sam owes half" names only Sam. */
    private static final Set<String> NOT_NAMES = Set.of(
            "owes", "owe", "owed", "half", "split", "splits", "with", "and", "the", "for", "ways", "way",
            "each", "between", "paid", "pay", "pays", "back", "from", "our", "his", "her", "their", "them",
            "they", "you", "rest", "share", "shares", "dinner", "lunch", "rent", "trip", "bill", "who", "what",
            "third", "thirds", "quarter", "all", "one", "two", "three", "four", "five", "of", "to");
    private static final Pattern WORD = Pattern.compile("[A-Za-z]{3,}");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRepository transactions;
    private final SplitRepaymentRepository repayments;
    private final RefundPairRepository refundPairs;

    public OwedService(NamedParameterJdbcTemplate jdbc, TransactionRepository transactions,
                       SplitRepaymentRepository repayments, RefundPairRepository refundPairs) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.repayments = repayments;
        this.refundPairs = refundPairs;
    }

    /** Outstanding per currency -- amounts in different currencies are never added together. */
    public record CurrencyTotal(String currency, double outstanding, int splits) {}

    public record Owed(List<OwedSplit> splits, List<CurrencyTotal> totals) {}

    private static long cents(double amount) {
        return Math.round(amount * 100);
    }

    /** What isn't yours on a split charge, in cents; zero for anything else. */
    private static long owedCents(Transaction t) {
        if (!"debit".equals(t.type()) || t.splitShare() == null) return 0;
        return Math.max(0, cents(t.amount()) - cents(t.splitShare()));
    }

    /** Split charges with money still to come back, newest first, each with likely repayments. */
    public Owed list(Long accountId) {
        List<Long> ids = jdbc.queryForList("""
                SELECT t.id FROM transactions t
                WHERE t.type = 'debit' AND t.split_share IS NOT NULL
                """ + (accountId != null ? " AND t.account_id = :accountId" : ""),
                new MapSqlParameterSource("accountId", accountId), Long.class);
        // A split where your share is the whole amount owes nothing; the outstanding check below drops it.
        List<Transaction> charges = transactions.findByIds(ids);

        Map<Long, List<SplitRepayment>> byCharge = repayments.findForTransactions(charges.stream().map(Transaction::id).toList())
                .stream().collect(Collectors.groupingBy(SplitRepayment::transactionId));
        Set<Long> creditIds = new HashSet<>();
        byCharge.values().forEach(rows -> rows.forEach(r -> { if (r.repaymentId() != null) creditIds.add(r.repaymentId()); }));
        Map<Long, Transaction> credits = transactions.findByIds(List.copyOf(creditIds)).stream()
                .collect(Collectors.toMap(Transaction::id, Function.identity()));
        List<Transaction> candidates = repaymentCandidates();

        List<OwedSplit> open = new ArrayList<>();
        for (Transaction t : charges) {
            List<SplitRepayment> rows = byCharge.getOrDefault(t.id(), List.of());
            long repaidCents = rows.stream().mapToLong(r -> cents(r.amount())).sum();
            long outstanding = Math.max(0, owedCents(t) - repaidCents);
            if (outstanding == 0) continue;
            open.add(new OwedSplit(t, owedCents(t) / 100.0, repaidCents / 100.0, outstanding / 100.0,
                    rows.stream().map(r -> new OwedSplit.Repayment(r.id(), r.amount(), r.repaidOn(),
                            r.repaymentId() == null ? null : credits.get(r.repaymentId()))).toList(),
                    suggest(t, outstanding, candidates)));
        }
        open.sort(Comparator.comparing((OwedSplit s) -> s.transaction().date()).reversed()
                .thenComparing(s -> s.transaction().id(), Comparator.reverseOrder()));

        Map<String, CurrencyTotal> totals = new LinkedHashMap<>();
        for (OwedSplit s : open) {
            String currency = s.transaction().accountCurrency() != null ? s.transaction().accountCurrency() : "USD";
            CurrencyTotal prior = totals.getOrDefault(currency, new CurrencyTotal(currency, 0, 0));
            totals.put(currency, new CurrencyTotal(currency, Money.round2(prior.outstanding() + s.outstanding()),
                    prior.splits() + 1));
        }
        return new Owed(open, List.copyOf(totals.values()));
    }

    /** Credits that could be a repayment: unsplit, not a transfer, not a refund, not linked already. */
    private List<Transaction> repaymentCandidates() {
        List<Long> ids = jdbc.queryForList("""
                SELECT t.id FROM transactions t
                LEFT JOIN categories c ON c.name = t.category
                WHERE t.type = 'credit' AND t.split_share IS NULL
                  AND COALESCE(c.is_transfer, 0) = 0
                  AND NOT EXISTS (SELECT 1 FROM split_repayments sr WHERE sr.repayment_id = t.id)
                """ + "  AND " + RefundPairRepository.NOT_A_REFUND, new MapSqlParameterSource(), Long.class);
        return transactions.findByIds(ids);
    }

    /** The people a split note names: its words, less the ones that say what the split is. */
    static Set<String> namesIn(String note) {
        Set<String> names = new HashSet<>();
        if (note == null) return names;
        Matcher m = WORD.matcher(note);
        while (m.find()) {
            String word = m.group().toLowerCase(Locale.ROOT);
            if (!NOT_NAMES.contains(word)) names.add(word);
        }
        return names;
    }

    private static boolean mentions(String description, Set<String> names) {
        Matcher m = WORD.matcher(description);
        while (m.find()) {
            if (names.contains(m.group().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * Likely repayments of {@code charge}: in its currency, from a few days before it to
     * {@link #SUGGEST_DAYS_AFTER} after, no more than what's outstanding -- and either from someone
     * its note names or for exactly what's outstanding. Both first, then a name, then the amount;
     * nearest in date within each.
     */
    private List<Transaction> suggest(Transaction charge, long outstandingCents, List<Transaction> candidates) {
        LocalDate chargeDate = parse(charge.date());
        if (chargeDate == null) return List.of();
        Set<String> names = namesIn(charge.splitNote());
        record Scored(Transaction t, int rank, long days) {}
        List<Scored> scored = new ArrayList<>();
        for (Transaction c : candidates) {
            if (charge.accountCurrency() != null && !charge.accountCurrency().equals(c.accountCurrency())) continue;
            LocalDate d = parse(c.date());
            if (d == null) continue;
            long days = ChronoUnit.DAYS.between(chargeDate, d);
            if (days < -SUGGEST_DAYS_BEFORE || days > SUGGEST_DAYS_AFTER) continue;
            if (cents(c.amount()) > outstandingCents) continue;
            boolean named = !names.isEmpty() && mentions(c.description(), names);
            boolean exact = cents(c.amount()) == outstandingCents;
            if (!named && !exact) continue;
            scored.add(new Scored(c, named && exact ? 0 : named ? 1 : 2, Math.abs(days)));
        }
        return scored.stream()
                .sorted(Comparator.comparingInt(Scored::rank).thenComparingLong(Scored::days)
                        .thenComparingLong(s -> s.t().id()))
                .limit(MAX_SUGGESTIONS)
                .map(Scored::t)
                .toList();
    }

    /**
     * Records money paid back toward split charge {@code transactionId}. With {@code repaymentId},
     * it's that credit -- its amount and date; otherwise {@code amount} (default: everything
     * outstanding) on {@code repaidOn} (default: today).
     *
     * @return the new repayment's id
     * @throws NoSuchElementException   the charge (or the credit) doesn't exist
     * @throws IllegalArgumentException it can't be recorded, with why
     */
    @Transactional
    public long record(long transactionId, Double amount, Long repaymentId, String repaidOn) {
        Transaction charge = transactions.findById(transactionId)
                .orElseThrow(() -> new NoSuchElementException("Transaction not found."));
        long owed = owedCents(charge);
        if (owed == 0) throw new IllegalArgumentException("Only a split charge has money owed on it.");
        Double repaidSoFar = repayments.repaidByTransaction(List.of(transactionId)).get(transactionId);
        long repaid = repaidSoFar == null ? 0 : cents(repaidSoFar);
        long outstanding = owed - repaid;
        if (outstanding <= 0) throw new IllegalArgumentException("Nothing is outstanding on this charge.");

        if (repaymentId != null) {
            Transaction credit = transactions.findById(repaymentId)
                    .orElseThrow(() -> new NoSuchElementException("That credit no longer exists."));
            if (!"credit".equals(credit.type())) throw new IllegalArgumentException("A repayment is money coming in.");
            if (credit.splitShare() != null) throw new IllegalArgumentException("A split transaction can't be a repayment.");
            if (repayments.linkedRepaymentIds().contains(repaymentId)) {
                throw new IllegalArgumentException("That credit is already a repayment.");
            }
            if (refundPairs.confirmedRefundIds().contains(repaymentId)) {
                throw new IllegalArgumentException("That credit is a refund of a purchase.");
            }
            if (cents(credit.amount()) > outstanding) {
                throw new IllegalArgumentException("That credit is more than what's still owed on this charge.");
            }
            if (charge.accountCurrency() != null && !charge.accountCurrency().equals(credit.accountCurrency())) {
                throw new IllegalArgumentException("That credit is in a different currency.");
            }
            return repayments.insert(transactionId, credit.amount(), credit.date(), repaymentId);
        }

        long paid = amount == null ? outstanding : cents(amount);
        if (paid <= 0) throw new IllegalArgumentException("amount must be greater than zero.");
        if (paid > outstanding) throw new IllegalArgumentException("That's more than what's still owed on this charge.");
        String on = repaidOn == null || repaidOn.isBlank() ? LocalDate.now().toString() : repaidOn.trim();
        if (parse(on) == null) throw new IllegalArgumentException("repaidOn must be a date like 2026-11-25.");
        return repayments.insert(transactionId, paid / 100.0, on, null);
    }

    /** Takes a recorded repayment back. False for an unknown id. */
    public boolean undo(long repaymentRecordId) {
        return repayments.delete(repaymentRecordId);
    }

    private static LocalDate parse(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }
}
