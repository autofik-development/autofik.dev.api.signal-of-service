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

    /**
     * Retrieves a paginated list of SOS requests submitted by a specific user.
     * Typically used for displaying a historical feed on the mobile application.
     *
     * @param userId   the unique identifier of the requesting user
     * @param pageable pagination parameters (page number, size, and sorting directives)
     * @return a {@link Page} container enclosing matching {@link SosRequest} entities
     */
    Page<SosRequest> findByUserUserId(UUID userId, Pageable pageable);

    /**
     * Retrieves a paginated list of all SOS requests currently matching a specific system status.
     * Frequently used by administrative dashboards or discovery lists for available mechanics.
     *
     * @param status   the structural operational state (e.g., PENDING, ACCEPTED)
     * @param pageable pagination parameters (page number, size, and sorting directives)
     * @return a {@link Page} container enclosing matching {@link SosRequest} entities
     */
    Page<SosRequest> findByStatus(SosStatus status, Pageable pageable);

    /**
     * Fetches all SOS requests submitted by a specific user that belong to a single explicit status.
     * Handy for extracting un-paginated context on localized user-specific states.
     *
     * @param userId the unique identifier of the user
     * @param status the exact target operational state
     * @return a un-paginated list of matching {@link SosRequest} records
     */
    List<SosRequest> findByUserUserIdAndStatus(UUID userId, SosStatus status);

    // check if user already has an active SOS in any of the given statuses
    boolean existsByUserUserIdAndStatusIn(UUID userId, List<SosStatus> statuses);
}
