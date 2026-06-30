package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosMedia;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.enums.MediaType;
import com.autofik.sos.dto.response.SosMediaResponse;
import com.autofik.sos.mapper.SosMediaMapper;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosMediaRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.SosMediaService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SosMediaServiceImpl implements SosMediaService {

    private static final Duration PLAYBACK_URL_TTL = Duration.ofMinutes(15);

    private final SosMediaRepository sosMediaRepository;
    private final SosRequestRepository sosRequestRepository;
    private final UserRepository userRepository;
    private final MechanicRepository mechanicRepository;
    private final SosMediaMapper sosMediaMapper;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Override
    @Transactional
    public SosMediaResponse upload(UUID sosId, MultipartFile file, UUID userId, UUID mechanicId) {
        if ((userId == null) == (mechanicId == null)) {
            throw new IllegalStateException("Exactly one of userId or mechanicId must be provided");
        }
        SosRequest sos = sosRequestRepository.findById(sosId)
                .orElseThrow(() -> new EntityNotFoundException("SOS request not found: " + sosId));
        MediaType mediaType = resolveMediaType(file.getContentType());

        SosMedia.SosMediaBuilder mediaBuilder = SosMedia.builder()
                .sosRequest(sos)
                .mediaType(mediaType)
                .contentType(file.getContentType())
                .sizeBytes(file.getSize());

        if (userId != null) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
            mediaBuilder.uploadedByUser(user);
        } else {
            Mechanic mechanic = mechanicRepository.findById(mechanicId)
                    .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
            mediaBuilder.uploadedByMechanic(mechanic);
        }

        String objectKey = buildObjectKey(sosId, file.getOriginalFilename());
        uploadToS3(objectKey, file);
        mediaBuilder.objectKey(objectKey);

        SosMedia saved = sosMediaRepository.save(mediaBuilder.build());
        return toResponseWithUrl(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SosMediaResponse> getBySosRequest(UUID sosId) {
        return sosMediaRepository.findBySosRequestSosId(sosId).stream()
                .map(this::toResponseWithUrl)
                .toList();
    }

    private MediaType resolveMediaType(String contentType) {
        if (contentType == null) {
            throw new IllegalStateException("Missing content type for uploaded file");
        }
        if (contentType.startsWith("image/")) {
            return MediaType.PHOTO;
        }
        if (contentType.startsWith("video/")) {
            return MediaType.VIDEO;
        }
        throw new IllegalStateException("Unsupported content type: " + contentType);
    }

    private String buildObjectKey(UUID sosId, String originalFilename) {
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf('.'));
        }
        return "sos/" + sosId + "/" + UUID.randomUUID() + extension;
    }

    private void uploadToS3(String objectKey, MultipartFile file) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize())
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
    }

    private SosMediaResponse toResponseWithUrl(SosMedia media) {
        SosMediaResponse base = sosMediaMapper.toResponse(media);
        String url = presignGetUrl(media.getObjectKey());
        return new SosMediaResponse(
                base.mediaId(), base.mediaType(), base.contentType(), base.sizeBytes(), url, base.uploadedAt()
        );
    }

    private String presignGetUrl(String objectKey) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(PLAYBACK_URL_TTL)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }
}
