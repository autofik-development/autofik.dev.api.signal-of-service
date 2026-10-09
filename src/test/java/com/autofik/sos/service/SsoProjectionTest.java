package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.repository.VehicleRepository;
import com.autofik.sos.service.impl.SsoProjection;
import jakarta.persistence.EntityNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class SsoProjectionTest {

  @Mock UserRepository users;
  @Mock MechanicRepository mechanics;
  @Mock VehicleRepository vehicles;
  @InjectMocks SsoProjection projection;

  private final UUID id = UUID.randomUUID();

  @Test
  void user_found() {
    User u = User.builder().userId(id).build();
    when(users.findById(id)).thenReturn(Optional.of(u));
    assertThat(projection.user(id)).isSameAs(u);
  }

  @Test
  void user_missing_isNotFound() {
    when(users.findById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> projection.user(id)).isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void mechanic_found() {
    Mechanic m = Mechanic.builder().mechanicId(id).build();
    when(mechanics.findById(id)).thenReturn(Optional.of(m));
    assertThat(projection.mechanic(id)).isSameAs(m);
  }

  @Test
  void mechanic_missing_isNotFound() {
    when(mechanics.findById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> projection.mechanic(id)).isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void vehicle_ownedByCaller_isReturned() {
    User owner = User.builder().userId(UUID.randomUUID()).build();
    Vehicle v = Vehicle.builder().id(id.toString()).user(owner).build();
    when(vehicles.findById(id.toString())).thenReturn(Optional.of(v));
    assertThat(projection.vehicle(id.toString(), owner)).isSameAs(v);
  }

  @Test
  void vehicle_ownedBySomeoneElse_isDenied() {
    User owner = User.builder().userId(UUID.randomUUID()).build();
    User caller = User.builder().userId(UUID.randomUUID()).build();
    when(vehicles.findById(id.toString()))
        .thenReturn(Optional.of(Vehicle.builder().id(id.toString()).user(owner).build()));
    assertThatThrownBy(() -> projection.vehicle(id.toString(), caller))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void vehicle_missing_isNotFound() {
    when(vehicles.findById(id.toString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> projection.vehicle(id.toString(), User.builder().userId(id).build()))
        .isInstanceOf(EntityNotFoundException.class);
  }
}
