package com.autofik.sos.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SpanExporters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * What gets traced beyond Spring Boot's defaults, and what does not.
 *
 * <p>Spans come from Micrometer observations: Boot and Spring instrument HTTP requests, the Kafka
 * templates and listeners (switched on in {@link KafkaConfig}), Redis commands and the Medusa
 * client; datasource-micrometer adds JDBC; the Google Maps, S3 and OneSignal calls are observed
 * where they are made. Export settings come from the JAEGER_* variables ({@link
 * JaegerEnvironmentPostProcessor}).
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfig {

  /**
   * Keeps probes and orphaned statements out of Jaeger.
   *
   * <p>The load balancer polls {@code /}, {@code /healthy} and {@code /actuator/**} every few
   * seconds, and each poll would be a trace. Turning their observation off also takes them out of
   * the {@code http.server.requests} metric, which they only skewed.
   *
   * <p>A JDBC statement with nothing around it comes from a sweep, a probe or a STOMP location ping
   * (STOMP frames are not traced): one single-span trace every few seconds, explaining nothing.
   * Inside a request, a Kafka listener or a push it is kept. The same goes for Spring Security's
   * observations when they are switched on.
   */
  @Bean
  ObservationPredicate tracingScope() {
    return (name, context) -> {
      if (context instanceof ServerRequestObservationContext server) {
        HttpServletRequest request = server.getCarrier();
        return request == null || !isProbe(request.getRequestURI());
      }
      if (context.getParentObservation() == null) {
        return !name.startsWith("jdbc.") && !name.startsWith("spring.security.");
      }
      return true;
    };
  }

  static boolean isProbe(String path) {
    return path.equals("/")
        || path.equals("/healthy")
        || path.equals("/actuator")
        || path.startsWith("/actuator/");
  }

  /**
   * Drops a Redis command span that has no parent: a location ping over STOMP (throttle key,
   * pub/sub relay) or a health check, several a second across the fleet.
   *
   * <p>Done at export, not with the predicate above, because Lettuce creates the observation on its
   * I/O thread and attaches the parent afterwards, so a predicate would see no parent for any
   * command. A Redis span has no children, so dropping it leaves no hole in a trace. The command
   * metric is unaffected.
   */
  @Bean
  SpanExportingPredicate noOrphanRedisCommands() {
    return span -> !(isRoot(span) && "redis".equals(span.getTags().get("db.system")));
  }

  private static boolean isRoot(FinishedSpan span) {
    return span.getParentId() == null || !SpanId.isValid(span.getParentId());
  }

  /** Boot's exporters, each wrapped so a failing export is logged once (see QuietSpanExporter). */
  @Bean
  SpanExporters quietSpanExporters(
      ObjectProvider<SpanExporter> exporters,
      @Value("${management.opentelemetry.tracing.export.otlp.endpoint:}") String endpoint) {
    return SpanExporters.of(
        exporters
            .orderedStream()
            .map(exporter -> (SpanExporter) new QuietSpanExporter(exporter, endpoint))
            .toList());
  }

  /**
   * Logs the first failed export and the first success after it, and nothing in between.
   *
   * <p>Export runs on the span processor's own thread, behind a bounded queue, so a down or slow
   * Jaeger never reaches a request: spans are dropped once the queue is full. What it did reach was
   * the log, at one line per failed batch (throttled by OpenTelemetry to one a minute, forever,
   * each with a stack trace); that logger is turned off in application.yaml in favour of this.
   */
  @Slf4j
  static final class QuietSpanExporter implements SpanExporter {

    private final SpanExporter delegate;
    private final String target;
    private final AtomicBoolean failing = new AtomicBoolean();

    QuietSpanExporter(SpanExporter delegate, String endpoint) {
      this.delegate = delegate;
      this.target = endpoint.isBlank() ? delegate.getClass().getSimpleName() : endpoint;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      CompletableResultCode result = delegate.export(spans);
      result.whenComplete(
          () -> {
            if (result.isSuccess()) {
              if (failing.compareAndSet(true, false)) {
                log.info("Span export to {} works again", target);
              }
            } else if (failing.compareAndSet(false, true)) {
              log.warn(
                  "Span export to {} failed ({}); spans are dropped until it works again, and"
                      + " further failures are not logged",
                  target,
                  reason(result.getFailureThrowable()));
            }
          });
      return result;
    }

    // OpenTelemetry wraps the I/O error in its own (internal) exception type; the cause is the part
    // that says what went wrong.
    static String reason(Throwable failure) {
      if (failure == null) {
        return "no reason given";
      }
      Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
      return cause.getMessage() == null
          ? cause.getClass().getSimpleName()
          : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    @Override
    public CompletableResultCode flush() {
      return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
      return delegate.shutdown();
    }
  }

  /**
   * HTTP client spans without the query string in {@code http.url}: a query can carry what must
   * never reach a span (coordinates, phone numbers). Spring's default records the full URI. For any
   * RestClient this service builds itself; the Medusa client uses it.
   */
  public static final class QuerylessClientRequestConvention
      extends DefaultClientRequestObservationConvention {

    @Override
    protected KeyValue requestUri(ClientRequestObservationContext context) {
      KeyValue uri = super.requestUri(context);
      int query = uri.getValue().indexOf('?');
      return query < 0 ? uri : KeyValue.of(uri.getKey(), uri.getValue().substring(0, query));
    }
  }
}
