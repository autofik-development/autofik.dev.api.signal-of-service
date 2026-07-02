package com.autofik.sos.service;

import com.autofik.sos.dto.request.CreateMechanicRequest;
import com.autofik.sos.dto.response.MechanicResponse;

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
     * Use case: Find all available mechanics near a GPS location (no specialization filter).
     * Uses a two-stage H3 + PostGIS search — see MechanicServiceImpl for details.
     * @param lat Latitude of the search center point.
     * @param lng Longitude of the search center point.
     * @return List of nearby available mechanics.
     */
    List<MechanicResponse> findAvailable(double lat, double lng);

    /**
     * Use case: Find available mechanics near a location who can handle a specific problem type.
     * @param lat Latitude of the search center point.
     * @param lng Longitude of the search center point.
     * @param problemType The breakdown category to filter by (e.g., "flat_tire", "engine").
     * @return List of nearby available mechanics with matching specialization.
     */
    List<MechanicResponse> findAvailable(double lat, double lng, String problemType);

    /**
     * Use case: Update a mechanic's real-time GPS position (auto-resolves the active SOS from DB).
     * Used by the mobile app when the caller doesn't explicitly know the current sosId.
     * @param mechanicId Identifies the mechanic sending the ping.
     * @param lat New latitude.
     * @param lng New longitude.
     * @return Updated mechanic profile.
     */
    MechanicResponse updateLocation(UUID mechanicId, double lat, double lng);

    /**
     * Use case: Update a mechanic's GPS position for a known active SOS (skips the bid lookup).
     * Used when the sosId is already known, avoiding an extra DB query.
     * @param mechanicId Identifies the mechanic sending the ping.
     * @param lat New latitude.
     * @param lng New longitude.
     * @param sosId The active SOS request this location update belongs to.
     * @return Updated mechanic profile.
     */
    MechanicResponse updateLocation(UUID mechanicId, double lat, double lng, UUID sosId);

    /**
     * Use case: Toggle a mechanic's duty status (online/offline).
     * Offline mechanics are excluded from all SOS fan-out searches.
     * @param mechanicId Identifies the target mechanic.
     * @param available true = online and accepting jobs, false = offline.
     * @return Updated mechanic profile.
     */
    MechanicResponse setAvailability(UUID mechanicId, boolean available);

    /**
     * Use case: Admin dashboard — retrieve all mechanic accounts.
     * @return Full list of all mechanic records.
     */
    List<MechanicResponse> getAll();
}
