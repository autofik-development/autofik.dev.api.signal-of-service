package com.autofik.sos.domain.entity;

import com.autofik.sos.domain.enums.FuelType;
import jakarta.persistence.*;
import java.util.List;
import lombok.*;

/**
 * Represents a vehicle owned by a {@link User}. A vehicle can be linked to multiple {@link
 * SosRequest}s over time, and its fuel type / model info is used to match suitable mechanics.
 */
@Entity
@Table(name = "vehicle")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class Vehicle {

  // Assigned, not generated: this row is a projection of an SSO vehicle and must carry that
  // same id, because the driver names it by SSO's id when raising an SOS. SSO's ids are not UUIDs
  // (e.g. "648123456789135"), so this is a string.
  @Id
  @Column(name = "id", length = 64, updatable = false, nullable = false)
  @ToString.Include
  private String id;

  // Registration plate. What the mechanic actually looks for on arrival.
  @Column(name = "license_plate")
  @ToString.Include
  private String licensePlate;

  // Vehicle manufacturer, e.g. "Toyota", "Honda"
  @Column(name = "car_brand")
  @ToString.Include
  private String carBrand;

  // Vehicle model, e.g. "Camry", "Civic"
  @Column(name = "car_model")
  @ToString.Include
  private String carModel;

  // Stored as String to support flexible formats (e.g. "2023", "2022-2023").
  @Column(name = "car_model_year")
  @ToString.Include
  private String carModelYear;

  // Used to match mechanics who specialize in specific engine/fuel types.
  @Enumerated(EnumType.STRING)
  @Column(name = "fuel_type")
  @ToString.Include
  private FuelType fuelType;

  // Relations
  // Owner of this vehicle. Lazily fetched; every vehicle must belong to a user.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  @ToString.Exclude
  private User user;

  // All SOS (roadside assistance) requests ever made for this vehicle.
  // Inverse side of the relationship, owned by SosRequest.vehicle.
  @OneToMany(mappedBy = "vehicle", fetch = FetchType.LAZY)
  @ToString.Exclude
  private List<SosRequest> sosRequests;
}
