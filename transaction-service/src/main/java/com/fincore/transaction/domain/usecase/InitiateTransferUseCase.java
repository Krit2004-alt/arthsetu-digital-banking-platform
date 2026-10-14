package com.fincore.transaction.domain.usecase;

import com.fincore.transaction.domain.exception.DuplicateTransferException;
import com.fincore.transaction.domain.exception.TransferLockException;
import com.fincore.transaction.domain.model.Transfer;
import com.fincore.transaction.domain.model.Transfer.Currency;
import com.fincore.transaction.domain.port.AccountServiceClient;
import com.fincore.transaction.domain.port.DistributedLockPort;
import com.fincore.transaction.domain.port.IdempotencyPort;
import com.fincore.transaction.domain.port.TransferRepository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public class InitiateTransferUseCase {

    private final TransferRepository    transferRepository;
    private final AccountServiceClient  accountServiceClient;
    private final DistributedLockPort   distributedLock;
    private final IdempotencyPort       idempotency;

    public InitiateTransferUseCase(TransferRepository transferRepository,
                                   AccountServiceClient accountServiceClient,
                                   DistributedLockPort distributedLock,
                                   IdempotencyPort idempotency) {
        this.transferRepository  = transferRepository;
        this.accountServiceClient = accountServiceClient;
        this.distributedLock     = distributedLock;
        this.idempotency         = idempotency;
    }

    public InitiateTransferResult execute(UUID sourceAccountId, UUID targetAccountId,
                                          BigDecimal amount, Currency currency,
                                          String idempotencyKey) {
        return execute(sourceAccountId, targetAccountId, amount, currency, idempotencyKey, null);
    }

    public InitiateTransferResult execute(UUID sourceAccountId, UUID targetAccountId,
                                          BigDecimal amount, Currency currency,
                                          String idempotencyKey, String userId) {

        if (sourceAccountId.equals(targetAccountId)) {
            throw new IllegalArgumentException("Source and target accounts must be different");
        }

        // 1. Check if transfer was already recorded in DB with this idempotency key
        Optional<Transfer> existingTransfer = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existingTransfer.isPresent()) {
            Transfer existing = existingTransfer.get();
            validateMatchingParameters(existing, sourceAccountId, targetAccountId, amount, currency, idempotencyKey);
            return new InitiateTransferResult(existing, false);
        }

        // 2. Check if key is in Redis cache
        Optional<String> cachedTransferId = idempotency.get(idempotencyKey);
        if (cachedTransferId.isPresent()) {
            try {
                UUID transferId = UUID.fromString(cachedTransferId.get());
                Optional<Transfer> transferFromCache = transferRepository.findById(transferId);
                if (transferFromCache.isPresent()) {
                    Transfer existing = transferFromCache.get();
                    validateMatchingParameters(existing, sourceAccountId, targetAccountId, amount, currency, idempotencyKey);
                    return new InitiateTransferResult(existing, false);
                }
            } catch (IllegalArgumentException ignored) {
                // Not a UUID or invalid format, proceed with lock and DB check
            }
        }

        // 3. Acquire distributed lock on source account
        String lockKey = "lock:account:" + sourceAccountId;
        boolean locked = distributedLock.acquireLock(lockKey, 5, 10);

        if (!locked) {
            throw new TransferLockException(sourceAccountId.toString());
        }

        try {
            // Double check inside lock to prevent race condition
            Optional<Transfer> concurrentTransfer = transferRepository.findByIdempotencyKey(idempotencyKey);
            if (concurrentTransfer.isPresent()) {
                Transfer existing = concurrentTransfer.get();
                validateMatchingParameters(existing, sourceAccountId, targetAccountId, amount, currency, idempotencyKey);
                return new InitiateTransferResult(existing, false);
            }

            // Validate balance and caller ownership via HTTP sync call to Account Service
            accountServiceClient.validateBalance(sourceAccountId, amount, currency.name(), userId);

            // Create and persist transfer
            Transfer transfer = new Transfer(
                    sourceAccountId, targetAccountId,
                    amount, currency, idempotencyKey
            );

            Transfer saved = transferRepository.save(transfer);

            // Record idempotency key immediately
            idempotency.save(idempotencyKey, saved.getId().toString(), 24);

            return new InitiateTransferResult(saved, true);

        } finally {
            distributedLock.releaseLock(lockKey);
        }
    }

    private void validateMatchingParameters(Transfer existing, UUID sourceAccountId, UUID targetAccountId,
                                            BigDecimal amount, Currency currency, String idempotencyKey) {
        if (!existing.getSourceAccountId().equals(sourceAccountId)
                || !existing.getTargetAccountId().equals(targetAccountId)
                || existing.getAmount().compareTo(amount) != 0
                || existing.getCurrency() != currency) {
            throw new DuplicateTransferException("Idempotency key '" + idempotencyKey
                    + "' was already used for a transfer with different parameters");
        }
    }
}