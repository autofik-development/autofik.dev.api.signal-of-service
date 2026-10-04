package com.autofik.sos.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.dto.request.CreateBidRequest;
import com.autofik.sos.dto.request.CreateFeedbackRequest;
import com.autofik.sos.dto.request.CreateSosRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The numeric limits on request bodies, checked by Bean Validation exactly as the controllers run
 * it. Each property states the rule independently of the annotations and asserts the validator
 * agrees on every generated value, with the boundaries and their nearest neighbours always mixed
 * in.
 */
class RequestBoundsFuzzTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  // The Cambodia box from the README; the annotations on CreateSosRequest must match it.
  private static final double LAT_MIN = 10.41, LAT_MAX = 14.69;
  private static final double LNG_MIN = 102.5, LNG_MAX = 107.63;

  @Property(seed = Fuzz.SEED)
  void sosCoordinates_areValidExactlyInsideTheCambodiaBox(
      @ForAll("latitudes") Double lat, @ForAll("longitudes") Double lng) {
    CreateSosRequest r =
        new CreateSosRequest(UUID.randomUUID(), "sg", null, lat, lng, UUID.randomUUID(), null);

    boolean inside =
        lat != null
            && lng != null
            && Double.isFinite(lat)
            && Double.isFinite(lng)
            && lat >= LAT_MIN
            && lat <= LAT_MAX
            && lng >= LNG_MIN
            && lng <= LNG_MAX;

    assertThat(
            invalidFields(r).stream().anyMatch(f -> f.equals("latitude") || f.equals("longitude")))
        .as("lat=%s lng=%s", lat, lng)
        .isEqualTo(!inside);
  }

  @Property(seed = Fuzz.SEED)
  void broadcastRadius_isOptional_andOtherwiseValidFrom5To20Km(@ForAll("radii") Integer radiusKm) {
    CreateSosRequest r =
        new CreateSosRequest(
            UUID.randomUUID(), "sg", null, 11.5564, 104.9282, UUID.randomUUID(), radiusKm);

    boolean valid = radiusKm == null || (radiusKm >= 5 && radiusKm <= 20);

    assertThat(invalidFields(r).contains("broadcastRadiusKm"))
        .as("broadcastRadiusKm=%s", radiusKm)
        .isEqualTo(!valid);
  }

  @Property(seed = Fuzz.SEED)
  void feedbackRating_isValidExactlyFrom1To5(@ForAll("ratings") BigDecimal rating) {
    CreateFeedbackRequest r =
        new CreateFeedbackRequest(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), rating, "ok");

    boolean valid =
        rating != null
            && rating.compareTo(BigDecimal.ONE) >= 0
            && rating.compareTo(BigDecimal.valueOf(5)) <= 0;

    assertThat(invalidFields(r).contains("rating")).as("rating=%s", rating).isEqualTo(!valid);
  }

  @Property(seed = Fuzz.SEED)
  void bidPrice_isValidFromOneCent(@ForAll("prices") BigDecimal price) {
    CreateBidRequest r = new CreateBidRequest(UUID.randomUUID(), UUID.randomUUID(), price);

    boolean valid = price != null && price.compareTo(new BigDecimal("0.01")) >= 0;

    assertThat(invalidFields(r).contains("price")).as("price=%s", price).isEqualTo(!valid);
  }

  // ── generators ─────────────────────────────────────────────────────────────

  @Provide
  Arbitrary<Double> latitudes() {
    return around(LAT_MIN, LAT_MAX, 8.0, 17.0);
  }

  @Provide
  Arbitrary<Double> longitudes() {
    return around(LNG_MIN, LNG_MAX, 100.0, 110.0);
  }

  /** Uniform values across a band wider than [min, max], plus the edges and odd doubles. */
  private static Arbitrary<Double> around(double min, double max, double from, double to) {
    return Arbitraries.frequencyOf(
        net.jqwik.api.Tuple.of(6, Arbitraries.doubles().between(from, to).ofScale(6)),
        net.jqwik.api.Tuple.of(
            4,
            Arbitraries.of(
                min,
                max,
                Math.nextDown(min),
                Math.nextUp(min),
                Math.nextDown(max),
                Math.nextUp(max),
                0.0,
                -0.0,
                -min,
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.MAX_VALUE,
                -Double.MAX_VALUE,
                Double.MIN_VALUE)),
        net.jqwik.api.Tuple.of(1, Arbitraries.just(null)));
  }

  @Provide
  Arbitrary<Integer> radii() {
    return Arbitraries.oneOf(
        Arbitraries.integers().between(-10, 50),
        Arbitraries.of(4, 5, 6, 19, 20, 21, 30, 0, Integer.MIN_VALUE, Integer.MAX_VALUE),
        Arbitraries.just(null));
  }

  @Provide
  Arbitrary<BigDecimal> ratings() {
    return Arbitraries.oneOf(
        decimals(-200, 800),
        Arbitraries.of(
                "1",
                "5",
                "1.0",
                "5.00",
                "0.99",
                "0.999999",
                "5.01",
                "5.000001",
                "1.000001",
                "0",
                "-1",
                "1E+1",
                "5E-0",
                "0.1E+1")
            .map(BigDecimal::new),
        Arbitraries.just(null));
  }

  @Provide
  Arbitrary<BigDecimal> prices() {
    return Arbitraries.oneOf(
        decimals(-100, 100_000),
        Arbitraries.of("0.01", "0.009", "0.0100", "0", "-0.01", "0.010001", "1E-2", "1E+12")
            .map(BigDecimal::new),
        Arbitraries.just(null));
  }

  /** Values in [from/100, to/100] at scales 0 to 6, so equal values come in several spellings. */
  private static Arbitrary<BigDecimal> decimals(int from, int to) {
    return net.jqwik.api.Combinators.combine(
            Arbitraries.integers().between(from, to), Arbitraries.integers().between(0, 4))
        .as((cents, extra) -> BigDecimal.valueOf(cents, 2).setScale(2 + extra));
  }

  private static Set<String> invalidFields(Object request) {
    Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(request);
    return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
  }
}
