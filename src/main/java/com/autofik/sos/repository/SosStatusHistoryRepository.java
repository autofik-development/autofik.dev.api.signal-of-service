package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.SosStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
/**
 * Data Access Object (DAO) interface for managing SosStatusHistory records.
 * Used for tracking the chronological audit trail of an SOS request's status updates.
 */
@Repository // Marks this as a database repository component managed by Spring
public interface SosStatusHistoryRepository extends JpaRepository<SosStatusHistory, UUID> {
    // list all status log by sosId sort from Ascending order.
    List<SosStatusHistory> findBySosRequestSosIdOrderByChangeAtAsc(UUID sosId);
}
