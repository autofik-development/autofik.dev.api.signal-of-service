package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.enums.SosStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
/**
 * Data Access Object (DAO) interface for managing emergency SosRequest records.
 * Provides the data access layer for tracking live breakdown tickets and driver request histories.
 */
@Repository // Marks this as a database repository component managed by Spring
public interface SosRequestRepository extends JpaRepository<SosRequest, UUID> {

    // list sos request by userId
    List<SosRequest> findByUserUserId(UUID userId);
    Page<SosRequest> findByUserUserId(UUID userId, Pageable pageable);

    // list sos request history by sos status
    List<SosRequest> findByStatus(SosStatus status);
    Page<SosRequest> findByStatus(SosStatus status, Pageable pageable);

    // list sos request by userId and sos status
    List<SosRequest> findByUserUserIdAndStatus(UUID userId, SosStatus status);

    // check if user already has an active SOS in any of the given statuses
    boolean existsByUserUserIdAndStatusIn(UUID userId, List<SosStatus> statuses);
}
