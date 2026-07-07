package com.autofik.sos.service;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicSkill;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.enums.ApprovalStatus;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.dto.event.MechanicLocationEvent;
import com.autofik.sos.dto.response.MechanicLocationResponse;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.MechanicMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.service.impl.MechanicServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class MechanicServiceImplTest {

    private static final double LAT = 11.5573;
    private static final double LNG = 104.9282;
    private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);

    @Mock MechanicRepository mechanicRepository;
    @Mock BidSosRepository bidSosRepository;
    @Mock MechanicMapper mechanicMapper;
    @Mock SosEventProducer sosEventProducer;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ObjectMapper objectMapper;

    @InjectMocks MechanicServiceImpl mechanicService;

    private ValueOperations<String, String> valueOps;

    private final UUID mechanicId = UUID.randomUUID();
    private final UUID sosId = UUID.randomUUID();
    private Mechanic mechanic;
    private MechanicResponse mechanicResponse;

    @BeforeEach
    void setUp() {
        valueOps = mock(ValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // Default: throttle allows Postgres write on every ping
        lenient().when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        mechanic = Mechanic.builder()
                .mechanicId(mechanicId).fullName("Bob").phoneNumber("081").email("bob@fix.com")
                .availableStatus(true).build();
        mechanicResponse = new MechanicResponse(
                mechanicId, "Bob", "081", "bob@fix.com",
                null, null, ApprovalStatus.PENDING, null, 10,
                BigDecimal.ZERO, 0, true, ApprovalStatus.PENDING, null, null);
    }

    // ── getById ───────────────────────────────────────────────────────────────

    @Test
    void getById_found_returnsResponse() {
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(mechanicMapper.toResponse(mechanic)).thenReturn(mechanicResponse);

        assertThat(mechanicService.getById(mechanicId)).isEqualTo(mechanicResponse);
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
        when(mechanicRepository.filterByRadius(anyList(), eq(LAT), eq(LNG))).thenReturn(List.of());

        assertThat(mechanicService.findAvailable(LAT, LNG, null, 15)).isEmpty();
    }

    @Test
    void findAvailable_stage2HasCandidates_returnsMappedList() {
        when(mechanicRepository.findByH3Cells(anyList())).thenReturn(List.of(mechanic));
        when(mechanicRepository.filterByRadius(anyList(), eq(LAT), eq(LNG))).thenReturn(List.of(mechanic));
        when(mechanicMapper.toResponseList(List.of(mechanic))).thenReturn(List.of(mechanicResponse));

        assertThat(mechanicService.findAvailable(LAT, LNG, null, 15)).containsExactly(mechanicResponse);
    }

    @Test
    void findAvailable_withProblemType_filtersToMatchingSkillOnly() {
        Mechanic flatTireMechanic = buildMechanicWithSkill("Flat Tire");
        Mechanic engineMechanic = buildMechanicWithSkill("Engine");
        MechanicResponse flatTireResponse = new MechanicResponse(
                flatTireMechanic.getMechanicId(), "Bob", "081", "b@fix.com",
                null, null, ApprovalStatus.PENDING, null, 10,
                BigDecimal.ZERO, 0, true, ApprovalStatus.PENDING, null, null);

        when(mechanicRepository.findByH3Cells(anyList())).thenReturn(List.of(flatTireMechanic, engineMechanic));
        when(mechanicRepository.filterByRadius(anyList(), eq(LAT), eq(LNG)))
                .thenReturn(List.of(flatTireMechanic, engineMechanic));
        when(mechanicRepository.findByIdsWithSkills(anyList()))
                .thenReturn(List.of(flatTireMechanic, engineMechanic));
        when(mechanicMapper.toResponseList(List.of(flatTireMechanic))).thenReturn(List.of(flatTireResponse));

        List<MechanicResponse> result = mechanicService.findAvailable(LAT, LNG, "Flat Tire", 15);

        assertThat(result).containsExactly(flatTireResponse);
    }

    @Test
    void findAvailable_withProblemType_noSkillMatch_returnsEmpty() {
        Mechanic engineMechanic = buildMechanicWithSkill("Engine");
        when(mechanicRepository.findByH3Cells(anyList())).thenReturn(List.of(engineMechanic));
        when(mechanicRepository.filterByRadius(anyList(), eq(LAT), eq(LNG))).thenReturn(List.of(engineMechanic));
        when(mechanicRepository.findByIdsWithSkills(anyList())).thenReturn(List.of(engineMechanic));
        when(mechanicMapper.toResponseList(List.of())).thenReturn(List.of());

        assertThat(mechanicService.findAvailable(LAT, LNG, "Flat Tire", 15)).isEmpty();
    }

    // ── updateLocation ────────────────────────────────────────────────────────

    @Test
    void updateLocation_throttled_skipsPostgresWrite() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(mechanicRepository.existsById(mechanicId)).thenReturn(true);
        when(bidSosRepository.findFirstByMechanicMechanicIdAndStatus(mechanicId, BidStatus.ACCEPTED))
                .thenReturn(Optional.empty());

        mechanicService.updateLocation(mechanicId, LAT, LNG);

        verify(mechanicRepository, never()).findById(any());
        verify(mechanicRepository, never()).save(any());
    }

    @Test
    void updateLocation_notThrottled_updatesLocationAndH3Index() {
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
        when(bidSosRepository.findFirstByMechanicMechanicIdAndStatus(mechanicId, BidStatus.ACCEPTED))
                .thenReturn(Optional.empty());

        mechanicService.updateLocation(mechanicId, LAT, LNG);

        assertThat(mechanic.getCurrentLocation()).isNotNull();
        assertThat(mechanic.getCurrentLocation().getY()).isEqualTo(LAT);
        assertThat(mechanic.getCurrentLocation().getX()).isEqualTo(LNG);
        assertThat(mechanic.getCurrentH3Index()).isNotBlank();
        verify(mechanicRepository).save(mechanic);
    }

    @Test
    void updateLocation_redisUnavailable_writesToPostgresOnEveryPing() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
        when(bidSosRepository.findFirstByMechanicMechanicIdAndStatus(mechanicId, BidStatus.ACCEPTED))
                .thenReturn(Optional.empty());

        mechanicService.updateLocation(mechanicId, LAT, LNG);

        verify(mechanicRepository).save(mechanic);
    }

    @Test
    void updateLocation_mechanicNotFound_persistPath_throws() {
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mechanicService.updateLocation(mechanicId, LAT, LNG))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void updateLocation_mechanicNotFound_throttledPath_throws() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(mechanicRepository.existsById(mechanicId)).thenReturn(false);

        assertThatThrownBy(() -> mechanicService.updateLocation(mechanicId, LAT, LNG))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void updateLocation_withSosId_publishesLocationViaRedisPubSub() throws Exception {
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        mechanicService.updateLocation(mechanicId, LAT, LNG, sosId);

        verify(redisTemplate).convertAndSend(eq("mechanic:loc:" + sosId), anyString());
        verify(sosEventProducer, never()).publishMechanicLocation(any());
    }

    @Test
    void updateLocation_withSosId_redisPubSubFails_fallsBackToKafka() throws Exception {
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        doThrow(new RuntimeException("pub/sub down")).when(redisTemplate).convertAndSend(anyString(), anyString());

        mechanicService.updateLocation(mechanicId, LAT, LNG, sosId);

        ArgumentCaptor<MechanicLocationEvent> captor = ArgumentCaptor.forClass(MechanicLocationEvent.class);
        verify(sosEventProducer).publishMechanicLocation(captor.capture());
        assertThat(captor.getValue().sosId()).isEqualTo(sosId);
        assertThat(captor.getValue().mechanicId()).isEqualTo(mechanicId);
        assertThat(captor.getValue().latitude()).isEqualTo(LAT);
        assertThat(captor.getValue().longitude()).isEqualTo(LNG);
    }

    @Test
    void updateLocation_withoutSosId_activeBidFound_publishesLocation() throws Exception {
        SosRequest sosRequest = mock(SosRequest.class);
        when(sosRequest.getSosId()).thenReturn(sosId);
        BidSos activeBid = mock(BidSos.class);
        when(activeBid.getSosRequest()).thenReturn(sosRequest);

        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
        when(bidSosRepository.findFirstByMechanicMechanicIdAndStatus(mechanicId, BidStatus.ACCEPTED))
                .thenReturn(Optional.of(activeBid));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        mechanicService.updateLocation(mechanicId, LAT, LNG);

        verify(redisTemplate).convertAndSend(eq("mechanic:loc:" + sosId), anyString());
    }

    @Test
    void updateLocation_withoutSosId_noActiveBid_doesNotPublish() {
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
        when(bidSosRepository.findFirstByMechanicMechanicIdAndStatus(mechanicId, BidStatus.ACCEPTED))
                .thenReturn(Optional.empty());

        mechanicService.updateLocation(mechanicId, LAT, LNG);

        verify(redisTemplate, never()).convertAndSend(anyString(), any());
        verify(sosEventProducer, never()).publishMechanicLocation(any());
    }

    // ── getCurrentLocation ────────────────────────────────────────────────────

    @Test
    void getCurrentLocation_returnsLocationFromPostgres() {
        mechanic.setCurrentLocation(GEO.createPoint(new Coordinate(LNG, LAT)));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));

        MechanicLocationResponse result = mechanicService.getCurrentLocation(mechanicId);

        assertThat(result.latitude()).isEqualTo(LAT);
        assertThat(result.longitude()).isEqualTo(LNG);
        assertThat(result.asOf()).isNotNull();
    }

    @Test
    void getCurrentLocation_noLocationSet_throwsEntityNotFoundException() {
        mechanic.setCurrentLocation(null);
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));

        assertThatThrownBy(() -> mechanicService.getCurrentLocation(mechanicId))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(mechanicId.toString());
    }

    @Test
    void getCurrentLocation_mechanicNotFound_throwsEntityNotFoundException() {
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mechanicService.getCurrentLocation(mechanicId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Mechanic buildMechanicWithSkill(String skillName) {
        Mechanic m = Mechanic.builder()
                .mechanicId(UUID.randomUUID()).fullName("Tech").phoneNumber("082")
                .email(UUID.randomUUID() + "@fix.com")
                .availableStatus(true).build();
        MechanicSkill skill = MechanicSkill.builder()
                .id(UUID.randomUUID())
                .skillTags(new String[]{skillName})
                .mechanic(m).build();
        m.setSkills(List.of(skill));
        return m;
    }
}
