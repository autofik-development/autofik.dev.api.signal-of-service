package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {

    List<Feedback> findByMechanicMechanicId(UUID mechanicId);

    Optional<Feedback> findBySosRequestSosId(UUID sosId);
}
