package com.autofik.sos.config;

import static com.autofik.sos.config.JaegerEnvironmentPostProcessor.ENDPOINT_PROPERTY;
import static com.autofik.sos.config.JaegerEnvironmentPostProcessor.EXPORT_ENABLED_PROPERTY;
import static com.autofik.sos.config.JaegerEnvironmentPostProcessor.SAMPLE_RATIO_PROPERTY;
import static com.autofik.sos.config.JaegerEnvironmentPostProcessor.SERVICE_NAME_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;

/**
 * The JAEGER_* variables must mean here exactly what they mean to the gateway and SSO, or one
 * shared env file would send SOS's spans somewhere else, or nowhere.
 */
class JaegerEnvironmentPostProcessorTest {

  private static final DeferredLogFactory LOGS = Supplier::get;

  private static Map<String, Object> properties(Map<String, String> env) {
    return new JaegerEnvironmentPostProcessor(LOGS).properties(env::get);
  }

  @Test
  void unset_meansTheContractDefaults() {
    assertThat(properties(Map.of()))
        .containsEntry(EXPORT_ENABLED_PROPERTY, true)
        .containsEntry(SERVICE_NAME_PROPERTY, "autofik-sos-service")
        .containsEntry(ENDPOINT_PROPERTY, "http://localhost:4318/v1/traces")
        // Not Boot's own 10%: every request is traced unless the variable says otherwise.
        .containsEntry(SAMPLE_RATIO_PROPERTY, 1.0);
  }

  @Test
  void setValues_areUsed() {
    assertThat(
            properties(
                Map.of(
                    "JAEGER_ENABLED", "true",
                    "JAEGER_SERVICE_NAME", "sos-staging",
                    "JAEGER_EXPORTER_OTLP_ENDPOINT", "http://jaeger:4318/v1/traces",
                    "JAEGER_SAMPLE_RATIO", "0.25")))
        .containsEntry(EXPORT_ENABLED_PROPERTY, true)
        .containsEntry(SERVICE_NAME_PROPERTY, "sos-staging")
        .containsEntry(ENDPOINT_PROPERTY, "http://jaeger:4318/v1/traces")
        .containsEntry(SAMPLE_RATIO_PROPERTY, 0.25);
  }

  // Only the exact string "false" turns export off — SSO's rule — where Spring alone would also
  // read off/no/0/FALSE as false.
  @ParameterizedTest
  @CsvSource({"false,false", "FALSE,true", "off,true", "0,true", "no,true", "true,true", "'',true"})
  void onlyTheStringFalse_turnsExportOff(String raw, boolean exported) {
    Map<String, String> env = new HashMap<>();
    env.put("JAEGER_ENABLED", raw);
    assertThat(properties(env)).containsEntry(EXPORT_ENABLED_PROPERTY, exported);
  }

  @ParameterizedTest
  @CsvSource({"2,1.0", "-1,0.0", "abc,1.0", "NaN,1.0", "'  ',1.0", "' 0.5 ',0.5", "0,0.0"})
  void ratio_isClampedAndABadOneMeansEverything(String raw, double ratio) {
    assertThat(properties(Map.of("JAEGER_SAMPLE_RATIO", raw)))
        .containsEntry(SAMPLE_RATIO_PROPERTY, ratio);
  }

  @Test
  void blankNameAndEndpoint_fallBackToTheDefaults() {
    assertThat(properties(Map.of("JAEGER_SERVICE_NAME", " ", "JAEGER_EXPORTER_OTLP_ENDPOINT", "")))
        .containsEntry(SERVICE_NAME_PROPERTY, "autofik-sos-service")
        .containsEntry(ENDPOINT_PROPERTY, "http://localhost:4318/v1/traces");
  }

  // Added last: a profile (application-test.yaml turns export off) or an explicit
  // MANAGEMENT_TRACING_* setting outranks the JAEGER_* mapping.
  @Test
  void explicitBootProperties_win() {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "config",
                Map.of(
                    "JAEGER_SAMPLE_RATIO",
                    "0.5",
                    "JAEGER_SERVICE_NAME",
                    "from-jaeger-var",
                    EXPORT_ENABLED_PROPERTY,
                    "false")));

    new JaegerEnvironmentPostProcessor(LOGS)
        .postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty(EXPORT_ENABLED_PROPERTY)).isEqualTo("false");
    assertThat(environment.getProperty(SAMPLE_RATIO_PROPERTY)).isEqualTo("0.5");
    assertThat(environment.getProperty(SERVICE_NAME_PROPERTY)).isEqualTo("from-jaeger-var");
  }

  // Without its spring.factories entry the class is never run and the variables are ignored.
  @Test
  void isRegisteredWithSpringBoot() {
    assertThat(
            SpringFactoriesLoader.forDefaultResourceLocation()
                .load(
                    EnvironmentPostProcessor.class,
                    ArgumentResolver.of(DeferredLogFactory.class, LOGS)))
        .anyMatch(JaegerEnvironmentPostProcessor.class::isInstance);
  }
}
