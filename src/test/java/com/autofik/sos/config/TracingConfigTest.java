package com.autofik.sos.config;

import static com.autofik.sos.config.TestTracing.PARENT_SPAN_ID;
import static com.autofik.sos.config.TestTracing.TRACEPARENT;
import static com.autofik.sos.config.TestTracing.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * TracingConfig and the Kafka wiring against the real observation-to-span pipeline: the gateway's
 * traceparent continued on a request, probes left out, the trace context carried in Kafka record
 * headers, and query strings kept off client spans.
 */
class TracingConfigTest {

  private final TestTracing tracing = new TestTracing();

  @AfterEach
  void close() {
    tracing.close();
  }

  @RestController
  static class Endpoints {
    @GetMapping({"/api/v1/sos/{id}", "/", "/healthy", "/actuator/health"})
    String ok() {
      return "ok";
    }
  }

  private MockMvc mvc() {
    tracing.registry.observationConfig().observationPredicate(new TracingConfig().tracingScope());
    return MockMvcBuilders.standaloneSetup(new Endpoints())
        .addFilters(new ServerHttpObservationFilter(tracing.registry))
        .build();
  }

  // The gateway's client span is the parent of SOS's server span: the same trace id, and the
  // traceparent's span id as the parent. Without it SOS would start a trace of its own and the
  // request would show up in Jaeger as two.
  @Test
  void aRequest_continuesTheGatewaysTrace() throws Exception {
    mvc()
        .perform(get("/api/v1/sos/abc").header("traceparent", TRACEPARENT))
        .andExpect(status().isOk());

    assertThat(tracing.finished()).hasSize(1);
    SpanData server = tracing.finished().getFirst();
    assertThat(server.getKind()).isEqualTo(SpanKind.SERVER);
    assertThat(server.getTraceId()).isEqualTo(TRACE_ID);
    assertThat(server.getParentSpanId()).isEqualTo(PARENT_SPAN_ID);
    assertThat(server.getParentSpanContext().isRemote()).isTrue();
  }

  @Test
  void probes_areNotTraced() throws Exception {
    MockMvc mvc = mvc();
    for (String probe : List.of("/", "/healthy", "/actuator/health")) {
      mvc.perform(get(probe).header("traceparent", TRACEPARENT)).andExpect(status().isOk());
    }
    assertThat(tracing.finished()).isEmpty();
  }

  @Test
  void isProbe_coversOnlyTheProbePaths() {
    assertThat(TracingConfig.isProbe("/")).isTrue();
    assertThat(TracingConfig.isProbe("/healthy")).isTrue();
    assertThat(TracingConfig.isProbe("/actuator")).isTrue();
    assertThat(TracingConfig.isProbe("/actuator/prometheus")).isTrue();
    assertThat(TracingConfig.isProbe("/api/v1/sos")).isFalse();
    assertThat(TracingConfig.isProbe("/healthyish")).isFalse();
  }

  // A statement with no request, listener or push around it (a sweep, a location ping) is dropped;
  // the same statement inside a request stays.
  @Test
  void jdbcObservations_onlyInsideSomething() {
    tracing.registry.observationConfig().observationPredicate(new TracingConfig().tracingScope());

    Observation.createNotStarted("jdbc.query", tracing.registry).observe(() -> {});
    assertThat(tracing.finished()).isEmpty();

    Observation.createNotStarted("request", tracing.registry)
        .observe(
            () -> Observation.createNotStarted("jdbc.query", tracing.registry).observe(() -> {}));
    assertThat(tracing.finished()).extracting(SpanData::getName).contains("jdbc.query");
  }

  // Every record the service publishes carries the trace context in its headers, so the listener
  // that takes it (and anything it does, such as a push) joins the request's trace. The JSON value
  // is untouched: consumers that do not trace see an extra header and nothing else.
  @Test
  void aPublishedRecord_carriesTheTraceparent() {
    MockProducer<String, String> producer =
        new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
    KafkaTemplate<String, String> template =
        new KafkaConfig.ObservedKafkaTemplate<>(new MockProducerFactory<>(() -> producer));
    template.setObservationRegistry(tracing.registry);

    Observation request = Observation.start("request", tracing.registry);
    try (Observation.Scope ignored = request.openScope()) {
      template.send("sos.created", "sos-1", "{\"sosId\":\"sos-1\"}").join();
    } finally {
      request.stop();
    }

    Header traceparent = producer.history().getFirst().headers().lastHeader("traceparent");
    assertThat(traceparent).as("traceparent header").isNotNull();
    String value = new String(traceparent.value(), StandardCharsets.UTF_8);
    SpanData send =
        tracing.finished().stream()
            .filter(s -> s.getKind() == SpanKind.PRODUCER)
            .findFirst()
            .orElseThrow();
    assertThat(value).startsWith("00-" + send.getTraceId() + "-" + send.getSpanId() + "-");
    assertThat(producer.history().getFirst().value()).isEqualTo("{\"sosId\":\"sos-1\"}");
  }

  @Test
  void everyTemplateAndListenerFactory_isObserved() {
    KafkaProperties props = new KafkaProperties();
    props.getConsumer().setGroupId("tracing-test");
    KafkaConfig config = new KafkaConfig(props);

    assertThat(config.kafkaTemplate()).isInstanceOf(KafkaConfig.ObservedKafkaTemplate.class);
    assertThat(config.deadLetterBytesTemplate())
        .isInstanceOf(KafkaConfig.ObservedKafkaTemplate.class);
    assertThat(
            config.kafkaListenerContainerFactory().getContainerProperties().isObservationEnabled())
        .isTrue();
    assertThat(
            config
                .mediaUploadedListenerContainerFactory()
                .getContainerProperties()
                .isObservationEnabled())
        .isTrue();
    assertThat(
            config
                .notificationListenerContainerFactory()
                .getContainerProperties()
                .isObservationEnabled())
        .isTrue();
  }

  // Spring's default client convention records the full URI, query included; a query can carry
  // coordinates or a phone number.
  @Test
  void clientSpans_dropTheQuery() {
    ClientRequestObservationContext context =
        new ClientRequestObservationContext(
            new MockClientHttpRequest(
                HttpMethod.GET, URI.create("http://medusa:9000/store/x?phone=%2B855&lat=11.5")));

    KeyValue url =
        new TracingConfig.QuerylessClientRequestConvention()
            .getHighCardinalityKeyValues(context).stream()
                .filter(kv -> kv.getKey().equals("http.url"))
                .findFirst()
                .orElseThrow();
    assertThat(url.getValue()).isEqualTo("http://medusa:9000/store/x");
  }

  // A down Jaeger must not write a stack trace per batch for as long as it is down; the result
  // still reaches the span processor unchanged.
  @Test
  void quietExporter_passesTheResultThrough() {
    CompletableResultCode failed =
        CompletableResultCode.ofExceptionalFailure(new RuntimeException("x"));
    SpanExporter failing =
        new SpanExporter() {
          @Override
          public CompletableResultCode export(Collection<SpanData> spans) {
            return failed;
          }

          @Override
          public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
          }

          @Override
          public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
          }
        };

    SpanExporter quiet =
        new TracingConfig.QuietSpanExporter(failing, "http://localhost:4318/v1/traces");

    assertThat(quiet.export(List.of())).isSameAs(failed);
    assertThat(quiet.export(List.of()).isSuccess()).isFalse();
    assertThat(
            TracingConfig.QuietSpanExporter.reason(
                new RuntimeException("wrap", new java.io.IOException("refused"))))
        .isEqualTo("IOException: refused");
  }
}
