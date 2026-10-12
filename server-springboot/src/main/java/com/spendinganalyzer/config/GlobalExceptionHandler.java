package com.spendinganalyzer.config;

import com.spendinganalyzer.dto.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(new ErrorResponse("File too large. Maximum size is 20MB."));
    }

    /**
     * Bad request parameters — a malformed or backwards date range, for instance. These are
     * the caller's mistake, so they get a 400 with the reason rather than being swallowed by
     * the catch-all below and reported as a server fault.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }

    /**
     * Anything else is a server fault -- except Spring's own exceptions that already carry an HTTP
     * status (a missing static file is a 404, a wrong method a 405, a missing parameter a 400).
     * Catching {@code Exception} would otherwise turn every one of those into a 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse withStatus) {
            String detail = withStatus.getBody().getDetail();
            return ResponseEntity.status(withStatus.getStatusCode())
                    .body(new ErrorResponse(detail != null ? detail : e.getMessage()));
        }
        return ResponseEntity.status(500).body(new ErrorResponse("Unexpected error: " + e.getMessage()));
    }
}
