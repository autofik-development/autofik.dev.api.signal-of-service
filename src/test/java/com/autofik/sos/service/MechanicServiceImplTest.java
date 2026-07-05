package com.autofik.sos.service;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicSkill;
import com.autofik.sos.domain.enums.ApprovalStatus;
import com.autofik.sos.domain.enums.MechanicRole;
import java.util.Arrays;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.MechanicMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.service.impl.MechanicServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MechanicServiceImplTest {

    private static final double LAT = 13.7563;
    private static final double LNG = 100.5018;

    @Mock MechanicRepository mechanicRepository;
    @Mock BidSosRepository bidSosRepository;
    @Mock MechanicMapper mechanicMapper;
    @Mock SosEventProducer sosEventProducer;

    @InjectMocks MechanicServiceImpl mechanicService;

    private final UUID mechanicId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private Mechanic mechanic;
    private MechanicResponse mechanicResponse;

    @BeforeEach
    void setUp() {
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
    void findAvailable_stage1Empty_returnsEmptyListWithoutCallingStage2() {
        when(mechanicRepository.findByH3Cells(anyList())).thenReturn(List.of());

        List<MechanicResponse> result = mechanicService.findAvailable(LAT, LNG);

        assertThat(result).isEmpty();
        verify(mechanicRepository).findByH3Cells(anyList());
        verify(mechanicRepository, org.mockito.Mockito.never()).filterByRadius(anyList(), anyDouble(), anyDouble());
    }

    @Test
    void findAvailable_stage2ReturnsCandidates_returnsMappedList() {
        when(mechanicRepository.findByH3Cells(anyList())).thenReturn(List.of(mechanic));
        when(mechanicRepository.filterByRadius(anyList(), eq(LAT), eq(LNG))).thenReturn(List.of(mechanic));
        when(mechanicMapper.toResponseList(List.of(mechanic))).thenReturn(List.of(mechanicResponse));

        List<MechanicResponse> result = mechanicService.findAvailable(LAT, LNG);

        assertThat(result).containsExactly(mechanicResponse);
    }

    @Test
    void findAvailable_withProblemType_filtersOutNonMatchingSpecializations() {
        Mechanic flatTireMechanic = buildMechanicWithSkill("Flat Tire");
        Mechanic engineMechanic   = buildMechanicWithSkill("Engine");
        MechanicResponse flatTireResponse = new MechanicResponse(
                flatTireMechanic.getMechanicId(), "Bob", "081", "b@fix.com",
                null, null, ApprovalStatus.PENDING, null, 10,
                BigDecimal.ZERO, 0, true, ApprovalStatus.PENDING, null, null);

        when(mechanicRepository.findByH3Cells(anyList()))
                .thenReturn(List.of(flatTireMechanic, engineMechanic));
        when(mechanicRepository.filterByRadius(anyList(), eq(LAT), eq(LNG)))
                .thenReturn(List.of(flatTireMechanic, engineMechanic));
        when(mechanicMapper.toResponseList(List.of(flatTireMechanic)))
                .thenReturn(List.of(flatTireResponse));

        List<MechanicResponse> result = mechanicService.findAvailable(LAT, LNG, "Flat Tire");

        assertThat(result).containsExactly(flatTireResponse);
    }

    @Test
    void findAvailable_withProblemType_noSpecializationMatch_returnsEmpty() {
        Mechanic engineMechanic = buildMechanicWithSkill("Engine");
        when(mechanicRepository.findByH3Cells(anyList())).thenReturn(List.of(engineMechanic));
        when(mechanicRepository.filterByRadius(anyList(), eq(LAT), eq(LNG))).thenReturn(List.of(engineMechanic));
        when(mechanicMapper.toResponseList(List.of())).thenReturn(List.of());

        List<MechanicResponse> result = mechanicService.findAvailable(LAT, LNG, "Flat Tire");

        assertThat(result).isEmpty();
    }

    @Test
    void findAvailable_nullProblemType_skipsSpecializationFilter() {
        mechanic.setSkills(List.of());
        when(mechanicRepository.findByH3Cells(anyList())).thenReturn(List.of(mechanic));
        when(mechanicRepository.filterByRadius(anyList(), eq(LAT), eq(LNG))).thenReturn(List.of(mechanic));
        when(mechanicMapper.toResponseList(List.of(mechanic))).thenReturn(List.of(mechanicResponse));

        assertThat(mechanicService.findAvailable(LAT, LNG, null)).containsExactly(mechanicResponse);
    }

    // ── updateLocation ────────────────────────────────────────────────────────

    @Test
    void updateLocation_updatesLocationAndH3Index() {
        double newLat = 13.8000, newLng = 100.6000;
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
        when(mechanicMapper.toResponse(mechanic)).thenReturn(mechanicResponse);

        mechanicService.updateLocation(mechanicId, newLat, newLng);

        assertThat(mechanic.getCurrentLocation()).isNotNull();
        assertThat(mechanic.getCurrentLocation().getY()).isEqualTo(newLat);
        assertThat(mechanic.getCurrentLocation().getX()).isEqualTo(newLng);
        assertThat(mechanic.getCurrentH3Index()).isNotBlank();
        verify(mechanicRepository).save(mechanic);
    }

    @Test
    void updateLocation_notFound_throwsEntityNotFoundException() {
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mechanicService.updateLocation(mechanicId, LAT, LNG))
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
