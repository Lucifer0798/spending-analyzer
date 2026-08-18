package com.spendinganalyzer.dto;

import java.util.List;

public record CategoryMonthlySeries(
        String category,
        List<MonthlyTotal> months,
        double linearTrendNextMonth,
        double movingAverage3mo,
        double overallTotal,
        double lastMonthTotal
) {}
