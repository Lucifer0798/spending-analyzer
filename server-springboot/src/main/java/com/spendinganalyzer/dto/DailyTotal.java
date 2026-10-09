package com.spendinganalyzer.dto;

/** Spend on one calendar day: the total, and how many transactions made it up. */
public record DailyTotal(String date, double total, int count) {}
