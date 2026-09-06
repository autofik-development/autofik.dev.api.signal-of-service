package com.autofik.sos.repository;

import com.autofik.sos.domain.entity.Vehicle;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
  // list vehicle by userId
  List<Vehicle> findByUserUserId(UUID userId);
}
