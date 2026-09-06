package com.autofik.sos.service;

import com.autofik.sos.dto.request.CreateMechanicRequest;
import com.autofik.sos.dto.request.CreateMechanicSkillRequest;
import com.autofik.sos.dto.response.MechanicLocationResponse;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.dto.response.MechanicSkillResponse;
import java.util.List;
import java.util.UUID;

/**
 * Service Interface defining the business use cases for human Mechanics. Manages accounts, active
 * availability states, and real-time GPS location tracking.
 */
public interface MechanicService {

  /**
   * Use case: Register a new mechanic account in the system.
   *
   * @param request Contains name, phone, email, and base GPS coordinates.
   * @return The created mechanic profile.
   */
  /**
   * @param mechanicId the caller's SSO mechanic id — this row is a projection of that identity and
   *     must carry the same UUID, so the id is never generated here
   */
  MechanicResponse create(CreateMechanicRequest request, UUID mechanicId);

  /**
   * Use case: Fetch a single mechanic's profile by their unique ID.
   *
   * @param mechanicId Identifies the target mechanic.
   * @return The matching mechanic profile.
   */
  MechanicResponse getById(UUID mechanicId);

  /**
   * Use case: Find available mechanics near a GPS location. Three-stage search: H3 adaptive kRing →
   * PostGIS ST_DWithin → optional skill filter.
   *
   * @param lat Latitude of the search center.
   * @param lng Longitude of the search center.
   * @param problemType Optional breakdown category filter (e.g. "flat_tire"). Pass null to skip.
   * @param radiusKm Search radius in km (5–30). Controls the H3 kRing depth.
   */
  List<MechanicResponse> findAvailable(double lat, double lng, String problemType, int radiusKm);

  /**
   * Use case: Declare what work a mechanic can take on. Dispatch inner-joins the skill table, so a
   * mechanic without at least one skill entry is never matched to an SOS.
   *
   * @param mechanicId Identifies the mechanic.
   * @param request Skill tags (matched against the SOS problem type) and vehicle specialization.
   * @return the stored skill entry.
   */
  MechanicSkillResponse addSkill(UUID mechanicId, CreateMechanicSkillRequest request);

  /**
   * Use case: List every skill entry a mechanic has declared.
   *
   * @param mechanicId Identifies the mechanic.
   * @return the mechanic's skill entries, empty when none are declared.
   */
  List<MechanicSkillResponse> getSkills(UUID mechanicId);

  /**
   * Use case: Replace the tags or specialization on one existing skill entry.
   *
   * @param mechanicId Ownership check — the skill must belong to this mechanic.
   * @param skillId Identifies the skill entry to replace.
   * @param request The new tags and specialization.
   * @return the updated skill entry.
   */
  MechanicSkillResponse updateSkill(
      UUID mechanicId, UUID skillId, CreateMechanicSkillRequest request);

  /**
   * Use case: Withdraw a skill entry, narrowing what the mechanic is dispatched for.
   *
   * @param mechanicId Ownership check — the skill must belong to this mechanic.
   * @param skillId Identifies the skill entry to remove.
   */
  void deleteSkill(UUID mechanicId, UUID skillId);

  /**
   * Use case: Update a mechanic's real-time GPS position (auto-resolves the active SOS from DB).
   * Used by the mobile app when the caller doesn't explicitly know the current sosId.
   *
   * @param mechanicId Identifies the mechanic sending the ping.
   * @param lat New latitude.
   * @param lng New longitude.
   */
  void updateLocation(UUID mechanicId, double lat, double lng);

  /**
   * Use case: Update a mechanic's GPS position for a known active SOS (skips the bid lookup). Used
   * when the sosId is already known, avoiding an extra DB query.
   *
   * @param mechanicId Identifies the mechanic sending the ping.
   * @param lat New latitude.
   * @param lng New longitude.
   * @param sosId The active SOS request this location update belongs to.
   */
  void updateLocation(UUID mechanicId, double lat, double lng, UUID sosId);

  /**
   * Use case: On-demand read of a mechanic's current position (e.g. initial screen load, before the
   * next WebSocket push arrives). Reads the live Redis cache first; falls back to the last durable
   * Postgres snapshot if Redis has nothing for this mechanic.
   *
   * @param mechanicId Identifies the target mechanic.
   * @param callerId Proven identity of the requester — that mechanic, or the driver they are
   *     currently on a job for. Anyone else gets 403.
   * @return The mechanic's most recently known position.
   */
  MechanicLocationResponse getCurrentLocation(UUID mechanicId, UUID callerId);

  /**
   * Use case: Toggle a mechanic's duty status (online/offline). Offline mechanics are excluded from
   * all SOS fan-out searches.
   *
   * @param mechanicId Identifies the target mechanic.
   * @param available true = online and accepting jobs, false = offline.
   * @return Updated mechanic profile.
   */
  MechanicResponse setAvailability(UUID mechanicId, boolean available);
}
