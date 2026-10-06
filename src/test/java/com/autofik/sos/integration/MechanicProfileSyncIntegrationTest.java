package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicSkill;
import com.autofik.sos.domain.enums.MechanicRole;
import com.autofik.sos.repository.MechanicRateRepository;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The mechanic-context header carries the mechanic's SSO profile, and the first request after it
 * changes brings this service's copy in step: created when missing, updated when known, and never
 * touching what only this service knows (location, availability).
 */
class MechanicProfileSyncIntegrationTest extends FlowTestSupport {

  @Autowired private MechanicRateRepository rateRepository;

  /** SSO's own response shape: the mechanic block inside {@code data}. */
  private static String profileContext(
      UUID id, String name, String tags, int radius, String avgRate) {
    return "{\"status\":\"success\",\"data\":{\"mechanic\":{\"uuid\":\""
        + id
        + "\",\"mechanicId\":\"87654321\",\"phoneNumber\":\"+855123456789\","
        + "\"role\":\"MECHANIC\",\"mechanicType\":\"freelance\",\"status\":\"offline\","
        + "\"otpVerify\":true,\"profileVerify\":true,\"tokenStatus\":\"active\","
        + "\"isLogout\":false,"
        + "\"profile\":{\"fullName\":\""
        + name
        + "\",\"email\":\"john@example.com\"},"
        + "\"skillService\":{\"skillTag\":"
        + tags
        + ",\"serviceRadiusKm\":"
        + radius
        + "},\"avgRate\":\""
        + avgRate
        + "\"}}}";
  }

  /** Any mechanic route will do; this one only reads. */
  private HttpResponse<String> call(UUID id, String context) throws Exception {
    return send(req("/api/v1/mechanics/" + id + "/skills").header("mechanic-context", context));
  }

  private List<String[]> tagsOf(UUID id) {
    return mechanicSkillRepository.findByMechanicMechanicId(id).stream()
        .map(MechanicSkill::getSkillTags)
        .toList();
  }

  @Test
  void aMechanicSeenForTheFirstTime_isCreatedFromTheProfile() throws Exception {
    UUID id = UUID.randomUUID();

    HttpResponse<String> response =
        call(id, profileContext(id, "John Doe", "[\"engine_repair\",\"oil_change\"]", 50, "4.80"));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    Mechanic created = mechanicRepository.findById(id).orElseThrow();
    assertThat(created.getFullName()).isEqualTo("John Doe");
    assertThat(created.getPhoneNumber()).isEqualTo("+855123456789");
    assertThat(created.getMechanicRole()).isEqualTo(MechanicRole.FREELANCE);
    assertThat(created.getServiceRadiusKm()).isEqualTo(50);
    assertThat(tagsOf(id)).hasSize(1);
    assertThat(tagsOf(id).get(0)).containsExactly("ENGINE_REPAIR", "OIL_CHANGE");
    assertThat(rateRepository.findByMechanic(created).orElseThrow().getRatingAvg())
        .isEqualByComparingTo("4.80");
  }

  @Test
  void aKnownMechanic_isUpdated_andKeepsItsOwnState() throws Exception {
    Mechanic m = skilledMechanic(11.5564, 104.9282, "TOW");
    m.setAvailableStatus(false);
    m = mechanicRepository.save(m);
    String h3 = m.getCurrentH3Index();
    UUID id = m.getMechanicId();

    HttpResponse<String> response =
        call(id, profileContext(id, "New Name", "[\"battery\"]", 30, "3.50"));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    Mechanic after = mechanicRepository.findById(id).orElseThrow();
    assertThat(after.getFullName()).isEqualTo("New Name");
    assertThat(after.getServiceRadiusKm()).isEqualTo(30);
    assertThat(tagsOf(id)).hasSize(1);
    assertThat(tagsOf(id).get(0)).containsExactly("BATTERY");
    assertThat(after.getAvailableStatus()).as("availability is this service's own").isFalse();
    assertThat(after.getCurrentH3Index()).isEqualTo(h3);
    assertThat(after.getCurrentLocation()).isNotNull();
  }

  @Test
  void aHeaderWithNoProfile_changesNothing() throws Exception {
    Mechanic m = skilledMechanic(11.5564, 104.9282, "TOW");
    UUID id = m.getMechanicId();

    HttpResponse<String> response = call(id, mechanicContext(id));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(mechanicRepository.findById(id).orElseThrow().getServiceRadiusKm()).isEqualTo(10);
    assertThat(tagsOf(id).get(0)).containsExactly("TOW");
  }

  @Test
  void outOfBoundsValues_neverBlockTheRequest_andAreNotStored() throws Exception {
    Mechanic m = skilledMechanic(11.5564, 104.9282, "TOW");
    UUID id = m.getMechanicId();

    HttpResponse<String> response =
        call(id, profileContext(id, "John Doe", "[\"tow\"]", 5000, "not-a-number"));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    Mechanic after = mechanicRepository.findById(id).orElseThrow();
    assertThat(after.getServiceRadiusKm()).as("5000 km is refused").isEqualTo(10);
    assertThat(after.getFullName()).as("the valid part still syncs").isEqualTo("John Doe");
    assertThat(rateRepository.findByMechanic(after)).isEmpty();
  }

  @Test
  void aChangedTag_replacesTheOldOne_inTheRowDispatchReads() throws Exception {
    UUID id = UUID.randomUUID();
    call(id, profileContext(id, "John Doe", "[\"tow\"]", 50, "4.00"));
    call(id, profileContext(id, "John Doe", "[\"tire\"]", 50, "4.00"));

    assertThat(tagsOf(id)).hasSize(1);
    assertThat(tagsOf(id).get(0)).containsExactly("TIRE");
  }
}
