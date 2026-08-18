package com.spendinganalyzer.dto;

import java.util.List;

public record SummaryResponse(
        List<CategoryTotal> categoryTotals,
        List<MonthlyTotal> monthlyTotals,
        List<CategoryMonthlySeries> monthlyByCategory
) {}
