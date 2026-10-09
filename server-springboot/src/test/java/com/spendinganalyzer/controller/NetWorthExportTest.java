package com.spendinganalyzer.controller;

import com.spendinganalyzer.repository.AccountBalanceRepository;
import com.spendinganalyzer.repository.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Net Worth page's history and raw balances, as CSV -- the same numbers the page shows. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NetWorthExportTest {

    private static final String BOM = String.valueOf((char) 0xFEFF);

    @Autowired
    private ExportController controller;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountBalanceRepository balances;

    private static List<String> lines(ResponseEntity<byte[]> response) {
        String text = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(text).startsWith(BOM);   // Excel opens it as UTF-8, like every other export
        return Arrays.stream(text.substring(1).split("\r\n")).filter(l -> !l.isEmpty()).toList();
    }

    private void seed() {
        long savings = accounts.create("Savings", "savings", "USD").id();
        long euro = accounts.create("Euro account", "checking", "EUR").id();
        long old = accounts.create("Old card", "credit_card", "USD").id();
        accounts.update(old, null, null, true, null);   // archived: left out, like on the page

        balances.upsert(1L, "2026-05-01", 1000);
        balances.upsert(savings, "2026-05-01", 500);
        balances.upsert(1L, "2026-06-01", 1200);        // savings carried forward at 500
        balances.upsert(euro, "2026-05-15", 300);
        balances.upsert(old, "2026-05-01", -900);
    }

    @Test
    @DisplayName("net worth history carries balances forward, keeps currencies as separate series, and skips archived accounts")
    void netWorthHistory() {
        seed();

        assertThat(lines(controller.netWorth())).containsExactly(
                "date,currency,net_worth",
                "2026-05-01,USD,1500.0",
                "2026-06-01,USD,1700.0",
                "2026-05-15,EUR,300.0");
    }

    @Test
    @DisplayName("balances lists every logged entry for active accounts, oldest first")
    void balancesExport() {
        seed();

        assertThat(lines(controller.balances())).containsExactly(
                "date,account,currency,balance",
                "2026-05-01,Default,USD,1000.0",
                "2026-05-01,Savings,USD,500.0",
                "2026-05-15,Euro account,EUR,300.0",
                "2026-06-01,Default,USD,1200.0");
    }

    @Test
    @DisplayName("nothing logged yet gives header-only files, not errors")
    void emptyExports() {
        assertThat(lines(controller.netWorth())).containsExactly("date,currency,net_worth");
        assertThat(lines(controller.balances())).containsExactly("date,account,currency,balance");
    }
}
