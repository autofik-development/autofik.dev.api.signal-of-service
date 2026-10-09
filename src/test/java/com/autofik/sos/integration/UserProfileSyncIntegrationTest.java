package com.autofik.sos.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.domain.enums.FuelType;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The user-context header carries the driver's SSO profile and vehicles. Without it a new driver
 * cannot raise an SOS: the user and the vehicle rows do not exist yet.
 */
class UserProfileSyncIntegrationTest extends FlowTestSupport {

  /** SSO's own vehicle id: a number as text, not a UUID. */
  private static final String SSO_VEHICLE_ID = "648123456789135";

  private static String driverContext(UUID id, String phone, String vehicleId) {
    return "{\"user\":{\"uuid\":\""
        + id
        + "\",\"role\":\"USER\",\"otpVerify\":true,\"profileVerify\":true,"
        + "\"tokenStatus\":\"active\",\"isLogout\":false,"
        + "\"phoneNumber\":\""
        + phone
        + "\",\"profile\":{\"username\":\"bothtest15\"},"
        + "\"vehicles\":[{\"id\":\""
        + vehicleId
        + "\",\"carBrand\":\"Toyota\",\"carModel\":\"Camry\",\"carModelYear\":\"2022\","
        + "\"fuelType\":\"petrol\",\"licensePlate\":\"1A 2345\"}]}}";
  }

  private HttpResponse<String> createSos(UUID userId, String vehicleId, String context)
      throws Exception {
    String body =
        "{\"userId\":\""
            + userId
            + "\",\"serviceGroupId\":\"sg_it\",\"description\":\"flat tyre\","
            + "\"latitude\":11.5564,\"longitude\":104.9282,\"vehicleId\":\""
            + vehicleId
            + "\",\"broadcastRadiusKm\":10}";
    return send(
        req("/api/v1/sos")
            .header("user-context", context)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
  }

  private static String uniquePhone() {
    return "+855" + (100_000_000L + (System.nanoTime() % 899_999_999L));
  }

  @Test
  void aBrandNewDriver_canRaiseAnSos_withSsosOwnVehicleId() throws Exception {
    UUID id = UUID.randomUUID();
    String vehicleId = SSO_VEHICLE_ID + id.toString().substring(0, 4);
    String phone = uniquePhone();

    HttpResponse<String> response = createSos(id, vehicleId, driverContext(id, phone, vehicleId));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    User user = userRepository.findById(id).orElseThrow();
    assertThat(user.getName()).isEqualTo("bothtest15");
    assertThat(user.getPhone()).isEqualTo(phone);
    Vehicle vehicle = vehicleRepository.findById(vehicleId).orElseThrow();
    assertThat(vehicle.getUser().getUserId()).isEqualTo(id);
    assertThat(vehicle.getCarBrand()).isEqualTo("Toyota");
    assertThat(vehicle.getFuelType()).isEqualTo(FuelType.PETROL);
    assertThat(vehicle.getLicensePlate()).isEqualTo("1A 2345");
  }

  @Test
  void aPhoneAnotherDriverHeld_movesToTheCaller() throws Exception {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    String phone = uniquePhone();
    String firstVehicle = "v-" + first;
    String secondVehicle = "v-" + second;

    assertThat(
            createSos(first, firstVehicle, driverContext(first, phone, firstVehicle)).statusCode())
        .isEqualTo(201);
    // The second driver presents the same number; the unique column must not make this a 500.
    HttpResponse<String> response =
        createSos(second, secondVehicle, driverContext(second, phone, secondVehicle));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    assertThat(userRepository.findById(second).orElseThrow().getPhone()).isEqualTo(phone);
    assertThat(userRepository.findById(first).orElseThrow().getPhone())
        .isEqualTo("released:" + first);
  }

  @Test
  void aDriverWithNoProfileInTheHeader_stillGets404_whenSosNeverHeardOfThem() throws Exception {
    UUID id = UUID.randomUUID();

    HttpResponse<String> response = createSos(id, SSO_VEHICLE_ID, userContext(id));

    assertThat(response.statusCode()).isEqualTo(404);
  }
}
