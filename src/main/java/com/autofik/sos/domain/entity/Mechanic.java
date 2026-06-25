package com.autofik.sos.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
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

    @Column(name = "is_available", nullable = false)
    @Builder.Default
    private Boolean isAvailable = true;

    @Column(name = "rating_avg", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal ratingAvg = BigDecimal.ZERO;

    @Column(name = "base_location", columnDefinition = "geography(Point, 4326)")
    private Point baseLocation;

    @Column(name = "current_location", columnDefinition = "geography(Point, 4326)")
    private Point currentLocation;

    @Column(name = "current_h3_index", length = 16)
    private String currentH3Index;

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

