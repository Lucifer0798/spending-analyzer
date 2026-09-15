package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CurrencyNetWorth;
import com.spendinganalyzer.dto.NetWorthAccount;
import com.spendinganalyzer.dto.NetWorthPoint;
import com.spendinganalyzer.dto.NetWorthResponse;
import com.spendinganalyzer.repository.AccountBalanceRepository;
import com.spendinganalyzer.repository.AccountBalanceRepository.BalanceRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns logged balances into a net worth total and a history of it over time — carrying each
 * account's last-known balance forward until a newer one is logged, the way a running balance
 * sheet does. Archived accounts are left out entirely, current total and history alike.
 */
@Service
public class NetWorthService {

    private final AccountBalanceRepository balances;

    public NetWorthService(AccountBalanceRepository balances) {
        this.balances = balances;
    }

    public NetWorthResponse compute() {
        List<BalanceRow> rows = balances.findAllForActiveAccounts();
        if (rows.isEmpty()) {
            return new NetWorthResponse(0, List.of(), List.of(), "USD", null);
        }

        Map<String, List<BalanceRow>> byCurrency = new LinkedHashMap<>();
        for (BalanceRow row : rows) {
            byCurrency.computeIfAbsent(row.currency(), k -> new ArrayList<>()).add(row);
        }

        if (byCurrency.size() == 1) {
            CurrencyNetWorth only = computeForCurrency(byCurrency.values().iterator().next());
            return new NetWorthResponse(only.total(), only.accounts(), only.history(), only.currency(), null);
        }

        // More than one currency in play: summing balances across them would be as meaningless
        // as summing spend across them, so each gets its own slice instead — same split
        // SummaryResponse uses for the dashboard.
        List<CurrencyNetWorth> perCurrency = byCurrency.values().stream()
                .map(this::computeForCurrency)
                .toList();
        return new NetWorthResponse(0, List.of(), List.of(), null, perCurrency);
    }

    /** {@code rows} is already ordered by date then account id, from the repository query. */
    private CurrencyNetWorth computeForCurrency(List<BalanceRow> rows) {
        Map<Long, String> accountNames = new LinkedHashMap<>();
        Map<Long, Double> latestBalance = new LinkedHashMap<>();
        Map<Long, String> latestDate = new LinkedHashMap<>();
        List<NetWorthPoint> history = new ArrayList<>();
        String currentDate = null;

        for (BalanceRow row : rows) {
            // A new date means every account's balance is now known as of the previous date --
            // that's a point on the history chart before this row starts updating the running total.
            if (currentDate != null && !row.date().equals(currentDate)) {
                history.add(new NetWorthPoint(currentDate, round2(sum(latestBalance.values()))));
            }
            accountNames.put(row.accountId(), row.accountName());
            latestBalance.put(row.accountId(), row.balance());
            latestDate.put(row.accountId(), row.date());
            currentDate = row.date();
        }
        history.add(new NetWorthPoint(currentDate, round2(sum(latestBalance.values()))));

        List<NetWorthAccount> accounts = latestBalance.keySet().stream()
                .map(id -> new NetWorthAccount(id, accountNames.get(id), round2(latestBalance.get(id)), latestDate.get(id)))
                .toList();

        return new CurrencyNetWorth(rows.get(0).currency(), round2(sum(latestBalance.values())), accounts, history);
    }

    private static double sum(Collection<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).sum();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
