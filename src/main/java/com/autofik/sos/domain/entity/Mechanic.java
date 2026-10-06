package com.autofik.sos.domain.entity;

import com.autofik.sos.domain.enums.ApprovalStatus;
import com.autofik.sos.domain.enums.MechanicRole;
import jakarta.persistence.*;
import java.util.List;
import java.util.UUID;
import lombok.*;
import org.locationtech.jts.geom.Point;

/**
 * Represents a mechanic who can accept and fulfill SOS requests. Location search uses a two-stage
 * approach: fast H3 cell index (Stage 1) → exact PostGIS radius check (Stage 2). See
 * MechanicRepository for the queries.
 */
@Entity
// B-tree index on current_h3_index enables the Stage 1 H3 cell lookup without a full table scan
@Table(
    name = "mechanic",
    indexes = {@Index(name = "idx_mechanic_current_h3_index", columnList = "current_h3_index")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class Mechanic {

  // Assigned, not generated: this row is a projection of an SSO mechanic and must carry that
  // same UUID. A locally generated id would silently disagree with every other service.
  // ponytail: an assigned id makes save() SELECT before INSERT. Create path only, not hot.
  @Id
  @Column(name = "id", updatable = false, nullable = false)
  @ToString.Include
  private UUID mechanicId;

  @Column(name = "full_name", length = 30, nullable = false)
  @ToString.Include
  private String fullName;

  @Column(name = "phone_number", length = 20, nullable = false)
  @ToString.Include
  private String phoneNumber;

  @Column(length = 30)
  @ToString.Include
  private String email;

  // under_garage = works at a fixed shop; freeland = mobile/independent.
  @Enumerated(EnumType.STRING)
  @Column(name = "mechanic_role")
  @ToString.Include
  private MechanicRole mechanicRole;

  @Column(name = "referral_code", unique = true)
  private String referralCode;

  // Identity document check (ID card, license) — done during onboarding.
  @Enumerated(EnumType.STRING)
  @Column(name = "kyc_status", nullable = false)
  @Builder.Default
  @ToString.Include
  private ApprovalStatus kycStatus = ApprovalStatus.PENDING;

  @Column(columnDefinition = "TEXT")
  private String address;

  // Registered home/base address; used as fallback when the mechanic has no active location ping.
  @Column(name = "base_location", columnDefinition = "geography(Point, 4326)")
  @ToString.Exclude
  private Point baseLocation;

  // Real-time GPS position updated on every location ping; used for radius matching.
  @Column(name = "current_location", columnDefinition = "geography(Point, 4326)")
  @ToString.Exclude
  private Point currentLocation;

  // When current_location was last written. Dispatch skips a mechanic whose last ping is older
  // than sos.dispatch.location-max-age-minutes; null (never pinged since this column existed) is
  // treated as fresh so a deploy does not silently drop everyone who is offline at that moment.
  @Column(name = "location_updated_at")
  private java.time.LocalDateTime locationUpdatedAt;

  // When the mechanic last moved: set when a persisted ping is at least
  // sos.location.moved-threshold-meters from the previous one. Unlike locationUpdatedAt (stamped on
  // every ping) this stays put while the phone sits still, so the no-show sweep can tell a parked
  // mechanic from a moving one. null (not seen since this column existed) counts as moving.
  @Column(name = "location_moved_at")
  private java.time.LocalDateTime locationMovedAt;

  // H3 hex-cell address (resolution 7, ~5 km² per cell) derived from currentLocation.
  // Stored as a plain string so the B-tree index above can serve Stage 1 lookups.
  @Column(name = "current_h3_index", length = 16)
  @ToString.Include
  private String currentH3Index;

  // Coverage area in km; multiplied by 1000 when passed to PostGIS ST_DWithin (which expects
  // meters).
  @Column(name = "service_radius_km", nullable = false)
  @Builder.Default
  @ToString.Include
  private Integer serviceRadiusKm = 10;

  @Column(name = "available_status", nullable = false)
  @Builder.Default
  @ToString.Include
  private Boolean availableStatus = true;

  // Admin verification of mechanic credentials/skills — separate from KYC.
  @Enumerated(EnumType.STRING)
  @Column(name = "verification_status", nullable = false)
  @Builder.Default
  @ToString.Include
  private ApprovalStatus verificationStatus = ApprovalStatus.PENDING;

  // Relations
  @OneToOne(mappedBy = "mechanic", cascade = CascadeType.ALL)
  @ToString.Exclude
  private MechanicRate rate;

  @OneToMany(mappedBy = "mechanic", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
  @ToString.Exclude
  private List<MechanicSkill> skills;

  @OneToMany(mappedBy = "mechanic", fetch = FetchType.LAZY)
  @ToString.Exclude
  private List<Feedback> feedbacks;

  @OneToMany(mappedBy = "mechanic", fetch = FetchType.LAZY)
  @ToString.Exclude
  private List<BidSos> bids;

  @OneToMany(mappedBy = "proposedBy", fetch = FetchType.LAZY)
  @ToString.Exclude
  private List<SosStatusHistory> proposedHistories;
}
