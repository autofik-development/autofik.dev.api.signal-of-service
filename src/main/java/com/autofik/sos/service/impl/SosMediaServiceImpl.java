package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosMedia;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.MediaType;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.event.SosMediaUploadedEvent;
import com.autofik.sos.dto.response.SosMediaResponse;
import com.autofik.sos.exception.BadRequestException;
import com.autofik.sos.exception.ConflictException;
import com.autofik.sos.exception.ServiceUnavailableException;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.SosMediaMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosMediaRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.SosMediaService;
import com.autofik.sos.service.SosService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.persistence.EntityNotFoundException;
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
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Implementation of {@link SosMediaService} handling secure media uploads, image normalization
 * (processing/compression), AWS S3 storage, and event streaming.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SosMediaServiceImpl implements SosMediaService {

  // Strict upload file size ceiling. Same env var drives spring.servlet.multipart limits.
  @Value("${sos.media.max-upload-mb:10}")
  private long maxUploadMb;

  // Target maximum dimension bounding box for resized images — sized for mobile
  // viewing, not print/zoom detail
  @Value("${sos.media.max-image-dimension:1280}")
  private int maxImageDimension;

  // Hard ceiling on source pixel dimensions, checked before decode. Guards against
  // decompression-bomb uploads (small file, huge declared dimensions) exhausting memory.
  @Value("${sos.media.max-source-dimension:8000}")
  private int maxSourceDimension;

  // JPEG compression quality (0.0-1.0) balancing clarity and bandwidth efficiency
  @Value("${sos.media.jpeg-quality:0.80}")
  private double jpegQuality;

  // Strict whitelist of permitted MIME types mapped to their uniform extensions
  private static final Map<String, String> ALLOWED_CONTENT_TYPES =
      Map.of(
          "image/jpeg", ".jpg",
          "image/png", ".png");

  private final SosMediaRepository sosMediaRepository;
  private final SosRequestRepository sosRequestRepository;
  private final BidSosRepository bidSosRepository;
  private final SosService sosService; // reuses the one definition of "who may see this SOS"
  private final UserRepository userRepository;
  private final MechanicRepository mechanicRepository;
  private final SosMediaMapper sosMediaMapper;
  private final SosEventProducer sosEventProducer;
  private final S3Client s3Client;
  private final S3Presigner s3Presigner;
  private final TransactionTemplate transactionTemplate;

  @Value("${aws.s3.bucket}")
  private String bucket;

  // Photos attached to the fan-out payload sent to every nearby mechanic
  // Presigned URL expiration window. Broadcast photo links stop working after this.
  @Value("${aws.s3.presign-ttl-minutes:15}")
  private int presignTtlMinutes;

  @Value("${sos.broadcast.max-photos:3}")
  private int maxBroadcastPhotos;

  // Photos kept per ticket. ponytail: counted before the insert, so two uploads at the same moment
  // can overshoot by one; a lock on the ticket row if that ever matters.
  @Value("${sos.media.max-per-sos:10}")
  private int maxPerSos;

  // Tickets that are over: no more photos, whoever asks.
  private static final java.util.Set<SosStatus> CLOSED_STATUSES =
      java.util.EnumSet.of(SosStatus.COMPLETED, SosStatus.CANCELLED, SosStatus.EXPIRED);

  // Set by Spring; stays NOOP when the class is built by hand (tests), which records nothing.
  @Autowired(required = false)
  ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

  // The S3 SDK has no tracing of its own, so each call is a span here. Name only: the bucket and
  // the object key stay off the span.
  static final String S3_PUT_OBJECT = "s3.put-object";
  static final String S3_PRESIGN = "s3.presign-get-object";

  /**
   * Validates, processes, compresses, and uploads an SOS emergency media file. Inserts metadata
   * into the local DB and publishes an event notification to Kafka.
   *
   * @param sosId The identity of the target SOS emergency request.
   * @param file The raw multi-part file from the request payload.
   * @param userId The identity of the user uploader (null if uploaded by mechanic).
   * @param mechanicId The identity of the mechanic uploader (null if uploaded by user).
   * @return Formatted media response object including a short-lived S3 access URL.
   * @throws IllegalArgumentException If neither or both actor contexts are passed, or file
   *     structural validation, size limits, or magic bytes check fail.
   */
  // Deliberately NOT @Transactional: the S3 PUT is a network call and would hold a Postgres
  // connection for its whole duration. Only the two database phases run in a transaction, as in
  // SosServiceImpl.create; the object is deleted again if the second one fails.
  @Override
  public SosMediaResponse upload(UUID sosId, MultipartFile file, UUID userId, UUID mechanicId) {

    // Enforce XOR logic: An upload must strictly be tied to either a User OR a Mechanic context
    if ((userId == null) == (mechanicId == null)) {
      throw new IllegalArgumentException("Exactly one of userId or mechanicId must be provided");
    }

    // Phase 1: Basic validation filters
    validateContentType(file.getContentType());
    if (file.getSize() > maxUploadMb * 1024 * 1024) {
      throw new MaxUploadSizeExceededException(maxUploadMb * 1024 * 1024);
    }

    // Phase 2: Domain Entity Verification (short transaction)
    Uploader uploader =
        transactionTemplate.execute(status -> loadUploader(sosId, userId, mechanicId));

    // Phase 3: Secure media transformation (no transaction)
    byte[] raw;
    try {
      raw = file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read uploaded file", e);
    }
    // Guard against spoofed extensions by inspecting early bytes
    verifyMagicBytes(raw, file.getContentType());
    byte[] compressed;
    try {
      // Normalize image bounds and transcode directly to JPEG
      compressed = compressToJpeg(raw);
    } catch (IOException e) {
      // The bytes passed the signature check but do not decode: the client's file is bad (400),
      // not our failure (500).
      throw new BadRequestException("File is not a readable image", e);
    }

    // Phase 4: S3 Object Storage Persistence (no transaction)
    String objectKey = "sos/" + sosId + "/" + UUID.randomUUID() + ".jpg";
    try {
      Observation.createNotStarted(S3_PUT_OBJECT, observationRegistry)
          .observe(
              () ->
                  s3Client.putObject(
                      PutObjectRequest.builder()
                          .bucket(bucket)
                          .key(objectKey)
                          .contentType("image/jpeg")
                          .build(),
                      RequestBody.fromBytes(compressed)));
    } catch (SdkException e) {
      // Storage down, slow or refusing us: the client's photo is fine, so say "try again" (503)
      // rather than answer 500.
      log.error("S3 upload failed for SOS {}: {}", sosId, e.getMessage());
      throw new ServiceUnavailableException("Photo storage is temporarily unavailable");
    }

    // Phase 5: metadata insert and event (short transaction). A failure here would orphan the
    // object, so it is removed before the error goes on.
    try {
      return transactionTemplate.execute(
          status -> persistMedia(uploader, objectKey, compressed.length));
    } catch (RuntimeException e) {
      deleteQuietly(objectKey);
      throw e;
    }
  }

  private record Uploader(SosRequest sos, User user, Mechanic mechanic) {}

  // Runs inside the transaction opened by upload().
  private Uploader loadUploader(UUID sosId, UUID userId, UUID mechanicId) {
    SosRequest sos =
        sosRequestRepository
            .findById(sosId)
            .orElseThrow(() -> new EntityNotFoundException("SOS request not found: " + sosId));

    // Only the driver who raised the ticket and the mechanic assigned to it may attach — otherwise
    // anyone holding a sosId (or a past bidder) can push images into someone else's incident.
    boolean party =
        userId != null
            ? sos.getUser().getUserId().equals(userId)
            : bidSosRepository
                .findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED)
                .map(bid -> bid.getMechanic().getMechanicId().equals(mechanicId))
                .orElse(false);
    if (!party) {
      throw new AccessDeniedException("You are not part of this SOS request");
    }
    if (CLOSED_STATUSES.contains(sos.getStatus())) {
      throw new ConflictException("This SOS request is closed; photos can no longer be added");
    }
    if (sosMediaRepository.countBySosRequestSosId(sosId) >= maxPerSos) {
      throw new ConflictException("This SOS request already has " + maxPerSos + " photos");
    }

    if (userId != null) {
      User user =
          userRepository
              .findById(userId)
              .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
      return new Uploader(sos, user, null);
    }
    Mechanic mechanic =
        mechanicRepository
            .findById(mechanicId)
            .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
    return new Uploader(sos, null, mechanic);
  }

  // Runs inside the transaction opened by upload(). The entities were loaded by an earlier,
  // already closed transaction; they are only referenced by id here, never modified.
  private SosMediaResponse persistMedia(Uploader uploader, String objectKey, int sizeBytes) {
    User user = uploader.user();
    Mechanic mechanic = uploader.mechanic();
    SosMedia saved =
        sosMediaRepository.save(
            SosMedia.builder()
                .sosRequest(uploader.sos())
                .mediaType(MediaType.PHOTO)
                .contentType("image/jpeg")
                .sizeBytes((long) sizeBytes)
                .objectKey(objectKey)
                .uploadedByUser(user)
                .uploadedByMechanic(mechanic)
                .build());

    // Event Notification Propagation (Kafka)
    UUID uploadedById = user != null ? user.getUserId() : mechanic.getMechanicId();
    String uploadedByRole = user != null ? "USER" : "MECHANIC";
    sosEventProducer.publishMediaUploaded(
        new SosMediaUploadedEvent(
            uploader.sos().getSosId(),
            saved.getMediaId(),
            uploadedByRole,
            uploadedById,
            saved.getUploadedAt()));

    return toResponseWithUrl(saved);
  }

  private void deleteQuietly(String objectKey) {
    try {
      s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
    } catch (RuntimeException e) {
      log.warn("Could not remove orphaned media object {}: {}", objectKey, e.getMessage());
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
    // Same answer as GET /sos/{id}: 404 whether the ticket is missing or not the caller's,
    // so an outsider cannot tell a real id from a made-up one.
    if (!sosService.canView(sosId, callerId)) {
      throw new EntityNotFoundException("SOS request not found: " + sosId);
    }
    return sosMediaRepository.findBySosRequestSosId(sosId).stream()
        .map(this::toResponseWithUrl)
        .toList();
  }

  /**
   * Presigned URLs for the photos attached to a ticket. Capped: a mechanic's incoming-request card
   * shows a thumbnail or two, and every extra URL is ~500 bytes on a socket frame fanned out to
   * every nearby mechanic.
   */
  @Override
  @Transactional(readOnly = true)
  public List<String> photoUrls(UUID sosId) {
    return sosMediaRepository.findBySosRequestSosId(sosId).stream()
        .filter(m -> m.getMediaType() == MediaType.PHOTO)
        .limit(maxBroadcastPhotos)
        .map(m -> presignGetUrl(m.getObjectKey()))
        .toList();
  }

  /** Confirms that the HTTP header content-type matches whitelist protocols. */
  private void validateContentType(String contentType) {
    if (contentType == null || !ALLOWED_CONTENT_TYPES.containsKey(contentType)) {
      throw new BadRequestException(
          "Unsupported content type: "
              + contentType
              + ". Allowed: "
              + ALLOWED_CONTENT_TYPES.keySet());
    }
  }

  /**
   * Inspects binary signatures (Magic Bytes) to stop users from masking malicious binaries or
   * alternative files behind trusted extensions like .jpg/.png.
   */
  private void verifyMagicBytes(byte[] data, String contentType) {
    boolean valid =
        switch (contentType) {
          case "image/jpeg" ->
              data.length >= 3
                  && (data[0] & 0xFF) == 0xFF
                  && (data[1] & 0xFF) == 0xD8
                  && (data[2] & 0xFF) == 0xFF;
          case "image/png" ->
              data.length >= 8
                  && (data[0] & 0xFF) == 0x89
                  && data[1] == 'P'
                  && data[2] == 'N'
                  && data[3] == 'G'
                  && data[4] == '\r'
                  && data[5] == '\n'
                  && (data[6] & 0xFF) == 0x1A
                  && data[7] == '\n';
          default -> true;
        };
    if (!valid) {
      throw new BadRequestException("File content does not match declared type: " + contentType);
    }
  }

  /**
   * Evaluates boundaries, resizes if image limits are exceeded, and returns an optimized JPEG byte
   * payload.
   */
  private byte[] compressToJpeg(byte[] raw) throws IOException {
    Dimension size = readDimensions(raw);
    // Pre-decode safety check: intercepts malicious payloads attempting to exhaust JVM memory
    if (size.width > maxSourceDimension || size.height > maxSourceDimension) {
      throw new BadRequestException(
          "Image dimensions "
              + size.width
              + "x"
              + size.height
              + " exceed the "
              + maxSourceDimension
              + "x"
              + maxSourceDimension
              + " limit");
    }
    var builder =
        Thumbnails.of(new ByteArrayInputStream(raw))
            .outputQuality(jpegQuality)
            .outputFormat("jpeg");
    if (size.width > maxImageDimension || size.height > maxImageDimension) {
      // Scales down proportionately to fit within max bounds
      builder.size(maxImageDimension, maxImageDimension).keepAspectRatio(true);
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

  /** Maps database domain entities to service response contracts containing fresh playback URLs. */
  private SosMediaResponse toResponseWithUrl(SosMedia media) {
    SosMediaResponse base = sosMediaMapper.toResponse(media);
    String url = presignGetUrl(media.getObjectKey());
    return new SosMediaResponse(
        base.mediaId(),
        base.mediaType(),
        base.contentType(),
        base.sizeBytes(),
        url,
        base.uploadedAt());
  }

  /** Generates a secure, signed URL providing read permissions to an isolated S3 asset. */
  private String presignGetUrl(String objectKey) {
    GetObjectPresignRequest presignRequest =
        GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(presignTtlMinutes))
            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())
            .build();
    // Signing is local with static keys, but resolving credentials would not be with an AWS
    // provider chain; the span shows which it is.
    return Observation.createNotStarted(S3_PRESIGN, observationRegistry)
        .observe(() -> s3Presigner.presignGetObject(presignRequest).url().toString());
  }
}
