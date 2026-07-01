package com.autofik.sos.domain.entity;

import com.autofik.sos.domain.enums.BidStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;



/*
* Domain entity representation a Mechanic's bid response to an emergency SOS request.
* Maps directly to the 'bid_sos' table in the database
* */
@Entity // tell JPA that this class is a database model/table
@Table(name = "bid_sos") // Specifies the exact database table name
@Getter @Setter // Lombok: Automatically generates getter and setter for all fields
@NoArgsConstructor @AllArgsConstructor // Lombok: Generates both empty and all-argument constructors
@Builder // Lombok: Implements the Builder Pattern for clean object creation
@ToString(exclude = {"sosRequest", "mechanic"}) // Prevents infinite loops/StackOverflow during logging by excluding lazy relations
public class BidSos {

    // --- IDENTIFIER ---
    @Id // Marks this field as the Primary Key
    @GeneratedValue // Dictates that the ID is automatically generated
    @UuidGenerator  // Hibernate specific: Automatically generates a secure, random UUID (v4)
    @Column(name = "bid_id", updatable = false, nullable = false) // Maps to 'bid_id' column; cannot be modified later or be null
    private UUID bidId;

    // Financial amount proposed by the mechanic. Using BigDecimal avoids floating-point rounding errors.
    // precision 10, scale 2 means: max 10 digits total, with 2 digits after the decimal point (e.g., 99999999.99)
    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    // Tracks the state of the bid (e.g., PENDING, ACCEPTED, REJECTED)
    @Enumerated(EnumType.STRING) // Saves the enum as a String text (e.g., "PENDING") in the DB instead of an integer index (0, 1)
    @Column(name = "status", nullable = false)
    @Builder.Default // Ensures that if you use the @Builder to make a Bid, it defaults to PENDING if not specified
    private BidStatus status = BidStatus.PENDING;

    // --- TIMESTAMPS ---
    @Column(name = "bid_at", updatable = false) // Time when the mechanic submitted the bid; immutable after creation
    private LocalDateTime bidAt;

    @Column(name = "responded_at") // Time when the user/driver accepted or rejected this bid
    private LocalDateTime respondedAt;

    // --- ROUTING / MATRIX ESTIMATIONS ---
    // These fields store raw metrics and user-friendly text, likely derived from a maps API (like Google Maps)
    @Column(name = "estimated_distance_m") // Distance in meters (e.g., 5200)
    private Long estimatedDistanceM;

    @Column(name = "estimated_duration_s") // Duration in seconds (e.g., 600)
    private Long estimatedDurationS;

    @Column(name = "estimated_distance_text") // Formatted distance text for UI
    private String estimatedDistanceText;

    @Column(name = "estimated_duration_text") // Formatted duration text for UI
    private String estimatedDurationText;

    // --- LIFECYCLE HOOKS --
    @PrePersist   // JPA Lifecycle callback: Automatically executes right before this record is inserted into the DB
    protected void onCreate() {
        bidAt = LocalDateTime.now(); // Automatically sets the current server time as the creation date
    }

    // --- RELATION ---
    // Many bids can belong to one single SOS Request.
    // FetchType.LAZY means: Don't load the massive SOS request data from the database unless code explicitly calls .getSosRequest()
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sos_id", nullable = false)
    private SosRequest sosRequest; // Foreign Key column linking to the 'sos_request' table

    // Many bids can be submitted by the same human Mechanic.
    // FetchType.LAZY prevents pulling mechanic profiles until specifically needed.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mechanic_id", nullable = false) // Foreign Key column linking to the 'mechanic' table
    private Mechanic mechanic;
}
