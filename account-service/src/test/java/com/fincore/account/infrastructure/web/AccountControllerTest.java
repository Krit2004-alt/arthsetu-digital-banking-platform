package com.fincore.account.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.account.application.service.AccountApplicationService;
import com.fincore.account.domain.model.Account;
import com.fincore.account.domain.model.Account.AccountType;
import com.fincore.account.domain.model.Account.Currency;
import com.fincore.account.infrastructure.web.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AccountApplicationService accountApplicationService;

    @InjectMocks
    private AccountController accountController;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(accountController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/accounts should create account and return 201 CREATED")
    void createAccount_ShouldReturn201() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest(AccountType.SAVINGS, Currency.PEN);
        Account account = new Account(accountId, "ACC-00000000001", userId, AccountType.SAVINGS, Currency.PEN,
                BigDecimal.ZERO, Account.AccountStatus.ACTIVE, java.time.LocalDateTime.now());

        when(accountApplicationService.createAccount(eq(userId), eq(AccountType.SAVINGS), eq(Currency.PEN)))
                .thenReturn(account);

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", userId.toString())
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(accountId.toString()))
                .andExpect(jsonPath("$.accountNumber").value("ACC-00000000001"))
                .andExpect(jsonPath("$.type").value("SAVINGS"))
                .andExpect(jsonPath("$.currency").value("PEN"));
    }

    @Test
    @DisplayName("GET /api/v1/accounts should return list of accounts for authenticated user")
    void getAccounts_ShouldReturnAccountsList() throws Exception {
        Account account = new Account(accountId, "ACC-00000000001", userId, AccountType.SAVINGS, Currency.PEN,
                BigDecimal.ZERO, Account.AccountStatus.ACTIVE, java.time.LocalDateTime.now());

        when(accountApplicationService.getAccountsByUserId(eq(userId)))
                .thenReturn(List.of(account));

        mockMvc.perform(get("/api/v1/accounts")
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(accountId.toString()))
                .andExpect(jsonPath("$[0].accountNumber").value("ACC-00000000001"));
    }

    @Test
    @DisplayName("GET /api/v1/accounts/{accountId}/balance should return balance for account")
    void getBalance_ShouldReturnBalance() throws Exception {
        Account account = new Account(accountId, "ACC-00000000001", userId, AccountType.SAVINGS, Currency.PEN,
                new BigDecimal("500.00"), Account.AccountStatus.ACTIVE, java.time.LocalDateTime.now());

        when(accountApplicationService.getBalance(eq(accountId), eq(userId)))
                .thenReturn(account);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/balance", accountId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId.toString()))
                .andExpect(jsonPath("$.balance").value(500.00));
    }
}
