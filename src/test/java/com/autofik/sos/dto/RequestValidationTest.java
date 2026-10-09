package com.autofik.sos.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.dto.request.CreateBidRequest;
import com.autofik.sos.dto.request.CreateFeedbackRequest;
import com.autofik.sos.dto.request.CreateSosRequest;
import com.autofik.sos.dto.request.UpdateBidStatusRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Boundary values of the request DTOs, run through the real Bean Validation engine. The geofence
 * (Cambodia) and the radius/price/rating limits are business rules, and an off-by-one in an
 * annotation is invisible until a real request lands on the line.
 */
class RequestValidationTest {

  private static ValidatorFactory factory;
  private static Validator validator;
  private static final UUID ID = UUID.randomUUID();

  @BeforeAll
  static void up() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void down() {
    factory.close();
  }

  private static <T> Set<String> bad(T value) {
    return validator.validate(value).stream()
        .map(ConstraintViolation::getPropertyPath)
        .map(Object::toString)
        .collect(Collectors.toSet());
  }

  private static CreateSosRequest sos(Double lat, Double lng, Integer radius, String group) {
    return new CreateSosRequest(ID, group, "d", lat, lng, ID.toString(), radius);
  }

  @Test
  void sos_valid_hasNoViolations() {
    assertThat(bad(sos(11.55, 104.92, 15, "sg_1"))).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "10.41,102.5,true",
    "14.69,107.63,true",
    "10.40,104.0,false",
    "14.70,104.0,false",
    "12.0,102.49,false",
    "12.0,107.64,false",
    "0,0,false",
    "-11.55,104.92,false"
  })
  void sos_geofenceEdges(double lat, double lng, boolean ok) {
    assertThat(bad(sos(lat, lng, 10, "sg")).isEmpty()).isEqualTo(ok);
  }

  @ParameterizedTest
  @CsvSource({"4,false", "5,true", "20,true", "21,false", "-1,false", "0,false"})
  void sos_radiusBounds(int radius, boolean ok) {
    assertThat(bad(sos(11.55, 104.92, radius, "sg")).isEmpty()).isEqualTo(ok);
  }

  @Test
  void sos_radiusIsOptional() {
    assertThat(bad(sos(11.55, 104.92, null, "sg"))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t\n"})
  void sos_blankServiceGroup_isRejected(String group) {
    assertThat(bad(sos(11.55, 104.92, 10, group))).containsExactly("serviceGroupId");
  }

  @Test
  void sos_missingRequiredFields_areAllReported() {
    assertThat(bad(new CreateSosRequest(null, null, null, null, null, null, null)))
        .containsExactlyInAnyOrder(
            "userId", "serviceGroupId", "latitude", "longitude", "vehicleId");
  }

  @ParameterizedTest
  @CsvSource({"0.01,true", "0.00,false", "-1,false", "1000000.00,true"})
  void bid_priceMustBeAtLeastOneCent(String price, boolean ok) {
    assertThat(bad(new CreateBidRequest(ID, ID, new BigDecimal(price))).isEmpty()).isEqualTo(ok);
  }

  @Test
  void bid_missingFields() {
    assertThat(bad(new CreateBidRequest(null, null, null)))
        .containsExactlyInAnyOrder("mechanicId", "sosId", "price");
  }

  @ParameterizedTest
  @CsvSource({"1.0,true", "5.0,true", "3.5,true", "0.9,false", "5.1,false", "0,false", "-5,false"})
  void feedback_ratingBounds(String rating, boolean ok) {
    assertThat(bad(new CreateFeedbackRequest(ID, ID, ID, new BigDecimal(rating), null)).isEmpty())
        .isEqualTo(ok);
  }

  @Test
  void updateBidStatus_requiresBoth() {
    assertThat(bad(new UpdateBidStatusRequest(null, null)))
        .containsExactlyInAnyOrder("userId", "status");
    assertThat(bad(new UpdateBidStatusRequest(ID, BidStatus.ACCEPTED))).isEmpty();
  }
}
