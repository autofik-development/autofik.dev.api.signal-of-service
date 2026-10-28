package com.autofik.sos.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** How the gateway's headers become one acting identity, and what is refused. */
class AppHeaderContextTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
  private static final String MECHANIC_ID = "22222222-2222-2222-2222-222222222222";

  private static AppHeaderContext from(Map<String, String> headers) {
    return AppHeaderContext.from(headers::get, JSON);
  }

  private static Map<String, String> headers(String... kv) {
    Map<String, String> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  private static String user(String uuid) {
    return "{\"user\":{\"uuid\":" + (uuid == null ? "null" : "\"" + uuid + "\"") + "}}";
  }

  private static String mechanic(String uuid) {
    return "{\"mechanic\":{\"uuid\":" + (uuid == null ? "null" : "\"" + uuid + "\"") + "}}";
  }

  @Test
  void noHeaders_isAnonymous() {
    AppHeaderContext ctx = from(headers());

    assertThat(ctx.user()).isNull();
    assertThat(ctx.mechanic()).isNull();
    assertThat(ctx.identity()).isNull();
    assertThat(ctx.ipAddress()).isNull();
  }

  @Test
  void aUserHeader_isTheIdentity() {
    AppHeaderContext ctx = from(headers("user-context", user(USER_ID)));

    assertThat(ctx.identity().uuid()).isEqualTo(USER_ID);
    assertThat(ctx.user().uuid()).isEqualTo(USER_ID);
  }

  @Test
  void aMechanicHeader_isTheIdentity_andImpliesTheMechanicRole() {
    AppHeaderContext ctx = from(headers("mechanic-context", mechanic(MECHANIC_ID)));

    assertThat(ctx.identity().uuid()).isEqualTo(MECHANIC_ID);
    assertThat(ctx.identity().impliedRole()).contains(CallerRole.MECHANIC);
  }

  @Test
  void theMechanicBlock_insideTheUserHeader_isReadToo() {
    AppHeaderContext ctx = from(headers("user-context", mechanic(MECHANIC_ID)));

    assertThat(ctx.mechanic().uuid()).isEqualTo(MECHANIC_ID);
  }

  @Test
  void theMechanicBlock_nestedUnderData_isReadToo() {
    String nested = "{\"data\":{\"mechanic\":{\"uuid\":\"" + MECHANIC_ID + "\"}}}";

    AppHeaderContext ctx = from(headers("mechanic-context", nested));

    assertThat(ctx.mechanic().uuid()).isEqualTo(MECHANIC_ID);
  }

  @Test
  void bothHeadersNamingTheSamePerson_regardlessOfCase_isOneMechanicIdentity() {
    String upper = MECHANIC_ID.toUpperCase();

    AppHeaderContext ctx =
        from(headers("user-context", user(MECHANIC_ID), "mechanic-context", mechanic(upper)));

    assertThat(ctx.identity().uuid()).isEqualTo(upper);
  }

  @Test
  void bothHeadersNamingDifferentPeople_isRefused() {
    AppHeaderContext ctx =
        from(headers("user-context", user(USER_ID), "mechanic-context", mechanic(MECHANIC_ID)));

    assertThatThrownBy(ctx::identity)
        .isInstanceOf(UserContextRejectedException.class)
        .hasMessageContaining("Conflicting");
  }

  @Test
  void aBlockThatNamesNobody_isNotAClaim_theOtherOneStands() {
    AppHeaderContext userNamed =
        from(headers("user-context", user(USER_ID), "mechanic-context", mechanic(null)));
    AppHeaderContext mechanicNamed =
        from(headers("user-context", user("  "), "mechanic-context", mechanic(MECHANIC_ID)));

    assertThat(userNamed.identity().uuid()).isEqualTo(USER_ID);
    assertThat(mechanicNamed.identity().uuid()).isEqualTo(MECHANIC_ID);
  }

  @Test
  void aMalformedOrBlankHeader_leavesTheCallerAnonymous() {
    assertThat(from(headers("user-context", "{not json")).identity()).isNull();
    assertThat(from(headers("user-context", "   ")).identity()).isNull();
  }

  @Test
  void theForwardedAddress_isTheFirstHop_trimmed() {
    assertThat(from(headers("x-forwarded-for", " 1.2.3.4 , 5.6.7.8")).ipAddress())
        .isEqualTo("1.2.3.4");
    assertThat(from(headers("x-forwarded-for", "9.9.9.9")).ipAddress()).isEqualTo("9.9.9.9");
    assertThat(from(headers("x-forwarded-for", "  ")).ipAddress()).isNull();
  }

  @Test
  void theOtherHeaders_arePassedThrough() {
    AppHeaderContext ctx =
        from(headers("service-code", "SOS", "user-agent", "okhttp", "authorization", "Bearer abc"));

    assertThat(ctx.serviceCode()).isEqualTo("SOS");
    assertThat(ctx.userAgent()).isEqualTo("okhttp");
    assertThat(ctx.authorization()).isEqualTo("Bearer abc");
  }
}
