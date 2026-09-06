package com.autofik.sos.domain.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * Aggregate row per mechanic — upserted on every new feedback submission. ratingAvg and ratingCount
 * are recalculated from all feedbacks in FeedbackServiceImpl. Kept as a separate table (rather than
 * computing on the fly) so mechanic rating lookups (e.g. for dispatch/matching) don't require
 * aggregating over the full feedback history on every read.
 */
@Entity
@Table(name = "mechanic_rate")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class MechanicRate {

  // Primary key, auto-generated as a UUID (not client-supplied, not updatable after creation)
  @Id
  @GeneratedValue
  @UuidGenerator
  @Column(name = "id", updatable = false, nullable = false)
  @ToString.Include
  private UUID id;

  // One rate row per mechanic — unique enforced at DB level
  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "mechanic_profile_id", nullable = false, unique = true)
  @ToString.Exclude
  private Mechanic mechanic;

  // Average rating across all feedback for this mechanic, e.g. 4.75 (precision 3, scale 2)
  @Column(name = "rating_avg", precision = 3, scale = 2)
  @ToString.Include
  private BigDecimal ratingAvg;

  // Total number of feedback entries factored into ratingAvg
  @Column(name = "rating_count", nullable = false)
  @Builder.Default
  @ToString.Include
  private Integer ratingCount = 0;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  @ToString.Include
  private LocalDateTime createdAt;

  // Updated on every feedback recalculation — lets you see how stale the cached aggregate is
  @UpdateTimestamp
  @Column(name = "updated_at")
  @ToString.Include
  private LocalDateTime updatedAt;

  public static class MechanicRateBuilder {
    @Override
    public String toString() {
      return "MechanicRate.MechanicRateBuilder(id="
          + this.id
          + ", ratingAvg="
          + this.ratingAvg
          + ", ratingCount="
          + this.ratingCount$value
          + ", createdAt="
          + this.createdAt
          + ")";
    }
  }
}
