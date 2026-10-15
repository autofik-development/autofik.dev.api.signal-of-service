package com.autofik.sos.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Turns the JAEGER_* variables into Spring Boot's tracing properties, reading them exactly as the
 * gateway and SSO do (SSO's {@code src/tracing.ts} is the reference), so one value in a shared env
 * file means the same thing in all three services.
 *
 * <p>Not {@code ${JAEGER_...:default}} placeholders in application.yaml, because Spring reads those
 * its own way and the services would drift: a blank {@code JAEGER_SAMPLE_RATIO} became Boot's 10%
 * default instead of all requests, {@code off}/{@code 0}/{@code no} also stopped export, and a
 * ratio outside 0..1 or a blank endpoint stopped the service from starting. Here, as in SSO, only
 * the exact string {@code false} turns export off, a blank value means the default, and the ratio
 * is clamped.
 *
 * <p>Added last, below every config file and environment variable: application-test.yaml (export
 * off) and an explicit {@code MANAGEMENT_TRACING_*} setting still win over a developer's .env.
 * Standard {@code OTEL_*} variables, which Boot maps itself, win too.
 */
public class JaegerEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  static final String ENABLED = "JAEGER_ENABLED";
  static final String SERVICE_NAME = "JAEGER_SERVICE_NAME";
  static final String ENDPOINT = "JAEGER_EXPORTER_OTLP_ENDPOINT";
  static final String SAMPLE_RATIO = "JAEGER_SAMPLE_RATIO";

  static final String DEFAULT_SERVICE_NAME = "autofik-sos-service";
  static final String DEFAULT_ENDPOINT = "http://localhost:4318/v1/traces";

  static final String EXPORT_ENABLED_PROPERTY = "management.tracing.export.enabled";
  static final String SERVICE_NAME_PROPERTY =
      "management.opentelemetry.resource-attributes[service.name]";
  static final String ENDPOINT_PROPERTY = "management.opentelemetry.tracing.export.otlp.endpoint";
  static final String SAMPLE_RATIO_PROPERTY = "management.tracing.sampling.probability";

  static final String SOURCE_NAME = "jaegerTracing";

  private final Log log;

  public JaegerEnvironmentPostProcessor(DeferredLogFactory logFactory) {
    this.log = logFactory.getLog(JaegerEnvironmentPostProcessor.class);
  }

  // After config data, so the values the .env import supplies are visible too.
  @Override
  public int getOrder() {
    return ConfigDataEnvironmentPostProcessor.ORDER + 1;
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    environment
        .getPropertySources()
        .addLast(new MapPropertySource(SOURCE_NAME, properties(environment::getProperty)));
  }

  /** The Boot properties for these variables; {@code env} answers null for an unset one. */
  Map<String, Object> properties(UnaryOperator<String> env) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(EXPORT_ENABLED_PROPERTY, exportEnabled(env.apply(ENABLED)));
    properties.put(SERVICE_NAME_PROPERTY, orDefault(env.apply(SERVICE_NAME), DEFAULT_SERVICE_NAME));
    properties.put(ENDPOINT_PROPERTY, orDefault(env.apply(ENDPOINT), DEFAULT_ENDPOINT));
    properties.put(SAMPLE_RATIO_PROPERTY, sampleRatio(env.apply(SAMPLE_RATIO)));
    return properties;
  }

  private boolean exportEnabled(String raw) {
    if (raw != null && !raw.equals("false") && !raw.equals("true") && !raw.isBlank()) {
      // Spring would read off/no/0/FALSE as false; the gateway and SSO keep exporting.
      log.warn(ENABLED + "=" + raw + " is not \"false\", so span export stays on");
    }
    return !"false".equals(raw);
  }

  private double sampleRatio(String raw) {
    if (raw == null || raw.isBlank()) {
      return 1.0;
    }
    double ratio;
    try {
      ratio = Double.parseDouble(raw.trim());
    } catch (NumberFormatException notANumber) {
      log.warn(SAMPLE_RATIO + "=" + raw + " is not a number between 0 and 1; sampling every trace");
      return 1.0;
    }
    if (Double.isNaN(ratio)) {
      return 1.0;
    }
    double clamped = Math.min(1.0, Math.max(0.0, ratio));
    if (clamped != ratio) {
      log.warn(SAMPLE_RATIO + "=" + raw + " is outside 0..1; using " + clamped);
    }
    return clamped;
  }

  private static String orDefault(String raw, String fallback) {
    return raw == null || raw.isBlank() ? fallback : raw.trim();
  }
}
