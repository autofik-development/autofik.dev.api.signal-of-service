package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.dto.event.MechanicLocationEvent;
import com.autofik.sos.dto.request.CreateMechanicRequest;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.MechanicMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.service.MechanicService;
import com.uber.h3core.H3Core;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MechanicServiceImpl implements MechanicService {

    // Resolution 7 ≈ 5 km² per cell; kRing(4) expands the search to ~15 km radius.
    // Must match the constants in SosServiceImpl so both fan-out and availability queries use the same grid.
    private static final int H3_RESOLUTION = 7;
    private static final int H3_RING_RADIUS = 4;
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
    private static final H3Core H3;

    static {
        try {
            H3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final MechanicRepository mechanicRepository;
    private final BidSosRepository bidSosRepository;
    private final MechanicMapper mechanicMapper;
    private final SosEventProducer sosEventProducer;

    @Override
    @Transactional
    public MechanicResponse create(CreateMechanicRequest request) {
        Mechanic mechanic = Mechanic.builder()
                .name(request.name())
                .phone(request.phone())
                .email(request.email())
                .baseLocation(GEOMETRY_FACTORY.createPoint(new Coordinate(request.longitude(), request.latitude())))
                .build();
        return mechanicMapper.toResponse(mechanicRepository.save(mechanic));
    }

    @Override
    @Transactional(readOnly = true)
    public MechanicResponse getById(UUID mechanicId) {
        return mechanicMapper.toResponse(mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MechanicResponse> findAvailable(double lat, double lng) {
        return findAvailable(lat, lng, null);
    }

    // Two-stage search to avoid expensive full-table PostGIS scans on every request.
    // Stage 1: B-tree index scan on current_h3_index — filters by hex cells, O(cells).
    // Stage 2: PostGIS ST_DWithin on the Stage 1 shortlist only — exact distance check.
    @Override
    @Transactional(readOnly = true)
    public List<MechanicResponse> findAvailable(double lat, double lng, String problemType) {
        String centerCell = H3.geoToH3Address(lat, lng, H3_RESOLUTION);
        List<String> searchCells = H3.kRing(centerCell, H3_RING_RADIUS);

        List<Mechanic> stage1 = mechanicRepository.findByH3Cells(searchCells);
        if (stage1.isEmpty()) {
            return List.of();
        }

        List<UUID> candidateIds = stage1.stream().map(Mechanic::getMechanicId).toList();
        List<Mechanic> stage2 = mechanicRepository.filterByRadius(candidateIds, lat, lng);

        // Stage 3: in-memory specialization filter — only applied when problemType is provided
        List<Mechanic> result = (problemType == null || problemType.isBlank())
                ? stage2
                : stage2.stream()
                        .filter(m -> m.getSpecializations().stream()
                                .anyMatch(s -> s.getSpecialization().getName()
                                        .equalsIgnoreCase(problemType)))
                        .toList();

        return mechanicMapper.toResponseList(result);
    }

    // Called by the REST controller when no sosId is provided — resolves the active job automatically.
    // No active ACCEPTED bid → mechanic is not on a job, location is saved but no event is published.
    @Override
    @Transactional
    public MechanicResponse updateLocation(UUID mechanicId, double lat, double lng) {
        Mechanic mechanic = mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
        mechanic.setCurrentLocation(GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat)));
        mechanic.setCurrentH3Index(H3.geoToH3Address(lat, lng, H3_RESOLUTION));
        MechanicResponse response = mechanicMapper.toResponse(mechanicRepository.save(mechanic));

        bidSosRepository.findFirstByMechanicMechanicIdAndStatus(mechanicId, BidStatus.ACCEPTED)
                .ifPresent(bid -> sosEventProducer.publishMechanicLocation(
                        new MechanicLocationEvent(
                                bid.getSosRequest().getSosId(), mechanicId, lat, lng, LocalDateTime.now()
                        )
                ));
        return response;
    }

    // Overload used by the Kafka consumer or when the caller already knows the sosId,
    // skipping the extra DB lookup to find the active bid.
    @Override
    @Transactional
    public MechanicResponse updateLocation(UUID mechanicId, double lat, double lng, UUID sosId) {
        Mechanic mechanic = mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
        mechanic.setCurrentLocation(GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat)));
        mechanic.setCurrentH3Index(H3.geoToH3Address(lat, lng, H3_RESOLUTION));
        MechanicResponse response = mechanicMapper.toResponse(mechanicRepository.save(mechanic));

        if (sosId != null) {
            sosEventProducer.publishMechanicLocation(
                    new MechanicLocationEvent(sosId, mechanicId, lat, lng, LocalDateTime.now())
            );
        }
        return response;
    }

    @Override
    @Transactional
    public MechanicResponse setAvailability(UUID mechanicId, boolean available) {
        Mechanic mechanic = mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
        mechanic.setIsAvailable(available);
        return mechanicMapper.toResponse(mechanicRepository.save(mechanic));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MechanicResponse> getAll() {
        return mechanicMapper.toResponseList(mechanicRepository.findAll());
    }
}
