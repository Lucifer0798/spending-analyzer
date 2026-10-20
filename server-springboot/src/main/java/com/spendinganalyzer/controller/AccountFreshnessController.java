package com.spendinganalyzer.controller;

import com.spendinganalyzer.service.AccountFreshnessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** How current each account's imported statements and logged balances are -- see {@link AccountFreshnessService}. */
@RestController
@RequestMapping("/api")
public class AccountFreshnessController {

    private final AccountFreshnessService service;

    public AccountFreshnessController(AccountFreshnessService service) {
        this.service = service;
    }

    @GetMapping("/accounts/freshness")
    public AccountFreshnessService.Freshness freshness() {
        return service.freshness();
    }
}
