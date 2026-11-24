package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.RefundPairView;
import com.spendinganalyzer.dto.RefundSuggestion;
import com.spendinganalyzer.model.RefundPair;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.MerchantNameRepository;
import com.spendinganalyzer.repository.RefundPairRepository;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds refunds -- a credit from a merchant you'd earlier bought from -- and, once confirmed, takes
 * the refund off the purchase in every spending total and stops counting it as income. Nothing about
 * either transaction changes: {@code StatsService} reads the pairing ({@code refund_pairs}, V32).
 *
 * <p>A credit is offered as a refund of a purchase when they're in the same account, from the same
 * merchant (refund words like "REFUND" or "RETURN" set aside, or both under one merchant name), the
 * credit is at most {@link #MAX_DAYS_AFTER} days after the purchase, and no bigger than what's left
 * of it. The whole amount is enough on its own; a partial refund also needs a refund word in its
 * description, so a payment that merely comes from a merchant you also pay isn't offered. Each is a
 * suggestion until confirmed -- a wrong pair would hide real spending.
 */
@Service
public class RefundMatchingService {

    /** Return windows run 30-90 days, and a card refund can take a couple of weeks to post. */
    public static final int MAX_DAYS_AFTER = 120;

    /** Set aside when comparing a credit's merchant with a purchase's. */
    private static final Pattern REFUND_WORDS = Pattern.compile(
            "\\b(REFUNDS?|REFUNDED|RFND|RETURNS?|RETURNED|RTN|REVERSAL|REVERSED|CREDIT|CR|MERCHANDISE)\\b");
    /** What marks a credit as a refund on its own, for a partial one. "CREDIT" alone doesn't. */
    private static final Pattern REFUND_SIGNAL = Pattern.compile(
            "\\b(REFUNDS?|REFUNDED|RFND|RETURNS?|RETURNED|RTN|REVERSAL|REVERSED)\\b");
    private static final Pattern LEFTOVER_PUNCTUATION = Pattern.compile("^[\\s\\-:/*]+|[\\s\\-:/]+$");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRepository transactions;
    private final RefundPairRepository pairs;
    private final MerchantNameRepository merchantNames;

    public RefundMatchingService(NamedParameterJdbcTemplate jdbc, TransactionRepository transactions,
                                 RefundPairRepository pairs, MerchantNameRepository merchantNames) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.pairs = pairs;
        this.merchantNames = merchantNames;
    }

    private record Row(long id, String group, LocalDate date, long cents, boolean refundWord, String batchId) {}

    private record Edge(Row purchase, Row refund, int daysAfter, boolean exact) {}

    public record Suggestions(int total, List<RefundSuggestion> suggestions) {}

    public record PairRef(long purchaseId, long refundId) {}

    public record Skipped(long purchaseId, long refundId, String reason) {}

    public record ConfirmResult(int confirmed, List<Skipped> skipped) {}

    /** The merchant a purchase is from, as refunds are compared with it. */
    static String merchantOf(String description) {
        return MerchantNormalizer.normalize(description);
    }

    /** The merchant a credit is from, refund words set aside: "REFUND - AMAZON.COM" is AMAZON.COM. */
    static String refundMerchantOf(String description) {
        String upper = description.toUpperCase(Locale.ROOT);
        String stripped = LEFTOVER_PUNCTUATION.matcher(REFUND_WORDS.matcher(upper).replaceAll(" ")).replaceAll("");
        return MerchantNormalizer.normalize(stripped.isBlank() ? upper : stripped);
    }

    static boolean hasRefundWord(String description) {
        return REFUND_SIGNAL.matcher(description.toUpperCase(Locale.ROOT)).find();
    }

    /** Likely refunds, the newest refund first, at most {@code limit} ({@code total} is the full count). */
    public Suggestions suggest(int limit) {
        return suggest(limit, null);
    }

    /**
     * As above, keeping only pairs with a side from upload {@code batchId} -- what an import summary
     * asks after. Matching still runs over everything, so a pair is the same pair either way.
     */
    public Suggestions suggest(int limit, String batchId) {
        List<Edge> chosen = matchAll();
        if (batchId != null) {
            chosen = chosen.stream()
                    .filter(e -> batchId.equals(e.purchase().batchId()) || batchId.equals(e.refund().batchId()))
                    .toList();
        }
        List<Edge> page = chosen.subList(0, Math.min(limit, chosen.size()));

        List<Long> ids = new ArrayList<>();
        for (Edge e : page) {
            ids.add(e.purchase().id());
            ids.add(e.refund().id());
        }
        Map<Long, Transaction> byId = transactions.findByIds(ids).stream()
                .collect(Collectors.toMap(Transaction::id, Function.identity()));
        Map<Long, Long> refundedCents = pairs.refundedCentsByPurchase();

        List<RefundSuggestion> suggestions = page.stream()
                .map(e -> new RefundSuggestion(byId.get(e.purchase().id()), byId.get(e.refund().id()), e.daysAfter(),
                        refundedCents.getOrDefault(e.purchase().id(), 0L) / 100.0, e.exact()))
                .toList();
        return new Suggestions(chosen.size(), suggestions);
    }

    private List<Edge> matchAll() {
        Set<Long> alreadyRefunds = pairs.confirmedRefundIds();
        Set<String> dismissed = pairs.dismissedKeys();
        Map<Long, Long> refundedCents = pairs.refundedCentsByPurchase();
        MerchantNameRepository.Names names = merchantNames.load();

        Map<String, List<Row>> purchasesByGroup = new HashMap<>();
        List<Row> credits = new ArrayList<>();
        jdbc.query("""
                SELECT t.id, t.account_id, a.currency, t.date, t.amount, t.type, t.description, t.upload_batch_id,
                       COALESCE(c.is_transfer, 0) AS is_transfer, COALESCE(c.is_income, 0) AS is_income
                FROM transactions t
                LEFT JOIN accounts a ON a.id = t.account_id
                LEFT JOIN categories c ON c.name = t.category
                WHERE t.split_share IS NULL
                """, new MapSqlParameterSource(), rs -> {
            if (rs.getInt("is_transfer") == 1) return;
            LocalDate date = parseDate(rs.getString("date"));
            if (date == null) return;
            long id = rs.getLong("id");
            String description = rs.getString("description");
            boolean debit = "debit".equals(rs.getString("type"));
            String merchant = debit ? merchantOf(description) : refundMerchantOf(description);
            // One merchant name over several keys is one merchant, as everywhere else names apply.
            String group = rs.getLong("account_id") + "|" + rs.getString("currency") + "|" + names.label(merchant);
            Row row = new Row(id, group, date, Math.round(rs.getDouble("amount") * 100), !debit && hasRefundWord(description),
                    rs.getString("upload_batch_id"));
            if (debit) {
                if (rs.getInt("is_income") == 1) return;
                purchasesByGroup.computeIfAbsent(group, k -> new ArrayList<>()).add(row);
            } else if (!alreadyRefunds.contains(id)) {
                credits.add(row);
            }
        });

        List<Edge> edges = new ArrayList<>();
        for (Row credit : credits) {
            for (Row purchase : purchasesByGroup.getOrDefault(credit.group(), List.of())) {
                long days = ChronoUnit.DAYS.between(purchase.date(), credit.date());
                if (days < 0 || days > MAX_DAYS_AFTER) continue;
                long left = purchase.cents() - refundedCents.getOrDefault(purchase.id(), 0L);
                if (credit.cents() > left) continue;
                boolean exact = credit.cents() == left;
                if (!exact && !credit.refundWord()) continue;
                if (dismissed.contains(RefundPairRepository.key(purchase.id(), credit.id()))) continue;
                edges.add(new Edge(purchase, credit, (int) days, exact));
            }
        }

        // The whole amount first, then the nearest purchase before it; each credit refunds one
        // purchase, and a purchase takes refunds only up to what's left of it.
        edges.sort(Comparator.comparing((Edge e) -> !e.exact())
                .thenComparingInt(Edge::daysAfter)
                .thenComparingLong(e -> e.refund().id())
                .thenComparingLong(e -> e.purchase().id()));
        Set<Long> usedCredits = new HashSet<>();
        Map<Long, Long> left = new HashMap<>();
        List<Edge> chosen = new ArrayList<>();
        for (Edge e : edges) {
            if (usedCredits.contains(e.refund().id())) continue;
            long remaining = left.computeIfAbsent(e.purchase().id(),
                    id -> e.purchase().cents() - refundedCents.getOrDefault(id, 0L));
            if (e.refund().cents() > remaining) continue;
            usedCredits.add(e.refund().id());
            left.put(e.purchase().id(), remaining - e.refund().cents());
            chosen.add(e);
        }

        chosen.sort(Comparator.comparing((Edge e) -> e.refund().date()).reversed()
                .thenComparing(e -> e.refund().id(), Comparator.reverseOrder()));
        return chosen;
    }

    private static LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    /**
     * Confirms each pair that still qualifies. One that no longer does (edited, deleted, already a
     * refund, more than what's left of the purchase) is skipped with a reason rather than failing
     * the rest.
     */
    @Transactional
    public ConfirmResult confirm(List<PairRef> refs) {
        Set<Long> alreadyRefunds = new HashSet<>(pairs.confirmedRefundIds());
        Map<Long, Long> refundedCents = new HashMap<>(pairs.refundedCentsByPurchase());
        Set<String> transfer = categoryNames("is_transfer = 1");
        Set<String> notSpending = categoryNames("is_transfer = 1 OR is_income = 1");

        int confirmed = 0;
        List<Skipped> skipped = new ArrayList<>();
        for (PairRef ref : refs) {
            Optional<Transaction> purchase = transactions.findById(ref.purchaseId());
            Optional<Transaction> refund = transactions.findById(ref.refundId());
            String reason = reasonNotPairable(purchase, refund, alreadyRefunds, refundedCents, notSpending, transfer);
            if (reason != null) {
                skipped.add(new Skipped(ref.purchaseId(), ref.refundId(), reason));
                continue;
            }
            pairs.save(ref.purchaseId(), ref.refundId(), RefundPair.CONFIRMED);
            alreadyRefunds.add(ref.refundId());
            refundedCents.merge(ref.purchaseId(), Math.round(refund.get().amount() * 100), Long::sum);
            confirmed++;
        }
        return new ConfirmResult(confirmed, skipped);
    }

    /** Null when the refund can be confirmed against the purchase, otherwise why not. */
    private static String reasonNotPairable(Optional<Transaction> purchase, Optional<Transaction> refund,
                                            Set<Long> alreadyRefunds, Map<Long, Long> refundedCents,
                                            Set<String> notSpending, Set<String> transfer) {
        if (purchase.isEmpty() || refund.isEmpty()) return "One of the transactions no longer exists.";
        Transaction p = purchase.get();
        Transaction r = refund.get();
        if (!"debit".equals(p.type()) || !"credit".equals(r.type())) return "A refund is a credit paired with a purchase.";
        if (p.accountId() != r.accountId()) return "The refund is in a different account from the purchase.";
        if (p.splitShare() != null || r.splitShare() != null) return "A split transaction can't be paired with a refund.";
        if (notSpending.contains(p.category())) return "The purchase is in a transfer or income category.";
        if (transfer.contains(r.category())) return "The credit is in a transfer category.";
        LocalDate pd = parseDate(p.date());
        LocalDate rd = parseDate(r.date());
        if (pd == null || rd == null || rd.isBefore(pd)) return "The refund is dated before the purchase.";
        if (alreadyRefunds.contains(r.id())) return "That credit is already a refund of another purchase.";
        long left = Math.round(p.amount() * 100) - refundedCents.getOrDefault(p.id(), 0L);
        if (Math.round(r.amount() * 100) > left) return "The refund is more than what's left of the purchase.";
        return null;
    }

    /** "Not a refund of this": remembered so the pair isn't suggested again. Changes no transaction. */
    @Transactional
    public int dismiss(List<PairRef> refs) {
        int dismissed = 0;
        for (PairRef ref : refs) {
            if (transactions.findById(ref.purchaseId()).isEmpty() || transactions.findById(ref.refundId()).isEmpty()) continue;
            pairs.save(ref.purchaseId(), ref.refundId(), RefundPair.DISMISSED);
            dismissed++;
        }
        return dismissed;
    }

    /** Forgets a pair: a confirmed refund counts as it did before, a dismissed one can be suggested again. */
    public boolean undo(long pairId) {
        return pairs.delete(pairId);
    }

    /** Every recorded pair with its two transactions, the most recent refund first. */
    public List<RefundPairView> list() {
        List<RefundPair> all = pairs.findAll();
        List<Long> ids = new ArrayList<>();
        for (RefundPair p : all) {
            ids.add(p.purchaseId());
            ids.add(p.refundId());
        }
        Map<Long, Transaction> byId = transactions.findByIds(ids).stream()
                .collect(Collectors.toMap(Transaction::id, Function.identity()));
        return all.stream()
                .filter(p -> byId.containsKey(p.purchaseId()) && byId.containsKey(p.refundId()))
                .map(p -> new RefundPairView(p.id(), p.status(), p.createdAt(), byId.get(p.purchaseId()), byId.get(p.refundId())))
                .sorted(Comparator.comparing((RefundPairView v) -> v.refund().date()).reversed()
                        .thenComparing(RefundPairView::id, Comparator.reverseOrder()))
                .toList();
    }

    private Set<String> categoryNames(String flags) {
        Set<String> names = new HashSet<>();
        jdbc.query("SELECT name FROM categories WHERE " + flags,
                rs -> { names.add(rs.getString("name")); });
        return names;
    }
}
