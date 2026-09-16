package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.SosStatusHistory;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
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
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.SosStatusHistoryRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.repository.VehicleRepository;
import com.autofik.sos.service.GoogleMapsService;
import com.autofik.sos.service.MedusaService;
import com.autofik.sos.service.RouteEtaService;
import com.autofik.sos.service.SosService;
import com.autofik.sos.util.GeoDistance;
import com.uber.h3core.H3Core;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation managing the lifecycles of emergency roadside requests (SOS). Employs a
 * deterministic finite-state machine for transitions, leverages an Uber H3 and PostGIS hybrid
 * geospatial fan-out framework to locate nearby mechanics, and manages active bidding timeouts
 * using dynamic scheduled tasks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SosServiceImpl implements SosService {

  // State-machine guard: only transitions listed here are allowed.
  // COMPLETED and EXPIRED are terminal states — they have no outgoing transitions.
  private static final Map<SosStatus, Set<SosStatus>> VALID_TRANSITIONS =
      Map.of(
          SosStatus.PENDING, Set.of(SosStatus.ACCEPTED, SosStatus.CANCELLED),
          SosStatus.ACCEPTED, Set.of(SosStatus.ON_THE_WAY, SosStatus.CANCELLED),
          SosStatus.ON_THE_WAY, Set.of(SosStatus.ARRIVED, SosStatus.CANCELLED),
          SosStatus.ARRIVED, Set.of(SosStatus.COMPLETED, SosStatus.CANCELLED));

  // Standard SRID 4326 geometry factory for GPS tracking (WGS 84 latitude/longitude)
  private static final GeometryFactory GEOMETRY_FACTORY =
      new GeometryFactory(new PrecisionModel(), 4326);
  // Resolution 7 ≈ 5 km² per H3 cell — fixed by what is stored in current_h3_index on Mechanic.
  // Changing this would require a schema + data migration; kRing radius is the adaptive variable
  // instead.
  private static final int H3_RESOLUTION = 7;
  // Center-to-center distance between adjacent resolution-7 cells ≈ edge * sqrt(3) ≈ 2.114 km.
  // Used to convert a km radius into the minimum kRing radius that covers it.
  private static final double H3_R7_CENTER_TO_CENTER_KM = 2.114;
  // Default broadcast radius used for fan-out when the SOS request has no user-defined limit.
  private static final int DEFAULT_BROADCAST_KM = 15;

  private static final String SOS_COOLDOWN_KEY_PREFIX = "sos:cooldown:";
  private static final Duration SOS_COOLDOWN = Duration.ofSeconds(60);

  // Collection of states indicating that a customer ticket is unresolved
  private static final List<SosStatus> ACTIVE_STATUSES =
      List.of(SosStatus.PENDING, SosStatus.ACCEPTED, SosStatus.ON_THE_WAY, SosStatus.ARRIVED);
  private static final H3Core H3;

  static {
    try {
      H3 = H3Core.newInstance();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final SosRequestRepository sosRequestRepository;
  private final UserRepository userRepository;
  private final VehicleRepository vehicleRepository;
  private final SsoProjection ssoProjection;
  private final MechanicRepository mechanicRepository;
  private final BidSosRepository bidSosRepository;
  private final SosStatusHistoryRepository sosStatusHistoryRepository;
  private final SosMapper sosMapper;
  private final SosStatusHistoryMapper sosStatusHistoryMapper;
  private final MechanicMapper mechanicMapper;
  private final GoogleMapsService googleMapsService;
  private final RouteEtaService routeEtaService;
  private final MedusaService medusaService;
  private final SosEventProducer sosEventProducer;
  private final SimpMessagingTemplate messagingTemplate;
  private final TaskScheduler taskScheduler;
  private final StringRedisTemplate redisTemplate;

  // How close a mechanic must be, by road, before they may mark ARRIVED. Configurable because
  // the right value depends on the road layout: a divided boulevard can put someone standing
  // beside the customer several hundred metres away by road.
  @Value("${sos.arrival.max-distance-meters:50}")
  private int arrivalMaxDistanceMeters;

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void expireOverduePendingRequests() {
    LocalDateTime now = LocalDateTime.now();

    List<SosRequest> overdue =
        sosRequestRepository.findByStatusAndBiddingExpiresAtBefore(SosStatus.PENDING, now);
    if (!overdue.isEmpty()) {
      log.warn("Expiring {} PENDING SOS request(s) missed while offline", overdue.size());
      overdue.forEach(this::doExpire);
      sosRequestRepository.saveAll(overdue);
    }

    List<SosRequest> active =
        sosRequestRepository.findByStatusAndBiddingExpiresAtAfter(SosStatus.PENDING, now);
    if (!active.isEmpty()) {
      log.info("Re-arming bidding expiry timer for {} PENDING SOS request(s)", active.size());
      active.forEach(this::scheduleBiddingExpiry);
    }
  }

  private void doExpire(SosRequest s) {
    s.setStatus(SosStatus.EXPIRED);
    UUID sosId = s.getSosId();
    UUID userId = s.getUser().getUserId();
    sosEventProducer.publishStatusUpdated(
        new SosStatusUpdatedEvent(
            sosId, null, userId, SosStatus.PENDING, SosStatus.EXPIRED, LocalDateTime.now()));
    boolean retriesLeft = s.getRebroadcastCount() < SosRequest.MAX_REBROADCAST;
    String body =
        retriesLeft
            ? "No mechanic was found. Tap to try again."
            : "No mechanic available after "
                + SosRequest.MAX_REBROADCAST
                + " attempts. Please call support.";
    sosEventProducer.publishNotification(
        new SosNotificationEvent(
            "BIDDING_EXPIRED", userId, "USER", sosId, "Bidding window closed", body));
    messagingTemplate.convertAndSendToUser(
        userId.toString(),
        "/queue/sos-updates",
        Map.of(
            "type",
            "BIDDING_EXPIRED",
            "sosId",
            sosId,
            "retriesLeft",
            retriesLeft,
            "attemptsLeft",
            SosRequest.MAX_REBROADCAST - s.getRebroadcastCount()));
  }

  /**
   * Spawns a new emergency roadside ticket. Enforces concurrency caps per client, tracks request
   * velocity via Redis, and pushes geo-queries out to dispatch mechanics.
   *
   * @param request Data transfer containing spatial positions, problems, and bounds.
   * @return Transformed SOS object detailing current status and assignments.
   * @throws IllegalStateException If the initiating user holds an active ticket.
   * @throws TooManyRequestsException If throttling window rules are broken.
   */
  @Override
  @Transactional
  public SosRequestResponse create(CreateSosRequest request) {
    // Enforce structural restriction: A user can only process one active distress event at a time
    if (sosRequestRepository.existsByUserUserIdAndStatusIn(request.userId(), ACTIVE_STATUSES)) {
      throw new IllegalStateException("You already have an active SOS request");
    }

    // Redis distributed lock prevents accidental double-tap double submissions
    Boolean allowed =
        redisTemplate
            .opsForValue()
            .setIfAbsent(SOS_COOLDOWN_KEY_PREFIX + request.userId(), "1", SOS_COOLDOWN);
    if (!Boolean.TRUE.equals(allowed)) {
      throw new TooManyRequestsException(
          "Please wait 60 seconds before submitting another SOS request");
    }

    String problemTypeKey = medusaService.resolveServiceGroupKey(request.serviceGroupId());

    // Both rows are projections of SSO. ponytail: no SSO round-trip for today's test (see
    // SsoProjection) — the vehicle lookup checks local ownership instead of SSO's per-user
    // scoping, so a driver still can't file a breakdown against a stranger's car.
    User user = ssoProjection.user(request.userId());
    Vehicle vehicle = ssoProjection.vehicle(request.vehicleId(), user);
    SosRequest sos =
        SosRequest.builder()
            .problemType(problemTypeKey)
            .description(request.description())
            .location(
                GEOMETRY_FACTORY.createPoint(
                    new Coordinate(request.longitude(), request.latitude())))
            .broadcastRadiusKm(request.broadcastRadiusKm())
            .user(user)
            .vehicle(vehicle)
            .build();
    sosRequestRepository.save(sos);

    // Geocode coordinates asynchronously to map a readable address
    String address = googleMapsService.reverseGeocode(request.latitude(), request.longitude());
    if (address != null) {
      sos.setLocationAddress(address);
      sosRequestRepository.save(sos);
    }

    // Broadcast out to standard Kafka topic pipelines
    sosEventProducer.publishSosCreated(
        new SosCreatedEvent(
            sos.getSosId(),
            user.getUserId(),
            vehicle.getId(),
            sos.getProblemType(),
            sos.getDescription(),
            request.latitude(),
            request.longitude(),
            sos.getLocationAddress(),
            sos.getCreatedAt()));

    // Spatial dispatch step to find target mechanics
    fanOutToNearbyMechanics(
        sos.getSosId(),
        request.latitude(),
        request.longitude(),
        problemTypeKey,
        request.broadcastRadiusKm());

    // Queue automated closing of the auction window if no responses come through
    scheduleBiddingExpiry(sos);

    return toResponseWithMechanic(sos);
  }

  @Override
  @Transactional(readOnly = true)
  public SosRequestResponse getById(UUID sosId, UUID callerId) {
    SosRequest sos = findSosOrThrow(sosId);
    // Not 403: telling an outsider "forbidden" tells them the id is real.
    if (!isVisibleTo(sos, callerId)) {
      throw new EntityNotFoundException("SOS request not found: " + sosId);
    }
    return toResponseWithMechanic(sos);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean canView(UUID sosId, UUID callerId) {
    return sosRequestRepository
        .findById(sosId)
        .map(sos -> isVisibleTo(sos, callerId))
        .orElse(false);
  }

  // The driver who raised it, an existing bidder, or an eligible mechanic during the bidding
  // window.
  // Eligibility matched the broadcast fan-out criteria so a mechanic can inspect an SOS before
  // bidding.
  private boolean isVisibleTo(SosRequest sos, UUID callerId) {
    if (sos.getUser().getUserId().equals(callerId)) {
      return true;
    }
    if (bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(sos.getSosId(), callerId)) {
      return true;
    }
    if (sos.getBiddingExpiresAt() == null
        || !LocalDateTime.now().isBefore(sos.getBiddingExpiresAt())) {
      return false;
    }
    return isEligibleMechanic(sos, callerId);
  }

  private boolean isEligibleMechanic(SosRequest sos, UUID callerId) {
    Point location = sos.getLocation();
    if (location == null || sos.getProblemType() == null) {
      return false;
    }

    double lat = location.getY();
    double lng = location.getX();

    int effectiveRadiusKm =
        sos.getBroadcastRadiusKm() != null ? sos.getBroadcastRadiusKm() : DEFAULT_BROADCAST_KM;
    String sosCell = H3.geoToH3Address(lat, lng, H3_RESOLUTION);
    List<String> searchCells = H3.kRing(sosCell, ringRadiusFor(effectiveRadiusKm));

    List<Mechanic> coarse =
        mechanicRepository.findByH3CellsAndSkillTag(searchCells, sos.getProblemType());

    if (coarse.isEmpty()) {
      return false;
    }

    List<UUID> ids = coarse.stream().map(Mechanic::getMechanicId).toList();

    List<Mechanic> matched =
        sos.getBroadcastRadiusKm() != null
            ? mechanicRepository.filterByRadiusAndBroadcast(
                ids, lat, lng, sos.getBroadcastRadiusKm() * 1000.0)
            : mechanicRepository.filterByRadius(ids, lat, lng);
    return matched.stream().anyMatch(mechanic -> mechanic.getMechanicId().equals(callerId));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SosRequestResponse> getByUser(UUID userId, Pageable pageable) {
    return toPageWithMechanic(sosRequestRepository.findByUserUserId(userId, pageable));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SosRequestResponse> getByUserAndStatus(
      UUID userId, SosStatus status, Pageable pageable) {
    return toPageWithMechanic(
        sosRequestRepository.findByUserUserIdAndStatus(userId, status, pageable));
  }

  // Direct status update (mechanic-initiated, no user approval needed).
  // Only ON_THE_WAY and ARRIVED flow through here; COMPLETED requires user approval via
  // proposeStatusChange.
  @Override
  @Transactional
  public SosRequestResponse updateStatus(UUID sosId, UUID mechanicId, SosStatus toStatus) {
    if (toStatus != SosStatus.ON_THE_WAY && toStatus != SosStatus.ARRIVED) {
      throw new IllegalStateException(
          "Only ON_THE_WAY and ARRIVED can be set directly. Use POST /api/v1/sos/{sosId}/completion for COMPLETED.");
    }
    SosRequest sos = findSosOrThrow(sosId);
    validateTransition(sos.getStatus(), toStatus);
    Mechanic mechanic =
        mechanicRepository
            .findById(mechanicId)
            .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
    requireAssignedMechanic(sos, mechanicId);

    if (toStatus == SosStatus.ARRIVED) {
      requireMechanicAtSos(mechanic, sos);
    }

    SosStatus fromStatus = sos.getStatus();
    sos.setStatus(toStatus);
    sosRequestRepository.save(sos);

    // The cached route is keyed only by sosId and outlives its 30s refresh window, so leaving
    // en route has to invalidate it eagerly. Without this, pings arriving inside the current
    // window keep interpolating against the stale snapshot and the customer watches a live
    // countdown for up to 30 seconds after the mechanic has already pulled up.
    // ON_THE_WAY is still en route; only ARRIVED ends the drive here.
    if (toStatus == SosStatus.ARRIVED) {
      routeEtaService.clear(sosId);
    }

    LocalDateTime now = LocalDateTime.now();
    SosStatusHistory history =
        SosStatusHistory.builder()
            .sosRequest(sos)
            .fromStatus(fromStatus)
            .toStatus(toStatus)
            .proposedBy(mechanic)
            .proposedAt(now)
            .approvedStatus(ApprovedStatus.ACCEPTED)
            .approveAt(now)
            .build();
    sosStatusHistoryRepository.save(history);

    // Inform external subscribers via Kafka and active WebSocket connections
    SosStatusUpdatedEvent statusEvent =
        new SosStatusUpdatedEvent(
            sos.getSosId(), mechanicId, sos.getUser().getUserId(), fromStatus, toStatus, now);
    sosEventProducer.publishStatusUpdated(statusEvent);
    messagingTemplate.convertAndSendToUser(
        sos.getUser().getUserId().toString(), "/queue/sos-updates", statusEvent);
    publishStatusNotifications(toStatus, sos, mechanicId);

    return toResponseWithMechanic(sos);
  }

  // COMPLETED requires explicit user approval to prevent fraud (mechanic marking done before
  // finishing).
  // This records a PENDING_APPROVAL history entry and notifies the user; approveStatusChange()
  // finalizes it.
  @Override
  @Transactional
  public SosStatusHistoryResponse proposeStatusChange(
      UUID sosId, ProposeStatusChangeRequest request) {
    if (request.toStatus() != SosStatus.COMPLETED) {
      throw new IllegalStateException(
          "Only COMPLETED requires customer approval. Use PATCH /api/v1/sos/{sosId}/status for ON_THE_WAY and ARRIVED.");
    }
    SosRequest sos = findSosOrThrow(sosId);
    validateTransition(sos.getStatus(), request.toStatus());
    Mechanic mechanic =
        mechanicRepository
            .findById(request.mechanicId())
            .orElseThrow(
                () -> new EntityNotFoundException("Mechanic not found: " + request.mechanicId()));
    requireAssignedMechanic(sos, request.mechanicId());
    SosStatusHistory history =
        SosStatusHistory.builder()
            .sosRequest(sos)
            .fromStatus(sos.getStatus())
            .toStatus(request.toStatus())
            .proposedBy(mechanic)
            .proposedAt(LocalDateTime.now())
            .approvedStatus(ApprovedStatus.PENDING_APPROVAL)
            .build();
    sosStatusHistoryRepository.save(history);

    messagingTemplate.convertAndSendToUser(
        sos.getUser().getUserId().toString(),
        "/queue/sos-updates",
        Map.of(
            "type", "COMPLETION_REQUESTED", "sosId", sos.getSosId(), "historyId", history.getId()));
    sosEventProducer.publishNotification(
        new SosNotificationEvent(
            "COMPLETION_REQUESTED",
            sos.getUser().getUserId(),
            "USER",
            sos.getSosId(),
            "Completion requested",
            "Your mechanic has marked the service complete. Please approve."));
    return sosStatusHistoryMapper.toResponse(history);
  }

  /** Finalizes or discards a pending milestone update based on the client's explicit response. */
  @Override
  @Transactional
  public SosRequestResponse approveStatusChange(ApproveStatusChangeRequest request) {
    SosStatusHistory history =
        sosStatusHistoryRepository
            .findById(request.historyId())
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        "Status history not found: " + request.historyId()));
    User user =
        userRepository
            .findById(request.userId())
            .orElseThrow(() -> new EntityNotFoundException("User not found: " + request.userId()));
    // The historyId is the only thing the caller supplies — without this, any driver could
    // approve or reject the completion of anyone else's job.
    if (!history.getSosRequest().getUser().getUserId().equals(request.userId())) {
      throw new AccessDeniedException("You can only respond to your own SOS request");
    }

    // A decided history entry cannot be decided again. ACCEPTED-then-ACCEPTED is incidentally
    // blocked by validateTransition below once the sos is already COMPLETED, but ACCEPTED-then-
    // REJECTED (or the reverse) is not — without this, the outcome could be flipped after the
    // fact, silently re-notifying the mechanic.
    if (history.getApprovedStatus() != ApprovedStatus.PENDING_APPROVAL) {
      throw new IllegalStateException(
          "This completion request was already " + history.getApprovedStatus());
    }

    history.setApprovedStatus(request.approvedStatus());
    history.setApprovedBy(user);
    history.setApproveAt(LocalDateTime.now());
    sosStatusHistoryRepository.save(history);

    SosRequest sos = history.getSosRequest();
    if (request.approvedStatus() == ApprovedStatus.ACCEPTED) {
      SosStatus fromStatus = sos.getStatus();
      validateTransition(fromStatus, history.getToStatus());
      sos.setStatus(history.getToStatus());
      sosRequestRepository.save(sos);
      routeEtaService.clear(sos.getSosId()); // COMPLETED — no drive left to count down

      UUID mechanicId = history.getProposedBy().getMechanicId();
      SosStatusUpdatedEvent statusEvent =
          new SosStatusUpdatedEvent(
              sos.getSosId(),
              mechanicId,
              user.getUserId(),
              fromStatus,
              history.getToStatus(),
              LocalDateTime.now());
      sosEventProducer.publishStatusUpdated(statusEvent);
      messagingTemplate.convertAndSendToUser(
          mechanicId.toString(), "/queue/sos-updates", statusEvent);

      publishStatusNotifications(history.getToStatus(), sos, mechanicId);
    } else if (request.approvedStatus() == ApprovedStatus.REJECTED) {
      UUID mechanicId = history.getProposedBy().getMechanicId();
      messagingTemplate.convertAndSendToUser(
          mechanicId.toString(),
          "/queue/sos-updates",
          Map.of(
              "type",
              "COMPLETION_REJECTED",
              "sosId",
              sos.getSosId(),
              "historyId",
              history.getId()));
      sosEventProducer.publishNotification(
          new SosNotificationEvent(
              "COMPLETION_REJECTED",
              mechanicId,
              "MECHANIC",
              sos.getSosId(),
              "Completion rejected",
              "The customer has rejected the service completion request."));
    }
    return toResponseWithMechanic(sos);
  }

  private void publishStatusNotifications(SosStatus toStatus, SosRequest sos, UUID mechanicId) {
    UUID userId = sos.getUser().getUserId();
    switch (toStatus) {
      case ON_THE_WAY ->
          sosEventProducer.publishNotification(
              new SosNotificationEvent(
                  "STATUS_ON_THE_WAY",
                  userId,
                  "USER",
                  sos.getSosId(),
                  "Mechanic is on the way",
                  "Your mechanic is heading to your location"));
      case ARRIVED ->
          sosEventProducer.publishNotification(
              new SosNotificationEvent(
                  "STATUS_ARRIVED",
                  userId,
                  "USER",
                  sos.getSosId(),
                  "Mechanic has arrived",
                  "Your mechanic has arrived at your location"));
      case COMPLETED -> {
        sosEventProducer.publishNotification(
            new SosNotificationEvent(
                "STATUS_COMPLETED",
                userId,
                "USER",
                sos.getSosId(),
                "Service completed",
                "Your repair has been completed"));
        sosEventProducer.publishNotification(
            new SosNotificationEvent(
                "STATUS_COMPLETED",
                mechanicId,
                "MECHANIC",
                sos.getSosId(),
                "Service completed",
                "The service has been marked completed"));
      }
      default -> {}
    }
  }

  /** Terminates an outstanding ticket before work completion. */
  @Override
  @Transactional
  public SosRequestResponse cancel(UUID sosId, UUID userId) {
    SosRequest sos = findSosOrThrow(sosId);
    if (!sos.getUser().getUserId().equals(userId)) {
      throw new AccessDeniedException("You can only cancel your own SOS request");
    }
    SosStatus fromStatus = sos.getStatus();
    if (!VALID_TRANSITIONS.containsKey(fromStatus)) {
      throw new IllegalStateException("Cannot cancel a request with status: " + fromStatus);
    }
    sos.setStatus(SosStatus.CANCELLED);
    sosRequestRepository.save(sos);
    routeEtaService.clear(sosId); // job is over — drop any countdown still cached

    sosEventProducer.publishStatusUpdated(
        new SosStatusUpdatedEvent(
            sos.getSosId(), null, userId, fromStatus, SosStatus.CANCELLED, LocalDateTime.now()));
    sosEventProducer.publishNotification(
        new SosNotificationEvent(
            "STATUS_CANCELLED",
            userId,
            "USER",
            sos.getSosId(),
            "SOS request cancelled",
            "Your SOS request has been cancelled"));

    return toResponseWithMechanic(sos);
  }

  // SosRequest has no direct mechanic relation — the assigned mechanic is resolved
  // from the ACCEPTED bid on this SOS request (at most one can exist).
  private SosRequestResponse toResponseWithMechanic(SosRequest sos) {
    SosRequestResponse base = sosMapper.toResponse(sos);
    MechanicResponse mechanic =
        bidSosRepository
            .findFirstBySosRequestSosIdAndStatus(sos.getSosId(), BidStatus.ACCEPTED)
            .map(bid -> mechanicMapper.toResponse(bid.getMechanic()))
            .orElse(null);
    return new SosRequestResponse(
        base.sosId(),
        base.problemType(),
        base.description(),
        base.locationAddress(),
        base.status(),
        base.latitude(),
        base.longitude(),
        base.broadcastRadiusKm(),
        base.createdAt(),
        base.updatedAt(),
        base.biddingExpiresAt(),
        base.bidDecisionExpiresAt(),
        base.rebroadcastCount(),
        base.user(),
        base.vehicle(),
        mechanic);
  }

  private List<SosRequestResponse> toResponseListWithMechanic(List<SosRequest> sosList) {
    return sosList.stream().map(this::toResponseWithMechanic).toList();
  }

  private Page<SosRequestResponse> toPageWithMechanic(Page<SosRequest> page) {
    return page.map(this::toResponseWithMechanic);
  }

  // Advancing a job belongs to the mechanic whose bid was accepted, not to whoever knows the
  // sosId. Without this any mechanic could walk someone else's ticket to ARRIVED or COMPLETED.
  private void requireAssignedMechanic(SosRequest sos, UUID mechanicId) {
    boolean assigned =
        bidSosRepository
            .findFirstBySosRequestSosIdAndStatus(sos.getSosId(), BidStatus.ACCEPTED)
            .map(bid -> bid.getMechanic().getMechanicId().equals(mechanicId))
            .orElse(false);
    if (!assigned) {
      throw new AccessDeniedException("You are not the mechanic assigned to this SOS request");
    }
  }

  private SosRequest findSosOrThrow(UUID sosId) {
    return sosRequestRepository
        .findById(sosId)
        .orElseThrow(() -> new EntityNotFoundException("SOS request not found: " + sosId));
  }

  /** Compares the target state transition against the allowed matrix rules. */
  /**
   * Gate on ON_THE_WAY → ARRIVED: the mechanic must actually be at the breakdown.
   *
   * <p>Measured against current_location in Postgres, never against coordinates supplied with the
   * request — a mechanic controls those and would simply send the customer's own position. That
   * makes this a guard against the casual "close enough, mark it arrived" tap rather than against a
   * deliberately spoofed GPS feed, which only client attestation can address.
   *
   * <p>Road distance rather than straight-line, so a mechanic who is metres away across a river or
   * below an overpass — and cannot actually reach the customer — does not pass. The trade is that
   * being parked across a divided road can read several hundred metres, so the threshold is
   * configurable; see sos.arrival.max-distance-meters.
   */
  private void requireMechanicAtSos(Mechanic mechanic, SosRequest sos) {
    Point mechanicLocation = mechanic.getCurrentLocation();
    if (mechanicLocation == null) {
      throw new IllegalStateException(
          "Cannot mark arrived: no location on record for this mechanic. "
              + "Enable location sharing and try again.");
    }

    DistanceInfo route = googleMapsService.getDistanceInfo(mechanicLocation, sos.getLocation());

    // Maps unavailable or no drivable route: fall back to straight-line rather than blocking
    // every arrival in the country during an outage. Straight-line never exceeds road distance,
    // so the fallback is the more permissive of the two — it fails toward letting a mechanic
    // who is genuinely present complete the job.
    double distanceMeters;
    if (route != null) {
      distanceMeters = route.distanceMeters();
    } else {
      distanceMeters =
          GeoDistance.haversineMeters(
              mechanicLocation.getY(), mechanicLocation.getX(),
              sos.getLocation().getY(), sos.getLocation().getX());
      log.warn(
          "Route distance unavailable for SOS {}; falling back to straight-line {}m",
          sos.getSosId(),
          Math.round(distanceMeters));
    }

    if (distanceMeters > arrivalMaxDistanceMeters) {
      throw new IllegalStateException(
          String.format(
              "Cannot mark arrived: %dm from the customer, which exceeds the %dm limit.",
              Math.round(distanceMeters), arrivalMaxDistanceMeters));
    }
  }

  private void validateTransition(SosStatus from, SosStatus to) {
    Set<SosStatus> allowed = VALID_TRANSITIONS.getOrDefault(from, Set.of());
    if (!allowed.contains(to)) {
      throw new IllegalStateException("Invalid status transition: " + from + " → " + to);
    }
  }

  // Two-stage geo-search to notify relevant mechanics when a new SOS is created.
  // Stage 1 (H3 index): fast B-tree scan to get candidates in nearby hex cells.
  // Stage 2 (PostGIS): exact ST_DWithin check against each mechanic's service_radius_m.
  // recipientId=null in the notification signals a fan-out (no specific OneSignal push).
  private void fanOutToNearbyMechanics(
      UUID sosId, double lat, double lng, String problemType, Integer broadcastRadiusKm) {
    int effectiveRadiusKm = broadcastRadiusKm != null ? broadcastRadiusKm : DEFAULT_BROADCAST_KM;
    String sosCell = H3.geoToH3Address(lat, lng, H3_RESOLUTION);
    List<String> searchCells = H3.kRing(sosCell, ringRadiusFor(effectiveRadiusKm));

    List<Mechanic> coarse = mechanicRepository.findByH3CellsAndSkillTag(searchCells, problemType);
    if (coarse.isEmpty()) return;

    List<UUID> ids = coarse.stream().map(Mechanic::getMechanicId).toList();
    List<Mechanic> matched =
        broadcastRadiusKm != null
            ? mechanicRepository.filterByRadiusAndBroadcast(
                ids, lat, lng, broadcastRadiusKm * 1000.0)
            : mechanicRepository.filterByRadius(ids, lat, lng);

    SosNotificationEvent notification =
        new SosNotificationEvent(
            "SOS_CREATED",
            null,
            "MECHANIC",
            sosId,
            "New SOS request nearby",
            "Problem: " + problemType);
    matched.forEach(
        m ->
            messagingTemplate.convertAndSendToUser(
                m.getMechanicId().toString(), "/queue/sos-new-request", notification));
  }

  // Converts a km radius into the minimum kRing depth that fully covers it at resolution 7.
  // +1 ring of buffer accounts for partial hex overlap at the boundary.
  private static int ringRadiusFor(int radiusKm) {
    return (int) Math.ceil(radiusKm / H3_R7_CENTER_TO_CENTER_KM) + 1;
  }

  // Uses TaskScheduler (not @Scheduled) because each SOS has its own unique expiry time.
  // The lambda re-fetches the entity inside the transaction to avoid stale-state issues.
  @Override
  @Transactional
  public SosRequestResponse rebroadcast(UUID sosId, UUID userId) {
    SosRequest sos = findSosOrThrow(sosId);

    if (!sos.getUser().getUserId().equals(userId)) {
      throw new AccessDeniedException("You can only rebroadcast your own SOS request");
    }
    if (sos.getStatus() != SosStatus.EXPIRED) {
      throw new IllegalStateException("Only EXPIRED SOS requests can be rebroadcast");
    }
    if (sos.getRebroadcastCount() >= SosRequest.MAX_REBROADCAST) {
      throw new TooManyRequestsException(
          "Maximum rebroadcast attempts ("
              + SosRequest.MAX_REBROADCAST
              + ") reached. Please call support.");
    }

    sos.setStatus(SosStatus.PENDING);
    sos.setRebroadcastCount(sos.getRebroadcastCount() + 1);
    sos.setBiddingExpiresAt(LocalDateTime.now().plusSeconds(SosRequest.BIDDING_WINDOW_SECONDS));
    sosRequestRepository.save(sos);
    // Back to bidding: the previous mechanic's route is meaningless now.
    routeEtaService.clear(sosId);

    int attemptsLeft = SosRequest.MAX_REBROADCAST - sos.getRebroadcastCount();
    messagingTemplate.convertAndSendToUser(
        userId.toString(),
        "/queue/sos-updates",
        Map.of(
            "type",
            "SOS_REBROADCAST",
            "sosId",
            sosId,
            "rebroadcastCount",
            sos.getRebroadcastCount(),
            "attemptsLeft",
            attemptsLeft));

    fanOutToNearbyMechanics(
        sosId,
        sos.getLocation().getY(),
        sos.getLocation().getX(),
        sos.getProblemType(),
        sos.getBroadcastRadiusKm());

    scheduleBiddingExpiry(sos);

    return toResponseWithMechanic(sos);
  }

  /**
   * Registers an isolated thread execution wrapper targeting the exact timestamp the ticket closes.
   * The internal lambda re-fetches the database record at trigger time to eliminate race conditions
   * or dirty-write status tracking.
   */
  private void scheduleBiddingExpiry(SosRequest sos) {
    if (sos.getBiddingExpiresAt() == null) return;
    UUID sosId = sos.getSosId();
    taskScheduler.schedule(
        () -> {
          sosRequestRepository
              .findById(sosId)
              .ifPresent(
                  s -> {
                    if (s.getStatus() != SosStatus.PENDING) return;
                    doExpire(s);
                    sosRequestRepository.save(s);
                  });
        },
        sos.getBiddingExpiresAt().atZone(ZoneId.systemDefault()).toInstant());
  }
}
