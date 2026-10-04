package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.autofik.sos.dto.external.DistanceInfo;
import com.autofik.sos.service.impl.GoogleMapsServiceImpl;
import com.google.maps.DistanceMatrixApi;
import com.google.maps.DistanceMatrixApiRequest;
import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.GeocodingApiRequest;
import com.google.maps.model.Distance;
import com.google.maps.model.DistanceMatrix;
import com.google.maps.model.DistanceMatrixElement;
import com.google.maps.model.DistanceMatrixElementStatus;
import com.google.maps.model.DistanceMatrixRow;
import com.google.maps.model.GeocodingResult;
import com.google.maps.model.LatLng;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GoogleMapsServiceImplTest {

  private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);

  @Mock GeoApiContext geoApiContext;
  @InjectMocks GoogleMapsServiceImpl googleMapsService;

  // ── reverseGeocode ────────────────────────────────────────────────────────

  @Test
  void reverseGeocode_validCoordinates_returnsFormattedAddress() throws Exception {
    GeocodingResult result = new GeocodingResult();
    result.formattedAddress = "Sukhumvit Rd, Bangkok";

    try (MockedStatic<GeocodingApi> mocked = mockStatic(GeocodingApi.class)) {
      GeocodingApiRequest mockReq = mock(GeocodingApiRequest.class);
      mocked
          .when(() -> GeocodingApi.reverseGeocode(any(GeoApiContext.class), any(LatLng.class)))
          .thenReturn(mockReq);
      when(mockReq.await()).thenReturn(new GeocodingResult[] {result});

      assertThat(googleMapsService.reverseGeocode(13.7563, 100.5018))
          .isEqualTo("Sukhumvit Rd, Bangkok");
    }
  }

  @Test
  void reverseGeocode_emptyResults_returnsNull() throws Exception {
    try (MockedStatic<GeocodingApi> mocked = mockStatic(GeocodingApi.class)) {
      GeocodingApiRequest mockReq = mock(GeocodingApiRequest.class);
      mocked
          .when(() -> GeocodingApi.reverseGeocode(any(GeoApiContext.class), any(LatLng.class)))
          .thenReturn(mockReq);
      when(mockReq.await()).thenReturn(new GeocodingResult[] {});

      assertThat(googleMapsService.reverseGeocode(13.7, 100.5)).isNull();
    }
  }

  @Test
  void reverseGeocode_apiThrowsException_returnsNullWithoutPropagating() {
    try (MockedStatic<GeocodingApi> mocked = mockStatic(GeocodingApi.class)) {
      mocked
          .when(() -> GeocodingApi.reverseGeocode(any(GeoApiContext.class), any(LatLng.class)))
          .thenThrow(new RuntimeException("network error"));

      assertThat(googleMapsService.reverseGeocode(13.7, 100.5)).isNull();
    }
  }

  // ── getDistanceInfo ───────────────────────────────────────────────────────

  @Test
  void getDistanceInfo_success_returnsPopulatedRecord() throws Exception {
    Point origin = GEO.createPoint(new Coordinate(100.5018, 13.7563));
    Point destination = GEO.createPoint(new Coordinate(100.5500, 13.8000));

    Distance dist = new Distance();
    dist.inMeters = 3200;
    dist.humanReadable = "3.2 km";

    com.google.maps.model.Duration dur = new com.google.maps.model.Duration();
    dur.inSeconds = 480;
    dur.humanReadable = "8 mins";

    DistanceMatrixElement element = new DistanceMatrixElement();
    element.status = DistanceMatrixElementStatus.OK;
    element.distance = dist;
    element.duration = dur;

    DistanceMatrixRow row = new DistanceMatrixRow();
    row.elements = new DistanceMatrixElement[] {element};
    DistanceMatrix matrix =
        new DistanceMatrix(new String[0], new String[0], new DistanceMatrixRow[] {row});

    try (MockedStatic<DistanceMatrixApi> mocked = mockStatic(DistanceMatrixApi.class)) {
      DistanceMatrixApiRequest mockReq = mock(DistanceMatrixApiRequest.class, RETURNS_SELF);
      mocked.when(() -> DistanceMatrixApi.newRequest(any(GeoApiContext.class))).thenReturn(mockReq);
      when(mockReq.await()).thenReturn(matrix);

      DistanceInfo info = googleMapsService.getDistanceInfo(origin, destination);

      assertThat(info).isNotNull();
      assertThat(info.distanceMeters()).isEqualTo(3200);
      assertThat(info.durationSeconds()).isEqualTo(480);
      assertThat(info.distanceText()).isEqualTo("3.2 km");
      assertThat(info.durationText()).isEqualTo("8 mins");
    }
  }

  @Test
  void getDistanceInfo_elementStatusNotOk_returnsNull() throws Exception {
    Point origin = GEO.createPoint(new Coordinate(100.50, 13.75));
    Point destination = GEO.createPoint(new Coordinate(100.55, 13.80));

    DistanceMatrixElement element = new DistanceMatrixElement();
    element.status = DistanceMatrixElementStatus.NOT_FOUND;

    DistanceMatrixRow row = new DistanceMatrixRow();
    row.elements = new DistanceMatrixElement[] {element};
    DistanceMatrix matrix =
        new DistanceMatrix(new String[0], new String[0], new DistanceMatrixRow[] {row});

    try (MockedStatic<DistanceMatrixApi> mocked = mockStatic(DistanceMatrixApi.class)) {
      DistanceMatrixApiRequest mockReq = mock(DistanceMatrixApiRequest.class, RETURNS_SELF);
      mocked.when(() -> DistanceMatrixApi.newRequest(any(GeoApiContext.class))).thenReturn(mockReq);
      when(mockReq.await()).thenReturn(matrix);

      assertThat(googleMapsService.getDistanceInfo(origin, destination)).isNull();
    }
  }

  @Test
  void getDistanceInfo_apiThrowsException_returnsNullWithoutPropagating() {
    Point origin = GEO.createPoint(new Coordinate(100.50, 13.75));
    Point destination = GEO.createPoint(new Coordinate(100.55, 13.80));

    try (MockedStatic<DistanceMatrixApi> mocked = mockStatic(DistanceMatrixApi.class)) {
      mocked
          .when(() -> DistanceMatrixApi.newRequest(any(GeoApiContext.class)))
          .thenThrow(new RuntimeException("API unavailable"));

      assertThat(googleMapsService.getDistanceInfo(origin, destination)).isNull();
    }
  }

  @Test
  void blankApiKey_skipsEveryGoogleCall_andReturnsNull() {
    org.springframework.test.util.ReflectionTestUtils.setField(googleMapsService, "apiKey", "");
    org.locationtech.jts.geom.Point p =
        new org.locationtech.jts.geom.GeometryFactory()
            .createPoint(new org.locationtech.jts.geom.Coordinate(104.9, 11.5));

    assertThat(googleMapsService.reverseGeocode(11.5, 104.9)).isNull();
    assertThat(googleMapsService.getDistanceInfo(p, p)).isNull();
    assertThat(googleMapsService.getDistanceInfos(java.util.List.of(p), p)).isNull();
    verifyNoInteractions(geoApiContext);
  }
}
