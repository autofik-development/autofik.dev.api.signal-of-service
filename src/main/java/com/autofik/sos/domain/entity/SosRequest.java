package com.autofik.sos.domain.entity;

import com.autofik.sos.domain.enums.SosStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import org.locationtech.jts.geom.Point;

/**
 * Core domain entity for a user-submitted roadside assistance request. Lifecycle: PENDING →
 * ACCEPTED → ON_THE_WAY → ARRIVED → COMPLETED (or CANCELLED/EXPIRED). The accepted mechanic is NOT
 * stored here — resolve it via the ACCEPTED bid in BidSos.
 */
@Entity
@Table(name = "sos_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// Exclude lazy-loaded collections from toString to avoid triggering DB queries during logging
@ToString(exclude = {"user", "vehicle", "bids", "statusHistory", "feedback", "media"})
public class SosRequest {

  // Primary key, auto-generated as a UUID (not client-supplied, not updatable after creation)
  @Id
  @GeneratedValue
  @UuidGenerator
  @Column(name = "sos_id", updatable = false, nullable = false)
  private UUID sosId;

  // Category of the roadside issue, e.g. "flat tire", "battery", "engine failure"
  @Column(name = "problem_type", nullable = false)
  private String problemType;

  // Free-text details the user provides about the problem
  @Column(columnDefinition = "TEXT")
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Builder.Default
  private SosStatus status = SosStatus.PENDING;

  // geography(Point, 4326) uses WGS-84 (the same CRS as GPS/Google Maps).
  // PostGIS stores it as a "geography" (not "geometry") type so that ST_DWithin
  // radius checks work in meters without requiring a local projection.
  @Column(columnDefinition = "geography(Point, 4326)", nullable = false)
  private Point location;

  // Human-readable address resolved from the GPS coordinates via reverse geocoding.
  // Stored so the UI doesn't have to call the Maps API on every read.
  @Column(name = "location_address", columnDefinition = "TEXT")
  private String locationAddress;

  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  // updatable=false: the bidding window is fixed at creation time and must never change.
  @Column(name = "bidding_expires_at")
  private LocalDateTime biddingExpiresAt;

  @Column(name = "rebroadcast_count", columnDefinition = "integer not null default 0")
  @Builder.Default
  private int rebroadcastCount = 0;

  // Optional user-defined cap on how far to broadcast this SOS. null = no limit (mechanic's
  // service_radius_km is the only constraint). Max enforced at the DTO layer (50 km).
  @Column(name = "broadcast_radius_km")
  private Integer broadcastRadiusKm;

  // 250 seconds (~4 min) for mechanics to respond before the request expires automatically.
  public static final int BIDDING_WINDOW_SECONDS = 250;
  public static final int MAX_REBROADCAST = 3;

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = LocalDateTime.now();
    biddingExpiresAt = createdAt.plusSeconds(BIDDING_WINDOW_SECONDS);
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }

  // --- Relations ---
  // The user who submitted this SOS request
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  // The vehicle involved in this request, used to match mechanics by fuel/engine type
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "vehicle_id", nullable = false)
  private Vehicle vehicle;

  // All bids submitted by mechanics for this request. Deleting the request cascades to its bids.
  @OneToMany(mappedBy = "sosRequest", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
  private List<BidSos> bids;

  // Full audit trail of status transitions (proposed by mechanic, approved by user) for this
  // request
  @OneToMany(mappedBy = "sosRequest", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
  private List<SosStatusHistory> statusHistory;

  // The user's post-completion feedback for this request, if submitted (1:1)
  @OneToOne(mappedBy = "sosRequest", cascade = CascadeType.ALL)
  private Feedback feedback;

  // Photos/videos attached to this request (e.g. of the vehicle issue)
  @OneToMany(mappedBy = "sosRequest", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
  private List<SosMedia> media;
}
