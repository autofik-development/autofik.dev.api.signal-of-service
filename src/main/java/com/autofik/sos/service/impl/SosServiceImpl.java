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
import com.autofik.sos.dto.external.EtaInfo;
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
import com.autofik.sos.service.SosMediaService;
import com.autofik.sos.service.SosService;
import com.autofik.sos.util.GeoDistance;
import com.uber.h3core.H3Core;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
          SosStatus.ARRIVED, Set.of(SosStatus.IN_PROGRESS, SosStatus.CANCELLED),
          SosStatus.IN_PROGRESS, Set.of(SosStatus.COMPLETED, SosStatus.CANCELLED));

  // Standard SRID 4326 geometry factory for GPS tracking (WGS 84 latitude/longitude)
  private static final GeometryFactory GEOMETRY_FACTORY =
      new GeometryFactory(new PrecisionModel(), 4326);

  // Resolution 7 ≈ 5 km² per H3 cell — fixed by what is stored in current_h3_index on Mechanic.
  // Changing this would require a schema + data migration; kRing radius is the adaptive variable
  // instead.
  @Value("${sos.h3.resolution:7}")
  private int h3Resolution;

  // Center-to-center distance between adjacent resolution-7 cells ≈ edge * sqrt(3) ≈ 2.114 km.
  // Used to convert a km radius into the minimum kRing radius that covers it.
  @Value("${sos.h3.center-to-center-km:2.114}")
  private double h3CenterToCenterKm;

  @Value("${sos.geo.earth-radius-m:6371000}")
  private double earthRadiusM;

  // A stored offer estimate older than this is treated as stale and re-fetched (and re-billed)
  // on the next fan-out, because the mechanic has probably moved. Short enough that a rebroadcast
  // after the customer waited a while shows a current number; long enough that the usual
  // back-to-back auto-expand windows reuse it.
  @Value("${sos.eta.offer-estimate-max-age-seconds:300}")
  private int offerEstimateMaxAgeSeconds;

  // How many of the nearest mechanics get a real (billed) distance/time in the job offer.
  @Value("${sos.eta.offer-max-mechanics:25}")
  private int offerMaxMechanics;

  // Default broadcast radius used for fan-out when the SOS request has no user-defined limit.
  @Value("${sos.broadcast.default-km:15}")
  private int defaultBroadcastKm;

  // Seconds mechanics have to bid before a broadcast attempt expires, and how many times a
  // zero-bid ticket may be rebroadcast (auto or manual) before it gives up.
  @Value("${sos.bidding.window-seconds:60}")
  private int biddingWindowSeconds;

  // How long the customer has to answer a completion request. When it passes, the request is
  // approved automatically, so a customer who never answers cannot leave the job open forever (it
  // blocks them from a new SOS and keeps the mechanic tied to it).
  @Value("${sos.completion.approval-window-seconds:120}")
  private long completionApprovalWindowSeconds;

  @Value("${sos.bidding.max-rebroadcast:3}")
  private int maxRebroadcast;

  private static final String SOS_COOLDOWN_KEY_PREFIX = "sos:cooldown:";

  // Per-SOS memory of which mechanics already got a (billed) offer estimate. Outlives the bidding
  // window plus every rebroadcast attempt; bounded so abandoned tickets do not pin the key.
  private static final String OFFER_ETA_KEY_PREFIX = "sos:offer-eta:";
  private static final Duration OFFER_ETA_TTL = Duration.ofHours(1);

  // Google's Distance Matrix limit on origins per request.
  private static final int MAX_MATRIX_ORIGINS = 25;

  // Minimum gap between two SOS creations by the same user.
  @Value("${sos.create.cooldown-seconds:60}")
  private int createCooldownSeconds;

  // Collection of states indicating that a customer ticket is unresolved
  private static final List<SosStatus> ACTIVE_STATUSES =
      List.of(
          SosStatus.PENDING,
          SosStatus.ACCEPTED,
          SosStatus.ON_THE_WAY,
          SosStatus.ARRIVED,
          SosStatus.IN_PROGRESS);
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
  private final TransactionTemplate transactionTemplate;

  // Lazy: SosMediaServiceImpl asks this service who may see a ticket, so injecting it directly
  // would close the cycle and fail startup.
  private final ObjectProvider<SosMediaService> sosMediaService;

  // How close a mechanic must be, by road, before they may mark ARRIVED. Configurable because
  // the right value depends on the road layout: a divided boulevard can put someone standing
  // beside the customer several hundred metres away by road.
  @Value("${sos.arrival.max-distance-meters:50}")
  private int arrivalMaxDistanceMeters;

  // Auto-expand step/ceiling for a zero-bid retry — deliberately separate from the customer's own
  // broadcastRadiusKm preference cap (@Max(20) in CreateSosRequest). That cap is what the
  // customer chose; this is the system's own last-resort fallback when nobody responded, so it's
  // allowed to search wider than the customer ever asked for. Configurable so the step size and
  // ceiling can be tuned per-environment without a redeploy.
  @Value("${sos.auto-expand.step-km:5}")
  private int autoExpandStepKm;

  @Value("${sos.auto-expand.max-km:35}")
  private int autoExpandMaxKm;

  // Not @Transactional, same as the per-ticket expiry timer below: doExpire may auto-expand, which
  // calls Google (offer estimates) and pushes over the broker, and none of that should hold a
  // Postgres connection. Each repository call is its own short transaction.
  @EventListener(ApplicationReadyEvent.class)
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
    UUID sosId = s.getSosId();
    // A true zero-response expiry auto-widens the search instead of leaving the customer stuck;
    // a ticket that already has bids (even unaccepted ones) is a decision for the customer, not
    // the system, so it still goes terminal below.
    if (!bidSosRepository.existsBySosRequestSosId(sosId)
        && s.getRebroadcastCount() < maxRebroadcast) {
      autoExpandAndRebroadcast(s);
      return;
    }

    s.setStatus(SosStatus.EXPIRED);
    UUID userId = s.getUser().getUserId();
    sosEventProducer.publishStatusUpdated(
        new SosStatusUpdatedEvent(
            sosId, null, userId, SosStatus.PENDING, SosStatus.EXPIRED, LocalDateTime.now()));
    boolean retriesLeft = s.getRebroadcastCount() < maxRebroadcast;
    String body =
        retriesLeft
            ? "No mechanic was found. Tap to try again."
            : "No mechanic available after " + maxRebroadcast + " attempts. Please call support.";
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
            maxRebroadcast - s.getRebroadcastCount()));
  }

  // Zero bids at expiry: widen the net and try again automatically, instead of the customer
  // having to notice the ticket died and tap manual retry. Status stays PENDING throughout — this
  // never surfaces as EXPIRED, it just quietly casts a wider net.
  private void autoExpandAndRebroadcast(SosRequest s) {
    UUID sosId = s.getSosId();
    UUID userId = s.getUser().getUserId();
    int currentRadiusKm =
        s.getBroadcastRadiusKm() != null ? s.getBroadcastRadiusKm() : defaultBroadcastKm;
    int newRadiusKm = Math.min(currentRadiusKm + autoExpandStepKm, autoExpandMaxKm);

    s.setBroadcastRadiusKm(newRadiusKm);
    s.setRebroadcastCount(s.getRebroadcastCount() + 1);
    s.setBiddingExpiresAt(LocalDateTime.now().plusSeconds(biddingWindowSeconds));
    // Previous window's route estimate (if any) no longer applies to a freshly widened search.
    routeEtaService.clear(sosId);

    int attemptsLeft = maxRebroadcast - s.getRebroadcastCount();
    messagingTemplate.convertAndSendToUser(
        userId.toString(),
        "/queue/sos-updates",
        Map.of(
            "type",
            "SOS_AUTO_REBROADCAST",
            "sosId",
            sosId,
            "newRadiusKm",
            newRadiusKm,
            "rebroadcastCount",
            s.getRebroadcastCount(),
            "attemptsLeft",
            attemptsLeft));
    sosEventProducer.publishNotification(
        new SosNotificationEvent(
            "SOS_AUTO_REBROADCAST",
            userId,
            "USER",
            sosId,
            "Widening search area",
            "No mechanic responded yet — searching a wider area (" + newRadiusKm + " km)."));

    fanOutToNearbyMechanics(
        sosId, s.getLocation().getY(), s.getLocation().getX(), s.getProblemType(), newRadiusKm);

    scheduleBiddingExpiry(s);
  }

  // Same "we're already casting a wider net" message the zero-bid expiry retry sends above —
  // used at ticket creation too, so the customer isn't left wondering why their ticket just sat
  // there before the very first broadcast even went out.
  private void notifyRadiusWidened(UUID sosId, UUID userId, int newRadiusKm) {
    sosEventProducer.publishNotification(
        new SosNotificationEvent(
            "SOS_AUTO_REBROADCAST",
            userId,
            "USER",
            sosId,
            "Widening search area",
            "Few mechanics registered nearby — searching within " + newRadiusKm + " km."));
    messagingTemplate.convertAndSendToUser(
        userId.toString(),
        "/queue/sos-updates",
        Map.of("type", "SOS_AUTO_REBROADCAST", "sosId", sosId, "newRadiusKm", newRadiusKm));
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
  // Deliberately NOT @Transactional. A single transaction here held a Postgres connection across
  // Medusa, Google reverse-geocode and the offer-estimate call (up to 5 s each when Maps is slow),
  // so a few slow broadcasts could drain the pool. Only the writes run in a transaction, and the
  // slow external calls run outside it.
  @Override
  public SosRequestResponse create(CreateSosRequest request) {
    // Enforce structural restriction: A user can only process one active distress event at a time
    if (sosRequestRepository.existsByUserUserIdAndStatusIn(request.userId(), ACTIVE_STATUSES)) {
      throw new IllegalStateException("You already have an active SOS request");
    }

    // Redis distributed lock prevents accidental double-tap double submissions
    // Fails open when Redis is unreachable: the active-ticket check above still stops a second
    // live ticket, and refusing an emergency request because a cache is down is the worse outcome.
    Boolean allowed;
    try {
      allowed =
          redisTemplate
              .opsForValue()
              .setIfAbsent(
                  SOS_COOLDOWN_KEY_PREFIX + request.userId(),
                  "1",
                  Duration.ofSeconds(createCooldownSeconds));
    } catch (RuntimeException e) {
      log.warn("Redis unavailable for create cooldown, allowing request: {}", e.getMessage());
      allowed = Boolean.TRUE;
    }
    if (!Boolean.TRUE.equals(allowed)) {
      throw new TooManyRequestsException(
          "Please wait "
              + createCooldownSeconds
              + " seconds before submitting another SOS request");
    }

    // The cooldown only guards a double-tap of a request that went through. If Medusa is down, or
    // the user, vehicle or service group is not found, no ticket exists, so the key is released:
    // otherwise one failed attempt locks an emergency caller out of retrying for the whole window.
    String problemTypeKey;
    SosRequest sos;
    try {
      // External call, outside any transaction.
      problemTypeKey = medusaService.resolveServiceGroupKey(request.serviceGroupId());

      // The only transactional part: look up the projections, pick the radius, insert the ticket.
      String key = problemTypeKey;
      sos = transactionTemplate.execute(status -> persistSos(request, key));
    } catch (RuntimeException e) {
      releaseCreateCooldown(request.userId());
      throw e;
    }
    int startingRadiusKm = sos.getBroadcastRadiusKm();
    User user = sos.getUser();

    // Geocode coordinates to map a readable address. External call, outside any transaction; the
    // update below is its own short write.
    String address = googleMapsService.reverseGeocode(request.latitude(), request.longitude());
    if (address != null) {
      sos.setLocationAddress(address);
      sosRequestRepository.save(sos);
    }

    // Queue automated closing of the auction window if no responses come through. First, and
    // before anything that can fail: the ticket is already committed, so it must always expire.
    scheduleBiddingExpiry(sos);

    // Everything below is notification. The ticket exists, so a failure here must not turn into a
    // 500 for a request that succeeded (the customer would retry into "already have an active
    // SOS").
    try {
      // Tell the customer up front if we already had to widen past their requested/default radius
      // — otherwise they see a ticket sitting quietly and have no idea the system is already
      // trying harder on their behalf, same concern the expiry-based widening already covers.
      int requestedOrDefaultKm =
          request.broadcastRadiusKm() != null ? request.broadcastRadiusKm() : defaultBroadcastKm;
      if (startingRadiusKm > requestedOrDefaultKm) {
        notifyRadiusWidened(sos.getSosId(), user.getUserId(), startingRadiusKm);
      }

      // Broadcast out to standard Kafka topic pipelines
      sosEventProducer.publishSosCreated(
          new SosCreatedEvent(
              sos.getSosId(),
              user.getUserId(),
              sos.getVehicle().getId(),
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
          startingRadiusKm);
    } catch (RuntimeException e) {
      log.error("SOS {} was created but notifying/broadcasting failed", sos.getSosId(), e);
    }

    return toResponseWithMechanic(sos);
  }

  // Runs inside the transaction opened by create(); keep external calls out of it.
  private void releaseCreateCooldown(UUID userId) {
    try {
      redisTemplate.delete(SOS_COOLDOWN_KEY_PREFIX + userId);
    } catch (RuntimeException e) {
      // Same stance as the claim: Redis being down must not turn a failed create into a new error.
      log.warn("Could not release the create cooldown for user {}: {}", userId, e.getMessage());
    }
  }

  private SosRequest persistSos(CreateSosRequest request, String problemTypeKey) {
    // Both rows are projections of SSO. ponytail: no SSO round-trip for today's test (see
    // SsoProjection) — the vehicle lookup checks local ownership instead of SSO's per-user
    // scoping, so a driver still can't file a breakdown against a stranger's car.
    User user = ssoProjection.user(request.userId());
    Vehicle vehicle = ssoProjection.vehicle(request.vehicleId(), user);
    // Escalate the starting radius up front if nobody's even nearby at the requested one —
    // otherwise the ticket would broadcast into an empty area and sit for the full 60s window
    // before the zero-bid expiry path (autoExpandAndRebroadcast) ever notices.
    int startingRadiusKm =
        resolveStartingRadiusKm(
            request.latitude(), request.longitude(), problemTypeKey, request.broadcastRadiusKm());
    SosRequest sos =
        SosRequest.builder()
            .problemType(problemTypeKey)
            .description(request.description())
            .location(
                GEOMETRY_FACTORY.createPoint(
                    new Coordinate(request.longitude(), request.latitude())))
            .broadcastRadiusKm(startingRadiusKm)
            .biddingExpiresAt(LocalDateTime.now().plusSeconds(biddingWindowSeconds))
            .user(user)
            .vehicle(vehicle)
            .build();
    sosRequestRepository.save(sos);
    return sos;
  }

  @Override
  @Transactional(readOnly = true)
  public SosRequestResponse getById(UUID sosId, UUID callerId) {
    SosRequest sos = findSosOrThrow(sosId);
    // Not 403: telling an outsider "forbidden" tells them the id is real.
    if (!isVisibleTo(sos, callerId)) {
      throw new EntityNotFoundException("SOS request not found: " + sosId);
    }
    SosRequestResponse response = toResponseWithMechanic(sos);
    // The owner needs the historyId of a waiting completion request to approve it. The push and the
    // COMPLETION_REQUESTED frame carry it too, but a missed frame must not leave the job stuck.
    if (sos.getStatus() == SosStatus.IN_PROGRESS && sos.getUser().getUserId().equals(callerId)) {
      return sosStatusHistoryRepository
          .findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
              sosId, ApprovedStatus.PENDING_APPROVAL)
          .map(
              history ->
                  response.withPendingCompletion(
                      history.getId(), completionApprovalDeadline(history)))
          .orElse(response);
    }
    return response;
  }

  // Live view for the customer's "waiting for a bid" screen — reuses the ticket's own location,
  // problem type, and current (possibly auto-expanded) radius, so mobile never has to reconstruct
  // those itself or hit the customer-facing @Max(20) cap on GET /mechanics/available once a
  // ticket's radius has auto-expanded past it.
  @Override
  @Transactional(readOnly = true)
  public List<MechanicResponse> getNearbyMechanics(UUID sosId, UUID callerId) {
    SosRequest sos = findSosOrThrow(sosId);
    if (!isVisibleTo(sos, callerId)) {
      throw new EntityNotFoundException("SOS request not found: " + sosId);
    }

    Point location = sos.getLocation();
    int radiusKm =
        sos.getBroadcastRadiusKm() != null ? sos.getBroadcastRadiusKm() : defaultBroadcastKm;
    return findMatchedMechanics(location.getY(), location.getX(), sos.getProblemType(), radiusKm)
        .stream()
        .map(mechanicMapper::toResponse)
        .toList();
  }

  // Read-only H3 + skill-tag + radius match, identical criteria to isEligibleMechanic/
  // fanOutToNearbyMechanics but without their notification side effects — safe to call from a
  // GET.
  private List<Mechanic> findMatchedMechanics(
      double lat, double lng, String problemType, int radiusKm) {
    String sosCell = H3.geoToH3Address(lat, lng, h3Resolution);
    List<String> searchCells = H3.kRing(sosCell, ringRadiusFor(radiusKm));
    List<UUID> coarse = mechanicRepository.findByH3CellsAndSkillTag(searchCells, problemType);
    if (coarse.isEmpty()) {
      return List.of();
    }
    List<UUID> matched =
        mechanicRepository.filterByRadiusAndBroadcast(coarse, lat, lng, radiusKm * 1000.0);
    return matched.isEmpty() ? List.of() : mechanicRepository.findAllWithRateById(matched);
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
        sos.getBroadcastRadiusKm() != null ? sos.getBroadcastRadiusKm() : defaultBroadcastKm;
    String sosCell = H3.geoToH3Address(lat, lng, h3Resolution);
    List<String> searchCells = H3.kRing(sosCell, ringRadiusFor(effectiveRadiusKm));

    List<UUID> coarse =
        mechanicRepository.findByH3CellsAndSkillTag(searchCells, sos.getProblemType());

    if (!coarse.contains(callerId)) {
      return false;
    }

    List<UUID> matched =
        sos.getBroadcastRadiusKm() != null
            ? mechanicRepository.filterByRadiusAndBroadcast(
                coarse, lat, lng, sos.getBroadcastRadiusKm() * 1000.0)
            : mechanicRepository.filterByRadius(coarse, lat, lng);
    return matched.contains(callerId);
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
  // Only ON_THE_WAY, ARRIVED, and IN_PROGRESS flow through here; COMPLETED requires user approval
  // via proposeStatusChange.
  @Override
  @Transactional
  public SosRequestResponse updateStatus(UUID sosId, UUID mechanicId, SosStatus toStatus) {
    if (toStatus != SosStatus.ON_THE_WAY
        && toStatus != SosStatus.ARRIVED
        && toStatus != SosStatus.IN_PROGRESS) {
      // The requested target status is not one this endpoint accepts: a bad request (400), not a
      // conflict with the ticket's current state.
      throw new IllegalArgumentException(
          "Only ON_THE_WAY, ARRIVED, and IN_PROGRESS can be set directly. Use POST /api/v1/sos/{sosId}/completion for COMPLETED.");
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

    if (toStatus == SosStatus.IN_PROGRESS) {
      sos.setJobStartedAt(LocalDateTime.now());
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
      throw new IllegalArgumentException(
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
    // A retry (double tap, timeout) must not leave two approvable rows or push the customer twice:
    // the request that is already waiting is the answer.
    var alreadyWaiting =
        sosStatusHistoryRepository.findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
            sosId, ApprovedStatus.PENDING_APPROVAL);
    if (alreadyWaiting.isPresent()) {
      return sosStatusHistoryMapper.toResponse(alreadyWaiting.get());
    }
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
            "type",
            "COMPLETION_REQUESTED",
            "sosId",
            sos.getSosId(),
            "historyId",
            history.getId(),
            "approveBy",
            completionApprovalDeadline(history).toString()));
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

  /**
   * Records the answer to a waiting completion request and applies it. {@code approver} is the
   * customer, or null when the approval window ran out and the system answered.
   */
  private void decideCompletion(SosStatusHistory history, ApprovedStatus decision, User approver) {
    SosRequest sos = history.getSosRequest();
    history.setApprovedStatus(decision);
    history.setApprovedBy(approver);
    history.setApproveAt(LocalDateTime.now());
    sosStatusHistoryRepository.save(history);

    if (decision == ApprovedStatus.ACCEPTED) {
      SosStatus fromStatus = sos.getStatus();
      validateTransition(fromStatus, history.getToStatus());
      sos.setStatus(history.getToStatus());
      if (history.getToStatus() == SosStatus.COMPLETED) {
        sos.setJobCompletedAt(LocalDateTime.now());
      }
      sosRequestRepository.save(sos);
      routeEtaService.clear(sos.getSosId()); // COMPLETED — no drive left to count down

      UUID mechanicId = history.getProposedBy().getMechanicId();
      SosStatusUpdatedEvent statusEvent =
          new SosStatusUpdatedEvent(
              sos.getSosId(),
              mechanicId,
              sos.getUser().getUserId(),
              fromStatus,
              history.getToStatus(),
              LocalDateTime.now());
      sosEventProducer.publishStatusUpdated(statusEvent);
      messagingTemplate.convertAndSendToUser(
          mechanicId.toString(), "/queue/sos-updates", statusEvent);

      publishStatusNotifications(history.getToStatus(), sos, mechanicId);
    } else if (decision == ApprovedStatus.REJECTED) {
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
  }

  /**
   * Approves completion requests the customer has not answered within the approval window, so the
   * job closes, the mechanic is released and the customer can create a new SOS. Runs on a fixed
   * delay (and so also catches requests overdue after a restart). Each request is handled in its
   * own transaction and re-checked under the ticket's row lock, so a customer answering at the same
   * moment wins and nothing is applied twice.
   */
  @Scheduled(fixedDelayString = "${sos.completion.sweep-interval-ms:15000}")
  public void approveOverdueCompletions() {
    LocalDateTime cutoff = LocalDateTime.now().minusSeconds(completionApprovalWindowSeconds);
    for (SosStatusHistory overdue :
        sosStatusHistoryRepository.findByApprovedStatusAndProposedAtBefore(
            ApprovedStatus.PENDING_APPROVAL, cutoff)) {
      try {
        transactionTemplate.executeWithoutResult(status -> autoApproveCompletion(overdue.getId()));
      } catch (RuntimeException e) {
        // One bad row must not stop the others; it is retried on the next sweep.
        log.error("Auto-approving completion {} failed: {}", overdue.getId(), e.getMessage());
      }
    }
  }

  private void autoApproveCompletion(UUID historyId) {
    SosStatusHistory history = sosStatusHistoryRepository.findById(historyId).orElse(null);
    if (history == null) return;
    sosRequestRepository.lockStatus(history.getSosRequest().getSosId());
    if (sosStatusHistoryRepository.findApprovedStatusById(historyId).orElse(ApprovedStatus.ACCEPTED)
        != ApprovedStatus.PENDING_APPROVAL) {
      return; // the customer answered while we waited for the lock
    }
    SosRequest sos = history.getSosRequest();
    if (sos.getStatus() != SosStatus.IN_PROGRESS) {
      return; // cancelled or otherwise moved on: nothing to complete
    }
    log.warn(
        "Completion request {} for SOS {} unanswered after {} s, approving automatically",
        historyId,
        sos.getSosId(),
        completionApprovalWindowSeconds);
    decideCompletion(history, ApprovedStatus.ACCEPTED, null);
    messagingTemplate.convertAndSendToUser(
        sos.getUser().getUserId().toString(),
        "/queue/sos-updates",
        Map.of("type", "COMPLETION_AUTO_APPROVED", "sosId", sos.getSosId()));
  }

  /** When an unanswered completion request is approved automatically. */
  private LocalDateTime completionApprovalDeadline(SosStatusHistory history) {
    return history.getProposedAt() == null
        ? null
        : history.getProposedAt().plusSeconds(completionApprovalWindowSeconds);
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

    // The customer and the approval-window sweep can answer the same request at the same moment;
    // take the ticket's row lock so only one of them applies a decision.
    sosRequestRepository.lockStatus(history.getSosRequest().getSosId());
    ApprovedStatus current =
        sosStatusHistoryRepository
            .findApprovedStatusById(history.getId())
            .orElse(history.getApprovedStatus());

    // A decided history entry cannot be decided again. ACCEPTED-then-ACCEPTED is incidentally
    // blocked by validateTransition below once the sos is already COMPLETED, but ACCEPTED-then-
    // REJECTED (or the reverse) is not — without this, the outcome could be flipped after the
    // fact, silently re-notifying the mechanic.
    if (current != ApprovedStatus.PENDING_APPROVAL) {
      throw new IllegalStateException("This completion request was already " + current);
    }

    SosRequest sos = history.getSosRequest();
    decideCompletion(history, request.approvedStatus(), user);
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
      case IN_PROGRESS ->
          sosEventProducer.publishNotification(
              new SosNotificationEvent(
                  "STATUS_IN_PROGRESS",
                  userId,
                  "USER",
                  sos.getSosId(),
                  "Job started",
                  "Your mechanic has started the repair"));
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

    // Whoever was on the job, or had bid on it, must hear it is off: an assigned mechanic would
    // otherwise keep driving to a cancelled job.
    UUID assignedMechanicId =
        bidSosRepository
            .findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED)
            .map(bid -> bid.getMechanic().getMechanicId())
            .orElse(null);
    List<UUID> pendingBidders =
        bidSosRepository.findBySosRequestSosIdAndStatus(sosId, BidStatus.PENDING).stream()
            .map(bid -> bid.getMechanic().getMechanicId())
            .toList();

    sosEventProducer.publishStatusUpdated(
        new SosStatusUpdatedEvent(
            sos.getSosId(),
            assignedMechanicId,
            userId,
            fromStatus,
            SosStatus.CANCELLED,
            LocalDateTime.now()));
    sosEventProducer.publishNotification(
        new SosNotificationEvent(
            "STATUS_CANCELLED",
            userId,
            "USER",
            sos.getSosId(),
            "SOS request cancelled",
            "Your SOS request has been cancelled"));
    notifyMechanicsOfCancellation(sos, fromStatus, assignedMechanicId, pendingBidders);

    return toResponseWithMechanic(sos);
  }

  /**
   * Tells the assigned mechanic (a frame and a push) and every mechanic with a pending bid (a frame
   * each and one batched push) that the customer cancelled. The frame carries the status the
   * request was in, so the app can tell "my job was cancelled" from "a request I bid on was".
   */
  private void notifyMechanicsOfCancellation(
      SosRequest sos, SosStatus fromStatus, UUID assignedMechanicId, List<UUID> pendingBidders) {
    if (assignedMechanicId != null) {
      messagingTemplate.convertAndSendToUser(
          assignedMechanicId.toString(),
          "/queue/sos-updates",
          Map.of(
              "type", "SOS_CANCELLED", "sosId", sos.getSosId(), "fromStatus", fromStatus.name()));
      sosEventProducer.publishNotification(
          new SosNotificationEvent(
              "SOS_CANCELLED",
              assignedMechanicId,
              "MECHANIC",
              sos.getSosId(),
              "Job cancelled",
              "The customer has cancelled this job"));
    }
    if (!pendingBidders.isEmpty()) {
      for (UUID bidder : pendingBidders) {
        messagingTemplate.convertAndSendToUser(
            bidder.toString(),
            "/queue/sos-updates",
            Map.of(
                "type", "SOS_CANCELLED", "sosId", sos.getSosId(), "fromStatus", fromStatus.name()));
      }
      sosEventProducer.publishNotification(
          new SosNotificationEvent(
              "SOS_CANCELLED",
              null,
              "MECHANIC",
              sos.getSosId(),
              "Request cancelled",
              "The customer cancelled a request you bid on",
              null,
              null,
              pendingBidders));
    }
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
        base.jobStartedAt(),
        base.jobCompletedAt(),
        base.durationSeconds(),
        base.rebroadcastCount(),
        base.user(),
        base.vehicle(),
        mechanic,
        null,
        null);
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
              mechanicLocation.getY(),
              mechanicLocation.getX(),
              sos.getLocation().getY(),
              sos.getLocation().getX(),
              earthRadiusM);
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
  // Escalates the starting radius if the requested one finds zero mechanic candidates at all
  // (by H3 cell, ignoring exact-distance filtering) — a synchronous, pre-broadcast check,
  // distinct from the later expiry-based auto-expand (autoExpandAndRebroadcast) which handles
  // "mechanics exist but nobody bid in time." Does not consume a rebroadcastCount attempt.
  private int resolveStartingRadiusKm(
      double lat, double lng, String problemType, Integer requestedRadiusKm) {
    int radiusKm = requestedRadiusKm != null ? requestedRadiusKm : defaultBroadcastKm;
    while (hasNoCandidates(lat, lng, problemType, radiusKm) && radiusKm < autoExpandMaxKm) {
      radiusKm = Math.min(radiusKm + autoExpandStepKm, autoExpandMaxKm);
    }
    return radiusKm;
  }

  private boolean hasNoCandidates(double lat, double lng, String problemType, int radiusKm) {
    String sosCell = H3.geoToH3Address(lat, lng, h3Resolution);
    List<String> searchCells = H3.kRing(sosCell, ringRadiusFor(radiusKm));
    return mechanicRepository.findByH3CellsAndSkillTag(searchCells, problemType).isEmpty();
  }

  private void fanOutToNearbyMechanics(
      UUID sosId, double lat, double lng, String problemType, Integer broadcastRadiusKm) {
    int effectiveRadiusKm = broadcastRadiusKm != null ? broadcastRadiusKm : defaultBroadcastKm;
    String sosCell = H3.geoToH3Address(lat, lng, h3Resolution);
    List<String> searchCells = H3.kRing(sosCell, ringRadiusFor(effectiveRadiusKm));

    List<UUID> coarse = mechanicRepository.findByH3CellsAndSkillTag(searchCells, problemType);
    if (coarse.isEmpty()) return;

    List<UUID> matchedIds =
        broadcastRadiusKm != null
            ? mechanicRepository.filterByRadiusAndBroadcast(
                coarse, lat, lng, broadcastRadiusKm * 1000.0)
            : mechanicRepository.filterByRadius(coarse, lat, lng);
    if (matchedIds.isEmpty()) return;
    // Entities only for the offer estimates (they need each mechanic's location); one query.
    List<Mechanic> matched = mechanicRepository.findAllWithRateById(matchedIds);

    // Photos are uploaded after the ticket is created, so this is empty on the first broadcast
    // and populated on a rebroadcast — which is the attempt where the mechanic most needs to see
    // what the job actually is before bidding.
    List<String> photoUrls = sosMediaService.getObject().photoUrls(sosId);
    Map<UUID, EtaInfo> estimates = offerEstimates(sosId, matched, lat, lng);
    matched.forEach(
        m ->
            messagingTemplate.convertAndSendToUser(
                m.getMechanicId().toString(),
                "/queue/sos-new-request",
                new SosNotificationEvent(
                    "SOS_CREATED",
                    null,
                    "MECHANIC",
                    sosId,
                    "New SOS request nearby",
                    "Problem: " + problemType,
                    photoUrls,
                    estimates.get(m.getMechanicId()))));
  }

  // Real road distance/time per mechanic for the job offer. Google bills per mechanic, so each
  // mechanic is only ever paid for once per SOS: the estimate is remembered in Redis, and a later
  // fan-out (auto-expand, manual rebroadcast) reuses it for mechanics already offered and asks
  // Google only for the newly reached ones, in a single Distance Matrix request. Newly reached
  // mechanics beyond offerMaxMechanics (nearest first) still get the offer without an estimate.
  // A failed call or Redis outage yields no estimate for those mechanics — the offer never waits
  // on or fails because of Maps, and a missing number is better than an invented one.
  private Map<UUID, EtaInfo> offerEstimates(
      UUID sosId, List<Mechanic> matched, double lat, double lng) {
    String key = OFFER_ETA_KEY_PREFIX + sosId;
    Map<UUID, EtaInfo> estimates = new HashMap<>(readOfferEstimates(key));

    List<Mechanic> nearest =
        matched.stream()
            .filter(m -> m.getCurrentLocation() != null)
            .filter(m -> !estimates.containsKey(m.getMechanicId()))
            .sorted(
                Comparator.comparingDouble(
                    m ->
                        GeoDistance.haversineMeters(
                            m.getCurrentLocation().getY(),
                            m.getCurrentLocation().getX(),
                            lat,
                            lng,
                            earthRadiusM)))
            .limit(Math.min(offerMaxMechanics, MAX_MATRIX_ORIGINS))
            .toList();
    if (nearest.isEmpty()) return estimates;

    Point destination = GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat));
    List<DistanceInfo> infos =
        googleMapsService.getDistanceInfos(
            nearest.stream().map(Mechanic::getCurrentLocation).toList(), destination);
    if (infos == null || infos.size() != nearest.size()) return estimates;

    Map<UUID, EtaInfo> fresh = new HashMap<>();
    for (int i = 0; i < nearest.size(); i++) {
      DistanceInfo d = infos.get(i);
      if (d != null) {
        fresh.put(
            nearest.get(i).getMechanicId(),
            new EtaInfo(
                d.distanceMeters(), d.durationSeconds(), d.distanceText(), d.durationText()));
      }
    }
    writeOfferEstimates(key, fresh);
    estimates.putAll(fresh);
    return estimates;
  }

  // Stored as mechanicId -> "meters<TAB>seconds<TAB>distanceText<TAB>durationText<TAB>epochSecond".
  // Entries older than offerEstimateMaxAgeSeconds (or in the old 4-field format, with no
  // timestamp) are skipped so those mechanics are re-estimated. Redis trouble degrades to "nobody
  // offered yet" (one extra billed call), never to a failed broadcast.
  private Map<UUID, EtaInfo> readOfferEstimates(String key) {
    try {
      long oldestAllowed = java.time.Instant.now().getEpochSecond() - offerEstimateMaxAgeSeconds;
      Map<UUID, EtaInfo> out = new HashMap<>();
      redisTemplate
          .opsForHash()
          .entries(key)
          .forEach(
              (k, v) -> {
                String[] p = v.toString().split("	", 5);
                if (p.length < 5 || Long.parseLong(p[4]) < oldestAllowed) return;
                out.put(
                    UUID.fromString(k.toString()),
                    new EtaInfo(Long.parseLong(p[0]), Long.parseLong(p[1]), p[2], p[3]));
              });
      return out;
    } catch (Exception e) {
      log.warn("Could not read offer estimates {}: {}", key, e.getMessage());
      return Map.of();
    }
  }

  private void writeOfferEstimates(String key, Map<UUID, EtaInfo> fresh) {
    if (fresh.isEmpty()) return;
    try {
      long now = java.time.Instant.now().getEpochSecond();
      Map<String, String> entries = new HashMap<>();
      fresh.forEach(
          (id, e) ->
              entries.put(
                  id.toString(),
                  e.remainingDistanceM()
                      + "\t"
                      + e.remainingDurationS()
                      + "\t"
                      + e.remainingDistanceText()
                      + "\t"
                      + e.remainingDurationText()
                      + "	"
                      + now));
      redisTemplate.opsForHash().putAll(key, entries);
      redisTemplate.expire(key, OFFER_ETA_TTL);
    } catch (Exception e) {
      log.warn("Could not store offer estimates {}: {}", key, e.getMessage());
    }
  }

  // Converts a km radius into the minimum kRing depth that fully covers it at resolution 7.
  // +1 ring of buffer accounts for partial hex overlap at the boundary.
  private int ringRadiusFor(int radiusKm) {
    return (int) Math.ceil(radiusKm / h3CenterToCenterKm) + 1;
  }

  // Uses TaskScheduler (not @Scheduled) because each SOS has its own unique expiry time.
  // The lambda re-fetches the entity inside the transaction to avoid stale-state issues.
  // Not @Transactional: the broadcast calls Google (offer estimates), and a transaction would hold
  // a Postgres connection for the whole of it. Only the validation and the state change run in one;
  // the response is built inside it too, because user and vehicle are lazy and the mapper needs a
  // live session to read them.
  @Override
  public SosRequestResponse rebroadcast(UUID sosId, UUID userId) {
    Rebroadcast reopened = transactionTemplate.execute(status -> reopenSos(sosId, userId));
    SosRequest sos = reopened.sos();

    // First, and before anything that can fail: the ticket is already back to PENDING and
    // committed, so its bidding window must always close.
    scheduleBiddingExpiry(sos);

    // Back to bidding: the previous mechanic's route is meaningless now.
    routeEtaService.clear(sosId);

    // The ticket is committed, so a notification failure must not turn into a 500 for a request
    // that succeeded — the customer would retry into "only EXPIRED requests can be rebroadcast".
    try {
      int attemptsLeft = maxRebroadcast - sos.getRebroadcastCount();
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
    } catch (RuntimeException e) {
      log.error("SOS {} was rebroadcast but notifying/broadcasting failed", sosId, e);
    }

    return reopened.response();
  }

  private record Rebroadcast(SosRequest sos, SosRequestResponse response) {}

  // Runs inside the transaction opened by rebroadcast(); keep external calls out of it.
  private Rebroadcast reopenSos(UUID sosId, UUID userId) {
    SosRequest sos = findSosOrThrow(sosId);

    if (!sos.getUser().getUserId().equals(userId)) {
      throw new AccessDeniedException("You can only rebroadcast your own SOS request");
    }
    if (sos.getStatus() != SosStatus.EXPIRED) {
      throw new IllegalStateException("Only EXPIRED SOS requests can be rebroadcast");
    }
    if (sos.getRebroadcastCount() >= maxRebroadcast) {
      throw new TooManyRequestsException(
          "Maximum rebroadcast attempts (" + maxRebroadcast + ") reached. Please call support.");
    }

    sos.setStatus(SosStatus.PENDING);
    sos.setRebroadcastCount(sos.getRebroadcastCount() + 1);
    sos.setBiddingExpiresAt(LocalDateTime.now().plusSeconds(biddingWindowSeconds));
    sosRequestRepository.save(sos);
    return new Rebroadcast(sos, toResponseWithMechanic(sos));
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
