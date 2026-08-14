package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.enums.SosStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
     * Use case: the caller filters their own SOS history by state.
     * Scoped to one user by design — an unscoped status listing exposes every breakdown
     * in the system.
     */
    Page<SosRequest> findByUserUserIdAndStatus(UUID userId, SosStatus status, Pageable pageable);

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

    /**
     * Dynamic finder query targeted by background cleanup threads and startup listeners.
     * Evaluates a two-part criteria filter to pull down stale requests for lifecycle transition.
     *
     * Performance Warning:
     * To prevent costly full-table scans during heavy production spikes or service restarts,
     * this method requires a composite index matching its structural evaluation order:
     * {@code CREATE INDEX idx_sos_status_expiry ON sos_request(status, bidding_expires_at);}
     *
     * @param status The current operational lifecycle state node to filter (e.g., SosStatus.PENDING)
     * @param threshold The point-in-time barrier comparison marker (typically LocalDateTime.now())
     * @return A list containing all un-closed tickets whose bidding time frame has elapsed
     */
    List<SosRequest> findByStatusAndBiddingExpiresAtBefore(SosStatus status, LocalDateTime threshold);

    /**
     * Finds PENDING requests whose bidding window has not yet closed.
     * Used on startup to re-arm in-memory TaskScheduler timers that were lost when the app stopped.
     *
     * @param status    the lifecycle state to filter (always SosStatus.PENDING in practice)
     * @param threshold point-in-time lower bound (typically LocalDateTime.now())
     * @return requests still within their active bidding window
     */
    List<SosRequest> findByStatusAndBiddingExpiresAtAfter(SosStatus status, LocalDateTime threshold);

    /**
     * Moves one request between two states in a single statement, and reports whether it won.
     *
     * <p>Read-then-write cannot express this. The expiry timer reading PENDING, a bid being
     * accepted, and the timer then writing EXPIRED interleave into an expired request with an
     * accepted bid on it. Here the {@code from} state is part of the WHERE clause, so the loser of
     * that race changes nothing and gets 0 back.
     *
     * <p>The same property gives multi-instance deployments free leader election: every instance
     * may run the startup sweep, but exactly one receives 1 for any given row, so only one instance
     * publishes the notifications.
     *
     * @return 1 when this caller performed the transition, 0 when the request had already moved on
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    // VERSIONED bumps the optimistic-lock column, which a bulk update otherwise leaves alone —
    // without it a copy of this row already loaded elsewhere could still be written back on top.
    @Query("""
            UPDATE VERSIONED SosRequest s
               SET s.status = :to
             WHERE s.sosId = :sosId
               AND s.status = :from
            """)
    int compareAndSetStatus(@Param("sosId") UUID sosId,
                            @Param("from") SosStatus from,
                            @Param("to") SosStatus to);
}
