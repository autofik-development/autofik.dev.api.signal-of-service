package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.enums.BidStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BidSosRepository extends JpaRepository<BidSos, UUID> {

    List<BidSos> findBySosRequestSosId(UUID sosId);

    List<BidSos> findByMechanicMechanicId(UUID mechanicId);

    List<BidSos> findBySosRequestSosIdAndStatus(UUID sosId, BidStatus status);

    // At most one ACCEPTED bid per SOS request — used to resolve the assigned mechanic.
    Optional<BidSos> findFirstBySosRequestSosIdAndStatus(UUID sosId, BidStatus status);

    boolean existsBySosRequestSosIdAndMechanicMechanicId(UUID sosId, UUID mechanicId);
}
