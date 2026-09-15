package com.spendinganalyzer.dto;

/** Total net worth as of one date -- a point on the history chart. */
public record NetWorthPoint(String date, double total) {}
