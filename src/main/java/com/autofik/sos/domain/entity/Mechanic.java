package com.autofik.sos.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Represents a mechanic who can accept and fulfill SOS requests.
 * Location search uses a two-stage approach: fast H3 cell index (Stage 1) →
 * exact PostGIS radius check (Stage 2). See MechanicRepository for the queries.
 */
@Entity
// B-tree index on current_h3_index enables the Stage 1 H3 cell lookup without a full table scan
@Table(name = "mechanic", indexes = {
        @Index(name = "idx_mechanic_current_h3_index", columnList = "current_h3_index")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Mechanic {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "mechanic_id", updatable = false, nullable = false)
    private UUID mechanicId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String phone;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "hash_password")
    private String hashPassword;

    @Column(name = "referrer_code", unique = true)
    private String referrerCode;

    @Column(name = "is_available", nullable = false)
    @Builder.Default
    private Boolean isAvailable = true;

    @Column(name = "rating_avg", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal ratingAvg = BigDecimal.ZERO;

    // Registered home/base address; used as fallback when the mechanic has no active location ping.
    @Column(name = "base_location", columnDefinition = "geography(Point, 4326)")
    private Point baseLocation;

    // Real-time GPS position updated on every location ping; used for radius matching.
    @Column(name = "current_location", columnDefinition = "geography(Point, 4326)")
    private Point currentLocation;

    // H3 hex-cell address (resolution 7, ~5 km² per cell) derived from currentLocation.
    // Stored as a plain string so the B-tree index above can serve Stage 1 lookups.
    @Column(name = "current_h3_index", length = 16)
    private String currentH3Index;

    // How far (in meters) this mechanic is willing to travel — varies per person.
    // Used by PostGIS ST_DWithin in the Stage 2 radius query.
    @Column(name = "service_radius_m", nullable = false)
    @Builder.Default
    private Integer serviceRadiusM = 10000;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Relations

    @OneToMany(mappedBy = "mechanic", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<MechanicSpecialization> specializations;

    @OneToMany(mappedBy = "mechanic", fetch = FetchType.LAZY)
    private List<Feedback> feedbacks;

    @OneToMany(mappedBy = "mechanic", fetch = FetchType.LAZY)
    private List<BidSos> bids;

    @OneToMany(mappedBy = "proposedBy", fetch = FetchType.LAZY)
    private List<SosStatusHistory> proposedHistories;
}

