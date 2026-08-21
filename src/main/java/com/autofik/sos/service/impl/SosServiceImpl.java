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
import com.autofik.sos.exception.ConflictException;
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
import com.autofik.sos.service.GoogleMapsService;
import com.autofik.sos.service.MedusaService;
import com.autofik.sos.service.RouteEtaService;
import com.autofik.sos.service.SosService;
import com.autofik.sos.util.GeoDistance;
import com.autofik.sos.util.GeoSupport;
import com.autofik.sos.util.TransactionHooks;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service implementation managing the lifecycles of emergency roadside requests (SOS).
 *
 * <p>Transitions are guarded by the state machine on {@link SosStatus}. Dispatch to nearby mechanics
 * lives in {@link SosDispatchService} and the bidding-window timers in {@link SosExpiryScheduler} —
 * both are separate beans because both need to run outside this class's transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SosServiceImpl implements SosService {

    private static final String SOS_COOLDOWN_KEY_PREFIX = "sos:cooldown:";
    private static final Duration SOS_COOLDOWN = Duration.ofSeconds(60);

    // Derived from the state machine so there is one definition of "unresolved" rather than a
    // hand-maintained copy that drifts from it.
    private static final List<SosStatus> ACTIVE_STATUSES = Arrays.stream(SosStatus.values())
            .filter(SosStatus::isActive)
            .toList();

    private final SosRequestRepository sosRequestRepository;
    private final UserRepository userRepository;
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
    private final SosDispatchService sosDispatchService;
    private final SosExpiryScheduler sosExpiryScheduler;
    private final SosEventProducer sosEventProducer;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;


    // How close a mechanic must be, by road, before they may mark ARRIVED. Configurable because
    // the right value depends on the road layout: a divided boulevard can put someone standing
    // beside the customer several hundred metres away by road.
    @Value("${sos.arrival.max-distance-meters:50}")
    private int arrivalMaxDistanceMeters;

    /**
     * Spawns a new emergency roadside ticket. Enforces one active ticket per driver, throttles
     * repeat submissions through Redis, and dispatches the request to nearby mechanics.
     *
     * @throws ConflictException        if the initiating user already holds an active ticket
     * @throws TooManyRequestsException if the per-user cooldown has not elapsed
     */
    @Override
    @Transactional
    public SosRequestResponse create(CreateSosRequest request) {
        // Redis cooldown first: it is the cheapest rejection, it needs no database connection, and
        // it is what stops a double-tap from reaching the paid Maps call below.
        Boolean allowed = redisTemplate.opsForValue()
                .setIfAbsent(SOS_COOLDOWN_KEY_PREFIX + request.userId(), "1", SOS_COOLDOWN);
        if (!Boolean.TRUE.equals(allowed)) {
            throw new TooManyRequestsException("Please wait 60 seconds before submitting another SOS request");
        }
        // Redis does not roll back with Postgres. Without this, a create that fails on any of the
        // checks below still costs the driver a 60-second lockout for a request that never existed.
        TransactionHooks.afterRollback(() ->
                redisTemplate.delete(SOS_COOLDOWN_KEY_PREFIX + request.userId()));

        // Geocoded before the first database read, so this blocking HTTP call does not hold a
        // connection from the pool for its duration. Fails soft — a null address costs the UI a
        // street name, nothing more.
        String address = googleMapsService.reverseGeocode(request.latitude(), request.longitude());

        // Enforce structural restriction: a user can only have one unresolved distress event
        if (sosRequestRepository.existsByUserUserIdAndStatusIn(request.userId(), ACTIVE_STATUSES)) {
            throw new ConflictException("You already have an active SOS request");
        }

        String problemTypeKey = medusaService.resolveServiceGroupKey(request.serviceGroupId());

        // Both rows are projections of SSO, created here on first use. The vehicle read is scoped
        // by SSO to the calling user, so it doubles as the ownership check: unverified, a driver
        // could file a breakdown against a stranger's car and hand a mechanic the wrong plate.
        // This has to happen inside the request — the caller's bearer exists nowhere else.
        User user = ssoProjection.user(request.userId());
        Vehicle vehicle = ssoProjection.vehicle(request.vehicleId(), user);
        SosRequest built = SosRequest.builder()
                .problemType(problemTypeKey)
                .description(request.description())
                .location(GeoSupport.point(request.latitude(), request.longitude()))
                .locationAddress(address)
                .broadcastRadiusKm(request.broadcastRadiusKm())
                .user(user)
                .vehicle(vehicle)
                .build();
        // Must use the returned instance, not `built`: @Builder.Default sets version=0L (not
        // null), so Spring Data's version-based isNew() check sees a non-null version and treats
        // this transient row as already-persisted — save() routes to merge() instead of
        // persist(), which returns a new managed copy and leaves `built`'s generated sosId
        // permanently null.
        SosRequest sos = sosRequestRepository.save(built);

        // Kafka, the mechanic fan-out and the expiry timer all fire only once the row is committed.
        // A mechanic told about an SOS that is not yet visible gets a 404 from their next read, and
        // a rollback after this point would leave every nearby mechanic notified of a breakdown
        // that never happened.
        TransactionHooks.afterCommit(() -> {
            sosEventProducer.publishSosCreated(new SosCreatedEvent(
                    sos.getSosId(), user.getUserId(), vehicle.getId(),
                    sos.getProblemType(), sos.getDescription(),
                    request.latitude(), request.longitude(),
                    sos.getLocationAddress(), sos.getCreatedAt()
            ));
            sosDispatchService.fanOutToNearbyMechanics(sos.getSosId(), request.latitude(), request.longitude(),
                    problemTypeKey, request.broadcastRadiusKm());
            sosExpiryScheduler.schedule(sos);
        });

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
        return sosRequestRepository.findById(sosId)
                .map(sos -> isVisibleTo(sos, callerId))
                .orElse(false);
    }

    // The driver who raised it, or any mechanic who bid on it — a mechanic needs to read the SOS
    // to decide what to bid, so visibility starts at the bid rather than at the acceptance.
    private boolean isVisibleTo(SosRequest sos, UUID callerId) {
        return sos.getUser().getUserId().equals(callerId)
                || bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(sos.getSosId(), callerId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SosRequestResponse> getByUser(UUID userId, Pageable pageable) {
        return toPageWithMechanic(sosRequestRepository.findByUserUserId(userId, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SosRequestResponse> getByUserAndStatus(UUID userId, SosStatus status, Pageable pageable) {
        return toPageWithMechanic(sosRequestRepository.findByUserUserIdAndStatus(userId, status, pageable));
    }

    // Direct status update (mechanic-initiated, no user approval needed).
    // Only ON_THE_WAY and ARRIVED flow through here; COMPLETED requires user approval via proposeStatusChange.
    @Override
    @Transactional
    public SosRequestResponse updateStatus(UUID sosId, UUID mechanicId, SosStatus toStatus) {
        if (toStatus != SosStatus.ON_THE_WAY && toStatus != SosStatus.ARRIVED) {
            throw new IllegalArgumentException(
                "Only ON_THE_WAY and ARRIVED can be set directly. Use POST /api/v1/sos/{sosId}/completion for COMPLETED.");
        }
        SosRequest sos = findSosOrThrow(sosId);
        validateTransition(sos.getStatus(), toStatus);
        Mechanic mechanic = mechanicRepository.findById(mechanicId)
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
        SosStatusHistory history = SosStatusHistory.builder()
                .sosRequest(sos)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .proposedBy(mechanic)
                .proposedAt(now)
                .approvedStatus(ApprovedStatus.ACCEPTED)
                .approveAt(now)
                .build();
        sosStatusHistoryRepository.save(history);

        // Inform external subscribers via Kafka and active WebSocket connections — after commit,
        // so nobody is told about a transition that is still capable of rolling back.
        SosStatusUpdatedEvent statusEvent = new SosStatusUpdatedEvent(
                sos.getSosId(), mechanicId, sos.getUser().getUserId(),
                fromStatus, toStatus, now
        );
        UUID userId = sos.getUser().getUserId();
        TransactionHooks.afterCommit(() -> {
            sosEventProducer.publishStatusUpdated(statusEvent);
            messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/sos-updates", statusEvent);
            publishStatusNotifications(toStatus, sosId, userId, mechanicId);
        });

        return toResponseWithMechanic(sos);
    }

    // COMPLETED requires explicit user approval to prevent fraud (mechanic marking done before finishing).
    // This records a PENDING_APPROVAL history entry and notifies the user; approveStatusChange() finalizes it.
    @Override
    @Transactional
    public SosStatusHistoryResponse proposeStatusChange(UUID sosId, ProposeStatusChangeRequest request) {
        if (request.toStatus() != SosStatus.COMPLETED) {
            throw new IllegalArgumentException(
                "Only COMPLETED requires customer approval. Use PATCH /api/v1/sos/{sosId}/status for ON_THE_WAY and ARRIVED.");
        }
        SosRequest sos = findSosOrThrow(sosId);
        validateTransition(sos.getStatus(), request.toStatus());
        Mechanic mechanic = mechanicRepository.findById(request.mechanicId())
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + request.mechanicId()));
        requireAssignedMechanic(sos, request.mechanicId());
        SosStatusHistory history = SosStatusHistory.builder()
                .sosRequest(sos)
                .fromStatus(sos.getStatus())
                .toStatus(request.toStatus())
                .proposedBy(mechanic)
                .proposedAt(LocalDateTime.now())
                .approvedStatus(ApprovedStatus.PENDING_APPROVAL)
                .build();
        sosStatusHistoryRepository.save(history);

        UUID userId = sos.getUser().getUserId();
        TransactionHooks.afterCommit(() -> {
            messagingTemplate.convertAndSendToUser(
                    userId.toString(), "/queue/sos-updates",
                    Map.of("type", "COMPLETION_REQUESTED", "sosId", sosId, "historyId", history.getId())
            );
            sosEventProducer.publishNotification(new SosNotificationEvent(
                    "COMPLETION_REQUESTED", userId, "USER", sosId,
                    "Completion requested", "Your mechanic has marked the service complete. Please approve."
            ));
        });
        return sosStatusHistoryMapper.toResponse(history);
    }

    /**
     * Finalizes or discards a pending milestone update based on the client's explicit response.
     */
    @Override
    @Transactional
    public SosRequestResponse approveStatusChange(ApproveStatusChangeRequest request) {
        SosStatusHistory history = sosStatusHistoryRepository.findById(request.historyId())
                .orElseThrow(() -> new EntityNotFoundException("Status history not found: " + request.historyId()));
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + request.userId()));
        // The historyId is the only thing the caller supplies — without this, any driver could
        // approve or reject the completion of anyone else's job.
        if (!history.getSosRequest().getUser().getUserId().equals(request.userId())) {
            throw new AccessDeniedException("You can only respond to your own SOS request");
        }

        history.setApprovedStatus(request.approvedStatus());
        history.setApprovedBy(user);
        history.setApproveAt(LocalDateTime.now());
        sosStatusHistoryRepository.save(history);

        SosRequest sos = history.getSosRequest();
        UUID sosId = sos.getSosId();
        UUID mechanicId = history.getProposedBy().getMechanicId();
        if (request.approvedStatus() == ApprovedStatus.ACCEPTED) {
            SosStatus fromStatus = sos.getStatus();
            SosStatus toStatus = history.getToStatus();
            validateTransition(fromStatus, toStatus);
            sos.setStatus(toStatus);
            sosRequestRepository.save(sos);
            routeEtaService.clear(sosId);   // COMPLETED — no drive left to count down

            SosStatusUpdatedEvent statusEvent = new SosStatusUpdatedEvent(
                    sosId, mechanicId, user.getUserId(), fromStatus, toStatus, LocalDateTime.now()
            );
            TransactionHooks.afterCommit(() -> {
                sosEventProducer.publishStatusUpdated(statusEvent);
                messagingTemplate.convertAndSendToUser(mechanicId.toString(), "/queue/sos-updates", statusEvent);
                publishStatusNotifications(toStatus, sosId, user.getUserId(), mechanicId);
            });
        } else if (request.approvedStatus() == ApprovedStatus.REJECTED) {
            UUID historyId = history.getId();
            TransactionHooks.afterCommit(() -> {
                messagingTemplate.convertAndSendToUser(
                        mechanicId.toString(), "/queue/sos-updates",
                        Map.of("type", "COMPLETION_REJECTED", "sosId", sosId, "historyId", historyId)
                );
                sosEventProducer.publishNotification(new SosNotificationEvent(
                        "COMPLETION_REJECTED", mechanicId, "MECHANIC", sosId,
                        "Completion rejected", "The customer has rejected the service completion request."
                ));
            });
        }
        return toResponseWithMechanic(sos);
    }

    private void publishStatusNotifications(SosStatus toStatus, UUID sosId, UUID userId, UUID mechanicId) {
        switch (toStatus) {
            case ON_THE_WAY -> sosEventProducer.publishNotification(new SosNotificationEvent(
                    "STATUS_ON_THE_WAY", userId, "USER", sosId,
                    "Mechanic is on the way", "Your mechanic is heading to your location"));
            case ARRIVED -> sosEventProducer.publishNotification(new SosNotificationEvent(
                    "STATUS_ARRIVED", userId, "USER", sosId,
                    "Mechanic has arrived", "Your mechanic has arrived at your location"));
            case COMPLETED -> {
                sosEventProducer.publishNotification(new SosNotificationEvent(
                        "STATUS_COMPLETED", userId, "USER", sosId,
                        "Service completed", "Your repair has been completed"));
                sosEventProducer.publishNotification(new SosNotificationEvent(
                        "STATUS_COMPLETED", mechanicId, "MECHANIC", sosId,
                        "Service completed", "The service has been marked completed"));
            }
            default -> { }
        }
    }

    /**
     * Terminates an outstanding ticket before work completion.
     */
    @Override
    @Transactional
    public SosRequestResponse cancel(UUID sosId, UUID userId) {
        SosRequest sos = findSosOrThrow(sosId);
        if (!sos.getUser().getUserId().equals(userId)) {
            throw new AccessDeniedException("You can only cancel your own SOS request");
        }
        SosStatus fromStatus = sos.getStatus();
        if (fromStatus.isTerminal()) {
            throw new ConflictException("Cannot cancel a request with status: " + fromStatus);
        }
        sos.setStatus(SosStatus.CANCELLED);
        sosRequestRepository.save(sos);
        routeEtaService.clear(sosId);   // job is over — drop any countdown still cached

        TransactionHooks.afterCommit(() -> {
            sosEventProducer.publishStatusUpdated(new SosStatusUpdatedEvent(
                    sosId, null, userId, fromStatus, SosStatus.CANCELLED, LocalDateTime.now()
            ));
            sosEventProducer.publishNotification(new SosNotificationEvent(
                    "STATUS_CANCELLED", userId, "USER", sosId,
                    "SOS request cancelled", "Your SOS request has been cancelled"
            ));
        });

        return toResponseWithMechanic(sos);
    }

    // SosRequest has no direct mechanic relation — the assigned mechanic is resolved
    // from the ACCEPTED bid on this SOS request (at most one can exist).
    private SosRequestResponse toResponseWithMechanic(SosRequest sos) {
        MechanicResponse mechanic = bidSosRepository
                .findFirstBySosRequestSosIdAndStatus(sos.getSosId(), BidStatus.ACCEPTED)
                .map(bid -> mechanicMapper.toResponse(bid.getMechanic()))
                .orElse(null);
        return withMechanic(sos, mechanic);
    }

    // One query for the whole page instead of one per row: a 20-row page cost 21 round trips
    // before, plus a lazy mechanic load on each.
    private Page<SosRequestResponse> toPageWithMechanic(Page<SosRequest> page) {
        List<UUID> sosIds = page.getContent().stream().map(SosRequest::getSosId).toList();
        if (sosIds.isEmpty()) {
            return page.map(sos -> withMechanic(sos, null));
        }
        Map<UUID, MechanicResponse> assigned = bidSosRepository
                .findBySosRequestSosIdInAndStatus(sosIds, BidStatus.ACCEPTED).stream()
                .collect(Collectors.toMap(
                        bid -> bid.getSosRequest().getSosId(),
                        bid -> mechanicMapper.toResponse(bid.getMechanic()),
                        (first, duplicate) -> first));
        return page.map(sos -> withMechanic(sos, assigned.get(sos.getSosId())));
    }

    private SosRequestResponse withMechanic(SosRequest sos, MechanicResponse mechanic) {
        SosRequestResponse base = sosMapper.toResponse(sos);
        return new SosRequestResponse(
                base.sosId(), base.problemType(), base.description(), base.locationAddress(),
                base.status(), base.latitude(), base.longitude(), base.broadcastRadiusKm(),
                base.createdAt(), base.updatedAt(),
                base.biddingExpiresAt(), base.rebroadcastCount(), base.user(), base.vehicle(), mechanic
        );
    }

    // Advancing a job belongs to the mechanic whose bid was accepted, not to whoever knows the
    // sosId. Without this any mechanic could walk someone else's ticket to ARRIVED or COMPLETED.
    private void requireAssignedMechanic(SosRequest sos, UUID mechanicId) {
        boolean assigned = bidSosRepository
                .findFirstBySosRequestSosIdAndStatus(sos.getSosId(), BidStatus.ACCEPTED)
                .map(bid -> bid.getMechanic().getMechanicId().equals(mechanicId))
                .orElse(false);
        if (!assigned) {
            throw new AccessDeniedException("You are not the mechanic assigned to this SOS request");
        }
    }

    private SosRequest findSosOrThrow(UUID sosId) {
        return sosRequestRepository.findById(sosId)
                .orElseThrow(() -> new EntityNotFoundException("SOS request not found: " + sosId));
    }

    /**
     * Gate on ON_THE_WAY → ARRIVED: the mechanic must actually be at the breakdown.
     *
     * Measured against current_location in Postgres, never against coordinates supplied with the
     * request — a mechanic controls those and would simply send the customer's own position. That
     * makes this a guard against the casual "close enough, mark it arrived" tap rather than against
     * a deliberately spoofed GPS feed, which only client attestation can address.
     *
     * Road distance rather than straight-line, so a mechanic who is metres away across a river or
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
            distanceMeters = GeoDistance.haversineMeters(
                    mechanicLocation.getY(), mechanicLocation.getX(),
                    sos.getLocation().getY(), sos.getLocation().getX());
            log.warn("Route distance unavailable for SOS {}; falling back to straight-line {}m",
                    sos.getSosId(), Math.round(distanceMeters));
        }

        if (distanceMeters > arrivalMaxDistanceMeters) {
            throw new ConflictException(String.format(
                    "Cannot mark arrived: %dm from the customer, which exceeds the %dm limit.",
                    Math.round(distanceMeters), arrivalMaxDistanceMeters));
        }
    }

    private void validateTransition(SosStatus from, SosStatus to) {
        if (!from.canTransitionTo(to)) {
            throw new ConflictException("Invalid status transition: " + from + " → " + to);
        }
    }

    /**
     * Re-opens an expired request and dispatches it again. Capped at
     * {@link SosRequest#MAX_REBROADCAST} attempts.
     */
    @Override
    @Transactional
    public SosRequestResponse rebroadcast(UUID sosId, UUID userId) {
        SosRequest sos = findSosOrThrow(sosId);

        if (!sos.getUser().getUserId().equals(userId)) {
            throw new AccessDeniedException("You can only rebroadcast your own SOS request");
        }
        if (sos.getStatus() != SosStatus.EXPIRED) {
            throw new ConflictException("Only EXPIRED SOS requests can be rebroadcast");
        }
        if (sos.getRebroadcastCount() >= SosRequest.MAX_REBROADCAST) {
            throw new TooManyRequestsException(
                    "Maximum rebroadcast attempts (" + SosRequest.MAX_REBROADCAST + ") reached. Please call support.");
        }

        sos.setStatus(SosStatus.PENDING);
        sos.setRebroadcastCount(sos.getRebroadcastCount() + 1);
        sos.setBiddingExpiresAt(LocalDateTime.now().plusSeconds(SosRequest.BIDDING_WINDOW_SECONDS));
        sosRequestRepository.save(sos);
        // Back to bidding: the previous mechanic's route is meaningless now.
        routeEtaService.clear(sosId);

        int rebroadcastCount = sos.getRebroadcastCount();
        int attemptsLeft = SosRequest.MAX_REBROADCAST - rebroadcastCount;
        double lat = sos.getLocation().getY();
        double lng = sos.getLocation().getX();
        String problemType = sos.getProblemType();
        Integer broadcastRadiusKm = sos.getBroadcastRadiusKm();
        TransactionHooks.afterCommit(() -> {
            messagingTemplate.convertAndSendToUser(
                    userId.toString(), "/queue/sos-updates",
                    Map.of("type", "SOS_REBROADCAST", "sosId", sosId,
                            "rebroadcastCount", rebroadcastCount,
                            "attemptsLeft", attemptsLeft)
            );
            sosDispatchService.fanOutToNearbyMechanics(sosId, lat, lng, problemType, broadcastRadiusKm);
            sosExpiryScheduler.schedule(sos);
        });

        return toResponseWithMechanic(sos);
    }
}
