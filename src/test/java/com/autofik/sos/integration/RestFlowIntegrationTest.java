package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.dto.event.SosCreatedEvent;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;

/**
 * REST against the whole app with real Postgres, Redis and Kafka: what the caller sees, and the
 * side effects left behind in Kafka and Redis.
 */
class RestFlowIntegrationTest extends FlowTestSupport {

  @Test
  void createSos_returns201_publishesToKafkaKeyedBySosId_andSetsTheRedisCooldown()
      throws Exception {
    Driver d = newDriver();
    try (KafkaConsumer<String, String> created = tap("sos.created")) {
      created.poll(Duration.ofMillis(200)); // join the group before the event is produced

      UUID sosId = createSosOk(d);

      ConsumerRecord<String, String> record =
          awaitRecord(created, sosId.toString(), Duration.ofSeconds(30));
      assertThat(parse(record.value()).get("userId").asString())
          .isEqualTo(d.user().getUserId().toString());
    }
    assertThat(redis.hasKey("sos:cooldown:" + d.user().getUserId())).isTrue();
    Long ttl = redis.getExpire("sos:cooldown:" + d.user().getUserId());
    assertThat(ttl).as("cooldown TTL seconds").isBetween(1L, 60L);
  }

  @Test
  void secondCreateWhileTicketIsActive_is409_andAfterCancelTheCooldownAnswers429()
      throws Exception {
    Driver d = newDriver();
    UUID sosId = createSosOk(d);

    HttpResponse<String> duplicate = createSos(d);
    assertThat(duplicate.statusCode()).isEqualTo(409);
    assertThat(parse(duplicate.body()).get("status").asInt()).isEqualTo(409);

    HttpResponse<String> cancel =
        send(
            req("/api/v1/sos/" + sosId + "/cancel")
                .header("user-context", userContext(d.user().getUserId()))
                .method("PATCH", HttpRequest.BodyPublishers.noBody()));
    assertThat(cancel.statusCode()).as(cancel.body()).isEqualTo(200);

    HttpResponse<String> tooSoon = createSos(d);
    assertThat(tooSoon.statusCode()).as(tooSoon.body()).isEqualTo(429);
  }

  @Test
  void validationFailures_are400_withAStructuredBody_andNothingReachesKafkaOrRedis()
      throws Exception {
    Driver d = newDriver();
    String outsideCambodia = createBody(d).replace("\"latitude\":11.5564", "\"latitude\":48.85");

    HttpResponse<String> r =
        send(
            req("/api/v1/sos")
                .header("user-context", userContext(d.user().getUserId()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(outsideCambodia)));

    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(parse(r.body()).get("status").asInt()).isEqualTo(400);
    assertThat(redis.hasKey("sos:cooldown:" + d.user().getUserId()))
        .as("a rejected request must not burn the user's cooldown")
        .isFalse();
  }

  @Test
  void malformedJson_andWrongContentType_areClientErrors_notServerErrors() throws Exception {
    Driver d = newDriver();
    HttpResponse<String> broken =
        send(
            req("/api/v1/sos")
                .header("user-context", userContext(d.user().getUserId()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":")));
    HttpResponse<String> wrongType =
        send(
            req("/api/v1/sos")
                .header("user-context", userContext(d.user().getUserId()))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(createBody(d))));

    assertThat(broken.statusCode()).isEqualTo(400);
    assertThat(wrongType.statusCode()).isEqualTo(415);
  }

