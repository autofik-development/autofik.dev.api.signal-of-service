package com.autofik.sos.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The driver profile the gateway puts in the user block, vehicles included. */
class UserProfileContextTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private static final UUID ID = UUID.randomUUID();

  private UserContext.User parse(String json) {
    return AppHeaderContext.from(
            name -> AppHeaderContext.USER_CONTEXT_HEADER.equals(name) ? json : null, objectMapper)
        .user();
  }

  @Test
  void theDriverProfileAndItsVehicles_areRead() {
    UserContext.User u =
        parse(
            "{\"user\":{\"uuid\":\""
                + ID
                + "\",\"role\":\"USER\",\"phoneNumber\":\"+855123456789\","
                + "\"profile\":{\"username\":\"bothtest15\",\"gender\":\"MALE\"},"
                + "\"vehicles\":[{\"id\":\"648123456789135\",\"carBrand\":\"Toyota\","
                + "\"carModel\":\"Camry\",\"carModelYear\":\"2022\",\"fuelType\":\"petrol\","
                + "\"licensePlate\":\"1A 2345\",\"image\":\"ignored\",\"isActive\":true}]}}");

    assertThat(u.uuid()).isEqualTo(ID.toString());
    assertThat(u.phoneNumber()).isEqualTo("+855123456789");
    assertThat(u.profile().username()).isEqualTo("bothtest15");
    assertThat(u.vehicles()).hasSize(1);
    assertThat(u.vehicles().get(0).id()).isEqualTo("648123456789135");
    assertThat(u.vehicles().get(0).fuelType()).isEqualTo("petrol");
    assertThat(u.vehicles().get(0).licensePlate()).isEqualTo("1A 2345");
  }

  @Test
  void aHeaderWithoutAProfile_stillParses() {
    UserContext.User u = parse("{\"user\":{\"uuid\":\"" + ID + "\",\"role\":\"USER\"}}");

    assertThat(u.uuid()).isEqualTo(ID.toString());
    assertThat(u.profile()).isNull();
    assertThat(u.vehicles()).isNull();
  }
}
