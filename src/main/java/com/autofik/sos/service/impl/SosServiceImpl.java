package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.SosStatusHistory;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.event.BidRejectedEvent;
import com.autofik.sos.dto.event.SosCreatedEvent;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.dto.event.SosStatusUpdatedEvent;
import com.autofik.sos.dto.external.DistanceInfo;
import com.autofik.sos.dto.external.EtaInfo;
import com.autofik.sos.dto.request.ApproveStatusChangeRequest;
import com.autofik.sos.dto.request.CreateSosRequest;
import com.autofik.sos.dto.request.ProposeStatusChangeRequest;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.dto.response.MechanicSummaryResponse;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.dto.response.SosStatusHistoryEntry;
import com.autofik.sos.dto.response.SosStatusHistoryResponse;
import com.autofik.sos.exception.BadRequestException;
import com.autofik.sos.exception.ConflictException;
import com.autofik.sos.exception.TooManyRequestsException;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.MechanicMapper;
import com.autofik.sos.mapper.SosMapper;
import com.autofik.sos.mapper.SosStatusHistoryMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosOfferRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.SosStatusHistoryRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.repository.VehicleRepository;
import com.autofik.sos.service.GoogleMapsService;
import com.autofik.sos.service.MedusaService;
import com.autofik.sos.service.RouteEtaService;
import com.autofik.sos.service.SosMediaService;
import com.autofik.sos.service.SosService;
import com.autofik.sos.util.AfterCommit;
import com.autofik.sos.util.Geo;
import com.autofik.sos.util.GeoDistance;
import com.uber.h3core.H3Core;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;
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

  // A mechanic whose last location ping is older than this is not offered jobs (they have
  // probably closed the app); a mechanic with no recorded ping is kept.
  @Value("${sos.dispatch.location-max-age-minutes:5}")
  private int locationMaxAgeMinutes;

  // A mechanic whose road ETA to the SOS is longer than this is not offered the job. Straight-line
  // radius ignores rivers and one-way roads. Only applied where an estimate already exists.
  // 0 disables.
  @Value("${sos.dispatch.max-road-eta-minutes:30}")
  private int maxRoadEtaMinutes;

  // How long an assigned mechanic may stand still before being asked "are you still coming?"
  // (0 turns the no-show sweep off), and how long they have to answer before the job is released.
  @Value("${sos.no-show.stalled-minutes:5}")
  private int noShowStalledMinutes;

  @Value("${sos.no-show.prompt-response-seconds:60}")
  private int noShowPromptResponseSeconds;

  private LocalDateTime locationFreshSince() {
    return LocalDateTime.now().minusMinutes(locationMaxAgeMinutes);
  }

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
  // F-11: a job cannot be marked complete the moment it starts. 0 turns the check off.
  @Value("${sos.completion.min-in-progress-seconds:60}")
  private int minInProgressSeconds;

  @Value("${sos.completion.approval-window-seconds:120}")
  private long completionApprovalWindowSeconds;

  // Same idea for the "your mechanic has arrived" confirmation, but shorter: the mechanic is
  // already checked to be at the breakdown, so silence means the customer is not there, not that
  // the arrival is false. When it passes, ARRIVED is confirmed automatically and the wait below
  // starts.
  @Value("${sos.arrival.approval-window-seconds:60}")
  private long arrivalApprovalWindowSeconds;

  // How long after ARRIVED took effect a mechanic must wait before they may end the job because
  // the customer never came to the car. 0 turns the action off.
  @Value("${sos.customer-wait.minutes:10}")
  private int customerWaitMinutes;

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

  // A bid lets its mechanic read the ticket only while it is live: waiting for the customer, or
  // accepted. A rejected, withdrawn or released bid is over, and with it the access.
  // Radius meant as "no cap" for the eligibility query: LEAST() then leaves the mechanic's own.
  private static final double NO_BROADCAST_CAP_M = 1e12;

  private static final List<BidStatus> LIVE_BID_STATUSES =
      List.of(BidStatus.PENDING, BidStatus.ACCEPTED);

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
  private final SosOfferRepository sosOfferRepository;
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

  // At startup: expire what was missed while the service was down, then re-arm a timer for every
  // window still open. Not @Transactional: each expiry is its own short transaction, and the
  // auto-expand fan-out after it calls Google and the broker, which must not hold a connection.
  @EventListener(ApplicationReadyEvent.class)
  public void expireOverduePendingRequests() {
    expireOverdue();

    List<SosRequest> active =
        sosRequestRepository.findByStatusAndBiddingExpiresAtAfter(
            SosStatus.PENDING, LocalDateTime.now());
    if (!active.isEmpty()) {
      log.info("Re-arming bidding expiry timer for {} PENDING SOS request(s)", active.size());
      active.forEach(this::scheduleBiddingExpiry);
    }
  }

  /**
   * Safety net for the in-memory timers: a timer that threw, or one lost some other way, would
   * otherwise leave the ticket PENDING (an active ticket, so the driver cannot raise another) until
   * the next restart. Does not re-arm timers; the startup hook does that once.
   */
  @Scheduled(fixedDelayString = "${sos.expiry.sweep-interval-ms:30000}")
  public void sweepOverdueExpiry() {
    expireOverdue();
  }

  private void expireOverdue() {
    List<SosRequest> overdue =
        sosRequestRepository.findByStatusAndBiddingExpiresAtBefore(
            SosStatus.PENDING, LocalDateTime.now());
    if (overdue.isEmpty()) return;
    log.warn("Expiring {} overdue PENDING SOS request(s)", overdue.size());
    for (SosRequest s : overdue) {
      try {
        expireIfDue(s.getSosId());
      } catch (RuntimeException e) {
        // One bad ticket must not stop the others; it is looked at again on the next sweep.
        log.error("Expiry of SOS {} failed: {}", s.getSosId(), e.getMessage());
      }
    }
  }

  private record Expired(
      SosRequest sos, UUID userId, boolean autoExpanded, List<RejectedBid> closedBids) {}

  /**
   * The one way a bidding window ends, used by the timer, the startup hook and the sweep. The
   * change is made under the ticket lock and only if the ticket is still PENDING and past its
   * window, so an accept or cancel that lands first wins, a stale timer does nothing, and running
   * it twice is harmless. Everything external happens after the commit.
   */
  private void expireIfDue(UUID sosId) {
    Expired expired = transactionTemplate.execute(status -> expireStep(sosId));
    if (expired != null) finishExpiry(expired);
  }

  // Runs inside the transaction opened by expireIfDue(); keep external calls out of it.
  private Expired expireStep(UUID sosId) {
    // RACE GUARD: the ticket lock accept, cancel and release also take, so whoever comes second
    // sees the status the first one committed.
    if (!SosStatus.PENDING.name().equals(sosRequestRepository.lockStatus(sosId).orElse(null))) {
      return null;
    }
    SosRequest s = findSosOrThrow(sosId);
    LocalDateTime now = LocalDateTime.now();
    if (s.getBiddingExpiresAt() == null || s.getBiddingExpiresAt().isAfter(now)) {
      return null; // a window renewed since the timer was set (release, rebroadcast, auto-expand)
    }
    UUID userId = s.getUser().getUserId();

    // A true zero-response expiry auto-widens the search instead of leaving the customer stuck;
    // a ticket that already has bids (even unaccepted ones) is a decision for the customer, not
    // the system, so it still goes terminal below. Status stays PENDING when widening.
    if (!bidSosRepository.existsBySosRequestSosId(sosId)
        && s.getRebroadcastCount() < maxRebroadcast) {
      int currentRadiusKm =
          s.getBroadcastRadiusKm() != null ? s.getBroadcastRadiusKm() : defaultBroadcastKm;
      s.setBroadcastRadiusKm(Math.min(currentRadiusKm + autoExpandStepKm, autoExpandMaxKm));
      s.setRebroadcastCount(s.getRebroadcastCount() + 1);
      s.setBiddingExpiresAt(now.plusSeconds(biddingWindowSeconds));
      sosRequestRepository.save(s);
      return new Expired(s, userId, true, List.of());
    }

    s.setStatus(SosStatus.EXPIRED);
    sosRequestRepository.save(s);
    // The bids die with the request, in the same transaction: if closing them fails the ticket
    // stays PENDING and the next sweep tries again, rather than an EXPIRED ticket with live bids.
    return new Expired(s, userId, false, rejectPendingBids(sosId));
  }

  /** Everything that follows a committed expiry. None of it may undo or fail the expiry. */
  private void finishExpiry(Expired expired) {
    SosRequest s = expired.sos();
    UUID sosId = s.getSosId();
    UUID userId = expired.userId();
    if (expired.autoExpanded()) {
      // First, and before anything that can fail: the widened window must always close.
      scheduleBiddingExpiry(s);
    }
    try {
      if (expired.autoExpanded()) {
        announceAutoExpand(s, userId);
      } else {
        announceExpiry(s, userId, expired.closedBids());
      }
    } catch (RuntimeException e) {
      log.error("SOS {} expired but notifying/broadcasting failed", sosId, e);
    }
  }

  // Zero bids at expiry: tell the customer the net was widened and offer the job again, instead of
  // the customer having to notice the ticket died and tap manual retry.
  private void announceAutoExpand(SosRequest s, UUID userId) {
    UUID sosId = s.getSosId();
    int newRadiusKm = s.getBroadcastRadiusKm();
    // Previous window's route estimate (if any) no longer applies to a freshly widened search.
    routeEtaService.clear(sosId);

    int attemptsLeft = maxRebroadcast - s.getRebroadcastCount();
    AfterCommit.run(
        () ->
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
                    attemptsLeft)));
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
  }

  private void announceExpiry(SosRequest s, UUID userId, List<RejectedBid> closedBids) {
    UUID sosId = s.getSosId();
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
    AfterCommit.run(
        () ->
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
                    maxRebroadcast - s.getRebroadcastCount())));
    notifyBiddersOfExpiry(sosId, closedBids);
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
    AfterCommit.run(
        () ->
            messagingTemplate.convertAndSendToUser(
                userId.toString(),
                "/queue/sos-updates",
                Map.of(
                    "type", "SOS_AUTO_REBROADCAST", "sosId", sosId, "newRadiusKm", newRadiusKm)));
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
      throw new ConflictException("You already have an active SOS request");
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
    // update below is its own short write. Only the address column: `sos` is the copy from before
    // the call, and saving it whole would undo a cancel or an accept that landed meanwhile. The
    // copy keeps the address for the created event and the response.
    String address = googleMapsService.reverseGeocode(request.latitude(), request.longitude());
    if (address != null) {
      sos.setLocationAddress(address);
      sosRequestRepository.updateLocationAddress(sos.getSosId(), address, LocalDateTime.now());
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
    // RACE GUARD: create's first check is unlocked, so a rebroadcast (or, with Redis down, a second
    // create) can commit a live ticket after it. The customer's row lock is the one rebroadcast
    // takes too; the check repeated under it sees what the other committed. A missing user still
    // fails below, as before.
    userRepository.lockById(request.userId());
    if (sosRequestRepository.existsByUserUserIdAndStatusIn(request.userId(), ACTIVE_STATUSES)) {
      throw new ConflictException("You already have an active SOS request");
    }
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
                Geo.WGS84.createPoint(new Coordinate(request.longitude(), request.latitude())))
            .broadcastRadiusKm(startingRadiusKm)
            .biddingExpiresAt(LocalDateTime.now().plusSeconds(biddingWindowSeconds))
            .user(user)
            .vehicle(vehicle)
            .build();
    sosRequestRepository.save(sos);
    return sos;
  }

  // The owner and the mechanic whose bid is accepted: narrower than isVisibleTo, which also lets
  // every bidder and every eligible mechanic in. A released mechanic's bid is CANCELLED, so they
  // lose access with the job. Not 403: telling an outsider "forbidden" tells them the id is real.
  @Override
  @Transactional(readOnly = true)
  public List<SosStatusHistoryEntry> getHistory(UUID sosId, UUID callerId) {
    SosRequest sos = findSosOrThrow(sosId);
    boolean assigned =
        bidSosRepository
            .findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED)
            .map(bid -> bid.getMechanic().getMechanicId().equals(callerId))
            .orElse(false);
    if (!sos.getUser().getUserId().equals(callerId) && !assigned) {
      throw new EntityNotFoundException("SOS request not found: " + sosId);
    }
    List<SosStatusHistory> rows =
        sosStatusHistoryRepository.findBySosRequestSosIdOrderByChangeAtAsc(sosId);
    List<SosStatusHistoryEntry> entries =
        new ArrayList<>(
            rows.stream()
                .map(
                    h ->
                        new SosStatusHistoryEntry(
                            h.getFromStatus(),
                            h.getToStatus(),
                            h.getApproveAt() != null ? h.getApproveAt() : h.getChangeAt(),
                            h.getApprovedStatus()))
                .toList());

    // A customer cancel writes no row (proposed_by is a mechanic and NOT NULL), so its step is
    // rebuilt from the ticket: from the last state that actually took effect (rejected proposals,
    // including the ones the cancel itself closed, do not count), at the cancel's update time.
    // The no-show cancel already has its own row. ponytail: updatedAt can be a few seconds late if
    // the cancel landed during create's address lookup; a cancelled_at column fixes that if needed.
    if (sos.getStatus() == SosStatus.CANCELLED) {
      SosStatus from = SosStatus.PENDING;
      for (SosStatusHistory h : rows) {
        if (h.getApprovedStatus() == ApprovedStatus.ACCEPTED) from = h.getToStatus();
      }
      if (from != SosStatus.CANCELLED) {
        entries.add(
            new SosStatusHistoryEntry(
                from, SosStatus.CANCELLED, sos.getUpdatedAt(), ApprovedStatus.ACCEPTED));
      }
    }
    return entries;
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
    boolean owner = sos.getUser().getUserId().equals(callerId);
    // The customer's contact details and the assigned mechanic's are for the two people on the
    // job. A mechanic still deciding whether to bid sees the job, not the people.
    boolean assigned =
        response.mechanic() != null && response.mechanic().mechanicId().equals(callerId);
    if (!owner && !assigned) {
      return response.withoutParties();
    }
    // The owner needs the historyId of a waiting request to answer it: completion while the job is
    // IN_PROGRESS, arrival while the mechanic is still ON_THE_WAY (the field keeps its old name).
    // The push and the COMPLETION_REQUESTED / ARRIVAL_REQUESTED frame carry it too, but a missed
    // frame must not leave the job stuck.
    if ((sos.getStatus() == SosStatus.IN_PROGRESS || sos.getStatus() == SosStatus.ON_THE_WAY)
        && sos.getUser().getUserId().equals(callerId)) {
      return sosStatusHistoryRepository
          .findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
              sosId, ApprovedStatus.PENDING_APPROVAL)
          .map(
              history -> response.withPendingCompletion(history.getId(), approvalDeadline(history)))
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
  public List<MechanicSummaryResponse> getNearbyMechanics(UUID sosId, UUID callerId) {
    SosRequest sos = findSosOrThrow(sosId);
    // Owner only. Bidders and eligible mechanics can read the ticket itself, but everyone on this
    // list is their competitor. 404 rather than 403, as for any SOS the caller may not see.
    if (!sos.getUser().getUserId().equals(callerId)) {
      throw new EntityNotFoundException("SOS request not found: " + sosId);
    }

    Point location = sos.getLocation();
    int radiusKm =
        sos.getBroadcastRadiusKm() != null ? sos.getBroadcastRadiusKm() : defaultBroadcastKm;
    return findMatchedMechanics(location.getY(), location.getX(), sos.getProblemType(), radiusKm)
        .stream()
        .map(mechanicMapper::toSummary)
        .toList();
  }

  // Read-only H3 + skill-tag + radius match, identical criteria to isEligibleMechanic/
  // fanOutToNearbyMechanics but without their notification side effects — safe to call from a
  // GET.
  private List<Mechanic> findMatchedMechanics(
      double lat, double lng, String problemType, int radiusKm) {
    String sosCell = H3.geoToH3Address(lat, lng, h3Resolution);
    List<String> searchCells = H3.kRing(sosCell, Geo.ringRadiusFor(radiusKm, h3CenterToCenterKm));
    List<UUID> coarse =
        mechanicRepository.findByH3CellsAndSkillTag(searchCells, problemType, locationFreshSince());
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

  @Override
  @Transactional(readOnly = true)
  public boolean isOwner(UUID sosId, UUID callerId) {
    return sosRequestRepository
        .findById(sosId)
        .map(sos -> sos.getUser().getUserId().equals(callerId))
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
    if (bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicIdAndStatusIn(
        sos.getSosId(), callerId, LIVE_BID_STATUSES)) {
      return true;
    }
    // Accept does not clear biddingExpiresAt, so the window alone would keep the ticket (and the
    // assigned mechanic on it) open to every nearby mechanic for the rest of the minute.
    if (sos.getStatus() != SosStatus.PENDING
        || sos.getBiddingExpiresAt() == null
        || !LocalDateTime.now().isBefore(sos.getBiddingExpiresAt())) {
      return false;
    }
    return isEligibleMechanic(sos, callerId);
  }

  @Override
  public boolean isEligibleMechanic(SosRequest sos, UUID callerId) {
    Point location = sos.getLocation();
    if (location == null || sos.getProblemType() == null) {
      return false;
    }

    double lat = location.getY();
    double lng = location.getX();

    int effectiveRadiusKm =
        sos.getBroadcastRadiusKm() != null ? sos.getBroadcastRadiusKm() : defaultBroadcastKm;
    String sosCell = H3.geoToH3Address(lat, lng, h3Resolution);
    List<String> searchCells =
        H3.kRing(sosCell, Geo.ringRadiusFor(effectiveRadiusKm, h3CenterToCenterKm));

    // One mechanic, asked about by id: the same predicates as the area matcher, at a cost that does
    // not grow with the mechanics around the ticket. No broadcast radius: only the mechanic's own
    // radius limits them (what filterByRadius did).
    double radiusM =
        sos.getBroadcastRadiusKm() != null
            ? sos.getBroadcastRadiusKm() * 1000.0
            : NO_BROADCAST_CAP_M;
    return mechanicRepository.isEligible(
        callerId, searchCells, sos.getProblemType(), locationFreshSince(), lat, lng, radiusM);
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
  // Only ON_THE_WAY and IN_PROGRESS flow through here; ARRIVED and COMPLETED need the customer's
  // answer, via proposeArrival and proposeStatusChange.
  @Override
  @Transactional
  public SosRequestResponse updateStatus(UUID sosId, UUID mechanicId, SosStatus toStatus) {
    if (toStatus != SosStatus.ON_THE_WAY && toStatus != SosStatus.IN_PROGRESS) {
      // The requested target status is not one this endpoint accepts: a bad request (400), not a
      // conflict with the ticket's current state.
      throw new BadRequestException(
          "Only ON_THE_WAY and IN_PROGRESS can be set directly. Use POST /api/v1/sos/{sosId}/arrival for ARRIVED and POST /api/v1/sos/{sosId}/completion for COMPLETED.");
    }
    // Same ticket lock a mechanic's release takes, so ARRIVED and a release cannot both win.
    sosRequestRepository.lockStatus(sosId);
    SosRequest sos = findSosOrThrow(sosId);
    validateTransition(sos.getStatus(), toStatus);
    Mechanic mechanic =
        mechanicRepository
            .findById(mechanicId)
            .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
    requireAssignedMechanic(sos, mechanicId);

    SosStatus fromStatus = sos.getStatus();
    sos.setStatus(toStatus);
    sosRequestRepository.save(sos);

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
    AfterCommit.run(
        () ->
            messagingTemplate.convertAndSendToUser(
                sos.getUser().getUserId().toString(), "/queue/sos-updates", statusEvent));
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
      throw new BadRequestException(
          "Only COMPLETED requires customer approval here. Use POST /api/v1/sos/{sosId}/arrival for ARRIVED and PATCH /api/v1/sos/{sosId}/status for ON_THE_WAY and IN_PROGRESS.");
    }
    // Same ticket lock a cancel, a release and proposeArrival take, so a proposal cannot be written
    // for a ticket that was just cancelled or released.
    sosRequestRepository.lockStatus(sosId);
    return propose(findSosOrThrow(sosId), request.mechanicId(), request.toStatus());
  }

  // ARRIVED works like COMPLETED: the mechanic says they are there, the customer gets a pop-up and
  // confirms, and a customer who does not answer is confirmed automatically after
  // sos.arrival.approval-window-seconds. The ticket stays ON_THE_WAY until then.
  //
  // Not @Transactional: the road-distance check calls Google (up to ~7 s), and holding the ticket
  // lock and a connection across it made a cancel, the customer's answer and expiry on this ticket
  // wait. The cheap checks run first without the lock, so an outsider or a retry never costs a Maps
  // call; Google runs outside any transaction; then propose() checks everything again under the
  // same ticket lock a cancel, a release and the customer's answer take, so a cancel that landed
  // during the call still wins.
  @Override
  public SosStatusHistoryResponse proposeArrival(UUID sosId, UUID mechanicId) {
    ArrivalCheck check = transactionTemplate.execute(status -> arrivalCheck(sosId, mechanicId));
    if (check.waiting() != null) return check.waiting();
    requireMechanicAtSos(check.mechanic(), check.sos());
    return transactionTemplate.execute(
        status -> {
          sosRequestRepository.lockStatus(sosId);
          return propose(findSosOrThrow(sosId), mechanicId, SosStatus.ARRIVED);
        });
  }

  /** What proposeArrival needs before the Maps call, or the request already waiting. */
  private record ArrivalCheck(
      Mechanic mechanic, SosRequest sos, SosStatusHistoryResponse waiting) {}

  // The same checks propose() makes, in the same order, without the ticket lock.
  private ArrivalCheck arrivalCheck(UUID sosId, UUID mechanicId) {
    SosRequest sos = findSosOrThrow(sosId);
    validateTransition(sos.getStatus(), SosStatus.ARRIVED);
    Mechanic mechanic =
        mechanicRepository
            .findById(mechanicId)
            .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
    requireAssignedMechanic(sos, mechanicId);
    return sosStatusHistoryRepository
        .findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
            sosId, ApprovedStatus.PENDING_APPROVAL)
        .map(w -> new ArrivalCheck(null, null, sosStatusHistoryMapper.toResponse(w)))
        .orElse(new ArrivalCheck(mechanic, sos, null));
  }

  /** A mechanic's request to move the ticket to ARRIVED or COMPLETED, waiting for the customer. */
  private SosStatusHistoryResponse propose(SosRequest sos, UUID mechanicId, SosStatus toStatus) {
    UUID sosId = sos.getSosId();
    validateTransition(sos.getStatus(), toStatus);
    Mechanic mechanic =
        mechanicRepository
            .findById(mechanicId)
            .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
    requireAssignedMechanic(sos, mechanicId);
    if (toStatus == SosStatus.COMPLETED
        && minInProgressSeconds > 0
        && sos.getJobStartedAt() != null
        && sos.getJobStartedAt().plusSeconds(minInProgressSeconds).isAfter(LocalDateTime.now())) {
      throw new ConflictException(
          "The job was started less than " + minInProgressSeconds + " seconds ago");
    }
    // A retry (double tap, timeout) must not leave two approvable rows or push the customer twice:
    // the request that is already waiting is the answer.
    var alreadyWaiting =
        sosStatusHistoryRepository.findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
            sosId, ApprovedStatus.PENDING_APPROVAL);
    if (alreadyWaiting.isPresent()) {
      return sosStatusHistoryMapper.toResponse(alreadyWaiting.get());
    }
    // ARRIVED's distance check ran before the lock (proposeArrival), so Google is never called
    // here.
    SosStatusHistory history =
        SosStatusHistory.builder()
            .sosRequest(sos)
            .fromStatus(sos.getStatus())
            .toStatus(toStatus)
            .proposedBy(mechanic)
            .proposedAt(LocalDateTime.now())
            .approvedStatus(ApprovedStatus.PENDING_APPROVAL)
            .build();
    sosStatusHistoryRepository.save(history);

    boolean arrival = toStatus == SosStatus.ARRIVED;
    String type = arrival ? "ARRIVAL_REQUESTED" : "COMPLETION_REQUESTED";
    AfterCommit.run(
        () ->
            messagingTemplate.convertAndSendToUser(
                sos.getUser().getUserId().toString(),
                "/queue/sos-updates",
                Map.of(
                    "type",
                    type,
                    "sosId",
                    sos.getSosId(),
                    "historyId",
                    history.getId(),
                    "approveBy",
                    approvalDeadline(history).toString())));
    sosEventProducer.publishNotification(
        new SosNotificationEvent(
            type,
            sos.getUser().getUserId(),
            "USER",
            sos.getSosId(),
            arrival ? "Your mechanic has arrived" : "Completion requested",
            arrival
                ? "Your mechanic says they are at your location. Please confirm."
                : "Your mechanic has marked the service complete. Please approve."));
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
      if (history.getToStatus() == SosStatus.ARRIVED) {
        requireStillAssigned(sos, history);
      }
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
      AfterCommit.run(
          () ->
              messagingTemplate.convertAndSendToUser(
                  mechanicId.toString(), "/queue/sos-updates", statusEvent));

      // A customer who just tapped "confirm" does not need a push saying the mechanic arrived.
      if (!(history.getToStatus() == SosStatus.ARRIVED && approver != null)) {
        publishStatusNotifications(history.getToStatus(), sos, mechanicId);
      }
    } else if (decision == ApprovedStatus.REJECTED) {
      UUID mechanicId = history.getProposedBy().getMechanicId();
      boolean arrival = history.getToStatus() == SosStatus.ARRIVED;
      String type = arrival ? "ARRIVAL_REJECTED" : "COMPLETION_REJECTED";
      AfterCommit.run(
          () ->
              messagingTemplate.convertAndSendToUser(
                  mechanicId.toString(),
                  "/queue/sos-updates",
                  Map.of("type", type, "sosId", sos.getSosId(), "historyId", history.getId())));
      sosEventProducer.publishNotification(
          new SosNotificationEvent(
              type,
              mechanicId,
              "MECHANIC",
              sos.getSosId(),
              arrival ? "Arrival not confirmed" : "Completion rejected",
              arrival
                  ? "The customer says you are not at their location."
                  : "The customer has rejected the service completion request."));
    }
  }

  // An arrival request belongs to the mechanic who made it. If the job was released and taken by
  // someone else since, the old request must not move the new mechanic's ticket.
  private void requireStillAssigned(SosRequest sos, SosStatusHistory history) {
    if (!stillAssigned(sos, history)) {
      throw new ConflictException("This arrival request is no longer valid");
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
    LocalDateTime now = LocalDateTime.now();
    // Arrival and completion requests have their own windows; fetch from the shorter one and let
    // each row wait for its own.
    LocalDateTime cutoff =
        now.minusSeconds(Math.min(completionApprovalWindowSeconds, arrivalApprovalWindowSeconds));
    for (SosStatusHistory overdue :
        sosStatusHistoryRepository.findByApprovedStatusAndProposedAtBefore(
            ApprovedStatus.PENDING_APPROVAL, cutoff)) {
      if (approvalDeadline(overdue).isAfter(now)) continue;
      try {
        transactionTemplate.executeWithoutResult(status -> autoApproveCompletion(overdue.getId()));
      } catch (RuntimeException e) {
        // One bad row must not stop the others; it is retried on the next sweep.
        log.error("Auto-approving request {} failed: {}", overdue.getId(), e.getMessage());
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
    boolean arrival = history.getToStatus() == SosStatus.ARRIVED;
    SosStatus expected = arrival ? SosStatus.ON_THE_WAY : SosStatus.IN_PROGRESS;
    if (sos.getStatus() != expected || (arrival && !stillAssigned(sos, history))) {
      // Cancelled, released or otherwise moved on: nothing to apply. Close it so the sweep stops
      // reading it every cycle.
      history.setApprovedStatus(ApprovedStatus.REJECTED);
      history.setApproveAt(LocalDateTime.now());
      sosStatusHistoryRepository.save(history);
      return;
    }
    log.warn(
        "{} request {} for SOS {} unanswered after {} s, approving automatically",
        arrival ? "Arrival" : "Completion",
        historyId,
        sos.getSosId(),
        approvalWindowSeconds(history));
    decideCompletion(history, ApprovedStatus.ACCEPTED, null);
    String type = arrival ? "ARRIVAL_AUTO_CONFIRMED" : "COMPLETION_AUTO_APPROVED";
    AfterCommit.run(
        () ->
            messagingTemplate.convertAndSendToUser(
                sos.getUser().getUserId().toString(),
                "/queue/sos-updates",
                Map.of("type", type, "sosId", sos.getSosId())));
  }

  private boolean stillAssigned(SosRequest sos, SosStatusHistory history) {
    return bidSosRepository
        .findFirstBySosRequestSosIdAndStatus(sos.getSosId(), BidStatus.ACCEPTED)
        .map(
            bid ->
                bid.getMechanic().getMechanicId().equals(history.getProposedBy().getMechanicId()))
        .orElse(false);
  }

  /** How long the customer has to answer this kind of request before the system answers. */
  private long approvalWindowSeconds(SosStatusHistory history) {
    return history.getToStatus() == SosStatus.ARRIVED
        ? arrivalApprovalWindowSeconds
        : completionApprovalWindowSeconds;
  }

  /** When an unanswered request is approved automatically. */
  private LocalDateTime approvalDeadline(SosStatusHistory history) {
    return history.getProposedAt() == null
        ? null
        : history.getProposedAt().plusSeconds(approvalWindowSeconds(history));
  }

  /** Finalizes or discards a pending milestone update based on the client's explicit response. */
  @Override
  @Transactional
  public SosRequestResponse approveStatusChange(ApproveStatusChangeRequest request) {
    // PENDING_APPROVAL is a state, not an answer: decideCompletion would stamp approver and time on
    // a row that stays pending, change nothing, and still answer 200.
    if (request.approvedStatus() == ApprovedStatus.PENDING_APPROVAL) {
      throw new BadRequestException("approvedStatus must be ACCEPTED or REJECTED");
    }
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
      throw new ConflictException("This request was already " + current);
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
    // Same ticket lock a mechanic's release takes, so the two cannot both win.
    sosRequestRepository.lockStatus(sosId);
    SosRequest sos = findSosOrThrow(sosId);
    if (!sos.getUser().getUserId().equals(userId)) {
      throw new AccessDeniedException("You can only cancel your own SOS request");
    }
    SosStatus fromStatus = sos.getStatus();
    if (!VALID_TRANSITIONS.containsKey(fromStatus)) {
      throw new ConflictException("Cannot cancel a request with status: " + fromStatus);
    }
    sos.setStatus(SosStatus.CANCELLED);
    sosRequestRepository.save(sos);
    routeEtaService.clear(sosId); // job is over — drop any countdown still cached

    // Requests the mechanic made and the customer has not answered end with the ticket. Closing
    // them under this lock is what lets a concurrent approveStatusChange, which re-reads the row
    // after waiting for the lock, see REJECTED and refuse instead of applying it to a dead ticket.
    // propose() keeps it to one row per ticket; closing all of them also covers older data.
    LocalDateTime closedAt = LocalDateTime.now();
    List<SosStatusHistory> waiting =
        sosStatusHistoryRepository.findBySosRequestSosIdAndApprovedStatus(
            sosId, ApprovedStatus.PENDING_APPROVAL);
    waiting.forEach(
        row -> {
          row.setApprovedStatus(ApprovedStatus.REJECTED);
          row.setApproveAt(closedAt);
        });
    sosStatusHistoryRepository.saveAll(waiting);

    // Whoever was on the job, or had bid on it, must hear it is off: an assigned mechanic would
    // otherwise keep driving to a cancelled job.
    UUID assignedMechanicId =
        bidSosRepository
            .findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED)
            .map(bid -> bid.getMechanic().getMechanicId())
            .orElse(null);
    // Their bids are over with the request: leaving them PENDING showed a live bid on a dead
    // ticket.
    List<UUID> pendingBidders =
        rejectPendingBids(sosId).stream().map(RejectedBid::mechanicId).toList();

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
   * The mechanic has been at the breakdown for sos.customer-wait.minutes since ARRIVED took effect
   * and the customer never came to the car: they may end the job. The ticket becomes CANCELLED, so
   * the mechanic is free again, and the customer is told. Ending it is the mechanic's call, never
   * the system's, so a customer who is only slow to answer is not cancelled on.
   */
  @Override
  @Transactional
  public SosRequestResponse reportCustomerNoShow(UUID sosId, UUID mechanicId) {
    if (customerWaitMinutes <= 0) {
      throw new ConflictException("Ending a job because the customer is not there is off");
    }
    // Same ticket lock a cancel, the customer's answer and a release take.
    sosRequestRepository.lockStatus(sosId);
    SosRequest sos = findSosOrThrow(sosId);
    requireAssignedMechanic(sos, mechanicId);
    if (sos.getStatus() != SosStatus.ARRIVED) {
      throw new ConflictException(
          "A job can only be ended for a missing customer after ARRIVED, current status: "
              + sos.getStatus());
    }
    LocalDateTime arrivedAt =
        sosStatusHistoryRepository
            .findFirstBySosRequestSosIdAndToStatusAndApprovedStatusOrderByChangeAtDesc(
                sosId, SosStatus.ARRIVED, ApprovedStatus.ACCEPTED)
            .map(h -> h.getApproveAt() != null ? h.getApproveAt() : h.getChangeAt())
            .orElseThrow(() -> new ConflictException("No arrival on record for this job"));
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime readyAt = arrivedAt.plusMinutes(customerWaitMinutes);
    if (readyAt.isAfter(now)) {
      long minutesLeft = (Duration.between(now, readyAt).toSeconds() + 59) / 60;
      throw new ConflictException(
          "The customer has " + minutesLeft + " more minute(s) to reach you");
    }
    Mechanic mechanic =
        mechanicRepository
            .findById(mechanicId)
            .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
    requireMechanicStillAtSos(mechanic, sos);

    sos.setStatus(SosStatus.CANCELLED);
    sosRequestRepository.save(sos);
    routeEtaService.clear(sosId);
    sosStatusHistoryRepository.save(
        SosStatusHistory.builder()
            .sosRequest(sos)
            .fromStatus(SosStatus.ARRIVED)
            .toStatus(SosStatus.CANCELLED)
            .proposedBy(mechanic)
            .proposedAt(now)
            .approvedStatus(ApprovedStatus.ACCEPTED)
            .approveAt(now)
            .build());

    UUID userId = sos.getUser().getUserId();
    sosEventProducer.publishStatusUpdated(
        new SosStatusUpdatedEvent(
            sosId, mechanicId, userId, SosStatus.ARRIVED, SosStatus.CANCELLED, now));
    AfterCommit.run(
        () ->
            messagingTemplate.convertAndSendToUser(
                userId.toString(),
                "/queue/sos-updates",
                Map.of("type", "CUSTOMER_NO_SHOW", "sosId", sosId, "fromStatus", "ARRIVED")));
    sosEventProducer.publishNotification(
        new SosNotificationEvent(
            "CUSTOMER_NO_SHOW",
            userId,
            "USER",
            sosId,
            "Your mechanic left",
            "Your mechanic waited and could not meet you, so the request was closed."));
    return toResponseWithMechanic(sos);
  }

  /**
   * The mechanic is still where the ticket is. Straight-line, no Google call: this runs inside a
   * transaction, and straight-line never exceeds road distance so it is the more lenient of the two
   * checks. Stops "mark arrived, drive off, report a no-show".
   */
  private void requireMechanicStillAtSos(Mechanic mechanic, SosRequest sos) {
    Point at = mechanic.getCurrentLocation();
    if (at == null) {
      throw new ConflictException("No location on record for this mechanic");
    }
    double meters =
        GeoDistance.haversineMeters(
            at.getY(), at.getX(), sos.getLocation().getY(), sos.getLocation().getX(), earthRadiusM);
    if (meters > arrivalMaxDistanceMeters) {
      throw new ConflictException(
          String.format(
              "You are %dm from the customer; stay within %dm to report a missing customer.",
              Math.round(meters), arrivalMaxDistanceMeters));
    }
  }

  private record RejectedBid(UUID bidId, UUID mechanicId) {}

  /**
   * Closes every PENDING bid on the ticket as REJECTED, for a cancelled or an expired request, and
   * tells Kafka. REJECTED because there is no status for "the request ended"; the frame the
   * mechanic gets says why. Runs inside a transaction (the mechanic is read lazily).
   */
  private List<RejectedBid> rejectPendingBids(UUID sosId) {
    List<BidSos> pending =
        bidSosRepository.findBySosRequestSosIdAndStatus(sosId, BidStatus.PENDING);
    if (pending.isEmpty()) return List.of();
    LocalDateTime now = LocalDateTime.now();
    pending.forEach(
        b -> {
          b.setStatus(BidStatus.REJECTED);
          b.setRespondedAt(now);
        });
    bidSosRepository.saveAll(pending);
    sosEventProducer.publishBidRejected(
        new BidRejectedEvent(sosId, pending.stream().map(BidSos::getBidId).toList()));
    return pending.stream()
        .map(b -> new RejectedBid(b.getBidId(), b.getMechanic().getMechanicId()))
        .toList();
  }

  /** The request ran out of time: every mechanic who had a bid on it hears so (frame, one push). */
  private void notifyBiddersOfExpiry(UUID sosId, List<RejectedBid> closed) {
    if (closed.isEmpty()) return;
    List<UUID> bidders = closed.stream().map(RejectedBid::mechanicId).toList();
    for (UUID bidder : bidders) {
      AfterCommit.run(
          () ->
              messagingTemplate.convertAndSendToUser(
                  bidder.toString(),
                  "/queue/sos-updates",
                  Map.of("type", "SOS_EXPIRED", "sosId", sosId)));
    }
    sosEventProducer.publishNotification(
        new SosNotificationEvent(
            "SOS_EXPIRED",
            null,
            "MECHANIC",
            sosId,
            "Request expired",
            "The request you bid on ran out of time",
            null,
            null,
            bidders));
  }

  /**
   * Tells the assigned mechanic (a frame and a push) and every mechanic with a pending bid (a frame
   * each and one batched push) that the customer cancelled. The frame carries the status the
   * request was in, so the app can tell "my job was cancelled" from "a request I bid on was".
   */
  private void notifyMechanicsOfCancellation(
      SosRequest sos, SosStatus fromStatus, UUID assignedMechanicId, List<UUID> pendingBidders) {
    if (assignedMechanicId != null) {
      AfterCommit.run(
          () ->
              messagingTemplate.convertAndSendToUser(
                  assignedMechanicId.toString(),
                  "/queue/sos-updates",
                  Map.of(
                      "type",
                      "SOS_CANCELLED",
                      "sosId",
                      sos.getSosId(),
                      "fromStatus",
                      fromStatus.name())));
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
        AfterCommit.run(
            () ->
                messagingTemplate.convertAndSendToUser(
                    bidder.toString(),
                    "/queue/sos-updates",
                    Map.of(
                        "type",
                        "SOS_CANCELLED",
                        "sosId",
                        sos.getSosId(),
                        "fromStatus",
                        fromStatus.name())));
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
    MechanicResponse mechanic =
        bidSosRepository
            .findFirstBySosRequestSosIdAndStatus(sos.getSosId(), BidStatus.ACCEPTED)
            .map(bid -> mechanicMapper.toResponse(bid.getMechanic()))
            .orElse(null);
    return withMechanic(sos, mechanic);
  }

  private SosRequestResponse withMechanic(SosRequest sos, MechanicResponse mechanic) {
    SosRequestResponse base = sosMapper.toResponse(sos);
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

  // One query for the whole page instead of one per row.
  private Page<SosRequestResponse> toPageWithMechanic(Page<SosRequest> page) {
    List<UUID> ids = page.getContent().stream().map(SosRequest::getSosId).toList();
    Map<UUID, MechanicResponse> assigned = new HashMap<>();
    if (!ids.isEmpty()) {
      for (BidSos bid :
          bidSosRepository.findWithMechanicBySosIdsAndStatus(ids, BidStatus.ACCEPTED)) {
        assigned.put(bid.getSosRequest().getSosId(), mechanicMapper.toResponse(bid.getMechanic()));
      }
    }
    return page.map(sos -> withMechanic(sos, assigned.get(sos.getSosId())));
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
      throw new ConflictException(
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
      throw new ConflictException(
          String.format(
              "Cannot mark arrived: %dm from the customer, which exceeds the %dm limit.",
              Math.round(distanceMeters), arrivalMaxDistanceMeters));
    }
  }

  private void validateTransition(SosStatus from, SosStatus to) {
    Set<SosStatus> allowed = VALID_TRANSITIONS.getOrDefault(from, Set.of());
    if (!allowed.contains(to)) {
      throw new ConflictException("Invalid status transition: " + from + " → " + to);
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
    List<String> searchCells = H3.kRing(sosCell, Geo.ringRadiusFor(radiusKm, h3CenterToCenterKm));
    return mechanicRepository
        .findByH3CellsAndSkillTag(searchCells, problemType, locationFreshSince())
        .isEmpty();
  }

  private void fanOutToNearbyMechanics(
      UUID sosId, double lat, double lng, String problemType, Integer broadcastRadiusKm) {
    fanOutToNearbyMechanics(sosId, lat, lng, problemType, broadcastRadiusKm, Set.of());
  }

  private void fanOutToNearbyMechanics(
      UUID sosId,
      double lat,
      double lng,
      String problemType,
      Integer broadcastRadiusKm,
      Set<UUID> excludeMechanicIds) {
    int effectiveRadiusKm = broadcastRadiusKm != null ? broadcastRadiusKm : defaultBroadcastKm;
    String sosCell = H3.geoToH3Address(lat, lng, h3Resolution);
    List<String> searchCells =
        H3.kRing(sosCell, Geo.ringRadiusFor(effectiveRadiusKm, h3CenterToCenterKm));

    List<UUID> coarse =
        mechanicRepository.findByH3CellsAndSkillTag(searchCells, problemType, locationFreshSince());
    if (coarse.isEmpty()) return;

    List<UUID> inRange =
        broadcastRadiusKm != null
            ? mechanicRepository.filterByRadiusAndBroadcast(
                coarse, lat, lng, broadcastRadiusKm * 1000.0)
            : mechanicRepository.filterByRadius(coarse, lat, lng);
    // A mechanic who released this ticket cannot bid on it again (BidServiceImpl.priorBid), so
    // never offer it to them, whichever path is fanning out.
    Set<UUID> releasers =
        sosStatusHistoryRepository.findMechanicIdsByToStatus(sosId, SosStatus.PENDING);
    List<UUID> matchedIds =
        inRange.stream()
            .filter(id -> !excludeMechanicIds.contains(id) && !releasers.contains(id))
            .toList();
    if (matchedIds.isEmpty()) return;
    // Entities only for the offer estimates (they need each mechanic's location); one query.
    List<Mechanic> matched = mechanicRepository.findAllWithRateById(matchedIds);

    // Photos are uploaded after the ticket is created, so this is empty on the first broadcast
    // and populated on a rebroadcast — which is the attempt where the mechanic most needs to see
    // what the job actually is before bidding.
    List<String> photoUrls = sosMediaService.getObject().photoUrls(sosId);
    Map<UUID, EtaInfo> estimates = offerEstimates(sosId, matched, lat, lng);
    // Straight-line radius ignores the road network: drop anyone the estimate says is too far to
    // drive. No estimate (beyond the nearest few, or Google down) never blocks an offer.
    List<Mechanic> reachable =
        maxRoadEtaMinutes <= 0
            ? matched
            : matched.stream()
                .filter(
                    m -> {
                      EtaInfo eta = estimates.get(m.getMechanicId());
                      return eta == null || eta.remainingDurationS() <= maxRoadEtaMinutes * 60L;
                    })
                .toList();
    reachable.forEach(
        m ->
            messagingTemplate.convertAndSendToUser(
                m.getMechanicId().toString(),
                "/queue/sos-new-request",
                offerFrame(sosId, problemType, photoUrls, estimates.get(m.getMechanicId()))));
    recordOffers(sosId, reachable);
  }

  // G12: who was offered, so an offer never answered by a bid can be counted later. A statistics
  // write must never fail or delay the offer itself.
  private void recordOffers(UUID sosId, List<Mechanic> offered) {
    if (offered.isEmpty()) return;
    try {
      sosOfferRepository.recordOffered(
          sosId, offered.stream().map(Mechanic::getMechanicId).toList());
    } catch (RuntimeException e) {
      log.warn("Could not record the offers for SOS {}: {}", sosId, e.toString());
    }
  }

  // The offer a mechanic receives, shared by the broadcast and the reconnect sync so the two cannot
  // drift apart.
  private static SosNotificationEvent offerFrame(
      UUID sosId, String problemType, List<String> photoUrls, EtaInfo estimate) {
    return new SosNotificationEvent(
        "SOS_CREATED",
        null,
        "MECHANIC",
        sosId,
        "New SOS request nearby",
        "Problem: " + problemType,
        photoUrls,
        estimate);
  }

  // A 60 s bidding window keeps the number of open tickets around one mechanic tiny, so a small cap
  // is plenty. ponytail: raise it, or page, if windows get long.
  private static final int OPEN_OFFERS_LIMIT = 20;

  @Override
  @Transactional(readOnly = true)
  public List<SosNotificationEvent> openOffersFor(UUID mechanicId) {
    Mechanic mechanic =
        mechanicRepository
            .findById(mechanicId)
            .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
    if (mechanic.getCurrentLocation() == null) return List.of();

    List<UUID> ids =
        sosRequestRepository.findOpenIdsNear(mechanicId, LocalDateTime.now(), OPEN_OFFERS_LIMIT);
    if (ids.isEmpty()) return List.of();
    Map<UUID, SosRequest> byId = new HashMap<>();
    sosRequestRepository.findAllById(ids).forEach(sos -> byId.put(sos.getSosId(), sos));

    List<SosNotificationEvent> offers = new ArrayList<>();
    for (UUID id : ids) { // the query's order: newest first
      SosRequest sos = byId.get(id);
      // The query is a cheap superset; this is the rule the broadcast itself applies.
      if (sos == null || !isEligibleMechanic(sos, mechanicId)) continue;
      offers.add(
          offerFrame(
              id,
              sos.getProblemType(),
              sosMediaService.getObject().photoUrls(id),
              readOfferEstimates(OFFER_ETA_KEY_PREFIX + id).get(mechanicId)));
    }
    return offers;
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

    Point destination = Geo.WGS84.createPoint(new Coordinate(lng, lat));
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
      AfterCommit.run(
          () ->
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
                      attemptsLeft)));

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

  private record Released(SosRequest sos, SosStatus fromStatus, SosRequestResponse response) {}

  /**
   * The assigned mechanic backs out before ARRIVED: the ticket goes back to PENDING with a fresh
   * bidding window and is offered again to everyone but the mechanic who released it. Shaped like
   * {@link #rebroadcast}: commit first, then timer, frames and fan-out, none of which may turn a
   * committed release into a 500.
   */
  @Override
  public SosRequestResponse releaseByMechanic(UUID sosId, UUID mechanicId) {
    Released released = transactionTemplate.execute(status -> releaseJob(sosId, mechanicId));
    finishRelease(released, mechanicId, false);
    return released.response();
  }

  /**
   * Everything that follows a committed release. The ticket is PENDING and committed, so none of
   * this may turn the release into a failure. {@code noShow} is the system releasing a mechanic who
   * stopped answering; that mechanic did not ask for it, so they are told as well.
   */
  private void finishRelease(Released released, UUID mechanicId, boolean noShow) {
    SosRequest sos = released.sos();
    UUID sosId = sos.getSosId();
    // First, and before anything that can fail: the bidding window must always close.
    scheduleBiddingExpiry(sos);
    // Back to bidding: the released mechanic's route is meaningless now.
    routeEtaService.clear(sosId);

    try {
      UUID userId = sos.getUser().getUserId();
      sosEventProducer.publishStatusUpdated(
          new SosStatusUpdatedEvent(
              sosId,
              mechanicId,
              userId,
              released.fromStatus(),
              SosStatus.PENDING,
              LocalDateTime.now()));
      String type = noShow ? "MECHANIC_NO_SHOW" : "MECHANIC_RELEASED";
      AfterCommit.run(
          () ->
              messagingTemplate.convertAndSendToUser(
                  userId.toString(),
                  "/queue/sos-updates",
                  Map.of(
                      "type", type, "sosId", sosId, "fromStatus", released.fromStatus().name())));
      sosEventProducer.publishNotification(
          new SosNotificationEvent(
              type,
              userId,
              "USER",
              sosId,
              noShow ? "Your mechanic is not coming" : "Mechanic released the job",
              noShow
                  ? "Your mechanic stopped moving and did not answer. We are finding you another one."
                  : "Your mechanic can no longer take this job. We are finding you another one."));
      if (noShow) {
        AfterCommit.run(
            () ->
                messagingTemplate.convertAndSendToUser(
                    mechanicId.toString(),
                    "/queue/sos-updates",
                    Map.of("type", "JOB_RELEASED", "sosId", sosId, "reason", "NO_SHOW")));
        sosEventProducer.publishNotification(
            new SosNotificationEvent(
                "JOB_RELEASED",
                mechanicId,
                "MECHANIC",
                sosId,
                "Job released",
                "You were removed from a job because you stopped moving and did not answer."));
      }
      fanOutToNearbyMechanics(
          sosId,
          sos.getLocation().getY(),
          sos.getLocation().getX(),
          sos.getProblemType(),
          sos.getBroadcastRadiusKm(),
          Set.of(mechanicId));
    } catch (RuntimeException e) {
      log.error("SOS {} was released but notifying/broadcasting failed", sosId, e);
    }
  }

  // Runs inside the transaction opened by releaseByMechanic(); keep external calls out of it.
  private Released releaseJob(UUID sosId, UUID mechanicId) {
    // RACE GUARD: the ticket lock a cancel, an ARRIVED update and a bid decision also take, so
    // whoever comes second sees the status the first one committed.
    sosRequestRepository.lockStatus(sosId);
    SosRequest sos = findSosOrThrow(sosId);

    BidSos bid =
        bidSosRepository
            .findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED)
            .filter(b -> b.getMechanic().getMechanicId().equals(mechanicId))
            .orElseThrow(
                () ->
                    new AccessDeniedException(
                        "You are not the mechanic assigned to this SOS request"));

    SosStatus fromStatus = sos.getStatus();
    if (fromStatus != SosStatus.ACCEPTED && fromStatus != SosStatus.ON_THE_WAY) {
      throw new ConflictException(
          "A job can only be released before ARRIVED, current status: " + fromStatus);
    }

    return applyRelease(sos, bid);
  }

  // The write of a release, shared by the mechanic's own release and the no-show sweep. Runs inside
  // a transaction that already holds the ticket lock.
  private Released applyRelease(SosRequest sos, BidSos bid) {
    UUID sosId = sos.getSosId();
    SosStatus fromStatus = sos.getStatus();
    LocalDateTime now = LocalDateTime.now();
    bid.setStatus(BidStatus.CANCELLED);
    bid.setRespondedAt(now);
    bidSosRepository.save(bid);
    // The other bidders were auto-rejected when this bid was accepted. Dropping those rows lets
    // them bid again: bid create refuses anyone who already has a bid row on the ticket.
    bidSosRepository.deleteBySosRequestSosIdAndStatus(sosId, BidStatus.REJECTED);
    // An arrival the customer has not answered yet belongs to the mechanic who just left.
    sosStatusHistoryRepository
        .findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
            sosId, ApprovedStatus.PENDING_APPROVAL)
        .ifPresent(
            waiting -> {
              waiting.setApprovedStatus(ApprovedStatus.REJECTED);
              waiting.setApproveAt(now);
              sosStatusHistoryRepository.save(waiting);
            });

    sos.setStatus(SosStatus.PENDING);
    sos.setBiddingExpiresAt(now.plusSeconds(biddingWindowSeconds));
    sosRequestRepository.save(sos);

    sosStatusHistoryRepository.save(
        SosStatusHistory.builder()
            .sosRequest(sos)
            .fromStatus(fromStatus)
            .toStatus(SosStatus.PENDING)
            .proposedBy(bid.getMechanic())
            .proposedAt(now)
            .approvedStatus(ApprovedStatus.ACCEPTED)
            .approveAt(now)
            .build());

    return new Released(sos, fromStatus, toResponseWithMechanic(sos));
  }

  private record NoShowStep(boolean nudge, Released released) {}

  /**
   * An assigned mechanic who stops moving is asked whether they are still coming, and released if
   * they do not answer. Moving, or never recorded (null), is never stalled. Each job is re-checked
   * under the ticket lock before anything happens, so a cancel, an ARRIVED update or the mechanic's
   * own release that lands first wins and the sweep does nothing.
   *
   * <p>ponytail: tapping "continue" only restarts the clock, so a mechanic can answer every few
   * minutes without ever moving; the customer can cancel at any time. Cap the confirmations if that
   * is ever abused.
   */
  @Scheduled(fixedDelayString = "${sos.no-show.sweep-interval-ms:30000}")
  public void checkNoShowJobs() {
    if (noShowStalledMinutes <= 0) return;
    for (BidSos candidate : bidSosRepository.findAcceptedBeforeArrival()) {
      UUID sosId = candidate.getSosRequest().getSosId();
      UUID mechanicId = candidate.getMechanic().getMechanicId();
      try {
        NoShowStep step = transactionTemplate.execute(status -> noShowStep(sosId, mechanicId));
        if (step == null) continue;
        if (step.released() != null) {
          finishRelease(step.released(), mechanicId, true);
        } else if (step.nudge()) {
          // Send first, record after: a send that fails is tried again on the next sweep.
          messagingTemplate.convertAndSendToUser(
              mechanicId.toString(),
              "/queue/sos-updates",
              Map.of(
                  "type",
                  "STILL_COMING_CHECK",
                  "sosId",
                  sosId,
                  "respondBySeconds",
                  noShowPromptResponseSeconds));
          sosEventProducer.publishNotification(
              new SosNotificationEvent(
                  "STILL_COMING_CHECK",
                  mechanicId,
                  "MECHANIC",
                  sosId,
                  "Are you still coming?",
                  "You have not moved for a while. Tell us if you are still on your way."));
          transactionTemplate.executeWithoutResult(status -> markNudged(sosId, mechanicId));
        }
      } catch (RuntimeException e) {
        // One bad job must not stop the others; it is looked at again on the next sweep.
        log.error("No-show check for SOS {} failed: {}", sosId, e.getMessage());
      }
    }
  }

  // Runs inside a transaction: decides what to do about one assigned mechanic, after the lock.
  private NoShowStep noShowStep(UUID sosId, UUID mechanicId) {
    sosRequestRepository.lockStatus(sosId);
    SosRequest sos = findSosOrThrow(sosId);
    if (sos.getStatus() != SosStatus.ACCEPTED && sos.getStatus() != SosStatus.ON_THE_WAY) {
      return null;
    }
    // A mechanic who has asked the customer to confirm their arrival is standing at the breakdown
    // on purpose; not moving is expected until the customer answers.
    if (sosStatusHistoryRepository
        .findFirstBySosRequestSosIdAndApprovedStatusOrderByChangeAtDesc(
            sosId, ApprovedStatus.PENDING_APPROVAL)
        .isPresent()) {
      return null;
    }
    BidSos bid =
        bidSosRepository
            .findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED)
            .filter(b -> b.getMechanic().getMechanicId().equals(mechanicId))
            .orElse(null);
    if (bid == null) return null;

    LocalDateTime now = LocalDateTime.now();
    LocalDateTime moved = bid.getMechanic().getLocationMovedAt();
    // The stall clock starts at acceptance, not at the mechanic's last move before it: someone
    // parked at a garage for an hour who accepts a job gets the full stalled-minutes to set off,
    // not a "still coming?" within one sweep and a release a minute later. respondedAt is when the
    // customer accepted this bid. No recorded move at all still counts as moving.
    if (moved != null && bid.getRespondedAt() != null && bid.getRespondedAt().isAfter(moved)) {
      moved = bid.getRespondedAt();
    }
    boolean stalled = moved != null && moved.isBefore(now.minusMinutes(noShowStalledMinutes));
    if (!stalled) {
      if (bid.getNoShowPromptedAt() != null) {
        bid.setNoShowPromptedAt(null); // moving again, or confirmed: the question is withdrawn
        bidSosRepository.save(bid);
      }
      return null;
    }
    if (bid.getNoShowPromptedAt() == null) return new NoShowStep(true, null);
    if (bid.getNoShowPromptedAt().isAfter(now.minusSeconds(noShowPromptResponseSeconds))) {
      return null; // asked, still within the time to answer
    }
    log.warn("SOS {}: mechanic {} stopped moving and did not answer, releasing", sosId, mechanicId);
    return new NoShowStep(false, applyRelease(sos, bid));
  }

  private void markNudged(UUID sosId, UUID mechanicId) {
    sosRequestRepository.lockStatus(sosId);
    bidSosRepository
        .findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED)
        .filter(b -> b.getMechanic().getMechanicId().equals(mechanicId))
        .filter(b -> b.getNoShowPromptedAt() == null)
        .ifPresent(
            b -> {
              b.setNoShowPromptedAt(LocalDateTime.now());
              bidSosRepository.save(b);
            });
  }

  /** The assigned mechanic answers "are you still coming?": yes. Restarts the stationary clock. */
  @Override
  @Transactional
  public SosRequestResponse confirmStillComing(UUID sosId, UUID mechanicId) {
    sosRequestRepository.lockStatus(sosId);
    SosRequest sos = findSosOrThrow(sosId);
    requireAssignedMechanic(sos, mechanicId);
    if (sos.getStatus() != SosStatus.ACCEPTED && sos.getStatus() != SosStatus.ON_THE_WAY) {
      throw new ConflictException(
          "Only a job before ARRIVED can be confirmed, current status: " + sos.getStatus());
    }
    BidSos bid =
        bidSosRepository
            .findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED)
            .orElseThrow();
    // "I am coming" counts as a sign of life, so the clock starts again from now.
    Mechanic mechanic = bid.getMechanic();
    mechanic.setLocationMovedAt(LocalDateTime.now());
    mechanicRepository.save(mechanic);
    bid.setNoShowPromptedAt(null);
    bidSosRepository.save(bid);
    return toResponseWithMechanic(sos);
  }

  // Runs inside the transaction opened by rebroadcast(); keep external calls out of it.
  private Rebroadcast reopenSos(UUID sosId, UUID userId) {
    // RACE GUARD: the customer's row lock first (the one create takes, so the two cannot each miss
    // the other's live ticket), then the ticket lock cancel, release and accept take, before the
    // ticket is read. A double tap queues behind the first reopen and then sees PENDING, so only
    // one fan-out, one timer and one count increment happen. Order: user, ticket, mechanic.
    userRepository.lockById(userId);
    sosRequestRepository.lockStatus(sosId);
    SosRequest sos = findSosOrThrow(sosId);

    if (!sos.getUser().getUserId().equals(userId)) {
      throw new AccessDeniedException("You can only rebroadcast your own SOS request");
    }
    if (sos.getStatus() != SosStatus.EXPIRED) {
      throw new ConflictException("Only EXPIRED SOS requests can be rebroadcast");
    }
    if (sos.getRebroadcastCount() >= maxRebroadcast) {
      throw new TooManyRequestsException(
          "Maximum rebroadcast attempts (" + maxRebroadcast + ") reached. Please call support.");
    }
    // Same rule as create: one live ticket per customer. This ticket is EXPIRED, so only another
    // one can match.
    if (sosRequestRepository.existsByUserUserIdAndStatusIn(userId, ACTIVE_STATUSES)) {
      throw new ConflictException("You already have an active SOS request");
    }

    // A new round: the last one's bids were closed at expiry (or turned down by the customer) and
    // would only refuse those mechanics a second bid on a job they are offered again.
    bidSosRepository.deleteBySosRequestSosIdAndStatus(sosId, BidStatus.REJECTED);

    sos.setStatus(SosStatus.PENDING);
    sos.setRebroadcastCount(sos.getRebroadcastCount() + 1);
    sos.setBiddingExpiresAt(LocalDateTime.now().plusSeconds(biddingWindowSeconds));
    sosRequestRepository.save(sos);
    return new Rebroadcast(sos, toResponseWithMechanic(sos));
  }

  /**
   * Fires when the ticket's bidding window closes. {@link #expireIfDue} re-reads the ticket under
   * its lock, so a timer that outlived its window (renewed since) does nothing; a timer that is
   * lost or throws is covered by {@link #sweepOverdueExpiry}.
   */
  private void scheduleBiddingExpiry(SosRequest sos) {
    if (sos.getBiddingExpiresAt() == null) return;
    UUID sosId = sos.getSosId();
    taskScheduler.schedule(
        () -> {
          try {
            expireIfDue(sosId);
          } catch (RuntimeException e) {
            log.error("Expiry timer for SOS {} failed, the sweep will retry", sosId, e);
          }
        },
        sos.getBiddingExpiresAt().atZone(ZoneId.systemDefault()).toInstant());
  }
}
