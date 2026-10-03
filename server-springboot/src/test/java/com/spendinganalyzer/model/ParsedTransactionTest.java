package com.spendinganalyzer.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParsedTransactionTest {

    @Test
    @DisplayName("the dedupe key joins normalised fields with a NUL, which no field can contain")
    void dedupeKeyIsNulSeparated() {
        var t = new ParsedTransaction("2026-05-01", "  Coffee Shop ", 4.5, "debit", "Dining");

        // Pinned exactly: keys are compared between an upload and rows already stored, so any
        // change here must apply to both sides at once -- which it does, since both call this.
        String nul = String.valueOf((char) 0);
        assertThat(t.dedupeKey()).isEqualTo("2026-05-01" + nul + "coffee shop" + nul + "4.50" + nul + "debit");
    }
}
