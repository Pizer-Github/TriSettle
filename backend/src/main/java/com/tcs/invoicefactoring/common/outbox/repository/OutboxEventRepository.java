package com.tcs.invoicefactoring.common.outbox.repository;

import com.tcs.invoicefactoring.common.outbox.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("""
            SELECT o FROM OutboxEvent o
            WHERE o.status IN ('PENDING', 'FAILED')
              AND o.scheduledAt <= :now
              AND (o.claimedUntil IS NULL OR o.claimedUntil < :now)
            ORDER BY o.scheduledAt ASC
            """)
    List<OutboxEvent> findReadyToClaim(@Param("now") Instant now);

    List<OutboxEvent> findByAggregateId(UUID aggregateId);
}
