package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
/**
 * Data Access Object (DAO) interface for managing Vehicle records.
 * Tracks driver vehicle information, registration plates, and default car selections.
 */
@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
    // finds all vehicle own by specified user
    List<Vehicle> findByUserUserId(UUID userId);
    // find vehicle by PlateNumber
    Optional<Vehicle> findByPlateNumber(String plateNumber);
    // find vehicle belong to specified user and default
    Optional<Vehicle> findByUserUserIdAndIsDefault(UUID userId, Boolean isDefault);
}
