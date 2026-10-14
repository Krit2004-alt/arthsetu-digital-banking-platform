package com.fincore.account.infrastructure.web;

import com.fincore.account.application.service.AccountApplicationService;
import com.fincore.account.domain.model.Account.AccountType;
import com.fincore.account.domain.model.Account.Currency;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountApplicationService accountApplicationService;

    public AccountController(AccountApplicationService accountApplicationService) {
        this.accountApplicationService = accountApplicationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(
            @Valid @RequestBody CreateAccountRequest request,
            @RequestHeader("X-User-Id") String userId) {

        log.info("Create account request userId={} type={} currency={}",
                userId, request.type(), request.currency());

        return AccountResponse.fromDomain(
                accountApplicationService.createAccount(
                        UUID.fromString(userId),
                        request.type(),
                        request.currency()
                )
        );
    }

    @GetMapping
    public java.util.List<AccountResponse> getAccounts(
            @RequestHeader("X-User-Id") String userId) {

        log.info("Get accounts request for userId={}", userId);

        return accountApplicationService.getAccountsByUserId(UUID.fromString(userId))
                .stream()
                .map(AccountResponse::fromDomain)
                .toList();
    }

    @GetMapping("/{accountId}/balance")
    public AccountResponse getBalance(
            @PathVariable UUID accountId,
            @RequestHeader("X-User-Id") String userId) {

        log.info("Get balance request accountId={} userId={}", accountId, userId);

        return AccountResponse.fromDomain(
                accountApplicationService.getBalance(accountId, UUID.fromString(userId))
        );
    }

    /**
     * Lightweight ownership check called by Transaction Service before serving transfer history.
     * Returns 200 OK if {@code X-User-Id} owns the account; the global exception handler returns
     * 403 (UnauthorizedAccountAccessException) or 404 (AccountNotFoundException) otherwise.
     */
    @GetMapping("/{accountId}/verify-ownership")
    public ResponseEntity<Void> verifyOwnership(
            @PathVariable UUID accountId,
            @RequestHeader("X-User-Id") String userId) {

        log.info("Verify ownership request accountId={} userId={}", accountId, userId);
        accountApplicationService.verifyOwnership(accountId, UUID.fromString(userId));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{accountId}/validate-balance")
    public ResponseEntity<Void> validateBalance(
            @PathVariable UUID accountId,
            @RequestBody Map<String, Object> request) {

        BigDecimal amount = new BigDecimal(request.get("amount").toString());
        String currency   = request.get("currency").toString();
        UUID userId = request.containsKey("userId") && request.get("userId") != null
                ? UUID.fromString(request.get("userId").toString())
                : null;

        log.info("Validate balance request accountId={} amount={} currency={} userId={}",
                accountId, amount, currency, userId);

        accountApplicationService.validateBalance(accountId, amount, currency, userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{accountId}/credit")
    public ResponseEntity<Void> credit(
            @PathVariable UUID accountId,
            @RequestBody Map<String, Object> request) {

        BigDecimal amount = new BigDecimal(request.get("amount").toString());
        String currency   = request.get("currency").toString();

        log.info("Credit request accountId={} amount={} currency={}",
                accountId, amount, currency);

        accountApplicationService.credit(accountId, amount, currency);
        return ResponseEntity.ok().build();
    }
}