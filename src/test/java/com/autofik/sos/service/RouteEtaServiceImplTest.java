package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.external.DistanceInfo;
import com.autofik.sos.dto.external.EtaInfo;
import com.autofik.sos.dto.external.EtaSnapshot;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.service.impl.RouteEtaServiceImpl;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class RouteEtaServiceImplTest {

  private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);

  // Destination (the stranded driver) and a start point ~5.4 km due west of it.
  private static final double DEST_LAT = 11.5729738;
  private static final double DEST_LNG = 104.8831247;
  private static final double START_LAT = 11.5729738;
  private static final double START_LNG = 104.8331247;

  private static final long SNAPSHOT_DISTANCE_M = 6000;
  private static final long SNAPSHOT_DURATION_S = 900;

  @Mock SosRequestRepository sosRequestRepository;
  @Mock GoogleMapsService googleMapsService;
  @Mock StringRedisTemplate redisTemplate;
  @Mock ObjectMapper objectMapper;

  @InjectMocks RouteEtaServiceImpl routeEtaService;

  private ValueOperations<String, String> valueOps;
  private final UUID sosId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    valueOps = mock(ValueOperations.class);
    lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
  }

  private SosRequest sosAt(SosStatus status) {
    Point location = GEO.createPoint(new Coordinate(DEST_LNG, DEST_LAT));
    return SosRequest.builder().sosId(sosId).status(status).location(location).build();
  }

  /** Puts the service on the interpolation path with a known snapshot already cached. */
  private void givenCachedSnapshot() {
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
    when(valueOps.get(anyString())).thenReturn("{}");
    when(objectMapper.readValue(anyString(), eq(EtaSnapshot.class)))
        .thenReturn(
            new EtaSnapshot(
                SNAPSHOT_DISTANCE_M,
                SNAPSHOT_DURATION_S,
                START_LAT,
                START_LNG,
                DEST_LAT,
                DEST_LNG,
                false));
  }

  // ── refresh path ──────────────────────────────────────────────────────────

  @Test
  void currentEta_whenGateAcquired_callsMapsAndReturnsLiveRoute() {
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosAt(SosStatus.ON_THE_WAY)));
    when(googleMapsService.getDistanceInfo(any(), any()))
        .thenReturn(new DistanceInfo(5200, 3900, "5.2 km", "1 hour 5 mins"));

    EtaInfo eta = routeEtaService.currentEta(sosId, START_LAT, START_LNG);

    assertThat(eta).isNotNull();
    assertThat(eta.remainingDistanceM()).isEqualTo(5200);
    assertThat(eta.remainingDurationS()).isEqualTo(3900);
    assertThat(eta.estimated()).isFalse();
    // Text is rebuilt server-side rather than reusing Google's wording.
    assertThat(eta.remainingDurationText()).isEqualTo("1 hr 5 min");
    assertThat(eta.remainingDistanceText()).isEqualTo("5.2 km");
  }

  @Test
  void currentEta_whenMapsUnavailable_fallsBackToStraightLineMarkedEstimated() {
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosAt(SosStatus.ON_THE_WAY)));
    when(googleMapsService.getDistanceInfo(any(), any())).thenReturn(null);

    EtaInfo eta = routeEtaService.currentEta(sosId, START_LAT, START_LNG);

    assertThat(eta).isNotNull();
    assertThat(eta.estimated()).isTrue();
    // ~5.4 km great-circle inflated by the 1.35 winding factor.
    assertThat(eta.remainingDistanceM()).isBetween(6_500L, 8_000L);
    assertThat(eta.remainingDurationS()).isPositive();
  }

  @Test
  void currentEta_whenNotEnRoute_returnsNull() {
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosAt(SosStatus.ARRIVED)));

    assertThat(routeEtaService.currentEta(sosId, START_LAT, START_LNG)).isNull();
    verify(googleMapsService, never()).getDistanceInfo(any(), any());
  }

  @Test
  void currentEta_whenSosMissing_returnsNull() {
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.empty());

    assertThat(routeEtaService.currentEta(sosId, START_LAT, START_LNG)).isNull();
  }

  @Test
  void currentEta_nullSosId_returnsNullWithoutTouchingRedis() {
    assertThat(routeEtaService.currentEta(null, START_LAT, START_LNG)).isNull();
    verifyNoInteractions(redisTemplate, sosRequestRepository, googleMapsService);
  }

  // ── interpolation path ────────────────────────────────────────────────────

  @Test
  void currentEta_withinRefreshWindow_doesNotCallMaps() {
    givenCachedSnapshot();

    routeEtaService.currentEta(sosId, START_LAT, START_LNG);

    verify(googleMapsService, never()).getDistanceInfo(any(), any());
    verify(sosRequestRepository, never()).findById(any());
  }

  @Test
  void currentEta_movingTowardDestination_reducesRemaining() {
    givenCachedSnapshot();

    // Roughly halfway along the westward approach.
    EtaInfo eta = routeEtaService.currentEta(sosId, DEST_LAT, 104.8581247);

    assertThat(eta).isNotNull();
    assertThat(eta.remainingDistanceM()).isLessThan(SNAPSHOT_DISTANCE_M);
    assertThat(eta.remainingDurationS()).isLessThan(SNAPSHOT_DURATION_S);
    // ~2.7 km of the 6 km route consumed.
    assertThat(eta.remainingDistanceM()).isBetween(3_000L, 3_600L);
  }

  /**
   * The direction-sensitivity guarantee: progress is the drop in distance-to-destination, not
   * distance travelled. A detour must make the ETA worse, never better.
   */
  @Test
  void currentEta_movingAwayFromDestination_increasesRemaining() {
    givenCachedSnapshot();

    // Further west — away from the destination.
    EtaInfo eta = routeEtaService.currentEta(sosId, DEST_LAT, 104.8031247);

    assertThat(eta).isNotNull();
    assertThat(eta.remainingDistanceM()).isGreaterThan(SNAPSHOT_DISTANCE_M);
    assertThat(eta.remainingDurationS()).isGreaterThan(SNAPSHOT_DURATION_S);
  }

  /**
   * Duration is scaled by distance covered rather than wall-clock elapsed time, so a mechanic held
   * at a red light does not have their ETA tick toward zero while stationary.
   */
  @Test
  void currentEta_stationary_holdsRemainingSteady() {
    givenCachedSnapshot();

    EtaInfo eta = routeEtaService.currentEta(sosId, START_LAT, START_LNG);

    assertThat(eta).isNotNull();
    assertThat(eta.remainingDistanceM()).isEqualTo(SNAPSHOT_DISTANCE_M);
    assertThat(eta.remainingDurationS()).isEqualTo(SNAPSHOT_DURATION_S);
    assertThat(eta.remainingDurationText()).isEqualTo("15 min");
  }

  @Test
  void currentEta_arrivedAtDestination_clampsToZero() {
    givenCachedSnapshot();

    // Standing on the destination: progress exceeds the cached route length.
    EtaInfo eta = routeEtaService.currentEta(sosId, DEST_LAT, DEST_LNG);

    assertThat(eta).isNotNull();
    assertThat(eta.remainingDistanceM()).isZero();
    assertThat(eta.remainingDurationS()).isZero();
    assertThat(eta.remainingDurationText()).isEqualTo("< 1 min");
  }

  // ── resilience ────────────────────────────────────────────────────────────

  @Test
  void currentEta_whenRedisDown_stillResolvesViaMaps() {
    when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosAt(SosStatus.ACCEPTED)));
    when(googleMapsService.getDistanceInfo(any(), any()))
        .thenReturn(new DistanceInfo(4200, 600, "4.2 km", "10 mins"));

    EtaInfo eta = routeEtaService.currentEta(sosId, START_LAT, START_LNG);

    assertThat(eta).isNotNull();
    assertThat(eta.remainingDistanceM()).isEqualTo(4200);
    assertThat(eta.remainingDurationText()).isEqualTo("10 min");
  }

  /**
   * With Redis unavailable the in-process gate takes over, so the second ping in the same window
   * must not spend another Distance Matrix call.
   */
  @Test
  void currentEta_whenRedisDown_localGateStillThrottlesMapsCalls() {
    when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosAt(SosStatus.ACCEPTED)));
    when(googleMapsService.getDistanceInfo(any(), any()))
        .thenReturn(new DistanceInfo(4200, 600, "4.2 km", "10 mins"));

    routeEtaService.currentEta(sosId, START_LAT, START_LNG);
    routeEtaService.currentEta(sosId, START_LAT, START_LNG);

    verify(googleMapsService, times(1)).getDistanceInfo(any(), any());
  }

  @Test
  void clear_removesBothRedisKeys() {
    routeEtaService.clear(sosId);

    verify(redisTemplate).delete("sos:eta:" + sosId);
    verify(redisTemplate).delete("sos:eta:lock:" + sosId);
  }

  @Test
  void clear_whenRedisDown_doesNotThrow() {
    doThrow(new RuntimeException("Redis down")).when(redisTemplate).delete(anyString());

    routeEtaService.clear(sosId);
  }
}
