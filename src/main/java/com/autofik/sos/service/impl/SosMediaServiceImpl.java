package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.SosMedia;
import com.autofik.sos.dto.event.SosMediaUploadedEvent;
import com.autofik.sos.dto.response.SosMediaResponse;
import com.autofik.sos.exception.ConflictException;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.SosMediaMapper;
import com.autofik.sos.repository.SosMediaRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.service.SosMediaService;
import com.autofik.sos.service.SosService;
import org.springframework.security.access.AccessDeniedException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Dimension;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of {@link SosMediaService} handling secure media uploads,
 * image normalization (processing/compression), AWS S3 storage, and event streaming.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SosMediaServiceImpl implements SosMediaService {

    // Presigned URL expiration window
    private static final Duration PLAYBACK_URL_TTL = Duration.ofMinutes(15);

    // Everything is transcoded to JPEG on the way in, whatever it arrived as.
    private static final String JPEG_CONTENT_TYPE = "image/jpeg";

    // Strict upload file size ceiling (10MB)
    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    // Target maximum dimension bounding box for resized images — sized for mobile
    // viewing, not print/zoom detail
    private static final int MAX_IMAGE_DIMENSION = 1280;
    // Hard ceiling on source pixel dimensions, checked before decode. Guards against
    // decompression-bomb uploads (small file, huge declared dimensions) exhausting memory.
    private static final int MAX_SOURCE_DIMENSION = 8000;

    // JPEG compression quality metric (80%) balancing clarity and bandwidth efficiency
    private static final double JPEG_QUALITY = 0.80;

    // Strict whitelist of permitted MIME types mapped to their uniform extensions
    private static final Map<String, String> ALLOWED_CONTENT_TYPES = Map.of(
            "image/jpeg", ".jpg",
            "image/png",  ".png"
    );

    private final SosMediaRepository sosMediaRepository;
    private final SosRequestRepository sosRequestRepository;
    private final SosService sosService;   // reuses the one definition of "who may see this SOS"
    private final SosMediaWriter sosMediaWriter;
    private final SosMediaMapper sosMediaMapper;
    private final SosEventProducer sosEventProducer;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucket;

    /**
     * Validates, processes, compresses, and uploads an SOS emergency media file.
     * Inserts metadata into the local DB and publishes an event notification to Kafka.
     *
     * @param sosId      The identity of the target SOS emergency request.
     * @param file       The raw multi-part file from the request payload.
     * @param userId     The identity of the user uploader (null if uploaded by mechanic).
     * @param mechanicId The identity of the mechanic uploader (null if uploaded by user).
     * @return Formatted media response object including a short-lived S3 access URL.
     * @throws ConflictException        If neither or both actor contexts are passed.
     * @throws IllegalArgumentException If file structural validation, size limits, or magic bytes check fail.
     */
    @Override
    public SosMediaResponse upload(UUID sosId, MultipartFile file, UUID userId, UUID mechanicId) {

        // Enforce XOR logic: An upload must strictly be tied to either a User OR a Mechanic context
        if ((userId == null) == (mechanicId == null)) {
            throw new ConflictException("Exactly one of userId or mechanicId must be provided");
        }

        // Phase 1: Basic validation filters
        validateContentType(file.getContentType());
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "File exceeds the " + (MAX_SIZE_BYTES / (1024 * 1024)) + " MB limit");
        }

        // Phase 2: the request has to exist, and the uploader has to be part of it — otherwise
        // anyone holding a sosId can push images into someone else's incident.
        if (!sosRequestRepository.existsById(sosId)) {
            throw new EntityNotFoundException("SOS request not found: " + sosId);
        }
        UUID uploadedById = userId != null ? userId : mechanicId;
        if (!sosService.canView(sosId, uploadedById)) {
            throw new AccessDeniedException("You are not part of this SOS request");
        }

        // Phase 3: Secure media transformation
        byte[] compressed;
        try {
            byte[] raw = file.getBytes();
            // Guard against spoofed extensions by inspecting early bytes
            verifyMagicBytes(raw, file.getContentType());
            // Normalize image bounds and transcode directly to JPEG
            compressed = compressToJpeg(raw);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to process uploaded file", e);
        }

        // Phase 4: S3 first, then the row. Deliberately outside a transaction — see SosMediaWriter.
        String objectKey = "sos/" + sosId + "/" + UUID.randomUUID() + ".jpg";
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(JPEG_CONTENT_TYPE).build(),
                RequestBody.fromBytes(compressed));

        // Phase 5: metadata row. S3 does not roll back with Postgres, so a failed insert has to
        // take its object with it — otherwise every failure leaves a paid-for orphan nothing
        // references.
        SosMedia saved;
        try {
            saved = sosMediaWriter.persist(sosId, userId, mechanicId,
                    objectKey, JPEG_CONTENT_TYPE, compressed.length);
        } catch (RuntimeException e) {
            deleteQuietly(objectKey);
            throw e;
        }

        // Phase 6: Event Notification Propagation (Kafka)
        sosEventProducer.publishMediaUploaded(new SosMediaUploadedEvent(
                sosId, saved.getMediaId(), userId != null ? "USER" : "MECHANIC",
                uploadedById, saved.getUploadedAt()));

        return toResponseWithUrl(saved);
    }

    // Best-effort: the row insert already failed and that is what the caller is being told about.
    // A failure to clean up leaves an orphan, which is a storage cost, not a correctness problem.
    private void deleteQuietly(String objectKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (RuntimeException cleanupFailure) {
            log.warn("Orphaned S3 object {} — metadata insert failed and cleanup did not succeed: {}",
                    objectKey, cleanupFailure.getMessage());
        }
    }

    /**
     * Retrieves all media assets attached to a given SOS request identifier.
     *
     * @param sosId Target SOS request identifier.
     * @return List of mapped media records enriched with fresh S3 presigned URLs.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SosMediaResponse> getBySosRequest(UUID sosId, UUID callerId) {
        if (!sosService.canView(sosId, callerId)) {
            throw new AccessDeniedException("You are not part of this SOS request");
        }
        return sosMediaRepository.findBySosRequestSosId(sosId).stream()
                .map(this::toResponseWithUrl)
                .toList();
    }

    /**
     * Confirms that the HTTP header content-type matches whitelist protocols.
     */
    private void validateContentType(String contentType) {
        if (contentType == null || !ALLOWED_CONTENT_TYPES.containsKey(contentType)) {
            throw new IllegalArgumentException(
                    "Unsupported content type: " + contentType + ". Allowed: " + ALLOWED_CONTENT_TYPES.keySet());
        }
    }

    /**
     * Inspects binary signatures (Magic Bytes) to stop users from masking malicious binaries
     * or alternative files behind trusted extensions like .jpg/.png.
     */
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

    /**
     * Evaluates boundaries, resizes if image limits are exceeded, and returns
     * an optimized JPEG byte payload.
     */
    private byte[] compressToJpeg(byte[] raw) throws IOException {
        Dimension size = readDimensions(raw);
        // Pre-decode safety check: intercepts malicious payloads attempting to exhaust JVM memory
        if (size.width > MAX_SOURCE_DIMENSION || size.height > MAX_SOURCE_DIMENSION) {
            throw new IllegalArgumentException(
                    "Image dimensions " + size.width + "x" + size.height
                            + " exceed the " + MAX_SOURCE_DIMENSION + "x" + MAX_SOURCE_DIMENSION + " limit");
        }
        var builder = Thumbnails.of(new ByteArrayInputStream(raw))
                .outputQuality(JPEG_QUALITY)
                .outputFormat("jpeg");
        if (size.width > MAX_IMAGE_DIMENSION || size.height > MAX_IMAGE_DIMENSION) {
            // Scales down proportionately to fit within max bounds
            builder.size(MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION).keepAspectRatio(true);
        } else {
            // Already within bounds — re-encode as JPEG without upscaling.
            builder.scale(1.0);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        builder.toOutputStream(out);
        return out.toByteArray();
    }

    /**
     * Reads image dimensions using metadata stream parsing instead of reading all pixels into memory,
     * protecting application threads against heap strain.
     */
    private Dimension readDimensions(byte[] raw) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IOException("No image reader available for uploaded file");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                return new Dimension(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose(); // Explicit memory cleanup for native ImageReader hooks
            }
        }
    }

    /**
     * Maps database domain entities to service response contracts containing fresh playback URLs.
     */
    private SosMediaResponse toResponseWithUrl(SosMedia media) {
        SosMediaResponse base = sosMediaMapper.toResponse(media);
        String url = presignGetUrl(media.getObjectKey());
        return new SosMediaResponse(
                base.mediaId(), base.mediaType(), base.contentType(), base.sizeBytes(), url, base.uploadedAt());
    }

    /**
     * Generates a secure, signed URL providing read permissions to an isolated S3 asset.
     */
    private String presignGetUrl(String objectKey) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(PLAYBACK_URL_TTL)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }
}
