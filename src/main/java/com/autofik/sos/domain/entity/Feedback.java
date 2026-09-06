package com.autofik.sos.domain.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.UuidGenerator;

/*
 * Domain entity representing the Feedback/Review given after an SOS service is completed.
 * Maps directly to the 'feedback' table in the database.
 */
@Entity // Tells JPA this class represents a database table
@Table(name = "feedback") // Sets the exact table name in the DB to 'feedback'
// Hibernate Database Check: Ensures the database itself rejects any rating that is not between 1.0
// and 5.0
@Check(constraints = "rating >= 1.0 AND rating <= 5.0")
@Getter
@Setter // lombok Generate getters and setters for all columns
@NoArgsConstructor
@AllArgsConstructor // Lombok: Generates standard empty and full constructors
@Builder // Lombok: Provides a clean way to instantiate a Feedback object
@ToString // Lombok: Generates a toString method, but respects individual @ToString.Exclude tags
// below
public class Feedback {

  // Primary key, auto-generated as a UUID (not client-supplied, not updatable after creation)
  @Id
  @GeneratedValue
  @UuidGenerator
  @Column(updatable = false, nullable = false)
  private UUID id;

  // Optional free-text review left by the user; rating alone is sufficient to submit feedback
  @Column(columnDefinition = "TEXT")
  private String comment;

  // Star rating from 1.0 to 5.0 (enforced at DB level via @Check above)
  @Column(nullable = false, precision = 2, scale = 1)
  private BigDecimal rating;

  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
  }

  // Relations
  // The user who submitted this feedback
  @ToString.Exclude
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  // The SOS request this feedback is for. One feedback per request (enforced via unique
  // constraint).
  @ToString.Exclude
  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "sos_id", nullable = false, unique = true)
  private SosRequest sosRequest;

  // The mechanic being rated/reviewed
  @ToString.Exclude
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "mechanic_id", nullable = false)
  private Mechanic mechanic;
}
