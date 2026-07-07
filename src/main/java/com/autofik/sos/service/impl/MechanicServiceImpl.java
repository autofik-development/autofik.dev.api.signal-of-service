package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.dto.event.MechanicLocationEvent;
import com.autofik.sos.dto.request.CreateMechanicRequest;
import com.autofik.sos.dto.response.MechanicLocationResponse;
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
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MechanicServiceImpl implements MechanicService {

    private static final int H3_RESOLUTION = 7;
    // Center-to-center distance between adjacent resolution-7 cells ≈ edge × √3 ≈ 2.114 km.
    // Matches the constant in SosServiceImpl so both fan-out and availability use the same formula.
    private static final double H3_R7_CENTER_TO_CENTER_KM = 2.114;
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
    private static final H3Core H3;

    static {
        try {
            H3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Redis is used only for two things:
    //   1. GPS write throttle: limits Postgres writes to once per PG_SYNC_INTERVAL per mechanic.
    //   2. Live location pub/sub: broadcasts mechanic position to the customer app during an active job.
    // Availability search and location reads use Postgres current_location as the source of truth.
    private static final String PG_SYNC_KEY_PREFIX = "mechanic:pg-sync:";
    private static final Duration PG_SYNC_INTERVAL = Duration.ofSeconds(10);

    private final MechanicRepository mechanicRepository;
    private final BidSosRepository bidSosRepository;
    private final MechanicMapper mechanicMapper;
    private final SosEventProducer sosEventProducer;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public MechanicResponse create(CreateMechanicRequest request) {
        Mechanic mechanic = Mechanic.builder()
                .fullName(request.fullName())
                .phoneNumber(request.phoneNumber())
                .email(request.email())
                .mechanicRole(request.mechanicRole())
                .address(request.address())
                .serviceRadiusKm(request.serviceRadiusKm() != null ? request.serviceRadiusKm() : 10)
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

    // Three-stage geo-search using Postgres current_location as the source of truth.
    // Stage 1: H3 adaptive kRing B-tree scan — fast coarse filter, eliminates most of the table.
    // Stage 2: PostGIS ST_DWithin — exact filter respecting each mechanic's service_radius_km.
    // Stage 3 (in-memory): skill tag filter, only when problemType is provided.
    @Override
    @Transactional(readOnly = true)
    public List<MechanicResponse> findAvailable(double lat, double lng, String problemType, int radiusKm) {
        boolean needsSkills = problemType != null && !problemType.isBlank();

        String centerCell = H3.geoToH3Address(lat, lng, H3_RESOLUTION);
        List<String> searchCells = H3.kRing(centerCell, ringRadiusFor(radiusKm));

        List<Mechanic> stage1 = mechanicRepository.findByH3Cells(searchCells);
        if (stage1.isEmpty()) {
            return List.of();
        }

        List<UUID> candidateIds = stage1.stream().map(Mechanic::getMechanicId).toList();
        List<Mechanic> stage2 = mechanicRepository.filterByRadius(candidateIds, lat, lng);
        if (stage2.isEmpty()) {
            return List.of();
        }

        List<Mechanic> result;
        if (!needsSkills) {
            result = stage2;
        } else {
            // JOIN FETCH skills for the exact stage-2 set to avoid N+1 lazy-load in the filter below
            List<UUID> finalIds = stage2.stream().map(Mechanic::getMechanicId).toList();
            List<Mechanic> withSkills = mechanicRepository.findByIdsWithSkills(finalIds);
            result = withSkills.stream()
                    .filter(m -> m.getSkills() != null && m.getSkills().stream()
                            .anyMatch(s -> s.getSkillTags() != null &&
                                    java.util.Arrays.stream(s.getSkillTags())
                                            .anyMatch(tag -> tag.equalsIgnoreCase(problemType))))
                    .toList();
            if (result.isEmpty()) {
                log.debug("problemType '{}' matched no mechanic skill tags; {} nearby candidate(s) excluded",
                        problemType, withSkills.size());
            }
        }

        return mechanicMapper.toResponseList(result);
    }

    private static int ringRadiusFor(int radiusKm) {
        return (int) Math.ceil(radiusKm / H3_R7_CENTER_TO_CENTER_KM) + 1;
    }

    // Called by the REST controller when no sosId is provided — resolves the active job automatically.
    @Override
    @Transactional
    public void updateLocation(UUID mechanicId, double lat, double lng) {
        writeLocation(mechanicId, lat, lng);
        bidSosRepository.findFirstByMechanicMechanicIdAndStatus(mechanicId, BidStatus.ACCEPTED)
                .ifPresent(bid -> publishLocation(bid.getSosRequest().getSosId(), mechanicId, lat, lng));
    }

    // Overload used when the caller already knows the sosId — skips the active-bid DB lookup.
    @Override
    @Transactional
    public void updateLocation(UUID mechanicId, double lat, double lng, UUID sosId) {
        writeLocation(mechanicId, lat, lng);
        if (sosId != null) {
            publishLocation(sosId, mechanicId, lat, lng);
        }
    }

    // Redis Pub/Sub primary — subscriber relays to WebSocket immediately.
    // Kafka fallback activates automatically on any Redis exception.
    private void publishLocation(UUID sosId, UUID mechanicId, double lat, double lng) {
        MechanicLocationEvent event = new MechanicLocationEvent(sosId, mechanicId, lat, lng, Instant.now());
        try {
            redisTemplate.convertAndSend(
                    "mechanic:loc:" + sosId,
                    objectMapper.writeValueAsString(event)
            );
        } catch (Exception e) {
            log.warn("Redis pub/sub failed for SOS {}, falling back to Kafka: {}", sosId, e.getMessage());
            sosEventProducer.publishMechanicLocation(event);
        }
    }

    // Postgres is the durable store. Redis throttle key limits writes to once per PG_SYNC_INTERVAL.
    // When Redis is down the throttle key is unavailable, so every ping falls through to Postgres —
    // keeping the snapshot current during an outage.
    private void writeLocation(UUID mechanicId, double lat, double lng) {
        boolean shouldPersist;
        try {
            Boolean throttle = redisTemplate.opsForValue()
                    .setIfAbsent(PG_SYNC_KEY_PREFIX + mechanicId, "1", PG_SYNC_INTERVAL);
            shouldPersist = Boolean.TRUE.equals(throttle);
        } catch (Exception e) {
            log.warn("Redis unavailable for mechanic {}, writing to Postgres on every ping: {}", mechanicId, e.getMessage());
            shouldPersist = true;
        }

        if (shouldPersist) {
            Mechanic mechanic = mechanicRepository.findById(mechanicId)
                    .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
            mechanic.setCurrentLocation(GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat)));
            mechanic.setCurrentH3Index(H3.geoToH3Address(lat, lng, H3_RESOLUTION));
            mechanicRepository.save(mechanic);
        } else if (!mechanicRepository.existsById(mechanicId)) {
            throw new EntityNotFoundException("Mechanic not found: " + mechanicId);
        }
    }

    // Reads directly from Postgres current_location — authoritative, always up to date within
    // the 10-second write throttle window. Continuous updates during a job still arrive via WebSocket.
    @Override
    @Transactional(readOnly = true)
    public MechanicLocationResponse getCurrentLocation(UUID mechanicId) {
        Mechanic mechanic = mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
        org.locationtech.jts.geom.Point location = mechanic.getCurrentLocation();
        if (location == null) {
            throw new EntityNotFoundException("No location available for mechanic: " + mechanicId);
        }
        return new MechanicLocationResponse(location.getY(), location.getX(), Instant.now());
    }

    @Override
    @Transactional
    public MechanicResponse setAvailability(UUID mechanicId, boolean available) {
        Mechanic mechanic = mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
        mechanic.setAvailableStatus(available);
        return mechanicMapper.toResponse(mechanicRepository.save(mechanic));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MechanicResponse> getAll(Pageable pageable) {
        return mechanicRepository.findAll(pageable).map(mechanicMapper::toResponse);
    }
}
