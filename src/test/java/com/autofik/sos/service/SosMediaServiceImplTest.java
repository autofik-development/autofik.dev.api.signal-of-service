package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosMedia;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.enums.MediaType;
import com.autofik.sos.dto.response.SosMediaResponse;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.SosMediaMapper;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosMediaRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.impl.SosMediaServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@ExtendWith(MockitoExtension.class)
class SosMediaServiceImplTest {

  @Mock SosMediaRepository sosMediaRepository;
  @Mock SosService sosService;
  @Mock SosRequestRepository sosRequestRepository;
  @Mock UserRepository userRepository;
  @Mock MechanicRepository mechanicRepository;
  @Mock SosMediaMapper sosMediaMapper;
  @Mock SosEventProducer sosEventProducer;
  @Mock S3Client s3Client;
  @Mock S3Presigner s3Presigner;
  @Mock org.springframework.transaction.support.TransactionTemplate transactionTemplate;

  @InjectMocks SosMediaServiceImpl sosMediaService;

  private final UUID sosId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID mechanicId = UUID.randomUUID();

  private SosRequest sosRequest;
  private User user;
  private Mechanic mechanic;
  private MultipartFile file;

  private static byte[] jpegBytes() throws Exception {
    BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "jpeg", out);
    return out.toByteArray();
  }

  private static byte[] pngBytes() throws Exception {
    BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  @BeforeEach
  void setUp() throws Exception {
    ReflectionTestUtils.setField(sosMediaService, "bucket", "test-bucket");
    ReflectionTestUtils.setField(sosMediaService, "presignTtlMinutes", 15);
    ReflectionTestUtils.setField(sosMediaService, "maxSourceDimension", 8000);
    ReflectionTestUtils.setField(sosMediaService, "maxUploadMb", 10L);
    ReflectionTestUtils.setField(sosMediaService, "maxImageDimension", 1280);
    ReflectionTestUtils.setField(sosMediaService, "jpegQuality", 0.80);
    // upload() runs its two database phases through the template; run the callback inline.
    lenient()
        .when(transactionTemplate.execute(any()))
        .thenAnswer(
            inv ->
                ((org.springframework.transaction.support.TransactionCallback<?>)
                        inv.getArgument(0))
                    .doInTransaction(null));
    // Participation is covered by SosServiceImplTest; here every caller is a legitimate party.
    lenient().when(sosService.canView(any(), any())).thenReturn(true);

    sosRequest = SosRequest.builder().sosId(sosId).build();
    user = User.builder().userId(userId).name("Alice").build();
    mechanic =
        Mechanic.builder()
            .mechanicId(mechanicId)
            .fullName("Bob")
            .phoneNumber("081")
            .email("bob@fix.com")
            .build();

    file = mock(MultipartFile.class);
    lenient().when(file.getContentType()).thenReturn("image/jpeg");
    lenient().when(file.getSize()).thenReturn(1024L);
    lenient().when(file.getBytes()).thenReturn(jpegBytes());

    PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
    lenient()
        .when(presigned.url())
        .thenReturn(new URL("https://minio.example.com/test-bucket/some-key?signed=1"));
    lenient()
        .when(
            s3Presigner.presignGetObject(
                any(
                    software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
                        .class)))
        .thenReturn(presigned);
  }

  @Test
  void upload_whenTheMetadataInsertFails_theStoredObjectIsRemoved() throws Exception {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosRequest));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(sosMediaRepository.save(any(SosMedia.class))).thenThrow(new IllegalStateException("db"));

    assertThatThrownBy(() -> sosMediaService.upload(sosId, file, userId, null))
        .isInstanceOf(IllegalStateException.class);

    ArgumentCaptor<software.amazon.awssdk.services.s3.model.PutObjectRequest> put =
        ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.PutObjectRequest.class);
    verify(s3Client)
        .putObject(put.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
    ArgumentCaptor<software.amazon.awssdk.services.s3.model.DeleteObjectRequest> del =
        ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class);
    verify(s3Client).deleteObject(del.capture());
    assertThat(del.getValue().key()).isEqualTo(put.getValue().key());
    verifyNoInteractions(sosEventProducer);
  }

  @Test
  void upload_whenStorageFails_answers503_andWritesNoMetadata() throws Exception {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosRequest));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(s3Client.putObject(
            any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
            any(software.amazon.awssdk.core.sync.RequestBody.class)))
        .thenThrow(software.amazon.awssdk.core.exception.SdkClientException.create("unreachable"));

    assertThatThrownBy(() -> sosMediaService.upload(sosId, file, userId, null))
        .isInstanceOf(com.autofik.sos.exception.ServiceUnavailableException.class);

    verifyNoInteractions(sosMediaRepository, sosEventProducer);
  }

  @Test
  void upload_jpeg_compressedAndStoredAsJpeg() throws Exception {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosRequest));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(sosMediaRepository.save(any(SosMedia.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sosMediaMapper.toResponse(any(SosMedia.class)))
        .thenReturn(
            new SosMediaResponse(UUID.randomUUID(), MediaType.PHOTO, "image/jpeg", 0L, null, null));

    SosMediaResponse result = sosMediaService.upload(sosId, file, userId, null);

    assertThat(result.url()).contains("minio.example.com");

    ArgumentCaptor<SosMedia> captor = ArgumentCaptor.forClass(SosMedia.class);
    verify(sosMediaRepository).save(captor.capture());
    SosMedia saved = captor.getValue();
    assertThat(saved.getMediaType()).isEqualTo(MediaType.PHOTO);
    assertThat(saved.getContentType()).isEqualTo("image/jpeg");
    assertThat(saved.getObjectKey()).startsWith("sos/" + sosId + "/").endsWith(".jpg");
    assertThat(saved.getSizeBytes()).isPositive();
    assertThat(saved.getUploadedByUser()).isEqualTo(user);
    assertThat(saved.getUploadedByMechanic()).isNull();

    verify(s3Client)
        .putObject(
            any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
  }

  @Test
  void upload_png_compressedAndStoredAsJpeg() throws Exception {
    when(file.getContentType()).thenReturn("image/png");
    when(file.getBytes()).thenReturn(pngBytes());
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosRequest));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(sosMediaRepository.save(any(SosMedia.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sosMediaMapper.toResponse(any(SosMedia.class)))
        .thenReturn(
            new SosMediaResponse(UUID.randomUUID(), MediaType.PHOTO, "image/jpeg", 0L, null, null));

    sosMediaService.upload(sosId, file, userId, null);

    ArgumentCaptor<SosMedia> captor = ArgumentCaptor.forClass(SosMedia.class);
    verify(sosMediaRepository).save(captor.capture());
    SosMedia saved = captor.getValue();
    assertThat(saved.getContentType()).isEqualTo("image/jpeg");
    assertThat(saved.getObjectKey()).endsWith(".jpg");
  }

  @Test
  void upload_mechanicUpload_setsUploadedByMechanic() throws Exception {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosRequest));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(sosMediaRepository.save(any(SosMedia.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sosMediaMapper.toResponse(any(SosMedia.class)))
        .thenReturn(
            new SosMediaResponse(UUID.randomUUID(), MediaType.PHOTO, "image/jpeg", 0L, null, null));

    sosMediaService.upload(sosId, file, null, mechanicId);

    ArgumentCaptor<SosMedia> captor = ArgumentCaptor.forClass(SosMedia.class);
    verify(sosMediaRepository).save(captor.capture());
    assertThat(captor.getValue().getUploadedByMechanic()).isEqualTo(mechanic);
    assertThat(captor.getValue().getUploadedByUser()).isNull();
  }

  @Test
  void upload_neitherUserNorMechanicProvided_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> sosMediaService.upload(sosId, file, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Exactly one");

    verifyNoInteractions(sosRequestRepository, sosMediaRepository, s3Client);
  }

  @Test
  void upload_bothUserAndMechanicProvided_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> sosMediaService.upload(sosId, file, userId, mechanicId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Exactly one");

    verifyNoInteractions(sosRequestRepository, sosMediaRepository, s3Client);
  }

  @Test
  void upload_sosNotFound_throwsEntityNotFoundException() {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> sosMediaService.upload(sosId, file, userId, null))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining(sosId.toString());
  }

  @Test
  void upload_unsupportedContentType_throwsIllegalArgumentException() {
    when(file.getContentType()).thenReturn("video/mp4");

    assertThatThrownBy(() -> sosMediaService.upload(sosId, file, userId, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported content type");

    verifyNoInteractions(sosRequestRepository, s3Client, sosMediaRepository);
  }

  @Test
  void upload_imageTooLarge_throwsIllegalArgumentException() {
    when(file.getSize()).thenReturn(11L * 1024 * 1024); // 11 MB > 10 MB limit

    assertThatThrownBy(() -> sosMediaService.upload(sosId, file, userId, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("10 MB");

    verifyNoInteractions(sosRequestRepository, s3Client, sosMediaRepository);
  }

  @Test
  void upload_magicBytesMismatch_throwsIllegalArgumentException() throws Exception {
    when(file.getContentType()).thenReturn("image/jpeg");
    when(file.getBytes()).thenReturn(pngBytes()); // PNG bytes claimed as JPEG
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosRequest));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> sosMediaService.upload(sosId, file, userId, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match declared type");

    verifyNoInteractions(s3Client, sosMediaRepository);
  }

  @Test
  void getBySosRequest_returnsListWithPresignedUrls() {
    SosMedia media =
        SosMedia.builder()
            .mediaId(UUID.randomUUID())
            .objectKey("sos/" + sosId + "/x.jpg")
            .mediaType(MediaType.PHOTO)
            .contentType("image/jpeg")
            .sizeBytes(10L)
            .build();
    when(sosMediaRepository.findBySosRequestSosId(sosId)).thenReturn(List.of(media));
    when(sosMediaMapper.toResponse(media))
        .thenReturn(
            new SosMediaResponse(
                media.getMediaId(), MediaType.PHOTO, "image/jpeg", 10L, null, null));

    List<SosMediaResponse> result = sosMediaService.getBySosRequest(sosId, userId);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).url()).contains("minio.example.com");
  }

  @Test
  void getBySosRequest_cannotView_isNotFound_likeReadingTheSos() {
    when(sosService.canView(sosId, mechanicId)).thenReturn(false);

    assertThatThrownBy(() -> sosMediaService.getBySosRequest(sosId, mechanicId))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining(sosId.toString());

    verifyNoInteractions(sosMediaRepository);
  }
}
