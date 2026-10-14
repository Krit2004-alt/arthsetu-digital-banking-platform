package com.fincore.transaction.infrastructure.config;

import com.fincore.transaction.domain.exception.InsufficientFundsException;
import com.fincore.transaction.domain.exception.TransferNotFoundException;
import com.fincore.transaction.domain.exception.UnauthorizedTransferAccessException;
import com.fincore.transaction.domain.port.AccountServiceClient;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class AccountServiceClientImpl implements AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClientImpl.class);

    private final AccountServiceFeignClient feignClient;

    public AccountServiceClientImpl(AccountServiceFeignClient feignClient) {
        this.feignClient = feignClient;
    }

    @Override
    public void validateBalance(UUID accountId, BigDecimal amount, String currency) {
        validateBalance(accountId, amount, currency, null);
    }

    @Override
    @CircuitBreaker(name = "account-service", fallbackMethod = "validateBalanceFallback")
    public void validateBalance(UUID accountId, BigDecimal amount, String currency, String userId) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount);
        body.put("currency", currency);
        if (userId != null) {
            body.put("userId", userId);
        }
        feignClient.validateBalance(accountId, body);
    }

    @Override
    @CircuitBreaker(name = "account-service", fallbackMethod = "verifyOwnershipFallback")
    public void verifyOwnership(UUID accountId, String userId) {
        feignClient.verifyOwnership(accountId, userId);
    }

    @Override
    @CircuitBreaker(name = "account-service", fallbackMethod = "creditFallback")
    public void credit(UUID accountId, BigDecimal amount, String currency) {
        feignClient.credit(accountId, Map.of(
                "amount", amount,
                "currency", currency
        ));
    }

    public void validateBalanceFallback(UUID accountId, BigDecimal amount,
                                        String currency, String userId, Throwable ex) {
        if (ex instanceof FeignException.UnprocessableEntity || (ex.getMessage() != null && ex.getMessage().contains("INSUFFICIENT_FUNDS"))) {
            throw new InsufficientFundsException();
        }
        if (ex instanceof FeignException.Forbidden) {
            throw new UnauthorizedTransferAccessException("You do not have access to account: " + accountId);
        }
        if (ex instanceof FeignException.NotFound) {
            throw new TransferNotFoundException("Account not found: " + accountId);
        }
        log.error("Circuit breaker open for validateBalance accountId={} error={}",
                accountId, ex.getMessage());
        throw new RuntimeException("Account service unavailable");
    }

    public void verifyOwnershipFallback(UUID accountId, String userId, Throwable ex) {
        if (ex instanceof FeignException.Forbidden) {
            throw new UnauthorizedTransferAccessException("You do not have access to transfer history for account: " + accountId);
        }
        if (ex instanceof FeignException.NotFound) {
            throw new TransferNotFoundException("Account not found with id: " + accountId);
        }
        log.error("Circuit breaker open for verifyOwnership accountId={} error={}",
                accountId, ex.getMessage());
        throw new RuntimeException("Account service unavailable");
    }

    public void creditFallback(UUID accountId, BigDecimal amount,
                               String currency, Throwable ex) {
        log.error("Circuit breaker open for credit accountId={} error={}",
                accountId, ex.getMessage());
        throw new RuntimeException("Account service unavailable");
    }
}