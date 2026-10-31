package com.autofik.sos.domain.entity;

import com.autofik.sos.domain.enums.UserStatus;
import jakarta.persistence.*;
import java.util.List;
import java.util.UUID;
import lombok.*;

/**
 * Represents an end user (customer) of the platform who can own vehicles, raise SOS roadside
 * assistance requests, and leave feedback for mechanics. Distinct from mechanic/admin accounts,
 * which are handled separately.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class User {

  // Assigned, not generated: this row is a projection of an SSO user and must carry that same
  // UUID. A locally generated id would silently disagree with every other service.
  @Id
  @Column(name = "user_id", updatable = false, nullable = false)
  @ToString.Include
  private UUID userId;

  // Full display name of the user
  @Column(nullable = false)
  @ToString.Include
  private String name;

  // Primary contact number; used for OTP login and must be unique across all users
  @Column(nullable = false, unique = true)
  @ToString.Include
  private String phone;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  @Builder.Default
  // Defaults to ACTIVE — users don't require approval, only mechanics do.
  @ToString.Include
  private UserStatus status = UserStatus.ACTIVE;

  // SSO profile photo URL; synced from the gateway profile.
  @Column(name = "profile_image", length = 500)
  private String profileImage;

  // Used for referral program tracking; generated at registration.
  @Column(name = "referer_code", unique = true)
  @ToString.Include
  private String refererCode;

  // Relations
  // All vehicles registered by this user. Deleting the user cascades to their vehicles.
  @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
  @ToString.Exclude
  private List<Vehicle> vehicles;

  // Full history of SOS requests raised by this user.
  @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
  @ToString.Exclude
  private List<SosRequest> sosRequests;

  // Feedback (ratings/reviews) submitted by this user after completed SOS requests.
  @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
  @ToString.Exclude
  private List<Feedback> feedbacks;

  // SOS status history entries this user approved as an admin/reviewer.
  // Note: this is only populated when the user acts in an approval role
  // (e.g. admin), not for a typical customer account.
  @OneToMany(mappedBy = "approvedBy", fetch = FetchType.LAZY)
  @ToString.Exclude
  private List<SosStatusHistory> approvedHistories;
}
