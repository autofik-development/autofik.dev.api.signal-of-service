package com.autofik.sos.domain.entity;

import com.autofik.sos.domain.enums.MediaType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single photo or video attached to an {@link SosRequest}, uploaded either
 * by the requesting user (e.g. showing the vehicle issue) or by the assigned
 * mechanic (e.g. for arrival/completion documentation and dispute resolution).
 * The actual file is stored in object storage (S3-compatible); this row only
 * tracks metadata and the object key.
 */
@Entity
@Table(name = "sos_media")
@Check(constraints = "uploaded_by_user IS NOT NULL OR uploaded_by_mechanic IS NOT NULL")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
@ToString(exclude = {"sosRequest", "uploadedByUser", "uploadedByMechanic"})
public class SosMedia {

    // Primary key, auto-generated as a UUID (not client-supplied, not updatable after creation)
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "media_id", updatable = false, nullable = false)
    private UUID mediaId;

    // PHOTO or VIDEO
    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false)
    private MediaType mediaType;

    // Key/path of the file in object storage (e.g. S3 bucket key), used to build the download/view URL
    @Column(name = "object_key", nullable = false)
    private String objectKey;

    // MIME type of the uploaded file, e.g. "image/jpeg", "video/mp4"
    @Column(name = "content_type", nullable = false)
    private String contentType;

    // File size in bytes, useful for storage accounting and upload validation
    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "uploaded_at", updatable = false)
    private LocalDateTime uploadedAt;

    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
    }

    // The SOS request this media belongs to
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sos_id", nullable = false)
    private SosRequest sosRequest;

    // Either the user or the mechanic uploaded this — never both, never neither (enforced by @Check above)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_user")
    private User uploadedByUser;

    // Set instead of uploadedByUser when the mechanic is the one who uploaded this media
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_mechanic")
    private Mechanic uploadedByMechanic;
}
