package com.autofik.sos.service.impl;

import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.dto.event.SosCreatedEvent;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.SosStatusHistory;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.ApproveStatusChangeRequest;
import com.autofik.sos.dto.request.CreateSosRequest;
import com.autofik.sos.dto.request.ProposeStatusChangeRequest;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.dto.response.SosStatusHistoryResponse;
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
import com.autofik.sos.exception.TooManyRequestsException;
import com.autofik.sos.service.SosService;
import com.uber.h3core.H3Core;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.autofik.sos.dto.event.SosStatusUpdatedEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SosServiceImpl implements SosService {

    // State-machine guard: only transitions listed here are allowed.
    // COMPLETED and EXPIRED are terminal states — they have no outgoing transitions.
    private static final Map<SosStatus, Set<SosStatus>> VALID_TRANSITIONS = Map.of(
            SosStatus.PENDING,    Set.of(SosStatus.ACCEPTED, SosStatus.CANCELLED),
            SosStatus.ACCEPTED,   Set.of(SosStatus.ON_THE_WAY, SosStatus.CANCELLED),
            SosStatus.ON_THE_WAY, Set.of(SosStatus.ARRIVED, SosStatus.CANCELLED),
            SosStatus.ARRIVED,    Set.of(SosStatus.COMPLETED, SosStatus.CANCELLED)
    );

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
    // Resolution 7 ≈ 5 km² per H3 cell — fixed by what is stored in current_h3_index on Mechanic.
    // Changing this would require a schema + data migration; kRing radius is the adaptive variable instead.
    private static final int H3_RESOLUTION = 7;
    // Center-to-center distance between adjacent resolution-7 cells ≈ edge * sqrt(3) ≈ 2.114 km.
    // Used to convert a km radius into the minimum kRing radius that covers it.
    private static final double H3_R7_CENTER_TO_CENTER_KM = 2.114;
    // Default broadcast radius used for fan-out when the SOS request has no user-defined limit.
    private static final int DEFAULT_BROADCAST_KM = 15;

    private static final String SOS_COOLDOWN_KEY_PREFIX = "sos:cooldown:";
    private static final Duration SOS_COOLDOWN = Duration.ofSeconds(60);
    private static final List<SosStatus> ACTIVE_STATUSES = List.of(
            SosStatus.PENDING, SosStatus.ACCEPTED, SosStatus.ON_THE_WAY, SosStatus.ARRIVED
    );
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
    private final MechanicRepository mechanicRepository;
    private final BidSosRepository bidSosRepository;
    private final SosStatusHistoryRepository sosStatusHistoryRepository;
    private final SosMapper sosMapper;
    private final SosStatusHistoryMapper sosStatusHistoryMapper;
    private final MechanicMapper mechanicMapper;
    private final GoogleMapsService googleMapsService;
    private final SosEventProducer sosEventProducer;
    private final SimpMessagingTemplate messagingTemplate;
    private final TaskScheduler taskScheduler;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional
    public SosRequestResponse create(CreateSosRequest request) {
        if (sosRequestRepository.existsByUserUserIdAndStatusIn(request.userId(), ACTIVE_STATUSES)) {
            throw new IllegalStateException("You already have an active SOS request");
        }

        Boolean allowed = redisTemplate.opsForValue()
                .setIfAbsent(SOS_COOLDOWN_KEY_PREFIX + request.userId(), "1", SOS_COOLDOWN);
        if (!Boolean.TRUE.equals(allowed)) {
            throw new TooManyRequestsException("Please wait 60 seconds before submitting another SOS request");
        }

        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + request.userId()));
        Vehicle vehicle = vehicleRepository.findById(request.vehicleId())
                .orElseThrow(() -> new EntityNotFoundException("Vehicle not found: " + request.vehicleId()));
        SosRequest sos = SosRequest.builder()
                .problemType(request.problemType())
                .description(request.description())
                .location(GEOMETRY_FACTORY.createPoint(new Coordinate(request.longitude(), request.latitude())))
                .broadcastRadiusKm(request.broadcastRadiusKm())
                .user(user)
                .vehicle(vehicle)
                .build();
        sosRequestRepository.save(sos);

        String address = googleMapsService.reverseGeocode(request.latitude(), request.longitude());
        if (address != null) {
            sos.setLocationAddress(address);
            sosRequestRepository.save(sos);
        }

        sosEventProducer.publishSosCreated(new SosCreatedEvent(
            sos.getSosId(), user.getUserId(), vehicle.getId(),
            sos.getProblemType(), sos.getDescription(),
            request.latitude(), request.longitude(),
            sos.getLocationAddress(), sos.getCreatedAt()
        ));

        fanOutToNearbyMechanics(sos.getSosId(), request.latitude(), request.longitude(),
                request.problemType(), request.broadcastRadiusKm());

        scheduleBiddingExpiry(sos);

        return toResponseWithMechanic(sos);
    }

    @Override
    @Transactional(readOnly = true)
    public SosRequestResponse getById(UUID sosId) {
        return toResponseWithMechanic(findSosOrThrow(sosId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SosRequestResponse> getByUser(UUID userId, Pageable pageable) {
        return toPageWithMechanic(sosRequestRepository.findByUserUserId(userId, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SosRequestResponse> getByStatus(SosStatus status, Pageable pageable) {
        return toPageWithMechanic(sosRequestRepository.findByStatus(status, pageable));
    }

    // Direct status update (mechanic-initiated, no user approval needed).
    // Only ON_THE_WAY and ARRIVED flow through here; COMPLETED requires user approval via proposeStatusChange.
    @Override
    @Transactional
    public SosRequestResponse updateStatus(UUID sosId, UUID mechanicId, SosStatus toStatus) {
        if (toStatus != SosStatus.ON_THE_WAY && toStatus != SosStatus.ARRIVED) {
            throw new IllegalStateException(
                "Only ON_THE_WAY and ARRIVED can be set directly. Use POST /api/v1/sos/{sosId}/completion for COMPLETED.");
        }
        SosRequest sos = findSosOrThrow(sosId);
        validateTransition(sos.getStatus(), toStatus);
        Mechanic mechanic = mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));

        SosStatus fromStatus = sos.getStatus();
        sos.setStatus(toStatus);
        sosRequestRepository.save(sos);

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

        SosStatusUpdatedEvent statusEvent = new SosStatusUpdatedEvent(
                sos.getSosId(), mechanicId, sos.getUser().getUserId(),
                fromStatus, toStatus, now
        );
        sosEventProducer.publishStatusUpdated(statusEvent);
        messagingTemplate.convertAndSendToUser(
                sos.getUser().getUserId().toString(), "/queue/sos-updates", statusEvent
        );
        publishStatusNotifications(toStatus, sos, mechanicId);

        return toResponseWithMechanic(sos);
    }

    // COMPLETED requires explicit user approval to prevent fraud (mechanic marking done before finishing).
    // This records a PENDING_APPROVAL history entry and notifies the user; approveStatusChange() finalizes it.
    @Override
    @Transactional
    public SosStatusHistoryResponse proposeStatusChange(UUID sosId, ProposeStatusChangeRequest request) {
        if (request.toStatus() != SosStatus.COMPLETED) {
            throw new IllegalStateException(
                "Only COMPLETED requires customer approval. Use PATCH /api/v1/sos/{sosId}/status for ON_THE_WAY and ARRIVED.");
        }
        SosRequest sos = findSosOrThrow(sosId);
        validateTransition(sos.getStatus(), request.toStatus());
        Mechanic mechanic = mechanicRepository.findById(request.mechanicId())
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + request.mechanicId()));
        SosStatusHistory history = SosStatusHistory.builder()
                .sosRequest(sos)
                .fromStatus(sos.getStatus())
                .toStatus(request.toStatus())
                .proposedBy(mechanic)
                .proposedAt(LocalDateTime.now())
                .approvedStatus(ApprovedStatus.PENDING_APPROVAL)
                .build();
        sosStatusHistoryRepository.save(history);

        messagingTemplate.convertAndSendToUser(
                sos.getUser().getUserId().toString(), "/queue/sos-updates",
                Map.of("type", "COMPLETION_REQUESTED", "sosId", sos.getSosId(), "historyId", history.getId())
        );
        sosEventProducer.publishNotification(new SosNotificationEvent(
                "COMPLETION_REQUESTED", sos.getUser().getUserId(), "USER", sos.getSosId(),
                "Completion requested", "Your mechanic has marked the service complete. Please approve."
        ));
        return sosStatusHistoryMapper.toResponse(history);
    }

    @Override
    @Transactional
    public SosRequestResponse approveStatusChange(ApproveStatusChangeRequest request) {
        SosStatusHistory history = sosStatusHistoryRepository.findById(request.historyId())
                .orElseThrow(() -> new EntityNotFoundException("Status history not found: " + request.historyId()));
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + request.userId()));

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

            UUID mechanicId = history.getProposedBy().getMechanicId();
            SosStatusUpdatedEvent statusEvent = new SosStatusUpdatedEvent(
                    sos.getSosId(), mechanicId, user.getUserId(),
                    fromStatus, history.getToStatus(), LocalDateTime.now()
            );
            sosEventProducer.publishStatusUpdated(statusEvent);
            messagingTemplate.convertAndSendToUser(mechanicId.toString(), "/queue/sos-updates", statusEvent);

            publishStatusNotifications(history.getToStatus(), sos, mechanicId);
        } else if (request.approvedStatus() == ApprovedStatus.REJECTED) {
            UUID mechanicId = history.getProposedBy().getMechanicId();
            messagingTemplate.convertAndSendToUser(
                    mechanicId.toString(), "/queue/sos-updates",
                    Map.of("type", "COMPLETION_REJECTED", "sosId", sos.getSosId(), "historyId", history.getId())
            );
            sosEventProducer.publishNotification(new SosNotificationEvent(
                    "COMPLETION_REJECTED", mechanicId, "MECHANIC", sos.getSosId(),
                    "Completion rejected", "The customer has rejected the service completion request."
            ));
        }
        return toResponseWithMechanic(sos);
    }

    private void publishStatusNotifications(SosStatus toStatus, SosRequest sos, UUID mechanicId){
        UUID userId = sos.getUser().getUserId();
        switch (toStatus) {
            case ON_THE_WAY -> sosEventProducer.publishNotification(new SosNotificationEvent(
                    "STATUS_ON_THE_WAY", userId, "USER", sos.getSosId(),
                    "Mechanic is on the way", "Your mechanic is heading to your location"));
            case ARRIVED -> sosEventProducer.publishNotification(new SosNotificationEvent(
                    "STATUS_ARRIVED", userId, "USER", sos.getSosId(),
                    "Mechanic has arrived", "Your mechanic has arrived at your location"));
            case COMPLETED -> {
                sosEventProducer.publishNotification(new SosNotificationEvent(
                        "STATUS_COMPLETED", userId, "USER", sos.getSosId(),
                        "Service completed", "Your repair has been completed"));
                sosEventProducer.publishNotification(new SosNotificationEvent(
                        "STATUS_COMPLETED", mechanicId, "MECHANIC", sos.getSosId(),
                        "Service completed", "The service has been marked completed"));
            }
            default -> { } 
        }
    }

    @Override
    @Transactional
    public SosRequestResponse cancel(UUID sosId, UUID userId) {
        SosRequest sos = findSosOrThrow(sosId);
        SosStatus fromStatus = sos.getStatus();
        if (!VALID_TRANSITIONS.containsKey(fromStatus)) {
            throw new IllegalStateException("Cannot cancel a request with status: " + fromStatus);
        }
        sos.setStatus(SosStatus.CANCELLED);
        sosRequestRepository.save(sos);

        sosEventProducer.publishStatusUpdated(new SosStatusUpdatedEvent(
                sos.getSosId(), null, userId, fromStatus, SosStatus.CANCELLED, LocalDateTime.now()
        ));
        sosEventProducer.publishNotification(new SosNotificationEvent(
                "STATUS_CANCELLED", userId, "USER", sos.getSosId(),
                "SOS request cancelled", "Your SOS request has been cancelled"
        ));

        return toResponseWithMechanic(sos);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SosRequestResponse> getAll(Pageable pageable) {
        return toPageWithMechanic(sosRequestRepository.findAll(pageable));
    }

    // SosRequest has no direct mechanic relation — the assigned mechanic is resolved
    // from the ACCEPTED bid on this SOS request (at most one can exist).
    private SosRequestResponse toResponseWithMechanic(SosRequest sos) {
        SosRequestResponse base = sosMapper.toResponse(sos);
        MechanicResponse mechanic = bidSosRepository
                .findFirstBySosRequestSosIdAndStatus(sos.getSosId(), BidStatus.ACCEPTED)
                .map(bid -> mechanicMapper.toResponse(bid.getMechanic()))
                .orElse(null);
        return new SosRequestResponse(
                base.sosId(), base.problemType(), base.description(), base.locationAddress(),
                base.status(), base.latitude(), base.longitude(), base.broadcastRadiusKm(),
                base.createdAt(), base.updatedAt(),
                base.biddingExpiresAt(), base.user(), base.vehicle(), mechanic
        );
    }

    private List<SosRequestResponse> toResponseListWithMechanic(List<SosRequest> sosList) {
        return sosList.stream().map(this::toResponseWithMechanic).toList();
    }

    private Page<SosRequestResponse> toPageWithMechanic(Page<SosRequest> page) {
        return page.map(this::toResponseWithMechanic);
    }

    private SosRequest findSosOrThrow(UUID sosId) {
        return sosRequestRepository.findById(sosId)
                .orElseThrow(() -> new EntityNotFoundException("SOS request not found: " + sosId));
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
    private void fanOutToNearbyMechanics(UUID sosId, double lat, double lng,
                                         String problemType, Integer broadcastRadiusKm) {
        int effectiveRadiusKm = broadcastRadiusKm != null ? broadcastRadiusKm : DEFAULT_BROADCAST_KM;
        String sosCell = H3.geoToH3Address(lat, lng, H3_RESOLUTION);
        List<String> searchCells = H3.kRing(sosCell, ringRadiusFor(effectiveRadiusKm));

        List<Mechanic> coarse = mechanicRepository.findByH3CellsAndSkillTag(searchCells, problemType);
        if (coarse.isEmpty()) return;

        List<UUID> ids = coarse.stream().map(Mechanic::getMechanicId).toList();
        List<Mechanic> matched = broadcastRadiusKm != null
                ? mechanicRepository.filterByRadiusAndBroadcast(ids, lat, lng, broadcastRadiusKm * 1000.0)
                : mechanicRepository.filterByRadius(ids, lat, lng);

        SosNotificationEvent notification = new SosNotificationEvent(
                "SOS_CREATED", null, "MECHANIC", sosId,
                "New SOS request nearby", "Problem: " + problemType
        );
        matched.forEach(m ->
                messagingTemplate.convertAndSendToUser(
                        m.getMechanicId().toString(), "/queue/sos-new-request", notification
                )
        );
    }

    // Converts a km radius into the minimum kRing depth that fully covers it at resolution 7.
    // +1 ring of buffer accounts for partial hex overlap at the boundary.
    private static int ringRadiusFor(int radiusKm) {
        return (int) Math.ceil(radiusKm / H3_R7_CENTER_TO_CENTER_KM) + 1;
    }

    // Uses TaskScheduler (not @Scheduled) because each SOS has its own unique expiry time.
    // The lambda re-fetches the entity inside the transaction to avoid stale-state issues.
    private void scheduleBiddingExpiry(SosRequest sos) {
        if (sos.getBiddingExpiresAt() == null) return;
        UUID sosId = sos.getSosId();
        UUID userId = sos.getUser().getUserId();
        taskScheduler.schedule(() -> {
            sosRequestRepository.findById(sosId).ifPresent(s -> {
                if (s.getStatus() != SosStatus.PENDING) return;
                s.setStatus(SosStatus.EXPIRED);
                sosRequestRepository.save(s);
                sosEventProducer.publishStatusUpdated(new SosStatusUpdatedEvent(
                        sosId, null, userId, SosStatus.PENDING, SosStatus.EXPIRED, LocalDateTime.now()
                ));
                sosEventProducer.publishNotification(new SosNotificationEvent(
                        "BIDDING_EXPIRED", userId, "USER", sosId,
                        "Bidding window closed", "No mechanic was found. Please try again."
                ));
                messagingTemplate.convertAndSendToUser(
                        userId.toString(), "/queue/sos-updates",
                        Map.of("type", "BIDDING_EXPIRED", "sosId", sosId)
                );
            });
        }, sos.getBiddingExpiresAt().toInstant(ZoneOffset.UTC));
    }
}
