package com.fincore.transaction.domain.exception;

public class UnauthorizedTransferAccessException extends DomainException {

    public UnauthorizedTransferAccessException(String accountId) {
        super("UNAUTHORIZED_TRANSFER_ACCESS",
                "You do not have access to transfer history for account: " + accountId);
    }
}
