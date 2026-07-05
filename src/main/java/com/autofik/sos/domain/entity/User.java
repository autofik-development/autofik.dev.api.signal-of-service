package com.autofik.sos.domain.entity;

import com.autofik.sos.domain.enums.UserStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.util.List;
import java.util.UUID;

/**
 * Represents an end user (customer) of the platform who can own vehicles,
 * raise SOS roadside assistance requests, and leave feedback for mechanics.
 * Distinct from mechanic/admin accounts, which are handled separately.
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

    // Primary key, auto-generated as a UUID (not client-supplied, not updatable after creation)
    @Id
    @GeneratedValue
    @UuidGenerator
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
