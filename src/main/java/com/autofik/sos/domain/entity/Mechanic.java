package com.autofik.sos.domain.entity;

import com.autofik.sos.domain.enums.ApprovalStatus;
import com.autofik.sos.domain.enums.MechanicRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import org.locationtech.jts.geom.Point;

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
    @Column(name = "id", updatable = false, nullable = false)
    private UUID mechanicId;

    // Links this mechanic profile to a platform user account (SSO).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "full_name", length = 30, nullable = false)
    private String fullName;

    @Column(name = "phone_number", length = 20, nullable = false)
    private String phoneNumber;

    @Column(length = 30)
    private String email;

    // under_garage = works at a fixed shop; freeland = mobile/independent.
    @Enumerated(EnumType.STRING)
    @Column(name = "mechanic_role")
    private MechanicRole mechanicRole;

    @Column(name = "referral_code", unique = true)
    private String referralCode;

    // Identity document check (ID card, license) — done during onboarding.
    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false)
    @Builder.Default
    private ApprovalStatus kycStatus = ApprovalStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String address;

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

    // Coverage area in km; multiplied by 1000 when passed to PostGIS ST_DWithin (which expects meters).
    @Column(name = "service_radius_km", nullable = false)
    @Builder.Default
    private Integer serviceRadiusKm = 10;

    @Column(name = "available_status", nullable = false)
    @Builder.Default
    private Boolean availableStatus = true;

    // Admin verification of mechanic credentials/skills — separate from KYC.
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    @Builder.Default
    private ApprovalStatus verificationStatus = ApprovalStatus.PENDING;

    // Relations
    @OneToMany(mappedBy = "mechanic", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<MechanicSkill> skills;

    @OneToMany(mappedBy = "mechanic", fetch = FetchType.LAZY)
    private List<Feedback> feedbacks;

    @OneToMany(mappedBy = "mechanic", fetch = FetchType.LAZY)
    private List<BidSos> bids;

    @OneToMany(mappedBy = "proposedBy", fetch = FetchType.LAZY)
    private List<SosStatusHistory> proposedHistories;
}
