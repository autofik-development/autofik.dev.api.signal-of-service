package com.autofik.sos.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.SosMedia;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.dto.response.SosMediaResponse;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.SosMediaMapper;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosMediaRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.SosService;
import com.autofik.sos.service.impl.SosMediaServiceImpl;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * Upload bytes that get past the content-type and magic-byte checks but are not a clean image:
 * random tails behind a real signature, truncated images, flipped bytes, forged dimensions. The
 * upload must either store a JPEG or refuse with IllegalArgumentException (a 400). Any other
 * exception reaches the global handler's catch-all and the client sees a 500 for its own bad file.
 */
class MediaUploadFuzzTest {

  private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
  private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] PNG = encode("png", 40, 30);
  private static final byte[] JPEG = encode("jpg", 40, 30);

  @Mock SosMediaRepository sosMediaRepository;
  @Mock SosService sosService;
  @Mock SosRequestRepository sosRequestRepository;
  @Mock UserRepository userRepository;
  @Mock MechanicRepository mechanicRepository;
  @Mock SosMediaMapper sosMediaMapper;
  @Mock SosEventProducer sosEventProducer;
  @Mock S3Client s3Client;
  @Mock S3Presigner s3Presigner;

  @InjectMocks SosMediaServiceImpl sosMediaService;

  private AutoCloseable mocks;
  private final UUID sosId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();

  @BeforeTry
  void freshService() throws Exception {
    sosMediaService = null;
    mocks = MockitoAnnotations.openMocks(this);
    ReflectionTestUtils.setField(sosMediaService, "bucket", "test-bucket");
    ReflectionTestUtils.setField(sosMediaService, "presignTtlMinutes", 15);
    ReflectionTestUtils.setField(sosMediaService, "maxSourceDimension", 8000);
    ReflectionTestUtils.setField(sosMediaService, "maxUploadMb", 10L);
    ReflectionTestUtils.setField(sosMediaService, "maxImageDimension", 1280);
    ReflectionTestUtils.setField(sosMediaService, "jpegQuality", 0.80);
    when(sosService.canView(any(), any())).thenReturn(true);
    when(sosRequestRepository.findById(sosId))
        .thenReturn(Optional.of(SosRequest.builder().sosId(sosId).build()));
    when(userRepository.findById(userId))
        .thenReturn(Optional.of(User.builder().userId(userId).name("Alice").build()));
    when(sosMediaRepository.save(any(SosMedia.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sosMediaMapper.toResponse(any()))
        .thenReturn(new SosMediaResponse(null, null, null, null, null, null));
    PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
    when(presigned.url()).thenReturn(new URL("https://s3.example/test-bucket/k?signed=1"));
    when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);
  }

  @AfterTry
  void closeMocks() throws Exception {
    mocks.close();
  }

  @Property(seed = Fuzz.SEED)
  void anUploadEitherStoresAJpeg_orIsRefusedAsBadInput(@ForAll("uploads") Upload upload) {
    Throwable thrown = null;
    try {
      sosMediaService.upload(
          sosId,
          new MockMultipartFile("file", "f", upload.contentType(), upload.bytes()),
          userId,
          null);
    } catch (Throwable t) {
      thrown = t;
    }

    if (thrown == null) {
      verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    } else {
      assertThat(thrown)
          .as("%s → %s: %s", upload, thrown.getClass().getSimpleName(), thrown.getMessage())
          .isInstanceOf(IllegalArgumentException.class);
      verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
  }

  // ── generators ─────────────────────────────────────────────────────────────

  record Upload(String kind, String contentType, byte[] bytes) {
    @Override
    public String toString() {
      return kind + " as " + contentType + " (" + bytes.length + " bytes)";
    }
  }

  @Provide
  Arbitrary<Upload> uploads() {
    return Arbitraries.oneOf(
        randomTail("png", PNG_MAGIC),
        randomTail("jpeg", JPEG_MAGIC),
        truncated("png", PNG),
        truncated("jpeg", JPEG),
        flipped("png", PNG),
        flipped("jpeg", JPEG),
        forgedPngSize());
  }

  /** A real signature followed by noise. */
  private static Arbitrary<Upload> randomTail(String type, byte[] magic) {
    return Arbitraries.bytes()
        .array(byte[].class)
        .ofMaxSize(2_000)
        .map(tail -> new Upload("magic+noise", "image/" + type, concat(magic, tail)));
  }

  /** A real image cut short somewhere after its signature. */
  private static Arbitrary<Upload> truncated(String type, byte[] image) {
    return Arbitraries.integers()
        .between(8, image.length - 1)
        .map(n -> new Upload("truncated@" + n, "image/" + type, Arrays.copyOf(image, n)));
  }

  /** A real image with a few bytes past the signature overwritten. */
  private static Arbitrary<Upload> flipped(String type, byte[] image) {
    return Arbitraries.integers()
        .between(8, image.length - 1)
        .list()
        .ofMinSize(1)
        .ofMaxSize(8)
        .map(
            positions -> {
              byte[] b = image.clone();
              for (int p : positions) b[p] = (byte) ~b[p];
              return new Upload("flipped" + positions, "image/" + type, b);
            });
  }

  /**
   * A PNG whose header claims any size, with no pixel data behind it. The dimension guard must
   * refuse the oversized ones before anything tries to allocate them.
   */
  private static Arbitrary<Upload> forgedPngSize() {
    return Combinators.combine(
            Arbitraries.integers().between(1, Integer.MAX_VALUE),
            Arbitraries.integers().between(1, Integer.MAX_VALUE))
        .as(
            (w, h) -> {
              byte[] b = PNG.clone();
              // IHDR data starts at byte 16: width then height, big-endian. The CRC is left stale.
              putInt(b, 16, w);
              putInt(b, 20, h);
              return new Upload("forged " + w + "x" + h, "image/png", b);
            });
  }

  private static void putInt(byte[] b, int at, int v) {
    b[at] = (byte) (v >>> 24);
    b[at + 1] = (byte) (v >>> 16);
    b[at + 2] = (byte) (v >>> 8);
    b[at + 3] = (byte) v;
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  private static byte[] encode(String format, int w, int h) {
    try {
      BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
      for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) img.setRGB(x, y, x * 6 << 8 | y * 8);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(img, format, out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
