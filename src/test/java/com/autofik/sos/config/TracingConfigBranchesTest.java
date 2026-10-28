package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/** The remaining branches of {@link TracingConfig}; the pipeline itself is in its own test. */
class TracingConfigBranchesTest {

  private final TracingConfig config = new TracingConfig();

  private static ServerRequestObservationContext server(HttpServletRequest request) {
    ServerRequestObservationContext context = mock(ServerRequestObservationContext.class);
    when(context.getCarrier()).thenReturn(request);
    return context;
  }

  private static FinishedSpan span(String name, Map<String, String> tags, String parentId) {
    FinishedSpan span = mock(FinishedSpan.class);
    when(span.getName()).thenReturn(name);
    when(span.getTags()).thenReturn(tags);
    when(span.getParentId()).thenReturn(parentId);
    return span;
  }

  @Test
  void aServerRequest_isObserved_unlessItIsAProbe() {
    ObservationPredicate scope = config.tracingScope();
    HttpServletRequest api = mock(HttpServletRequest.class);
    when(api.getRequestURI()).thenReturn("/api/v1/sos");
    HttpServletRequest probe = mock(HttpServletRequest.class);
    when(probe.getRequestURI()).thenReturn("/actuator/health");

    assertThat(scope.test("http.server.requests", server(null))).isTrue();
    assertThat(scope.test("http.server.requests", server(api))).isTrue();
    assertThat(scope.test("http.server.requests", server(probe))).isFalse();
  }

  @Test
  void anObservationWithNoParent_isDropped_onlyForJdbcAndSpringSecurity() {
    ObservationPredicate scope = config.tracingScope();
    Observation.Context root = new Observation.Context();

    assertThat(scope.test("jdbc.query", root)).isFalse();
    assertThat(scope.test("spring.security.filterchains", root)).isFalse();
    assertThat(scope.test("kafka.consume", root)).isTrue();
  }

  @Test
  void anObservationWithAParent_isAlwaysKept() {
    ObservationPredicate scope = config.tracingScope();
    Observation.Context child = new Observation.Context();
    child.setParentObservation(mock(Observation.class));

    assertThat(scope.test("jdbc.query", child)).isTrue();
  }

  @Test
  void aRedisSpan_isExportedOnlyWhenItHasAParent_andIsNotAnIgnoredCommand() {
    SpanExportingPredicate predicate = config.noOrphanRedisCommands();
    String parent = "00f067aa0ba902b7";

    // No tags at all: only the span name can mark it as ignored.
    assertThat(predicate.isExportable(span("hmset", null, parent))).isFalse();
    assertThat(predicate.isExportable(span("GET", Map.of("db.system", "redis"), parent))).isTrue();
    // A command whose tag names no operation falls back to the span name.
    assertThat(predicate.isExportable(span("del", Map.of("db.system", "redis"), parent))).isFalse();
    // A nameless span with no operation is just exported.
    assertThat(predicate.isExportable(span(null, Map.of("db.system", "redis"), parent))).isTrue();
    // Not Redis: a root span is kept; an invalid parent id counts as a root.
    assertThat(predicate.isExportable(span("query", Map.of("db.system", "postgresql"), null)))
        .isTrue();
    assertThat(predicate.isExportable(span("get", Map.of("db.system", "redis"), "not-a-span-id")))
        .isFalse();
  }

  @Test
  void quietSpanExporters_wrapEveryExporterTheContextHas() {
    @SuppressWarnings("unchecked")
    ObjectProvider<SpanExporter> exporters = mock(ObjectProvider.class);
    when(exporters.orderedStream()).thenReturn(Stream.of(mock(SpanExporter.class)));

    assertThat(config.quietSpanExporters(exporters, "http://jaeger:4318/v1/traces")).isNotNull();
  }

  /** An exporter whose next export answers with whatever the test set. */
  private static final class Scripted implements SpanExporter {
    final AtomicReference<CompletableResultCode> next = new AtomicReference<>();
    int flushed;
    int shutDown;

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      return next.get();
    }

    @Override
    public CompletableResultCode flush() {
      flushed++;
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      shutDown++;
      return CompletableResultCode.ofSuccess();
    }
  }

  @Test
  void quietExporter_logsTheFailureOnce_andTheRecovery_andPassesEveryResultThrough() {
    Scripted delegate = new Scripted();
    SpanExporter quiet = new TracingConfig.QuietSpanExporter(delegate, " ");

    delegate.next.set(CompletableResultCode.ofFailure());
    assertThat(quiet.export(List.of()).isSuccess()).isFalse();
    assertThat(quiet.export(List.of()).isSuccess()).isFalse(); // second failure: not logged again

    delegate.next.set(CompletableResultCode.ofSuccess());
    assertThat(quiet.export(List.of()).isSuccess()).isTrue(); // first success after failures
    assertThat(quiet.export(List.of()).isSuccess()).isTrue(); // success when not failing
  }

  @Test
  void quietExporter_flushAndShutdown_goToTheDelegate() {
    Scripted delegate = new Scripted();
    SpanExporter quiet = new TracingConfig.QuietSpanExporter(delegate, "http://jaeger:4318");

    assertThat(quiet.flush().isSuccess()).isTrue();
    assertThat(quiet.shutdown().isSuccess()).isTrue();

    assertThat(delegate.flushed).isEqualTo(1);
    assertThat(delegate.shutDown).isEqualTo(1);
  }

  @Test
  void theFailureReason_isReadableWhateverTheShape() {
    assertThat(TracingConfig.QuietSpanExporter.reason(null)).isEqualTo("no reason given");
    assertThat(TracingConfig.QuietSpanExporter.reason(new IllegalStateException("boom")))
        .isEqualTo("IllegalStateException: boom");
    assertThat(
            TracingConfig.QuietSpanExporter.reason(
                new RuntimeException("wrapper", new java.io.IOException())))
        .isEqualTo("IOException");
  }
}
