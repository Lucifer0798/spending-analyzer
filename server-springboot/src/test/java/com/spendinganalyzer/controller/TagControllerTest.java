package com.spendinganalyzer.controller;

import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.TagRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TagControllerTest {

    @Autowired
    private TagController controller;

    @Autowired
    private TagRepository tags;

    @Autowired
    private TransactionRepository transactions;

    private long transactionId;

    @BeforeEach
    void seed() {
        transactions.insertBatch(
                List.of(new ParsedTransaction("2026-06-01", "FLIGHT", 200.00, "debit", null)),
                "tag-controller-batch", 1L);
        transactionId = transactions
                .find(null, null, null, com.spendinganalyzer.dto.DateRange.ALL, 1, 0).get(0).id();
    }

    @Test
    @DisplayName("lists every tag with its usage count")
    void listsTagsWithCounts() {
        tags.addTag(transactionId, "business trip");

        var list = controller.list();

        assertThat(list).extracting("name").containsExactly("business trip");
        assertThat(list.get(0).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("deletes a tag everywhere, and reports a missing one as 404")
    void deletesTagEverywhere() {
        tags.addTag(transactionId, "business trip");

        ResponseEntity<?> response = controller.delete("business trip");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(tags.exists("business trip")).isFalse();
        assertThat(controller.delete("business trip").getStatusCode().value()).isEqualTo(404);
    }
}
