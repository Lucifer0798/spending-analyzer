package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Currency;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AccountController {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public AccountController(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    public record AccountWithCount(
            long id, String name, String type, boolean archived,
            String created_at, String currency, int transactionCount
    ) {}

    @GetMapping("/accounts")
    public Map<String, Object> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        List<AccountWithCount> accounts = accountRepository.findAll(includeArchived).stream()
                .map(a -> new AccountWithCount(a.id(), a.name(), a.type(), a.archived(), a.createdAt(),
                        a.currency(), accountRepository.transactionCount(a.id())))
                .toList();
        return Map.of("accounts", accounts, "types", Account.TYPES, "currencies", Account.CURRENCIES);
    }

    @PostMapping("/accounts")
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        String name = body.get("name") == null ? "" : body.get("name").trim();
        String type = body.getOrDefault("type", "checking");
        String currency = body.getOrDefault("currency", "USD");

        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required."));
        }
        if (!Account.TYPES.contains(type)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("type must be one of: " + String.join(", ", Account.TYPES)));
        }
        if (!isValidCurrency(currency)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("currency must be a valid ISO 4217 code."));
        }
        if (accountRepository.nameExists(name, null)) {
            return ResponseEntity.status(409).body(new ErrorResponse("An account named '" + name + "' already exists."));
        }

        return ResponseEntity.ok(accountRepository.create(name, type, currency.toUpperCase()));
    }

    @PatchMapping("/accounts/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (accountRepository.findById(id).isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Account not found."));
        }

        String name = body.get("name") instanceof String s && !s.isBlank() ? s.trim() : null;
        String type = body.get("type") instanceof String s ? s : null;
        Boolean archived = body.get("archived") instanceof Boolean b ? b : null;
        String currency = body.get("currency") instanceof String s ? s : null;

        if (type != null && !Account.TYPES.contains(type)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("type must be one of: " + String.join(", ", Account.TYPES)));
        }
        if (currency != null && !isValidCurrency(currency)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("currency must be a valid ISO 4217 code."));
        }
        if (name != null && accountRepository.nameExists(name, id)) {
            return ResponseEntity.status(409).body(new ErrorResponse("An account named '" + name + "' already exists."));
        }

        accountRepository.update(id, name, type, archived, currency != null ? currency.toUpperCase() : null);
        return ResponseEntity.ok(accountRepository.findById(id).orElseThrow());
    }

    private static boolean isValidCurrency(String code) {
        try {
            Currency.getInstance(code.toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Deleting an account moves its transactions to the default account rather than
     * deleting them, so a mis-named account can be cleaned up without losing history.
     */
    @DeleteMapping("/accounts/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        if (id == Account.DEFAULT_ID) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("The default account cannot be deleted."));
        }
        if (accountRepository.findById(id).isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Account not found."));
        }

        int moved = accountRepository.transactionCount(id);
        transactionRepository.reassignAccount(id, Account.DEFAULT_ID);
        accountRepository.delete(id);

        return ResponseEntity.ok(Map.of("ok", true, "transactionsMovedToDefault", moved));
    }
}
