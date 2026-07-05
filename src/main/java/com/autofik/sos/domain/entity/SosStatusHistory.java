package com.autofik.sos.domain.entity;

import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.SosStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Audit trail of status transitions for an {@link SosRequest}.
 * A mechanic proposes a status change (e.g. IN_PROGRESS -> COMPLETED),
 * and the requesting user approves or rejects it. Each row represents
 * one proposed transition and its eventual approval outcome.
 */
@Entity
@Table(name = "sos_status_history")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class SosStatusHistory {

    // Primary key, auto-generated as a UUID (not client-supplied, not updatable after creation)
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    // Timestamp this history row was created (i.e. when the change was recorded).
    // Not the same as proposedAt/approveAt below — set automatically via @PrePersist.
    @Column(name = "change_at", nullable = false, updatable = false)
    private LocalDateTime changeAt;

    // The status the SOS request was in before this transition
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false)
    private SosStatus fromStatus;

    // The status the mechanic is proposing to move the SOS request to
    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private SosStatus toStatus;

    // When the mechanic proposed this status change
    @Column(name = "proposed_at")
    private LocalDateTime proposedAt;

    @PrePersist
    protected void onCreate() {
        changeAt = LocalDateTime.now();
    }

    // When the user approved/rejected the proposed change
    @Column(name = "approve_at")
    private LocalDateTime approveAt;

    // Outcome of the user's decision on the proposed change (e.g. APPROVED, REJECTED, PENDING)
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

    // The SOS request this status transition belongs t
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sos_id", nullable = false)
    private SosRequest sosRequest;

    // Custom toString avoids lazy-loading full related entities;
    // only pulls their IDs to prevent accidental N+1 / LazyInitializationException
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
