package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.enums.SosStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SosRequestRepository extends JpaRepository<SosRequest, UUID> {

    List<SosRequest> findByUserId(UUID userId);

    List<SosRequest> findByStatus(SosStatus status);

    List<SosRequest> findByUserIdAndStatus(UUID userId, SosStatus status);
}
