package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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
import com.autofik.sos.dto.response.SosStatusHistoryEntry;
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
import java.util.Map;
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
  @Mock SosOfferRepository sosOfferRepository;
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
  @Mock org.springframework.transaction.support.TransactionTemplate transactionTemplate;
  @Mock ValueOperations<String, String> valueOps;
  @Mock org.springframework.data.redis.core.HashOperations<String, Object, Object> hashOps;
  @Mock org.springframework.beans.factory.ObjectProvider<SosMediaService> sosMediaService;
  @Mock SosMediaService sosMediaServiceBean;

  @InjectMocks SosServiceImpl sosService;

  private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);

  private final UUID userId = UUID.randomUUID();
  private final UUID sosId = UUID.randomUUID();
  private final String vehicleId = UUID.randomUUID().toString();
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
    ReflectionTestUtils.setField(sosService, "h3Resolution", 7);
    ReflectionTestUtils.setField(sosService, "h3CenterToCenterKm", 2.114);
    ReflectionTestUtils.setField(sosService, "earthRadiusM", 6_371_000d);
    ReflectionTestUtils.setField(sosService, "defaultBroadcastKm", 15);
    ReflectionTestUtils.setField(sosService, "biddingWindowSeconds", 60);
    ReflectionTestUtils.setField(sosService, "maxRebroadcast", 3);
    ReflectionTestUtils.setField(sosService, "noShowStalledMinutes", 5);
    ReflectionTestUtils.setField(sosService, "noShowPromptResponseSeconds", 60);
    ReflectionTestUtils.setField(sosService, "locationMaxAgeMinutes", 5);
    ReflectionTestUtils.setField(sosService, "maxRoadEtaMinutes", 30);
    ReflectionTestUtils.setField(sosService, "createCooldownSeconds", 60);
    ReflectionTestUtils.setField(sosService, "completionApprovalWindowSeconds", 120L);
    ReflectionTestUtils.setField(sosService, "arrivalApprovalWindowSeconds", 60L);
    ReflectionTestUtils.setField(sosService, "customerWaitMinutes", 10);
    ReflectionTestUtils.setField(sosService, "offerMaxMechanics", 25);
    ReflectionTestUtils.setField(sosService, "offerEstimateMaxAgeSeconds", 300);
    // create() runs its writes through the template; run the callback inline.
    lenient()
        .when(transactionTemplate.execute(any()))
        .thenAnswer(
            inv ->
                ((org.springframework.transaction.support.TransactionCallback<?>)
                        inv.getArgument(0))
                    .doInTransaction(null));
    // Broadcast payload carries photo URLs; the mocked bean returns an empty list.
    lenient().when(sosMediaService.getObject()).thenReturn(sosMediaServiceBean);
    // The geo filters return ids; the entities come back through findAllWithRateById.
    lenient()
        .when(mechanicRepository.findAllWithRateById(anyList()))
        .thenAnswer(
            inv -> ((List<UUID>) inv.getArgument(0)).stream().map(knownMechanics::get).toList());
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

  // A PENDING ticket whose bidding window has run out, as the expiry step reads it under the lock.
  private SosRequest overduePending() {
    SosRequest sos = sosAt(SosStatus.PENDING);
    sos.setBiddingExpiresAt(LocalDateTime.now().minusSeconds(1));
    when(sosRequestRepository.lockStatus(sosId)).thenReturn(Optional.of("PENDING"));
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    return sos;
  }

  private void expireIfDue() {
    ReflectionTestUtils.invokeMethod(sosService, "expireIfDue", sosId);
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

  // The same gates as updateStatus, without the response mapping a proposal never reaches.
  private void stubProposeBase(SosRequest sos) {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    stubAssignedMechanic(sos);
  }

  private void stubProposeArrival(SosRequest sos) {
    stubProposeBase(sos);
    when(sosStatusHistoryRepository.save(any(SosStatusHistory.class)))
        .thenAnswer(
            inv -> {
              SosStatusHistory h = inv.getArgument(0);
              h.setId(historyId);
              return h;
            });
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

  // ── proposeArrival: the mechanic says they are there, the customer confirms ─────

  @Test
  void proposeArrival_withinLimit_asksTheCustomer_andLeavesTheTicketOnTheWay() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    stubProposeArrival(sos);
    when(googleMapsService.getDistanceInfo(any(), any()))
        .thenReturn(new DistanceInfo(48, 30, "48 m", "1 min"));

    sosService.proposeArrival(sosId, mechanicId);

    assertThat(sos.getStatus())
        .as("not ARRIVED until the customer answers")
        .isEqualTo(SosStatus.ON_THE_WAY);
    ArgumentCaptor<SosStatusHistory> saved = ArgumentCaptor.forClass(SosStatusHistory.class);
    verify(sosStatusHistoryRepository).save(saved.capture());
    assertThat(saved.getValue().getFromStatus()).isEqualTo(SosStatus.ON_THE_WAY);
    assertThat(saved.getValue().getToStatus()).isEqualTo(SosStatus.ARRIVED);
    assertThat(saved.getValue().getApprovedStatus()).isEqualTo(ApprovedStatus.PENDING_APPROVAL);
    verify(sosRequestRepository).lockStatus(sosId);
    ArgumentCaptor<Object> frame = ArgumentCaptor.forClass(Object.class);
    verify(messagingTemplate)
        .convertAndSendToUser(eq(userId.toString()), eq("/queue/sos-updates"), frame.capture());
    Map<?, ?> sent = (Map<?, ?>) frame.getValue();
    assertThat(sent.get("type")).isEqualTo("ARRIVAL_REQUESTED");
    assertThat(sent.get("historyId")).isEqualTo(historyId);
    assertThat(sent.get("approveBy")).isNotNull();
    verify(sosEventProducer)
        .publishNotification(
            argThat(n -> n.type().equals("ARRIVAL_REQUESTED") && n.recipientId().equals(userId)));
  }

  // The whole point of the gate: a mechanic who is not there cannot claim to be.
  @Test
  void proposeArrival_beyondLimit_rejected_andNothingIsAsked() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    stubAssignedMechanic(sos);
    when(googleMapsService.getDistanceInfo(any(), any()))
        .thenReturn(new DistanceInfo(4200, 600, "4.2 km", "10 min"));

    assertThatThrownBy(() -> sosService.proposeArrival(sosId, mechanicId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("4200m");

    verify(sosStatusHistoryRepository, never()).save(any());
    verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
  }

  // A mechanic who never shares location must not be able to bypass the gate by staying silent.
  @Test
  void proposeArrival_noMechanicLocation_rejected() {
    mechanic.setCurrentLocation(null);
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    stubAssignedMechanic(sos);

    assertThatThrownBy(() -> sosService.proposeArrival(sosId, mechanicId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no location on record");

    verify(googleMapsService, never()).getDistanceInfo(any(), any());
  }

  // A Maps outage must not halt every arrival in the country — fall back to straight-line,
  // which never exceeds road distance and so errs toward letting a present mechanic proceed.
  @Test
  void proposeArrival_mapsUnavailable_fallsBackToStraightLine() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    stubProposeArrival(sos);
    when(googleMapsService.getDistanceInfo(any(), any())).thenReturn(null);

    sosService.proposeArrival(sosId, mechanicId);

    verify(sosStatusHistoryRepository).save(any(SosStatusHistory.class));
  }

  @Test
  void proposeArrival_mapsUnavailable_stillRejectsWhenFarAway() {
    mechanic.setCurrentLocation(GEO.createPoint(new Coordinate(103.8600, 13.3600))); // Siem Reap
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    stubAssignedMechanic(sos);
    when(googleMapsService.getDistanceInfo(any(), any())).thenReturn(null);

    assertThatThrownBy(() -> sosService.proposeArrival(sosId, mechanicId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exceeds the 50m limit");
  }

  // A double tap or a timeout retry must not ask the customer twice.
  @Test
  void proposeArrival_whenOneIsAlreadyWaiting_returnsIt_withoutAnotherRowPushOrMapsCall() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    stubProposeBase(sos);
    SosStatusHistory waiting = waitingArrival(sos, 5);
    when(sosStatusHistoryRepository.findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
            sosId, ApprovedStatus.PENDING_APPROVAL))
        .thenReturn(Optional.of(waiting));
    when(sosStatusHistoryMapper.toResponse(waiting)).thenReturn(historyResponse);

    assertThat(sosService.proposeArrival(sosId, mechanicId)).isEqualTo(historyResponse);

    verify(sosStatusHistoryRepository, never()).save(any(SosStatusHistory.class));
    verify(sosEventProducer, never()).publishNotification(any());
    verify(googleMapsService, never()).getDistanceInfo(any(), any());
  }

  @Test
  void proposeArrival_byAMechanicWhoIsNotAssigned_isDenied() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> sosService.proposeArrival(sosId, mechanicId))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

    verify(sosStatusHistoryRepository, never()).save(any(SosStatusHistory.class));
  }

  @Test
  void proposeArrival_beforeTheMechanicIsOnTheWay_isAnInvalidTransition() {
    SosRequest sos = sosAt(SosStatus.ACCEPTED);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));

    assertThatThrownBy(() -> sosService.proposeArrival(sosId, mechanicId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Invalid status transition");
  }

  // ARRIVED has one way in now, so the customer's answer cannot be skipped.
  @Test
  void updateStatus_arrived_isRefused_pointingToTheArrivalCall() {
    assertThatThrownBy(() -> sosService.updateStatus(sosId, mechanicId, SosStatus.ARRIVED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/arrival");

    verify(sosRequestRepository, never()).save(any());
  }

  private SosStatusHistory waitingArrival(SosRequest sos, long askedSecondsAgo) {
    SosStatusHistory h =
        SosStatusHistory.builder()
            .sosRequest(sos)
            .fromStatus(SosStatus.ON_THE_WAY)
            .toStatus(SosStatus.ARRIVED)
            .proposedBy(mechanic)
            .proposedAt(LocalDateTime.now().minusSeconds(askedSecondsAgo))
            .approvedStatus(ApprovedStatus.PENDING_APPROVAL)
            .build();
    h.setId(historyId);
    return h;
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

  /**
   * The cooldown only guards a double-tap. When no ticket was created (Medusa down, group unknown),
   * the key must be released, or one failed attempt locks an emergency caller out for the window.
   */
  @Test
  void create_whenMedusaFails_releasesTheCooldown_soTheCallerCanRetryAtOnce() {
    when(sosRequestRepository.existsByUserUserIdAndStatusIn(eq(userId), any())).thenReturn(false);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    when(medusaService.resolveServiceGroupKey(serviceGroupId))
        .thenThrow(new com.autofik.sos.exception.ServiceUnavailableException("Medusa down"));
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7, 100.5, vehicleId, null);

    assertThatThrownBy(() -> sosService.create(request))
        .isInstanceOf(com.autofik.sos.exception.ServiceUnavailableException.class);

    verify(redisTemplate).delete(endsWith(userId.toString()));
  }

  @Test
  void create_whenTheTicketCannotBeSaved_releasesTheCooldown() {
    stubCreateGuards();
    when(ssoProjection.user(userId)).thenThrow(new EntityNotFoundException("User not found"));
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7, 100.5, vehicleId, null);

    assertThatThrownBy(() -> sosService.create(request))
        .isInstanceOf(EntityNotFoundException.class);

    verify(redisTemplate).delete(endsWith(userId.toString()));
  }

  @Test
  void create_whenRedisCannotReleaseTheCooldown_stillSurfacesTheRealError() {
    when(sosRequestRepository.existsByUserUserIdAndStatusIn(eq(userId), any())).thenReturn(false);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    when(medusaService.resolveServiceGroupKey(serviceGroupId))
        .thenThrow(new EntityNotFoundException("Service group not found"));
    when(redisTemplate.delete(anyString()))
        .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7, 100.5, vehicleId, null);

    assertThatThrownBy(() -> sosService.create(request))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining("Service group");
  }

  @Test
  void create_whenItSucceeds_keepsTheCooldown() {
    stubCreateGuards();
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, 15);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(mechanic));
    stubSaveAssignsSosId();
    when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(null);
    when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

    sosService.create(request);

    verify(redisTemplate, never()).delete(anyString());
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
  void create_mapsReturnsAddress_writesOnlyTheAddress() {
    stubCreateGuards();
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, null);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    stubSaveAssignsSosId();
    when(googleMapsService.reverseGeocode(13.7563, 100.5018)).thenReturn("Sukhumvit Rd, Bangkok");
    when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

    sosService.create(request);

    // One save (the insert); the address afterwards is a single-column update, so a cancel or an
    // accept during the lookup is not overwritten by the copy create still holds.
    verify(sosRequestRepository, times(1)).save(any(SosRequest.class));
    verify(sosRequestRepository)
        .updateLocationAddress(eq(sosId), eq("Sukhumvit Rd, Bangkok"), any(LocalDateTime.class));
    ArgumentCaptor<SosCreatedEvent> event = ArgumentCaptor.forClass(SosCreatedEvent.class);
    verify(sosEventProducer).publishSosCreated(event.capture());
    assertThat(event.getValue().locationAddress()).isEqualTo("Sukhumvit Rd, Bangkok");
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
    verify(sosRequestRepository, never()).updateLocationAddress(any(), any(), any());
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

  // The first check is unlocked: a rebroadcast can commit a live ticket after it. The check is
  // repeated under the customer's row lock, and a hit there writes nothing and frees the cooldown.
  @Test
  void create_anotherTicketCommittedMeanwhile_isConflictUnderTheUserLock() {
    when(sosRequestRepository.existsByUserUserIdAndStatusIn(eq(userId), any()))
        .thenReturn(false, true);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    when(medusaService.resolveServiceGroupKey(serviceGroupId)).thenReturn(resolvedKey);
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7, 100.5, vehicleId, null);

    assertThatThrownBy(() -> sosService.create(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active SOS request");

    org.mockito.InOrder order = inOrder(userRepository, sosRequestRepository);
    order.verify(userRepository).lockById(userId);
    order.verify(sosRequestRepository).existsByUserUserIdAndStatusIn(eq(userId), any());
    verify(sosRequestRepository, never()).save(any());
    verify(ssoProjection, never()).user(any());
    verify(redisTemplate).delete(endsWith(userId.toString()));
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

  @Test
  void create_redisDown_failsOpen_andStillCreatesTheTicket() {
    when(sosRequestRepository.existsByUserUserIdAndStatusIn(eq(userId), any())).thenReturn(false);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(medusaService.resolveServiceGroupKey(serviceGroupId)).thenReturn(resolvedKey);
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
        .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, 15);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(mechanic));
    stubSaveAssignsSosId();
    when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(null);
    when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

    assertThat(sosService.create(request)).isNotNull();
    verify(sosRequestRepository, atLeastOnce()).save(any(SosRequest.class));
  }

  // ── create: starting-radius escalation (item 16a, immediate variant) ───────

  @Test
  void create_mechanicsFoundAtRequestedRadius_doesNotEscalate() {
    stubCreateGuards();
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, 15);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(mechanic));
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

  // ── create: external calls stay out of the transaction ─────────────────────

  /** Medusa and Google can each take seconds; none may hold a DB connection while they run. */
  @Test
  void create_externalCalls_runOutsideTheTransaction() {
    java.util.concurrent.atomic.AtomicBoolean inTx =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    doAnswer(
            inv -> {
              inTx.set(true);
              try {
                return ((org.springframework.transaction.support.TransactionCallback<?>)
                        inv.getArgument(0))
                    .doInTransaction(null);
              } finally {
                inTx.set(false);
              }
            })
        .when(transactionTemplate)
        .execute(any());
    // Guards, minus Medusa (stubbed below so it can assert where it was called from).
    when(sosRequestRepository.existsByUserUserIdAndStatusIn(eq(userId), any())).thenReturn(false);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    when(medusaService.resolveServiceGroupKey(any()))
        .thenAnswer(
            inv -> {
              assertThat(inTx.get()).as("Medusa called inside the transaction").isFalse();
              return resolvedKey;
            });
    when(googleMapsService.reverseGeocode(anyDouble(), anyDouble()))
        .thenAnswer(
            inv -> {
              assertThat(inTx.get()).as("Google called inside the transaction").isFalse();
              return "Somewhere";
            });
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(mechanic));
    stubSaveAssignsSosId();
    when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

    sosService.create(
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, 15));
  }

  /** The ticket is committed by then, so a notification failure must not become a 500. */
  @Test
  void create_broadcastFailsAfterCommit_stillReturnsTicketAndSchedulesExpiry() {
    stubCreateGuards();
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(mechanic));
    stubSaveAssignsSosId();
    when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(null);
    doThrow(new RuntimeException("kafka down")).when(sosEventProducer).publishSosCreated(any());
    when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

    SosRequestResponse result =
        sosService.create(
            new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, 15));

    assertThat(result).isNotNull();
    verify(taskScheduler).schedule(any(Runnable.class), any(java.time.Instant.class));
  }

  // ── create: pre-bid road estimate in the job offer ─────────────────────────

  @Test
  void create_offerEstimate_onlyNearestMechanicsGetOne_inSingleGoogleCall() {
    ReflectionTestUtils.setField(sosService, "offerMaxMechanics", 1);
    stubCreateGuards();
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, 15);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    UUID nearId = UUID.randomUUID();
    Mechanic near =
        Mechanic.builder()
            .mechanicId(nearId)
            .currentLocation(GEO.createPoint(new Coordinate(100.5020, 13.7560)))
            .build();
    // `mechanic` is parked in Phnom Penh, far from the request — it should get no estimate.
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(mechanic, near));
    when(mechanicRepository.filterByRadiusAndBroadcast(
            any(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ids(mechanic, near));
    when(googleMapsService.getDistanceInfos(any(), any()))
        .thenReturn(List.of(new DistanceInfo(800, 420, "0.8 km", "7 mins")));
    stubSaveAssignsSosId();
    when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(null);
    when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

    sosService.create(request);

    verify(googleMapsService, times(1)).getDistanceInfos(argThat(o -> o.size() == 1), any());
    ArgumentCaptor<SosNotificationEvent> sent = ArgumentCaptor.forClass(SosNotificationEvent.class);
    verify(messagingTemplate)
        .convertAndSendToUser(eq(nearId.toString()), eq("/queue/sos-new-request"), sent.capture());
    assertThat(sent.getValue().estimate().remainingDistanceText()).isEqualTo("0.8 km");
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(mechanicId.toString()),
            eq("/queue/sos-new-request"),
            argThat((SosNotificationEvent e) -> e.estimate() == null));
  }

  @Test
  void create_offerEstimate_googleFails_offerStillSentWithoutEstimate() {
    stubCreateGuards();
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, 15);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(mechanic));
    when(mechanicRepository.filterByRadiusAndBroadcast(
            any(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ids(mechanic));
    when(googleMapsService.getDistanceInfos(any(), any())).thenReturn(null);
    stubSaveAssignsSosId();
    when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(null);
    when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

    sosService.create(request);

    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(mechanicId.toString()),
            eq("/queue/sos-new-request"),
            argThat((SosNotificationEvent e) -> e.estimate() == null));
  }

  @Test
  void create_offerEstimate_mechanicAlreadyOffered_reusesStoredEstimateAndSkipsGoogle() {
    stubCreateGuards();
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, 15);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(mechanic));
    when(mechanicRepository.filterByRadiusAndBroadcast(
            any(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ids(mechanic));
    // This mechanic already got an estimate for this SOS on an earlier fan-out.
    when(redisTemplate.opsForHash()).thenReturn(hashOps);
    when(hashOps.entries(anyString()))
        .thenReturn(
            Map.of(
                mechanicId.toString(),
                "800	420	0.8 km	7 mins	" + java.time.Instant.now().getEpochSecond()));
    stubSaveAssignsSosId();
    when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(null);
    when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

    sosService.create(request);

    verify(googleMapsService, never()).getDistanceInfos(any(), any());
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(mechanicId.toString()),
            eq("/queue/sos-new-request"),
            argThat(
                (SosNotificationEvent e) ->
                    e.estimate() != null && e.estimate().remainingDistanceM() == 800));
  }

  /** The mechanic has likely moved since a stale estimate was taken, so it is fetched again. */
  @Test
  void create_offerEstimate_staleStoredEstimate_isRefetched() {
    stubCreateGuards();
    CreateSosRequest request =
        new CreateSosRequest(userId, serviceGroupId, null, 13.7563, 100.5018, vehicleId, 15);
    when(ssoProjection.user(userId)).thenReturn(user);
    when(ssoProjection.vehicle(vehicleId, user)).thenReturn(vehicle);
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(mechanic));
    when(mechanicRepository.filterByRadiusAndBroadcast(
            any(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ids(mechanic));
    when(redisTemplate.opsForHash()).thenReturn(hashOps);
    long anHourAgo = java.time.Instant.now().getEpochSecond() - 3600;
    when(hashOps.entries(anyString()))
        .thenReturn(Map.of(mechanicId.toString(), "800	420	0.8 km	7 mins	" + anHourAgo));
    when(googleMapsService.getDistanceInfos(any(), any()))
        .thenReturn(List.of(new DistanceInfo(1200, 600, "1.2 km", "10 mins")));
    stubSaveAssignsSosId();
    when(googleMapsService.reverseGeocode(anyDouble(), anyDouble())).thenReturn(null);
    when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);

    sosService.create(request);

    verify(googleMapsService).getDistanceInfos(any(), any());
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(mechanicId.toString()),
            eq("/queue/sos-new-request"),
            argThat(
                (SosNotificationEvent e) ->
                    e.estimate() != null && e.estimate().remainingDistanceM() == 1200));
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
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(), ids(mechanic), ids(mechanic));
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

  // ── completion approval window: unanswered requests are approved automatically ──

  @SuppressWarnings("unchecked")
  private void runSweepTransactionsInline() {
    doAnswer(
            inv -> {
              ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>)
                      inv.getArgument(0))
                  .accept(null);
              return null;
            })
        .when(transactionTemplate)
        .executeWithoutResult(any());
  }

  private SosStatusHistory overdueCompletion(SosRequest sos) {
    SosStatusHistory h = waitingCompletion(sos);
    h.setProposedBy(mechanic);
    h.setProposedAt(LocalDateTime.now().minusMinutes(5));
    return h;
  }

  private void stubOverdue(SosStatusHistory h) {
    runSweepTransactionsInline();
    when(sosStatusHistoryRepository.findByApprovedStatusAndProposedAtBefore(
            eq(ApprovedStatus.PENDING_APPROVAL), any(LocalDateTime.class)))
        .thenReturn(List.of(h));
    when(sosStatusHistoryRepository.findById(h.getId())).thenReturn(Optional.of(h));
  }

  @Test
  void approveOverdueCompletions_completesTheJob_asASystemApproval() {
    SosRequest sos = inProgressSosOwnedByUser();
    SosStatusHistory h = overdueCompletion(sos);
    stubOverdue(h);
    when(sosStatusHistoryRepository.findApprovedStatusById(h.getId()))
        .thenReturn(Optional.of(ApprovedStatus.PENDING_APPROVAL));

    sosService.approveOverdueCompletions();

    assertThat(h.getApprovedStatus()).isEqualTo(ApprovedStatus.ACCEPTED);
    assertThat(h.getApprovedBy()).as("approved by the system, not a person").isNull();
    assertThat(h.getApproveAt()).isNotNull();
    assertThat(sos.getStatus()).isEqualTo(SosStatus.COMPLETED);
    assertThat(sos.getJobCompletedAt()).isNotNull();
    verify(sosEventProducer).publishStatusUpdated(any());
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(userId.toString()),
            eq("/queue/sos-updates"),
            eq(Map.of("type", "COMPLETION_AUTO_APPROVED", "sosId", sosId)));
  }

  @Test
  void approveOverdueCompletions_nothingOverdue_doesNothing() {
    when(sosStatusHistoryRepository.findByApprovedStatusAndProposedAtBefore(any(), any()))
        .thenReturn(List.of());

    sosService.approveOverdueCompletions();

    verify(sosRequestRepository, never()).save(any(SosRequest.class));
    verify(sosEventProducer, never()).publishStatusUpdated(any());
  }

  // The customer and the sweep can answer at the same moment; the customer, who got the lock first,
  // wins and the sweep must not apply a second decision.
  @Test
  void approveOverdueCompletions_whenTheCustomerAnsweredMeanwhile_changesNothing() {
    SosRequest sos = inProgressSosOwnedByUser();
    SosStatusHistory h = overdueCompletion(sos);
    stubOverdue(h);
    when(sosStatusHistoryRepository.findApprovedStatusById(h.getId()))
        .thenReturn(Optional.of(ApprovedStatus.REJECTED));

    sosService.approveOverdueCompletions();

    assertThat(sos.getStatus()).isEqualTo(SosStatus.IN_PROGRESS);
    verify(sosEventProducer, never()).publishStatusUpdated(any());
  }

  @Test
  void approveOverdueCompletions_whenTheJobWasCancelled_doesNotCompleteIt() {
    SosRequest sos = inProgressSosOwnedByUser();
    sos.setStatus(SosStatus.CANCELLED);
    SosStatusHistory h = overdueCompletion(sos);
    stubOverdue(h);
    when(sosStatusHistoryRepository.findApprovedStatusById(h.getId()))
        .thenReturn(Optional.of(ApprovedStatus.PENDING_APPROVAL));

    sosService.approveOverdueCompletions();

    assertThat(sos.getStatus()).isEqualTo(SosStatus.CANCELLED);
    verify(sosEventProducer, never()).publishStatusUpdated(any());
  }

  @Test
  void approveOverdueCompletions_oneFailingRequestDoesNotStopTheNext() {
    SosRequest sos = inProgressSosOwnedByUser();
    SosStatusHistory broken = overdueCompletion(sos);
    broken.setId(UUID.randomUUID());
    SosStatusHistory good = overdueCompletion(sos);
    runSweepTransactionsInline();
    when(sosStatusHistoryRepository.findByApprovedStatusAndProposedAtBefore(any(), any()))
        .thenReturn(List.of(broken, good));
    when(sosStatusHistoryRepository.findById(broken.getId()))
        .thenThrow(new IllegalStateException("db hiccup"));
    when(sosStatusHistoryRepository.findById(good.getId())).thenReturn(Optional.of(good));
    when(sosStatusHistoryRepository.findApprovedStatusById(good.getId()))
        .thenReturn(Optional.of(ApprovedStatus.PENDING_APPROVAL));

    sosService.approveOverdueCompletions();

    assertThat(sos.getStatus()).isEqualTo(SosStatus.COMPLETED);
  }

  @Test
  void getById_owner_seesWhenAWaitingCompletionIsApprovedAutomatically() {
    SosRequest sos = inProgressSosOwnedByUser();
    SosStatusHistory h = waitingCompletion(sos);
    LocalDateTime proposed = LocalDateTime.of(2026, 10, 4, 10, 0);
    h.setProposedAt(proposed);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(sosMapper.toResponse(sos)).thenReturn(sosResponse);
    when(sosStatusHistoryRepository.findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
            sosId, ApprovedStatus.PENDING_APPROVAL))
        .thenReturn(Optional.of(h));

    assertThat(sosService.getById(sosId, userId).pendingCompletionApproveBy())
        .isEqualTo(proposed.plusSeconds(120));
  }

  // ── pending completion: GET /sos/{id} and the proposal ───────────────────

  private SosRequest inProgressSosOwnedByUser() {
    return SosRequest.builder()
        .sosId(sosId)
        .problemType(resolvedKey)
        .status(SosStatus.IN_PROGRESS)
        .user(user)
        .vehicle(vehicle)
        .build();
  }

  private SosStatusHistory waitingCompletion(SosRequest sos) {
    SosStatusHistory h =
        SosStatusHistory.builder()
            .sosRequest(sos)
            .fromStatus(SosStatus.IN_PROGRESS)
            .toStatus(SosStatus.COMPLETED)
            .approvedStatus(ApprovedStatus.PENDING_APPROVAL)
            .build();
    h.setId(historyId);
    return h;
  }

  @Test
  void getById_owner_seesTheHistoryIdOfAWaitingCompletion() {
    SosRequest sos = inProgressSosOwnedByUser();
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(sosMapper.toResponse(sos)).thenReturn(sosResponse);
    when(sosStatusHistoryRepository.findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
            sosId, ApprovedStatus.PENDING_APPROVAL))
        .thenReturn(Optional.of(waitingCompletion(sos)));

    assertThat(sosService.getById(sosId, userId).pendingCompletionHistoryId()).isEqualTo(historyId);
  }

  @Test
  void getById_owner_nothingWaiting_showsNoHistoryId() {
    SosRequest sos = inProgressSosOwnedByUser();
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(sosMapper.toResponse(sos)).thenReturn(sosResponse);

    assertThat(sosService.getById(sosId, userId).pendingCompletionHistoryId()).isNull();
  }

  @Test
  void getById_aMechanic_neverSeesTheHistoryId_andTheLookupIsNotEvenRun() {
    SosRequest sos = inProgressSosOwnedByUser();
    UUID assignedMechanic = UUID.randomUUID();
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(sosMapper.toResponse(sos)).thenReturn(sosResponse);
    when(bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(sosId, assignedMechanic))
        .thenReturn(true);

    assertThat(sosService.getById(sosId, assignedMechanic).pendingCompletionHistoryId()).isNull();

    verify(sosStatusHistoryRepository, never())
        .findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(any(), any());
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
            true,
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
    when(mechanicRepository.findByH3CellsAndSkillTag(anyList(), eq(resolvedKey), any()))
        .thenReturn(ids(mechanic));
    when(mechanicRepository.filterByRadius(anyList(), anyDouble(), anyDouble()))
        .thenReturn(ids(mechanic));
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
    when(mechanicRepository.findByH3CellsAndSkillTag(anyList(), eq(resolvedKey), any()))
        .thenReturn(ids(mechanic));
    when(mechanicRepository.filterByRadius(anyList(), anyDouble(), anyDouble())).thenReturn(ids());

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
            true,
            null,
            null);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
    when(mechanicRepository.findByH3CellsAndSkillTag(anyList(), eq(resolvedKey), any()))
        .thenReturn(ids(mechanic));
    when(mechanicRepository.filterByRadiusAndBroadcast(
            anyList(), anyDouble(), anyDouble(), eq(25_000.0)))
        .thenReturn(ids(mechanic));
    when(mechanicMapper.toResponse(mechanic)).thenReturn(mechanicResponse);

    List<MechanicResponse> result = sosService.getNearbyMechanics(sosId, userId);

    assertThat(result).containsExactly(mechanicResponse);
  }

  @Test
  void getNearbyMechanics_noRadiusSetYet_fallsBackToDefaultBroadcastKm() {
    pendingSos.setLocation(GEO.createPoint(new Coordinate(104.9282, 11.5573)));
    // broadcastRadiusKm left null — a legacy ticket predating the auto-expand feature.
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
    when(mechanicRepository.findByH3CellsAndSkillTag(anyList(), eq(resolvedKey), any()))
        .thenReturn(ids(mechanic));
    when(mechanicRepository.filterByRadiusAndBroadcast(
            anyList(), anyDouble(), anyDouble(), eq(15_000.0))) // sos.broadcast.default-km
        .thenReturn(ids(mechanic));
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
                true,
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

  // Same ticket lock as a cancel and a release, so a proposal cannot be written for a dead ticket.
  @Test
  void proposeStatusChange_takesTheTicketLock() {
    SosRequest sos = inProgressSosOwnedByUser();
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    stubAssignedMechanic(sos);
    when(sosStatusHistoryRepository.save(any(SosStatusHistory.class)))
        .thenAnswer(
            inv -> {
              SosStatusHistory h = inv.getArgument(0);
              h.setId(historyId);
              return h;
            });
    when(sosStatusHistoryMapper.toResponse(any(SosStatusHistory.class)))
        .thenReturn(historyResponse);

    sosService.proposeStatusChange(
        sosId, new ProposeStatusChangeRequest(mechanicId, SosStatus.COMPLETED));

    var order = inOrder(sosRequestRepository);
    order.verify(sosRequestRepository).lockStatus(sosId);
    order.verify(sosRequestRepository).findById(sosId);
  }

  // A double tap or a timeout retry must not leave two approvable rows or push the customer twice.
  @Test
  void proposeStatusChange_whenOneIsAlreadyWaiting_returnsIt_withoutANewRowOrPush() {
    SosRequest sos = inProgressSosOwnedByUser();
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    stubAssignedMechanic(sos);
    SosStatusHistory waiting = waitingCompletion(sos);
    when(sosStatusHistoryRepository.findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
            sosId, ApprovedStatus.PENDING_APPROVAL))
        .thenReturn(Optional.of(waiting));
    when(sosStatusHistoryMapper.toResponse(waiting)).thenReturn(historyResponse);

    SosStatusHistoryResponse result =
        sosService.proposeStatusChange(
            sosId, new ProposeStatusChangeRequest(mechanicId, SosStatus.COMPLETED));

    assertThat(result).isEqualTo(historyResponse);
    verify(sosStatusHistoryRepository, never()).save(any(SosStatusHistory.class));
    verify(sosEventProducer, never()).publishNotification(any());
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
  void proposeStatusChange_terminalStatus_throwsIllegalArgumentException() {
    ProposeStatusChangeRequest request =
        new ProposeStatusChangeRequest(mechanicId, SosStatus.CANCELLED);

    assertThatThrownBy(() -> sosService.proposeStatusChange(sosId, request))
        .isInstanceOf(IllegalArgumentException.class);
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
        .isInstanceOf(IllegalArgumentException.class)
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

  private BidSos bidBy(UUID mechanicUuid, BidStatus status, SosRequest sos) {
    Mechanic bidder =
        Mechanic.builder()
            .mechanicId(mechanicUuid)
            .fullName("M")
            .phoneNumber("1")
            .email("m@x.com")
            .build();
    return BidSos.builder()
        .bidId(UUID.randomUUID())
        .status(status)
        .sosRequest(sos)
        .mechanic(bidder)
        .build();
  }

  // An assigned mechanic used to keep driving to a job the customer had cancelled: nobody told
  // them.
  @Test
  void cancel_acceptedJob_tellsTheAssignedMechanic_withAFrameAndAPush() {
    SosRequest accepted =
        SosRequest.builder()
            .sosId(sosId)
            .status(SosStatus.ON_THE_WAY)
            .user(user)
            .vehicle(vehicle)
            .build();
    UUID assigned = UUID.randomUUID();
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(accepted));
    when(sosRequestRepository.save(accepted)).thenReturn(accepted);
    when(sosMapper.toResponse(accepted)).thenReturn(sosResponse);
    when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(Optional.of(bidBy(assigned, BidStatus.ACCEPTED, accepted)));

    sosService.cancel(sosId, userId);

    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(assigned.toString()),
            eq("/queue/sos-updates"),
            eq(Map.of("type", "SOS_CANCELLED", "sosId", sosId, "fromStatus", "ON_THE_WAY")));
    ArgumentCaptor<SosNotificationEvent> pushes =
        ArgumentCaptor.forClass(SosNotificationEvent.class);
    verify(sosEventProducer, times(2)).publishNotification(pushes.capture());
    assertThat(pushes.getAllValues())
        .anySatisfy(
            p -> {
              assertThat(p.type()).isEqualTo("SOS_CANCELLED");
              assertThat(p.recipientId()).isEqualTo(assigned);
              assertThat(p.recipientRole()).isEqualTo("MECHANIC");
            });
    ArgumentCaptor<SosStatusUpdatedEvent> event =
        ArgumentCaptor.forClass(SosStatusUpdatedEvent.class);
    verify(sosEventProducer).publishStatusUpdated(event.capture());
    assertThat(event.getValue().mechanicId())
        .as("the Kafka event names the mechanic")
        .isEqualTo(assigned);
  }

  @Test
  void cancel_pendingBids_tellsEachBidder_withOneBatchedPush() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
    when(sosRequestRepository.save(pendingSos)).thenReturn(pendingSos);
    when(sosMapper.toResponse(pendingSos)).thenReturn(sosResponse);
    when(bidSosRepository.findBySosRequestSosIdAndStatus(sosId, BidStatus.PENDING))
        .thenReturn(
            List.of(
                bidBy(a, BidStatus.PENDING, pendingSos), bidBy(b, BidStatus.PENDING, pendingSos)));

    sosService.cancel(sosId, userId);

    for (UUID bidder : List.of(a, b)) {
      verify(messagingTemplate)
          .convertAndSendToUser(
              eq(bidder.toString()),
              eq("/queue/sos-updates"),
              eq(Map.of("type", "SOS_CANCELLED", "sosId", sosId, "fromStatus", "PENDING")));
    }
    ArgumentCaptor<SosNotificationEvent> pushes =
        ArgumentCaptor.forClass(SosNotificationEvent.class);
    verify(sosEventProducer, times(2)).publishNotification(pushes.capture());
    assertThat(pushes.getAllValues())
        .anySatisfy(p -> assertThat(p.recipientIds()).containsExactlyInAnyOrder(a, b));
  }

  @Test
  void cancel_withNoBids_sendsNoMechanicFramesOrPushes() {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
    when(sosRequestRepository.save(pendingSos)).thenReturn(pendingSos);
    when(sosMapper.toResponse(pendingSos)).thenReturn(sosResponse);

    sosService.cancel(sosId, userId);

    verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    verify(sosEventProducer, times(1)).publishNotification(any(SosNotificationEvent.class));
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

  // ── cancel closes the requests still waiting for the customer (D2) ─────────

  private SosStatusHistory waitingRequest(SosStatus from, SosStatus to) {
    return historyRow(
        from, to, ApprovedStatus.PENDING_APPROVAL, LocalDateTime.now().minusMinutes(1), null);
  }

  private void stubCancel(SosRequest sos, List<SosStatusHistory> waiting) {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(sosRequestRepository.save(sos)).thenReturn(sos);
    when(sosMapper.toResponse(sos)).thenReturn(sosResponse);
    when(sosStatusHistoryRepository.findBySosRequestSosIdAndApprovedStatus(
            sosId, ApprovedStatus.PENDING_APPROVAL))
        .thenReturn(waiting);
  }

  @Test
  void cancel_withACompletionWaitingForApproval_closesItAsRejected() {
    SosRequest sos = sosAt(SosStatus.IN_PROGRESS);
    SosStatusHistory waiting = waitingRequest(SosStatus.IN_PROGRESS, SosStatus.COMPLETED);
    stubCancel(sos, List.of(waiting));

    sosService.cancel(sosId, userId);

    assertThat(sos.getStatus()).isEqualTo(SosStatus.CANCELLED);
    assertThat(waiting.getApprovedStatus()).isEqualTo(ApprovedStatus.REJECTED);
    assertThat(waiting.getApproveAt()).isNotNull();
    assertThat(waiting.getApprovedBy()).as("the system closed it, not the customer").isNull();
    verify(sosStatusHistoryRepository).saveAll(List.of(waiting));
  }

  @Test
  void cancel_closesEveryPendingApprovalRow_ifMultipleArePossible() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    SosStatusHistory arrival = waitingRequest(SosStatus.ON_THE_WAY, SosStatus.ARRIVED);
    SosStatusHistory completion = waitingRequest(SosStatus.IN_PROGRESS, SosStatus.COMPLETED);
    stubCancel(sos, List.of(arrival, completion));

    sosService.cancel(sosId, userId);

    assertThat(List.of(arrival, completion))
        .allSatisfy(row -> assertThat(row.getApprovedStatus()).isEqualTo(ApprovedStatus.REJECTED));
    assertThat(arrival.getApproveAt()).isNotNull().isEqualTo(completion.getApproveAt());
    verify(sosStatusHistoryRepository).saveAll(List.of(arrival, completion));
  }

  @Test
  void cancel_withNothingWaiting_writesNoHistory() {
    SosRequest sos = sosAt(SosStatus.PENDING);
    stubCancel(sos, List.of());

    sosService.cancel(sosId, userId);

    assertThat(sos.getStatus()).isEqualTo(SosStatus.CANCELLED);
    verify(sosStatusHistoryRepository, never()).save(any());
    verify(sosStatusHistoryRepository).saveAll(List.of());
  }

  @Test
  void cancel_isIdempotent_doesNotDuplicateHistoryOrEvents() {
    SosRequest cancelled = sosAt(SosStatus.CANCELLED);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(cancelled));

    assertThatThrownBy(() -> sosService.cancel(sosId, userId))
        .isInstanceOf(IllegalStateException.class);

    verify(sosStatusHistoryRepository, never())
        .findBySosRequestSosIdAndApprovedStatus(any(), any());
    verify(sosStatusHistoryRepository, never()).save(any());
    verify(sosStatusHistoryRepository, never()).saveAll(any());
    verify(sosEventProducer, never()).publishStatusUpdated(any());
    verify(sosEventProducer, never()).publishNotification(any());
    verifyNoInteractions(messagingTemplate);
  }

  // The closed row is the history evidence; no separate CANCELLED row is written (audit item 7).
  @Test
  void cancel_cancellationHistoryAndMechanicNotificationArePresent() {
    SosRequest sos = sosAt(SosStatus.IN_PROGRESS);
    SosStatusHistory waiting = waitingRequest(SosStatus.IN_PROGRESS, SosStatus.COMPLETED);
    stubCancel(sos, List.of(waiting));
    UUID assigned = UUID.randomUUID();
    when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(Optional.of(bidBy(assigned, BidStatus.ACCEPTED, sos)));

    sosService.cancel(sosId, userId);

    assertThat(waiting.getApprovedStatus()).isEqualTo(ApprovedStatus.REJECTED);
    assertThat(waiting.getApproveAt()).isNotNull();
    verify(sosStatusHistoryRepository, never()).save(any());
    verify(sosStatusHistoryRepository).saveAll(List.of(waiting));

    ArgumentCaptor<SosStatusUpdatedEvent> event =
        ArgumentCaptor.forClass(SosStatusUpdatedEvent.class);
    verify(sosEventProducer).publishStatusUpdated(event.capture());
    assertThat(event.getValue().fromStatus()).isEqualTo(SosStatus.IN_PROGRESS);
    assertThat(event.getValue().toStatus()).isEqualTo(SosStatus.CANCELLED);
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(assigned.toString()),
            eq("/queue/sos-updates"),
            eq(Map.of("type", "SOS_CANCELLED", "sosId", sosId, "fromStatus", "IN_PROGRESS")));
    ArgumentCaptor<SosNotificationEvent> pushes =
        ArgumentCaptor.forClass(SosNotificationEvent.class);
    verify(sosEventProducer, times(2)).publishNotification(pushes.capture());
    assertThat(pushes.getAllValues())
        .extracting(SosNotificationEvent::type)
        .containsExactlyInAnyOrder("STATUS_CANCELLED", "SOS_CANCELLED");
  }

  // The customer's answer arrives after a cancel closed the request: refused before anything is
  // applied, even though the ticket copy approve loaded is still IN_PROGRESS.
  @Test
  void approveStatusChange_rowClosedByACancel_isRefusedBeforeAnythingIsApplied() {
    SosRequest staleSos = sosAt(SosStatus.IN_PROGRESS);
    SosStatusHistory history =
        SosStatusHistory.builder()
            .id(historyId)
            .fromStatus(SosStatus.IN_PROGRESS)
            .toStatus(SosStatus.COMPLETED)
            .proposedBy(mechanic)
            .sosRequest(staleSos)
            .approvedStatus(ApprovedStatus.PENDING_APPROVAL)
            .build();
    when(sosStatusHistoryRepository.findById(historyId)).thenReturn(Optional.of(history));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(sosStatusHistoryRepository.findApprovedStatusById(historyId))
        .thenReturn(Optional.of(ApprovedStatus.REJECTED));

    assertThatThrownBy(
            () ->
                sosService.approveStatusChange(
                    new ApproveStatusChangeRequest(userId, historyId, ApprovedStatus.ACCEPTED)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already REJECTED");

    assertThat(staleSos.getStatus()).isEqualTo(SosStatus.IN_PROGRESS);
    assertThat(staleSos.getJobCompletedAt()).isNull();
    verify(sosRequestRepository, never()).save(any());
    verify(sosStatusHistoryRepository, never()).save(any());
    verify(sosEventProducer, never()).publishStatusUpdated(any());
    verify(sosEventProducer, never()).publishNotification(any());
    verifyNoInteractions(messagingTemplate);
  }

  // ── expiry: auto-expand on a true zero-bid expiry ───────────────────────────

  @Test
  void expiry_zeroBidsBelowMaxRebroadcast_autoExpandsInsteadOfExpiring() {
    SosRequest sos = overduePending();
    when(bidSosRepository.existsBySosRequestSosId(sosId)).thenReturn(false);

    expireIfDue();

    assertThat(sos.getStatus()).isEqualTo(SosStatus.PENDING);
    assertThat(sos.getBroadcastRadiusKm()).isEqualTo(20); // default-km(15) + step(5)
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

  // ── rebroadcast: state change in a short transaction, broadcast outside it ─────

  private SosRequest expiredSosWithRadius() {
    SosRequest sos = sosAt(SosStatus.EXPIRED);
    sos.setBroadcastRadiusKm(15);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    // Lenient: the broadcast stubs are not reached when the failure under test hits first.
    lenient()
        .when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(mechanic));
    lenient()
        .when(
            mechanicRepository.filterByRadiusAndBroadcast(
                any(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ids(mechanic));
    when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);
    return sos;
  }

  @Test
  void rebroadcast_expiredTicket_goesBackToPendingAndBroadcasts() {
    SosRequest sos = expiredSosWithRadius();

    sosService.rebroadcast(sosId, userId);

    assertThat(sos.getStatus()).isEqualTo(SosStatus.PENDING);
    assertThat(sos.getRebroadcastCount()).isEqualTo(1);
    verify(messagingTemplate)
        .convertAndSendToUser(eq(mechanicId.toString()), eq("/queue/sos-new-request"), any());
    verify(taskScheduler).schedule(any(Runnable.class), any(java.time.Instant.class));
  }

  /** The offer-estimate Google call and the broadcast must not hold the transaction open. */
  @Test
  void rebroadcast_broadcastRunsOutsideTheTransaction() {
    java.util.concurrent.atomic.AtomicBoolean inTx =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    doAnswer(
            inv -> {
              inTx.set(true);
              try {
                return ((org.springframework.transaction.support.TransactionCallback<?>)
                        inv.getArgument(0))
                    .doInTransaction(null);
              } finally {
                inTx.set(false);
              }
            })
        .when(transactionTemplate)
        .execute(any());
    expiredSosWithRadius();
    when(googleMapsService.getDistanceInfos(any(), any()))
        .thenAnswer(
            inv -> {
              assertThat(inTx.get()).as("Google called inside the transaction").isFalse();
              return List.of(new DistanceInfo(800, 420, "0.8 km", "7 mins"));
            });

    sosService.rebroadcast(sosId, userId);

    verify(googleMapsService).getDistanceInfos(any(), any());
  }

  @Test
  void rebroadcast_broadcastFailsAfterCommit_stillReturnsAndSchedulesExpiry() {
    expiredSosWithRadius();
    doThrow(new RuntimeException("broker down"))
        .when(messagingTemplate)
        .convertAndSendToUser(anyString(), anyString(), any(Object.class));

    SosRequestResponse result = sosService.rebroadcast(sosId, userId);

    assertThat(result).isNotNull();
    verify(taskScheduler).schedule(any(Runnable.class), any(java.time.Instant.class));
  }

  @Test
  void rebroadcast_notYourTicket_isDeniedAndNothingChanges() {
    SosRequest sos = sosAt(SosStatus.EXPIRED);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));

    assertThatThrownBy(() -> sosService.rebroadcast(sosId, UUID.randomUUID()))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

    assertThat(sos.getStatus()).isEqualTo(SosStatus.EXPIRED);
    verify(taskScheduler, never()).schedule(any(Runnable.class), any(java.time.Instant.class));
  }

  @Test
  void rebroadcast_notExpired_isConflict() {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosAt(SosStatus.PENDING)));

    assertThatThrownBy(() -> sosService.rebroadcast(sosId, userId))
        .isInstanceOf(IllegalStateException.class);
  }

  // A double tap must queue behind the first reopen: the lock comes before the ticket is read, so
  // the second request reads PENDING instead of the EXPIRED it would have read before the lock.
  @Test
  void rebroadcast_takesTheUserLockThenTheTicketLockBeforeReadingTheTicket() {
    expiredSosWithRadius();

    sosService.rebroadcast(sosId, userId);

    org.mockito.InOrder order = inOrder(userRepository, sosRequestRepository);
    order.verify(userRepository).lockById(userId);
    order.verify(sosRequestRepository).lockStatus(sosId);
    order.verify(sosRequestRepository).findById(sosId);
  }

  @Test
  void rebroadcast_userHasAnotherActiveTicket_isConflictAndNothingChanges() {
    SosRequest sos = sosAt(SosStatus.EXPIRED);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(sosRequestRepository.existsByUserUserIdAndStatusIn(eq(userId), anyList()))
        .thenReturn(true);

    assertThatThrownBy(() -> sosService.rebroadcast(sosId, userId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already have an active SOS");

    assertThat(sos.getStatus()).isEqualTo(SosStatus.EXPIRED);
    assertThat(sos.getRebroadcastCount()).isZero();
    verify(sosRequestRepository, never()).save(any());
    verify(bidSosRepository, never()).deleteBySosRequestSosIdAndStatus(any(), any());
    verify(taskScheduler, never()).schedule(any(Runnable.class), any(java.time.Instant.class));
    verifyNoInteractions(messagingTemplate);
    verifyNoInteractions(googleMapsService);
    verify(mechanicRepository, never()).findByH3CellsAndSkillTag(any(), any(), any());
    verify(sosEventProducer, never()).publishNotification(any());
  }

  // Only the owner's other live tickets count, and the five live statuses are the ones create uses.
  @Test
  void rebroadcast_checksActiveTicketsForTheOwnerOnly() {
    expiredSosWithRadius();

    sosService.rebroadcast(sosId, userId);

    verify(sosRequestRepository)
        .existsByUserUserIdAndStatusIn(
            userId,
            List.of(
                SosStatus.PENDING,
                SosStatus.ACCEPTED,
                SosStatus.ON_THE_WAY,
                SosStatus.ARRIVED,
                SosStatus.IN_PROGRESS));
  }

  @Test
  void rebroadcast_missingTicket_is404AndNothingChanges() {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> sosService.rebroadcast(sosId, userId))
        .isInstanceOf(EntityNotFoundException.class);

    verify(sosRequestRepository, never()).existsByUserUserIdAndStatusIn(any(), any());
    verify(sosRequestRepository, never()).save(any());
    verify(taskScheduler, never()).schedule(any(Runnable.class), any(java.time.Instant.class));
    verifyNoInteractions(messagingTemplate);
    verifyNoInteractions(googleMapsService);
  }

  @Test
  void expiry_zeroBidsAtMaxRebroadcast_expiresInsteadOfExpandingFurther() {
    SosRequest sos = overduePending();
    sos.setRebroadcastCount(3); // sos.bidding.max-rebroadcast
    when(bidSosRepository.existsBySosRequestSosId(sosId)).thenReturn(false);

    expireIfDue();

    assertThat(sos.getStatus()).isEqualTo(SosStatus.EXPIRED);
    verify(sosEventProducer).publishStatusUpdated(any());
  }

  @Test
  void expiry_hasBidsEvenIfNoneAccepted_expiresInsteadOfAutoExpanding() {
    SosRequest sos = overduePending();
    when(bidSosRepository.existsBySosRequestSosId(sosId)).thenReturn(true);

    expireIfDue();

    assertThat(sos.getStatus()).isEqualTo(SosStatus.EXPIRED);
    assertThat(sos.getBroadcastRadiusKm()).isNull(); // untouched — never widened
    verify(sosEventProducer).publishStatusUpdated(any());
    verify(sosEventProducer, never())
        .publishNotification(argThat(n -> n.type().equals("SOS_AUTO_REBROADCAST")));
  }

  // ── mechanic release: the assigned mechanic backs out before ARRIVED ──────────

  private SosRequest releasableSos(SosStatus status) {
    SosRequest sos = sosAt(status);
    sos.setBroadcastRadiusKm(15);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    // Lenient: the refusal tests throw before a response is built.
    lenient().when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);
    return sos;
  }

  private BidSos acceptedBidOfAssignedMechanic(SosRequest sos) {
    BidSos bid = bidBy(mechanicId, BidStatus.ACCEPTED, sos);
    when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(Optional.of(bid));
    return bid;
  }

  @Test
  void release_fromAcceptedOrOnTheWay_reopensBiddingAndRecordsIt() {
    for (SosStatus from : List.of(SosStatus.ACCEPTED, SosStatus.ON_THE_WAY)) {
      reset(sosRequestRepository, bidSosRepository, sosStatusHistoryRepository, taskScheduler);
      SosRequest sos = releasableSos(from);
      BidSos bid = acceptedBidOfAssignedMechanic(sos);
      sos.setRebroadcastCount(2);

      sosService.releaseByMechanic(sosId, mechanicId);

      assertThat(sos.getStatus()).isEqualTo(SosStatus.PENDING);
      assertThat(sos.getBiddingExpiresAt()).isAfter(LocalDateTime.now());
      assertThat(sos.getRebroadcastCount())
          .as("a release is not the customer's retry")
          .isEqualTo(2);
      assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
      verify(bidSosRepository).deleteBySosRequestSosIdAndStatus(sosId, BidStatus.REJECTED);
      ArgumentCaptor<SosStatusHistory> history = ArgumentCaptor.forClass(SosStatusHistory.class);
      verify(sosStatusHistoryRepository).save(history.capture());
      assertThat(history.getValue().getFromStatus()).isEqualTo(from);
      assertThat(history.getValue().getToStatus()).isEqualTo(SosStatus.PENDING);
      verify(taskScheduler).schedule(any(Runnable.class), any(java.time.Instant.class));
    }
    verify(routeEtaService, times(2)).clear(sosId);
  }

  @Test
  void release_takesTheTicketLockBeforeReadingTheTicket() {
    SosRequest sos = releasableSos(SosStatus.ACCEPTED);
    acceptedBidOfAssignedMechanic(sos);

    sosService.releaseByMechanic(sosId, mechanicId);

    org.mockito.InOrder order = inOrder(sosRequestRepository);
    order.verify(sosRequestRepository).lockStatus(sosId);
    order.verify(sosRequestRepository).findById(sosId);
  }

  @Test
  void release_tellsTheCustomer_withAFrameAKafkaEventAndAPush() {
    SosRequest sos = releasableSos(SosStatus.ON_THE_WAY);
    acceptedBidOfAssignedMechanic(sos);

    sosService.releaseByMechanic(sosId, mechanicId);

    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(userId.toString()),
            eq("/queue/sos-updates"),
            eq(Map.of("type", "MECHANIC_RELEASED", "sosId", sosId, "fromStatus", "ON_THE_WAY")));
    ArgumentCaptor<SosStatusUpdatedEvent> event =
        ArgumentCaptor.forClass(SosStatusUpdatedEvent.class);
    verify(sosEventProducer).publishStatusUpdated(event.capture());
    assertThat(event.getValue().toStatus()).isEqualTo(SosStatus.PENDING);
    assertThat(event.getValue().mechanicId()).isEqualTo(mechanicId);
    verify(sosEventProducer)
        .publishNotification(
            argThat(n -> n.type().equals("MECHANIC_RELEASED") && n.recipientId().equals(userId)));
  }

  @Test
  void release_offersTheJobAgain_butNotToTheMechanicWhoReleasedIt() {
    SosRequest sos = releasableSos(SosStatus.ACCEPTED);
    acceptedBidOfAssignedMechanic(sos);
    Mechanic other =
        Mechanic.builder()
            .mechanicId(UUID.randomUUID())
            .fullName("Other")
            .phoneNumber("2")
            .email("o@x.com")
            .build();
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(mechanic, other));
    when(mechanicRepository.filterByRadiusAndBroadcast(
            any(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ids(mechanic, other));

    sosService.releaseByMechanic(sosId, mechanicId);

    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(other.getMechanicId().toString()), eq("/queue/sos-new-request"), any());
    verify(messagingTemplate, never())
        .convertAndSendToUser(eq(mechanicId.toString()), eq("/queue/sos-new-request"), any());
  }

  @Test
  void release_fromArrivedOrLater_isConflictAndNothingChanges() {
    for (SosStatus from :
        List.of(
            SosStatus.ARRIVED,
            SosStatus.IN_PROGRESS,
            SosStatus.COMPLETED,
            SosStatus.CANCELLED,
            SosStatus.PENDING)) {
      reset(sosRequestRepository, bidSosRepository);
      SosRequest sos = releasableSos(from);
      acceptedBidOfAssignedMechanic(sos);

      assertThatThrownBy(() -> sosService.releaseByMechanic(sosId, mechanicId))
          .as("release from %s", from)
          .isInstanceOf(IllegalStateException.class);
      assertThat(sos.getStatus()).isEqualTo(from);
    }
    verify(sosRequestRepository, never()).save(any());
    verify(bidSosRepository, never()).deleteBySosRequestSosIdAndStatus(any(), any());
    verifyNoInteractions(messagingTemplate, sosEventProducer);
  }

  @Test
  void release_byAnotherMechanic_isDeniedAndNothingChanges() {
    SosRequest sos = releasableSos(SosStatus.ACCEPTED);
    acceptedBidOfAssignedMechanic(sos);

    assertThatThrownBy(() -> sosService.releaseByMechanic(sosId, UUID.randomUUID()))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(sos.getStatus()).isEqualTo(SosStatus.ACCEPTED);
    verify(bidSosRepository, never()).deleteBySosRequestSosIdAndStatus(any(), any());
    verifyNoInteractions(messagingTemplate, sosEventProducer);
  }

  @Test
  void release_broadcastFailsAfterCommit_stillReturnsAndSchedulesExpiry() {
    SosRequest sos = releasableSos(SosStatus.ACCEPTED);
    acceptedBidOfAssignedMechanic(sos);
    doThrow(new RuntimeException("kafka down")).when(sosEventProducer).publishStatusUpdated(any());

    SosRequestResponse response = sosService.releaseByMechanic(sosId, mechanicId);

    assertThat(response.status()).isEqualTo(SosStatus.PENDING);
    assertThat(sos.getStatus()).isEqualTo(SosStatus.PENDING);
    verify(taskScheduler).schedule(any(Runnable.class), any(java.time.Instant.class));
  }

  @Test
  void cancel_and_updateStatus_takeTheTicketLockBeforeReadingTheTicket() {
    SosRequest sos = sosAt(SosStatus.PENDING);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(sosRequestRepository.save(sos)).thenReturn(sos);
    when(sosMapper.toResponse(sos)).thenReturn(sosResponse);

    sosService.cancel(sosId, userId);

    org.mockito.InOrder order = inOrder(sosRequestRepository);
    order.verify(sosRequestRepository).lockStatus(sosId);
    order.verify(sosRequestRepository).findById(sosId);

    reset(sosRequestRepository);
    SosRequest accepted = sosAt(SosStatus.ACCEPTED);
    stubUpdateStatus(accepted);
    sosService.updateStatus(sosId, mechanicId, SosStatus.ON_THE_WAY);

    org.mockito.InOrder order2 = inOrder(sosRequestRepository);
    order2.verify(sosRequestRepository).lockStatus(sosId);
    order2.verify(sosRequestRepository).findById(sosId);
  }

  // ── dispatch: stale location, and road ETA cap on the offer ───────────────────

  @Test
  void fanOut_asksOnlyForMechanicsSeenRecently() {
    SosRequest sos = expiredSosWithRadius();
    java.time.LocalDateTime before = java.time.LocalDateTime.now().minusMinutes(5);

    sosService.rebroadcast(sosId, userId);

    ArgumentCaptor<java.time.LocalDateTime> since =
        ArgumentCaptor.forClass(java.time.LocalDateTime.class);
    verify(mechanicRepository).findByH3CellsAndSkillTag(any(), eq(resolvedKey), since.capture());
    assertThat(since.getValue())
        .isBetween(before.minusSeconds(5), java.time.LocalDateTime.now().minusMinutes(5));
    assertThat(sos.getStatus()).isEqualTo(SosStatus.PENDING);
  }

  private Mechanic otherMechanic(double lng) {
    return Mechanic.builder()
        .mechanicId(UUID.randomUUID())
        .fullName("Other")
        .phoneNumber("2")
        .email("o@x.com")
        .currentLocation(GEO.createPoint(new Coordinate(lng, 11.5573)))
        .build();
  }

  @Test
  void fanOut_dropsMechanicsWhoseRoadEtaIsOverTheCap_butKeepsThoseWithNoEstimate() {
    SosRequest sos = expiredSosWithRadius();
    Mechanic near = otherMechanic(104.9283);
    Mechanic far = otherMechanic(104.9284);
    Mechanic unknown = otherMechanic(104.9285);
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(near, far, unknown));
    when(mechanicRepository.filterByRadiusAndBroadcast(
            any(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ids(near, far, unknown));
    // The estimates come back in the order of the origins Google was asked about (nearest first).
    when(googleMapsService.getDistanceInfos(any(), any()))
        .thenAnswer(
            inv -> {
              List<org.locationtech.jts.geom.Point> origins = inv.getArgument(0);
              return origins.stream()
                  .map(
                      p ->
                          p.getX() < 104.92835
                              ? new DistanceInfo(1_000, 300, "1 km", "5 mins")
                              : new DistanceInfo(40_000, 31 * 60, "40 km", "31 mins"))
                  .toList();
            });
    // Only two of the three get an estimate: the third is beyond the Google cap.
    ReflectionTestUtils.setField(sosService, "offerMaxMechanics", 2);

    sosService.rebroadcast(sosId, userId);

    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(near.getMechanicId().toString()), eq("/queue/sos-new-request"), any());
    verify(messagingTemplate, never())
        .convertAndSendToUser(
            eq(far.getMechanicId().toString()), eq("/queue/sos-new-request"), any());
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(unknown.getMechanicId().toString()), eq("/queue/sos-new-request"), any());
    assertThat(sos.getStatus()).isEqualTo(SosStatus.PENDING);
  }

  @Test
  void fanOut_roadEtaCapDisabled_offersEveryone() {
    expiredSosWithRadius();
    Mechanic far = otherMechanic(104.9284);
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any())).thenReturn(ids(far));
    when(mechanicRepository.filterByRadiusAndBroadcast(
            any(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ids(far));
    when(googleMapsService.getDistanceInfos(any(), any()))
        .thenReturn(List.of(new DistanceInfo(90_000, 3 * 3600, "90 km", "3 hours")));
    ReflectionTestUtils.setField(sosService, "maxRoadEtaMinutes", 0);

    sosService.rebroadcast(sosId, userId);

    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(far.getMechanicId().toString()), eq("/queue/sos-new-request"), any());
  }

  @Test
  void fanOut_recordsExactlyTheMechanicsThatWereOffered() {
    expiredSosWithRadius();
    Mechanic near = otherMechanic(104.9283);
    Mechanic far = otherMechanic(104.9284);
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(near, far));
    when(mechanicRepository.filterByRadiusAndBroadcast(
            any(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ids(near, far));
    when(googleMapsService.getDistanceInfos(any(), any()))
        .thenReturn(
            List.of(
                new DistanceInfo(1_000, 120, "1 km", "2 mins"),
                new DistanceInfo(90_000, 3 * 3600, "90 km", "3 hours")));
    ReflectionTestUtils.setField(sosService, "maxRoadEtaMinutes", 30);

    sosService.rebroadcast(sosId, userId);

    // The far mechanic is over the road-ETA cap, so they got no frame and no row.
    verify(sosOfferRepository).recordOffered(sosId, List.of(near.getMechanicId()));
  }

  @Test
  void fanOut_aFailedStatisticsWrite_doesNotStopTheOffer() {
    expiredSosWithRadius();
    Mechanic near = otherMechanic(104.9283);
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any())).thenReturn(ids(near));
    when(mechanicRepository.filterByRadiusAndBroadcast(
            any(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ids(near));
    org.mockito.Mockito.doThrow(new RuntimeException("db down"))
        .when(sosOfferRepository)
        .recordOffered(any(), any(java.util.Collection.class));

    sosService.rebroadcast(sosId, userId);

    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(near.getMechanicId().toString()), eq("/queue/sos-new-request"), any());
  }

  @Test
  void rebroadcast_neverOffersTheTicketToAMechanicWhoReleasedIt() {
    expiredSosWithRadius();
    Mechanic releaser = otherMechanic(104.9283);
    Mechanic other = otherMechanic(104.9284);
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(releaser, other));
    when(mechanicRepository.filterByRadiusAndBroadcast(
            any(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ids(releaser, other));
    when(sosStatusHistoryRepository.findMechanicIdsByToStatus(sosId, SosStatus.PENDING))
        .thenReturn(java.util.Set.of(releaser.getMechanicId()));

    sosService.rebroadcast(sosId, userId);

    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(other.getMechanicId().toString()), eq("/queue/sos-new-request"), any());
    verify(messagingTemplate, never())
        .convertAndSendToUser(
            eq(releaser.getMechanicId().toString()), eq("/queue/sos-new-request"), any());
  }

  // ── no-show: ask a mechanic who stopped moving, release them if they do not answer ──

  private BidSos noShowJob(SosStatus status, LocalDateTime movedAt, LocalDateTime promptedAt) {
    SosRequest sos = sosAt(status);
    sos.setBroadcastRadiusKm(15);
    // The mock template runs executeWithoutResult as a no-op; run it inline for the nudge record.
    lenient()
        .doAnswer(
            inv -> {
              ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>)
                      inv.getArgument(0))
                  .accept(null);
              return null;
            })
        .when(transactionTemplate)
        .executeWithoutResult(any());
    mechanic.setLocationMovedAt(movedAt);
    BidSos bid =
        BidSos.builder()
            .bidId(UUID.randomUUID())
            .status(BidStatus.ACCEPTED)
            .sosRequest(sos)
            .mechanic(mechanic)
            .noShowPromptedAt(promptedAt)
            .build();
    lenient().when(bidSosRepository.findAcceptedBeforeArrival()).thenReturn(List.of(bid));
    lenient().when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    lenient()
        .when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(Optional.of(bid));
    lenient().when(sosMapper.toResponse(any(SosRequest.class))).thenReturn(sosResponse);
    return bid;
  }

  private static LocalDateTime minutesAgo(long m) {
    return LocalDateTime.now().minusMinutes(m);
  }

  @Test
  void noShow_aMovingMechanic_isLeftAlone_andAPendingQuestionIsWithdrawn() {
    BidSos bid = noShowJob(SosStatus.ON_THE_WAY, minutesAgo(1), minutesAgo(3));

    sosService.checkNoShowJobs();

    assertThat(bid.getNoShowPromptedAt()).isNull();
    verifyNoInteractions(messagingTemplate, sosEventProducer);
  }

  // A mechanic who asked the customer to confirm their arrival stands at the breakdown on purpose.
  @Test
  void noShow_aMechanicWaitingForTheCustomersArrivalAnswer_isNeitherAskedNorReleased() {
    BidSos bid = noShowJob(SosStatus.ON_THE_WAY, minutesAgo(10), minutesAgo(3));
    when(sosStatusHistoryRepository.findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
            sosId, ApprovedStatus.PENDING_APPROVAL))
        .thenReturn(Optional.of(waitingArrival(bid.getSosRequest(), 5)));

    sosService.checkNoShowJobs();

    assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
    assertThat(bid.getSosRequest().getStatus()).isEqualTo(SosStatus.ON_THE_WAY);
    verifyNoInteractions(messagingTemplate, sosEventProducer);
  }

  @Test
  void noShow_aMechanicWithNoRecordedMovement_isTreatedAsMoving() {
    noShowJob(SosStatus.ACCEPTED, null, null);

    sosService.checkNoShowJobs();

    verifyNoInteractions(messagingTemplate, sosEventProducer);
    verify(bidSosRepository, never()).save(any());
  }

  @Test
  void noShow_standingStill_isAskedOnce_withAFrameAndAPush_andTheQuestionIsRecorded() {
    BidSos bid = noShowJob(SosStatus.ON_THE_WAY, minutesAgo(6), null);

    sosService.checkNoShowJobs();

    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(mechanicId.toString()),
            eq("/queue/sos-updates"),
            eq(Map.of("type", "STILL_COMING_CHECK", "sosId", sosId, "respondBySeconds", 60)));
    verify(sosEventProducer)
        .publishNotification(
            argThat(
                n -> n.type().equals("STILL_COMING_CHECK") && n.recipientId().equals(mechanicId)));
    assertThat(bid.getNoShowPromptedAt()).isNotNull();
    assertThat(bid.getStatus()).as("only asked, not released").isEqualTo(BidStatus.ACCEPTED);
  }

  @Test
  void noShow_aQuestionThatCouldNotBeSent_isNotRecorded_soItIsAskedAgain() {
    BidSos bid = noShowJob(SosStatus.ACCEPTED, minutesAgo(6), null);
    doThrow(new RuntimeException("broker down"))
        .when(messagingTemplate)
        .convertAndSendToUser(eq(mechanicId.toString()), eq("/queue/sos-updates"), any());

    sosService.checkNoShowJobs();

    assertThat(bid.getNoShowPromptedAt()).isNull();
  }

  @Test
  void noShow_askedAndStillWithinTheTimeToAnswer_isLeftAlone() {
    BidSos bid =
        noShowJob(SosStatus.ON_THE_WAY, minutesAgo(6), LocalDateTime.now().minusSeconds(20));

    sosService.checkNoShowJobs();

    assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
    verifyNoInteractions(messagingTemplate, sosEventProducer);
  }

  @Test
  void noShow_askedAndNotAnswered_isReleased_bothSidesToldAndTheJobOfferedToOthers() {
    BidSos bid = noShowJob(SosStatus.ON_THE_WAY, minutesAgo(8), minutesAgo(2));
    SosRequest sos = bid.getSosRequest();
    Mechanic other =
        Mechanic.builder()
            .mechanicId(UUID.randomUUID())
            .fullName("Other")
            .phoneNumber("2")
            .email("o@x.com")
            .build();
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(ids(mechanic, other));
    when(mechanicRepository.filterByRadiusAndBroadcast(
            any(), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(ids(mechanic, other));

    sosService.checkNoShowJobs();

    assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
    assertThat(sos.getStatus()).isEqualTo(SosStatus.PENDING);
    assertThat(sos.getBiddingExpiresAt()).isAfter(LocalDateTime.now());
    verify(bidSosRepository).deleteBySosRequestSosIdAndStatus(sosId, BidStatus.REJECTED);
    verify(sosStatusHistoryRepository).save(any(SosStatusHistory.class));
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(userId.toString()),
            eq("/queue/sos-updates"),
            eq(Map.of("type", "MECHANIC_NO_SHOW", "sosId", sosId, "fromStatus", "ON_THE_WAY")));
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(mechanicId.toString()),
            eq("/queue/sos-updates"),
            eq(Map.of("type", "JOB_RELEASED", "sosId", sosId, "reason", "NO_SHOW")));
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(other.getMechanicId().toString()), eq("/queue/sos-new-request"), any());
    verify(messagingTemplate, never())
        .convertAndSendToUser(eq(mechanicId.toString()), eq("/queue/sos-new-request"), any());
    verify(taskScheduler).schedule(any(Runnable.class), any(java.time.Instant.class));
    verify(routeEtaService).clear(sosId);
  }

  @Test
  void noShow_aJobThatMovedOnBeforeTheSweepGotTheLock_isSkippedWithNoSideEffects() {
    for (SosStatus now : List.of(SosStatus.ARRIVED, SosStatus.CANCELLED, SosStatus.COMPLETED)) {
      reset(bidSosRepository, sosRequestRepository, messagingTemplate, sosEventProducer);
      BidSos bid = noShowJob(now, minutesAgo(8), minutesAgo(2));

      sosService.checkNoShowJobs();

      assertThat(bid.getStatus()).as("%s", now).isEqualTo(BidStatus.ACCEPTED);
      verify(bidSosRepository, never()).deleteBySosRequestSosIdAndStatus(any(), any());
      verifyNoInteractions(messagingTemplate, sosEventProducer);
    }
  }

  @Test
  void noShow_switchedOff_doesNotEvenLookForJobs() {
    ReflectionTestUtils.setField(sosService, "noShowStalledMinutes", 0);

    sosService.checkNoShowJobs();

    verify(bidSosRepository, never()).findAcceptedBeforeArrival();
  }

  @Test
  void noShow_oneJobThatFails_doesNotStopTheNext() {
    BidSos first = noShowJob(SosStatus.ACCEPTED, minutesAgo(8), minutesAgo(2));
    UUID otherSos = UUID.randomUUID();
    SosRequest second = sosAt(SosStatus.ACCEPTED);
    second.setSosId(otherSos);
    Mechanic other =
        Mechanic.builder()
            .mechanicId(UUID.randomUUID())
            .fullName("O")
            .phoneNumber("3")
            .email("o2@x.com")
            .locationMovedAt(minutesAgo(8))
            .build();
    BidSos secondBid =
        BidSos.builder()
            .bidId(UUID.randomUUID())
            .status(BidStatus.ACCEPTED)
            .sosRequest(second)
            .mechanic(other)
            .noShowPromptedAt(minutesAgo(2))
            .build();
    when(bidSosRepository.findAcceptedBeforeArrival()).thenReturn(List.of(first, secondBid));
    // The first job blows up when it is looked up; the second is normal.
    when(sosRequestRepository.findById(sosId)).thenThrow(new RuntimeException("db hiccup"));
    when(sosRequestRepository.findById(otherSos)).thenReturn(Optional.of(second));
    when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(otherSos, BidStatus.ACCEPTED))
        .thenReturn(Optional.of(secondBid));

    sosService.checkNoShowJobs();

    assertThat(secondBid.getStatus()).isEqualTo(BidStatus.CANCELLED);
  }

  @Test
  void confirmStillComing_restartsTheClock_andWithdrawsTheQuestion() {
    BidSos bid = noShowJob(SosStatus.ON_THE_WAY, minutesAgo(8), minutesAgo(1));

    sosService.confirmStillComing(sosId, mechanicId);

    assertThat(mechanic.getLocationMovedAt()).isAfter(minutesAgo(1));
    assertThat(bid.getNoShowPromptedAt()).isNull();
    verify(mechanicRepository).save(mechanic);
  }

  @Test
  void confirmStillComing_byAnotherMechanic_isDenied() {
    noShowJob(SosStatus.ON_THE_WAY, minutesAgo(8), minutesAgo(1));

    assertThatThrownBy(() -> sosService.confirmStillComing(sosId, UUID.randomUUID()))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verify(mechanicRepository, never()).save(any());
  }

  @Test
  void confirmStillComing_afterArrived_isConflict() {
    noShowJob(SosStatus.ARRIVED, minutesAgo(8), null);

    assertThatThrownBy(() -> sosService.confirmStillComing(sosId, mechanicId))
        .isInstanceOf(IllegalStateException.class);
  }

  // ── reconnect sync: the offers still open for a mechanic ──────────────────────

  private SosRequest openTicket(UUID id, String problemType) {
    return SosRequest.builder()
        .sosId(id)
        .problemType(problemType)
        .status(SosStatus.PENDING)
        .location(GEO.createPoint(new Coordinate(104.9282, 11.5573)))
        .user(user)
        .vehicle(vehicle)
        .biddingExpiresAt(LocalDateTime.now().plusSeconds(30))
        .build();
  }

  @Test
  void openOffers_areTheSameFramesTheBroadcastSends_forTicketsTheMechanicIsStillEligibleFor() {
    UUID second = UUID.randomUUID();
    SosRequest a = openTicket(sosId, resolvedKey);
    SosRequest b = openTicket(second, resolvedKey);
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(sosRequestRepository.findOpenIdsNear(eq(mechanicId), any(), anyInt()))
        .thenReturn(List.of(second, sosId)); // newest first
    when(sosRequestRepository.findAllById(any())).thenReturn(List.of(a, b));
    // Both pass the real rule: the mechanic is in the H3 ring and inside the radius.
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(List.of(mechanicId));
    when(mechanicRepository.filterByRadius(any(), anyDouble(), anyDouble()))
        .thenReturn(List.of(mechanicId));
    when(sosMediaServiceBean.photoUrls(any())).thenReturn(List.of("https://x/p.jpg"));

    List<SosNotificationEvent> offers = sosService.openOffersFor(mechanicId);

    assertThat(offers).extracting(SosNotificationEvent::sosId).containsExactly(second, sosId);
    SosNotificationEvent offer = offers.get(0);
    assertThat(offer.type()).isEqualTo("SOS_CREATED");
    assertThat(offer.recipientRole()).isEqualTo("MECHANIC");
    assertThat(offer.body()).isEqualTo("Problem: " + resolvedKey);
    assertThat(offer.photoUrls()).containsExactly("https://x/p.jpg");
  }

  @Test
  void openOffers_dropATicketTheQueryFoundButTheRealRuleRejects() {
    SosRequest a = openTicket(sosId, resolvedKey);
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(sosRequestRepository.findOpenIdsNear(eq(mechanicId), any(), anyInt()))
        .thenReturn(List.of(sosId));
    when(sosRequestRepository.findAllById(any())).thenReturn(List.of(a));
    // Not in the mechanic's reach by the H3 + skill rule (busy, stale, or the ring misses them).
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any())).thenReturn(List.of());

    assertThat(sosService.openOffersFor(mechanicId)).isEmpty();
  }

  @Test
  void openOffers_attachTheCachedRoadEstimate_whenThereIsOne() {
    SosRequest a = openTicket(sosId, resolvedKey);
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(sosRequestRepository.findOpenIdsNear(eq(mechanicId), any(), anyInt()))
        .thenReturn(List.of(sosId));
    when(sosRequestRepository.findAllById(any())).thenReturn(List.of(a));
    when(mechanicRepository.findByH3CellsAndSkillTag(any(), any(), any()))
        .thenReturn(List.of(mechanicId));
    when(mechanicRepository.filterByRadius(any(), anyDouble(), anyDouble()))
        .thenReturn(List.of(mechanicId));
    when(redisTemplate.opsForHash()).thenReturn(hashOps);
    when(hashOps.entries(anyString()))
        .thenReturn(
            Map.of(
                mechanicId.toString(),
                "800\t420\t0.8 km\t7 mins\t" + java.time.Instant.now().getEpochSecond()));

    SosNotificationEvent offer = sosService.openOffersFor(mechanicId).get(0);

    assertThat(offer.estimate()).isNotNull();
    assertThat(offer.estimate().remainingDurationS()).isEqualTo(420);
  }

  @Test
  void openOffers_aMechanicWithNoStoredLocation_getsNothing_andNoQueryRuns() {
    mechanic.setCurrentLocation(null);
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));

    assertThat(sosService.openOffersFor(mechanicId)).isEmpty();

    verify(sosRequestRepository, never()).findOpenIdsNear(any(), any(), anyInt());
  }

  @Test
  void openOffers_unknownMechanic_isNotFound() {
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> sosService.openOffersFor(mechanicId))
        .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
  }

  // ── bids are closed with the request ──────────────────────────────────────────────

  @Test
  void cancel_closesThePendingBids_andTellsKafka_butLeavesTheAcceptedOneAlone() {
    SosRequest sos = sosAt(SosStatus.PENDING);
    UUID bidderA = UUID.randomUUID();
    UUID bidderB = UUID.randomUUID();
    BidSos a = bidBy(bidderA, BidStatus.PENDING, sos);
    BidSos b = bidBy(bidderB, BidStatus.PENDING, sos);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(sosRequestRepository.save(sos)).thenReturn(sos);
    when(sosMapper.toResponse(sos)).thenReturn(sosResponse);
    when(bidSosRepository.findBySosRequestSosIdAndStatus(sosId, BidStatus.PENDING))
        .thenReturn(List.of(a, b));

    sosService.cancel(sosId, userId);

    assertThat(a.getStatus()).isEqualTo(BidStatus.REJECTED);
    assertThat(b.getStatus()).isEqualTo(BidStatus.REJECTED);
    assertThat(a.getRespondedAt()).isNotNull();
    verify(bidSosRepository).saveAll(List.of(a, b));
    verify(sosEventProducer)
        .publishBidRejected(
            argThat(
                e ->
                    e.sosId().equals(sosId)
                        && e.bidIds().containsAll(List.of(a.getBidId(), b.getBidId()))));
    // They are still told the request was cancelled.
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(bidderA.toString()),
            eq("/queue/sos-updates"),
            eq(Map.of("type", "SOS_CANCELLED", "sosId", sosId, "fromStatus", "PENDING")));
  }

  @Test
  void expiry_withBids_closesThem_andTellsEachBidderOnce_withOneBatchedPush() {
    SosRequest sos = overduePending();
    UUID bidderA = UUID.randomUUID();
    UUID bidderB = UUID.randomUUID();
    BidSos a = bidBy(bidderA, BidStatus.PENDING, sos);
    BidSos b = bidBy(bidderB, BidStatus.PENDING, sos);
    when(bidSosRepository.existsBySosRequestSosId(sosId)).thenReturn(true);
    when(bidSosRepository.findBySosRequestSosIdAndStatus(sosId, BidStatus.PENDING))
        .thenReturn(List.of(a, b));

    expireIfDue();

    assertThat(sos.getStatus()).isEqualTo(SosStatus.EXPIRED);
    assertThat(a.getStatus()).isEqualTo(BidStatus.REJECTED);
    assertThat(b.getStatus()).isEqualTo(BidStatus.REJECTED);
    verify(sosEventProducer).publishBidRejected(any());
    for (UUID bidder : List.of(bidderA, bidderB)) {
      verify(messagingTemplate)
          .convertAndSendToUser(
              eq(bidder.toString()),
              eq("/queue/sos-updates"),
              eq(Map.of("type", "SOS_EXPIRED", "sosId", sosId)));
    }
    verify(sosEventProducer)
        .publishNotification(
            argThat(
                n ->
                    n.type().equals("SOS_EXPIRED")
                        && n.recipientIds() != null
                        && n.recipientIds().containsAll(List.of(bidderA, bidderB))));
    // The customer is told as before.
    verify(messagingTemplate)
        .convertAndSendToUser(eq(userId.toString()), eq("/queue/sos-updates"), any());
  }

  @Test
  void expiry_withNoBids_touchesNoBidAndSendsNoBidderFrame() {
    SosRequest sos = overduePending();
    sos.setRebroadcastCount(3);
    when(bidSosRepository.existsBySosRequestSosId(sosId)).thenReturn(false);

    expireIfDue();

    assertThat(sos.getStatus()).isEqualTo(SosStatus.EXPIRED);
    verify(bidSosRepository, never()).saveAll(any());
    verify(messagingTemplate, never())
        .convertAndSendToUser(
            any(), eq("/queue/sos-updates"), eq(Map.of("type", "SOS_EXPIRED", "sosId", sosId)));
  }

  @Test
  void expiry_aFailureClosingTheBids_failsTheWholeStep_andAnnouncesNothing() {
    overduePending();
    when(bidSosRepository.existsBySosRequestSosId(sosId)).thenReturn(true);
    when(bidSosRepository.findBySosRequestSosIdAndStatus(sosId, BidStatus.PENDING))
        .thenThrow(new RuntimeException("db hiccup"));

    // The transaction rolls back (the ticket stays PENDING in the DB); the sweep tries again.
    assertThatThrownBy(this::expireIfDue).hasMessageContaining("db hiccup");

    verify(sosEventProducer, never()).publishStatusUpdated(any());
    verifyNoInteractions(messagingTemplate);
  }

  @Test
  void expiry_takesTheTicketLockBeforeReadingTheTicket() {
    overduePending();
    when(bidSosRepository.existsBySosRequestSosId(sosId)).thenReturn(true);

    expireIfDue();

    var order = inOrder(sosRequestRepository);
    order.verify(sosRequestRepository).lockStatus(sosId);
    order.verify(sosRequestRepository).findById(sosId);
  }

  @Test
  void expiry_ofATicketNoLongerPending_isANoOp() {
    for (String status : List.of("ACCEPTED", "EXPIRED", "CANCELLED")) {
      reset(sosRequestRepository, sosEventProducer, messagingTemplate);
      when(sosRequestRepository.lockStatus(sosId)).thenReturn(Optional.of(status));

      expireIfDue();

      verify(sosRequestRepository, never()).findById(any());
      verify(sosRequestRepository, never()).save(any());
      verifyNoInteractions(sosEventProducer, messagingTemplate);
    }
  }

  @Test
  void expiry_whenTheWindowWasRenewedSinceTheTimerWasSet_isANoOp() {
    SosRequest sos = overduePending();
    sos.setBiddingExpiresAt(LocalDateTime.now().plusSeconds(30));

    expireIfDue();

    assertThat(sos.getStatus()).isEqualTo(SosStatus.PENDING);
    verify(sosRequestRepository, never()).save(any());
    verifyNoInteractions(sosEventProducer, messagingTemplate);
  }

  @Test
  void expiry_runTwice_announcesOnce() {
    SosRequest sos = overduePending();
    when(bidSosRepository.existsBySosRequestSosId(sosId)).thenReturn(true);

    expireIfDue();
    // The second run (timer plus sweep) sees the committed EXPIRED under the lock.
    when(sosRequestRepository.lockStatus(sosId)).thenReturn(Optional.of(sos.getStatus().name()));
    expireIfDue();

    verify(sosEventProducer, times(1)).publishStatusUpdated(any());
    verify(messagingTemplate, times(1))
        .convertAndSendToUser(eq(userId.toString()), eq("/queue/sos-updates"), any());
  }

  @Test
  void sweep_expiresEveryOverdueTicket_andGoesOnAfterOneFails() {
    UUID brokenId = UUID.randomUUID();
    SosRequest broken = SosRequest.builder().sosId(brokenId).build();
    SosRequest sos = overduePending();
    when(sosRequestRepository.findByStatusAndBiddingExpiresAtBefore(eq(SosStatus.PENDING), any()))
        .thenReturn(List.of(broken, sos));
    when(sosRequestRepository.lockStatus(brokenId)).thenThrow(new RuntimeException("lock timeout"));
    when(bidSosRepository.existsBySosRequestSosId(sosId)).thenReturn(true);

    sosService.sweepOverdueExpiry();

    assertThat(sos.getStatus()).isEqualTo(SosStatus.EXPIRED);
    verify(sosEventProducer).publishStatusUpdated(any());
  }

  @Test
  void expiryTimer_runsTheLockedStep_andSwallowsAFailure() {
    SosRequest sos = sosAt(SosStatus.PENDING);
    sos.setBiddingExpiresAt(LocalDateTime.now().plusSeconds(60));
    ReflectionTestUtils.invokeMethod(sosService, "scheduleBiddingExpiry", sos);
    ArgumentCaptor<Runnable> timer = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler).schedule(timer.capture(), any(java.time.Instant.class));
    when(sosRequestRepository.lockStatus(sosId)).thenThrow(new RuntimeException("db down"));

    timer.getValue().run(); // must not throw into the scheduler thread

    verify(sosRequestRepository).lockStatus(sosId);
    verify(sosRequestRepository, never()).save(any());
  }

  @Test
  void rebroadcast_startsACleanRound_byDeletingTheLastRoundsRejectedBids() {
    expiredSosWithRadius();

    sosService.rebroadcast(sosId, userId);

    verify(bidSosRepository).deleteBySosRequestSosIdAndStatus(sosId, BidStatus.REJECTED);
  }

  private final java.util.Map<UUID, Mechanic> knownMechanics = new java.util.HashMap<>();

  /**
   * Ids for the geo-filter stubs; remembers the entities so findAllWithRateById can return them.
   */
  // ── arrival confirmation: the customer answers, or the window runs out ────────────

  private void stubCustomerAnswer(SosStatusHistory history, SosRequest sos) {
    when(sosStatusHistoryRepository.findById(historyId)).thenReturn(Optional.of(history));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    lenient().when(sosMapper.toResponse(sos)).thenReturn(sosResponse);
  }

  @Test
  void approveStatusChange_arrivalConfirmed_marksArrived_withoutAPushToTheCustomerWhoTappedIt() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    SosStatusHistory h = waitingArrival(sos, 10);
    stubCustomerAnswer(h, sos);
    stubAssignedMechanic(sos);

    sosService.approveStatusChange(
        new ApproveStatusChangeRequest(userId, historyId, ApprovedStatus.ACCEPTED));

    assertThat(sos.getStatus()).isEqualTo(SosStatus.ARRIVED);
    assertThat(h.getApprovedStatus()).isEqualTo(ApprovedStatus.ACCEPTED);
    assertThat(h.getApprovedBy()).isEqualTo(user);
    assertThat(h.getApproveAt()).as("the wait starts here").isNotNull();
    verify(routeEtaService).clear(sosId);
    verify(sosEventProducer).publishStatusUpdated(any(SosStatusUpdatedEvent.class));
    verify(sosEventProducer, never()).publishNotification(any());
  }

  @Test
  void approveStatusChange_arrivalRejected_keepsTheTicketOnTheWay_andTellsTheMechanic() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    SosStatusHistory h = waitingArrival(sos, 10);
    stubCustomerAnswer(h, sos);

    sosService.approveStatusChange(
        new ApproveStatusChangeRequest(userId, historyId, ApprovedStatus.REJECTED));

    assertThat(sos.getStatus()).isEqualTo(SosStatus.ON_THE_WAY);
    assertThat(h.getApprovedStatus()).isEqualTo(ApprovedStatus.REJECTED);
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(mechanicId.toString()),
            eq("/queue/sos-updates"),
            eq(Map.of("type", "ARRIVAL_REJECTED", "sosId", sosId, "historyId", historyId)));
    verify(sosEventProducer)
        .publishNotification(
            argThat(
                n -> n.type().equals("ARRIVAL_REJECTED") && n.recipientId().equals(mechanicId)));
    verify(sosEventProducer, never()).publishStatusUpdated(any());
  }

  // The job was released and taken by someone else after the pop-up went out: the old request must
  // not move the new mechanic's ticket.
  @Test
  void approveStatusChange_arrivalOfAMechanicWhoIsNoLongerAssigned_isRefused() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    SosStatusHistory h = waitingArrival(sos, 10);
    stubCustomerAnswer(h, sos);
    when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(Optional.of(bidBy(UUID.randomUUID(), BidStatus.ACCEPTED, sos)));

    assertThatThrownBy(
            () ->
                sosService.approveStatusChange(
                    new ApproveStatusChangeRequest(userId, historyId, ApprovedStatus.ACCEPTED)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer valid");

    assertThat(sos.getStatus()).isEqualTo(SosStatus.ON_THE_WAY);
  }

  @Test
  void approveOverdueCompletions_unansweredArrival_isConfirmedAfterTheArrivalWindow() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    SosStatusHistory h = waitingArrival(sos, 61);
    stubOverdue(h);
    stubAssignedMechanic(sos);
    when(sosStatusHistoryRepository.findApprovedStatusById(h.getId()))
        .thenReturn(Optional.of(ApprovedStatus.PENDING_APPROVAL));

    sosService.approveOverdueCompletions();

    assertThat(sos.getStatus()).isEqualTo(SosStatus.ARRIVED);
    assertThat(h.getApprovedStatus()).isEqualTo(ApprovedStatus.ACCEPTED);
    assertThat(h.getApprovedBy()).as("confirmed by the system").isNull();
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(userId.toString()),
            eq("/queue/sos-updates"),
            eq(Map.of("type", "ARRIVAL_AUTO_CONFIRMED", "sosId", sosId)));
    // A customer who did not answer is the one who needs to hear the mechanic is there.
    verify(sosEventProducer)
        .publishNotification(
            argThat(n -> n.type().equals("STATUS_ARRIVED") && n.recipientId().equals(userId)));
  }

  @Test
  void approveOverdueCompletions_arrivalStillInsideItsWindow_isLeftAlone() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    SosStatusHistory h = waitingArrival(sos, 30);
    when(sosStatusHistoryRepository.findByApprovedStatusAndProposedAtBefore(any(), any()))
        .thenReturn(List.of(h));

    sosService.approveOverdueCompletions();

    assertThat(sos.getStatus()).isEqualTo(SosStatus.ON_THE_WAY);
    assertThat(h.getApprovedStatus()).isEqualTo(ApprovedStatus.PENDING_APPROVAL);
    verify(sosRequestRepository, never()).save(any(SosRequest.class));
  }

  // The arrival window is shorter than the completion one; a completion asked 100 s ago still
  // waits.
  @Test
  void approveOverdueCompletions_completionKeepsItsOwnLongerWindow() {
    SosRequest sos = inProgressSosOwnedByUser();
    SosStatusHistory h = waitingCompletion(sos);
    h.setProposedBy(mechanic);
    h.setProposedAt(LocalDateTime.now().minusSeconds(100));
    when(sosStatusHistoryRepository.findByApprovedStatusAndProposedAtBefore(any(), any()))
        .thenReturn(List.of(h));

    sosService.approveOverdueCompletions();

    assertThat(sos.getStatus()).isEqualTo(SosStatus.IN_PROGRESS);
    assertThat(h.getApprovedStatus()).isEqualTo(ApprovedStatus.PENDING_APPROVAL);
  }

  // The job moved on while the pop-up waited: close the request so the sweep stops reading it.
  @Test
  void approveOverdueCompletions_arrivalOnATicketThatMovedOn_isClosed_andChangesNothing() {
    SosRequest sos = sosAt(SosStatus.CANCELLED);
    SosStatusHistory h = waitingArrival(sos, 90);
    stubOverdue(h);
    when(sosStatusHistoryRepository.findApprovedStatusById(h.getId()))
        .thenReturn(Optional.of(ApprovedStatus.PENDING_APPROVAL));

    sosService.approveOverdueCompletions();

    assertThat(sos.getStatus()).isEqualTo(SosStatus.CANCELLED);
    assertThat(h.getApprovedStatus()).isEqualTo(ApprovedStatus.REJECTED);
    verify(sosEventProducer, never()).publishStatusUpdated(any());
    verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
  }

  @Test
  void release_closesAnArrivalTheCustomerHasNotAnsweredYet() {
    SosRequest sos = releasableSos(SosStatus.ON_THE_WAY);
    acceptedBidOfAssignedMechanic(sos);
    SosStatusHistory waiting = waitingArrival(sos, 10);
    when(sosStatusHistoryRepository.findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
            sosId, ApprovedStatus.PENDING_APPROVAL))
        .thenReturn(Optional.of(waiting));

    sosService.releaseByMechanic(sosId, mechanicId);

    assertThat(waiting.getApprovedStatus()).isEqualTo(ApprovedStatus.REJECTED);
    verify(sosStatusHistoryRepository).save(waiting);
  }

  @Test
  void getById_owner_seesTheHistoryIdOfAWaitingArrival() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(sosMapper.toResponse(sos)).thenReturn(sosResponse);
    when(sosStatusHistoryRepository.findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
            sosId, ApprovedStatus.PENDING_APPROVAL))
        .thenReturn(Optional.of(waitingArrival(sos, 10)));

    assertThat(sosService.getById(sosId, userId).pendingCompletionHistoryId()).isEqualTo(historyId);
  }

  // ── the customer never came: the mechanic ends the job after the wait ─────────────

  // Lenient throughout: the refusal tests stop before they reach every one of these.
  private SosRequest arrivedForNoShow(long arrivedMinutesAgo) {
    SosRequest sos = sosAt(SosStatus.ARRIVED);
    lenient().when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    lenient().when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    lenient().when(sosMapper.toResponse(sos)).thenReturn(sosResponse);
    lenient()
        .when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(Optional.of(bidBy(mechanicId, BidStatus.ACCEPTED, sos)));
    SosStatusHistory arrived =
        SosStatusHistory.builder()
            .sosRequest(sos)
            .fromStatus(SosStatus.ON_THE_WAY)
            .toStatus(SosStatus.ARRIVED)
            .proposedBy(mechanic)
            .approvedStatus(ApprovedStatus.ACCEPTED)
            .approveAt(LocalDateTime.now().minusMinutes(arrivedMinutesAgo))
            .build();
    lenient()
        .when(
            sosStatusHistoryRepository
                .findFirstBySosRequestSosIdAndToStatusAndApprovedStatusOrderByChangeAtDesc(
                    sosId, SosStatus.ARRIVED, ApprovedStatus.ACCEPTED))
        .thenReturn(Optional.of(arrived));
    return sos;
  }

  @Test
  void reportCustomerNoShow_afterTheWait_cancelsTheJob_andTellsTheCustomer() {
    SosRequest sos = arrivedForNoShow(11);

    sosService.reportCustomerNoShow(sosId, mechanicId);

    assertThat(sos.getStatus()).isEqualTo(SosStatus.CANCELLED);
    verify(sosRequestRepository).lockStatus(sosId);
    verify(routeEtaService).clear(sosId);
    ArgumentCaptor<SosStatusHistory> history = ArgumentCaptor.forClass(SosStatusHistory.class);
    verify(sosStatusHistoryRepository).save(history.capture());
    assertThat(history.getValue().getFromStatus()).isEqualTo(SosStatus.ARRIVED);
    assertThat(history.getValue().getToStatus()).isEqualTo(SosStatus.CANCELLED);
    assertThat(history.getValue().getProposedBy()).isEqualTo(mechanic);
    assertThat(history.getValue().getApprovedStatus()).isEqualTo(ApprovedStatus.ACCEPTED);
    ArgumentCaptor<SosStatusUpdatedEvent> event =
        ArgumentCaptor.forClass(SosStatusUpdatedEvent.class);
    verify(sosEventProducer).publishStatusUpdated(event.capture());
    assertThat(event.getValue().fromStatus()).isEqualTo(SosStatus.ARRIVED);
    assertThat(event.getValue().toStatus()).isEqualTo(SosStatus.CANCELLED);
    verify(messagingTemplate)
        .convertAndSendToUser(
            eq(userId.toString()),
            eq("/queue/sos-updates"),
            eq(Map.of("type", "CUSTOMER_NO_SHOW", "sosId", sosId, "fromStatus", "ARRIVED")));
    verify(sosEventProducer)
        .publishNotification(
            argThat(n -> n.type().equals("CUSTOMER_NO_SHOW") && n.recipientId().equals(userId)));
  }

  @Test
  void reportCustomerNoShow_beforeTheWaitIsOver_saysHowLongIsLeft() {
    SosRequest sos = arrivedForNoShow(4);

    assertThatThrownBy(() -> sosService.reportCustomerNoShow(sosId, mechanicId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("6 more minute");

    assertThat(sos.getStatus()).isEqualTo(SosStatus.ARRIVED);
    verify(sosRequestRepository, never()).save(any(SosRequest.class));
    verify(sosEventProducer, never()).publishStatusUpdated(any());
  }

  @Test
  void reportCustomerNoShow_beforeARRIVED_isRefused() {
    SosRequest sos = arrivedForNoShow(30);
    sos.setStatus(SosStatus.ON_THE_WAY);

    assertThatThrownBy(() -> sosService.reportCustomerNoShow(sosId, mechanicId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("after ARRIVED");
  }

  @Test
  void reportCustomerNoShow_byAMechanicWhoIsNotAssigned_isDenied() {
    arrivedForNoShow(30);

    assertThatThrownBy(() -> sosService.reportCustomerNoShow(sosId, UUID.randomUUID()))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

    verify(sosRequestRepository, never()).save(any(SosRequest.class));
  }

  // "Mark arrived, drive off, report a no-show" must not work.
  @Test
  void reportCustomerNoShow_whenTheMechanicHasLeft_isRefused() {
    SosRequest sos = arrivedForNoShow(30);
    mechanic.setCurrentLocation(GEO.createPoint(new Coordinate(103.8600, 13.3600))); // Siem Reap

    assertThatThrownBy(() -> sosService.reportCustomerNoShow(sosId, mechanicId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stay within 50m");

    assertThat(sos.getStatus()).isEqualTo(SosStatus.ARRIVED);
  }

  @Test
  void reportCustomerNoShow_withNoMechanicLocation_isRefused() {
    arrivedForNoShow(30);
    mechanic.setCurrentLocation(null);

    assertThatThrownBy(() -> sosService.reportCustomerNoShow(sosId, mechanicId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No location");
  }

  @Test
  void reportCustomerNoShow_whenTheWaitIsSetToZero_isOff() {
    ReflectionTestUtils.setField(sosService, "customerWaitMinutes", 0);

    assertThatThrownBy(() -> sosService.reportCustomerNoShow(sosId, mechanicId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is off");

    verify(sosRequestRepository, never()).lockStatus(any());
  }

  // ── getHistory: the job timeline, for the owner and the accepted mechanic ──────────

  private SosStatusHistory historyRow(
      SosStatus from,
      SosStatus to,
      ApprovedStatus decision,
      LocalDateTime changedAt,
      LocalDateTime decidedAt) {
    return SosStatusHistory.builder()
        .fromStatus(from)
        .toStatus(to)
        .approvedStatus(decision)
        .changeAt(changedAt)
        .approveAt(decidedAt)
        .proposedBy(mechanic)
        .build();
  }

  @Test
  void getHistory_owner_getsTheEntriesInOrder_withTheTimeTheStepTookEffect() {
    SosRequest sos = sosAt(SosStatus.ON_THE_WAY);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    stubAssignedMechanic(sos);
    LocalDateTime t = LocalDateTime.of(2026, 10, 7, 10, 0);
    when(sosStatusHistoryRepository.findBySosRequestSosIdOrderByChangeAtAsc(sosId))
        .thenReturn(
            List.of(
                historyRow(SosStatus.PENDING, SosStatus.ACCEPTED, ApprovedStatus.ACCEPTED, t, t),
                historyRow(
                    SosStatus.ACCEPTED,
                    SosStatus.ON_THE_WAY,
                    ApprovedStatus.ACCEPTED,
                    t.plusMinutes(3),
                    t.plusMinutes(3)),
                // Rejected, then asked again and still waiting: both show, with their decision.
                historyRow(
                    SosStatus.ON_THE_WAY,
                    SosStatus.ARRIVED,
                    ApprovedStatus.REJECTED,
                    t.plusMinutes(10),
                    t.plusMinutes(11)),
                historyRow(
                    SosStatus.ON_THE_WAY,
                    SosStatus.ARRIVED,
                    ApprovedStatus.PENDING_APPROVAL,
                    t.plusMinutes(12),
                    null)));

    List<SosStatusHistoryEntry> entries = sosService.getHistory(sosId, userId);

    assertThat(entries)
        .extracting(
            SosStatusHistoryEntry::fromStatus,
            SosStatusHistoryEntry::toStatus,
            SosStatusHistoryEntry::at,
            SosStatusHistoryEntry::decision)
        .containsExactly(
            tuple(SosStatus.PENDING, SosStatus.ACCEPTED, t, ApprovedStatus.ACCEPTED),
            tuple(
                SosStatus.ACCEPTED,
                SosStatus.ON_THE_WAY,
                t.plusMinutes(3),
                ApprovedStatus.ACCEPTED),
            tuple(
                SosStatus.ON_THE_WAY,
                SosStatus.ARRIVED,
                t.plusMinutes(11),
                ApprovedStatus.REJECTED),
            tuple(
                SosStatus.ON_THE_WAY,
                SosStatus.ARRIVED,
                t.plusMinutes(12),
                ApprovedStatus.PENDING_APPROVAL));
  }

  // A customer cancel writes no row; the timeline still ends with it, at the cancel's update time.
  @Test
  void getHistory_cancelledBeforeAnyAccept_endsWithPendingToCancelled() {
    SosRequest sos = sosAt(SosStatus.CANCELLED);
    LocalDateTime t = LocalDateTime.of(2026, 10, 7, 10, 0);
    sos.setUpdatedAt(t);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(sosStatusHistoryRepository.findBySosRequestSosIdOrderByChangeAtAsc(sosId))
        .thenReturn(List.of());

    assertThat(sosService.getHistory(sosId, userId))
        .extracting(
            SosStatusHistoryEntry::fromStatus,
            SosStatusHistoryEntry::toStatus,
            SosStatusHistoryEntry::at,
            SosStatusHistoryEntry::decision)
        .containsExactly(tuple(SosStatus.PENDING, SosStatus.CANCELLED, t, ApprovedStatus.ACCEPTED));
  }

  // The step starts from the last state that took effect, not from the arrival request the cancel
  // itself closed as REJECTED.
  @Test
  void getHistory_cancelledOnTheWay_appendsTheCancelAfterTheStoredSteps() {
    SosRequest sos = sosAt(SosStatus.CANCELLED);
    LocalDateTime t = LocalDateTime.of(2026, 10, 7, 10, 0);
    sos.setUpdatedAt(t.plusMinutes(20));
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(sosStatusHistoryRepository.findBySosRequestSosIdOrderByChangeAtAsc(sosId))
        .thenReturn(
            List.of(
                historyRow(SosStatus.PENDING, SosStatus.ACCEPTED, ApprovedStatus.ACCEPTED, t, t),
                historyRow(
                    SosStatus.ACCEPTED,
                    SosStatus.ON_THE_WAY,
                    ApprovedStatus.ACCEPTED,
                    t.plusMinutes(3),
                    t.plusMinutes(3)),
                historyRow(
                    SosStatus.ON_THE_WAY,
                    SosStatus.ARRIVED,
                    ApprovedStatus.REJECTED,
                    t.plusMinutes(10),
                    t.plusMinutes(20))));

    List<SosStatusHistoryEntry> entries = sosService.getHistory(sosId, userId);

    assertThat(entries).hasSize(4);
    assertThat(entries.get(3))
        .isEqualTo(
            new SosStatusHistoryEntry(
                SosStatus.ON_THE_WAY,
                SosStatus.CANCELLED,
                t.plusMinutes(20),
                ApprovedStatus.ACCEPTED));
  }

  // The no-show cancel writes its own ARRIVED -> CANCELLED row: no second step.
  @Test
  void getHistory_noShowCancelAlreadyHasItsRow_isNotDoubled() {
    SosRequest sos = sosAt(SosStatus.CANCELLED);
    LocalDateTime t = LocalDateTime.of(2026, 10, 7, 10, 0);
    sos.setUpdatedAt(t.plusMinutes(30));
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(sosStatusHistoryRepository.findBySosRequestSosIdOrderByChangeAtAsc(sosId))
        .thenReturn(
            List.of(
                historyRow(SosStatus.PENDING, SosStatus.ACCEPTED, ApprovedStatus.ACCEPTED, t, t),
                historyRow(
                    SosStatus.ARRIVED,
                    SosStatus.CANCELLED,
                    ApprovedStatus.ACCEPTED,
                    t.plusMinutes(30),
                    t.plusMinutes(30))));

    assertThat(sosService.getHistory(sosId, userId))
        .extracting(SosStatusHistoryEntry::toStatus)
        .containsExactly(SosStatus.ACCEPTED, SosStatus.CANCELLED);
  }

  // The mechanic whose bid was accepted reads the same list the customer does.
  @Test
  void getHistory_theMechanicWhoseBidWasAccepted_getsTheSameEntries() {
    SosRequest sos = sosAt(SosStatus.COMPLETED);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    stubAssignedMechanic(sos);
    LocalDateTime t = LocalDateTime.of(2026, 10, 7, 10, 0);
    when(sosStatusHistoryRepository.findBySosRequestSosIdOrderByChangeAtAsc(sosId))
        .thenReturn(
            List.of(
                historyRow(SosStatus.PENDING, SosStatus.ACCEPTED, ApprovedStatus.ACCEPTED, t, t)));

    assertThat(sosService.getHistory(sosId, mechanicId)).hasSize(1);
    assertThat(sosService.getHistory(sosId, userId)).hasSize(1);
  }

  // Not every bidder: the ticket rule lets a bidder read the ticket, the timeline is not theirs.
  @Test
  void getHistory_aMechanicWhoBidButWasNotChosen_getsNotFound() {
    SosRequest sos = sosAt(SosStatus.ACCEPTED);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    stubAssignedMechanic(sos); // someone else holds the accepted bid, not this caller

    assertThatThrownBy(() -> sosService.getHistory(sosId, UUID.randomUUID()))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining(sosId.toString());

    verify(sosStatusHistoryRepository, never()).findBySosRequestSosIdOrderByChangeAtAsc(any());
  }

  // A mechanic who released the job (or was released) has a CANCELLED bid: it is not their job now.
  @Test
  void getHistory_aMechanicWhoReleasedTheJob_getsNotFound() {
    SosRequest sos = sosAt(SosStatus.PENDING);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> sosService.getHistory(sosId, mechanicId))
        .isInstanceOf(EntityNotFoundException.class);

    verify(sosStatusHistoryRepository, never()).findBySosRequestSosIdOrderByChangeAtAsc(any());
  }

  @Test
  void getHistory_anotherDriver_getsNotFound() {
    SosRequest sos = sosAt(SosStatus.ACCEPTED);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> sosService.getHistory(sosId, UUID.randomUUID()))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void getHistory_unknownTicket_getsNotFound() {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> sosService.getHistory(sosId, userId))
        .isInstanceOf(EntityNotFoundException.class);
  }

  private List<UUID> ids(Mechanic... mechanics) {
    for (Mechanic m : mechanics) knownMechanics.put(m.getMechanicId(), m);
    return java.util.Arrays.stream(mechanics).map(Mechanic::getMechanicId).toList();
  }
}
