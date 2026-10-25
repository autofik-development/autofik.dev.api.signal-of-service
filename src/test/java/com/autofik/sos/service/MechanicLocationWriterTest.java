package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.service.impl.MechanicLocationWriter;
import jakarta.persistence.EntityNotFoundException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * The movement rule (location_moved_at) now lives in the UPDATE statement itself, so it is tested
 * against real PostGIS in MechanicRepositoryTest; here is what the writer decides around it.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class MechanicLocationWriterTest {

  private static final double LAT = 11.5573;
  private static final double LNG = 104.9282;

  @Mock MechanicRepository mechanicRepository;
  @Mock BidSosRepository bidSosRepository;
  @Mock StringRedisTemplate redisTemplate;

  @Spy
  com.autofik.sos.service.impl.RedisHealthGate redisGate =
      new com.autofik.sos.service.impl.RedisHealthGate();

  @InjectMocks MechanicLocationWriter writer;

  private ValueOperations<String, String> valueOps;

  private final UUID mechanicId = UUID.randomUUID();
  private final UUID sosId = UUID.randomUUID();
  private final String throttleKey = "mechanic:pg-sync:" + mechanicId;

  @BeforeEach
  void setUp() {
    valueOps = mock(ValueOperations.class);
    lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    org.springframework.test.util.ReflectionTestUtils.setField(writer, "pgSyncSeconds", 10);
    org.springframework.test.util.ReflectionTestUtils.setField(writer, "h3Resolution", 7);
    org.springframework.test.util.ReflectionTestUtils.setField(writer, "movedThresholdMeters", 20);
    // Default: throttle allows Postgres write on every ping
    lenient()
        .when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
        .thenReturn(true);
    // Default: the mechanic exists, so the single UPDATE changes one row.
    lenient()
        .when(
            mechanicRepository.updateLocation(
                eq(mechanicId),
                anyDouble(),
                anyDouble(),
                anyString(),
                anyDouble(),
                any(LocalDateTime.class)))
        .thenReturn(1);
  }

  // A ping inside the window costs no database work at all: no write, no existence check, no
  // active-job lookup.
  @Test
  void throttled_touchesNoTable() {
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

    assertThat(writer.persistAndResolveSos(mechanicId, LAT, LNG, null)).isNull();

    verifyNoInteractions(mechanicRepository, bidSosRepository);
  }

  @Test
  void notThrottled_writesLocationH3CellAndTimestampInOneStatement() {
    when(bidSosRepository.findActiveJob(mechanicId)).thenReturn(Optional.empty());
    LocalDateTime before = LocalDateTime.now().minusSeconds(1);

    writer.persistAndResolveSos(mechanicId, LAT, LNG, null);

    org.mockito.ArgumentCaptor<String> h3 = org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.ArgumentCaptor<LocalDateTime> now =
        org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
    verify(mechanicRepository)
        .updateLocation(eq(mechanicId), eq(LAT), eq(LNG), h3.capture(), eq(20d), now.capture());
    assertThat(h3.getValue()).isNotBlank();
    // Dispatch skips a mechanic whose last ping is too old, so the write stamps the time.
    assertThat(now.getValue()).isBetween(before, LocalDateTime.now().plusSeconds(1));
    verifyNoMoreInteractions(mechanicRepository);
  }

  // One failure trips the gate: the first ping persists, later pings in the window are throttled
  // in-process, and none of them waits on Redis again.
  @Test
  void redisUnavailable_throttlesInProcess_andStopsCallingRedis() {
    when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));
    when(bidSosRepository.findActiveJob(mechanicId)).thenReturn(Optional.empty());

    writer.persistAndResolveSos(mechanicId, LAT, LNG, null);
    writer.persistAndResolveSos(mechanicId, LAT, LNG, null);
    writer.persistAndResolveSos(mechanicId, LAT, LNG, null);

    verify(mechanicRepository, times(1))
        .updateLocation(eq(mechanicId), anyDouble(), anyDouble(), anyString(), anyDouble(), any());
    verify(redisTemplate, times(1)).opsForValue();
    assertThat(redisGate.isDown()).isTrue();
  }

  // An unknown mechanic fails on every ping: the throttle key it just claimed is released, so it is
  // not answered with a 204 for the rest of the window.
  @Test
  void mechanicNotFound_throws_andReleasesTheThrottleKey() {
    when(mechanicRepository.updateLocation(
            eq(mechanicId), anyDouble(), anyDouble(), anyString(), anyDouble(), any()))
        .thenReturn(0);

    assertThatThrownBy(() -> writer.persistAndResolveSos(mechanicId, LAT, LNG, null))
        .isInstanceOf(EntityNotFoundException.class);

    verify(redisTemplate).delete(throttleKey);
  }

  @Test
  void mechanicNotFound_withRedisDown_throwsWithoutTouchingRedisAgain() {
    when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));
    when(mechanicRepository.updateLocation(
            eq(mechanicId), anyDouble(), anyDouble(), anyString(), anyDouble(), any()))
        .thenReturn(0);

    assertThatThrownBy(() -> writer.persistAndResolveSos(mechanicId, LAT, LNG, null))
        .isInstanceOf(EntityNotFoundException.class);

    verify(redisTemplate, never()).delete(anyString());
  }

  // A caller-supplied sosId is only trusted when the mechanic holds the accepted bid on that live
  // SOS. Otherwise any mechanic could publish positions, and trigger billed route lookups, on
  // someone else's ticket.
  @Test
  void sosIdSupplied_andAssigned_isTrusted_withoutTheActiveJobLookup() {
    when(bidSosRepository.isAssignedToActiveJob(mechanicId, sosId)).thenReturn(true);

    assertThat(writer.persistAndResolveSos(mechanicId, LAT, LNG, sosId)).isEqualTo(sosId);

    verify(bidSosRepository, never()).findActiveJob(any());
  }

  @Test
  void sosIdSupplied_butNotAssigned_isIgnored_andNothingIsBroadcast() {
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
    when(bidSosRepository.findActiveJob(mechanicId)).thenReturn(Optional.of(activeBid));

    assertThat(writer.persistAndResolveSos(mechanicId, LAT, LNG, null)).isEqualTo(sosId);
  }

  @Test
  void noSosId_noActiveBid_resolvesToNull() {
    when(bidSosRepository.findActiveJob(mechanicId)).thenReturn(Optional.empty());

    assertThat(writer.persistAndResolveSos(mechanicId, LAT, LNG, null)).isNull();
  }
}
