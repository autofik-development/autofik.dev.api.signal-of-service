package com.autofik.sos.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Aggregate row per mechanic — upserted on every new feedback submission.
 * ratingAvg and ratingCount are recalculated from all feedbacks in FeedbackServiceImpl.
 * Kept as a separate table (rather than computing on the fly) so mechanic
 * rating lookups (e.g. for dispatch/matching) don't require aggregating
 * over the full feedback history on every read.
 */
@Entity
@Table(name = "mechanic_rate")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class MechanicRate {

    // Primary key, auto-generated as a UUID (not client-supplied, not updatable after creation)
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // The mechanic this aggregate rating belongs to (expected one row per mechanic)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mechanic_profile_id", nullable = false)
    private Mechanic mechanic;

    // Average rating across all feedback for this mechanic, e.g. 4.75 (precision 3, scale 2)
    @Column(name = "rating_avg", precision = 3, scale = 2)
    private BigDecimal ratingAvg;

    // Total number of feedback entries factored into ratingAvg
    @Column(name = "rating_count", nullable = false)
    @Builder.Default
    private Integer ratingCount = 0;
    
    // When this aggregate row was first created for the mechanic
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
