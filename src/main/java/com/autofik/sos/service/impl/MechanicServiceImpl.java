package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicSkill;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.dto.event.MechanicLocationEvent;
import com.autofik.sos.dto.external.EtaInfo;
import com.autofik.sos.dto.request.CreateMechanicRequest;
import com.autofik.sos.dto.request.CreateMechanicSkillRequest;
import com.autofik.sos.dto.response.MechanicLocationResponse;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.dto.response.MechanicSkillResponse;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.MechanicMapper;
import com.autofik.sos.mapper.MechanicSkillMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.MechanicSkillRepository;
import com.autofik.sos.service.MechanicService;
import com.autofik.sos.service.RouteEtaService;
import com.autofik.sos.util.GeoSupport;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Point;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MechanicServiceImpl implements MechanicService {

    // The service is Cambodia-only — see the coordinate bounds on the controller.
    private static final ZoneId CAMBODIA = ZoneId.of("Asia/Phnom_Penh");

    // Redis is used here only for live location pub/sub — broadcasting mechanic position to the
    // customer app during an active job. The GPS write throttle lives in MechanicLocationWriter
    // alongside the write it guards. Availability search and location reads use Postgres
    // current_location as the source of truth.

    private final MechanicRepository mechanicRepository;
    private final MechanicSkillRepository mechanicSkillRepository;
    private final BidSosRepository bidSosRepository;
    private final SsoProjection ssoProjection;
    private final MechanicLocationWriter locationWriter;
    private final MechanicMapper mechanicMapper;
    private final MechanicSkillMapper mechanicSkillMapper;
    private final SosEventProducer sosEventProducer;
    private final RouteEtaService routeEtaService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public MechanicResponse create(CreateMechanicRequest request, UUID mechanicId) {
        Mechanic mechanic = Mechanic.builder()
                .mechanicId(mechanicId)
                .fullName(request.fullName())
                .phoneNumber(request.phoneNumber())
                .email(request.email())
                .mechanicRole(request.mechanicRole())
                .address(request.address())
                .serviceRadiusKm(request.serviceRadiusKm() != null ? request.serviceRadiusKm() : 10)
                .baseLocation(GeoSupport.point(request.latitude(), request.longitude()))
                .build();
        Mechanic saved = mechanicRepository.save(mechanic);

        if (request.skills() != null && !request.skills().isEmpty()) {
            mechanicSkillRepository.saveAll(request.skills().stream()
                    .map(skill -> MechanicSkill.builder()
                            .mechanic(saved)
                            .skillTags(normalizeTags(skill.skillTags()))
                            .vehicleSpecialization(skill.vehicleSpecialization())
                            .build())
                    .toList());
        } else {
            // Dispatch inner-joins mechanic_skill, so this account cannot receive any SOS until a
            // skill entry is added. Logged because the mechanic looks healthy from every other angle.
            log.warn("Mechanic {} created with no skills — it will not be dispatched until skills are added",
                    saved.getMechanicId());
        }

        return mechanicMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public MechanicSkillResponse addSkill(UUID mechanicId, CreateMechanicSkillRequest request) {
        Mechanic mechanic = ssoProjection.mechanic(mechanicId);
        MechanicSkill skill = MechanicSkill.builder()
                .mechanic(mechanic)
                .skillTags(normalizeTags(request.skillTags()))
                .vehicleSpecialization(request.vehicleSpecialization())
                .build();
        return mechanicSkillMapper.toResponse(mechanicSkillRepository.save(skill));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MechanicSkillResponse> getSkills(UUID mechanicId) {
        // Distinguishes "unknown mechanic" (404) from "known mechanic, no skills yet" (empty list).
        if (!mechanicRepository.existsById(mechanicId)) {
            throw new EntityNotFoundException("Mechanic not found: " + mechanicId);
        }
        return mechanicSkillMapper.toResponseList(mechanicSkillRepository.findByMechanicMechanicId(mechanicId));
    }

    @Override
    @Transactional
    public MechanicSkillResponse updateSkill(UUID mechanicId, UUID skillId, CreateMechanicSkillRequest request) {
        MechanicSkill skill = findOwnedSkill(mechanicId, skillId);
        skill.setSkillTags(normalizeTags(request.skillTags()));
        skill.setVehicleSpecialization(request.vehicleSpecialization());
        return mechanicSkillMapper.toResponse(mechanicSkillRepository.save(skill));
    }

    @Override
    @Transactional
    public void deleteSkill(UUID mechanicId, UUID skillId) {
        mechanicSkillRepository.delete(findOwnedSkill(mechanicId, skillId));
    }

    // Scoping the lookup by mechanic means a guessed skill id cannot touch another mechanic's row.
    private MechanicSkill findOwnedSkill(UUID mechanicId, UUID skillId) {
        return mechanicSkillRepository.findByIdAndMechanicMechanicId(skillId, mechanicId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Skill " + skillId + " not found for mechanic " + mechanicId));
    }

    /**
     * Tags are dispatch keys, not display text, so they are trimmed, upper-cased and de-duplicated
     * on write to match the SOS problem type (itself upper-cased from the Medusa service group key).
     * Locale.ROOT keeps the casing stable regardless of the server's default locale.
     */
    private static String[] normalizeTags(List<String> tags) {
        return tags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .map(tag -> tag.toUpperCase(Locale.ROOT))
                .distinct()
                .toArray(String[]::new);
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

        List<String> searchCells = GeoSupport.searchCells(lat, lng, radiusKm);

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
                                    Arrays.stream(s.getSkillTags())
                                            .anyMatch(tag -> tag.equalsIgnoreCase(problemType))))
                    .toList();
            if (result.isEmpty()) {
                log.debug("problemType '{}' matched no mechanic skill tags; {} nearby candidate(s) excluded",
                        problemType, withSkills.size());
            }
        }

        return mechanicMapper.toResponseList(result);
    }

    // Convenience overload — delegates so both entry points share one broadcast decision.
    @Override
    public void updateLocation(UUID mechanicId, double lat, double lng) {
        updateLocation(mechanicId, lat, lng, null);
    }

    // sosId is optional: supplying it skips the active-bid lookup entirely.
    //
    // Deliberately not @Transactional. The persistence work is delegated to MechanicLocationWriter,
    // which carries its own boundary, so publishLocation below — a blocking Google call plus a Redis
    // publish and a Kafka fallback — runs with no Postgres connection checked out. Wrapping the whole
    // method would hold one for the duration of an external HTTP call on the hottest endpoint here,
    // draining the pool exactly when Maps is slow.
    @Override
    public void updateLocation(UUID mechanicId, double lat, double lng, UUID sosId) {
        UUID activeSos = locationWriter.persistAndResolveSos(mechanicId, lat, lng, sosId);
        if (activeSos != null) {
            publishLocation(activeSos, mechanicId, lat, lng);
        }
    }

    // Redis Pub/Sub primary — subscriber relays to WebSocket immediately.
    // Kafka fallback activates automatically on any Redis exception.
    private void publishLocation(UUID sosId, UUID mechanicId, double lat, double lng) {
        // Route progress rides along with the position so the customer's map and countdown
        // update in the same frame. Null when the SOS is no longer en route.
        EtaInfo eta = routeEtaService.currentEta(sosId, lat, lng);

        // Cambodian local time, truncated to milliseconds — nanosecond precision is noise no
        // client reads. The zone is fixed rather than taken from the host clock so the payload
        // is identical whether the service runs locally or in a UTC container.
        MechanicLocationEvent event = new MechanicLocationEvent(
                sosId, mechanicId, lat, lng,
                OffsetDateTime.now(CAMBODIA).truncatedTo(ChronoUnit.MILLIS),
                eta == null ? null : eta.remainingDistanceM(),
                eta == null ? null : eta.remainingDurationS(),
                eta == null ? null : eta.remainingDistanceText(),
                eta == null ? null : eta.remainingDurationText());
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

    // Reads directly from Postgres current_location — authoritative, always up to date within
    // the 10-second write throttle window. Continuous updates during a job still arrive via WebSocket.
    @Override
    @Transactional(readOnly = true)
    public MechanicLocationResponse getCurrentLocation(UUID mechanicId, UUID callerId) {
        // A mechanic's live position is visible to themselves and to the driver they are currently
        // working for. Open, it is a tracker for anyone who knows a mechanic's id.
        if (!mechanicId.equals(callerId) && !hasActiveJobWith(mechanicId, callerId)) {
            throw new AccessDeniedException("You are not on an active job with this mechanic");
        }
        Mechanic mechanic = mechanicRepository.findById(mechanicId)
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
        Point location = mechanic.getCurrentLocation();
        if (location == null) {
            throw new EntityNotFoundException("No location available for mechanic: " + mechanicId);
        }
        return new MechanicLocationResponse(location.getY(), location.getX(), Instant.now());
    }

    // "Active job" is the ACCEPTED bid: it is what assigns a mechanic to a driver, and it ends
    // when the SOS leaves an assigned state.
    private boolean hasActiveJobWith(UUID mechanicId, UUID userId) {
        return bidSosRepository.findFirstByMechanicMechanicIdAndStatus(mechanicId, BidStatus.ACCEPTED)
                .map(BidSos::getSosRequest)
                .filter(sos -> sos.getStatus().isAssigned())
                .map(sos -> sos.getUser().getUserId().equals(userId))
                .orElse(false);
    }

    @Override
    @Transactional
    public MechanicResponse setAvailability(UUID mechanicId, boolean available) {
        // Going online is the first thing a mechanic does, so it is where the projection is
        // usually created. Cold path — one lookup here costs nothing.
        Mechanic mechanic = ssoProjection.mechanic(mechanicId);
        mechanic.setAvailableStatus(available);
        return mechanicMapper.toResponse(mechanicRepository.save(mechanic));
    }

}
