package com.autofik.sos.config;

import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;

/**
 * The tracing stack production gets from Spring Boot — Micrometer observations bridged to an
 * OpenTelemetry tracer, W3C propagation, the sender/receiver handlers that inject and extract
 * {@code traceparent} — with an in-memory exporter where Jaeger would be. What a test reads from
 * {@link #finished()} is what would have been exported. Nothing global is touched, so tests using
 * it can run side by side.
 */
public final class TestTracing implements AutoCloseable {

  /** The caller's trace, as the gateway would send it. */
  public static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  public static final String PARENT_SPAN_ID = "00f067aa0ba902b7";
  public static final String TRACEPARENT = "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01";

  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
  private final SdkTracerProvider provider =
      SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();

  /** Observations made through this registry become spans in {@link #finished()}. */
  public final ObservationRegistry registry = ObservationRegistry.create();

  public TestTracing() {
    ContextPropagators propagators =
        ContextPropagators.create(
            TextMapPropagator.composite(
                W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance()));
    Tracer otelTracer = provider.get("sos-test");
    OtelTracer tracer = new OtelTracer(otelTracer, new OtelCurrentTraceContext(), event -> {});
    OtelPropagator propagator = new OtelPropagator(propagators, otelTracer);
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler.FirstMatchingCompositeObservationHandler(
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                new DefaultTracingObservationHandler(tracer)));
  }

  public List<SpanData> finished() {
    return exporter.getFinishedSpanItems();
  }

  @Override
  public void close() {
    provider.close();
  }
}
