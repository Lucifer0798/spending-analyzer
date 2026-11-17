package com.spendinganalyzer.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the Transactions search box means when it holds an amount, in whole cents:
 *
 * <ul>
 *   <li>{@code 42.50}, {@code $42.50}, {@code 1,200} -- that exact charge. Also still searched as
 *       text, since a plain number may well be a store or reference number in a description.</li>
 *   <li>{@code >200}, {@code >=200}, {@code <50}, {@code <=50} -- above or below; amounts only.</li>
 *   <li>{@code 10-20} or {@code 10..20} -- between, both ends included (either order). Also text.</li>
 * </ul>
 *
 * Matched against a charge's full amount, as it appears on the statement -- not a split share.
 * Anything else isn't an amount and searches text only, as before. Amounts are parsed with
 * BigDecimal, so there's no rounding drift and no narrowing cast of what was typed.
 *
 * @param minCents inclusive lower bound; null for none
 * @param maxCents inclusive upper bound; null for none
 * @param alsoText whether the term should still match descriptions too
 */
public record AmountSearch(Long minCents, Long maxCents, boolean alsoText) {

    private static final String NUMBER = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?|\\.\\d{1,2})";
    private static final String CURRENCY = "[$€£]?";
    private static final Pattern COMPARISON = Pattern.compile("^(>=|<=|>|<)\\s*" + CURRENCY + NUMBER + "$");
    private static final Pattern RANGE = Pattern.compile("^" + CURRENCY + NUMBER + "\\s*(?:-|–|\\.\\.)\\s*" + CURRENCY + NUMBER + "$");
    private static final Pattern EXACT = Pattern.compile("^" + CURRENCY + NUMBER + "$");

    /** Far beyond any real charge; keeps cents comfortably inside a long. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");

    public static Optional<AmountSearch> parse(String term) {
        if (term == null) return Optional.empty();
        String t = term.trim();
        try {
            Matcher m = COMPARISON.matcher(t);
            if (m.matches()) {
                long cents = cents(m.group(2));
                return Optional.of(switch (m.group(1)) {
                    case ">" -> new AmountSearch(cents + 1, null, false);
                    case ">=" -> new AmountSearch(cents, null, false);
                    case "<" -> new AmountSearch(null, cents - 1, false);
                    default -> new AmountSearch(null, cents, false);
                });
            }
            m = RANGE.matcher(t);
            if (m.matches()) {
                long a = cents(m.group(1));
                long b = cents(m.group(2));
                return Optional.of(new AmountSearch(Math.min(a, b), Math.max(a, b), true));
            }
            m = EXACT.matcher(t);
            if (m.matches()) {
                long cents = cents(m.group(1));
                return Optional.of(new AmountSearch(cents, cents, true));
            }
        } catch (ArithmeticException | NumberFormatException e) {
            // Too big to be a charge: treat it as text.
        }
        return Optional.empty();
    }

    private static long cents(String number) {
        BigDecimal amount = new BigDecimal(number.replace(",", ""));
        if (amount.compareTo(MAX_AMOUNT) > 0) throw new ArithmeticException("not a charge");
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }
}
