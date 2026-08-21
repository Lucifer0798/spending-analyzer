package com.spendinganalyzer.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reduces a raw statement description to a stable merchant label.
 *
 * <p>Shared by recurring detection and merchant memory on purpose. Both answer the same
 * underlying question — "are these the same merchant?" — and if they normalised
 * differently, a merchant could be one series to one feature and several to the other.
 *
 * <p>Store numbers, terminal ids, and order references vary per visit
 * ("WHOLE FOODS MARKET #123", "AMAZON.COM*AB123") and are stripped, so every visit to a
 * merchant collapses onto one label.
 */
public final class MerchantNormalizer {

    private static final Pattern STORE_SUFFIX = Pattern.compile("[*#]\\s*[A-Z0-9-]+\\s*$");
    private static final Pattern TRAILING_DIGITS = Pattern.compile("\\s+\\d{3,}\\s*$");
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\b\\d{4,}\\b");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");

    private MerchantNormalizer() {}

    public static String normalize(String description) {
        String s = description.toUpperCase(Locale.ROOT).trim();
        s = STORE_SUFFIX.matcher(s).replaceAll("");
        s = LONG_DIGIT_RUN.matcher(s).replaceAll("");
        s = TRAILING_DIGITS.matcher(s).replaceAll("");
        s = s.replaceAll("[*#]+\\s*$", "");
        s = MULTI_SPACE.matcher(s).replaceAll(" ").trim();
        // Never reduce a description to nothing: a purely numeric merchant would otherwise
        // normalise to an empty key and collide with every other such merchant.
        return s.isEmpty() ? description.trim().toUpperCase(Locale.ROOT) : s;
    }
}
