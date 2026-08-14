package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosMedia;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.enums.MediaType;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosMediaRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The transactional half of a media upload.
 *
 * <p>Kept in its own bean so the S3 PUT that precedes it runs with no Postgres connection checked
 * out — an upload is the slowest thing this service does, and holding a pooled connection for its
 * duration drains the pool exactly when the network is bad. Calling this from within
 * {@link SosMediaServiceImpl} would not work: self-invocation bypasses the proxy, so the boundary
 * has to sit on a different bean to take effect. Same split as
 * {@link MechanicLocationWriter}.
 */
@Component
@RequiredArgsConstructor
public class SosMediaWriter {

    private final SosMediaRepository sosMediaRepository;
    private final SosRequestRepository sosRequestRepository;
    private final UserRepository userRepository;
    private final MechanicRepository mechanicRepository;

    /**
     * @param userId     the uploading driver, or null when a mechanic uploaded
     * @param mechanicId the uploading mechanic, or null when a driver uploaded
     */
    @Transactional
    public SosMedia persist(UUID sosId, UUID userId, UUID mechanicId,
                            String objectKey, String contentType, long sizeBytes) {
        SosRequest sos = sosRequestRepository.findById(sosId)
                .orElseThrow(() -> new EntityNotFoundException("SOS request not found: " + sosId));

        User user = userId == null ? null : userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
        Mechanic mechanic = mechanicId == null ? null : mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));

        return sosMediaRepository.save(SosMedia.builder()
                .sosRequest(sos)
                .mediaType(MediaType.PHOTO)
                .contentType(contentType)
                .sizeBytes(sizeBytes)
                .objectKey(objectKey)
                .uploadedByUser(user)
                .uploadedByMechanic(mechanic)
                .build());
    }
}
