package com.autofik.sos.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The profile SSO puts in the mechanic block, in both shapes it can arrive in. */
class MechanicProfileContextTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private static final UUID ID = UUID.randomUUID();

  // Trimmed from SSO's "GET mechanic profile" response. mechanicId is a display number, not a uuid.
  private static final String MECHANIC =
      """
      {"uuid":"%s","mechanicId":"87654321","phoneNumber":"+855123456789","role":"MECHANIC",
       "mechanicType":"freelance","status":"offline","referralCode":"USC-123-4567",
       "profile":{"fullName":"John Doe","email":"john.doe@example.com","dob":"2004-06-15"},
       "skillService":{"skillTag":["engine_repair","brake_service","oil_change"],"serviceRadiusKm":50},
       "avgRate":"4.80","otpVerify":true,"profileVerify":true}
      """
          .formatted(ID);

  private MechanicContext.Mechanic parse(String json) {
    return AppHeaderContext.from(
            name -> AppHeaderContext.MECHANIC_CONTEXT_HEADER.equals(name) ? json : null,
            objectMapper)
        .mechanic();
  }

  private void assertFullProfile(MechanicContext.Mechanic m) {
    assertThat(m).isNotNull();
    assertThat(m.uuid()).isEqualTo(ID.toString());
    assertThat(m.phoneNumber()).isEqualTo("+855123456789");
    assertThat(m.mechanicType()).isEqualTo("freelance");
    assertThat(m.profile().fullName()).isEqualTo("John Doe");
    assertThat(m.profile().email()).isEqualTo("john.doe@example.com");
    assertThat(m.skillService().skillTag())
        .containsExactly("engine_repair", "brake_service", "oil_change");
    assertThat(m.skillService().serviceRadiusKm()).isEqualTo(50);
    assertThat(m.avgRate()).isEqualTo("4.80");
  }

  @Test
  void theMechanicBlockAtTheTopLevel_isRead() {
    assertFullProfile(parse("{\"mechanic\":" + MECHANIC + "}"));
  }

  @Test
  void ssosEnvelope_isRead() {
    assertFullProfile(
        parse(
            "{\"status\":\"success\",\"code\":\"PROFILE_GET_SUCCESS\",\"data\":{\"mechanic\":"
                + MECHANIC
                + "}}"));
  }

  // The gateway now sends avgRate as a JSON number; the whole header must still parse.
  @Test
  void numericAvgRate_isRead() {
    MechanicContext.Mechanic m =
        parse("{\"mechanic\":" + MECHANIC.replace("\"avgRate\":\"4.80\"", "\"avgRate\":4.8") + "}");

    assertThat(m).isNotNull();
    assertThat(m.uuid()).isEqualTo(ID.toString());
    assertThat(m.profile().fullName()).isEqualTo("John Doe");
    assertThat(new java.math.BigDecimal(m.avgRate())).isEqualByComparingTo("4.8");
  }

  @Test
  void aHeaderFromBeforeTheProfileExisted_stillParses_withNoProfile() {
    MechanicContext.Mechanic m =
        parse(
            "{\"mechanic\":{\"uuid\":\""
                + ID
                + "\",\"otpVerify\":true,\"profileVerify\":true,\"tokenStatus\":\"active\","
                + "\"isLogout\":false}}");

    assertThat(m.uuid()).isEqualTo(ID.toString());
    assertThat(m.profile()).isNull();
    assertThat(m.skillService()).isNull();
    assertThat(m.avgRate()).isNull();
  }
}
