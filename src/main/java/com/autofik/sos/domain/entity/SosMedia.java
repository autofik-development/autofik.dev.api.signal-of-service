package com.autofik.sos.domain.entity;

import com.autofik.sos.domain.enums.MediaType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sos_media")
@Check(constraints = "uploaded_by_user IS NOT NULL OR uploaded_by_mechanic IS NOT NULL")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@ToString(exclude = {"sosRequest", "uploadedByUser", "uploadedByMechanic"})
public class SosMedia {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "media_id", updatable = false, nullable = false)
    private UUID mediaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false)
    private MediaType mediaType;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "uploaded_at", updatable = false)
    private LocalDateTime uploadedAt;

    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sos_id", nullable = false)
    private SosRequest sosRequest;

    // Either the user or the mechanic uploaded this — never both, never neither (enforced by @Check above)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_user")
    private User uploadedByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_mechanic")
    private Mechanic uploadedByMechanic;
}
