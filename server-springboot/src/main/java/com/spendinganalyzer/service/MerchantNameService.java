package com.spendinganalyzer.service;

import com.spendinganalyzer.repository.MerchantNameRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Naming merchants, and merging look-alikes by giving them one name -- see V29 for what a name
 * changes. This lists every merchant there is (by {@link MerchantNormalizer} key, with how often it
 * appears), suggests look-alikes worth merging, and writes names.
 *
 * <p>A suggestion is keys sharing a first word ("AMAZON.COM", "AMAZON MKTP US") of at least
 * {@link #MIN_STEM} characters, skipping words that start many unrelated merchants' descriptions
 * (card-network and payment-processor prefixes). It's only ever offered: nothing is merged until the
 * user says so, and one turned down isn't offered again.
 */
@Service
public class MerchantNameService {

    public static final int MAX_NAME_LENGTH = 80;
    static final int MIN_STEM = 4;
    static final int MAX_SUGGESTIONS = 12;

    /** First words shared by unrelated merchants -- processors and statement boilerplate, not a brand. */
    static final Set<String> GENERIC_STEMS = Set.of(
            "THE", "POS", "DEBIT", "CREDIT", "CARD", "CHECKCARD", "PURCHASE", "PAYMENT", "ONLINE", "RECURRING",
            "VISA", "MASTERCARD", "PAYPAL", "SQUARE", "STRIPE", "TRANSFER", "DIRECT", "INTL", "INTERNATIONAL", "WWW");

    /**
     * @param name    null when it has none
     * @param count   transactions with this key; 0 for a name kept for a merchant with none left
     * @param example its most common raw description, so it's recognisable -- null when count is 0
     */
    public record Merchant(String key, String name, int count, String example, String lastDate) {}

    /** @param name what to call them: a name one of them already has, else the words they all start with, in title case */
    public record Suggestion(String stem, List<String> keys, String name, int count) {}

    public record Listing(List<Merchant> merchants, List<Suggestion> suggestions) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final MerchantNameRepository names;

    public MerchantNameService(NamedParameterJdbcTemplate jdbc, MerchantNameRepository names) {
        this.jdbc = jdbc;
        this.names = names;
    }

    public Listing list() {
        class Acc {
            int count;
            String example;
            int exampleCount;
            String lastDate;
        }
        Map<String, Acc> byKey = new HashMap<>();
        jdbc.query("SELECT description, COUNT(*) AS n, MAX(date) AS last FROM transactions GROUP BY description",
                new MapSqlParameterSource(), rs -> {
            String description = rs.getString("description");
            int n = rs.getInt("n");
            String last = rs.getString("last");
            Acc acc = byKey.computeIfAbsent(MerchantNormalizer.normalize(description), k -> new Acc());
            acc.count += n;
            if (n > acc.exampleCount || (n == acc.exampleCount && description.compareTo(acc.example) < 0)) {
                acc.example = description;
                acc.exampleCount = n;
            }
            if (acc.lastDate == null || (last != null && last.compareTo(acc.lastDate) > 0)) acc.lastDate = last;
        });

        MerchantNameRepository.Names current = names.load();
        List<Merchant> merchants = new ArrayList<>();
        byKey.forEach((key, acc) -> merchants.add(
                new Merchant(key, current.byKey().get(key), acc.count, acc.example, acc.lastDate)));
        // A name outlives its transactions (reset keeps names); list it so it can still be cleared.
        current.byKey().forEach((key, name) -> {
            if (!byKey.containsKey(key)) merchants.add(new Merchant(key, name, 0, null, null));
        });
        merchants.sort(Comparator.comparingInt(Merchant::count).reversed().thenComparing(Merchant::key));

        return new Listing(merchants, suggest(merchants, names.dismissedStems()));
    }

    static List<Suggestion> suggest(List<Merchant> merchants, Set<String> dismissed) {
        Map<String, List<Merchant>> byStem = new LinkedHashMap<>();
        for (Merchant m : merchants) {
            if (m.count() == 0) continue;
            String stem = stem(m.key());
            if (stem != null && !dismissed.contains(stem)) byStem.computeIfAbsent(stem, k -> new ArrayList<>()).add(m);
        }

        List<Suggestion> suggestions = new ArrayList<>();
        byStem.forEach((stem, group) -> {
            if (group.size() < 2) return;
            Set<String> groupNames = new HashSet<>();
            for (Merchant m : group) groupNames.add(m.name());
            // Already one merchant: every spelling carries the same name.
            if (groupNames.size() == 1 && !groupNames.contains(null)) return;

            // The name most of them (by transactions) already go by, if any do.
            Map<String, Integer> nameWeight = new HashMap<>();
            for (Merchant m : group) if (m.name() != null) nameWeight.merge(m.name(), m.count(), Integer::sum);
            String name = nameWeight.entrySet().stream()
                    // Ties go to the alphabetically first name, so the suggestion is stable.
                    .max(Map.Entry.<String, Integer>comparingByValue()
                            .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                    .map(Map.Entry::getKey)
                    .orElse(sharedLeadingWords(group.stream().map(Merchant::key).toList(), stem));
            suggestions.add(new Suggestion(stem, group.stream().map(Merchant::key).toList(), name,
                    group.stream().mapToInt(Merchant::count).sum()));
        });
        suggestions.sort(Comparator.comparingInt(Suggestion::count).reversed().thenComparing(Suggestion::stem));
        return suggestions.subList(0, Math.min(MAX_SUGGESTIONS, suggestions.size()));
    }

    /** A key's first word, if it's distinctive enough to suggest merging on; null otherwise. */
    static String stem(String key) {
        for (String token : key.split("[^A-Z0-9]+")) {
            if (token.isEmpty()) continue;
            boolean distinctive = token.length() >= MIN_STEM
                    && !token.chars().allMatch(Character::isDigit)
                    && !GENERIC_STEMS.contains(token);
            return distinctive ? token : null;
        }
        return null;
    }

    /**
     * The whole words every key starts with, title-cased: "BLUE BOTTLE COFFEE" and "BLUE BOTTLE COFFEE -
     * POS PURCHASE" give "Blue Bottle Coffee"; "TRADER JOE'S" and "TRADER JOE'S MARKET" give "Trader
     * Joe's". When they share no whole word ("AMAZON.COM", "AMAZON MKTP"), the stem that grouped them.
     */
    static String sharedLeadingWords(List<String> keys, String stem) {
        List<String[]> words = keys.stream().map(k -> k.trim().split("\\s+")).toList();
        List<String> shared = new ArrayList<>();
        for (int i = 0; ; i++) {
            final int at = i;
            if (words.stream().anyMatch(w -> w.length <= at)) break;
            String word = words.get(0)[at];
            if (words.stream().anyMatch(w -> !w[at].equals(word))) break;
            shared.add(titleCase(word));
        }
        return shared.isEmpty() ? titleCase(stem) : String.join(" ", shared);
    }

    private static String titleCase(String word) {
        return word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * Gives every key this name -- one key renames it, several merge them. A name matching one other
     * merchants already have, apart from case, takes that spelling, so "amazon" joins "Amazon" rather
     * than becoming a second merchant. Returns the name as stored.
     */
    @Transactional
    public String rename(Collection<String> keys, String name) {
        Set<String> renaming = new HashSet<>(keys);
        String stored = names.load().byKey().entrySet().stream()
                .filter(e -> !renaming.contains(e.getKey()) && e.getValue().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(name);
        names.setAll(keys, stored);
        return stored;
    }

    public int clear(Collection<String> keys) {
        return names.clearAll(keys);
    }

    public void dismissSuggestion(String stem) {
        names.dismissStem(stem);
    }
}
