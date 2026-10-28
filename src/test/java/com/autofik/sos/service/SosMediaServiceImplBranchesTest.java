package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosMedia;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.MediaType;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.exception.BadRequestException;
import com.autofik.sos.exception.ServiceUnavailableException;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.SosMediaMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosMediaRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.impl.SosMediaServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/** The upload checks, failure paths and photo URLs that {@link SosMediaServiceImplTest} skips. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SosMediaServiceImplBranchesTest {

  @Mock SosMediaRepository sosMediaRepository;
  @Mock SosService sosService;
  @Mock SosRequestRepository sosRequestRepository;
  @Mock BidSosRepository bidSosRepository;
  @Mock UserRepository userRepository;
  @Mock MechanicRepository mechanicRepository;
  @Mock SosMediaMapper sosMediaMapper;
  @Mock SosEventProducer sosEventProducer;
  @Mock S3Client s3Client;
  @Mock S3Presigner s3Presigner;
  @Mock TransactionTemplate transactionTemplate;

  @InjectMocks SosMediaServiceImpl service;

  private final UUID sosId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID mechanicId = UUID.randomUUID();
  private User user;
  private SosRequest sos;
  private MultipartFile file;

  private static byte[] image(String format, int width, int height) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
    return out.toByteArray();
  }

  @BeforeEach
  void setUp() throws Exception {
    ReflectionTestUtils.setField(service, "bucket", "test-bucket");
    ReflectionTestUtils.setField(service, "presignTtlMinutes", 15);
    ReflectionTestUtils.setField(service, "maxSourceDimension", 8000);
    ReflectionTestUtils.setField(service, "maxUploadMb", 10L);
    ReflectionTestUtils.setField(service, "maxImageDimension", 1280);
    ReflectionTestUtils.setField(service, "jpegQuality", 0.80);
    ReflectionTestUtils.setField(service, "maxPerSos", 10);
    ReflectionTestUtils.setField(service, "maxBroadcastPhotos", 2);
    when(transactionTemplate.execute(any()))
        .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));

    user = User.builder().userId(userId).name("Alice").build();
    sos = SosRequest.builder().sosId(sosId).user(user).status(SosStatus.PENDING).build();
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    file = mock(MultipartFile.class);
    when(file.getContentType()).thenReturn("image/jpeg");
    when(file.getSize()).thenReturn(1024L);
    when(file.getBytes()).thenReturn(image("jpeg", 100, 100));

    PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
    when(presigned.url()).thenReturn(new URL("https://minio.example.com/b/k?signed=1"));
    when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);
  }

  private void assertRefusedAsBadRequest(String contentType, byte[] bytes) throws Exception {
    when(file.getContentType()).thenReturn(contentType);
    when(file.getBytes()).thenReturn(bytes);
    assertThatThrownBy(() -> service.upload(sosId, file, userId, null))
        .as("%s with %d bytes", contentType, bytes.length)
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aFileThatCannotBeRead_isAnUncheckedIoError() throws Exception {
    when(file.getBytes()).thenThrow(new IOException("disk"));

    assertThatThrownBy(() -> service.upload(sosId, file, userId, null))
        .isInstanceOf(java.io.UncheckedIOException.class);
  }

  @Test
  void aMissingContentType_isRefused() throws Exception {
    assertRefusedAsBadRequest(null, new byte[] {1, 2, 3});
  }

  @Test
  void aUnknownDriverOrMechanic_isNotFound() {
    when(userRepository.findById(userId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.upload(sosId, file, userId, null))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining("User not found");

    Mechanic assigned = Mechanic.builder().mechanicId(mechanicId).build();
    when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(Optional.of(BidSos.builder().mechanic(assigned).build()));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.upload(sosId, file, null, mechanicId))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining("Mechanic not found");
  }

  @Test
  void everyWrongSignatureByte_isRefused_forBothImageTypes() throws Exception {
    byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x00};
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0x00};

    // too short, and each position wrong in turn
    assertRefusedAsBadRequest("image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8});
    for (int i = 0; i < 3; i++) {
      byte[] bad = jpeg.clone();
      bad[i] = 0x11;
      assertRefusedAsBadRequest("image/jpeg", bad);
    }
    assertRefusedAsBadRequest("image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'});
    for (int i = 0; i < 8; i++) {
      byte[] bad = png.clone();
      bad[i] = 0x11;
      assertRefusedAsBadRequest("image/png", bad);
    }
  }

  @Test
  void aFileWithTheRightSignatureButNoImageInside_isNotReadable() throws Exception {
    byte[] fake = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5, 6, 7, 8};

    assertRefusedAsBadRequest("image/jpeg", fake);
  }

  @Test
  void aPicture_largerThanTheBoundingBoxInOneDirectionOnly_isStillShrunk() throws Exception {
    ReflectionTestUtils.setField(service, "maxImageDimension", 50);
    when(sosMediaRepository.save(any(SosMedia.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sosMediaMapper.toResponse(any(SosMedia.class)))
        .thenReturn(
            new com.autofik.sos.dto.response.SosMediaResponse(
                UUID.randomUUID(), MediaType.PHOTO, "image/jpeg", 0L, null, null));

    when(file.getBytes()).thenReturn(image("jpeg", 20, 120)); // narrow but tall
    service.upload(sosId, file, userId, null);
    when(file.getBytes()).thenReturn(image("jpeg", 120, 20)); // wide but short
    service.upload(sosId, file, userId, null);

    verify(sosMediaRepository, org.mockito.Mockito.times(2)).save(any(SosMedia.class));
  }

  @Test
  void anInterruptedWaitForADecodeSlot_answers503_andKeepsTheInterrupt() {
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> service.upload(sosId, file, userId, null))
          .isInstanceOf(ServiceUnavailableException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted(); // clear the flag for the next test
    }
  }

  @Test
  void aFailedCleanup_doesNotHideTheOriginalError() {
    when(sosMediaRepository.save(any(SosMedia.class))).thenThrow(new IllegalStateException("db"));
    when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
        .thenThrow(new RuntimeException("s3 down"));

    assertThatThrownBy(() -> service.upload(sosId, file, userId, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("db");
  }

  @Test
  void photoUrls_listOnlyPhotos_capped_asPresignedLinks() {
    SosMedia a = SosMedia.builder().mediaType(MediaType.PHOTO).objectKey("a").build();
    SosMedia video = SosMedia.builder().mediaType(MediaType.VIDEO).objectKey("v").build();
    SosMedia b = SosMedia.builder().mediaType(MediaType.PHOTO).objectKey("b").build();
    SosMedia c = SosMedia.builder().mediaType(MediaType.PHOTO).objectKey("c").build();
    when(sosMediaRepository.findBySosRequestSosId(sosId)).thenReturn(List.of(a, video, b, c));

    List<String> urls = service.photoUrls(sosId);

    assertThat(urls).hasSize(2); // maxBroadcastPhotos, and the video is not a photo
    assertThat(urls).allMatch(u -> u.contains("minio.example.com"));
  }

  @Test
  void aPictureTooBigInEitherDirection_isRefusedBeforeDecoding() throws Exception {
    ReflectionTestUtils.setField(service, "maxSourceDimension", 50);

    assertRefusedAsBadRequest("image/jpeg", image("jpeg", 20, 120));
    assertRefusedAsBadRequest("image/jpeg", image("jpeg", 120, 20));
  }
}
