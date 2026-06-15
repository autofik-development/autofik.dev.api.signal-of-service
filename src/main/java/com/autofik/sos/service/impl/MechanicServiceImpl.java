package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.dto.request.CreateMechanicRequest;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.mapper.MechanicMapper;
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
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MechanicServiceImpl implements MechanicService {

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
    private final MechanicMapper mechanicMapper;

    @Override
    @Transactional
    public MechanicResponse create(CreateMechanicRequest request) {
        String h3Index = H3.geoToH3Address(request.latitude(), request.longitude(), H3_RESOLUTION);
        Mechanic mechanic = Mechanic.builder()
                .name(request.name())
                .phone(request.phone())
                .email(request.email())
                .baseLocation(GEOMETRY_FACTORY.createPoint(new Coordinate(request.longitude(), request.latitude())))
                .currentLocation(GEOMETRY_FACTORY.createPoint(new Coordinate(request.longitude(), request.latitude())))
                .currentH3Index(h3Index)
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

    @Override
    @Transactional
    public MechanicResponse updateLocation(UUID mechanicId, double lat, double lng) {
        Mechanic mechanic = mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
        mechanic.setCurrentLocation(GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat)));
        mechanic.setCurrentH3Index(H3.geoToH3Address(lat, lng, H3_RESOLUTION));
        return mechanicMapper.toResponse(mechanicRepository.save(mechanic));
    }
}
