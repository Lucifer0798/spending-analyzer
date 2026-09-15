package com.spendinganalyzer.dto;

/** One account's most recently logged balance. */
public record NetWorthAccount(long accountId, String accountName, double balance, String asOfDate) {}
