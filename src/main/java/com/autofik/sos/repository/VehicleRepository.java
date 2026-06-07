package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    List<Vehicle> findByUserId(UUID userId);

    Optional<Vehicle> findByPlateNumber(String plateNumber);

    Optional<Vehicle> findByUserIdAndIsDefault(UUID userId, Boolean isDefault);
}
