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

  // 401, not 403: the app has to know to send the user back through login.
  @Test
  void loggedOutSession_isUnauthorized() {
    assertThatThrownBy(() -> resolve(Map.of("isLogout", true)))
        .isInstanceOf(UserContextRejectedException.class)
        .extracting(e -> ((UserContextRejectedException) e).status())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unauth", "expired", "failed", "EXPIRED", " Failed "})
  void deadTokenStatus_isUnauthorized(String tokenStatus) {
    assertThatThrownBy(() -> resolve(Map.of("tokenStatus", tokenStatus)))
        .isInstanceOf(UserContextRejectedException.class)
        .extracting(e -> ((UserContextRejectedException) e).status())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  // 403, not 401: logging in again will not fix it — the verification has to be finished.
  @ParameterizedTest
  @CsvSource({"otpVerify,false", "profileVerify,false"})
  void unverifiedProfile_isForbidden(String field, boolean value) {
    assertThatThrownBy(() -> resolve(Map.of(field, value)))
        .isInstanceOf(UserContextRejectedException.class)
        .extracting(e -> ((UserContextRejectedException) e).status())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  // A flag the gateway omitted is not a flag that passed.
  @Test
  void missingVerificationFlags_areForbidden() {
    String context =
        "{\"user\":{\"uuid\":\"" + ID + "\",\"role\":\"USER\",\"tokenStatus\":\"active\"}}";

    assertThatThrownBy(
            () ->
                sdk.resolveCurrentUser(
                    headers(Map.of(AppHeaderContext.USER_CONTEXT_HEADER, context))))
        .isInstanceOf(UserContextRejectedException.class)
        .extracting(e -> ((UserContextRejectedException) e).status())
        .isEqualTo(HttpStatus.FORBIDDEN);
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

  @Test
  void legacyUserHeader_stillResolves() {
    Optional<CurrentUser> caller =
        sdk.resolveCurrentUser(headers(Map.of(GatewayIdentityFilter.USER_HEADER, ID.toString())));

    assertThat(caller)
        .get()
        .extracting(CurrentUser::id, CurrentUser::role)
        .containsExactly(ID, CallerRole.USER);
  }

  @Test
  void legacyMechanicHeader_stillResolves() {
    Optional<CurrentUser> caller =
        sdk.resolveCurrentUser(
            headers(Map.of(GatewayIdentityFilter.MECHANIC_HEADER, ID.toString())));

    assertThat(caller).get().extracting(CurrentUser::role).isEqualTo(CallerRole.MECHANIC);
  }

  // Both legacy headers means the edge is misconfigured. Guessing which one is real is how an
  // impersonation hole gets shipped.
  @Test
  void bothLegacyHeaders_leaveTheCallerAnonymous() {
    assertThat(
            sdk.resolveCurrentUser(
                headers(
                    Map.of(
                        GatewayIdentityFilter.USER_HEADER, UUID.randomUUID().toString(),
                        GatewayIdentityFilter.MECHANIC_HEADER, UUID.randomUUID().toString()))))
        .isEmpty();
  }

  // The verified context wins: the legacy header carries no verification at all.
  @Test
  void userContext_winsOverLegacyHeaders() {
    CurrentUser caller =
        sdk.resolveCurrentUser(
                headers(
                    Map.of(
                        AppHeaderContext.USER_CONTEXT_HEADER,
                            userContext(Map.of("role", "MECHANIC")),
                        GatewayIdentityFilter.USER_HEADER, UUID.randomUUID().toString())))
            .orElseThrow();

    assertThat(caller.id()).isEqualTo(ID);
    assertThat(caller.role()).isEqualTo(CallerRole.MECHANIC);
  }
}
