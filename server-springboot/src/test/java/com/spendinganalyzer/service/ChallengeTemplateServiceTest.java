package com.spendinganalyzer.service;

import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.ChallengeRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.ChallengeTemplateService.Template;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Challenge ideas worked out from three months of spending. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChallengeTemplateServiceTest {

    @Autowired private ChallengeTemplateService service;
    @Autowired private TransactionRepository transactions;
    @Autowired private ChallengeRepository challenges;
    @Autowired private AccountRepository accounts;

    private void spend(String date, double amount, String category) {
        transactions.insertBatch(List.of(new ParsedTransaction(date, category.toUpperCase() + " " + date, amount, "debit", category)),
                "template-test", Account.DEFAULT_ID);
    }

    /**
     * Newest spend 30 Sep, so the 90-day window is 3 Jul - 30 Sep. Dining: 12 small purchases
     * (frequent and small). Shopping: 7 at 100 (regular, not small). Rent: 3 big ones (neither).
     */
    private void threeMonths() {
        for (int day = 1; day <= 12; day++) spend("2026-08-%02d".formatted(day * 2), 5, "Dining & Coffee");
        for (int i = 0; i < 7; i++) spend("2026-09-%02d".formatted(3 + i * 3), 100, "Shopping");
        for (String month : List.of("07", "08", "09")) spend("2026-" + month + "-05", 1500, "Rent/Mortgage");
        spend("2026-09-30", 0.01, "Other");          // sets the window's end
        spend("2026-06-01", 999, "Dining & Coffee");  // before the window: ignored
    }

    @Test
    @DisplayName("a no-spend week for frequent small buys, halving the biggest regular category, and a cheaper fortnight overall")
    void suggestsThree() {
        threeMonths();

        assertThat(service.templates(Account.DEFAULT_ID)).containsExactly(
                new Template("no-spend-week", "No Dining & Coffee for a week", "Dining & Coffee", 0, 7, 4.67,
                        "12 purchases in 90 days — about 5 a week"),
                new Template("halve-category", "Halve your usual Shopping for 30 days", "Shopping", 115, 30, 233.33,
                        "Usually about 233 a month"),
                // (60 + 700 + 4500 + 0.01) over 90 days, times 14, is 818.22; 85% of that rounds to 695.
                new Template("cheaper-fortnight", "A cheaper fortnight", null, 695, 14, 818.22,
                        "Usually about 818 every two weeks, all spending"));
    }

    @Test
    @DisplayName("subscriptions and other regular charges aren't suggested -- a week off them isn't a choice")
    void leavesOutRegularCharges() {
        threeMonths();
        // Five monthly plans for a year: 15 small charges in the window, more than Dining's 12.
        for (String service : List.of("STREAMCO", "MUSICBOX", "CLOUDDRIVE", "NEWSDAILY", "FITAPP")) {
            for (int m = 0; m < 12; m++) {
                LocalDate date = LocalDate.of(2025, 10, 15).plusMonths(m);
                transactions.insertBatch(List.of(new ParsedTransaction(date.toString(), service, 9, "debit", "Subscriptions")),
                        "template-test", Account.DEFAULT_ID);
            }
        }

        List<Template> templates = service.templates(Account.DEFAULT_ID);
        assertThat(templates).extracting(Template::category).doesNotContain("Subscriptions");
        assertThat(templates.get(0).title()).isEqualTo("No Dining & Coffee for a week");
        // ...but they still count towards the total a cheaper fortnight is measured against.
        assertThat(templates.get(2).usual()).isGreaterThan(818.22);
    }

    @Test
    @DisplayName("a category with a challenge already running isn't suggested again")
    void skipsCategoriesInPlay() {
        threeMonths();
        LocalDate today = LocalDate.now();
        challenges.create("Cafe break", "Dining & Coffee", 0, today.toString(), today.plusDays(6).toString(), Account.DEFAULT_ID);

        assertThat(service.templates(Account.DEFAULT_ID)).extracting(Template::key)
                .containsExactly("halve-category", "cheaper-fortnight");
    }

    @Test
    @DisplayName("nothing to suggest without spending, or across accounts in more than one currency")
    void nothingToSuggest() {
        assertThat(service.templates(Account.DEFAULT_ID)).isEmpty();

        threeMonths();
        String home = accounts.findById(Account.DEFAULT_ID).orElseThrow().currency();
        accounts.create("Abroad", "checking", "USD".equals(home) ? "EUR" : "USD");
        assertThat(service.templates(null)).isEmpty();
        assertThat(service.templates(Account.DEFAULT_ID)).hasSize(3);
    }

    @Test
    @DisplayName("amounts are whole units, and fives from 20 up")
    void rounding() {
        assertThat(ChallengeTemplateService.rounded(7.4)).isEqualTo(7);
        assertThat(ChallengeTemplateService.rounded(0.3)).isEqualTo(1);
        assertThat(ChallengeTemplateService.rounded(22.6)).isEqualTo(25);
        assertThat(ChallengeTemplateService.rounded(116.67)).isEqualTo(115);
    }
}
