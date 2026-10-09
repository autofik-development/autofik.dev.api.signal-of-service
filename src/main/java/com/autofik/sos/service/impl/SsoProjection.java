package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.repository.VehicleRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Looks up the local projection of a User/Mechanic/Vehicle that SSO otherwise owns.
 *
 * <p>ponytail: no SSO round-trip — mobile is calling this service directly for today's test with no
 * gateway in front to hand it a bearer token, so every id here must already exist locally (seeded
 * via scripts/seed.sh, or created through POST /api/v1/mechanics) or the request fails clearly
 * instead of guessing. Restore the SSO verify+fetch call (git history: SsoClient, SsoProfile,
 * SsoVehicle) once the gateway is back and a real bearer is available again.
 */
@Component
@RequiredArgsConstructor
public class SsoProjection {

  private final UserRepository userRepository;
  private final MechanicRepository mechanicRepository;
  private final VehicleRepository vehicleRepository;

  @Transactional(readOnly = true)
  public User user(UUID userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
  }

  @Transactional(readOnly = true)
  public Mechanic mechanic(UUID mechanicId) {
    return mechanicRepository
        .findById(mechanicId)
        .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
  }

  @Transactional(readOnly = true)
  public Vehicle vehicle(String vehicleId, User owner) {
    Vehicle vehicle =
        vehicleRepository
            .findById(vehicleId)
            .orElseThrow(() -> new EntityNotFoundException("Vehicle not found: " + vehicleId));
    if (!vehicle.getUser().getUserId().equals(owner.getUserId())) {
      throw new AccessDeniedException("Vehicle does not belong to caller");
    }
    return vehicle;
  }
}
