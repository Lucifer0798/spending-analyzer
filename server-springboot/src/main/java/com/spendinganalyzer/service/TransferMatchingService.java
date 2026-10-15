package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.TransferPairView;
import com.spendinganalyzer.dto.TransferSuggestion;
import com.spendinganalyzer.model.Category;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.model.TransferPair;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.repository.TransferPairRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Finds money moved between your own accounts -- a debit in one and a credit for the same amount
 * in another, a few days apart -- and, once confirmed, moves both halves into a transfer category
 * so neither counts as spending or income.
 *
 * <p>Matching is deliberately strict: same currency, same amount to the cent, different accounts,
 * at most {@link #MAX_DAYS_APART} days apart, and neither side split. Anything looser (fees taken in
 * transit, currency conversion) would start pairing unrelated payments that happen to share an
 * amount, and a wrong pair hides real spending -- worse than a missed one, which just stays counted
 * the way it already was. Every pair is a suggestion until the user confirms it.
 *
 * <p>Confirming doesn't teach merchant memory: a transfer is recognised by having a matching other
 * half, not by its description, and "ONLINE PAYMENT" from one account could equally be a bill.
 */
@Service
public class TransferMatchingService {

    /** Banks can take a few business days to post the receiving side; a long weekend is 3-4 days. */
    public static final int MAX_DAYS_APART = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionRepository transactions;
    private final TransferPairRepository pairs;
    private final CategoryRepository categories;

    public TransferMatchingService(
            NamedParameterJdbcTemplate jdbc,
            TransactionRepository transactions,
            TransferPairRepository pairs,
            CategoryRepository categories
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.pairs = pairs;
        this.categories = categories;
    }

    /** Just the columns matching needs, so suggesting doesn't load every description into memory. */
    private record Candidate(long id, long accountId, String currency, LocalDate date, long cents, boolean inTransferCategory,
                             String batchId) {}

    private record Edge(Candidate debit, Candidate credit, int daysApart) {}

    public record Suggestions(int total, List<TransferSuggestion> suggestions) {}

    public record PairRef(long debitId, long creditId) {}

    public record Skipped(long debitId, long creditId, String reason) {}

    public record ConfirmResult(int confirmed, List<Skipped> skipped) {}

    /**
     * The category confirmed transfers are moved into: the one called "Transfer" if it's still
     * flagged as a transfer, otherwise the first transfer-flagged category in display order. Empty
     * when the user has removed every transfer category, in which case there's nowhere to put them.
     */
    public Optional<String> transferCategory() {
        List<Category> flagged = categories.findAll().stream().filter(Category::isTransfer).toList();
        return flagged.stream().map(Category::name).filter("Transfer"::equals).findFirst()
                .or(() -> flagged.stream().map(Category::name).findFirst());
    }

    /**
     * Likely transfers, newest first, at most {@code limit} of them ({@code total} is the full
     * count). Each transaction appears in at most one suggestion: when a debit could pair with
     * several credits, the closest in date wins, then the lower ids, so the answer is stable.
     */
    public Suggestions suggest(int limit) {
        return suggest(limit, null);
    }

    /**
     * As above, keeping only pairs with at least one side from upload {@code batchId} -- what an
     * import summary asks after. Pairing still runs over everything, so a pair is the same pair
     * whichever way it's asked for.
     */
    public Suggestions suggest(int limit, String batchId) {
        List<Edge> chosen = matchAll();
        if (batchId != null) {
            chosen = chosen.stream()
                    .filter(e -> batchId.equals(e.debit().batchId()) || batchId.equals(e.credit().batchId()))
                    .toList();
        }
        List<Edge> page = chosen.subList(0, Math.min(limit, chosen.size()));

        List<Long> ids = new ArrayList<>();
        for (Edge e : page) {
            ids.add(e.debit().id());
            ids.add(e.credit().id());
        }
        Map<Long, Transaction> byId = transactions.findByIds(ids).stream()
                .collect(Collectors.toMap(Transaction::id, Function.identity()));

        List<TransferSuggestion> suggestions = page.stream()
                .map(e -> new TransferSuggestion(byId.get(e.debit().id()), byId.get(e.credit().id()), e.daysApart()))
                .toList();
        return new Suggestions(chosen.size(), suggestions);
    }

