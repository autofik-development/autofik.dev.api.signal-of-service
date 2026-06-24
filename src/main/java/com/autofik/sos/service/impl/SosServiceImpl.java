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
import com.autofik.sos.service.SosService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.autofik.sos.dto.event.SosStatusUpdatedEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SosServiceImpl implements SosService {

    private static final Map<SosStatus, Set<SosStatus>> VALID_TRANSITIONS = Map.of(
            SosStatus.PENDING,    Set.of(SosStatus.ACCEPTED, SosStatus.CANCELLED),
            SosStatus.ACCEPTED,   Set.of(SosStatus.ON_THE_WAY, SosStatus.CANCELLED),
            SosStatus.ON_THE_WAY, Set.of(SosStatus.ARRIVED, SosStatus.CANCELLED),
            SosStatus.ARRIVED,    Set.of(SosStatus.COMPLETED, SosStatus.CANCELLED)
    );

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

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

    @Override
    @Transactional
    public SosRequestResponse create(CreateSosRequest request) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + request.userId()));
        Vehicle vehicle = vehicleRepository.findById(request.vehicleId())
                .orElseThrow(() -> new EntityNotFoundException("Vehicle not found: " + request.vehicleId()));
        SosRequest sos = SosRequest.builder()
                .problemType(request.problemType())
                .description(request.description())
                .location(GEOMETRY_FACTORY.createPoint(new Coordinate(request.longitude(), request.latitude())))
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
            sos.getSosId(), user.getUserId(), vehicle.getVehicleId(),
            sos.getProblemType(), sos.getDescription(),
            request.latitude(), request.longitude(),
            sos.getLocationAddress(), sos.getCreatedAt()
        ));
        sosEventProducer.publishNotification(new SosNotificationEvent(
                "SOS_CREATED", null, "MECHANIC", sos.getSosId(),
                "New SOS request nearby",
                "Problem: " + sos.getProblemType()
        ));

        return toResponseWithMechanic(sos);
    }

    @Override
    @Transactional(readOnly = true)
    public SosRequestResponse getById(UUID sosId) {
        return toResponseWithMechanic(findSosOrThrow(sosId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SosRequestResponse> getByUser(UUID userId) {
        return toResponseListWithMechanic(sosRequestRepository.findByUserUserId(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SosRequestResponse> getByStatus(SosStatus status) {
        return toResponseListWithMechanic(sosRequestRepository.findByStatus(status));
    }

    @Override
    @Transactional
    public SosStatusHistoryResponse proposeStatusChange(ProposeStatusChangeRequest request) {
        SosRequest sos = findSosOrThrow(request.sosId());
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
        return sosStatusHistoryMapper.toResponse(sosStatusHistoryRepository.save(history));
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
    public List<SosRequestResponse> getAll() {
        return toResponseListWithMechanic(sosRequestRepository.findAll());
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
                base.status(), base.latitude(), base.longitude(), base.createdAt(), base.updatedAt(),
                base.user(), base.vehicle(), mechanic
        );
    }

    private List<SosRequestResponse> toResponseListWithMechanic(List<SosRequest> sosList) {
        return sosList.stream().map(this::toResponseWithMechanic).toList();
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
}
