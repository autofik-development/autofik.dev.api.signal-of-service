package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;

/**
 * Media upload against a real S3-compatible store. Adobe S3Mock stands in for the production bucket
 * (the MinIO images are no longer pullable from Docker Hub or quay.io), so the bytes really travel
 * through the SDK and the presigned URL is really fetched. The base class's S3 settings, which
 * point nowhere useful, are overridden for this class only, so it boots its own context.
 */
class SosMediaIntegrationTest extends FlowTestSupport {

  private static final String BUCKET = "it-sos-media";

  static final GenericContainer<?> S3 =
      new GenericContainer<>("adobe/s3mock:4.7.0")
          .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET)
          .withExposedPorts(9090)
          .waitingFor(Wait.forLogMessage(".*Started S3MockApplication.*\n", 1));

  static {
    S3.start();
  }

  @DynamicPropertySource
  static void s3(DynamicPropertyRegistry r) {
    // The aws.s3.* keys themselves, not the S3_* variables the base class also sets: a property
    // beats the ${S3_*} placeholder in application.yaml regardless of registration order.
    r.add("aws.s3.endpoint", () -> "http://" + S3.getHost() + ":" + S3.getMappedPort(9090));
    r.add("aws.s3.access-key", () -> "it"); // S3Mock accepts any credentials
    r.add("aws.s3.secret-key", () -> "it");
    r.add("aws.s3.region", () -> "us-east-1");
    r.add("aws.s3.bucket", () -> BUCKET);
  }

  @Autowired private S3Client s3Client;

  @Test
  void driverUpload_is201_storesTheObject_publishesAnEvent_andIsListedWithAWorkingUrl()
      throws Exception {
    Driver d = newDriver();
    UUID sosId = createSosOk(d);

    UUID mediaId;
    try (KafkaConsumer<String, String> uploaded = tap("sos.media.uploaded")) {
      uploaded.poll(Duration.ofMillis(200));

      HttpResponse<String> r = upload(d, sosId, "image/png", png(), "damage.png");
      assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
      JsonNode body = parse(r.body());
      assertThat(body.get("contentType").asString()).isEqualTo("image/jpeg");
      assertThat(body.get("sizeBytes").asLong()).isPositive();
      mediaId = UUID.fromString(body.get("mediaId").asString());

      assertThat(
              parse(awaitRecord(uploaded, sosId.toString(), Duration.ofSeconds(30)).value())
                  .get("mediaId")
                  .asString())
          .isEqualTo(mediaId.toString());
    }

    assertThat(
            s3Client.listObjectsV2(b -> b.bucket(BUCKET).prefix("sos/" + sosId + "/")).keyCount())
        .as("objects stored under this SOS")
        .isEqualTo(1);

    HttpResponse<String> list =
        send(
            req("/api/v1/sos/" + sosId + "/media")
                .header("user-context", userContext(d.user().getUserId()))
                .GET());
    assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
    JsonNode item = parse(list.body()).get(0);
    assertThat(item.get("mediaId").asString()).isEqualTo(mediaId.toString());

    // The presigned URL must actually serve the JPEG the service wrote.
    HttpResponse<byte[]> fetched =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(item.get("url").asString())).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(fetched.statusCode()).isEqualTo(200);
    assertThat(fetched.body()).startsWith((byte) 0xFF, (byte) 0xD8); // JPEG magic bytes
  }

  @Test
  void aFileThatLiesAboutItsType_is400_andNothingIsStored() throws Exception {
    Driver d = newDriver();
    UUID sosId = createSosOk(d);

    HttpResponse<String> r =
        upload(d, sosId, "image/jpeg", "not an image".getBytes(StandardCharsets.UTF_8), "x.jpg");

    assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
    assertThat(
            s3Client.listObjectsV2(b -> b.bucket(BUCKET).prefix("sos/" + sosId + "/")).keyCount())
        .isZero();
  }

  @Test
  void unsupportedContentType_is400() throws Exception {
    Driver d = newDriver();
    UUID sosId = createSosOk(d);

    HttpResponse<String> r = upload(d, sosId, "application/pdf", png(), "doc.pdf");

    assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
  }

  @Test
  void aDriverWhoIsNotPartOfTheSos_cannotUpload() throws Exception {
    Driver owner = newDriver();
    Driver stranger = newDriver();
    UUID sosId = createSosOk(owner);

    HttpResponse<String> r = upload(stranger, sosId, "image/png", png(), "damage.png");

    assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
    assertThat(
            s3Client.listObjectsV2(b -> b.bucket(BUCKET).prefix("sos/" + sosId + "/")).keyCount())
        .isZero();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private HttpResponse<String> upload(
      Driver d, UUID sosId, String contentType, byte[] bytes, String filename) throws Exception {
    String boundary = "it-" + UUID.randomUUID();
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename
                + "\"\r\nContent-Type: "
                + contentType
                + "\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.write(bytes);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return send(
        req("/api/v1/sos/" + sosId + "/media")
            .header("user-context", userContext(d.user().getUserId()))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
  }

  /** A real, decodable 64x64 PNG. */
  private static byte[] png() throws Exception {
    BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }
}
