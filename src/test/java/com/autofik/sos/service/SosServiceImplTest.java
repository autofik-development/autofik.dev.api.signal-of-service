package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.autofik.sos.domain.entity.*;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.event.SosCreatedEvent;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.dto.event.SosStatusUpdatedEvent;
import com.autofik.sos.dto.external.DistanceInfo;
import com.autofik.sos.dto.request.ApproveStatusChangeRequest;
import com.autofik.sos.dto.request.CreateSosRequest;
import com.autofik.sos.dto.request.ProposeStatusChangeRequest;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.dto.response.SosStatusHistoryResponse;
import com.autofik.sos.exception.TooManyRequestsException;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.MechanicMapper;
import com.autofik.sos.mapper.SosMapper;
import com.autofik.sos.mapper.SosStatusHistoryMapper;
import com.autofik.sos.repository.*;
import com.autofik.sos.service.impl.SosServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import java.time.Duration;
import java.time.LocalDateTime;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SosServiceImplTest {

  @Mock SosRequestRepository sosRequestRepository;
  @Mock com.autofik.sos.service.impl.SsoProjection ssoProjection;
  @Mock UserRepository userRepository;
  @Mock VehicleRepository vehicleRepository;
  @Mock MechanicRepository mechanicRepository;
  @Mock BidSosRepository bidSosRepository;
  @Mock SosStatusHistoryRepository sosStatusHistoryRepository;
  @Mock SosMapper sosMapper;
  @Mock SosStatusHistoryMapper sosStatusHistoryMapper;
  @Mock MechanicMapper mechanicMapper;
  @Mock GoogleMapsService googleMapsService;
  @Mock RouteEtaService routeEtaService;
  @Mock MedusaService medusaService;
  @Mock SosEventProducer sosEventProducer;
  @Mock SimpMessagingTemplate messagingTemplate;
  @Mock TaskScheduler taskScheduler;
  @Mock StringRedisTemplate redisTemplate;
  @Mock ValueOperations<String, String> valueOps;
  @Mock org.springframework.beans.factory.ObjectProvider<SosMediaService> sosMediaService;
  @Mock SosMediaService sosMediaServiceBean;

  @InjectMocks SosServiceImpl sosService;

  private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);

  private final UUID userId = UUID.randomUUID();
  private final UUID sosId = UUID.randomUUID();
  private final UUID vehicleId = UUID.randomUUID();
  private final UUID mechanicId = UUID.randomUUID();
  private final UUID historyId = UUID.randomUUID();
  private final String serviceGroupId = "sg_01ABC123";
  private final String resolvedKey = "flat_tire";

  private User user;
  private Vehicle vehicle;
  private Mechanic mechanic;
  private SosRequest pendingSos;
  private SosRequestResponse sosResponse;
  private SosStatusHistoryResponse historyResponse;

  @BeforeEach
  void setUp() {
    user = User.builder().userId(userId).name("Alice").build();
    vehicle =
        Vehicle.builder().id(vehicleId).carBrand("Toyota").carModel("Camry").user(user).build();
    mechanic =
        Mechanic.builder()
            .mechanicId(mechanicId)
            .fullName("Bob")
            .phoneNumber("081")
            .email("bob@fix.com")
            // Parked at the breakdown by default; the proximity gate reads this, not the request.
            .currentLocation(GEO.createPoint(new Coordinate(104.9282, 11.5573)))
            .build();
    // @Value is not applied by @InjectMocks, so the gate would otherwise run against 0 metres.
    ReflectionTestUtils.setField(sosService, "arrivalMaxDistanceMeters", 50);
    ReflectionTestUtils.setField(sosService, "autoExpandStepKm", 5);
    ReflectionTestUtils.setField(sosService, "autoExpandMaxKm", 35);
    // Broadcast payload carries photo URLs; the mocked bean returns an empty list.
    lenient().when(sosMediaService.getObject()).thenReturn(sosMediaServiceBean);
    pendingSos =
        SosRequest.builder()
            .sosId(sosId)
            .problemType(resolvedKey)
            .status(SosStatus.PENDING)
            .user(user)
            .vehicle(vehicle)
            .build();
    sosResponse =
        new SosRequestResponse(
            sosId,
            resolvedKey,
            null,
            null,
            SosStatus.PENDING,
            13.7,
            100.5,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            null);
    historyResponse =
        new SosStatusHistoryResponse(
            historyId,
            SosStatus.ACCEPTED,
            SosStatus.ON_THE_WAY,
            null,
            null,
            null,
            ApprovedStatus.PENDING_APPROVAL,
            null,
            null);
  }

  // Stubs the guards + Medusa resolution that run at the top of create() before repo calls.
  private void stubCreateGuards() {
    when(sosRequestRepository.existsByUserUserIdAndStatusIn(eq(userId), any())).thenReturn(false);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    when(medusaService.resolveServiceGroupKey(serviceGroupId)).thenReturn(resolvedKey);
  }

  // Mimics @UuidGenerator assigning the id synchronously on save, since create() reads
  // sos.getSosId() off the same in-memory instance afterward (e.g. for the widened-radius
  // notification) rather than off save()'s return value.
  private void stubSaveAssignsSosId() {
    when(sosRequestRepository.save(any(SosRequest.class)))
        .thenAnswer(
            inv -> {
              SosRequest arg = inv.getArgument(0);
              if (arg.getSosId() == null) {
                arg.setSosId(sosId);
              }
              return arg;
            });
  }

  // ── updateStatus: cached ETA invalidation ─────────────────────────────────

  private SosRequest sosAt(SosStatus status) {
    return SosRequest.builder()
        .sosId(sosId)
        .problemType(resolvedKey)
        .status(status)
        .location(GEO.createPoint(new Coordinate(104.9282, 11.5573)))
        .user(user)
        .vehicle(vehicle)
        .build();
  }

  // The mechanic advancing a job is the one whose bid was accepted; every updateStatus path
  // has to pass that gate before reaching the behaviour under test.
  private void stubAssignedMechanic(SosRequest sos) {
    when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(
            Optional.of(
                BidSos.builder()
                    .bidId(UUID.randomUUID())
                    .status(BidStatus.ACCEPTED)
                    .sosRequest(sos)
                    .mechanic(mechanic)
                    .build()));
  }

  private void stubUpdateStatus(SosRequest sos) {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(sosMapper.toResponse(sos)).thenReturn(sosResponse);
    // updateStatus is reserved for the mechanic whose bid was accepted, so the fixture has to
    // be that mechanic — the same lookup also resolves the mechanic on the response.
    when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(
            Optional.of(
                BidSos.builder()
                    .bidId(UUID.randomUUID())
                    .status(BidStatus.ACCEPTED)
                    .sosRequest(sos)
                    .mechanic(mechanic)
                    .build()));
  }

  // The cached route outlives its 30s refresh window, so without an eager clear the customer
  // keeps receiving an interpolated countdown after the mechanic has already pulled up.
  @Test
  void updateStatus_arrived_clearsCachedEta() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    stubUpdateStatus(sos);
    when(googleMapsService.getDistanceInfo(any(), any()))
        .thenReturn(new DistanceInfo(30, 20, "30 m", "1 min"));

    sosService.updateStatus(sosId, mechanicId, SosStatus.ARRIVED);

    verify(routeEtaService).clear(sosId);
  }

  // ON_THE_WAY is still en route — clearing here would throw away a paid-for route mid-drive,
  // and the proximity gate must not apply: the mechanic is only just setting off.
  @Test
  void updateStatus_onTheWay_keepsCachedEtaAndSkipsProximityCheck() {
    SosRequest sos = sosAt(SosStatus.ACCEPTED);
    stubUpdateStatus(sos);

    sosService.updateStatus(sosId, mechanicId, SosStatus.ON_THE_WAY);

    verify(routeEtaService, never()).clear(any());
    verify(googleMapsService, never()).getDistanceInfo(any(), any());
  }

  // ── updateStatus: arrival proximity gate ──────────────────────────────────

  @Test
  void updateStatus_arrived_withinLimit_allowed() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    stubUpdateStatus(sos);
    when(googleMapsService.getDistanceInfo(any(), any()))
        .thenReturn(new DistanceInfo(48, 30, "48 m", "1 min"));

    sosService.updateStatus(sosId, mechanicId, SosStatus.ARRIVED);

    assertThat(sos.getStatus()).isEqualTo(SosStatus.ARRIVED);
  }

  // The whole point of the gate: a mechanic who is not there cannot claim to be.
  @Test
  void updateStatus_arrived_beyondLimit_rejectedAndStatusUnchanged() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    stubAssignedMechanic(sos);
    when(googleMapsService.getDistanceInfo(any(), any()))
        .thenReturn(new DistanceInfo(4200, 600, "4.2 km", "10 min"));

    assertThatThrownBy(() -> sosService.updateStatus(sosId, mechanicId, SosStatus.ARRIVED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("4200m");

    assertThat(sos.getStatus()).isEqualTo(SosStatus.ON_THE_WAY);
    verify(sosRequestRepository, never()).save(any());
    verify(routeEtaService, never()).clear(any());
  }

  // A mechanic who never shares location must not be able to bypass the gate by staying silent.
  @Test
  void updateStatus_arrived_noMechanicLocation_rejected() {
    mechanic.setCurrentLocation(null);
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    stubAssignedMechanic(sos);

    assertThatThrownBy(() -> sosService.updateStatus(sosId, mechanicId, SosStatus.ARRIVED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no location on record");

    verify(googleMapsService, never()).getDistanceInfo(any(), any());
  }

  // A Maps outage must not halt every arrival in the country — fall back to straight-line,
  // which never exceeds road distance and so errs toward letting a present mechanic proceed.
  @Test
  void updateStatus_arrived_mapsUnavailable_fallsBackToStraightLine() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    stubUpdateStatus(sos);
    when(googleMapsService.getDistanceInfo(any(), any())).thenReturn(null);

    sosService.updateStatus(sosId, mechanicId, SosStatus.ARRIVED);

    assertThat(sos.getStatus()).isEqualTo(SosStatus.ARRIVED);
  }

  @Test
  void updateStatus_arrived_mapsUnavailable_stillRejectsWhenFarAway() {
    mechanic.setCurrentLocation(GEO.createPoint(new Coordinate(103.8600, 13.3600))); // Siem Reap
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    stubAssignedMechanic(sos);
    when(googleMapsService.getDistanceInfo(any(), any())).thenReturn(null);

    assertThatThrownBy(() -> sosService.updateStatus(sosId, mechanicId, SosStatus.ARRIVED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exceeds the 50m limit");
  }

  // ── create ────────────────────────────────────────────────────────────────

  @Test
  void create_validRequest_buildsPointAndSaves() {
    stubCreateGuards();
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, null);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    stubSaveAssignsSosId();
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
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, null);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    stubSaveAssignsSosId();
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
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, "desc", 13.7563, 100.5018, vehicleId, null);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    when(sosRequestRepository.save(any(SosRequest.class)))
        .thenAnswer(
            inv -> {
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
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7, 100.5, vehicleId, null);

    assertThatThrownBy(() -> sosService.create(request))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining(serviceGroupId);

    verify(userRepository, never()).findById(any());
  }

  @Test
  // An unverified or unknown driver never gets a local row — SSO is the gate, not this service.
  void create_userNotVerified_isRejected() {
    stubCreateGuards();
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7, 100.5, vehicleId, null);
    when(ssoProjection.user(userId))
        .thenThrow(new org.springframework.security.access.AccessDeniedException("not verified"));

    assertThatThrownBy(() -> sosService.create(request))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

    verify(sosRequestRepository, never()).save(any());
  }

  @Test
  void create_mapsReturnsAddress_setsLocationAddressAndSavesTwice() {
    stubCreateGuards();
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, null);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    stubSaveAssignsSosId();
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
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, null);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    stubSaveAssignsSosId();
    when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(null);
    when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

    sosService.create(request);

    verify(sosRequestRepository, times(1)).save(any(SosRequest.class));
  }

  @Test
  // SSO scopes the vehicle read to its owner, so "not yours" and "does not exist" arrive
  // identically — and neither may produce an SOS against a stranger's car.
  void create_vehicleNotTheCallers_isRejected() {
    stubCreateGuards();
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7, 100.5, vehicleId, null);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user))
        .thenThrow(new org.springframework.security.access.AccessDeniedException("SSO denied"));

    assertThatThrownBy(() -> sosService.create(request))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

    verify(sosRequestRepository, never()).save(any());
  }

  // ── create guards ─────────────────────────────────────────────────────────

  @Test
  void create_userHasActiveSos_throwsIllegalStateException() {
    when(sosRequestRepository.existsByUserUserIdAndStatusIn(eq(userId), any())).thenReturn(true);
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7, 100.5, vehicleId, null);

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
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7, 100.5, vehicleId, null);

    assertThatThrownBy(() -> sosService.create(request))
        .isInstanceOf(TooManyRequestsException.class)
        .hasMessageContaining("60 seconds");

    verify(medusaService, never()).resolveServiceGroupKey(any());
    verify(userRepository, never()).findById(any());
  }

  // ── create: starting-radius escalation (item 16a, immediate variant) ───────

  @Test
  void create_mechanicsFoundAtRequestedRadius_doesNotEscalate() {
    stubCreateGuards();
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, 15);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any())).thenReturn(List.of(mechanic));
    stubSaveAssignsSosId();
    when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(null);
    when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

    sosService.create(request);

    ArgumentCaptor<SosRequest> captor = ArgumentCaptor.forClass(SosRequest.class);
    verify(sosRequestRepository, atLeastOnce()).save(captor.capture());
    assertThat(captor.getValue().getBroadcastRadiusKm()).isEqualTo(15);
    verify(sosEventProducer, never())
        .publishNotification(argThat(n -> n.type().equals("SOS_AUTO_REBROADCAST")));
  }

  @Test
  void create_zeroMechanicsAtRequestedRadius_escalatesOnce() {
    stubCreateGuards();
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, 15);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    // 1st call (resolving @15km): none. 2nd call (resolving @20km): found, stop escalating.
    // 3rd+ calls: fanOutToNearbyMechanics's own independent lookup.
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any()))
        .thenReturn(List.of(), List.of(mechanic), List.of(mechanic));
    stubSaveAssignsSosId();
    when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(null);
    when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

    sosService.create(request);

    ArgumentCaptor<SosRequest> captor = ArgumentCaptor.forClass(SosRequest.class);
    verify(sosRequestRepository, atLeastOnce()).save(captor.capture());
    assertThat(captor.getValue().getBroadcastRadiusKm()).isEqualTo(20); // 15 + step(5)
    verify(sosEventProducer)
        .publishNotification(argThat(n -> n.type().equals("SOS_AUTO_REBROADCAST")));
  }

  @Test
  void create_noMechanicsFoundAnywhere_capsAtAutoExpandMax() {
    stubCreateGuards();
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, null);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    // findByH3CellsAndSkillTag left unstubbed: Mockito's default for an unstubbed List-returning
    // method is an empty list, i.e. "no mechanics anywhere," on every call.
    stubSaveAssignsSosId();
    when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(null);
    when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

    sosService.create(request);

    ArgumentCaptor<SosRequest> captor = ArgumentCaptor.forClass(SosRequest.class);
    verify(sosRequestRepository, atLeastOnce()).save(captor.capture());
    assertThat(captor.getValue().getBroadcastRadiusKm())
        .isEqualTo(35); // DEFAULT(15) escalated to autoExpandMaxKm (set to 35 in setUp)
  }

  // ── getById ───────────────────────────────────────────────────────────────

  @Test
  void getById_found_returnsResponse() {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
    when(sosMapper.toResponse(pendingSos)).thenReturn(sosResponse);

    assertThat(sosService.getById(sosId, userId)).isEqualTo(sosResponse);
  }

  @Test
  void getById_notFound_throwsEntityNotFoundException() {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> sosService.getById(sosId, userId))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void getById_withAcceptedBid_resolvesMechanicFromBid() {
    BidSos acceptedBid =
        BidSos.builder()
            .bidId(UUID.randomUUID())
            .status(BidStatus.ACCEPTED)
            .sosRequest(pendingSos)
            .mechanic(mechanic)
            .build();
    MechanicResponse mechanicResponse =
        new MechanicResponse(
            mechanicId,
            "Bob",
            "081",
            "bob@fix.com",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            null,
            null,
            null);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
    when(sosMapper.toResponse(pendingSos)).thenReturn(sosResponse);
    when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(Optional.of(acceptedBid));
    when(mechanicMapper.toResponse(mechanic)).thenReturn(mechanicResponse);

    SosRequestResponse result = sosService.getById(sosId, userId);

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
  void getByUserAndStatus_delegatesToRepositoryAndMapsResult() {
    Pageable pageable = PageRequest.of(0, 20);
    when(sosRequestRepository.findByUserUserIdAndStatus(userId, SosStatus.PENDING, pageable))
        .thenReturn(new PageImpl<>(List.of(pendingSos)));
    when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(Optional.empty());
    when(sosMapper.toResponse(pendingSos)).thenReturn(sosResponse);

    Page<SosRequestResponse> result =
        sosService.getByUserAndStatus(userId, SosStatus.PENDING, pageable);

    assertThat(result.getContent()).containsExactly(sosResponse);
  }

  // Status listings must never escape the caller's own rows: unscoped, this endpoint returned
  // every breakdown in the system.
  @Test
  void getById_foreignSos_hidesItAsNotFound() {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
    UUID stranger = UUID.randomUUID();
    when(bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(sosId, stranger))
        .thenReturn(false);

    assertThatThrownBy(() -> sosService.getById(sosId, stranger))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void getById_eligibleMechanicWithoutBid_canViewDuringBiddingWindow() {
    pendingSos.setLocation(GEO.createPoint(new Coordinate(104.9282, 11.5573)));
    pendingSos.setBiddingExpiresAt(LocalDateTime.now().plusMinutes(1));

    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
    when(bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(sosId, mechanicId))
        .thenReturn(false);
    when(mechanicRepository.findByH3CellsAndSkillTag(anyList(), eq(resolvedKey)))
        .thenReturn(List.of(mechanic));
    when(mechanicRepository.filterByRadius(anyList(), anyDouble(), anyDouble()))
        .thenReturn(List.of(mechanic));
    when(sosMapper.toResponse(pendingSos)).thenReturn(sosResponse);
    SosRequestResponse result = sosService.getById(sosId, mechanicId);
    assertThat(result).isEqualTo(sosResponse);
  }

  @Test
  void getById_mechanicOutsideRadius_cannotView() {
    pendingSos.setLocation(GEO.createPoint(new Coordinate(104.9282, 11.5573)));
    pendingSos.setBiddingExpiresAt(LocalDateTime.now().plusMinutes(1));

    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
    when(bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(sosId, mechanicId))
        .thenReturn(false);
    when(mechanicRepository.findByH3CellsAndSkillTag(anyList(), eq(resolvedKey)))
        .thenReturn(List.of(mechanic));
    when(mechanicRepository.filterByRadius(anyList(), anyDouble(), anyDouble()))
        .thenReturn(List.of());

    assertThatThrownBy(() -> sosService.getById(sosId, mechanicId))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void getById_eligibleMechanicAfterBiddingExpires_cannotView() {
    pendingSos.setLocation(GEO.createPoint(new Coordinate(104.9282, 11.5573)));
    pendingSos.setBiddingExpiresAt(LocalDateTime.now().minusMinutes(1));

    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
    when(bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(sosId, mechanicId))
        .thenReturn(false);
    assertThatThrownBy(() -> sosService.getById(sosId, mechanicId))
        .isInstanceOf(EntityNotFoundException.class);

    verifyNoInteractions(mechanicRepository);
  }

  // ── getNearbyMechanics ───────────────────────────────────────────────────

  @Test
  void getNearbyMechanics_owner_usesTicketsCurrentRadiusAndReturnsMappedList() {
    pendingSos.setLocation(GEO.createPoint(new Coordinate(104.9282, 11.5573)));
    pendingSos.setBroadcastRadiusKm(25); // already auto-expanded past the default 15
    MechanicResponse mechanicResponse =
        new MechanicResponse(
            mechanicId,
            "Bob",
            "081",
            "bob@fix.com",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            null,
            null,
            null);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
    when(mechanicRepository.findByH3CellsAndSkillTag(anyList(), eq(resolvedKey)))
        .thenReturn(List.of(mechanic));
    when(mechanicRepository.filterByRadiusAndBroadcast(
            anyList(), anyDouble(), anyDouble(), eq(25_000.0)))
        .thenReturn(List.of(mechanic));
    when(mechanicMapper.toResponse(mechanic)).thenReturn(mechanicResponse);

    List<MechanicResponse> result = sosService.getNearbyMechanics(sosId, userId);

    assertThat(result).containsExactly(mechanicResponse);
  }

  @Test
  void getNearbyMechanics_noRadiusSetYet_fallsBackToDefaultBroadcastKm() {
    pendingSos.setLocation(GEO.createPoint(new Coordinate(104.9282, 11.5573)));
    // broadcastRadiusKm left null — a legacy ticket predating the auto-expand feature.
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
    when(mechanicRepository.findByH3CellsAndSkillTag(anyList(), eq(resolvedKey)))
        .thenReturn(List.of(mechanic));
    when(mechanicRepository.filterByRadiusAndBroadcast(
            anyList(), anyDouble(), anyDouble(), eq(15_000.0))) // DEFAULT_BROADCAST_KM
        .thenReturn(List.of(mechanic));
    when(mechanicMapper.toResponse(mechanic))
        .thenReturn(
            new MechanicResponse(
                mechanicId,
                "Bob",
                "081",
                "bob@fix.com",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                null,
                null));

    List<MechanicResponse> result = sosService.getNearbyMechanics(sosId, userId);

    assertThat(result).hasSize(1);
  }

  @Test
  void getNearbyMechanics_foreignCaller_throwsEntityNotFoundException() {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
    UUID stranger = UUID.randomUUID();
    when(bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(sosId, stranger))
        .thenReturn(false);

    assertThatThrownBy(() -> sosService.getNearbyMechanics(sosId, stranger))
        .isInstanceOf(EntityNotFoundException.class);
    verifyNoInteractions(mechanicMapper);
  }

  // ── proposeStatusChange ───────────────────────────────────────────────────

  @Test
  void proposeStatusChange_validTransition_savesHistoryAndReturnsResponse() {
    // IN_PROGRESS is now mandatory before COMPLETED — ARRIVED can no longer propose it directly.
    SosRequest inProgressSos =
        SosRequest.builder()
            .sosId(sosId)
            .problemType(resolvedKey)
            .status(SosStatus.IN_PROGRESS)
            .user(user)
            .vehicle(vehicle)
            .build();
    ProposeStatusChangeRequest request =
        new ProposeStatusChangeRequest(mechanicId, SosStatus.COMPLETED);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(inProgressSos));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    stubAssignedMechanic(inProgressSos);
    when(sosStatusHistoryRepository.save(any(SosStatusHistory.class)))
        .thenAnswer(
            inv -> {
              SosStatusHistory h = inv.getArgument(0);
              h.setId(historyId);
              return h;
            });
    when(sosStatusHistoryMapper.toResponse(any(SosStatusHistory.class)))
        .thenReturn(historyResponse);

    SosStatusHistoryResponse result = sosService.proposeStatusChange(sosId, request);

    ArgumentCaptor<SosStatusHistory> captor = ArgumentCaptor.forClass(SosStatusHistory.class);
    verify(sosStatusHistoryRepository).save(captor.capture());
    SosStatusHistory saved = captor.getValue();
    assertThat(saved.getFromStatus()).isEqualTo(SosStatus.IN_PROGRESS);
    assertThat(saved.getToStatus()).isEqualTo(SosStatus.COMPLETED);
    assertThat(saved.getApprovedStatus()).isEqualTo(ApprovedStatus.PENDING_APPROVAL);
    assertThat(result).isEqualTo(historyResponse);
  }

  // ARRIVED can no longer skip straight to COMPLETED now that IN_PROGRESS is mandatory.
  @Test
  void proposeStatusChange_fromArrived_rejectedSkippingInProgress() {
    SosRequest arrivedSos =
        SosRequest.builder()
            .sosId(sosId)
            .problemType(resolvedKey)
            .status(SosStatus.ARRIVED)
            .user(user)
            .vehicle(vehicle)
            .build();
    ProposeStatusChangeRequest request =
        new ProposeStatusChangeRequest(mechanicId, SosStatus.COMPLETED);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(arrivedSos));

    assertThatThrownBy(() -> sosService.proposeStatusChange(sosId, request))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void proposeStatusChange_invalidTransition_throwsIllegalStateException() {
    ProposeStatusChangeRequest request =
        new ProposeStatusChangeRequest(mechanicId, SosStatus.COMPLETED);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));

    assertThatThrownBy(() -> sosService.proposeStatusChange(sosId, request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PENDING");
  }

  @Test
  void proposeStatusChange_terminalStatus_throwsIllegalStateException() {
    ProposeStatusChangeRequest request =
        new ProposeStatusChangeRequest(mechanicId, SosStatus.CANCELLED);

    assertThatThrownBy(() -> sosService.proposeStatusChange(sosId, request))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void proposeStatusChange_mechanicNotFound_throwsEntityNotFoundException() {
    SosRequest inProgressSos =
        SosRequest.builder()
            .sosId(sosId)
            .problemType(resolvedKey)
            .status(SosStatus.IN_PROGRESS)
            .user(user)
            .vehicle(vehicle)
            .build();
    ProposeStatusChangeRequest request =
        new ProposeStatusChangeRequest(mechanicId, SosStatus.COMPLETED);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(inProgressSos));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> sosService.proposeStatusChange(sosId, request))
        .isInstanceOf(EntityNotFoundException.class);
  }

  // ── approveStatusChange ───────────────────────────────────────────────────

  @Test
  void approveStatusChange_accepted_updatesSosStatus() {
    SosRequest acceptedSos =
        SosRequest.builder()
            .sosId(sosId)
            .problemType(resolvedKey)
            .status(SosStatus.ACCEPTED)
            .user(user)
            .vehicle(vehicle)
            .build();
    SosStatusHistory history =
        SosStatusHistory.builder()
            .id(historyId)
            .fromStatus(SosStatus.ACCEPTED)
            .toStatus(SosStatus.ON_THE_WAY)
            .proposedBy(mechanic)
            .sosRequest(acceptedSos)
            .approvedStatus(ApprovedStatus.PENDING_APPROVAL)
            .build();
    ApproveStatusChangeRequest request =
        new ApproveStatusChangeRequest(userId, historyId, ApprovedStatus.ACCEPTED);

    when(sosStatusHistoryRepository.findById(historyId)).thenReturn(Optional.of(history));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(sosStatusHistoryRepository.save(history)).thenReturn(history);
    when(sosRequestRepository.save(acceptedSos)).thenReturn(acceptedSos);
    when(sosMapper.toResponse(acceptedSos)).thenReturn(sosResponse);

    sosService.approveStatusChange(request);

    assertThat(acceptedSos.getStatus()).isEqualTo(SosStatus.ON_THE_WAY);
    verify(sosRequestRepository).save(acceptedSos);

    ArgumentCaptor<SosStatusUpdatedEvent> captor =
        ArgumentCaptor.forClass(SosStatusUpdatedEvent.class);
    verify(sosEventProducer).publishStatusUpdated(captor.capture());
    SosStatusUpdatedEvent event = captor.getValue();
    assertThat(event.sosId()).isEqualTo(sosId);
    assertThat(event.mechanicId()).isEqualTo(mechanicId);
    assertThat(event.fromStatus()).isEqualTo(SosStatus.ACCEPTED);
    assertThat(event.toStatus()).isEqualTo(SosStatus.ON_THE_WAY);
    verify(messagingTemplate)
        .convertAndSendToUser(mechanicId.toString(), "/queue/sos-updates", event);
    verify(sosEventProducer).publishNotification(any(SosNotificationEvent.class));
  }

  @Test
  void approveStatusChange_completed_publishesNotificationToUserAndMechanicAndSetsJobCompletedAt() {
    SosRequest inProgressSos =
        SosRequest.builder()
            .sosId(sosId)
            .problemType(resolvedKey)
            .status(SosStatus.IN_PROGRESS)
            .jobStartedAt(LocalDateTime.now().minusMinutes(24))
            .user(user)
            .vehicle(vehicle)
            .build();
    SosStatusHistory history =
        SosStatusHistory.builder()
            .id(historyId)
            .fromStatus(SosStatus.IN_PROGRESS)
            .toStatus(SosStatus.COMPLETED)
            .proposedBy(mechanic)
            .sosRequest(inProgressSos)
            .approvedStatus(ApprovedStatus.PENDING_APPROVAL)
            .build();
    ApproveStatusChangeRequest request =
        new ApproveStatusChangeRequest(userId, historyId, ApprovedStatus.ACCEPTED);

    when(sosStatusHistoryRepository.findById(historyId)).thenReturn(Optional.of(history));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(sosStatusHistoryRepository.save(history)).thenReturn(history);
    when(sosRequestRepository.save(inProgressSos)).thenReturn(inProgressSos);
    when(sosMapper.toResponse(inProgressSos)).thenReturn(sosResponse);

    sosService.approveStatusChange(request);

    verify(sosEventProducer, times(2)).publishNotification(any(SosNotificationEvent.class));
    assertThat(inProgressSos.getStatus()).isEqualTo(SosStatus.COMPLETED);
    assertThat(inProgressSos.getJobCompletedAt()).isNotNull();
  }

  // ── IN_PROGRESS lifecycle ────────────────────────────────────────────────

  @Test
  void updateStatus_arrivedToInProgress_setsJobStartedAt() {
    SosRequest sos = sosAt(SosStatus.ARRIVED);
    stubUpdateStatus(sos);

    sosService.updateStatus(sosId, mechanicId, SosStatus.IN_PROGRESS);

    assertThat(sos.getStatus()).isEqualTo(SosStatus.IN_PROGRESS);
    assertThat(sos.getJobStartedAt()).isNotNull();
  }

  // Mandatory rule: ARRIVED can no longer be set directly to COMPLETED via the mechanic-set path
  // either — updateStatus already rejects any toStatus other than ON_THE_WAY/ARRIVED/IN_PROGRESS.
  @Test
  void updateStatus_completed_rejected_mustUseCompletionEndpoint() {
    assertThatThrownBy(() -> sosService.updateStatus(sosId, mechanicId, SosStatus.COMPLETED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("POST /api/v1/sos/{sosId}/completion");
  }

  @Test
  void approveStatusChange_rejected_doesNotUpdateSosStatus() {
    SosRequest acceptedSos =
        SosRequest.builder()
            .sosId(sosId)
            .problemType(resolvedKey)
            .status(SosStatus.ACCEPTED)
            .user(user)
            .vehicle(vehicle)
            .build();
    SosStatusHistory history =
        SosStatusHistory.builder()
            .id(historyId)
            .fromStatus(SosStatus.ACCEPTED)
            .toStatus(SosStatus.ON_THE_WAY)
            .proposedBy(mechanic)
            .sosRequest(acceptedSos)
            .approvedStatus(ApprovedStatus.PENDING_APPROVAL)
            .build();
    ApproveStatusChangeRequest request =
        new ApproveStatusChangeRequest(userId, historyId, ApprovedStatus.REJECTED);

    when(sosStatusHistoryRepository.findById(historyId)).thenReturn(Optional.of(history));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(sosStatusHistoryRepository.save(history)).thenReturn(history);
    when(sosMapper.toResponse(acceptedSos)).thenReturn(sosResponse);

    sosService.approveStatusChange(request);

    assertThat(acceptedSos.getStatus()).isEqualTo(SosStatus.ACCEPTED);
    verify(sosRequestRepository, never()).save(any());
    verify(sosEventProducer, never()).publishStatusUpdated(any());
    verify(messagingTemplate)
        .convertAndSendToUser(eq(mechanicId.toString()), eq("/queue/sos-updates"), any());
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

    ArgumentCaptor<SosStatusUpdatedEvent> captor =
        ArgumentCaptor.forClass(SosStatusUpdatedEvent.class);
    verify(sosEventProducer).publishStatusUpdated(captor.capture());
    assertThat(captor.getValue().toStatus()).isEqualTo(SosStatus.CANCELLED);
    assertThat(captor.getValue().fromStatus()).isEqualTo(SosStatus.PENDING);
    verify(sosEventProducer).publishNotification(any(SosNotificationEvent.class));
  }

  @Test
  void cancel_completedStatus_throwsIllegalStateException() {
    SosRequest completedSos =
        SosRequest.builder()
            .sosId(sosId)
            .problemType(resolvedKey)
            .status(SosStatus.COMPLETED)
            .user(user)
            .vehicle(vehicle)
            .build();
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(completedSos));

    assertThatThrownBy(() -> sosService.cancel(sosId, userId))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void cancel_cancelledStatus_throwsIllegalStateException() {
    SosRequest cancelledSos =
        SosRequest.builder()
            .sosId(sosId)
            .problemType(resolvedKey)
            .status(SosStatus.CANCELLED)
            .user(user)
            .vehicle(vehicle)
            .build();
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(cancelledSos));

    assertThatThrownBy(() -> sosService.cancel(sosId, userId))
        .isInstanceOf(IllegalStateException.class);
  }

  // ── doExpire: auto-expand on a true zero-bid expiry ─────────────────────────

  @Test
  void doExpire_zeroBidsBelowMaxRebroadcast_autoExpandsInsteadOfExpiring() {
    SosRequest sos = sosAt(SosStatus.PENDING);
    when(bidSosRepository.existsBySosRequestSosId(sosId)).thenReturn(false);

    ReflectionTestUtils.invokeMethod(sosService, "doExpire", sos);

    assertThat(sos.getStatus()).isEqualTo(SosStatus.PENDING);
    assertThat(sos.getBroadcastRadiusKm()).isEqualTo(20); // DEFAULT_BROADCAST_KM(15) + step(5)
    assertThat(sos.getRebroadcastCount()).isEqualTo(1);
    assertThat(sos.getBiddingExpiresAt()).isAfter(LocalDateTime.now());
    verify(sosEventProducer, never()).publishStatusUpdated(any());
    verify(sosEventProducer)
        .publishNotification(
            argThat(n -> n.type().equals("SOS_AUTO_REBROADCAST") && n.sosId().equals(sosId)));
    verify(messagingTemplate)
        .convertAndSendToUser(eq(userId.toString()), eq("/queue/sos-updates"), any());
    verify(taskScheduler).schedule(any(Runnable.class), any(java.time.Instant.class));
  }

  @Test
  void doExpire_zeroBidsAtMaxRebroadcast_expiresInsteadOfExpandingFurther() {
    SosRequest sos = sosAt(SosStatus.PENDING);
    sos.setRebroadcastCount(SosRequest.MAX_REBROADCAST);
    when(bidSosRepository.existsBySosRequestSosId(sosId)).thenReturn(false);

    ReflectionTestUtils.invokeMethod(sosService, "doExpire", sos);

    assertThat(sos.getStatus()).isEqualTo(SosStatus.EXPIRED);
    verify(sosEventProducer).publishStatusUpdated(any());
  }

  @Test
  void doExpire_hasBidsEvenIfNoneAccepted_expiresInsteadOfAutoExpanding() {
    SosRequest sos = sosAt(SosStatus.PENDING);
    when(bidSosRepository.existsBySosRequestSosId(sosId)).thenReturn(true);

    ReflectionTestUtils.invokeMethod(sosService, "doExpire", sos);

    assertThat(sos.getStatus()).isEqualTo(SosStatus.EXPIRED);
    assertThat(sos.getBroadcastRadiusKm()).isNull(); // untouched — never widened
    verify(sosEventProducer).publishStatusUpdated(any());
    verify(sosEventProducer, never())
        .publishNotification(argThat(n -> n.type().equals("SOS_AUTO_REBROADCAST")));
  }
}