    private List<Edge> matchAll() {
        Set<Long> alreadyPaired = pairs.confirmedTransactionIds();
        Set<String> dismissed = pairs.dismissedKeys();

        Map<String, List<Candidate>> creditsByAmount = new HashMap<>();
        List<Candidate> debits = new ArrayList<>();
        jdbc.query("""
                SELECT t.id, t.account_id, a.currency, t.date, t.amount, t.type, t.upload_batch_id,
                       COALESCE(c.is_transfer, 0) AS is_transfer
                FROM transactions t
                LEFT JOIN accounts a ON a.id = t.account_id
                LEFT JOIN categories c ON c.name = t.category
                WHERE t.split_share IS NULL
                """, new MapSqlParameterSource(), rs -> {
            long id = rs.getLong("id");
            if (alreadyPaired.contains(id)) return;
            LocalDate date = parseDate(rs.getString("date"));
            if (date == null) return;
            Candidate c = new Candidate(id, rs.getLong("account_id"), rs.getString("currency"), date,
                    Math.round(rs.getDouble("amount") * 100), rs.getInt("is_transfer") == 1,
                    rs.getString("upload_batch_id"));
            if ("debit".equals(rs.getString("type"))) {
                debits.add(c);
            } else {
                creditsByAmount.computeIfAbsent(amountKey(c), k -> new ArrayList<>()).add(c);
            }
        });

        List<Edge> edges = new ArrayList<>();
        for (Candidate debit : debits) {
            for (Candidate credit : creditsByAmount.getOrDefault(amountKey(debit), List.of())) {
                if (debit.accountId() == credit.accountId()) continue;
                // Both halves already excluded from spend and income -- nothing a pairing would fix.
                if (debit.inTransferCategory() && credit.inTransferCategory()) continue;
                if (dismissed.contains(TransferPairRepository.key(debit.id(), credit.id()))) continue;
                int days = (int) Math.abs(ChronoUnit.DAYS.between(debit.date(), credit.date()));
                if (days > MAX_DAYS_APART) continue;
                edges.add(new Edge(debit, credit, days));
            }
        }

        // Greedy by closeness: each transaction is used once, the nearest-dated partner first.
        edges.sort(Comparator.comparingInt(Edge::daysApart)
                .thenComparingLong(e -> e.debit().id())
                .thenComparingLong(e -> e.credit().id()));
        Set<Long> used = new HashSet<>();
        List<Edge> chosen = new ArrayList<>();
        for (Edge e : edges) {
            if (used.contains(e.debit().id()) || used.contains(e.credit().id())) continue;
            used.add(e.debit().id());
            used.add(e.credit().id());
            chosen.add(e);
        }

        chosen.sort(Comparator.comparing((Edge e) -> e.debit().date()).reversed()
                .thenComparing(e -> e.debit().id(), Comparator.reverseOrder()));
        return chosen;
    }

    private static String amountKey(Candidate c) {
        return c.currency() + "|" + c.cents();
    }

