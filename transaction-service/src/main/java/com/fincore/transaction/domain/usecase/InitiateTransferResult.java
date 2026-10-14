package com.fincore.transaction.domain.usecase;

import com.fincore.transaction.domain.model.Transfer;

public record InitiateTransferResult(Transfer transfer, boolean newlyCreated) {
}
