package com.fincore.account.application.service;

import com.fincore.account.domain.event.AccountCreatedEvent;
import com.fincore.account.domain.exception.AccountNotFoundException;
import com.fincore.account.domain.exception.UnauthorizedAccountAccessException;
import com.fincore.account.domain.model.Account;
import com.fincore.account.domain.model.Account.AccountType;
import com.fincore.account.domain.model.Account.Currency;
import com.fincore.account.domain.port.AccountEventPublisher;
import com.fincore.account.domain.port.AccountRepository;
import com.fincore.account.domain.usecase.CreateAccountUseCase;
import com.fincore.account.domain.usecase.GetBalanceUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountApplicationServiceTest {

    @Mock
    private CreateAccountUseCase createAccountUseCase;

    @Mock
    private GetBalanceUseCase getBalanceUseCase;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountEventPublisher eventPublisher;

    private AccountApplicationService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AccountApplicationService(
                createAccountUseCase,
                getBalanceUseCase,
                accountRepository,
                eventPublisher
        );
    }

    @Test
    @DisplayName("Should create account, save to DB, and publish AccountCreatedEvent")
    void createAccount_ShouldSaveAndPublish() {
        Account newAccount = new Account("ACC-1234567890", userId, AccountType.SAVINGS, Currency.PEN);
        when(createAccountUseCase.execute(userId, AccountType.SAVINGS, Currency.PEN)).thenReturn(newAccount);
        when(accountRepository.save(newAccount)).thenReturn(newAccount);

        Account result = service.createAccount(userId, AccountType.SAVINGS, Currency.PEN);

        assertThat(result).isSameAs(newAccount);
        verify(accountRepository).save(newAccount);
        verify(eventPublisher).publish(eq("fincore.account.created"), any(AccountCreatedEvent.class));
    }

    @Test
    @DisplayName("Should retrieve account list by userId")
    void getAccountsByUserId_ShouldReturnList() {
        Account a1 = new Account("ACC-1", userId, AccountType.SAVINGS, Currency.PEN);
        Account a2 = new Account("ACC-2", userId, AccountType.CHECKING, Currency.USD);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(a1, a2));

        List<Account> result = service.getAccountsByUserId(userId);

        assertThat(result).hasSize(2);
        assertThat(result).containsExactly(a1, a2);
    }

    @Test
    @DisplayName("Should get balance via GetBalanceUseCase when ownership matches")
    void getBalance_ShouldDelegateToUseCase() {
        Account account = new Account("ACC-1", userId, AccountType.SAVINGS, Currency.PEN);
        when(getBalanceUseCase.execute(accountId, userId)).thenReturn(account);

        Account result = service.getBalance(accountId, userId);

        assertThat(result).isSameAs(account);
        verify(getBalanceUseCase).execute(accountId, userId);
    }

    @Test
    @DisplayName("Should credit account balance successfully")
    void credit_WhenAccountExists_ShouldUpdateBalance() {
        when(accountRepository.creditBalance(accountId, new BigDecimal("100.00"))).thenReturn(1);

        service.credit(accountId, new BigDecimal("100.00"), "PEN");

        verify(accountRepository).creditBalance(accountId, new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("Should throw AccountNotFoundException when crediting non-existent account")
    void credit_WhenAccountNotFound_ShouldThrowException() {
        when(accountRepository.creditBalance(accountId, new BigDecimal("100.00"))).thenReturn(0);

        assertThatThrownBy(() -> service.credit(accountId, new BigDecimal("100.00"), "PEN"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("Should publish fincore.account.debited event when debit succeeds")
    void debitForTransfer_WhenDebitSucceeds_ShouldPublishDebitedEvent() {
        UUID transferId = UUID.randomUUID();
        when(accountRepository.debitBalance(accountId, new BigDecimal("50.00"))).thenReturn(1);

        service.debitForTransfer(accountId, transferId, new BigDecimal("50.00"), "PEN");

        verify(eventPublisher).publish(eq("fincore.account.debited"), any(Map.class));
    }

    @Test
    @DisplayName("Should publish fincore.account.debit.failed event when debit fails (e.g. insufficient funds)")
    void debitForTransfer_WhenDebitFails_ShouldPublishDebitFailedEvent() {
        UUID transferId = UUID.randomUUID();
        when(accountRepository.debitBalance(accountId, new BigDecimal("50.00"))).thenReturn(0);

        service.debitForTransfer(accountId, transferId, new BigDecimal("50.00"), "PEN");

        verify(eventPublisher).publish(eq("fincore.account.debit.failed"), any(Map.class));
    }
}
