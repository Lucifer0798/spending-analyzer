package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.UncategorizedMerchant;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups uncategorized transactions by merchant for the review queue.
 *
 * <p>Categorization leaves a transaction blank when neither merchant memory nor a built-in rule
 * recognises it. Answering those one row at a time would be tedious, and pointless: the answer is
 * the same for every visit to the same merchant, and it's merchant memory that makes it stick. So
 * the queue asks once per merchant -- the same {@link MerchantNormalizer} key memory uses -- and
 * the client applies the answer with the existing bulk-category endpoint, which teaches memory.
 *
 * <p>Grouped by currency as well as merchant, so a merchant seen on accounts in two currencies is
 * two rows rather than one row adding unlike amounts.
 */
@Service
public class UncategorizedReviewService {

    static final int MAX_EXAMPLES = 3;

    /** Joins merchant and currency into one map key; NUL can't occur in either, so no collisions. */
    private static final String KEY_SEPARATOR = String.valueOf((char) 0);

    private final TransactionRepository transactions;

    public UncategorizedReviewService(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    /** Most transactions first -- clearing those empties the queue fastest -- then biggest total. */
    public List<UncategorizedMerchant> merchants(Long accountId) {
        class Group {
            final String merchant;
            final String currency;
            double total;
            int debits;
            int credits;
            String first;
            String last;
            final Set<String> examples = new LinkedHashSet<>();
            final List<Long> ids = new ArrayList<>();

            Group(String merchant, String currency) {
                this.merchant = merchant;
                this.currency = currency;
            }
        }

        Map<String, Group> groups = new LinkedHashMap<>();
        for (Transaction t : transactions.findUncategorized(accountId)) {
            String merchant = MerchantNormalizer.normalize(t.description());
            String currency = t.accountCurrency() != null ? t.accountCurrency() : "USD";
            Group g = groups.computeIfAbsent(merchant + KEY_SEPARATOR + currency, k -> new Group(merchant, currency));

            g.total += t.amount();
            if ("credit".equals(t.type())) g.credits++; else g.debits++;
            if (g.first == null || t.date().compareTo(g.first) < 0) g.first = t.date();
            if (g.last == null || t.date().compareTo(g.last) > 0) g.last = t.date();
            if (g.examples.size() < MAX_EXAMPLES) g.examples.add(t.description());
            g.ids.add(t.id());
        }

        List<UncategorizedMerchant> result = new ArrayList<>();
        for (Group g : groups.values()) {
            result.add(new UncategorizedMerchant(g.merchant, g.currency, g.ids.size(),
                    Math.round(g.total * 100.0) / 100.0, g.debits, g.credits, g.first, g.last,
                    List.copyOf(g.examples), List.copyOf(g.ids)));
        }
        result.sort(Comparator.comparingInt(UncategorizedMerchant::count).reversed()
                .thenComparing(Comparator.comparingDouble(UncategorizedMerchant::total).reversed())
                .thenComparing(UncategorizedMerchant::merchant));
        return result;
    }
}
