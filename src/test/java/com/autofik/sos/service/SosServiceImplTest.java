package com.autofik.sos.service;

import com.autofik.sos.domain.entity.*;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.ApproveStatusChangeRequest;
import com.autofik.sos.dto.request.CreateSosRequest;
import com.autofik.sos.dto.request.ProposeStatusChangeRequest;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.dto.response.SosStatusHistoryResponse;
import com.autofik.sos.mapper.SosMapper;
import com.autofik.sos.mapper.SosStatusHistoryMapper;
import com.autofik.sos.repository.*;
import com.autofik.sos.service.impl.SosServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SosServiceImplTest {

    @Mock SosRequestRepository sosRequestRepository;
    @Mock UserRepository userRepository;
    @Mock VehicleRepository vehicleRepository;
    @Mock MechanicRepository mechanicRepository;
    @Mock SosStatusHistoryRepository sosStatusHistoryRepository;
    @Mock SosMapper sosMapper;
    @Mock SosStatusHistoryMapper sosStatusHistoryMapper;

    @InjectMocks SosServiceImpl sosService;

    private final UUID userId     = UUID.randomUUID();
    private final UUID sosId      = UUID.randomUUID();
    private final UUID vehicleId  = UUID.randomUUID();
    private final UUID mechanicId = UUID.randomUUID();
    private final UUID historyId  = UUID.randomUUID();

    private User user;
    private Vehicle vehicle;
    private Mechanic mechanic;
    private SosRequest pendingSos;
    private SosRequestResponse sosResponse;
    private SosStatusHistoryResponse historyResponse;

    @BeforeEach
    void setUp() {
        user = User.builder().id(userId).name("Alice").hashPassword("hash").build();
        vehicle = Vehicle.builder().vehicleId(vehicleId).plateNumber("ABC123")
                .brand("Toyota").model("Camry").year(2020).build();
        mechanic = Mechanic.builder().mechanicId(mechanicId).name("Bob").phone("081").email("bob@fix.com").hashPassword("h").build();
        pendingSos = SosRequest.builder()
                .sosId(sosId).problemType("Flat Tire").status(SosStatus.PENDING)
                .user(user).vehicle(vehicle).build();
        sosResponse = new SosRequestResponse(sosId, "Flat Tire", null, SosStatus.PENDING,
                13.7, 100.5, null, null, null, null, null);
        historyResponse = new SosStatusHistoryResponse(historyId, SosStatus.ACCEPTED,
                SosStatus.ON_THE_WAY, null, null, null, ApprovedStatus.PENDING_APPROVAL, null, null);
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_validRequest_buildsPointAndSaves() {
        CreateSosRequest request = new CreateSosRequest(userId, "Flat Tire", null, 13.7563, 100.5018, vehicleId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(vehicle));
        when(sosRequestRepository.save(any(SosRequest.class))).thenReturn(pendingSos);
        when(sosMapper.toResponse(pendingSos)).thenReturn(sosResponse);

        SosRequestResponse result = sosService.create(request);

        ArgumentCaptor<SosRequest> captor = ArgumentCaptor.forClass(SosRequest.class);
        verify(sosRequestRepository).save(captor.capture());
        SosRequest saved = captor.getValue();
        assertThat(saved.getLocation()).isNotNull();
        assertThat(saved.getLocation().getY()).isEqualTo(13.7563);
        assertThat(saved.getLocation().getX()).isEqualTo(100.5018);
        assertThat(result).isEqualTo(sosResponse);
    }

    @Test
    void create_userNotFound_throwsEntityNotFoundException() {
        CreateSosRequest request = new CreateSosRequest(userId, "Flat Tire", null, 13.7, 100.5, vehicleId);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sosService.create(request))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(userId.toString());
    }

    @Test
    void create_vehicleNotFound_throwsEntityNotFoundException() {
        CreateSosRequest request = new CreateSosRequest(userId, "Flat Tire", null, 13.7, 100.5, vehicleId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sosService.create(request))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(vehicleId.toString());
    }

    // ── getById ───────────────────────────────────────────────────────────────

    @Test
    void getById_found_returnsResponse() {
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
        when(sosMapper.toResponse(pendingSos)).thenReturn(sosResponse);

        assertThat(sosService.getById(sosId)).isEqualTo(sosResponse);
    }

    @Test
    void getById_notFound_throwsEntityNotFoundException() {
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sosService.getById(sosId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── getByUser / getByStatus ───────────────────────────────────────────────

    @Test
    void getByUser_delegatesToRepositoryAndMapsResult() {
        when(sosRequestRepository.findByUserId(userId)).thenReturn(List.of(pendingSos));
        when(sosMapper.toResponseList(List.of(pendingSos))).thenReturn(List.of(sosResponse));

        assertThat(sosService.getByUser(userId)).containsExactly(sosResponse);
    }

    @Test
    void getByStatus_delegatesToRepositoryAndMapsResult() {
        when(sosRequestRepository.findByStatus(SosStatus.PENDING)).thenReturn(List.of(pendingSos));
        when(sosMapper.toResponseList(List.of(pendingSos))).thenReturn(List.of(sosResponse));

        assertThat(sosService.getByStatus(SosStatus.PENDING)).containsExactly(sosResponse);
    }

    // ── proposeStatusChange ───────────────────────────────────────────────────

    @Test
    void proposeStatusChange_validTransition_savesHistoryAndReturnsResponse() {
        ProposeStatusChangeRequest request = new ProposeStatusChangeRequest(mechanicId, sosId, SosStatus.ACCEPTED);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(sosStatusHistoryRepository.save(any(SosStatusHistory.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(sosStatusHistoryMapper.toResponse(any(SosStatusHistory.class))).thenReturn(historyResponse);

        SosStatusHistoryResponse result = sosService.proposeStatusChange(request);

        ArgumentCaptor<SosStatusHistory> captor = ArgumentCaptor.forClass(SosStatusHistory.class);
        verify(sosStatusHistoryRepository).save(captor.capture());
        SosStatusHistory saved = captor.getValue();
        assertThat(saved.getFromStatus()).isEqualTo(SosStatus.PENDING);
        assertThat(saved.getToStatus()).isEqualTo(SosStatus.ACCEPTED);
        assertThat(saved.getApprovedStatus()).isEqualTo(ApprovedStatus.PENDING_APPROVAL);
        assertThat(result).isEqualTo(historyResponse);
    }

    @Test
    void proposeStatusChange_invalidTransition_throwsIllegalStateException() {
        ProposeStatusChangeRequest request = new ProposeStatusChangeRequest(mechanicId, sosId, SosStatus.COMPLETED);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));

        assertThatThrownBy(() -> sosService.proposeStatusChange(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void proposeStatusChange_terminalStatus_throwsIllegalStateException() {
        SosRequest completedSos = SosRequest.builder().sosId(sosId)
                .problemType("X").status(SosStatus.COMPLETED).user(user).vehicle(vehicle).build();
        ProposeStatusChangeRequest request = new ProposeStatusChangeRequest(mechanicId, sosId, SosStatus.CANCELLED);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(completedSos));

        assertThatThrownBy(() -> sosService.proposeStatusChange(request))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void proposeStatusChange_mechanicNotFound_throwsEntityNotFoundException() {
        ProposeStatusChangeRequest request = new ProposeStatusChangeRequest(mechanicId, sosId, SosStatus.ACCEPTED);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sosService.proposeStatusChange(request))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── approveStatusChange ───────────────────────────────────────────────────

    @Test
    void approveStatusChange_accepted_updatesSosStatus() {
        SosRequest acceptedSos = SosRequest.builder().sosId(sosId)
                .problemType("X").status(SosStatus.ACCEPTED).user(user).vehicle(vehicle).build();
        SosStatusHistory history = SosStatusHistory.builder()
                .id(historyId).fromStatus(SosStatus.ACCEPTED).toStatus(SosStatus.ON_THE_WAY)
                .proposedBy(mechanic).sosRequest(acceptedSos)
                .approvedStatus(ApprovedStatus.PENDING_APPROVAL).build();
        ApproveStatusChangeRequest request = new ApproveStatusChangeRequest(userId, historyId, ApprovedStatus.ACCEPTED);

        when(sosStatusHistoryRepository.findById(historyId)).thenReturn(Optional.of(history));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(sosStatusHistoryRepository.save(history)).thenReturn(history);
        when(sosRequestRepository.save(acceptedSos)).thenReturn(acceptedSos);
        when(sosMapper.toResponse(acceptedSos)).thenReturn(sosResponse);

        sosService.approveStatusChange(request);

        assertThat(acceptedSos.getStatus()).isEqualTo(SosStatus.ON_THE_WAY);
        verify(sosRequestRepository).save(acceptedSos);
    }

    @Test
    void approveStatusChange_rejected_doesNotUpdateSosStatus() {
        SosRequest acceptedSos = SosRequest.builder().sosId(sosId)
                .problemType("X").status(SosStatus.ACCEPTED).user(user).vehicle(vehicle).build();
        SosStatusHistory history = SosStatusHistory.builder()
                .id(historyId).fromStatus(SosStatus.ACCEPTED).toStatus(SosStatus.ON_THE_WAY)
                .proposedBy(mechanic).sosRequest(acceptedSos)
                .approvedStatus(ApprovedStatus.PENDING_APPROVAL).build();
        ApproveStatusChangeRequest request = new ApproveStatusChangeRequest(userId, historyId, ApprovedStatus.REJECTED);

        when(sosStatusHistoryRepository.findById(historyId)).thenReturn(Optional.of(history));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(sosStatusHistoryRepository.save(history)).thenReturn(history);
        when(sosMapper.toResponse(acceptedSos)).thenReturn(sosResponse);

        sosService.approveStatusChange(request);

        assertThat(acceptedSos.getStatus()).isEqualTo(SosStatus.ACCEPTED);
        verify(sosRequestRepository, never()).save(any());
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    @Test
    void cancel_pendingStatus_setsCancelled() {
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
        when(sosRequestRepository.save(pendingSos)).thenReturn(pendingSos);
        when(sosMapper.toResponse(pendingSos)).thenReturn(sosResponse);

        sosService.cancel(sosId, userId);

        assertThat(pendingSos.getStatus()).isEqualTo(SosStatus.CANCELLED);
        verify(sosRequestRepository).save(pendingSos);
    }

    @Test
    void cancel_completedStatus_throwsIllegalStateException() {
        SosRequest completedSos = SosRequest.builder().sosId(sosId)
                .problemType("X").status(SosStatus.COMPLETED).user(user).vehicle(vehicle).build();
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(completedSos));

        assertThatThrownBy(() -> sosService.cancel(sosId, userId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancel_cancelledStatus_throwsIllegalStateException() {
        SosRequest cancelledSos = SosRequest.builder().sosId(sosId)
                .problemType("X").status(SosStatus.CANCELLED).user(user).vehicle(vehicle).build();
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(cancelledSos));

        assertThatThrownBy(() -> sosService.cancel(sosId, userId))
                .isInstanceOf(IllegalStateException.class);
    }
}
