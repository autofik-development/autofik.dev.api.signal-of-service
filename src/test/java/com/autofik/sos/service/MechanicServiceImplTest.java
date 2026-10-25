package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicSkill;
import com.autofik.sos.dto.event.MechanicLocationEvent;
import com.autofik.sos.dto.external.EtaInfo;
import com.autofik.sos.dto.response.MechanicLocationResponse;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.dto.response.MechanicSummaryResponse;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.MechanicMapper;
import com.autofik.sos.mapper.MechanicSkillMapper;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.MechanicSkillRepository;
import com.autofik.sos.service.impl.MechanicLocationWriter;
import com.autofik.sos.service.impl.MechanicServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class MechanicServiceImplTest {

  private static final double LAT = 11.5573;
  private static final double LNG = 104.9282;
  private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);

  @Mock MechanicRepository mechanicRepository;
  @Mock com.autofik.sos.service.impl.SsoProjection ssoProjection;
  @Mock MechanicSkillRepository mechanicSkillRepository;
  @Mock MechanicLocationWriter locationWriter;
  @Mock MechanicMapper mechanicMapper;
  @Mock MechanicSkillMapper mechanicSkillMapper;
  @Mock SosEventProducer sosEventProducer;
  @Mock RouteEtaService routeEtaService;
  @Mock StringRedisTemplate redisTemplate;

  @Spy
  com.autofik.sos.service.impl.RedisHealthGate redisGate =
      new com.autofik.sos.service.impl.RedisHealthGate();

  @Mock ObjectMapper objectMapper;

  @InjectMocks MechanicServiceImpl mechanicService;

  private ValueOperations<String, String> valueOps;

  private final UUID mechanicId = UUID.randomUUID();
  private final UUID sosId = UUID.randomUUID();
  private Mechanic mechanic;
  private MechanicResponse mechanicResponse;
  private MechanicSummaryResponse mechanicSummary;

  @BeforeEach
  void setUp() {
    valueOps = mock(ValueOperations.class);
    lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    org.springframework.test.util.ReflectionTestUtils.setField(mechanicService, "h3Resolution", 7);
    org.springframework.test.util.ReflectionTestUtils.setField(
        mechanicService, "h3CenterToCenterKm", 2.114);
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
    mechanicResponse =
        new MechanicResponse(
            mechanicId,
            "Bob",
            "081",
            "bob@fix.com",
            null,
            null,
            null,
            10,
            BigDecimal.ZERO,
            0,
            true,
            null,
            null);
    mechanicSummary =
        new MechanicSummaryResponse(mechanicId, "Bob", null, 10, BigDecimal.ZERO, true, null, null);
  }

  // ── getById ───────────────────────────────────────────────────────────────

  @Test
  void getById_found_returnsThePublicViewWithoutPosition() {
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(mechanicMapper.toSummaryWithoutPosition(mechanic)).thenReturn(mechanicSummary);

    assertThat(mechanicService.getById(mechanicId)).isEqualTo(mechanicSummary);
    verify(mechanicMapper, never()).toResponse(any());
  }

  @Test
  void getById_notFound_throwsEntityNotFoundException() {
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> mechanicService.getById(mechanicId))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining(mechanicId.toString());
  }

  // ── findAvailable ─────────────────────────────────────────────────────────

  @Test
  void findAvailable_stage1Empty_returnsEmptyWithoutStage2() {
    when(mechanicRepository.findByH3Cells(anyList())).thenReturn(List.of());

    assertThat(mechanicService.findAvailable(LAT, LNG, null, 15)).isEmpty();
    verify(mechanicRepository, never()).filterByRadius(any(), anyDouble(), anyDouble());
  }

  @Test
  void findAvailable_stage2Empty_returnsEmpty() {
    when(mechanicRepository.findByH3Cells(anyList())).thenReturn(List.of(mechanic));
    when(mechanicRepository.filterByRadius(anyList(), eq(LAT), eq(LNG))).thenReturn(ids());

    assertThat(mechanicService.findAvailable(LAT, LNG, null, 15)).isEmpty();
  }

  @Test
  void findAvailable_stage2HasCandidates_returnsMappedList() {
    when(mechanicRepository.findByH3Cells(anyList())).thenReturn(List.of(mechanic));
    when(mechanicRepository.filterByRadius(anyList(), eq(LAT), eq(LNG))).thenReturn(ids(mechanic));
    when(mechanicMapper.toSummaryList(List.of(mechanic))).thenReturn(List.of(mechanicSummary));

    assertThat(mechanicService.findAvailable(LAT, LNG, null, 15)).containsExactly(mechanicSummary);
  }

  @Test
  void findAvailable_withProblemType_filtersToMatchingSkillOnly() {
    Mechanic flatTireMechanic = buildMechanicWithSkill("Flat Tire");
    Mechanic engineMechanic = buildMechanicWithSkill("Engine");
    MechanicSummaryResponse flatTireResponse =
        new MechanicSummaryResponse(
            flatTireMechanic.getMechanicId(), "Bob", null, 10, BigDecimal.ZERO, true, null, null);

    when(mechanicRepository.findByH3Cells(anyList()))
        .thenReturn(List.of(flatTireMechanic, engineMechanic));
    when(mechanicRepository.filterByRadius(anyList(), eq(LAT), eq(LNG)))
        .thenReturn(ids(flatTireMechanic, engineMechanic));
    when(mechanicRepository.findByIdsWithSkills(anyList()))
        .thenReturn(List.of(flatTireMechanic, engineMechanic));
    when(mechanicMapper.toSummaryList(List.of(flatTireMechanic)))
        .thenReturn(List.of(flatTireResponse));

    List<MechanicSummaryResponse> result = mechanicService.findAvailable(LAT, LNG, "Flat Tire", 15);

    assertThat(result).containsExactly(flatTireResponse);
  }

  @Test
  void findAvailable_withProblemType_noSkillMatch_returnsEmpty() {
    Mechanic engineMechanic = buildMechanicWithSkill("Engine");
    when(mechanicRepository.findByH3Cells(anyList())).thenReturn(List.of(engineMechanic));
    when(mechanicRepository.filterByRadius(anyList(), eq(LAT), eq(LNG)))
        .thenReturn(ids(engineMechanic));
    when(mechanicRepository.findByIdsWithSkills(anyList())).thenReturn(List.of(engineMechanic));
    when(mechanicMapper.toSummaryList(List.of())).thenReturn(List.of());

    assertThat(mechanicService.findAvailable(LAT, LNG, "Flat Tire", 15)).isEmpty();
  }

  // ── skills ────────────────────────────────────────────────────────────────

  // A known mechanic with nothing declared is a valid state — empty list, not 404.
  @Test
  void getSkills_knownMechanicWithNoSkills_returnsEmptyList() {
    when(mechanicRepository.existsById(mechanicId)).thenReturn(true);
    when(mechanicSkillRepository.findByMechanicMechanicId(mechanicId)).thenReturn(List.of());
    when(mechanicSkillMapper.toResponseList(List.of())).thenReturn(List.of());

    assertThat(mechanicService.getSkills(mechanicId)).isEmpty();
  }

  @Test
  void getSkills_unknownMechanic_throws() {
    when(mechanicRepository.existsById(mechanicId)).thenReturn(false);

    assertThatThrownBy(() -> mechanicService.getSkills(mechanicId))
        .isInstanceOf(EntityNotFoundException.class);
  }

  // ── updateLocation ────────────────────────────────────────────────────────

  // Persistence, the write throttle and the active-job lookup moved to MechanicLocationWriter
  // so they keep their transaction while the broadcast below runs without one — they are
  // covered in MechanicLocationWriterTest. What remains here is the broadcast half.

  @Test
  void updateLocation_withSosId_publishesLocationViaRedisPubSub() throws Exception {
    when(locationWriter.persistAndResolveSos(mechanicId, LAT, LNG, sosId)).thenReturn(sosId);
    when(objectMapper.writeValueAsString(any())).thenReturn("{}");

    mechanicService.updateLocation(mechanicId, LAT, LNG, sosId);

    verify(redisTemplate).convertAndSend(eq("mechanic:loc:" + sosId), anyString());
    verify(sosEventProducer, never()).publishMechanicLocation(any());
  }

  @Test
  void updateLocation_withSosId_redisPubSubFails_fallsBackToKafka() throws Exception {
    when(locationWriter.persistAndResolveSos(mechanicId, LAT, LNG, sosId)).thenReturn(sosId);
    when(objectMapper.writeValueAsString(any())).thenReturn("{}");
    doThrow(new RuntimeException("pub/sub down"))
        .when(redisTemplate)
        .convertAndSend(anyString(), anyString());

    mechanicService.updateLocation(mechanicId, LAT, LNG, sosId);

    ArgumentCaptor<MechanicLocationEvent> captor =
        ArgumentCaptor.forClass(MechanicLocationEvent.class);
    verify(sosEventProducer).publishMechanicLocation(captor.capture());
    assertThat(captor.getValue().sosId()).isEqualTo(sosId);
    assertThat(captor.getValue().mechanicId()).isEqualTo(mechanicId);
    assertThat(captor.getValue().latitude()).isEqualTo(LAT);
    assertThat(captor.getValue().longitude()).isEqualTo(LNG);
  }

  // No sosId supplied: the writer resolves the active job and the broadcast follows it,
  // so a reconnecting app keeps the customer's map alive.
  @Test
  void updateLocation_writerResolvesActiveJob_publishesOnThatSos() throws Exception {
    when(locationWriter.persistAndResolveSos(mechanicId, LAT, LNG, null)).thenReturn(sosId);
    when(objectMapper.writeValueAsString(any())).thenReturn("{}");

    mechanicService.updateLocation(mechanicId, LAT, LNG);

    verify(redisTemplate).convertAndSend(eq("mechanic:loc:" + sosId), anyString());
  }

  @Test
  void updateLocation_withSosId_attachesEtaToEvent() throws Exception {
    when(locationWriter.persistAndResolveSos(mechanicId, LAT, LNG, sosId)).thenReturn(sosId);
    when(objectMapper.writeValueAsString(any())).thenReturn("{}");
    when(routeEtaService.currentEta(sosId, LAT, LNG))
        .thenReturn(new EtaInfo(4200, 3900, "4.2 km", "1 hr 5 min"));
    doThrow(new RuntimeException("pub/sub down"))
        .when(redisTemplate)
        .convertAndSend(anyString(), anyString());

    mechanicService.updateLocation(mechanicId, LAT, LNG, sosId);

    ArgumentCaptor<MechanicLocationEvent> captor =
        ArgumentCaptor.forClass(MechanicLocationEvent.class);
    verify(sosEventProducer).publishMechanicLocation(captor.capture());
    assertThat(captor.getValue().remainingDistanceM()).isEqualTo(4200L);
    assertThat(captor.getValue().remainingDurationS()).isEqualTo(3900L);
    assertThat(captor.getValue().remainingDistanceText()).isEqualTo("4.2 km");
    assertThat(captor.getValue().remainingDurationText()).isEqualTo("1 hr 5 min");
  }

  // No ETA (arrived, cancelled, unresolvable route) must still broadcast the position —
  // the map keeps moving, only the countdown drops out.
  @Test
  void updateLocation_withSosId_noEta_stillPublishesPositionWithNullEtaFields() throws Exception {
    when(locationWriter.persistAndResolveSos(mechanicId, LAT, LNG, sosId)).thenReturn(sosId);
    when(objectMapper.writeValueAsString(any())).thenReturn("{}");
    when(routeEtaService.currentEta(sosId, LAT, LNG)).thenReturn(null);
    doThrow(new RuntimeException("pub/sub down"))
        .when(redisTemplate)
        .convertAndSend(anyString(), anyString());

    mechanicService.updateLocation(mechanicId, LAT, LNG, sosId);

    ArgumentCaptor<MechanicLocationEvent> captor =
        ArgumentCaptor.forClass(MechanicLocationEvent.class);
    verify(sosEventProducer).publishMechanicLocation(captor.capture());
    assertThat(captor.getValue().latitude()).isEqualTo(LAT);
    assertThat(captor.getValue().longitude()).isEqualTo(LNG);
    assertThat(captor.getValue().remainingDistanceM()).isNull();
    assertThat(captor.getValue().remainingDurationText()).isNull();
  }

  // Idle mechanic: nothing to broadcast, and no Google call should be paid for either.
  @Test
  void updateLocation_noActiveJob_doesNotPublish() {
    when(locationWriter.persistAndResolveSos(mechanicId, LAT, LNG, null)).thenReturn(null);

    mechanicService.updateLocation(mechanicId, LAT, LNG);

    verify(redisTemplate, never()).convertAndSend(anyString(), any());
    verify(sosEventProducer, never()).publishMechanicLocation(any());
    verify(routeEtaService, never()).currentEta(any(), anyDouble(), anyDouble());
  }

  // ── getCurrentLocation ────────────────────────────────────────────────────

  @Test
  void getCurrentLocation_returnsLocationFromPostgres() {
    mechanic.setCurrentLocation(GEO.createPoint(new Coordinate(LNG, LAT)));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));

    MechanicLocationResponse result = mechanicService.getCurrentLocation(mechanicId, mechanicId);

    assertThat(result.latitude()).isEqualTo(LAT);
    assertThat(result.longitude()).isEqualTo(LNG);
    assertThat(result.asOf()).isNotNull();
  }

  @Test
  void getCurrentLocation_noLocationSet_throwsEntityNotFoundException() {
    mechanic.setCurrentLocation(null);
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));

    assertThatThrownBy(() -> mechanicService.getCurrentLocation(mechanicId, mechanicId))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining(mechanicId.toString());
  }

  @Test
  void getCurrentLocation_mechanicNotFound_throwsEntityNotFoundException() {
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> mechanicService.getCurrentLocation(mechanicId, mechanicId))
        .isInstanceOf(EntityNotFoundException.class);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private Mechanic buildMechanicWithSkill(String skillName) {
    Mechanic m =
        Mechanic.builder()
            .mechanicId(UUID.randomUUID())
            .fullName("Tech")
            .phoneNumber("082")
            .email(UUID.randomUUID() + "@fix.com")
            .availableStatus(true)
            .build();
    MechanicSkill skill =
        MechanicSkill.builder()
            .id(UUID.randomUUID())
            .skillTags(new String[] {skillName})
            .mechanic(m)
            .build();
    m.setSkills(List.of(skill));
    return m;
  }

  private static List<UUID> ids(Mechanic... mechanics) {
    return java.util.Arrays.stream(mechanics).map(Mechanic::getMechanicId).toList();
  }
}
