package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.SosStatusHistory;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.SosStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Data Access Object (DAO) interface for managing SosStatusHistory records. Used for tracking the
 * chronological audit trail of an SOS request's status updates.
 */
@Repository // Marks this as a database repository component managed by Spring
public interface SosStatusHistoryRepository extends JpaRepository<SosStatusHistory, UUID> {
  // list all status log by sosId sort from Ascending order.
  List<SosStatusHistory> findBySosRequestSosIdOrderByChangeAtAsc(UUID sosId);

  /**
   * True when this mechanic walked away from the job after it was assigned to them (released it, or
   * was released for not coming): those transitions are the only ones that put a mechanic's name on
   * a move back to PENDING.
   */
  boolean existsBySosRequestSosIdAndProposedByMechanicIdAndToStatus(
      UUID sosId, UUID mechanicId, SosStatus toStatus);

  /** Every mechanic who walked away from this ticket (same rule as the exists check above). */
  @Query(
      "select distinct h.proposedBy.mechanicId from SosStatusHistory h "
          + "where h.sosRequest.sosId = :sosId and h.toStatus = :toStatus")
  Set<UUID> findMechanicIdsByToStatus(
      @Param("sosId") UUID sosId, @Param("toStatus") SosStatus toStatus);

  /**
   * The approval state as it is in the database right now. A scalar query, so it is not answered
   * from an entity already loaded in this transaction: used after taking the ticket's row lock to
   * see an answer another transaction committed while this one waited.
   */
  @Query("select h.approvedStatus from SosStatusHistory h where h.id = :id")
  Optional<ApprovedStatus> findApprovedStatusById(@Param("id") UUID id);

  /** Completion requests still waiting for an answer that were proposed before the cutoff. */
  List<SosStatusHistory> findByApprovedStatusAndProposedAtBefore(
      ApprovedStatus approvedStatus, LocalDateTime cutoff);

  /**
   * The newest history row for a SOS in the given approval state, e.g. a completion awaiting OK.
   */
  Optional<SosStatusHistory> findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
      UUID sosId, ApprovedStatus approvedStatus);

  /** Every history row for a SOS in the given approval state, e.g. all requests still waiting. */
  List<SosStatusHistory> findBySosRequestSosIdAndApprovedStatus(
      UUID sosId, ApprovedStatus approvedStatus);

  /** The newest row that moved the SOS to {@code toStatus} and was decided as {@code approved}. */
  Optional<SosStatusHistory>
      findFirstBySosRequestSosIdAndToStatusAndApprovedStatusOrderByChangeAtDesc(
          UUID sosId, SosStatus toStatus, ApprovedStatus approved);
}
