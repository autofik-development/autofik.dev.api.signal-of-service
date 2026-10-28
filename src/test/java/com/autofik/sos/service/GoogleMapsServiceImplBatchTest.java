package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.autofik.sos.dto.external.DistanceInfo;
import com.autofik.sos.service.impl.GoogleMapsServiceImpl;
import com.google.maps.DistanceMatrixApi;
import com.google.maps.DistanceMatrixApiRequest;
import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.GeocodingApiRequest;
import com.google.maps.model.AddressType;
import com.google.maps.model.Distance;
import com.google.maps.model.DistanceMatrix;
import com.google.maps.model.DistanceMatrixElement;
import com.google.maps.model.DistanceMatrixElementStatus;
import com.google.maps.model.DistanceMatrixRow;
import com.google.maps.model.GeocodingResult;
import com.google.maps.model.LatLng;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

/** The batch distance call and the address-picking rules of {@link GoogleMapsServiceImpl}. */
class GoogleMapsServiceImplBatchTest {

  private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);

  private final GeoApiContext context = mock(GeoApiContext.class);
  private final GoogleMapsServiceImpl service = new GoogleMapsServiceImpl(context);

  private static Point point(double lng, double lat) {
    return GEO.createPoint(new Coordinate(lng, lat));
  }

  private static DistanceMatrixElement element(
      DistanceMatrixElementStatus status, long meters, long seconds) {
    DistanceMatrixElement e = new DistanceMatrixElement();
    e.status = status;
    if (status == DistanceMatrixElementStatus.OK) {
      Distance d = new Distance();
      d.inMeters = meters;
      d.humanReadable = meters + " m";
      com.google.maps.model.Duration t = new com.google.maps.model.Duration();
      t.inSeconds = seconds;
      t.humanReadable = seconds + " s";
      e.distance = d;
      e.duration = t;
    }
    return e;
  }

  private static DistanceMatrix matrixOf(DistanceMatrixElement... elements) {
    DistanceMatrixRow[] rows = new DistanceMatrixRow[elements.length];
    for (int i = 0; i < elements.length; i++) {
      rows[i] = new DistanceMatrixRow();
      rows[i].elements = new DistanceMatrixElement[] {elements[i]};
    }
    return new DistanceMatrix(new String[0], new String[0], rows);
  }

  private static GeocodingResult address(String text, AddressType... types) {
    GeocodingResult r = new GeocodingResult();
    r.formattedAddress = text;
    r.types = types;
    return r;
  }

  @Test
  void noOrigins_isAnEmptyList_withoutCallingGoogle() {
    assertThat(service.getDistanceInfos(List.of(), point(104.9, 11.5))).isEmpty();

    verifyNoInteractions(context);
  }

  @Test
  void aBatch_pricesEveryOrigin_andMarksUnroutableOnesNull() throws Exception {
    List<Point> origins = List.of(point(104.90, 11.50), point(104.91, 11.51), point(104.92, 11.52));
    DistanceMatrix matrix =
        matrixOf(
            element(DistanceMatrixElementStatus.OK, 3200, 480),
            element(DistanceMatrixElementStatus.NOT_FOUND, 0, 0),
            element(DistanceMatrixElementStatus.OK, 900, 120));

    try (MockedStatic<DistanceMatrixApi> mocked = mockStatic(DistanceMatrixApi.class)) {
      DistanceMatrixApiRequest request = mock(DistanceMatrixApiRequest.class, RETURNS_SELF);
      mocked.when(() -> DistanceMatrixApi.newRequest(any(GeoApiContext.class))).thenReturn(request);
      when(request.await()).thenReturn(matrix);

      List<DistanceInfo> result = service.getDistanceInfos(origins, point(104.95, 11.55));

      assertThat(result).hasSize(3);
      assertThat(result.get(0).distanceMeters()).isEqualTo(3200);
      assertThat(result.get(1)).isNull();
      assertThat(result.get(2).durationSeconds()).isEqualTo(120);
    }
  }

  @Test
  void aSingleOriginBatch_isNotTaggedWithAnOriginCount() throws Exception {
    DistanceMatrix matrix = matrixOf(element(DistanceMatrixElementStatus.OK, 100, 10));

    try (MockedStatic<DistanceMatrixApi> mocked = mockStatic(DistanceMatrixApi.class)) {
      DistanceMatrixApiRequest request = mock(DistanceMatrixApiRequest.class, RETURNS_SELF);
      mocked.when(() -> DistanceMatrixApi.newRequest(any(GeoApiContext.class))).thenReturn(request);
      when(request.await()).thenReturn(matrix);

      assertThat(service.getDistanceInfos(List.of(point(104.9, 11.5)), point(104.95, 11.55)))
          .hasSize(1);
    }
  }

  @Test
  void aFailedBatch_isNull_notAnException() {
    try (MockedStatic<DistanceMatrixApi> mocked = mockStatic(DistanceMatrixApi.class)) {
      mocked
          .when(() -> DistanceMatrixApi.newRequest(any(GeoApiContext.class)))
          .thenThrow(new RuntimeException("quota"));

      assertThat(service.getDistanceInfos(List.of(point(104.9, 11.5)), point(104.95, 11.55)))
          .isNull();
    }
  }

  @Test
  void aKeyThatIsSetButNotBlank_leavesGoogleCallsOn() throws Exception {
    ReflectionTestUtils.setField(service, "apiKey", "AIza-not-blank");
    ReflectionTestUtils.setField(service, "observationRegistry", ObservationRegistry.NOOP);
    GeocodingResult[] results = {address("Street 51, Phnom Penh", AddressType.ROUTE)};

    try (MockedStatic<GeocodingApi> mocked = mockStatic(GeocodingApi.class)) {
      GeocodingApiRequest request = mock(GeocodingApiRequest.class);
      mocked
          .when(() -> GeocodingApi.reverseGeocode(any(GeoApiContext.class), any(LatLng.class)))
          .thenReturn(request);
      when(request.await()).thenReturn(results);

      assertThat(service.reverseGeocode(11.5, 104.9)).isEqualTo("Street 51, Phnom Penh");
    }
  }

  @Test
  void aPlusCodeIsSkipped_inFavourOfARealAddress() throws Exception {
    GeocodingResult[] results = {
      address("HV9R+86Q, Phnom Penh", AddressType.PLUS_CODE),
      address("Street 51, Phnom Penh", AddressType.ROUTE)
    };

    try (MockedStatic<GeocodingApi> mocked = mockStatic(GeocodingApi.class)) {
      GeocodingApiRequest request = mock(GeocodingApiRequest.class);
      mocked
          .when(() -> GeocodingApi.reverseGeocode(any(GeoApiContext.class), any(LatLng.class)))
          .thenReturn(request);
      when(request.await()).thenReturn(results);

      assertThat(service.reverseGeocode(11.5, 104.9)).isEqualTo("Street 51, Phnom Penh");
    }
  }

  @Test
  void whenEveryResultIsAPlusCode_theFirstIsUsedAsIs() throws Exception {
    GeocodingResult[] results = {
      address("HV9R+86Q, Phnom Penh", AddressType.PLUS_CODE),
      address("HV9R+87Q, Phnom Penh", AddressType.PLUS_CODE)
    };

    try (MockedStatic<GeocodingApi> mocked = mockStatic(GeocodingApi.class)) {
      GeocodingApiRequest request = mock(GeocodingApiRequest.class);
      mocked
          .when(() -> GeocodingApi.reverseGeocode(any(GeoApiContext.class), any(LatLng.class)))
          .thenReturn(request);
      when(request.await()).thenReturn(results);

      assertThat(service.reverseGeocode(11.5, 104.9)).isEqualTo("HV9R+86Q, Phnom Penh");
    }
  }

  @Test
  void aResultWithNoTypes_orSeveralTypes_isNotAPlusCodeOnly() throws Exception {
    GeocodingResult noTypes = address("No types, Phnom Penh");
    noTypes.types = null;
    GeocodingResult[] results = {noTypes};
    GeocodingResult[] several = {
      address("Mixed, Phnom Penh", AddressType.PLUS_CODE, AddressType.ROUTE)
    };

    try (MockedStatic<GeocodingApi> mocked = mockStatic(GeocodingApi.class)) {
      GeocodingApiRequest request = mock(GeocodingApiRequest.class);
      mocked
          .when(() -> GeocodingApi.reverseGeocode(any(GeoApiContext.class), any(LatLng.class)))
          .thenReturn(request);
      when(request.await()).thenReturn(results).thenReturn(several);

      assertThat(service.reverseGeocode(11.5, 104.9)).isEqualTo("No types, Phnom Penh");
      assertThat(service.reverseGeocode(11.5, 104.9)).isEqualTo("Mixed, Phnom Penh");
    }
  }
}
