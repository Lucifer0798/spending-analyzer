package com.spendinganalyzer.service;

import com.spendinganalyzer.controller.CategorizeController;
import com.spendinganalyzer.controller.ChangeLogController;
import com.spendinganalyzer.controller.TransactionController;
import com.spendinganalyzer.controller.UploadController;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.KeywordRuleRepository;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.repository.TagRepository;
import com.spendinganalyzer.repository.TransactionReceiptRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.ChangeLogService.Entry;
import com.spendinganalyzer.service.ChangeLogService.UndoResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChangeLogServiceTest {

    @Autowired private ChangeLogService changeLog;
    @Autowired private ChangeLogController history;
    @Autowired private TransactionController transactionsApi;
    @Autowired private UploadController uploads;
    @Autowired private CategorizeController categorize;
    @Autowired private BackupService backups;
    @Autowired private TransactionRepository transactions;
    @Autowired private MerchantCategoryRepository memory;
    @Autowired private KeywordRuleRepository keywordRules;
    @Autowired private TagRepository tags;
    @Autowired private TransactionReceiptRepository receipts;
    @Autowired private NamedParameterJdbcTemplate jdbc;

    private long add(String date, String description, double amount, String category) {
        transactions.insertBatch(List.of(new ParsedTransaction(date, description, amount, "debit", category)), "history-test",
                Account.DEFAULT_ID);
        return jdbc.queryForObject("SELECT MAX(id) FROM transactions", new MapSqlParameterSource(), Long.class);
    }

    private Transaction get(long id) {
        return transactions.findById(id).orElseThrow();
    }

    private Entry latest() {
        return changeLog.recent(1).get(0);
    }

    private UndoResult undo(Entry e) {
        return changeLog.undo(e.id()).orElseThrow();
    }

    @Test
    @DisplayName("undoing a category change puts the old category back and unlearns what memory was taught")
    void undoCategoryChange() {
        long id = add("2026-05-01", "PAWS & CLAWS 0091", 40, "Shopping");

        transactionsApi.update(id, Map.of("category", "Healthcare"));
        assertThat(latest().summary()).isEqualTo("\"PAWS & CLAWS 0091\" → Healthcare");
        assertThat(memory.findCatchAll("PAWS & CLAWS")).isPresent();

        assertThat(undo(latest())).isEqualTo(new UndoResult(1, 0, 0));
        assertThat(get(id).category()).isEqualTo("Shopping");
        assertThat(get(id).categorySource()).isEqualTo("import");
        assertThat(memory.findCatchAll("PAWS & CLAWS")).isEmpty();   // there was no lesson before
        assertThat(latest().undoneAt()).isNotNull();
    }

    @Test
    @DisplayName("memory that held an earlier lesson gets that lesson back")
    void restoresEarlierMemory() {
        memory.remember("CORNER COFFEE", "Dining & Coffee", MerchantCategory.SOURCE_USER);
        long id = add("2026-05-01", "CORNER COFFEE", 4.5, "Dining & Coffee");

        transactionsApi.update(id, Map.of("category", "Groceries"));
        undo(latest());

        assertThat(memory.findCatchAll("CORNER COFFEE")).get()
                .extracting(MerchantCategory::category, MerchantCategory::source)
                .containsExactly("Dining & Coffee", "user");
    }

    @Test
    @DisplayName("a row changed again since is left alone, and counted as skipped")
    void skipsRowsChangedSince() {
        long a = add("2026-05-01", "SHOP ONE", 10, null);
        long b = add("2026-05-02", "SHOP TWO", 20, null);
        transactionsApi.bulkCategory(Map.of("ids", List.of(a, b), "category", "Shopping"));
        Entry bulk = latest();
        assertThat(bulk.summary()).isEqualTo("Categorized 2 transactions as Shopping");
        transactionsApi.update(b, Map.of("description", "SHOP TWO (renamed)"));

        assertThat(undo(bulk)).isEqualTo(new UndoResult(1, 1, 0));
        assertThat(get(a).category()).isNull();
        assertThat(get(b).category()).isEqualTo("Shopping");
        assertThat(get(b).description()).isEqualTo("SHOP TWO (renamed)");
    }

    @Test
    @DisplayName("undoing a delete brings rows back with their ids and tags; a lost receipt is reported")
    void undoDeletes() {
        long a = add("2026-05-01", "TRAIN TICKET", 60, "Travel");
        long b = add("2026-05-02", "HOTEL", 240, "Travel");
        tags.addTag(a, "work trip");
        receipts.upsert(b, "hotel.pdf", "application/pdf", new byte[] {1, 2, 3});

        transactionsApi.bulkDelete(Map.of("ids", List.of(a, b)));
        Entry deleted = latest();
        assertThat(deleted.summary()).isEqualTo("Deleted 2 transactions");
        assertThat(deleted.receiptsLost()).isEqualTo(1);

        assertThat(undo(deleted)).isEqualTo(new UndoResult(2, 0, 0));
        assertThat(get(a).description()).isEqualTo("TRAIN TICKET");
        assertThat(get(b).amount()).isEqualTo(240);
        assertThat(tags.namesFor(a)).containsExactly("work trip");
        assertThat(receipts.find(b)).isEmpty();

        long c = add("2026-05-03", "TAXI", 30, "Travel");
        transactionsApi.delete(c);
        assertThat(latest().summary()).isEqualTo("Deleted \"TAXI\"");
        undo(latest());
        assertThat(get(c).description()).isEqualTo("TAXI");
    }

    @Test
    @DisplayName("undoing an import removes what it added and nothing else")
    void undoImport() {
        long before = add("2026-04-01", "EARLIER", 5, null);
        uploads.upload(new MockMultipartFile("file", "april.csv", "text/csv",
                        "Date,Description,Amount\n2026-04-02,ONE,-1\n2026-04-03,TWO,-2\n".getBytes(StandardCharsets.UTF_8)),
                Account.DEFAULT_ID, true, null, null, null, null, null, null);
        Entry imported = latest();
        assertThat(imported.summary()).isEqualTo("Imported 2 transactions into Default from april.csv");

        assertThat(undo(imported)).isEqualTo(new UndoResult(0, 0, 2));
        assertThat(transactions.find(null, null, null, com.spendinganalyzer.dto.DateRange.ALL, 100, 0))
                .extracting(Transaction::id).containsExactly(before);
    }

    @Test
    @DisplayName("re-applying keyword rules is recorded and can be undone")
    void undoReapplyRules() {
        long id = add("2026-05-01", "CITY VET CLINIC", 80, null);
        transactions.updateCategory(id, "Other", "rule");
        keywordRules.create("VET", "Healthcare");

        categorize.reapplyRules(false);
        assertThat(get(id).category()).isEqualTo("Healthcare");
        assertThat(latest().summary()).isEqualTo("Re-applied keyword rules (1 changed)");

        undo(latest());
        assertThat(get(id).category()).isEqualTo("Other");
    }

    @Test
    @DisplayName("an edit that changes nothing isn't recorded; undoing twice is a 409, an unknown id a 404")
    void noOpsAndErrors() {
        memory.remember("BOOKS LTD", "Shopping", MerchantCategory.SOURCE_USER);
        long id = add("2026-05-01", "BOOKS LTD", 12, "Shopping");
        transactions.updateCategory(id, "Shopping", "user");
        int entries = changeLog.recent(200).size();

        transactionsApi.update(id, Map.of("category", "Shopping"));
        assertThat(changeLog.recent(200)).hasSize(entries);

        transactionsApi.update(id, Map.of("category", "Education"));
        long entryId = latest().id();
        assertThat(history.undo(entryId).getStatusCode().value()).isEqualTo(200);
        assertThat(history.undo(entryId).getStatusCode().value()).isEqualTo(409);
        assertThat(history.undo(999_999).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("each action's response carries its History entry's id, for an Undo beside it; a no-op carries none")
    void responsesCarryChangeId() {
        long a = add("2026-05-01", "SHOP ONE", 10, null);
        long b = add("2026-05-02", "SHOP TWO", 20, null);

        var bulk = (Map<String, Object>) transactionsApi.bulkCategory(Map.of("ids", List.of(a, b), "category", "Shopping")).getBody();
        assertThat(bulk.get("changeId")).isEqualTo(latest().id());

        var edit = (Map<String, Object>) transactionsApi.update(a, Map.of("category", "Groceries")).getBody();
        assertThat(edit.get("changeId")).isEqualTo(latest().id());
        var noop = (Map<String, Object>) transactionsApi.update(a, Map.of("category", "Groceries")).getBody();
        assertThat(noop.get("changeId")).isNull();

        var deleted = (Map<String, Object>) transactionsApi.bulkDelete(Map.of("ids", List.of(a))).getBody();
        assertThat(deleted.get("changeId")).isEqualTo(latest().id());
        var single = (Map<String, Object>) transactionsApi.delete(b).getBody();
        assertThat(single.get("changeId")).isEqualTo(latest().id());

        var upload = (com.spendinganalyzer.dto.UploadResponse) uploads.upload(new MockMultipartFile("file", "may.csv", "text/csv",
                        "Date,Description,Amount\n2026-05-09,NEW ROW,-3\n".getBytes(StandardCharsets.UTF_8)),
                Account.DEFAULT_ID, true, null, null, null, null, null, null).getBody();
        assertThat(upload.changeId()).isEqualTo(latest().id());
    }

    @Test
    @DisplayName("keeps only the newest entries; reset and backup restore clear the log")
    void retentionAndClearing() {
        for (int i = 0; i < ChangeLogService.KEEP + 5; i++) changeLog.recordImport("import " + i, "batch-" + i, 1);
        assertThat(changeLog.recent(ChangeLogService.KEEP + 50)).hasSize(ChangeLogService.KEEP)
                .first().extracting(Entry::summary).isEqualTo("import " + (ChangeLogService.KEEP + 4));

        backups.restore(backups.export());
        assertThat(changeLog.recent(10)).isEmpty();

        changeLog.recordImport("again", "batch-x", 1);
        transactionsApi.reset();
        assertThat(changeLog.recent(10)).isEmpty();
    }
}
