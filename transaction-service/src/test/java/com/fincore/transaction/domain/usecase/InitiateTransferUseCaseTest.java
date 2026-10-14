package com.fincore.transaction.domain.usecase;

import com.fincore.transaction.domain.exception.DuplicateTransferException;
import com.fincore.transaction.domain.exception.TransferLockException;
import com.fincore.transaction.domain.model.Transfer;
import com.fincore.transaction.domain.model.Transfer.Currency;
import com.fincore.transaction.domain.port.AccountServiceClient;
import com.fincore.transaction.domain.port.DistributedLockPort;
import com.fincore.transaction.domain.port.IdempotencyPort;
import com.fincore.transaction.domain.port.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InitiateTransferUseCaseTest {

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountServiceClient accountServiceClient;

    @Mock
    private DistributedLockPort distributedLock;

    @Mock
    private IdempotencyPort idempotency;

    private InitiateTransferUseCase initiateTransferUseCase;

    private final UUID sourceAccountId = UUID.randomUUID();
    private final UUID targetAccountId = UUID.randomUUID();
    private final BigDecimal amount = new BigDecimal("150.00");
    private final Currency currency = Currency.USD;
    private final String idempotencyKey = "key-test-12345";

    @BeforeEach
    void setUp() {
        initiateTransferUseCase = new InitiateTransferUseCase(
                transferRepository,
                accountServiceClient,
                distributedLock,
                idempotency
        );
    }

    @Test
    @DisplayName("Should successfully initiate new transfer when parameters are valid and lock acquired")
    void execute_WhenNewTransfer_ShouldAcquireLockValidateBalanceAndSave() {
        when(transferRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(idempotency.get(idempotencyKey)).thenReturn(Optional.empty());
        when(distributedLock.acquireLock(eq("lock:account:" + sourceAccountId), anyLong(), anyLong())).thenReturn(true);
        when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InitiateTransferResult result = initiateTransferUseCase.execute(
                sourceAccountId, targetAccountId, amount, currency, idempotencyKey
        );

        assertThat(result).isNotNull();
        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.transfer().getSourceAccountId()).isEqualTo(sourceAccountId);
        assertThat(result.transfer().getTargetAccountId()).isEqualTo(targetAccountId);
        assertThat(result.transfer().getAmount()).isEqualTo(amount);
        assertThat(result.transfer().getStatus()).isEqualTo(Transfer.TransferStatus.PENDING);

        verify(distributedLock).acquireLock(eq("lock:account:" + sourceAccountId), eq(5L), eq(10L));
        verify(accountServiceClient).validateBalance(eq(sourceAccountId), eq(amount), eq(currency.name()), isNull());
        verify(transferRepository).save(any(Transfer.class));
        verify(idempotency).save(eq(idempotencyKey), anyString(), eq(24L));
        verify(distributedLock).releaseLock(eq("lock:account:" + sourceAccountId));
    }

    @Test
    @DisplayName("Should return existing transfer idempotently without re-locking when identical key is presented")
    void execute_WhenDuplicateTransferWithIdenticalParams_ShouldReturnExistingTransfer() {
        Transfer existingTransfer = new Transfer(sourceAccountId, targetAccountId, amount, currency, idempotencyKey);
        when(transferRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingTransfer));

        InitiateTransferResult result = initiateTransferUseCase.execute(
                sourceAccountId, targetAccountId, amount, currency, idempotencyKey
        );

        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.transfer()).isSameAs(existingTransfer);

        verify(distributedLock, never()).acquireLock(anyString(), anyLong(), anyLong());
        verify(accountServiceClient, never()).validateBalance(any(), any(), any());
        verify(transferRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw DuplicateTransferException when same idempotency key is reused with differing amount")
    void execute_WhenDuplicateTransferWithDifferentAmount_ShouldThrowDuplicateTransferException() {
        Transfer existingTransfer = new Transfer(sourceAccountId, targetAccountId, new BigDecimal("200.00"), currency, idempotencyKey);
        when(transferRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingTransfer));

        assertThatThrownBy(() -> initiateTransferUseCase.execute(
                sourceAccountId, targetAccountId, amount, currency, idempotencyKey
        )).isInstanceOf(DuplicateTransferException.class)
          .hasMessageContaining("different parameters");

        verify(distributedLock, never()).acquireLock(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when source and target account IDs are identical")
    void execute_WhenSourceEqualsTarget_ShouldThrowIllegalArgumentException() {
        assertThatThrownBy(() -> initiateTransferUseCase.execute(
                sourceAccountId, sourceAccountId, amount, currency, idempotencyKey
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Source and target accounts must be different");

        verifyNoInteractions(distributedLock, accountServiceClient, transferRepository);
    }

    @Test
    @DisplayName("Should throw TransferLockException when distributed lock cannot be acquired")
    void execute_WhenLockUnavailable_ShouldThrowTransferLockException() {
        when(transferRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(idempotency.get(idempotencyKey)).thenReturn(Optional.empty());
        when(distributedLock.acquireLock(anyString(), anyLong(), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> initiateTransferUseCase.execute(
                sourceAccountId, targetAccountId, amount, currency, idempotencyKey
        )).isInstanceOf(TransferLockException.class);

        verify(accountServiceClient, never()).validateBalance(any(), any(), any());
        verify(transferRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should always release lock in finally block if balance validation throws exception")
    void execute_WhenBalanceValidationFails_ShouldReleaseLock() {
        when(transferRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(idempotency.get(idempotencyKey)).thenReturn(Optional.empty());
        when(distributedLock.acquireLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        doThrow(new RuntimeException("Account service error"))
                .when(accountServiceClient).validateBalance(any(), any(), any(), any());

        assertThatThrownBy(() -> initiateTransferUseCase.execute(
                sourceAccountId, targetAccountId, amount, currency, idempotencyKey
        )).isInstanceOf(RuntimeException.class);

        verify(distributedLock).releaseLock(eq("lock:account:" + sourceAccountId));
        verify(transferRepository, never()).save(any());
    }
}
