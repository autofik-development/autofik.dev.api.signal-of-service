package com.autofik.sos.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The header is the whole of the identity, so every way it can be wrong is worth pinning: what is
 * waved through decides who this service acts as.
 */
class MicroserviceTokenSdkTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final MicroserviceTokenSdk sdk = new MicroserviceTokenSdk(objectMapper);

  private static final UUID ID = UUID.randomUUID();

  /** A context in the shape the gateway sends, with the named fields overridden. */
  private String userContext(Map<String, Object> overrides) {
    Map<String, Object> user =
        new java.util.LinkedHashMap<>(
            Map.of(
                "uuid",
                ID.toString(),
                "role",
                "USER",
                "deviceId",
                "device-1",
                "otpVerify",
                true,
                "profileVerify",
                true,
                "tokenStatus",
                "active",
                "isLogout",
                false));
    user.putAll(overrides);
    try {
      return objectMapper.writeValueAsString(Map.of("user", user));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UnaryOperator<String> headers(Map<String, String> values) {
    return name ->
        values.entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase(name))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
  }

  private Optional<CurrentUser> resolve(Map<String, Object> overrides) {
    return sdk.resolveCurrentUser(
        headers(
            Map.of(
                AppHeaderContext.USER_CONTEXT_HEADER,
                userContext(overrides),
                AppHeaderContext.SERVICE_CODE_HEADER,
                "SOS")));
  }

  @Test
  void verifiedContext_resolvesTheGatewaysIdentity() {
    CurrentUser caller = resolve(Map.of()).orElseThrow();

    assertThat(caller.id()).isEqualTo(ID);
    assertThat(caller.role()).isEqualTo(CallerRole.USER);
    assertThat(caller.deviceId()).isEqualTo("device-1");
    assertThat(caller.serviceCode()).isEqualTo("SOS");
  }

  // A garage acts on a breakdown exactly as a lone mechanic does; a CUSTOM account is a driver.
  @ParameterizedTest
  @CsvSource({
    "MECHANIC,MECHANIC",
    "GARAGE,MECHANIC",
    "garage,MECHANIC",
    "USER,USER",
    "CUSTOM,USER",
    "CUSTOMER,USER",
    "' user ',USER"
  })
  void ssoRoles_mapOntoTheTwoRolesThisServiceHas(String ssoRole, CallerRole expected) {
    assertThat(resolve(Map.of("role", ssoRole)).orElseThrow().role()).isEqualTo(expected);
  }

  // Defaulting an unknown role to driver would hand it every driver endpoint.
  @Test
  void unknownRole_isForbidden() {
    assertThatThrownBy(() -> resolve(Map.of("role", "ADMIN")))
        .isInstanceOf(UserContextRejectedException.class)
        .extracting(e -> ((UserContextRejectedException) e).status())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  // The internal gateway verifies the token and refuses a logged-out or expired session before
  // forwarding; SOS does not repeat it.
  @Test
  void sessionFields_areNotCheckedHere() {
    assertThat(resolve(Map.of("isLogout", true))).isPresent();
    assertThat(resolve(Map.of("tokenStatus", "expired"))).isPresent();
  }

  // The internal gateway refuses unverified callers before forwarding; SOS does not repeat it.
  @ParameterizedTest
  @CsvSource({"otpVerify,false", "profileVerify,false"})
  void verificationFlags_areNotCheckedHere(String field, boolean value) {
    assertThat(resolve(Map.of(field, value))).isPresent();
  }

  @Test
  void missingVerificationFlags_resolve() {
    String context =
        "{\"user\":{\"uuid\":\"" + ID + "\",\"role\":\"USER\",\"tokenStatus\":\"active\"}}";

    assertThat(
            sdk.resolveCurrentUser(headers(Map.of(AppHeaderContext.USER_CONTEXT_HEADER, context))))
        .isPresent();
  }

  @Test
  void noHeaders_leaveTheCallerAnonymous() {
    assertThat(sdk.resolveCurrentUser(name -> null)).isEmpty();
  }

  // Anonymous rather than rejected: the chain's own 401 answers it, and permitAll routes
  // (health checks, the upgrade) still work.
  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json at all",
        "{}",
        "{\"user\":{}}",
        "{\"user\":{\"uuid\":\"not-a-uuid\",\"role\":\"USER\"}}"
      })
  void unusableContext_leavesTheCallerAnonymous(String context) {
    assertThat(
            sdk.resolveCurrentUser(headers(Map.of(AppHeaderContext.USER_CONTEXT_HEADER, context))))
        .isEmpty();
  }

  // Only user-context / mechanic-context identify a caller; the gateway sends nothing else.
  @Test
  void legacyHeaders_noLongerResolve() {
    assertThat(
            sdk.resolveCurrentUser(
                headers(Map.of("X-User-Id", ID.toString(), "X-Mechanic-Id", ID.toString()))))
        .isEmpty();
  }
}
