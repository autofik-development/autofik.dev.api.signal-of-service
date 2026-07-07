package com.autofik.sos.service;

import com.autofik.sos.dto.request.CreateMechanicRequest;
import com.autofik.sos.dto.response.MechanicLocationResponse;
import com.autofik.sos.dto.response.MechanicResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Service Interface defining the business use cases for human Mechanics.
 * Manages accounts, active availability states, and real-time GPS location tracking.
 */
public interface MechanicService {

    /**
     * Use case: Register a new mechanic account in the system.
     * @param request Contains name, phone, email, and base GPS coordinates.
     * @return The created mechanic profile.
     */
    MechanicResponse create(CreateMechanicRequest request);

    /**
     * Use case: Fetch a single mechanic's profile by their unique ID.
     * @param mechanicId Identifies the target mechanic.
     * @return The matching mechanic profile.
     */
    MechanicResponse getById(UUID mechanicId);

    /**
     * Use case: Find available mechanics near a GPS location.
     * Three-stage search: H3 adaptive kRing → PostGIS ST_DWithin → optional skill filter.
     * @param lat        Latitude of the search center.
     * @param lng        Longitude of the search center.
     * @param problemType Optional breakdown category filter (e.g. "flat_tire"). Pass null to skip.
     * @param radiusKm   Search radius in km (5–30). Controls the H3 kRing depth.
     */
    List<MechanicResponse> findAvailable(double lat, double lng, String problemType, int radiusKm);

    /**
     * Use case: Update a mechanic's real-time GPS position (auto-resolves the active SOS from DB).
     * Used by the mobile app when the caller doesn't explicitly know the current sosId.
     * @param mechanicId Identifies the mechanic sending the ping.
     * @param lat New latitude.
     * @param lng New longitude.
     */
    void updateLocation(UUID mechanicId, double lat, double lng);

    /**
     * Use case: Update a mechanic's GPS position for a known active SOS (skips the bid lookup).
     * Used when the sosId is already known, avoiding an extra DB query.
     * @param mechanicId Identifies the mechanic sending the ping.
     * @param lat New latitude.
     * @param lng New longitude.
     * @param sosId The active SOS request this location update belongs to.
     */
    void updateLocation(UUID mechanicId, double lat, double lng, UUID sosId);

    /**
     * Use case: On-demand read of a mechanic's current position (e.g. initial screen load,
     * before the next WebSocket push arrives). Reads the live Redis cache first; falls back
     * to the last durable Postgres snapshot if Redis has nothing for this mechanic.
     * @param mechanicId Identifies the target mechanic.
     * @return The mechanic's most recently known position.
     */
    MechanicLocationResponse getCurrentLocation(UUID mechanicId);

    /**
     * Use case: Toggle a mechanic's duty status (online/offline).
     * Offline mechanics are excluded from all SOS fan-out searches.
     * @param mechanicId Identifies the target mechanic.
     * @param available true = online and accepting jobs, false = offline.
     * @return Updated mechanic profile.
     */
    MechanicResponse setAvailability(UUID mechanicId, boolean available);

    /**
     * Use case: Admin dashboard — retrieve all mechanic accounts (paginated).
     * @param pageable page number, size, and sort criteria.
     * @return Page of mechanic records.
     */
    Page<MechanicResponse> getAll(Pageable pageable);
}