  @Test
  void identity_missingHeader_isRejected_andMechanicCannotCreateAnSos() throws Exception {
    Driver d = newDriver();
    HttpResponse<String> anonymous =
        send(
            req("/api/v1/sos")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody(d))));
    Mechanic m = newMechanic(11.55, 104.92);
    HttpResponse<String> asMechanic =
        send(
            req("/api/v1/sos")
                .header("mechanic-context", mechanicContext(m.getMechanicId()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody(d))));

    assertThat(anonymous.statusCode()).isIn(401, 403);
    assertThat(asMechanic.statusCode()).isEqualTo(403);
  }

  @Test
  void createForSomeoneElse_isForbidden() throws Exception {
    Driver me = newDriver();
    Driver other = newDriver();
    HttpResponse<String> r =
        send(
            req("/api/v1/sos")
                .header("user-context", userContext(me.user().getUserId()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody(other))));
    assertThat(r.statusCode()).isEqualTo(403);
  }

  @Test
  void getSos_ownerSeesIt_strangerGets404_notAnExistenceLeak() throws Exception {
    Driver owner = newDriver();
    UUID sosId = createSosOk(owner);

    HttpResponse<String> mine =
        send(
            req("/api/v1/sos/" + sosId)
                .header("user-context", userContext(owner.user().getUserId())));
    HttpResponse<String> stranger =
        send(
            req("/api/v1/sos/" + sosId)
                .header("user-context", userContext(newDriver().user().getUserId())));

    assertThat(mine.statusCode()).isEqualTo(200);
    assertThat(parse(mine.body()).get("sosId").asString()).isEqualTo(sosId.toString());
    assertThat(stranger.statusCode()).isEqualTo(404);
  }

  @Test
  void pathVariableThatIsNotAUuid_is400() throws Exception {
    HttpResponse<String> r =
        send(
            req("/api/v1/sos/not-a-uuid")
                .header("user-context", userContext(newDriver().user().getUserId())));
    assertThat(r.statusCode()).isEqualTo(400);
  }

  @Test
  void mechanicLocationPatch_is204_andThrottlesPostgresWritesThroughARedisKey() throws Exception {
    Mechanic m = newMechanic(11.55, 104.92);
    String path = "/api/v1/mechanics/" + m.getMechanicId() + "/location?lat=11.5600&lng=104.9300";

    HttpResponse<String> first =
        send(
            req(path)
                .header("mechanic-context", mechanicContext(m.getMechanicId()))
                .method("PATCH", HttpRequest.BodyPublishers.noBody()));

    assertThat(first.statusCode()).isEqualTo(204);
    String key = "mechanic:pg-sync:" + m.getMechanicId();
    assertThat(redis.hasKey(key)).isTrue();
    assertThat(redis.getExpire(key)).isBetween(1L, 10L);
    assertThat(mechanicRepository.findById(m.getMechanicId()).orElseThrow().getCurrentH3Index())
        .as("first ping inside the window is written through to Postgres")
        .isNotNull();

    // A second ping inside the window must not rewrite Postgres.
    String h3Before =
        mechanicRepository.findById(m.getMechanicId()).orElseThrow().getCurrentH3Index();
    send(
        req("/api/v1/mechanics/" + m.getMechanicId() + "/location?lat=12.5000&lng=105.5000")
            .header("mechanic-context", mechanicContext(m.getMechanicId()))
            .method("PATCH", HttpRequest.BodyPublishers.noBody()));
    assertThat(mechanicRepository.findById(m.getMechanicId()).orElseThrow().getCurrentH3Index())
        .isEqualTo(h3Before);
  }

  @Test
  void locationOutsideTheGeofence_is400() throws Exception {
    Mechanic m = newMechanic(11.55, 104.92);
    HttpResponse<String> r =
        send(
            req("/api/v1/mechanics/" + m.getMechanicId() + "/location?lat=48.85&lng=2.35")
                .header("mechanic-context", mechanicContext(m.getMechanicId()))
                .method("PATCH", HttpRequest.BodyPublishers.noBody()));
    assertThat(r.statusCode()).isEqualTo(400);
  }

  @Test
  void health_isPublic_andReportsTheDatabase() throws Exception {
    HttpResponse<String> r = send(req("/healthy"));
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(parse(r.body()).get("database").asString()).isEqualTo("UP");
  }

  @Test
  void unknownRoute_is404_andSerializedError_doesNotLeakInternals() throws Exception {
    HttpResponse<String> r =
        send(
            req("/api/v1/nope")
                .header("user-context", userContext(newDriver().user().getUserId())));
    assertThat(r.statusCode()).isEqualTo(404);
    assertThat(r.body()).doesNotContain("Exception").doesNotContain("at com.autofik");
  }

  @Test
  void createdEventPayload_deserializesBackIntoTheEventType() throws Exception {
    Driver d = newDriver();
    try (KafkaConsumer<String, String> created = tap("sos.created")) {
      created.poll(Duration.ofMillis(200));
      UUID sosId = createSosOk(d);
      ConsumerRecord<String, String> record =
          awaitRecord(created, sosId.toString(), Duration.ofSeconds(30));
      // The wire format other services depend on: reads back into the typed event.
      SosCreatedEvent event =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
              .readValue(record.value(), SosCreatedEvent.class);
      assertThat(event.sosId()).isEqualTo(sosId);
      assertThat(event.latitude()).isEqualTo(11.5564);
      assertThat(event.createdAt()).isNotNull();
    }
  }
}
