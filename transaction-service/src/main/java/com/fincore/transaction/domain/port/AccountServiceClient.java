package com.fincore.transaction.domain.port;

import java.math.BigDecimal;
import java.util.UUID;

public interface AccountServiceClient {

    void validateBalance(UUID accountId, BigDecimal amount, String currency);

    default void validateBalance(UUID accountId, BigDecimal amount, String currency, String userId) {
        validateBalance(accountId, amount, currency);
    }

    /**
     * Verifies that {@code userId} is the owner of {@code accountId}.
     * Throws {@link com.fincore.transaction.domain.exception.UnauthorizedTransferAccessException}
     * if ownership cannot be confirmed.
     */
    void verifyOwnership(UUID accountId, String userId);

    void credit(UUID accountId, BigDecimal amount, String currency);
}