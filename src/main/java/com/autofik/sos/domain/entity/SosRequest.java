package com.autofik.sos.domain.entity;

import com.autofik.sos.domain.enums.SosStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.locationtech.jts.geom.Point;

/**
 * Core domain entity for a user-submitted roadside assistance request.
 * Lifecycle: PENDING → ACCEPTED → ON_THE_WAY → ARRIVED → COMPLETED (or CANCELLED/EXPIRED).
 * The accepted mechanic is NOT stored here — resolve it via the ACCEPTED bid in BidSos.
 */
@Entity
@Table(name = "sos_request")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
// Exclude lazy-loaded collections from toString to avoid triggering DB queries during logging
@ToString(exclude = {"user", "vehicle", "bids", "statusHistory", "feedback", "media"})
public class SosRequest {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "sos_id", updatable = false, nullable = false)
    private UUID sosId;

    @Column(name = "problem_type", nullable = false)
    private String problemType;

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
    @Column(name = "bidding_expires_at", updatable = false)
    private LocalDateTime biddingExpiresAt;

    // 250 seconds (~4 min) for mechanics to respond before the request expires automatically.
    private static final int BIDDING_WINDOW_SECONDS = 250;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @OneToMany(mappedBy = "sosRequest", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<BidSos> bids;

    @OneToMany(mappedBy = "sosRequest", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<SosStatusHistory> statusHistory;

    @OneToOne(mappedBy = "sosRequest", cascade = CascadeType.ALL)
    private Feedback feedback;

    @OneToMany(mappedBy = "sosRequest", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<SosMedia> media;
}