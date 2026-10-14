package com.fincore.transaction.domain.exception;

public class TransferNotFoundException extends DomainException {

    public TransferNotFoundException(String transferId) {
        super("TRANSFER_NOT_FOUND", "Transfer not found with id: " + transferId);
    }
}
