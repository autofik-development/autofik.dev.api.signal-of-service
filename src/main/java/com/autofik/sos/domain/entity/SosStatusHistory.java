package com.autofik.sos.domain.entity;

import com.autofik.sos.domain.Enum.ApprovedStatus;
import com.autofik.sos.domain.Enum.SosStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sos_status_history")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class SosStatusHistory {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "change_at", nullable = false, updatable = false)
    private LocalDateTime changeAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false)
    private SosStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private SosStatus toStatus;

    @Column(name = "proposed_at")
    private LocalDateTime proposedAt;

    @PrePersist
    protected void onCreate() {
        changeAt = LocalDateTime.now();
    }

    @Column(name = "approve_at")
    private LocalDateTime approveAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "approved_status")
    private ApprovedStatus approvedStatus;

    // Mechanic always proposes the status change
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposed_by", nullable = false)
    private Mechanic proposedBy;

    // User always approves or rejects it
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sos_id", nullable = false)
    private SosRequest sosRequest;

    @Override
    public String toString() {
        return "SosStatusHistory{" +
                "id=" + id +
                ", changeAt=" + changeAt +
                ", fromStatus=" + fromStatus +
                ", toStatus=" + toStatus +
                ", proposedAt=" + proposedAt +
                ", approveAt=" + approveAt +
                ", approvedStatus=" + approvedStatus +
                ", proposedById=" + (proposedBy != null ? proposedBy.getMechanicId() : null) +
                ", approvedById=" + (approvedBy != null ? approvedBy.getUserId() : null) +
                ", sosRequestId=" + (sosRequest != null ? sosRequest.getSosId() : null) +
                '}';
    }
}
