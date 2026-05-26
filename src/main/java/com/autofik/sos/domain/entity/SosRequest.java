package com.autofik.sos.domain.entity;

import com.autofik.sos.domain.Enum.SosStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "sos_request")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@ToString(exclude = {"user", "vehicle", "bids", "statusHistory", "feedback"})
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

    @Column(columnDefinition = "geography(Point, 4326)", nullable = false)
    private Point location;

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
}