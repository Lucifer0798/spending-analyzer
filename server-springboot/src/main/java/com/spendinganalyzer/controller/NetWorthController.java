package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.NetWorthResponse;
import com.spendinganalyzer.service.NetWorthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The net worth view across every active account — logging or forgetting a balance lives on
 * {@code /api/accounts/{id}/balances} instead, the same split transaction tags follow between
 * the account-scoped write and the cross-account read.
 */
@RestController
@RequestMapping("/api/net-worth")
public class NetWorthController {

    private final NetWorthService netWorthService;

    public NetWorthController(NetWorthService netWorthService) {
        this.netWorthService = netWorthService;
    }

    @GetMapping
    public NetWorthResponse get() {
        return netWorthService.compute();
    }
}
