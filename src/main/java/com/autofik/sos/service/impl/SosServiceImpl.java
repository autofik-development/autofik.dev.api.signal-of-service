package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.SosStatusHistory;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.ApproveStatusChangeRequest;
import com.autofik.sos.dto.request.CreateSosRequest;
import com.autofik.sos.dto.request.ProposeStatusChangeRequest;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.dto.response.SosStatusHistoryResponse;
import com.autofik.sos.mapper.SosMapper;
import com.autofik.sos.mapper.SosStatusHistoryMapper;
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
    private final SosStatusHistoryRepository sosStatusHistoryRepository;
    private final SosMapper sosMapper;
    private final SosStatusHistoryMapper sosStatusHistoryMapper;
    private final GoogleMapsService googleMapsService;

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

        return sosMapper.toResponse(sos);
    }

    @Override
    @Transactional(readOnly = true)
    public SosRequestResponse getById(UUID sosId) {
        return sosMapper.toResponse(findSosOrThrow(sosId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SosRequestResponse> getByUser(UUID userId) {
        return sosMapper.toResponseList(sosRequestRepository.findByUserId(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SosRequestResponse> getByStatus(SosStatus status) {
        return sosMapper.toResponseList(sosRequestRepository.findByStatus(status));
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
            validateTransition(sos.getStatus(), history.getToStatus());
            sos.setStatus(history.getToStatus());
            sosRequestRepository.save(sos);
        }
        return sosMapper.toResponse(sos);
    }

    @Override
    @Transactional
    public SosRequestResponse cancel(UUID sosId, UUID userId) {
        SosRequest sos = findSosOrThrow(sosId);
        if (!VALID_TRANSITIONS.containsKey(sos.getStatus())) {
            throw new IllegalStateException("Cannot cancel a request with status: " + sos.getStatus());
        }
        sos.setStatus(SosStatus.CANCELLED);
        return sosMapper.toResponse(sosRequestRepository.save(sos));
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
