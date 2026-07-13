package com.autofik.sos.service;

import com.autofik.sos.domain.entity.*;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.ApproveStatusChangeRequest;
import com.autofik.sos.dto.request.CreateSosRequest;
import com.autofik.sos.dto.request.ProposeStatusChangeRequest;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.dto.response.SosStatusHistoryResponse;
import com.autofik.sos.dto.event.SosCreatedEvent;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.dto.event.SosStatusUpdatedEvent;
import com.autofik.sos.exception.TooManyRequestsException;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.MechanicMapper;
import com.autofik.sos.mapper.SosMapper;
import com.autofik.sos.mapper.SosStatusHistoryMapper;
import com.autofik.sos.repository.*;
import com.autofik.sos.service.impl.SosServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SosServiceImplTest {

    @Mock SosRequestRepository sosRequestRepository;
    @Mock UserRepository userRepository;
    @Mock VehicleRepository vehicleRepository;
    @Mock MechanicRepository mechanicRepository;
    @Mock BidSosRepository bidSosRepository;
    @Mock SosStatusHistoryRepository sosStatusHistoryRepository;
    @Mock SosMapper sosMapper;
    @Mock SosStatusHistoryMapper sosStatusHistoryMapper;
    @Mock MechanicMapper mechanicMapper;
    @Mock GoogleMapsService googleMapsService;
    @Mock MedusaService medusaService;
    @Mock SosEventProducer sosEventProducer;
    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock TaskScheduler taskScheduler;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    @InjectMocks SosServiceImpl sosService;

    private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);

    private final UUID userId          = UUID.randomUUID();
    private final UUID sosId           = UUID.randomUUID();
    private final UUID vehicleId       = UUID.randomUUID();
    private final UUID mechanicId      = UUID.randomUUID();
    private final UUID historyId       = UUID.randomUUID();
    private final String serviceGroupId = "sg_01ABC123";
    private final String resolvedKey    = "flat_tire";

    private User user;
    private Vehicle vehicle;
    private Mechanic mechanic;
    private SosRequest pendingSos;
    private SosRequestResponse sosResponse;
    private SosStatusHistoryResponse historyResponse;

    @BeforeEach
    void setUp() {
        user = User.builder().userId(userId).name("Alice").build();
        vehicle = Vehicle.builder().id(vehicleId).carBrand("Toyota").carModel("Camry").build();
        mechanic = Mechanic.builder().mechanicId(mechanicId).fullName("Bob").phoneNumber("081").email("bob@fix.com").build();
        pendingSos = SosRequest.builder()
                .sosId(sosId).problemType(resolvedKey).status(SosStatus.PENDING)
                .user(user).vehicle(vehicle).build();
        sosResponse = new SosRequestResponse(sosId, resolvedKey, null, null, SosStatus.PENDING,
                13.7, 100.5, null, null, null, null, 0, null, null, null);
        historyResponse = new SosStatusHistoryResponse(historyId, SosStatus.ACCEPTED,
                SosStatus.ON_THE_WAY, null, null, null, ApprovedStatus.PENDING_APPROVAL, null, null);
    }

    // Stubs the guards + Medusa resolution that run at the top of create() before repo calls.
    private void stubCreateGuards() {
        when(sosRequestRepository.existsByUserUserIdAndStatusIn(eq(userId), any())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(medusaService.resolveServiceGroupKey(serviceGroupId)).thenReturn(resolvedKey);
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_validRequest_buildsPointAndSaves() {
        stubCreateGuards();
        CreateSosRequest request = new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(vehicle));
        when(sosRequestRepository.save(any(SosRequest.class))).thenReturn(pendingSos);
        when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(null);
        when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

        SosRequestResponse result = sosService.create(request);

        ArgumentCaptor<SosRequest> captor = ArgumentCaptor.forClass(SosRequest.class);
        verify(sosRequestRepository, atLeastOnce()).save(captor.capture());
        SosRequest saved = captor.getValue();
        assertThat(saved.getLocation()).isNotNull();
        assertThat(saved.getLocation().getY()).isEqualTo(13.7563);
        assertThat(saved.getLocation().getX()).isEqualTo(100.5018);
        assertThat(result).isEqualTo(sosResponse);
        verify(sosEventProducer).publishSosCreated(any(SosCreatedEvent.class));
    }

    @Test
    void create_storesProblemTypeKeyFromMedusa() {
        stubCreateGuards();
        CreateSosRequest request = new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(vehicle));
        when(sosRequestRepository.save(any(SosRequest.class))).thenReturn(pendingSos);
        when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(null);
        when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

        sosService.create(request);

        ArgumentCaptor<SosRequest> captor = ArgumentCaptor.forClass(SosRequest.class);
        verify(sosRequestRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getProblemType()).isEqualTo(resolvedKey);
    }

    @Test
    void create_publishesSosCreatedEventWithResolvedKey() {
        stubCreateGuards();
        CreateSosRequest request = new CreateSosRequest(userId, serviceGroupId, "desc", 13.7563, 100.5018, vehicleId, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(vehicle));
        when(sosRequestRepository.save(any(SosRequest.class))).thenAnswer(inv -> {
            SosRequest arg = inv.getArgument(0);
            arg.setSosId(sosId);
            return arg;
        });
        when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(null);
        when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

        sosService.create(request);

        ArgumentCaptor<SosCreatedEvent> captor = ArgumentCaptor.forClass(SosCreatedEvent.class);
        verify(sosEventProducer).publishSosCreated(captor.capture());
        SosCreatedEvent event = captor.getValue();
        assertThat(event.sosId()).isEqualTo(sosId);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.vehicleId()).isEqualTo(vehicleId);
        assertThat(event.problemType()).isEqualTo(resolvedKey);
        assertThat(event.latitude()).isEqualTo(13.7563);
        assertThat(event.longitude()).isEqualTo(100.5018);
    }

    @Test
    void create_invalidServiceGroupId_throwsEntityNotFoundException() {
        when(sosRequestRepository.existsByUserUserIdAndStatusIn(eq(userId), any())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(medusaService.resolveServiceGroupKey(serviceGroupId))
                .thenThrow(new EntityNotFoundException("Service group not found: " + serviceGroupId));
        CreateSosRequest request = new CreateSosRequest(userId, serviceGroupId, null, 13.7, 100.5, vehicleId, null);

        assertThatThrownBy(() -> sosService.create(request))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(serviceGroupId);

        verify(userRepository, never()).findById(any());
    }

    @Test
    void create_userNotFound_throwsEntityNotFoundException() {
        stubCreateGuards();
        CreateSosRequest request = new CreateSosRequest(userId, serviceGroupId, null, 13.7, 100.5, vehicleId, null);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sosService.create(request))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(userId.toString());
    }

    @Test
    void create_mapsReturnsAddress_setsLocationAddressAndSavesTwice() {
        stubCreateGuards();
        CreateSosRequest request = new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(vehicle));
        when(sosRequestRepository.save(any(SosRequest.class))).thenReturn(pendingSos);
        when(googleMapsService.reverseGeocode(13.7563, 100.5018)).thenReturn("Sukhumvit Rd, Bangkok");
        when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

        sosService.create(request);

        ArgumentCaptor<SosRequest> captor = ArgumentCaptor.forClass(SosRequest.class);
        verify(sosRequestRepository, times(2)).save(captor.capture());
        assertThat(captor.getValue().getLocationAddress()).isEqualTo("Sukhumvit Rd, Bangkok");
    }

    @Test
    void create_mapsReturnsNull_sosStillSavedWithoutAddress() {
        stubCreateGuards();
        CreateSosRequest request = new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.of(vehicle));
        when(sosRequestRepository.save(any(SosRequest.class))).thenReturn(pendingSos);
        when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(null);
        when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

        sosService.create(request);

        verify(sosRequestRepository, times(1)).save(any(SosRequest.class));
    }

    @Test
    void create_vehicleNotFound_throwsEntityNotFoundException() {
        stubCreateGuards();
        CreateSosRequest request = new CreateSosRequest(userId, serviceGroupId, null, 13.7, 100.5, vehicleId, null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(vehicleRepository.findById(vehicleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sosService.create(request))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(vehicleId.toString());
    }

    // ── create guards ─────────────────────────────────────────────────────────

    @Test
    void create_userHasActiveSos_throwsIllegalStateException() {
        when(sosRequestRepository.existsByUserUserIdAndStatusIn(eq(userId), any())).thenReturn(true);
        CreateSosRequest request = new CreateSosRequest(userId, serviceGroupId, null, 13.7, 100.5, vehicleId, null);

        assertThatThrownBy(() -> sosService.create(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active SOS request");

        verify(medusaService, never()).resolveServiceGroupKey(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void create_withinCooldown_throwsTooManyRequestsException() {
        when(sosRequestRepository.existsByUserUserIdAndStatusIn(eq(userId), any())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        CreateSosRequest request = new CreateSosRequest(userId, serviceGroupId, null, 13.7, 100.5, vehicleId, null);

        assertThatThrownBy(() -> sosService.create(request))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("60 seconds");

        verify(medusaService, never()).resolveServiceGroupKey(any());
        verify(userRepository, never()).findById(any());
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

    @Test
    void getById_withAcceptedBid_resolvesMechanicFromBid() {
        BidSos acceptedBid = BidSos.builder().bidId(UUID.randomUUID()).status(BidStatus.ACCEPTED)
                .sosRequest(pendingSos).mechanic(mechanic).build();
        MechanicResponse mechanicResponse = new MechanicResponse(
                mechanicId, "Bob", "081", "bob@fix.com",
                null, null, null, null, null, null, null, true, null, null, null);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
        when(sosMapper.toResponse(pendingSos)).thenReturn(sosResponse);
        when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
                .thenReturn(Optional.of(acceptedBid));
        when(mechanicMapper.toResponse(mechanic)).thenReturn(mechanicResponse);

        SosRequestResponse result = sosService.getById(sosId);

        assertThat(result.mechanic()).isEqualTo(mechanicResponse);
    }

    // ── getByUser / getByStatus ───────────────────────────────────────────────

    @Test
    void getByUser_delegatesToRepositoryAndMapsResult() {
        Pageable pageable = PageRequest.of(0, 20);
        when(sosRequestRepository.findByUserUserId(userId, pageable))
                .thenReturn(new PageImpl<>(List.of(pendingSos)));
        when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
                .thenReturn(Optional.empty());
        when(sosMapper.toResponse(pendingSos)).thenReturn(sosResponse);

        Page<SosRequestResponse> result = sosService.getByUser(userId, pageable);

        assertThat(result.getContent()).containsExactly(sosResponse);
    }

    @Test
    void getByStatus_delegatesToRepositoryAndMapsResult() {
        Pageable pageable = PageRequest.of(0, 20);
        when(sosRequestRepository.findByStatus(SosStatus.PENDING, pageable))
                .thenReturn(new PageImpl<>(List.of(pendingSos)));
        when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
                .thenReturn(Optional.empty());
        when(sosMapper.toResponse(pendingSos)).thenReturn(sosResponse);

        Page<SosRequestResponse> result = sosService.getByStatus(SosStatus.PENDING, pageable);

        assertThat(result.getContent()).containsExactly(sosResponse);
    }

    // ── proposeStatusChange ───────────────────────────────────────────────────

    @Test
    void proposeStatusChange_validTransition_savesHistoryAndReturnsResponse() {
        SosRequest arrivedSos = SosRequest.builder().sosId(sosId)
                .problemType(resolvedKey).status(SosStatus.ARRIVED).user(user).vehicle(vehicle).build();
        ProposeStatusChangeRequest request = new ProposeStatusChangeRequest(mechanicId, SosStatus.COMPLETED);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(arrivedSos));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(sosStatusHistoryRepository.save(any(SosStatusHistory.class)))
                .thenAnswer(inv -> {
                    SosStatusHistory h = inv.getArgument(0);
                    h.setId(historyId);
                    return h;
                });
        when(sosStatusHistoryMapper.toResponse(any(SosStatusHistory.class))).thenReturn(historyResponse);

        SosStatusHistoryResponse result = sosService.proposeStatusChange(sosId, request);

        ArgumentCaptor<SosStatusHistory> captor = ArgumentCaptor.forClass(SosStatusHistory.class);
        verify(sosStatusHistoryRepository).save(captor.capture());
        SosStatusHistory saved = captor.getValue();
        assertThat(saved.getFromStatus()).isEqualTo(SosStatus.ARRIVED);
        assertThat(saved.getToStatus()).isEqualTo(SosStatus.COMPLETED);
        assertThat(saved.getApprovedStatus()).isEqualTo(ApprovedStatus.PENDING_APPROVAL);
        assertThat(result).isEqualTo(historyResponse);
    }

    @Test
    void proposeStatusChange_invalidTransition_throwsIllegalStateException() {
        ProposeStatusChangeRequest request = new ProposeStatusChangeRequest(mechanicId, SosStatus.COMPLETED);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));

        assertThatThrownBy(() -> sosService.proposeStatusChange(sosId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void proposeStatusChange_terminalStatus_throwsIllegalStateException() {
        ProposeStatusChangeRequest request = new ProposeStatusChangeRequest(mechanicId, SosStatus.CANCELLED);

        assertThatThrownBy(() -> sosService.proposeStatusChange(sosId, request))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void proposeStatusChange_mechanicNotFound_throwsEntityNotFoundException() {
        SosRequest arrivedSos = SosRequest.builder().sosId(sosId)
                .problemType(resolvedKey).status(SosStatus.ARRIVED).user(user).vehicle(vehicle).build();
        ProposeStatusChangeRequest request = new ProposeStatusChangeRequest(mechanicId, SosStatus.COMPLETED);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(arrivedSos));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sosService.proposeStatusChange(sosId, request))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── approveStatusChange ───────────────────────────────────────────────────

    @Test
    void approveStatusChange_accepted_updatesSosStatus() {
        SosRequest acceptedSos = SosRequest.builder().sosId(sosId)
                .problemType(resolvedKey).status(SosStatus.ACCEPTED).user(user).vehicle(vehicle).build();
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

        ArgumentCaptor<SosStatusUpdatedEvent> captor = ArgumentCaptor.forClass(SosStatusUpdatedEvent.class);
        verify(sosEventProducer).publishStatusUpdated(captor.capture());
        SosStatusUpdatedEvent event = captor.getValue();
        assertThat(event.sosId()).isEqualTo(sosId);
        assertThat(event.mechanicId()).isEqualTo(mechanicId);
        assertThat(event.fromStatus()).isEqualTo(SosStatus.ACCEPTED);
        assertThat(event.toStatus()).isEqualTo(SosStatus.ON_THE_WAY);
        verify(messagingTemplate).convertAndSendToUser(mechanicId.toString(), "/queue/sos-updates", event);
        verify(sosEventProducer).publishNotification(any(SosNotificationEvent.class));
    }

    @Test
    void approveStatusChange_completed_publishesNotificationToUserAndMechanic() {
        SosRequest arrivedSos = SosRequest.builder().sosId(sosId)
                .problemType(resolvedKey).status(SosStatus.ARRIVED).user(user).vehicle(vehicle).build();
        SosStatusHistory history = SosStatusHistory.builder()
                .id(historyId).fromStatus(SosStatus.ARRIVED).toStatus(SosStatus.COMPLETED)
                .proposedBy(mechanic).sosRequest(arrivedSos)
                .approvedStatus(ApprovedStatus.PENDING_APPROVAL).build();
        ApproveStatusChangeRequest request = new ApproveStatusChangeRequest(userId, historyId, ApprovedStatus.ACCEPTED);

        when(sosStatusHistoryRepository.findById(historyId)).thenReturn(Optional.of(history));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(sosStatusHistoryRepository.save(history)).thenReturn(history);
        when(sosRequestRepository.save(arrivedSos)).thenReturn(arrivedSos);
        when(sosMapper.toResponse(arrivedSos)).thenReturn(sosResponse);

        sosService.approveStatusChange(request);

        verify(sosEventProducer, times(2)).publishNotification(any(SosNotificationEvent.class));
    }

    @Test
    void approveStatusChange_rejected_doesNotUpdateSosStatus() {
        SosRequest acceptedSos = SosRequest.builder().sosId(sosId)
                .problemType(resolvedKey).status(SosStatus.ACCEPTED).user(user).vehicle(vehicle).build();
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
        verify(sosEventProducer, never()).publishStatusUpdated(any());
        verify(messagingTemplate).convertAndSendToUser(
                eq(mechanicId.toString()), eq("/queue/sos-updates"), any());
        verify(sosEventProducer).publishNotification(any(SosNotificationEvent.class));
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

        ArgumentCaptor<SosStatusUpdatedEvent> captor = ArgumentCaptor.forClass(SosStatusUpdatedEvent.class);
        verify(sosEventProducer).publishStatusUpdated(captor.capture());
        assertThat(captor.getValue().toStatus()).isEqualTo(SosStatus.CANCELLED);
        assertThat(captor.getValue().fromStatus()).isEqualTo(SosStatus.PENDING);
        verify(sosEventProducer).publishNotification(any(SosNotificationEvent.class));
    }

    @Test
    void cancel_completedStatus_throwsIllegalStateException() {
        SosRequest completedSos = SosRequest.builder().sosId(sosId)
                .problemType(resolvedKey).status(SosStatus.COMPLETED).user(user).vehicle(vehicle).build();
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(completedSos));

        assertThatThrownBy(() -> sosService.cancel(sosId, userId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancel_cancelledStatus_throwsIllegalStateException() {
        SosRequest cancelledSos = SosRequest.builder().sosId(sosId)
                .problemType(resolvedKey).status(SosStatus.CANCELLED).user(user).vehicle(vehicle).build();
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(cancelledSos));

        assertThatThrownBy(() -> sosService.cancel(sosId, userId))
                .isInstanceOf(IllegalStateException.class);
    }
}
