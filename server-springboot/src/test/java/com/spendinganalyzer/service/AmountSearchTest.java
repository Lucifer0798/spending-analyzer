package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The search box reading amounts: what counts as one, and what it then finds. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AmountSearchTest {

    @Autowired private TransactionRepository transactions;
    @Autowired private NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("an exact amount, a comparison or a range -- in whole cents, with commas and a currency sign allowed")
    void parses() {
        assertThat(AmountSearch.parse("42.50")).contains(new AmountSearch(4250L, 4250L, true));
        assertThat(AmountSearch.parse(" $42.5 ")).contains(new AmountSearch(4250L, 4250L, true));
        assertThat(AmountSearch.parse("42")).contains(new AmountSearch(4200L, 4200L, true));
        assertThat(AmountSearch.parse("1,200")).contains(new AmountSearch(120000L, 120000L, true));
        assertThat(AmountSearch.parse(".99")).contains(new AmountSearch(99L, 99L, true));

        assertThat(AmountSearch.parse(">200")).contains(new AmountSearch(20001L, null, false));
        assertThat(AmountSearch.parse(">= 200")).contains(new AmountSearch(20000L, null, false));
        assertThat(AmountSearch.parse("<50")).contains(new AmountSearch(null, 4999L, false));
        assertThat(AmountSearch.parse("<=€50.25")).contains(new AmountSearch(null, 5025L, false));

        assertThat(AmountSearch.parse("10-20")).contains(new AmountSearch(1000L, 2000L, true));
        assertThat(AmountSearch.parse("20..10")).contains(new AmountSearch(1000L, 2000L, true));
        assertThat(AmountSearch.parse("$10 – $20")).contains(new AmountSearch(1000L, 2000L, true));
    }

    @Test
    @DisplayName("anything else is text: words, three decimals, misplaced commas, a bare sign, and numbers no charge could be")
    void notAmounts() {
        for (String term : List.of("coffee", "42.505", "1,20", "abc12", "-5", ">", "12 34", "99999999999999999999")) {
            assertThat(AmountSearch.parse(term)).as(term).isEqualTo(Optional.empty());
        }
    }

    private void spend(String description, double amount) {
        transactions.insertBatch(List.of(new ParsedTransaction("2026-05-01", description, amount, "debit", "Shopping")),
                "amount-search-test", Account.DEFAULT_ID);
    }

    private List<String> search(String term) {
        List<String> found = transactions.find(null, null, null, DateRange.ALL, null, term, 100, 0).stream()
                .map(Transaction::description).sorted().toList();
        assertThat(transactions.count(null, null, null, DateRange.ALL, null, term)).as("count for " + term).isEqualTo(found.size());
        return found;
    }

    @Test
    @DisplayName("finds charges by amount -- exact, above, below, between -- and a plain number still finds it in descriptions")
    void findsByAmount() {
        spend("CORNER CAFE", 4.50);
        spend("STORE 4250", 12.00);
        spend("BIG TV", 450.00);
        spend("RENT", 1200.00);
        spend("MARKET", 15.00);
        spend("NETFLIX", 15.49);

        assertThat(search("4.50")).containsExactly("CORNER CAFE");
        assertThat(search("4250")).containsExactly("STORE 4250");      // as text; nothing costs 4,250
        assertThat(search(">400")).containsExactly("BIG TV", "RENT");
        assertThat(search("<5")).containsExactly("CORNER CAFE");
        assertThat(search("15-16")).containsExactly("MARKET", "NETFLIX");
        assertThat(search("1,200")).containsExactly("RENT");
        assertThat(search("cafe")).containsExactly("CORNER CAFE");     // text as before
    }

    @Test
    @DisplayName("matches the charge on the statement, not a split share")
    void statementAmount() {
        spend("DINNER FOR FOUR", 100);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM transactions", new MapSqlParameterSource(), Long.class);
        transactions.updateSplit(id, 25.0, "four ways");

        assertThat(search("100")).containsExactly("DINNER FOR FOUR");
        assertThat(search("25")).isEmpty();
    }
}
