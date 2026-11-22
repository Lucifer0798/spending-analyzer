package com.spendinganalyzer.service;

import com.spendinganalyzer.controller.TransactionController;
import com.spendinganalyzer.dto.BackupData;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.ChangeLogService.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A free-text note of your own on any transaction: set, cleared, searched, undone, backed up. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionNotesTest {

    @Autowired private TransactionController api;
    @Autowired private TransactionRepository transactions;
    @Autowired private ChangeLogService changeLog;
    @Autowired private BackupService backups;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private long add(String description) {
        transactions.insertBatch(List.of(new ParsedTransaction("2026-05-01", description, 40, "debit", "Shopping")),
                "notes-test", Account.DEFAULT_ID);
        return jdbc.queryForObject("SELECT MAX(id) FROM transactions", new MapSqlParameterSource(), Long.class);
    }

    private String noteOf(long id) {
        return transactions.findById(id).orElseThrow().note();
    }

    private ResponseEntity<?> setNote(long id, Object note) {
        Map<String, Object> body = new HashMap<>();
        body.put("note", note);
        return api.update(id, body);
    }

    private Entry latest() {
        return changeLog.recent(1).get(0);
    }

    @Test
    @DisplayName("a note is trimmed and saved on its own; blank or null clears it; other fields are left alone")
    void setAndClear() {
        long id = add("SHOE SHOP");

        assertThat(setNote(id, "  returned the shoes  ").getStatusCode().value()).isEqualTo(200);
        assertThat(noteOf(id)).isEqualTo("returned the shoes");
        Transaction t = transactions.findById(id).orElseThrow();
        assertThat(t.category()).isEqualTo("Shopping");
        assertThat(t.splitNote()).isNull();

        setNote(id, "   ");
        assertThat(noteOf(id)).isNull();

        setNote(id, "Sam owes half");
        setNote(id, null);
        assertThat(noteOf(id)).isNull();
    }

    @Test
    @DisplayName("an edit that doesn't mention the note keeps it")
    void otherEditsKeepTheNote() {
        long id = add("SHOE SHOP");
        setNote(id, "returned the shoes");

        api.update(id, Map.of("category", "Gifts"));
        api.update(id, Map.of("split_share", 20, "split_note", "halves"));

        assertThat(noteOf(id)).isEqualTo("returned the shoes");
    }

    @Test
    @DisplayName("a note over the limit is a 400 and changes nothing; an unknown transaction is a 404")
    void validation() {
        long id = add("SHOE SHOP");
        setNote(id, "kept");

        ResponseEntity<?> tooLong = setNote(id, "x".repeat(TransactionController.MAX_NOTE_LENGTH + 1));
        assertThat(tooLong.getStatusCode().value()).isEqualTo(400);
        assertThat(noteOf(id)).isEqualTo("kept");

        assertThat(setNote(id, "x".repeat(TransactionController.MAX_NOTE_LENGTH)).getStatusCode().value()).isEqualTo(200);
        assertThat(setNote(id + 1000, "anything").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("the search box finds a transaction by its note, and counts it")
    void searchFindsNotes() {
        long shoes = add("SHOE SHOP");
        add("CORNER CAFE");
        setNote(shoes, "Returned -- refund pending");

        List<String> found = transactions.find(null, null, null, DateRange.ALL, null, "refund", 100, 0).stream()
                .map(Transaction::description).toList();
        assertThat(found).containsExactly("SHOE SHOP");
        assertThat(transactions.count(null, null, null, DateRange.ALL, null, "refund")).isEqualTo(1);
        // Descriptions still match as before.
        assertThat(transactions.count(null, null, null, DateRange.ALL, null, "cafe")).isEqualTo(1);
    }

    @Test
    @DisplayName("History records the note -- \"Noted\" or \"Removed the note on\" -- and Undo puts back what was there")
    void historyAndUndo() {
        long id = add("SHOE SHOP");

        setNote(id, "returned the shoes");
        Entry noted = latest();
        assertThat(noted.summary()).isEqualTo("Noted \"SHOE SHOP\"");

        setNote(id, "refund arrived");
        Entry changed = latest();
        assertThat(changed.id()).isNotEqualTo(noted.id());

        setNote(id, "");
        Entry removed = latest();
        assertThat(removed.summary()).isEqualTo("Removed the note on \"SHOE SHOP\"");

        changeLog.undo(removed.id()).orElseThrow();
        assertThat(noteOf(id)).isEqualTo("refund arrived");
        changeLog.undo(changed.id()).orElseThrow();
        assertThat(noteOf(id)).isEqualTo("returned the shoes");
        changeLog.undo(noted.id()).orElseThrow();
        assertThat(noteOf(id)).isNull();
    }

    @Test
    @DisplayName("undoing a delete brings the transaction back with its note")
    void undoDeleteKeepsNote() {
        long id = add("SHOE SHOP");
        setNote(id, "returned the shoes");

        api.delete(id);
        assertThat(transactions.findById(id)).isEmpty();
        changeLog.undo(latest().id()).orElseThrow();

        assertThat(noteOf(id)).isEqualTo("returned the shoes");
    }

    @Test
    @DisplayName("setting the same note again isn't recorded as a change")
    void sameNoteNotRecorded() {
        long id = add("SHOE SHOP");
        setNote(id, "returned the shoes");
        long before = latest().id();

        ResponseEntity<?> again = setNote(id, "returned the shoes");

        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(latest().id()).isEqualTo(before);
    }

    @Test
    @DisplayName("notes survive a backup and restore; a backup from before notes restores with none")
    void backupRoundTrip() {
        long id = add("SHOE SHOP");
        setNote(id, "returned the shoes");
        BackupData snapshot = backups.export();
        assertThat(snapshot.transactions()).filteredOn(t -> t.id() == id).singleElement()
                .extracting(Transaction::note).isEqualTo("returned the shoes");

        setNote(id, "changed after the backup");
        backups.restore(snapshot);
        assertThat(noteOf(id)).isEqualTo("returned the shoes");

        // An older backup's transactions simply have no "note" field.
        Transaction saved = snapshot.transactions().stream().filter(t -> t.id() == id).findFirst().orElseThrow();
        ObjectNode old = json.valueToTree(saved);
        assertThat(old.has("note")).isTrue();
        old.remove("note");
        Transaction fromOldBackup = json.treeToValue(old, Transaction.class);
        assertThat(fromOldBackup.note()).isNull();
        assertThat(fromOldBackup.description()).isEqualTo("SHOE SHOP");
    }
}
