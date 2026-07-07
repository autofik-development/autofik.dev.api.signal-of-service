package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
/**
 * Data Access Object (DAO) interface for managing Feedback records in the database.
 * Extends JpaRepository to handle automatic SQL query generation for reviews.
 */
@Repository // Tells Spring that this interface handles database operations and should be manageable by Spring's Dependency Injection
public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {

    /**
     * Finds all feedback reviews left for a specific mechanic.
     * Use case: Used to pull up a history of reviews on a mechanic's profile or to calculate their average rating.
     * SQL Equivalent: SELECT * FROM feedback WHERE mechanic_id = ?
     */
    List<Feedback> findByMechanicMechanicId(UUID mechanicId);
    Page<Feedback> findByMechanicMechanicId(UUID mechanicId, Pageable pageable);

    /**
     * Finds the specific feedback left for a unique emergency SOS request.
     * Use case: Used to check if a specific breakdown job has already been reviewed by the user.
     * Returns an 'Optional' because a job won't have feedback until the user actually submits one.
     * SQL Equivalent: SELECT * FROM feedback WHERE sos_id = ?
     */
    Optional<Feedback> findBySosRequestSosId(UUID sosId);
}
