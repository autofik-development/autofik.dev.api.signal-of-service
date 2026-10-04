package com.autofik.sos.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.security.AppHeaderContext;
import com.autofik.sos.security.CurrentUser;
import com.autofik.sos.security.MicroserviceTokenSdk;
import com.autofik.sos.security.UserContextRejectedException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway headers are the whole of the caller's identity and arrive from the network. Whatever
 * is in them, this must answer with an identity, no identity, or a clean rejection — never a stray
 * exception (a 500 on every request) and never an identity the header did not name.
 */
class GatewayHeaderFuzzTest {

  private final ObjectMapper mapper = JsonMapper.builder().build();
  private final MicroserviceTokenSdk sdk = new MicroserviceTokenSdk(mapper);

  private Optional<CurrentUser> resolve(Map<String, String> headers) {
    UnaryOperator<String> lookup = headers::get;
    try {
      return sdk.resolveCurrentUser(lookup);
    } catch (UserContextRejectedException rejected) {
      return Optional.empty(); // the documented, clean refusal
    }
  }

  @Property(seed = Fuzz.SEED)
  void arbitraryHeaderText_neverThrowsUnexpectedly(
      @ForAll("headerText") String user,
      @ForAll("headerText") String mechanic,
      @ForAll("headerText") String serviceCode) {
    Map<String, String> headers = new HashMap<>();
    headers.put(AppHeaderContext.USER_CONTEXT_HEADER, user);
    headers.put(AppHeaderContext.MECHANIC_CONTEXT_HEADER, mechanic);
    headers.put(AppHeaderContext.SERVICE_CODE_HEADER, serviceCode);

    resolve(headers); // any exception other than the rejection fails the property
  }

  /** Well-formed JSON with the wrong types and values in the fields that matter. */
  @Property(seed = Fuzz.SEED)
  void structuredButWrongContext_neverThrowsUnexpectedly(
      @ForAll("contextJson") String user, @ForAll("contextJson") String mechanic) {
    Map<String, String> headers = new HashMap<>();
    headers.put(AppHeaderContext.USER_CONTEXT_HEADER, user);
    headers.put(AppHeaderContext.MECHANIC_CONTEXT_HEADER, mechanic);
    headers.put(AppHeaderContext.SERVICE_CODE_HEADER, "SOS");

    resolve(headers);
  }

  /** A caller is only ever the id the header named — nothing is invented or defaulted. */
  @Property(seed = Fuzz.SEED)
  void resolvedIdentity_isExactlyTheUuidInTheHeader(
      @ForAll("anyUuidish") String uuid, @ForAll("fieldValue") String role) {
    String json =
        "{\"user\":{\"uuid\":"
            + quote(uuid)
            + ",\"role\":"
            + role
            + ",\"otpVerify\":true,\"profileVerify\":true,\"tokenStatus\":\"active\","
            + "\"isLogout\":false}}";
    Map<String, String> headers = new HashMap<>();
    headers.put(AppHeaderContext.USER_CONTEXT_HEADER, json);
    headers.put(AppHeaderContext.SERVICE_CODE_HEADER, "SOS");

    Optional<CurrentUser> caller = resolve(headers);

    caller.ifPresent(
        c -> {
          assertThat(c.role()).isNotNull();
          assertThat(c.id()).isEqualTo(UUID.fromString(uuid.trim()));
        });
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  @Provide
  Arbitrary<String> headerText() {
    return Arbitraries.oneOf(Fuzz.nastyStrings(), Arbitraries.just(null));
  }

  @Provide
  Arbitrary<String> anyUuidish() {
    return Arbitraries.oneOf(
        Arbitraries.create(() -> UUID.randomUUID().toString()),
        Arbitraries.create(() -> UUID.randomUUID().toString().toUpperCase()),
        Arbitraries.create(() -> " " + UUID.randomUUID() + " "),
        Arbitraries.of("00000000-0000-0000-0000-000000000000", "not-a-uuid", "", "1"));
  }

  @Provide
  Arbitrary<String> fieldValue() {
    return Arbitraries.of(
        "\"USER\"",
        "\"MECHANIC\"",
        "\"ADMIN\"",
        "\"user\"",
        "null",
        "1",
        "true",
        "{}",
        "[]",
        "\"\"");
  }

  @Provide
  Arbitrary<String> contextJson() {
    Arbitrary<String> value =
        Arbitraries.of(
            "null",
            "true",
            "false",
            "0",
            "-1",
            "1e999",
            "\"x\"",
            "\"\"",
            "[]",
            "{}",
            "\"00000000-0000-0000-0000-000000000000\"",
            "\"11111111-1111-1111-1111-111111111111\"");
    Arbitrary<String> key =
        Arbitraries.of(
            "uuid", "role", "deviceId", "otpVerify", "profileVerify", "tokenStatus", "isLogout");
    Arbitrary<String> field = key.flatMap(k -> value.map(v -> "\"" + k + "\":" + v));
    Arbitrary<String> body = field.list().ofMaxSize(8).map(l -> "{" + String.join(",", l) + "}");
    Arbitrary<String> wrapped =
        Arbitraries.of("user", "mechanic", "admin", "")
            .flatMap(w -> body.map(b -> w.isEmpty() ? b : "{\"" + w + "\":" + b + "}"));
    return Arbitraries.oneOf(wrapped, Arbitraries.of("{}", "[]", "null", "", "{\"user\":null}"));
  }
}
