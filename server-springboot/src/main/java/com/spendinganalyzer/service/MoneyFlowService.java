package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.MoneyFlow;
import com.spendinganalyzer.model.Category;
import com.spendinganalyzer.repository.CategoryRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The money flow diagram's numbers. Built from the same {@link StatsService} queries as the rest of
 * the Dashboard, so the spend side matches the category chart and the income side matches the
 * budget-vs-income card -- transfers are on neither side, which is what keeps money moved between
 * your own accounts from showing up as both income and spending.
 */
@Service
public class MoneyFlowService {

    private final StatsService stats;
    private final CategoryRepository categories;

    public MoneyFlowService(StatsService stats, CategoryRepository categories) {
        this.stats = stats;
        this.categories = categories;
    }

    public MoneyFlow flow(Long accountId, DateRange range) {
        String currency = stats.resolveCurrency(accountId);
        if (currency == null) {
            return MoneyFlow.NOT_APPLICABLE;
        }

        Map<String, String> groupByCategory = new HashMap<>();
        for (Category c : categories.findAll()) {
            if (c.groupName() != null) groupByCategory.put(c.name(), c.groupName());
        }

        List<MoneyFlow.Source> income = stats.computeIncomeCategoryTotals(accountId, range, null).stream()
                .filter(t -> t.total() > 0)
                .map(t -> new MoneyFlow.Source(t.category(), t.total()))
                .toList();
        List<MoneyFlow.Sink> spending = stats.computeCategoryTotals(accountId, range).stream()
                .filter(t -> t.total() > 0)
                .map(t -> new MoneyFlow.Sink(t.category(), groupByCategory.get(t.category()), t.total()))
                .toList();

        double totalIncome = Money.round2(income.stream().mapToDouble(MoneyFlow.Source::total).sum());
        double totalSpending = Money.round2(spending.stream().mapToDouble(MoneyFlow.Sink::total).sum());
        double difference = Money.round2(totalIncome - totalSpending);

        return new MoneyFlow(true, currency, income, spending, totalIncome, totalSpending,
                Math.max(0, difference), Math.max(0, -difference));
    }

}
