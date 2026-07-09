package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosMedia;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.enums.MediaType;
import com.autofik.sos.dto.event.SosMediaUploadedEvent;
import com.autofik.sos.dto.response.SosMediaResponse;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.SosMediaMapper;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosMediaRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.SosMediaService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import net.coobird.thumbnailator.Thumbnails;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SosMediaServiceImpl implements SosMediaService {

    private static final Duration PLAYBACK_URL_TTL = Duration.ofMinutes(15);
    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_IMAGE_DIMENSION = 1920;
    private static final double JPEG_QUALITY = 0.80;

    private static final Map<String, String> ALLOWED_CONTENT_TYPES = Map.of(
            "image/jpeg", ".jpg",
            "image/png",  ".png"
    );

    private final SosMediaRepository sosMediaRepository;
    private final SosRequestRepository sosRequestRepository;
    private final UserRepository userRepository;
    private final MechanicRepository mechanicRepository;
    private final SosMediaMapper sosMediaMapper;
    private final SosEventProducer sosEventProducer;
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

        validateContentType(file.getContentType());
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "File exceeds the " + (MAX_SIZE_BYTES / (1024 * 1024)) + " MB limit");
        }

        SosRequest sos = sosRequestRepository.findById(sosId)
                .orElseThrow(() -> new EntityNotFoundException("SOS request not found: " + sosId));

        User user = null;
        Mechanic mechanic = null;
        if (userId != null) {
            user = userRepository.findById(userId)
                    .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
        } else {
            mechanic = mechanicRepository.findById(mechanicId)
                    .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
        }

        byte[] compressed;
        try {
            byte[] raw = file.getBytes();
            verifyMagicBytes(raw, file.getContentType());
            compressed = compressToJpeg(raw);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to process uploaded file", e);
        }

        String objectKey = "sos/" + sosId + "/" + UUID.randomUUID() + ".jpg";
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType("image/jpeg").build(),
                RequestBody.fromBytes(compressed));

        SosMedia saved = sosMediaRepository.save(SosMedia.builder()
                .sosRequest(sos)
                .mediaType(MediaType.PHOTO)
                .contentType("image/jpeg")
                .sizeBytes((long) compressed.length)
                .objectKey(objectKey)
                .uploadedByUser(user)
                .uploadedByMechanic(mechanic)
                .build());

        UUID uploadedById = user != null ? user.getUserId() : mechanic.getMechanicId();
        String uploadedByRole = user != null ? "USER" : "MECHANIC";
        sosEventProducer.publishMediaUploaded(
                new SosMediaUploadedEvent(sosId, saved.getMediaId(), uploadedByRole, uploadedById, saved.getUploadedAt()));

        return toResponseWithUrl(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SosMediaResponse> getBySosRequest(UUID sosId) {
        return sosMediaRepository.findBySosRequestSosId(sosId).stream()
                .map(this::toResponseWithUrl)
                .toList();
    }

    private void validateContentType(String contentType) {
        if (contentType == null || !ALLOWED_CONTENT_TYPES.containsKey(contentType)) {
            throw new IllegalArgumentException(
                    "Unsupported content type: " + contentType + ". Allowed: " + ALLOWED_CONTENT_TYPES.keySet());
        }
    }

    private void verifyMagicBytes(byte[] data, String contentType) {
        boolean valid = switch (contentType) {
            case "image/jpeg" -> data.length >= 3
                    && (data[0] & 0xFF) == 0xFF
                    && (data[1] & 0xFF) == 0xD8
                    && (data[2] & 0xFF) == 0xFF;
            case "image/png"  -> data.length >= 8
                    && (data[0] & 0xFF) == 0x89
                    && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                    && data[4] == '\r' && data[5] == '\n'
                    && (data[6] & 0xFF) == 0x1A && data[7] == '\n';
            default -> true;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "File content does not match declared type: " + contentType);
        }
    }

    private byte[] compressToJpeg(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thumbnails.of(new ByteArrayInputStream(raw))
                .size(MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION)
                .keepAspectRatio(true)
                .outputQuality(JPEG_QUALITY)
                .outputFormat("jpeg")
                .toOutputStream(out);
        return out.toByteArray();
    }

    private SosMediaResponse toResponseWithUrl(SosMedia media) {
        SosMediaResponse base = sosMediaMapper.toResponse(media);
        String url = presignGetUrl(media.getObjectKey());
        return new SosMediaResponse(
                base.mediaId(), base.mediaType(), base.contentType(), base.sizeBytes(), url, base.uploadedAt());
    }

    private String presignGetUrl(String objectKey) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(PLAYBACK_URL_TTL)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }
}
