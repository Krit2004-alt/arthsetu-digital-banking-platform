package com.fincore.transaction.application.service;

import com.fincore.transaction.domain.event.TransferCancelledEvent;
import com.fincore.transaction.domain.event.TransferCompletedEvent;
import com.fincore.transaction.domain.event.TransferInitiatedEvent;
import com.fincore.transaction.domain.exception.UnauthorizedTransferAccessException;
import com.fincore.transaction.domain.model.Transfer;
import com.fincore.transaction.domain.model.Transfer.Currency;
import com.fincore.transaction.domain.port.AccountServiceClient;
import com.fincore.transaction.domain.port.IdempotencyPort;
import com.fincore.transaction.domain.port.TransferEventPublisher;
import com.fincore.transaction.domain.port.TransferRepository;
import com.fincore.transaction.domain.usecase.InitiateTransferResult;
import com.fincore.transaction.domain.usecase.InitiateTransferUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TransferApplicationService.class);

    private final InitiateTransferUseCase initiateTransferUseCase;
    private final TransferRepository      transferRepository;
    private final TransferEventPublisher  eventPublisher;
    private final AccountServiceClient    accountServiceClient;
    private final IdempotencyPort         idempotency;

    public TransferApplicationService(
            InitiateTransferUseCase initiateTransferUseCase,
            TransferRepository transferRepository,
            TransferEventPublisher eventPublisher,
            AccountServiceClient accountServiceClient,
            IdempotencyPort idempotency) {
        this.initiateTransferUseCase = initiateTransferUseCase;
        this.transferRepository      = transferRepository;
        this.eventPublisher          = eventPublisher;
        this.accountServiceClient    = accountServiceClient;
        this.idempotency             = idempotency;
    }

    @Transactional
    public Transfer initiateTransfer(UUID sourceAccountId, UUID targetAccountId,
                                     BigDecimal amount, Currency currency,
                                     String idempotencyKey) {
        return initiateTransfer(sourceAccountId, targetAccountId, amount, currency, idempotencyKey, null);
    }

    @Transactional
    public Transfer initiateTransfer(UUID sourceAccountId, UUID targetAccountId,
                                     BigDecimal amount, Currency currency,
                                     String idempotencyKey, String userId) {

        InitiateTransferResult result = (userId == null)
                ? initiateTransferUseCase.execute(sourceAccountId, targetAccountId, amount, currency, idempotencyKey)
                : initiateTransferUseCase.execute(sourceAccountId, targetAccountId, amount, currency, idempotencyKey, userId);

        Transfer transfer = result.transfer();

        if (result.newlyCreated()) {
            eventPublisher.publish(
                    "fincore.transfer.initiated",
                    new TransferInitiatedEvent(
                            transfer.getId(),
                            transfer.getSourceAccountId(),
                            transfer.getTargetAccountId(),
                            transfer.getAmount(),
                            transfer.getCurrency().name()
                    )
            );
            log.info("New transfer initiated transferId={}", transfer.getId());
        } else {
            log.info("Idempotent replay for transferId={} status={}", transfer.getId(), transfer.getStatus());
        }

        return transfer;
    }

    @Transactional(readOnly = true)
    public Transfer getTransferById(UUID transferId, String userId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new com.fincore.transaction.domain.exception.TransferNotFoundException(transferId.toString()));

        // User must own the source OR the target account.
        boolean authorized = false;
        try {
            accountServiceClient.verifyOwnership(transfer.getSourceAccountId(), userId);
            authorized = true;
        } catch (feign.FeignException.Forbidden ignored) {
            // not the source-account owner; try target
        }
        if (!authorized) {
            try {
                accountServiceClient.verifyOwnership(transfer.getTargetAccountId(), userId);
            } catch (feign.FeignException.Forbidden e) {
                throw new UnauthorizedTransferAccessException(transferId.toString());
            }
        }
        return transfer;
    }

    @Transactional(readOnly = true)
    public List<Transfer> getTransfersByAccountId(UUID accountId, String userId) {
        try {
            accountServiceClient.verifyOwnership(accountId, userId);
        } catch (feign.FeignException.Forbidden e) {
            throw new UnauthorizedTransferAccessException(accountId.toString());
        } catch (feign.FeignException.NotFound e) {
            throw new com.fincore.transaction.domain.exception.TransferNotFoundException(accountId.toString());
        }
        return transferRepository.findByAccountId(accountId);
    }

    // Called when Account Service confirms debit via Kafka
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleAccountDebited(UUID transferId) {
        transferRepository.findById(transferId)
                .ifPresent(transfer -> {
                    if (transfer.getStatus() != Transfer.TransferStatus.PENDING) {
                        log.warn("Ignoring duplicate or terminal account debited event for transferId={} currentStatus={}",
                                transferId, transfer.getStatus());
                        return;
                    }
                    try {
                        accountServiceClient.credit(
                                transfer.getTargetAccountId(),
                                transfer.getAmount(),
                                transfer.getCurrency().name()
                        );
                        transferRepository.updateStatus(transferId, Transfer.TransferStatus.COMPLETED);
                        idempotency.save(transfer.getIdempotencyKey(), transferId.toString(), 24);
                        eventPublisher.publish(
                                "fincore.transfer.completed",
                                new TransferCompletedEvent(
                                        transfer.getId(),
                                        transfer.getSourceAccountId(),
                                        transfer.getTargetAccountId(),
                                        transfer.getAmount()
                                )
                        );
                        log.info("Transfer completed transferId={}", transferId);
                    } catch (Exception e) {
                        log.error("Failed to credit target account for transferId={}. Initiating compensating refund. Error: {}",
                                transferId, e.getMessage());
                        // Saga compensating transaction: refund the source account
                        try {
                            accountServiceClient.credit(
                                    transfer.getSourceAccountId(),
                                    transfer.getAmount(),
                                    transfer.getCurrency().name()
                            );
                            String reason = "Target credit failed: " + e.getMessage() + ". Debited funds were refunded.";
                            transferRepository.updateStatusWithReason(transferId, Transfer.TransferStatus.FAILED, reason);
                            eventPublisher.publish(
                                    "fincore.transfer.cancelled",
                                    new TransferCancelledEvent(transfer.getId(), reason)
                            );
                            log.warn("Compensating refund completed successfully for transferId={}", transferId);
                        } catch (Exception compEx) {
                            log.error("CRITICAL: Failed compensating refund for transferId={}. Error: {}",
                                    transferId, compEx.getMessage());
                            transferRepository.updateStatusWithReason(transferId, Transfer.TransferStatus.FAILED,
                                    "Target credit and compensation failed: " + compEx.getMessage());
                        }
                    }
                });
    }

    // Called when Account Service reports debit failure via Kafka
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleAccountDebitFailed(UUID transferId, String reason) {
        transferRepository.findById(transferId)
                .ifPresent(transfer -> {
                    if (transfer.getStatus() != Transfer.TransferStatus.PENDING) {
                        log.warn("Ignoring duplicate or terminal account debit failed event for transferId={} currentStatus={}",
                                transferId, transfer.getStatus());
                        return;
                    }
                    transferRepository.updateStatusWithReason(transferId, Transfer.TransferStatus.CANCELLED, reason);
                    eventPublisher.publish(
                            "fincore.transfer.cancelled",
                            new TransferCancelledEvent(transfer.getId(), reason)
                    );
                    log.info("Transfer cancelled transferId={} reason={}", transferId, reason);
                });
    }
}