package com.fincore.transaction.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.transaction.application.service.TransferApplicationService;
import com.fincore.transaction.domain.model.Transfer;
import com.fincore.transaction.domain.model.Transfer.Currency;
import com.fincore.transaction.infrastructure.web.advice.GlobalExceptionHandler;
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

import com.fincore.transaction.domain.exception.TransferNotFoundException;
import com.fincore.transaction.domain.exception.UnauthorizedTransferAccessException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class TransferControllerTest {

    private MockMvc mockMvc;

    @Mock
    private TransferApplicationService transferApplicationService;

    @InjectMocks
    private TransferController transferController;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID sourceAccountId = UUID.randomUUID();
    private final UUID targetAccountId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(transferController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/transfers should return 202 ACCEPTED with TransferResponse")
    void initiateTransfer_ShouldReturn202Accepted() throws Exception {
        TransferRequest request = new TransferRequest(sourceAccountId, targetAccountId, new BigDecimal("100.00"), Currency.PEN);
        Transfer transfer = new Transfer(sourceAccountId, targetAccountId, new BigDecimal("100.00"), Currency.PEN, "idem-123");

        when(transferApplicationService.initiateTransfer(eq(sourceAccountId), eq(targetAccountId), eq(new BigDecimal("100.00")), eq(Currency.PEN), eq("idem-123"), eq(userId.toString())))
                .thenReturn(transfer);

        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idem-123")
                        .header("X-User-Id", userId.toString())
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.transferId").value(transfer.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.currency").value("PEN"));
    }

    @Test
    @DisplayName("GET /api/v1/transfers/{id} should return 200 OK when user owns source account")
    void getTransfer_WhenUserOwnsAccount_ShouldReturn200() throws Exception {
        UUID transferId = UUID.randomUUID();
        Transfer transfer = new Transfer(transferId, sourceAccountId, targetAccountId, new BigDecimal("100.00"),
                Currency.PEN, Transfer.TransferStatus.COMPLETED, null, "idem-123", java.time.LocalDateTime.now());

        when(transferApplicationService.getTransferById(transferId, userId.toString())).thenReturn(transfer);

        mockMvc.perform(get("/api/v1/transfers/{transferId}", transferId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("GET /api/v1/transfers/{id} should return 404 NOT FOUND when transfer does not exist")
    void getTransfer_WhenNotFound_ShouldReturn404() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferApplicationService.getTransferById(transferId, userId.toString()))
                .thenThrow(new TransferNotFoundException(transferId.toString()));

        mockMvc.perform(get("/api/v1/transfers/{transferId}", transferId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/transfers/{id} should return 403 when user owns neither account on the transfer")
    void getTransfer_WhenUserOwnsNeitherAccount_ShouldReturn403() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferApplicationService.getTransferById(transferId, userId.toString()))
                .thenThrow(new UnauthorizedTransferAccessException(transferId.toString()));

        mockMvc.perform(get("/api/v1/transfers/{transferId}", transferId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED_TRANSFER_ACCESS"));
    }

    @Test
    @DisplayName("GET /api/v1/transfers/account/{accountId} should return 200 with list of transfers when user owns account")
    void getTransfersByAccount_WhenUserOwnsAccount_ShouldReturnTransferList() throws Exception {
        Transfer transfer = new Transfer(sourceAccountId, targetAccountId, new BigDecimal("100.00"), Currency.PEN, "idem-123");

        when(transferApplicationService.getTransfersByAccountId(sourceAccountId, userId.toString()))
                .thenReturn(List.of(transfer));

        mockMvc.perform(get("/api/v1/transfers/account/{accountId}", sourceAccountId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].transferId").value(transfer.getId().toString()));
    }

    @Test
    @DisplayName("GET /api/v1/transfers/account/{accountId} should return 403 when user does not own account")
    void getTransfersByAccount_WhenUserDoesNotOwnAccount_ShouldReturn403() throws Exception {
        UUID otherAccountId = UUID.randomUUID();

        when(transferApplicationService.getTransfersByAccountId(eq(otherAccountId), eq(userId.toString())))
                .thenThrow(new com.fincore.transaction.domain.exception.UnauthorizedTransferAccessException(otherAccountId.toString()));

        mockMvc.perform(get("/api/v1/transfers/account/{accountId}", otherAccountId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED_TRANSFER_ACCESS"));
    }
}
