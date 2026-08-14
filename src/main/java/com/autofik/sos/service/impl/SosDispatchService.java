package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.util.GeoSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Finds the mechanics who should hear about a new SOS and tells them.
 *
 * <p>Two-stage geo-search:
 * <ol>
 *   <li>H3 index — a B-tree scan over the cells around the breakdown, which eliminates most of the
 *       table before PostGIS is asked anything</li>
 *   <li>PostGIS {@code ST_DWithin} — the exact check, respecting each mechanic's own service radius
 *       as well as the driver's broadcast cap</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SosDispatchService {

    // Broadcast radius used when the driver did not set one.
    private static final int DEFAULT_BROADCAST_KM = 15;

    private final MechanicRepository mechanicRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * @param broadcastRadiusKm the driver's optional cap; null means the mechanic's own service
     *                          radius is the only limit
     */
    public void fanOutToNearbyMechanics(UUID sosId, double lat, double lng,
                                        String problemType, Integer broadcastRadiusKm) {
        int effectiveRadiusKm = broadcastRadiusKm != null ? broadcastRadiusKm : DEFAULT_BROADCAST_KM;
        List<String> searchCells = GeoSupport.searchCells(lat, lng, effectiveRadiusKm);

        List<Mechanic> coarse = mechanicRepository.findByH3CellsAndSkillTag(searchCells, problemType);
        if (coarse.isEmpty()) {
            log.debug("No mechanic with skill '{}' in the {} cell(s) around SOS {}",
                    problemType, searchCells.size(), sosId);
            return;
        }

        List<UUID> ids = coarse.stream().map(Mechanic::getMechanicId).toList();
        List<Mechanic> matched = broadcastRadiusKm != null
                ? mechanicRepository.filterByRadiusAndBroadcast(ids, lat, lng, broadcastRadiusKm * 1000.0)
                : mechanicRepository.filterByRadius(ids, lat, lng);

        // recipientId is null: this is a fan-out, so there is no single device to push to — the
        // notification service resolves the audience from the SOS itself.
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
}
