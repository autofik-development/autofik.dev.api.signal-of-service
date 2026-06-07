package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.SosStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SosStatusHistoryRepository extends JpaRepository<SosStatusHistory, UUID> {

    List<SosStatusHistory> findBySosRequestSosIdOrderByChangeAtAsc(UUID sosId);
}
