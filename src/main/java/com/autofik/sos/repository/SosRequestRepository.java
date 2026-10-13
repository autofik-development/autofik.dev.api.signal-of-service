package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.enums.SosStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data Access Object (DAO) interface for managing emergency SosRequest records. Provides the data
 * access layer for tracking live breakdown tickets and driver request histories.
 */
@Repository // Marks this as a database repository component managed by Spring
public interface SosRequestRepository extends JpaRepository<SosRequest, UUID> {

  /**
   * Retrieves a paginated list of SOS requests submitted by a specific user. Typically used for
   * displaying a historical feed on the mobile application.
   *
   * @param userId the unique identifier of the requesting user
   * @param pageable pagination parameters (page number, size, and sorting directives)
   * @return a {@link Page} container enclosing matching {@link SosRequest} entities
   */
  Page<SosRequest> findByUserUserId(UUID userId, Pageable pageable);

  /**
   * Retrieves a paginated list of all SOS requests currently matching a specific system status.
   * Frequently used by administrative dashboards or discovery lists for available mechanics.
   *
   * @param status the structural operational state (e.g., PENDING, ACCEPTED)
   * @param pageable pagination parameters (page number, size, and sorting directives)
   * @return a {@link Page} container enclosing matching {@link SosRequest} entities
   */
  Page<SosRequest> findByStatus(SosStatus status, Pageable pageable);

  /**
   * Use case: the caller filters their own SOS history by state. Scoped to one user by design — an
   * unscoped status listing exposes every breakdown in the system.
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
   * Writes only the reverse-geocoded address. create() gets it after the ticket is committed, and a
   * full save of its detached copy would put back the status and anything else a cancel or an
   * accept changed meanwhile. A bulk update skips {@code @PreUpdate}, so updatedAt is set here.
   */
  @Transactional
  @Modifying
  @Query(
      "UPDATE SosRequest s SET s.locationAddress = :address, s.updatedAt = :now"
          + " WHERE s.sosId = :sosId")
  int updateLocationAddress(
      @Param("sosId") UUID sosId,
      @Param("address") String address,
      @Param("now") LocalDateTime now);

  /**
   * Dynamic finder query targeted by background cleanup threads and startup listeners. Evaluates a
   * two-part criteria filter to pull down stale requests for lifecycle transition.
   *
   * <p>Performance Warning: To prevent costly full-table scans during heavy production spikes or
   * service restarts, this method requires a composite index matching its structural evaluation
   * order: {@code CREATE INDEX idx_sos_status_expiry ON sos_request(status, bidding_expires_at);}
   *
   * @param status The current operational lifecycle state node to filter (e.g., SosStatus.PENDING)
   * @param threshold The point-in-time barrier comparison marker (typically LocalDateTime.now())
   * @return A list containing all un-closed tickets whose bidding time frame has elapsed
   */
  List<SosRequest> findByStatusAndBiddingExpiresAtBefore(SosStatus status, LocalDateTime threshold);

  /**
   * Finds PENDING requests whose bidding window has not yet closed. Used on startup to re-arm
   * in-memory TaskScheduler timers that were lost when the app stopped.
   *
   * @param status the lifecycle state to filter (always SosStatus.PENDING in practice)
   * @param threshold point-in-time lower bound (typically LocalDateTime.now())
   * @return requests still within their active bidding window
   */
  List<SosRequest> findByStatusAndBiddingExpiresAtAfter(SosStatus status, LocalDateTime threshold);

  /**
   * Locks the ticket row until the calling transaction ends and returns its status as committed in
   * the database, not as cached in the persistence context. Placing a bid and accepting a bid both
   * take this lock, so a bid can never be inserted between an accept's auto-reject and its commit,
   * and two accepts on the same ticket cannot both win (or deadlock rejecting each other's bid).
   */
  @Query(
      value = "SELECT status FROM sos_request WHERE sos_id = :sosId FOR UPDATE",
      nativeQuery = true)
  Optional<String> lockStatus(@Param("sosId") UUID sosId);

  /**
   * PENDING tickets whose bidding window is still open, inside the mechanic's own service radius,
   * of a kind the mechanic has a skill for, that the mechanic has not bid on: newest first. A cheap
   * superset of what the mechanic is offered; the caller applies the real eligibility rule to it.
   */
  @Query(
      value =
          """
            SELECT s.sos_id FROM sos_request s
            JOIN mechanic m ON m.id = :mechanicId
            WHERE s.status = 'PENDING'
              AND s.bidding_expires_at > :now
              AND ST_DWithin(s.location, m.current_location, m.service_radius_km * 1000)
              AND EXISTS (
                  SELECT 1 FROM mechanic_skill ms, unnest(ms.skill_tags) tag
                  WHERE ms.mechanic_profile_id = m.id AND UPPER(tag) = s.problem_type
              )
              AND NOT EXISTS (
                  SELECT 1 FROM bid_sos b
                  WHERE b.sos_id = s.sos_id AND b.mechanic_id = m.id
              )
            ORDER BY s.created_at DESC
            LIMIT :limit
            """,
      nativeQuery = true)
  List<UUID> findOpenIdsNear(
      @Param("mechanicId") UUID mechanicId,
      @Param("now") LocalDateTime now,
      @Param("limit") int limit);
}
