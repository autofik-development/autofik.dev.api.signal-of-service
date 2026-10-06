package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.Mechanic;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the flow tests share: a driver who has an SOS ticket, a mechanic socket, a customer
 * subscription that collects frames, and a Kafka tap on any topic. Medusa and Google are mocked
 * (see {@link IntegrationTestBase}) so creating a ticket never leaves the machine.
 */
abstract class FlowTestSupport extends IntegrationTestBase {

  protected static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  protected static final ObjectMapper JSON = JsonMapper.builder().build();

  @Autowired protected StringRedisTemplate redis;
  @Autowired protected SimpUserRegistry simpUsers;

  protected final StompTestClient stomp = new StompTestClient();

  @BeforeEach
  void externalServicesAnswer() {
    when(medusaService.resolveServiceGroupKey(any())).thenReturn("TOW");
    when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn("Phnom Penh");
  }

  // ── REST ───────────────────────────────────────────────────────────────────

  protected HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  protected HttpRequest.Builder req(String path) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .timeout(Duration.ofSeconds(180));
  }

  protected static String createBody(Driver d) {
    return "{\"userId\":\""
        + d.user().getUserId()
        + "\",\"serviceGroupId\":\"sg_it\",\"description\":\"flat tyre\","
        + "\"latitude\":11.5564,\"longitude\":104.9282,\"vehicleId\":\""
        + d.vehicle().getId()
        + "\",\"broadcastRadiusKm\":10}";
  }

  protected HttpResponse<String> createSos(Driver d) throws Exception {
    return send(
        req("/api/v1/sos")
            .header("user-context", userContext(d.user().getUserId()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(createBody(d))));
  }

  protected UUID createSosOk(Driver d) throws Exception {
    HttpResponse<String> r = createSos(d);
    assertThat(r.statusCode()).as("create SOS: %s", r.body()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(r.body()).get("sosId").asString());
  }

  // ── STOMP ──────────────────────────────────────────────────────────────────

  /** Frames received on one subscription, as the raw JSON text the server sent. */
  protected static final class Tap {
    final BlockingQueue<String> frames = new LinkedBlockingQueue<>();

    String next(Duration wait) throws InterruptedException {
      return frames.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  protected Tap subscribe(StompSession session, String destination) {
    Tap tap = new Tap();
    session.subscribe(
        destination,
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            tap.frames.add(new String((byte[]) payload, StandardCharsets.UTF_8));
          }
        });
    return tap;
  }

  /** SUBSCRIBE is asynchronous; wait until the broker holds this many subscriptions to it. */
  protected void awaitSubscriptions(String destination, long count) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      long found =
          simpUsers.getUsers().stream()
              .flatMap(u -> u.getSessions().stream())
              .flatMap(s -> s.getSubscriptions().stream())
              .filter(sub -> destination.equals(sub.getDestination()))
              .count();
      if (found >= count) return;
      Thread.sleep(50);
    }
    throw new AssertionError("fewer than " + count + " subscriptions to " + destination);
  }

  /** A connection that records the server's ERROR frames and transport failures. */
  protected StompSession connectCapturingErrors(
      String contextHeader, String contextJson, BlockingQueue<String> errors) throws Exception {
    return stomp.connectWith(
        port,
        contextHeader,
        contextJson,
        new StompSessionHandlerAdapter() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
          }

          @Override
          public void handleTransportError(StompSession s, Throwable ex) {
            errors.add("TRANSPORT " + ex);
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            errors.add("ERROR-FRAME " + headers.getFirst("message"));
          }
        });
  }

  /**
   * Sends a ping as a JSON object. The payload must be an object, not a String or byte[]: the test
   * client's Jackson converter would quote a String and base64 a byte[], and the server would
   * (correctly) reject both.
   */
  protected static void sendPing(StompSession session, java.util.Map<String, Object> ping) {
    session.send("/app/mechanic/location", ping);
  }

  protected static java.util.Map<String, Object> ping(double lat, double lng, UUID sosId) {
    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("latitude", lat);
    m.put("longitude", lng);
    if (sosId != null) m.put("sosId", sosId.toString());
    return m;
  }

  protected static JsonNode parse(String json) {
    return JSON.readTree(json);
  }

  // ── Kafka ──────────────────────────────────────────────────────────────────

  protected KafkaProducer<String, String> stringProducer() {
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap());
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    return new KafkaProducer<>(p);
  }

  protected void produce(String topic, String key, String json) throws Exception {
    try (KafkaProducer<String, String> producer = stringProducer()) {
      producer.send(new ProducerRecord<>(topic, key, json)).get(30, TimeUnit.SECONDS);
    }
  }

  /** Reads one topic from the start under a throwaway group. Close it when done. */
  protected KafkaConsumer<String, String> tap(String topic) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap());
    p.put(ConsumerConfig.GROUP_ID_CONFIG, "tap-" + UUID.randomUUID());
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    KafkaConsumer<String, String> c = new KafkaConsumer<>(p);
    c.subscribe(List.of(topic));
    return c;
  }

  /** Polls until a record with this key shows up, or the wait runs out. */
  protected ConsumerRecord<String, String> awaitRecord(
      KafkaConsumer<String, String> consumer, String key, Duration wait) {
    long deadline = System.currentTimeMillis() + wait.toMillis();
    List<String> seen = new ArrayList<>();
    while (System.currentTimeMillis() < deadline) {
      for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
        if (key.equals(r.key())) return r;
        seen.add(r.key());
      }
    }
    throw new AssertionError("no record with key " + key + " within " + wait + "; saw " + seen);
  }

  /** Recorded in the test output as a {@code [finding]} line for the test report. */
  protected static void finding(String text) {
    System.out.println("[finding] " + text);
  }

  protected HttpResponse<String> placeBid(Mechanic m, UUID sosId) throws Exception {
    return send(
        req("/api/v1/bids")
            .header("mechanic-context", mechanicContext(m.getMechanicId()))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"mechanicId\":\""
                        + m.getMechanicId()
                        + "\",\"sosId\":\""
                        + sosId
                        + "\",\"price\":25.00}")));
  }

  /** The ticket's status as its owner sees it. */
  protected String sosStatus(Driver d, UUID sosId) throws Exception {
    HttpResponse<String> r =
        send(
            req("/api/v1/sos/" + sosId)
                .header("user-context", userContext(d.user().getUserId()))
                .GET());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return parse(r.body()).get("status").asString();
  }

  protected UUID placeBidOk(Mechanic m, UUID sosId) throws Exception {
    HttpResponse<String> r = placeBid(m, sosId);
    assertThat(r.statusCode()).as("place bid: %s", r.body()).isEqualTo(201);
    return UUID.fromString(parse(r.body()).get("bidId").asString());
  }

  protected HttpResponse<String> acceptBid(Driver d, UUID bidId) throws Exception {
    return send(
        req("/api/v1/bids/" + bidId + "/status")
            .header("user-context", userContext(d.user().getUserId()))
            .header("Content-Type", "application/json")
            .method(
                "PATCH",
                HttpRequest.BodyPublishers.ofString(
                    "{\"userId\":\"" + d.user().getUserId() + "\",\"status\":\"ACCEPTED\"}")));
  }

  protected void acceptBidOk(Driver d, UUID bidId) throws Exception {
    HttpResponse<String> r = acceptBid(d, bidId);
    assertThat(r.statusCode()).as("accept bid: %s", r.body()).isEqualTo(200);
  }

  /** The mechanic bids and the driver accepts: the mechanic is now assigned to the ticket. */
  protected void assignMechanic(Driver d, Mechanic m, UUID sosId) throws Exception {
    acceptBidOk(d, placeBidOk(m, sosId));
  }
}
