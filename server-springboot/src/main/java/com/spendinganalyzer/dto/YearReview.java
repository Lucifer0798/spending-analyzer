package com.spendinganalyzer.dto;

import java.util.List;

/**
 * One calendar year at a glance: what came in, what went out, where it went.
 *
 * @param applicable      false when "all accounts" spans more than one currency -- yearly totals
 *                        would add unlike amounts, the same refusal every other total makes
 * @param year            null only when there are no transactions at all
 * @param savingsRate     saved as a percentage of income; null when there was no income to
 *                        measure against, rather than a meaningless infinity or zero
 * @param monthsWithData  months of this year with any transaction -- a year that's only half
 *                        imported reads very differently from a full one, so the UI says so
 * @param months          always twelve entries, January to December, zero where nothing landed
 * @param biggestMonth    the month with the most spend; null when there was none
 * @param quietestMonth   the month with the least spend, leaving out one only partly imported (the
 *                        newest month when its last day isn't on record yet, or the first when the
 *                        record starts more than a week in); null with fewer than two to choose from
 * @param previousYear    year - 1 when it has any transactions, else null (and
 *                        {@code categoryChanges} empty) -- there's nothing to compare against
 * @param categoryChanges every category's spend this year against last, biggest increase first --
 *                        over {@code comparison.months} only, so a part year isn't set against a full one
 * @param comparison      the two years side by side over the months both have data for; null when
 *                        there's no previous year or no month in common
 */
public record YearReview(
        boolean applicable,
        Integer year,
        List<Integer> availableYears,
        String currency,
        double income,
        double spend,
        double saved,
        Double savingsRate,
        int monthsWithData,
        List<Month> months,
        MonthlyTotal biggestMonth,
        MonthlyTotal quietestMonth,
        List<CategoryShare> topCategories,
        List<MerchantTotal> topMerchants,
        Integer previousYear,
        int previousYearMonthsWithData,
        List<CategoryComparison> categoryChanges,
        YearComparison comparison
) {
    public record Month(String month, double income, double spend) {}

    /**
     * Both years over the same months.
     *
     * @param months      the month numbers (1-12) both years have data for
     * @param likeForLike true when that's fewer months than one of the years has -- the totals then
     *                    aren't the years' own totals, and the page says which months they cover
     * @param savingsRate null without income, as for the year itself
     */
    public record YearComparison(List<Integer> months, boolean likeForLike, double spend, double previousSpend,
                                 double income, double previousIncome, double saved, double previousSaved,
                                 Double savingsRate, Double previousSavingsRate) {}

    /** @param sharePercent this category's slice of the year's total spend */
    public record CategoryShare(String category, double total, int count, double sharePercent) {}

    public static YearReview notApplicable(List<Integer> availableYears) {
        return new YearReview(false, null, availableYears, null, 0, 0, 0, null, 0,
                List.of(), null, null, List.of(), List.of(), null, 0, List.of(), null);
    }
}
