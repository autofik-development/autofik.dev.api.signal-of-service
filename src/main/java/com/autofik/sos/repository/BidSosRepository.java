package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository // Tells Spring that this interface handles database operations and should be manageable
// by Spring's Dependency Injection
public interface BidSosRepository extends JpaRepository<BidSos, UUID> {
  // Note: By extending JpaRepository<BidSos, UUID>, methods like .save(), .findById(), and
  // .deleteById() are instantly available.

  /**
   * Finds all bids made for a specific emergency SOS request. SQL Equivalent behind the scenes:
   * SELECT * FROM bid_sos WHERE sos_id = ?
   */
  List<BidSos> findBySosRequestSosId(UUID sosId);

  /**
   * Finds all bids submitted by a specific mechanic. SQL Equivalent: SELECT * FROM bid_sos WHERE
   * mechanic_id = ?
   */
  List<BidSos> findByMechanicMechanicId(UUID mechanicId);

  Page<BidSos> findByMechanicMechanicId(UUID mechanicId, Pageable pageable);

  /**
   * Finds a list of bids for a specific SOS request filtered by their status (e.g., all PENDING
   * bids). SQL Equivalent: SELECT * FROM bid_sos WHERE sos_id = ? AND status = ?
   */
  List<BidSos> findBySosRequestSosIdAndStatus(UUID sosId, BidStatus status);

  /**
   * At most one ACCEPTED bid per SOS request — used to resolve the assigned mechanic. * Grabs only
   * the first matching bid record instead of a list. Uses 'Optional' because an ACCEPTED bid might
   * not exist yet if the customer is still picking a mechanic.
   */
  Optional<BidSos> findFirstBySosRequestSosIdAndStatus(UUID sosId, BidStatus status);

  /**
   * SOS statuses in which an ACCEPTED bid still means "this mechanic is on that job". A bid stays
   * ACCEPTED after the ticket is completed or cancelled, so the bid status alone says nothing about
   * whether the job is live.
   */
  List<SosStatus> ACTIVE_JOB_STATUSES =
      List.of(SosStatus.ACCEPTED, SosStatus.ON_THE_WAY, SosStatus.ARRIVED, SosStatus.IN_PROGRESS);

  Optional<BidSos>
      findFirstByMechanicMechanicIdAndStatusAndSosRequestStatusInOrderByRespondedAtDesc(
          UUID mechanicId, BidStatus status, Collection<SosStatus> sosStatuses);

  /**
   * The SOS a mechanic is working right now, used to route location broadcasts: the most recently
   * accepted bid whose ticket is still live. Finished jobs are ignored, so a mechanic's second job
   * is never mistaken for the first.
   */
  default Optional<BidSos> findActiveJob(UUID mechanicId) {
    return findFirstByMechanicMechanicIdAndStatusAndSosRequestStatusInOrderByRespondedAtDesc(
        mechanicId, BidStatus.ACCEPTED, ACTIVE_JOB_STATUSES);
  }

  @Query(
      "select count(b) > 0 from BidSos b where b.mechanic.mechanicId = :mechanicId"
          + " and b.sosRequest.sosId = :sosId and b.status = :bidStatus"
          + " and b.sosRequest.status in :sosStatuses")
  boolean existsAssignment(
      @Param("mechanicId") UUID mechanicId,
      @Param("sosId") UUID sosId,
      @Param("bidStatus") BidStatus bidStatus,
      @Param("sosStatuses") Collection<SosStatus> sosStatuses);

  /** True when the mechanic holds the accepted bid on this SOS and the ticket is still live. */
  default boolean isAssignedToActiveJob(UUID mechanicId, UUID sosId) {
    return existsAssignment(mechanicId, sosId, BidStatus.ACCEPTED, ACTIVE_JOB_STATUSES);
  }

  @Query(
      "select count(b) > 0 from BidSos b where b.mechanic.mechanicId = :mechanicId"
          + " and b.sosRequest.user.userId = :userId and b.status = :bidStatus"
          + " and b.sosRequest.status in :sosStatuses")
  boolean existsJobWithUser(
      @Param("mechanicId") UUID mechanicId,
      @Param("userId") UUID userId,
      @Param("bidStatus") BidStatus bidStatus,
      @Param("sosStatuses") Collection<SosStatus> sosStatuses);

  /** True when this driver has a live ticket that the mechanic has been accepted for. */
  default boolean isOnActiveJobWithDriver(UUID mechanicId, UUID userId) {
    return existsJobWithUser(mechanicId, userId, BidStatus.ACCEPTED, ACTIVE_JOB_STATUSES);
  }

  /**
   * Checks if a mechanic has already submitted a bid for a specific SOS request. Returns true or
   * false. This prevents a mechanic from sending duplicate bids for the same job. SQL Equivalent:
   * SELECT COUNT(*) > 0 FROM bid_sos WHERE sos_id = ? AND mechanic_id = ?
   */
  boolean existsBySosRequestSosIdAndMechanicMechanicId(UUID sosId, UUID mechanicId);

  /**
   * Checks whether any bid at all exists for a SOS request, regardless of status or mechanic — used
   * to tell a true zero-response expiry from one where bids just weren't accepted in time.
   */
  boolean existsBySosRequestSosId(UUID sosId);
}
