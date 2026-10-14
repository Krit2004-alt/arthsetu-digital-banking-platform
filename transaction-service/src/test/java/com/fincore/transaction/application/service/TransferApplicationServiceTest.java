package com.fincore.transaction.application.service;

import com.fincore.transaction.domain.event.TransferCancelledEvent;
import com.fincore.transaction.domain.event.TransferCompletedEvent;
import com.fincore.transaction.domain.event.TransferInitiatedEvent;
import com.fincore.transaction.domain.model.Transfer;
import com.fincore.transaction.domain.model.Transfer.Currency;
import com.fincore.transaction.domain.port.AccountServiceClient;
import com.fincore.transaction.domain.port.IdempotencyPort;
import com.fincore.transaction.domain.port.TransferEventPublisher;
import com.fincore.transaction.domain.port.TransferRepository;
import com.fincore.transaction.domain.usecase.InitiateTransferResult;
import com.fincore.transaction.domain.usecase.InitiateTransferUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferApplicationServiceTest {

    @Mock
    private InitiateTransferUseCase initiateTransferUseCase;

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private TransferEventPublisher eventPublisher;

    @Mock
    private AccountServiceClient accountServiceClient;

    @Mock
    private IdempotencyPort idempotency;

    private TransferApplicationService service;

    private final UUID sourceAccountId = UUID.randomUUID();
    private final UUID targetAccountId = UUID.randomUUID();
    private final BigDecimal amount = new BigDecimal("250.00");
    private final Currency currency = Currency.PEN;
    private final String idempotencyKey = "key-saga-999";

    @BeforeEach
    void setUp() {
        service = new TransferApplicationService(
                initiateTransferUseCase,
                transferRepository,
                eventPublisher,
                accountServiceClient,
                idempotency
        );
    }

    @Test
    @DisplayName("Should publish TransferInitiatedEvent when a new transfer is initiated")
    void initiateTransfer_WhenNewTransfer_ShouldPublishEvent() {
        Transfer transfer = new Transfer(sourceAccountId, targetAccountId, amount, currency, idempotencyKey);
        when(initiateTransferUseCase.execute(sourceAccountId, targetAccountId, amount, currency, idempotencyKey))
                .thenReturn(new InitiateTransferResult(transfer, true));

        Transfer result = service.initiateTransfer(sourceAccountId, targetAccountId, amount, currency, idempotencyKey);

        assertThat(result).isSameAs(transfer);
        verify(eventPublisher).publish(eq("fincore.transfer.initiated"), any(TransferInitiatedEvent.class));
    }

    @Test
    @DisplayName("Should NOT publish TransferInitiatedEvent when request is an idempotent replay")
    void initiateTransfer_WhenIdempotentReplay_ShouldNotPublishDuplicateEvent() {
        Transfer transfer = new Transfer(sourceAccountId, targetAccountId, amount, currency, idempotencyKey);
        when(initiateTransferUseCase.execute(sourceAccountId, targetAccountId, amount, currency, idempotencyKey))
                .thenReturn(new InitiateTransferResult(transfer, false));

        Transfer result = service.initiateTransfer(sourceAccountId, targetAccountId, amount, currency, idempotencyKey);

        assertThat(result).isSameAs(transfer);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("Should credit target account and mark COMPLETED when account debit succeeds")
    void handleAccountDebited_WhenCreditSucceeds_ShouldCompleteTransfer() {
        UUID transferId = UUID.randomUUID();
        Transfer transfer = new Transfer(transferId, sourceAccountId, targetAccountId, amount, currency,
                Transfer.TransferStatus.PENDING, null, idempotencyKey, java.time.LocalDateTime.now());

        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));

        service.handleAccountDebited(transferId);

        verify(accountServiceClient).credit(targetAccountId, amount, currency.name());
        verify(transferRepository).updateStatus(transferId, Transfer.TransferStatus.COMPLETED);
        verify(idempotency).save(idempotencyKey, transferId.toString(), 24L);
        verify(eventPublisher).publish(eq("fincore.transfer.completed"), any(TransferCompletedEvent.class));
    }

    @Test
    @DisplayName("Should execute compensating refund on source account when target credit fails in Saga")
    void handleAccountDebited_WhenCreditFails_ShouldCompensateRefund() {
        UUID transferId = UUID.randomUUID();
        Transfer transfer = new Transfer(transferId, sourceAccountId, targetAccountId, amount, currency,
                Transfer.TransferStatus.PENDING, null, idempotencyKey, java.time.LocalDateTime.now());

        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        doThrow(new RuntimeException("Target account inactive or unreachable"))
                .when(accountServiceClient).credit(targetAccountId, amount, currency.name());

        service.handleAccountDebited(transferId);

        // Verify compensation: credited back to SOURCE account
        verify(accountServiceClient).credit(sourceAccountId, amount, currency.name());
        verify(transferRepository).updateStatusWithReason(eq(transferId), eq(Transfer.TransferStatus.FAILED),
                contains("refunded"));
        verify(eventPublisher).publish(eq("fincore.transfer.cancelled"), any(TransferCancelledEvent.class));
    }

    @Test
    @DisplayName("Should mark transfer CANCELLED with reason when Account Service reports debit failure")
    void handleAccountDebitFailed_ShouldMarkCancelledWithReason() {
        UUID transferId = UUID.randomUUID();
        Transfer transfer = new Transfer(transferId, sourceAccountId, targetAccountId, amount, currency,
                Transfer.TransferStatus.PENDING, null, idempotencyKey, java.time.LocalDateTime.now());

        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));

        service.handleAccountDebitFailed(transferId, "Insufficient funds");

        verify(transferRepository).updateStatusWithReason(transferId, Transfer.TransferStatus.CANCELLED, "Insufficient funds");
        verify(eventPublisher).publish(eq("fincore.transfer.cancelled"), any(TransferCancelledEvent.class));
    }

    @Test
    @DisplayName("Should return transfer by ID when user owns the source account")
    void getTransferById_WhenUserOwnsSourceAccount_ShouldReturnTransfer() {
        UUID transferId = UUID.randomUUID();
        Transfer transfer = new Transfer(transferId, sourceAccountId, targetAccountId, amount, currency,
                Transfer.TransferStatus.COMPLETED, null, idempotencyKey, java.time.LocalDateTime.now());

        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));
        doNothing().when(accountServiceClient).verifyOwnership(sourceAccountId, idempotencyKey);

        Transfer result = service.getTransferById(transferId, idempotencyKey);

        assertThat(result.getId()).isEqualTo(transferId);
        verify(accountServiceClient).verifyOwnership(sourceAccountId, idempotencyKey);
    }

    @Test
    @DisplayName("Should return list of transfers for an account when user owns it")
    void getTransfersByAccountId_WhenUserOwnsAccount_ShouldReturnTransfers() {
        Transfer t1 = new Transfer(sourceAccountId, targetAccountId, amount, currency, "key-1");
        Transfer t2 = new Transfer(targetAccountId, sourceAccountId, new BigDecimal("50.00"), currency, "key-2");

        doNothing().when(accountServiceClient).verifyOwnership(sourceAccountId, idempotencyKey);
        when(transferRepository.findByAccountId(sourceAccountId)).thenReturn(List.of(t1, t2));

        List<Transfer> results = service.getTransfersByAccountId(sourceAccountId, idempotencyKey);

        assertThat(results).hasSize(2);
        assertThat(results).containsExactly(t1, t2);
    }

    @Test
    @DisplayName("Should throw UnauthorizedTransferAccessException when user does not own account")
    void getTransfersByAccountId_WhenUserDoesNotOwnAccount_ShouldThrowUnauthorized() {
        UUID otherAccountId = UUID.randomUUID();
        doThrow(new feign.FeignException.Forbidden("Forbidden", feign.Request.create(
                feign.Request.HttpMethod.GET, "/verify-ownership",
                java.util.Collections.emptyMap(), null, null, null),
                new byte[0], java.util.Collections.emptyMap()))
                .when(accountServiceClient).verifyOwnership(otherAccountId, idempotencyKey);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.getTransfersByAccountId(otherAccountId, idempotencyKey))
                .isInstanceOf(com.fincore.transaction.domain.exception.UnauthorizedTransferAccessException.class)
                .hasMessageContaining("transfer history for account");

        verify(transferRepository, never()).findByAccountId(any());
    }

    @Test
    @DisplayName("Should throw UnauthorizedTransferAccessException when user owns neither account on the transfer")
    void getTransferById_WhenUserOwnsNeitherAccount_ShouldThrowUnauthorized() {
        UUID transferId = UUID.randomUUID();
        Transfer transfer = new Transfer(transferId, sourceAccountId, targetAccountId, amount, currency,
                Transfer.TransferStatus.COMPLETED, null, idempotencyKey, java.time.LocalDateTime.now());

        when(transferRepository.findById(transferId)).thenReturn(Optional.of(transfer));

        feign.FeignException.Forbidden forbidden = new feign.FeignException.Forbidden("Forbidden",
                feign.Request.create(feign.Request.HttpMethod.GET, "/verify-ownership",
                        java.util.Collections.emptyMap(), null, null, null),
                new byte[0], java.util.Collections.emptyMap());
        doThrow(forbidden).when(accountServiceClient).verifyOwnership(sourceAccountId, idempotencyKey);
        doThrow(forbidden).when(accountServiceClient).verifyOwnership(targetAccountId, idempotencyKey);

        assertThatThrownBy(() -> service.getTransferById(transferId, idempotencyKey))
                .isInstanceOf(com.fincore.transaction.domain.exception.UnauthorizedTransferAccessException.class)
                .hasMessageContaining("transfer history for account");
    }
}
