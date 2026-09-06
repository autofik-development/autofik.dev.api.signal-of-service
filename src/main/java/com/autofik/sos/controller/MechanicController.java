package com.autofik.sos.controller;

import com.autofik.sos.dto.request.CreateMechanicRequest;
import com.autofik.sos.dto.request.CreateMechanicSkillRequest;
import com.autofik.sos.dto.response.MechanicLocationResponse;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.dto.response.MechanicSkillResponse;
import com.autofik.sos.security.Caller;
import com.autofik.sos.service.MechanicService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for mechanic availability, skills, and real-time GPS tracking. Base URL:
 * /api/mechanics
 *
 * <p>There is no create endpoint: mechanics are born in SSO and the row here is a projection of
 * that identity, carrying the same UUID. It is created on the mechanic's first authenticated call.
 */
@RestController
@RequestMapping("/api/v1/mechanics")
@RequiredArgsConstructor
@Validated
public class MechanicController {

  private final MechanicService mechanicService;

  // POST /api/mechanics — Mechanic registers themselves with this service
  // The row's id is the caller's SSO mechanic id, never a fresh one: everything downstream
  // (bids, dispatch, WebSocket routing) keys on the SSO identity.
  // Interim: this is what lazy projection replaces once SOS can read /mechanic/profile itself.
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public MechanicResponse create(
      @Valid @RequestBody CreateMechanicRequest request, @AuthenticationPrincipal UUID callerId) {
    return mechanicService.create(request, callerId);
  }

  // GET /api/mechanics/{mechanicId} — Fetch a single mechanic profile by ID
  @GetMapping("/{mechanicId}")
  public MechanicResponse getById(@PathVariable UUID mechanicId) {
    return mechanicService.getById(mechanicId);
  }

  // GET /api/mechanics/available?lat=&lng=&problemType=&radiusKm= — Find nearby available mechanics
  // radiusKm (5–30, default 15): controls the H3 kRing search depth — matches broadcastRadiusKm on
  // SOS
  // problemType is optional: omit to search all specializations, provide to filter (e.g.,
  // "flat_tire")
  @GetMapping("/available")
  @PreAuthorize("hasRole('USER')")
  public List<MechanicResponse> findAvailable(
      @RequestParam @DecimalMin("10.41") @DecimalMax("14.69") double lat,
      @RequestParam @DecimalMin("102.5") @DecimalMax("107.63") double lng,
      @RequestParam(required = false) String problemType,
      @RequestParam(defaultValue = "15") @Min(5) @Max(30) int radiusKm) {
    return mechanicService.findAvailable(lat, lng, problemType, radiusKm);
  }

  // POST /api/v1/mechanics/{mechanicId}/skills — Declare work this mechanic can take on
  // Required for dispatch: the SOS fan-out inner-joins the skill table, so a mechanic with no
  // skill entry is never matched. Tags must equal the Medusa service group key (case-insensitive).
  @PostMapping("/{mechanicId}/skills")
  @ResponseStatus(HttpStatus.CREATED)
  public MechanicSkillResponse addSkill(
      @PathVariable UUID mechanicId,
      @Valid @RequestBody CreateMechanicSkillRequest request,
      @AuthenticationPrincipal UUID callerId) {
    Caller.mustMatch(callerId, mechanicId);
    return mechanicService.addSkill(mechanicId, request);
  }

  // GET /api/v1/mechanics/{mechanicId}/skills — List a mechanic's declared skills
  // An empty array means the mechanic exists but is not currently dispatchable
  @GetMapping("/{mechanicId}/skills")
  public List<MechanicSkillResponse> getSkills(@PathVariable UUID mechanicId) {
    return mechanicService.getSkills(mechanicId);
  }

  // PUT /api/v1/mechanics/{mechanicId}/skills/{skillId} — Replace one skill entry
  @PutMapping("/{mechanicId}/skills/{skillId}")
  public MechanicSkillResponse updateSkill(
      @PathVariable UUID mechanicId,
      @PathVariable UUID skillId,
      @Valid @RequestBody CreateMechanicSkillRequest request,
      @AuthenticationPrincipal UUID callerId) {
    Caller.mustMatch(callerId, mechanicId);
    return mechanicService.updateSkill(mechanicId, skillId, request);
  }

  // DELETE /api/v1/mechanics/{mechanicId}/skills/{skillId} — Withdraw a skill entry
  // Removing the last one makes the mechanic undispatchable again
  @DeleteMapping("/{mechanicId}/skills/{skillId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteSkill(
      @PathVariable UUID mechanicId,
      @PathVariable UUID skillId,
      @AuthenticationPrincipal UUID callerId) {
    Caller.mustMatch(callerId, mechanicId);
    mechanicService.deleteSkill(mechanicId, skillId);
  }

  // PATCH /api/mechanics/{mechanicId}/location?lat=&lng=&sosId= — Update mechanic's real-time GPS
  // sosId is optional: if provided skips the active-bid DB lookup for better performance
  // Called frequently by the mobile app while a mechanic is on an active job
  @PatchMapping("/{mechanicId}/location")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void updateLocation(
      @PathVariable UUID mechanicId,
      @RequestParam @DecimalMin("10.41") @DecimalMax("14.69") double lat,
      @RequestParam @DecimalMin("102.5") @DecimalMax("107.63") double lng,
      @RequestParam(required = false) UUID sosId,
      @AuthenticationPrincipal UUID callerId) {
    Caller.mustMatch(callerId, mechanicId);
    mechanicService.updateLocation(mechanicId, lat, lng, sosId);
  }

  // GET /api/mechanics/{mechanicId}/location — On-demand read of a mechanic's current position
  // Reads Redis first (fresh every ping), falls back to the last Postgres snapshot.
  // Continuous updates during an active job still arrive via WebSocket — use this only for
  // initial screen load or reconnect, not for polling.
  // Readable by that mechanic, and by a driver they are currently on a job for.
  @GetMapping("/{mechanicId}/location")
  public MechanicLocationResponse getCurrentLocation(
      @PathVariable UUID mechanicId, @AuthenticationPrincipal UUID callerId) {
    return mechanicService.getCurrentLocation(mechanicId, callerId);
  }

  // PATCH /api/mechanics/{mechanicId}/availability?available= — Toggle mechanic online/offline
  // Offline mechanics are excluded from all SOS fan-out and availability searches
  @PatchMapping("/{mechanicId}/availability")
  public MechanicResponse setAvailability(
      @PathVariable UUID mechanicId,
      @RequestParam boolean available,
      @AuthenticationPrincipal UUID callerId) {
    Caller.mustMatch(callerId, mechanicId);
    return mechanicService.setAvailability(mechanicId, available);
  }
}
