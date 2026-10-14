package com.fincore.transaction.infrastructure.persistence;

import com.fincore.transaction.domain.model.Transfer;
import com.fincore.transaction.domain.port.TransferRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class TransferRepositoryImpl implements TransferRepository {

    private final TransferJpaRepository jpaRepository;

    public TransferRepositoryImpl(TransferJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Transfer save(Transfer transfer) {
        return jpaRepository.save(TransferJpaEntity.fromDomain(transfer)).toDomain();
    }

    @Override
    public Optional<Transfer> findById(UUID id) {
        return jpaRepository.findById(id).map(TransferJpaEntity::toDomain);
    }

    @Override
    public Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey)
                .map(TransferJpaEntity::toDomain);
    }

    @Override
    public int updateStatus(UUID transferId, Transfer.TransferStatus status) {
        return jpaRepository.updateStatus(transferId, status);
    }

    @Override
    public int updateStatusWithReason(UUID transferId, Transfer.TransferStatus status, String failureReason) {
        return jpaRepository.updateStatusWithReason(transferId, status, failureReason);
    }

    @Override
    public List<Transfer> findByAccountId(UUID accountId) {
        return jpaRepository.findByAccountId(accountId)
                .stream()
                .map(TransferJpaEntity::toDomain)
                .toList();
    }
}