package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.enums.BidStatus;
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

    verify(bidSosRepository, never()).findFirstByMechanicMechanicIdAndStatus(any(), any());
  }

  @Test
  void notThrottled_updatesLocationAndH3Index() {
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
    when(bidSosRepository.findFirstByMechanicMechanicIdAndStatus(mechanicId, BidStatus.ACCEPTED))
        .thenReturn(Optional.empty());

    writer.persistAndResolveSos(mechanicId, LAT, LNG, null);

    assertThat(mechanic.getCurrentLocation()).isNotNull();
    assertThat(mechanic.getCurrentLocation().getY()).isEqualTo(LAT);
    assertThat(mechanic.getCurrentLocation().getX()).isEqualTo(LNG);
    assertThat(mechanic.getCurrentH3Index()).isNotBlank();
    verify(mechanicRepository).save(mechanic);
  }

  @Test
  void redisUnavailable_writesToPostgresOnEveryPing() {
    when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
    when(bidSosRepository.findFirstByMechanicMechanicIdAndStatus(mechanicId, BidStatus.ACCEPTED))
        .thenReturn(Optional.empty());

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

  // A caller-supplied sosId is authoritative — no reason to spend a query rediscovering it.
  @Test
  void sosIdSupplied_returnsItWithoutActiveBidLookup() {
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicRepository.save(mechanic)).thenReturn(mechanic);

    assertThat(writer.persistAndResolveSos(mechanicId, LAT, LNG, sosId)).isEqualTo(sosId);

    verify(bidSosRepository, never()).findFirstByMechanicMechanicIdAndStatus(any(), any());
  }

  @Test
  void noSosId_activeBidFound_resolvesToThatSos() {
    SosRequest sosRequest = mock(SosRequest.class);
    when(sosRequest.getSosId()).thenReturn(sosId);
    BidSos activeBid = mock(BidSos.class);
    when(activeBid.getSosRequest()).thenReturn(sosRequest);

    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
    when(bidSosRepository.findFirstByMechanicMechanicIdAndStatus(mechanicId, BidStatus.ACCEPTED))
        .thenReturn(Optional.of(activeBid));

    assertThat(writer.persistAndResolveSos(mechanicId, LAT, LNG, null)).isEqualTo(sosId);
  }

  @Test
  void noSosId_noActiveBid_resolvesToNull() {
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
    when(bidSosRepository.findFirstByMechanicMechanicIdAndStatus(mechanicId, BidStatus.ACCEPTED))
        .thenReturn(Optional.empty());

    assertThat(writer.persistAndResolveSos(mechanicId, LAT, LNG, null)).isNull();
  }
}
