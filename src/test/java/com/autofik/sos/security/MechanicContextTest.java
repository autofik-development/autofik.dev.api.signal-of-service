package com.autofik.sos.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mechanic app's half of the same contract: {@code mechanic-context} decides who this service
 * acts as on a bid, an arrival and a completion, so every way it can be wrong is worth pinning.
 *
 * @see MicroserviceTokenSdkTest for the driver side.
 */
class MechanicContextTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final MicroserviceTokenSdk sdk = new MicroserviceTokenSdk(objectMapper);

  private static final UUID ID = UUID.randomUUID();

  /** A mechanic block in the shape the gateway sends, with the named fields overridden. */
  private String mechanicContext(Map<String, Object> overrides) {
    Map<String, Object> mechanic = new LinkedHashMap<>();
    mechanic.put("uuid", ID.toString());
    mechanic.put("role", "MECHANIC");
    mechanic.put("deviceId", "device-1");
    mechanic.put("otpVerify", true);
    mechanic.put("profileVerify", true);
    mechanic.put("tokenStatus", "active");
    mechanic.put("isLogout", false);
    mechanic.putAll(overrides);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("mechanic", mechanic);
    return objectMapper.writeValueAsString(payload);
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
                AppHeaderContext.MECHANIC_CONTEXT_HEADER,
                mechanicContext(overrides),
                AppHeaderContext.SERVICE_CODE_HEADER,
                "SOS")));
  }

  @Test
  void verifiedContext_resolvesTheGatewaysMechanic() {
    CurrentUser caller = resolve(Map.of()).orElseThrow();

    assertThat(caller.id()).isEqualTo(ID);
    assertThat(caller.role()).isEqualTo(CallerRole.MECHANIC);
    assertThat(caller.isMechanic()).isTrue();
    assertThat(caller.deviceId()).isEqualTo("device-1");
    assertThat(caller.serviceCode()).isEqualTo("SOS");
  }

  // A garage account works a breakdown exactly as a lone mechanic does.
  @Test
  void garageRole_isAMechanic() {
    assertThat(resolve(Map.of("role", "GARAGE")).orElseThrow().role())
        .isEqualTo(CallerRole.MECHANIC);
  }

  // The key already said mechanic. Turning one away over a field the gateway left blank would
  // strand them mid-job for nothing.
  @Test
  void missingRole_fallsBackToTheBlockItArrivedIn() {
    String context =
        "{\"mechanic\":{\"uuid\":\""
            + ID
            + "\",\"otpVerify\":true,"
            + "\"profileVerify\":true,\"tokenStatus\":\"active\"}}";

    assertThat(
            sdk.resolveCurrentUser(
                    headers(Map.of(AppHeaderContext.MECHANIC_CONTEXT_HEADER, context)))
                .orElseThrow()
                .role())
        .isEqualTo(CallerRole.MECHANIC);
  }

  // A driver role inside a mechanic block is the edge sending something it does not mean. Picking
  // a winner is how a driver ends up holding the mechanic endpoints.
  @Test
  void driverRoleInsideAMechanicBlock_isForbidden() {
    assertThatThrownBy(() -> resolve(Map.of("role", "USER")))
        .isInstanceOf(UserContextRejectedException.class)
        .extracting(e -> ((UserContextRejectedException) e).status())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  // Session state is the internal gateway's check, not repeated here.
  @Test
  void sessionFields_areNotCheckedHere() {
    assertThat(resolve(Map.of("isLogout", true))).isPresent();
    assertThat(resolve(Map.of("tokenStatus", "expired"))).isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {"not json at all", "{}", "{\"mechanic\":{}}"})
  void unusableContext_leavesTheCallerAnonymous(String context) {
    assertThat(
            sdk.resolveCurrentUser(
                headers(Map.of(AppHeaderContext.MECHANIC_CONTEXT_HEADER, context))))
        .isEmpty();
  }

  // Not every environment gives the mechanic block a header of its own.
  @Test
  void mechanicBlockInsideUserContextHeader_resolvesTheSameWay() {
    CurrentUser caller =
        sdk.resolveCurrentUser(
                headers(Map.of(AppHeaderContext.USER_CONTEXT_HEADER, mechanicContext(Map.of()))))
            .orElseThrow();

    assertThat(caller.id()).isEqualTo(ID);
    assertThat(caller.role()).isEqualTo(CallerRole.MECHANIC);
  }

  // Both blocks, same person: the mechanic claim is the more specific one.
  @Test
  void bothBlocksNamingOnePerson_resolveAsTheMechanic() {
    String user =
        "{\"user\":{\"uuid\":\""
            + ID
            + "\",\"role\":\"USER\",\"otpVerify\":true,"
            + "\"profileVerify\":true,\"tokenStatus\":\"active\"}}";

    CurrentUser caller =
        sdk.resolveCurrentUser(
                headers(
                    Map.of(
                        AppHeaderContext.USER_CONTEXT_HEADER,
                        user,
                        AppHeaderContext.MECHANIC_CONTEXT_HEADER,
                        mechanicContext(Map.of()))))
            .orElseThrow();

    assertThat(caller.role()).isEqualTo(CallerRole.MECHANIC);
  }

  // Two different people named on one request is a misconfigured edge, and neither claim is more
  // trustworthy than the other.
  @Test
  void blocksNamingDifferentPeople_areUnauthorized() {
    String other =
        "{\"user\":{\"uuid\":\""
            + UUID.randomUUID()
            + "\",\"role\":\"USER\",\"otpVerify\":true,"
            + "\"profileVerify\":true,\"tokenStatus\":\"active\"}}";

    assertThatThrownBy(
            () ->
                sdk.resolveCurrentUser(
                    headers(
                        Map.of(
                            AppHeaderContext.USER_CONTEXT_HEADER,
                            other,
                            AppHeaderContext.MECHANIC_CONTEXT_HEADER,
                            mechanicContext(Map.of())))))
        .isInstanceOf(UserContextRejectedException.class)
        .extracting(e -> ((UserContextRejectedException) e).status())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