    private static LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }

    /**
     * Confirms each pair that still qualifies, moving both sides into the transfer category with
     * {@code category_source = 'user'} -- the user said so -- and remembering what each side was.
     * A pair that no longer qualifies (edited, deleted, already paired) is skipped with a reason
     * rather than failing the rest.
     *
     * @throws IllegalStateException when there's no transfer category to move them into
     */
    @Transactional
    public ConfirmResult confirm(List<PairRef> refs) {
        String transferCategory = transferCategory()
                .orElseThrow(() -> new IllegalStateException(
                        "There's no transfer category to move these into. Mark a category as a transfer on the Manage page first."));
        Set<String> transferNames = transferCategoryNames();
        Set<Long> alreadyPaired = new HashSet<>(pairs.confirmedTransactionIds());

        int confirmed = 0;
        List<Skipped> skipped = new ArrayList<>();
        for (PairRef ref : refs) {
            Optional<Transaction> debit = transactions.findById(ref.debitId());
            Optional<Transaction> credit = transactions.findById(ref.creditId());
            String reason = reasonNotPairable(debit, credit, alreadyPaired, transferNames);
            if (reason != null) {
                skipped.add(new Skipped(ref.debitId(), ref.creditId(), reason));
                continue;
            }
            Transaction d = debit.get();
            Transaction c = credit.get();
            pairs.save(d.id(), c.id(), TransferPair.CONFIRMED,
                    d.category(), d.categorySource(), c.category(), c.categorySource());
            // A side already in a transfer category keeps it -- the user may have picked a more
            // specific one ("Savings transfer") than the default.
            if (!transferNames.contains(d.category())) transactions.updateCategory(d.id(), transferCategory, "user");
            if (!transferNames.contains(c.category())) transactions.updateCategory(c.id(), transferCategory, "user");
            alreadyPaired.add(d.id());
            alreadyPaired.add(c.id());
            confirmed++;
        }
        return new ConfirmResult(confirmed, skipped);
    }

    /** Null when the two can be confirmed as a transfer, otherwise why not. */
    private static String reasonNotPairable(Optional<Transaction> debit, Optional<Transaction> credit,
                                            Set<Long> alreadyPaired, Set<String> transferNames) {
        if (debit.isEmpty() || credit.isEmpty()) return "One of the transactions no longer exists.";
        Transaction d = debit.get();
        Transaction c = credit.get();
        if (!"debit".equals(d.type()) || !"credit".equals(c.type())) return "A transfer is a debit paired with a credit.";
        if (d.accountId() == c.accountId()) return "Both transactions are in the same account.";
        if (d.accountCurrency() == null || !d.accountCurrency().equals(c.accountCurrency())) {
            return "The accounts are in different currencies.";
        }
        if (Math.round(d.amount() * 100) != Math.round(c.amount() * 100)) return "The amounts differ.";
        if (d.splitShare() != null || c.splitShare() != null) return "A split transaction can't be a transfer.";
        LocalDate dd = parseDate(d.date());
        LocalDate cd = parseDate(c.date());
        if (dd == null || cd == null || Math.abs(ChronoUnit.DAYS.between(dd, cd)) > MAX_DAYS_APART) {
            return "The dates are more than " + MAX_DAYS_APART + " days apart.";
        }
        if (alreadyPaired.contains(d.id()) || alreadyPaired.contains(c.id())) {
            return "One of the transactions is already part of a transfer.";
        }
        if (transferNames.contains(d.category()) && transferNames.contains(c.category())) {
            return "Both are already in a transfer category.";
        }
        return null;
    }

    /** "Not a transfer": remembered so the pair isn't suggested again. Changes no transaction. */
    @Transactional
    public int dismiss(List<PairRef> refs) {
        int dismissed = 0;
        for (PairRef ref : refs) {
            if (transactions.findById(ref.debitId()).isEmpty() || transactions.findById(ref.creditId()).isEmpty()) continue;
            pairs.save(ref.debitId(), ref.creditId(), TransferPair.DISMISSED, null, null, null, null);
            dismissed++;
        }
        return dismissed;
    }

    /**
     * Forgets a pair. For a confirmed one, each side goes back to the category it had before -- but
     * only if it's still in a transfer category; a side the user has since recategorized keeps
     * their newer choice. Returns how many transactions were restored, or empty for an unknown id.
     */
    @Transactional
    public Optional<Integer> undo(long pairId) {
        Optional<TransferPair> found = pairs.findById(pairId);
        if (found.isEmpty()) return Optional.empty();
        TransferPair pair = found.get();

        int restored = 0;
        if (TransferPair.CONFIRMED.equals(pair.status())) {
            Set<String> transferNames = transferCategoryNames();
            restored += restore(pair.debitId(), pair.debitPrevCategory(), pair.debitPrevSource(), transferNames);
            restored += restore(pair.creditId(), pair.creditPrevCategory(), pair.creditPrevSource(), transferNames);
        }
        pairs.delete(pairId);
        return Optional.of(restored);
    }

    private int restore(long id, String prevCategory, String prevSource, Set<String> transferNames) {
        Optional<Transaction> t = transactions.findById(id);
        if (t.isEmpty() || !transferNames.contains(t.get().category())) return 0;
        // The previous category might have been deleted since; an unknown name would be an
        // invalid category, so it falls back to uncategorized -- the review queue will ask.
        String category = prevCategory != null && categories.exists(prevCategory) ? prevCategory : null;
        if (category != null && category.equals(t.get().category())) return 0;
        transactions.updateCategory(id, category, category == null ? null : prevSource);
        return 1;
    }

    /**
     * Every recorded pair with its two transactions, the most recent money movement first -- not
     * the most recently recorded, since "mark all" records a whole batch at once.
     */
    public List<TransferPairView> list() {
        List<TransferPair> all = pairs.findAll();
        List<Long> ids = new ArrayList<>();
        for (TransferPair p : all) {
            ids.add(p.debitId());
            ids.add(p.creditId());
        }
        Map<Long, Transaction> byId = transactions.findByIds(ids).stream()
                .collect(Collectors.toMap(Transaction::id, Function.identity()));
        return all.stream()
                .filter(p -> byId.containsKey(p.debitId()) && byId.containsKey(p.creditId()))
                .map(p -> new TransferPairView(p.id(), p.status(), p.createdAt(), byId.get(p.debitId()), byId.get(p.creditId())))
                .sorted(Comparator.comparing((TransferPairView v) -> v.debit().date()).reversed()
                        .thenComparing(TransferPairView::id, Comparator.reverseOrder()))
                .toList();
    }

    private Set<String> transferCategoryNames() {
        return categories.findAll().stream().filter(Category::isTransfer).map(Category::name)
                .collect(Collectors.toSet());
    }
}
