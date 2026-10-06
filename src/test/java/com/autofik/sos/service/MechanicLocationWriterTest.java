package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.service.impl.MechanicLocationWriter;
import jakarta.persistence.EntityNotFoundException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class MechanicLocationWriterTest {

  private static final double LAT = 11.5573;
  private static final double LNG = 104.9282;

  @Mock MechanicRepository mechanicRepository;
  @Mock BidSosRepository bidSosRepository;
  @Mock StringRedisTemplate redisTemplate;

  @InjectMocks MechanicLocationWriter writer;

  private ValueOperations<String, String> valueOps;

  private final UUID mechanicId = UUID.randomUUID();
  private final UUID sosId = UUID.randomUUID();
  private Mechanic mechanic;

  @BeforeEach
  void setUp() {
    valueOps = mock(ValueOperations.class);
    lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    org.springframework.test.util.ReflectionTestUtils.setField(writer, "pgSyncSeconds", 10);
    org.springframework.test.util.ReflectionTestUtils.setField(writer, "h3Resolution", 7);
    org.springframework.test.util.ReflectionTestUtils.setField(writer, "movedThresholdMeters", 20);
    org.springframework.test.util.ReflectionTestUtils.setField(writer, "earthRadiusM", 6_371_000d);
    // Default: throttle allows Postgres write on every ping
    lenient()
        .when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
        .thenReturn(true);

    mechanic =
        Mechanic.builder()
            .mechanicId(mechanicId)
            .fullName("Bob")
            .phoneNumber("081")
            .email("bob@fix.com")
            .availableStatus(true)
            .build();
  }

  @Test
  void throttled_skipsPostgresWrite() {
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
    when(mechanicRepository.existsById(mechanicId)).thenReturn(true);

    writer.persistAndResolveSos(mechanicId, LAT, LNG, null);

    verify(mechanicRepository, never()).findById(any());
    verify(mechanicRepository, never()).save(any());
  }

  // The active-job lookup shares the Postgres write throttle, so a throttled ping must not
  // spend a query discovering that an idle mechanic has no job.
  @Test
  void throttled_skipsActiveBidLookup() {
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
    when(mechanicRepository.existsById(mechanicId)).thenReturn(true);

    assertThat(writer.persistAndResolveSos(mechanicId, LAT, LNG, null)).isNull();

    verify(bidSosRepository, never()).findActiveJob(any());
  }

  @Test
  void notThrottled_updatesLocationAndH3Index() {
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
    when(bidSosRepository.findActiveJob(mechanicId)).thenReturn(Optional.empty());

    writer.persistAndResolveSos(mechanicId, LAT, LNG, null);

    assertThat(mechanic.getCurrentLocation()).isNotNull();
    assertThat(mechanic.getCurrentLocation().getY()).isEqualTo(LAT);
    assertThat(mechanic.getCurrentLocation().getX()).isEqualTo(LNG);
    assertThat(mechanic.getCurrentH3Index()).isNotBlank();
    verify(mechanicRepository).save(mechanic);
  }

  // Dispatch skips a mechanic whose last ping is too old, so a persisted ping must stamp the time
  // and a throttled one (which writes nothing) must not.
  @Test
  void notThrottled_stampsWhenTheLocationWasLastWritten() {
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
    when(bidSosRepository.findActiveJob(mechanicId)).thenReturn(Optional.empty());
    java.time.LocalDateTime before = java.time.LocalDateTime.now().minusSeconds(1);

    writer.persistAndResolveSos(mechanicId, LAT, LNG, null);

    assertThat(mechanic.getLocationUpdatedAt())
        .isBetween(before, java.time.LocalDateTime.now().plusSeconds(1));
  }

  @Test
  void throttled_doesNotTouchTheLocationTimestamp() {
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
    when(mechanicRepository.existsById(mechanicId)).thenReturn(true);

    writer.persistAndResolveSos(mechanicId, LAT, LNG, null);

    assertThat(mechanic.getLocationUpdatedAt()).isNull();
  }

  // The no-show sweep needs to tell a parked mechanic from a moving one, and locationUpdatedAt
  // cannot: it is stamped on every ping. locationMovedAt moves only on a real change of position.
  private void pingFrom(double lat, double lng) {
    mechanic.setCurrentLocation(
        new org.locationtech.jts.geom.GeometryFactory(
                new org.locationtech.jts.geom.PrecisionModel(), 4326)
            .createPoint(new org.locationtech.jts.geom.Coordinate(lng, lat)));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
    when(bidSosRepository.findActiveJob(mechanicId)).thenReturn(Optional.empty());
  }

  @Test
  void ping_farEnoughFromThePreviousOne_recordsThatTheMechanicMoved() {
    pingFrom(LAT, LNG);
    // About 28 m north.
    writer.persistAndResolveSos(mechanicId, LAT + 0.00025, LNG, null);

    assertThat(mechanic.getLocationMovedAt()).isNotNull();
  }

  @Test
  void ping_nearThePreviousOne_doesNotRecordMovement() {
    pingFrom(LAT, LNG);
    // About 5 m north: GPS wobble, not movement.
    writer.persistAndResolveSos(mechanicId, LAT + 0.000045, LNG, null);

    assertThat(mechanic.getLocationMovedAt()).isNull();
  }

  @Test
  void firstEverLocation_countsAsMoved() {
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
    when(bidSosRepository.findActiveJob(mechanicId)).thenReturn(Optional.empty());

    writer.persistAndResolveSos(mechanicId, LAT, LNG, null);

    assertThat(mechanic.getLocationMovedAt()).isNotNull();
  }

  @Test
  void redisUnavailable_writesToPostgresOnEveryPing() {
    when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
    when(bidSosRepository.findActiveJob(mechanicId)).thenReturn(Optional.empty());

    writer.persistAndResolveSos(mechanicId, LAT, LNG, null);

    verify(mechanicRepository).save(mechanic);
  }

  @Test
  void mechanicNotFound_persistPath_throws() {
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> writer.persistAndResolveSos(mechanicId, LAT, LNG, null))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void mechanicNotFound_throttledPath_throws() {
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
    when(mechanicRepository.existsById(mechanicId)).thenReturn(false);

    assertThatThrownBy(() -> writer.persistAndResolveSos(mechanicId, LAT, LNG, null))
        .isInstanceOf(EntityNotFoundException.class);
  }

  // A caller-supplied sosId is only trusted when the mechanic holds the accepted bid on that live
  // SOS. Otherwise any mechanic could publish positions, and trigger billed route lookups, on
  // someone else's ticket.
  @Test
  void sosIdSupplied_andAssigned_isTrusted_withoutTheActiveJobLookup() {
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
    when(bidSosRepository.isAssignedToActiveJob(mechanicId, sosId)).thenReturn(true);

    assertThat(writer.persistAndResolveSos(mechanicId, LAT, LNG, sosId)).isEqualTo(sosId);

    verify(bidSosRepository, never()).findActiveJob(any());
  }

  @Test
  void sosIdSupplied_butNotAssigned_isIgnored_andNothingIsBroadcast() {
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
    when(bidSosRepository.isAssignedToActiveJob(mechanicId, sosId)).thenReturn(false);
    when(bidSosRepository.findActiveJob(mechanicId)).thenReturn(Optional.empty());

    assertThat(writer.persistAndResolveSos(mechanicId, LAT, LNG, sosId)).isNull();
  }

  // A stale or foreign sosId must not stop a mechanic who really is on a job from being tracked.
  @Test
  void sosIdSupplied_butNotAssigned_fallsBackToTheMechanicsOwnActiveJob() {
    UUID realSos = UUID.randomUUID();
    SosRequest sosRequest = mock(SosRequest.class);
    when(sosRequest.getSosId()).thenReturn(realSos);
    BidSos activeBid = mock(BidSos.class);
    when(activeBid.getSosRequest()).thenReturn(sosRequest);
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
    when(bidSosRepository.isAssignedToActiveJob(mechanicId, sosId)).thenReturn(false);
    when(bidSosRepository.findActiveJob(mechanicId)).thenReturn(Optional.of(activeBid));

    assertThat(writer.persistAndResolveSos(mechanicId, LAT, LNG, sosId)).isEqualTo(realSos);
  }

  @Test
  void noSosId_activeBidFound_resolvesToThatSos() {
    SosRequest sosRequest = mock(SosRequest.class);
    when(sosRequest.getSosId()).thenReturn(sosId);
    BidSos activeBid = mock(BidSos.class);
    when(activeBid.getSosRequest()).thenReturn(sosRequest);

    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
    when(bidSosRepository.findActiveJob(mechanicId)).thenReturn(Optional.of(activeBid));

    assertThat(writer.persistAndResolveSos(mechanicId, LAT, LNG, null)).isEqualTo(sosId);
  }

  @Test
  void noSosId_noActiveBid_resolvesToNull() {
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
    when(bidSosRepository.findActiveJob(mechanicId)).thenReturn(Optional.empty());

    assertThat(writer.persistAndResolveSos(mechanicId, LAT, LNG, null)).isNull();
  }
}
