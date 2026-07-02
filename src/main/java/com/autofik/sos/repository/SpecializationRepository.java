package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.Specialization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
/**
 * Data Access Object (DAO) interface for managing Specialization categories.
 * Acts as the registry lookup for all individual mechanical skills supported by AutoFik.
 */
@Repository
public interface SpecializationRepository extends JpaRepository<Specialization, UUID> {
    // find specialization by Specialization's name
    Optional<Specialization> findByName(String name);
}
