package com.autofik.sos.domain.entity;

import com.autofik.sos.domain.enums.BidStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "bid_sos")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@ToString(exclude = {"sosRequest", "mechanic"})
public class BidSos {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "bid_id", updatable = false, nullable = false)
    private UUID bidId;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private BidStatus status = BidStatus.PENDING;

    @Column(name = "bid_at", updatable = false)
    private LocalDateTime bidAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @PrePersist
    protected void onCreate() {
        bidAt = LocalDateTime.now();
    }

    // Relations
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sos_id", nullable = false)
    private SosRequest sosRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mechanic_id", nullable = false)
    private Mechanic mechanic;
}
