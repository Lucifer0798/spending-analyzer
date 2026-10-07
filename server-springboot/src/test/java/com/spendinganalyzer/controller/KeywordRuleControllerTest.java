package com.spendinganalyzer.controller;

import com.spendinganalyzer.model.KeywordRule;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.KeywordRuleRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class KeywordRuleControllerTest {

    @Autowired
    private KeywordRuleController controller;

    @Autowired
    private KeywordRuleRepository rules;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private TransactionRepository transactions;

    @Test
    @DisplayName("creates a rule, collapsing stray whitespace in the keyword")
    void createsRule() {
        ResponseEntity<?> response = controller.create(Map.of("keyword", "  PAWS   VET ", "category", "Healthcare"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((KeywordRule) response.getBody()).keyword()).isEqualTo("PAWS VET");
    }

    @Test
    @DisplayName("rejects a keyword that's too short, punctuation only, or for a category that doesn't exist")
    void rejectsBadInput() {
        assertThat(controller.create(Map.of("keyword", "V", "category", "Healthcare")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.create(Map.of("keyword", "&&", "category", "Healthcare")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.create(Map.of("keyword", "VET", "category", "Not A Category")).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("the same keyword in any case is one rule, so two rules can't disagree")
    void duplicateKeywordIsConflict() {
        controller.create(Map.of("keyword", "VET", "category", "Healthcare"));

        ResponseEntity<?> response = controller.create(Map.of("keyword", "vet", "category", "Shopping"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("preview counts whole-word matches, how many are still uncategorized, with examples")
    void previewCountsMatches() {
        transactions.insertBatch(List.of(
                new ParsedTransaction("2026-05-01", "PAWS & CLAWS VET 0091", 120, "debit", null),
                new ParsedTransaction("2026-05-02", "CITY VET CLINIC", 80, "debit", "Healthcare"),
                new ParsedTransaction("2026-05-03", "CORVETTE PARTS", 300, "debit", null)
        ), "preview-test", 1L);

        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) controller.preview("vet").getBody();

        assertThat(body.get("matches")).isEqualTo(2);           // not CORVETTE
        assertThat(body.get("uncategorized")).isEqualTo(1);
        assertThat((List<String>) body.get("examples")).containsExactlyInAnyOrder("PAWS & CLAWS VET 0091", "CITY VET CLINIC");
    }

    @Test
    @DisplayName("renaming or deleting a category carries its keyword rules along")
    void categoryChangesCascade() {
        long pets = categories.create("Pets", false, false).id();
        long rule = rules.create("VET", "Pets").id();

        categories.rename(pets, "Pets", "Animals");
        assertThat(rules.findById(rule)).get().extracting(KeywordRule::category).isEqualTo("Animals");

        categories.deleteAndReassign(pets, "Animals", "Other");
        assertThat(rules.findById(rule)).get().extracting(KeywordRule::category).isEqualTo("Other");
    }

    @Test
    @DisplayName("deletes a rule, and 404s on one that doesn't exist")
    void deletesRule() {
        long id = rules.create("VET", "Healthcare").id();

        assertThat(controller.delete(id).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.delete(id).getStatusCode().value()).isEqualTo(404);
    }
}
