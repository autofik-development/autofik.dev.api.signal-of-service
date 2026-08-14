package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.enums.BidStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository // Tells Spring that this interface handles database operations and should be manageable by Spring's Dependency Injection
public interface BidSosRepository extends JpaRepository<BidSos, UUID> {
    // Note: By extending JpaRepository<BidSos, UUID>, methods like .save(), .findById(), and .deleteById() are instantly available.

    /**
     * Finds all bids made for a specific emergency SOS request.
     * SQL Equivalent behind the scenes: SELECT * FROM bid_sos WHERE sos_id = ?
     */
    List<BidSos> findBySosRequestSosId(UUID sosId);

    /**
     * Finds all bids submitted by a specific mechanic.
     * SQL Equivalent: SELECT * FROM bid_sos WHERE mechanic_id = ?
     */
    List<BidSos> findByMechanicMechanicId(UUID mechanicId);
    Page<BidSos> findByMechanicMechanicId(UUID mechanicId, Pageable pageable);

    /**
     * Finds a list of bids for a specific SOS request filtered by their status (e.g., all PENDING bids).
     * SQL Equivalent: SELECT * FROM bid_sos WHERE sos_id = ? AND status = ?
     */
    List<BidSos> findBySosRequestSosIdAndStatus(UUID sosId, BidStatus status);

    /**
     * At most one ACCEPTED bid per SOS request — used to resolve the assigned mechanic.
     * * Grabs only the first matching bid record instead of a list.
     * Uses 'Optional' because an ACCEPTED bid might not exist yet if the customer is still picking a mechanic.
     */
    Optional<BidSos> findFirstBySosRequestSosIdAndStatus(UUID sosId, BidStatus status);

    /**
     * The batch form of the lookup above, for resolving the assigned mechanic across a whole page
     * of SOS requests. One query instead of one per row, with the mechanic and its rating joined in
     * — otherwise reading bid.getMechanic() re-introduces the N+1 a row at a time.
     */
    @EntityGraph(attributePaths = {"mechanic", "mechanic.rate"})
    List<BidSos> findBySosRequestSosIdInAndStatus(Collection<UUID> sosIds, BidStatus status);

    /**
     * Resolve the active SOS request for a mechanic — used to route location broadcasts.
     * * Finds the first bid belonging to a specific mechanic with a specific status (usually BidStatus.ACCEPTED).
     * This helps the system know if a mechanic is currently busy working on a live breakdown.
     */
    Optional<BidSos> findFirstByMechanicMechanicIdAndStatus(UUID mechanicId, BidStatus status);

    /**
     * Checks if a mechanic has already submitted a bid for a specific SOS request.
     * Returns true or false. This prevents a mechanic from sending duplicate bids for the same job.
     * SQL Equivalent: SELECT COUNT(*) > 0 FROM bid_sos WHERE sos_id = ? AND mechanic_id = ?
     */
    boolean existsBySosRequestSosIdAndMechanicMechanicId(UUID sosId, UUID mechanicId);
}
