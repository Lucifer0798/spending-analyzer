package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Tag;
import com.spendinganalyzer.model.TransactionTag;
import com.spendinganalyzer.repository.TagRepository.TagUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TagRepositoryTest {

    @Autowired
    private TagRepository repository;

    @Autowired
    private TransactionRepository transactions;

    private long transactionId;
    private long otherTransactionId;

    @BeforeEach
    void seedTransactions() {
        transactions.insertBatch(List.of(
                new ParsedTransaction("2026-06-01", "FLIGHT", 200.00, "debit", null),
                new ParsedTransaction("2026-06-02", "HOTEL", 150.00, "debit", null)
        ), "tag-test-batch", 1L);

        List<com.spendinganalyzer.model.Transaction> rows = transactions.find(
                null, null, null, com.spendinganalyzer.dto.DateRange.ALL, 10, 0);
        transactionId = rows.stream().filter(t -> t.description().equals("FLIGHT")).findFirst().orElseThrow().id();
        otherTransactionId = rows.stream().filter(t -> t.description().equals("HOTEL")).findFirst().orElseThrow().id();
    }

    @Test
    @DisplayName("tagging a transaction creates the tag and the association")
    void tagsATransaction() {
        repository.addTag(transactionId, "business trip");

        assertThat(repository.namesFor(transactionId)).containsExactly("business trip");
        assertThat(repository.findAll()).extracting("name").containsExactly("business trip");
    }

    @Test
    @DisplayName("tagging twice is a no-op, not a duplicate")
    void taggingTwiceIsANoOp() {
        repository.addTag(transactionId, "business trip");
        repository.addTag(transactionId, "business trip");

        assertThat(repository.namesFor(transactionId)).containsExactly("business trip");
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("a tag name is case-insensitive: different casing reuses the same tag")
    void tagNameIsCaseInsensitive() {
        repository.addTag(transactionId, "Business Trip");
        repository.addTag(otherTransactionId, "business trip");

        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findAllWithCounts()).containsExactly(new TagUsage("Business Trip", 2));
    }

    @Test
    @DisplayName("untagging removes the association but leaves the tag for other transactions")
    void untaggingLeavesTheTagForOthers() {
        repository.addTag(transactionId, "business trip");
        repository.addTag(otherTransactionId, "business trip");

        assertThat(repository.removeTag(transactionId, "business trip")).isTrue();

        assertThat(repository.namesFor(transactionId)).isEmpty();
        assertThat(repository.namesFor(otherTransactionId)).containsExactly("business trip");
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("untagging something not tagged reports false")
    void untaggingSomethingNotTaggedReportsFalse() {
        assertThat(repository.removeTag(transactionId, "no such tag")).isFalse();
    }

    @Test
    @DisplayName("namesByTransactionId batches several transactions in one call, omitting untagged ones")
    void namesByTransactionIdBatches() {
        repository.addTag(transactionId, "business trip");
        repository.addTag(transactionId, "reimbursable");
        repository.addTag(otherTransactionId, "reimbursable");

        var byId = repository.namesByTransactionId(List.of(transactionId, otherTransactionId, 999_999L));

        assertThat(byId.get(transactionId)).containsExactlyInAnyOrder("business trip", "reimbursable");
        assertThat(byId.get(otherTransactionId)).containsExactly("reimbursable");
        assertThat(byId).doesNotContainKey(999_999L);
    }

    @Test
    @DisplayName("deleting a tag by name removes every association and reports how many were untagged")
    void deletesTagEverywhere() {
        repository.addTag(transactionId, "business trip");
        repository.addTag(otherTransactionId, "business trip");

        int untagged = repository.deleteByName("business trip");

        assertThat(untagged).isEqualTo(2);
        assertThat(repository.exists("business trip")).isFalse();
        assertThat(repository.namesFor(transactionId)).isEmpty();
    }

    @Test
    @DisplayName("restoreAll replaces every tag and association, ids included")
    void restoreAllReplacesEverything() {
        repository.addTag(transactionId, "old tag");

        List<Tag> tagsToRestore = List.of(new Tag(1, "restored tag", "2026-01-01 00:00:00"));
        List<TransactionTag> assocsToRestore = List.of(
                new TransactionTag(otherTransactionId, 1, "2026-01-01 00:00:00"));

        repository.restoreAll(tagsToRestore, assocsToRestore);

        assertThat(repository.findAll()).extracting("name").containsExactly("restored tag");
        assertThat(repository.namesFor(transactionId)).isEmpty();
        assertThat(repository.namesFor(otherTransactionId)).containsExactly("restored tag");
    }
}
