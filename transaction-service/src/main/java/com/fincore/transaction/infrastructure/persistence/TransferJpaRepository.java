package com.fincore.transaction.infrastructure.persistence;

import com.fincore.transaction.domain.model.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferJpaRepository extends JpaRepository<TransferJpaEntity, UUID> {

    Optional<TransferJpaEntity> findByIdempotencyKey(String idempotencyKey);

    @Modifying
    @Query("UPDATE TransferJpaEntity t SET t.status = :status, t.updatedAt = CURRENT_TIMESTAMP WHERE t.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") Transfer.TransferStatus status);

    @Modifying
    @Query("UPDATE TransferJpaEntity t SET t.status = :status, t.failureReason = :reason, t.updatedAt = CURRENT_TIMESTAMP WHERE t.id = :id")
    int updateStatusWithReason(@Param("id") UUID id, @Param("status") Transfer.TransferStatus status, @Param("reason") String reason);

    @Query("SELECT t FROM TransferJpaEntity t WHERE t.sourceAccountId = :accountId OR t.targetAccountId = :accountId ORDER BY t.createdAt DESC")
    List<TransferJpaEntity> findByAccountId(@Param("accountId") UUID accountId);
}