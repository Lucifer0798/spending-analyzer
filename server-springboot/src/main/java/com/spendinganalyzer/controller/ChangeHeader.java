package com.spendinganalyzer.controller;

import org.springframework.http.ResponseEntity;

/**
 * The History entry an action wrote, sent back as {@value #NAME} so the page can offer Undo beside
 * the action -- a header rather than a body field, so each endpoint's response stays exactly what it
 * was (several return the saved entity itself). No header when the action changed nothing.
 */
final class ChangeHeader {

    static final String NAME = "X-Change-Id";

    private ChangeHeader() {}

    static ResponseEntity<Object> ok(Object body, Long changeId) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (changeId != null) response.header(NAME, changeId.toString());
        return response.body(body);
    }
}
