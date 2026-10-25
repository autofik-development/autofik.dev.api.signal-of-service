package com.autofik.sos.controller;

import com.autofik.sos.dto.response.MechanicLocationResponse;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.dto.response.MechanicSkillResponse;
import com.autofik.sos.dto.response.MechanicSummaryResponse;
import com.autofik.sos.security.AuthMechanic;
import com.autofik.sos.security.AuthUser;
import com.autofik.sos.security.Caller;
import com.autofik.sos.security.CurrentUser;
import com.autofik.sos.service.MechanicService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for mechanic availability, skills, and real-time GPS tracking. Base URL:
 * /api/mechanics
 *
 * <p>There is no create or skill-write endpoint: mechanics are born in SSO, and the row here, with
 * its skill tags and service radius, is created and kept in step from the SSO profile in the
 * mechanic-context header (MechanicProfileSync).
 */
@RestController
@RequestMapping("/api/v1/mechanics")
@RequiredArgsConstructor
@Validated
public class MechanicController {

  private final MechanicService mechanicService;

  // GET /api/mechanics/{mechanicId} — Public view of one mechanic: no contact fields, no position
  @GetMapping("/{mechanicId}")
  public MechanicSummaryResponse getById(@PathVariable UUID mechanicId) {
    return mechanicService.getById(mechanicId);
  }

  // GET /api/mechanics/available?lat=&lng=&problemType=&radiusKm= — Find nearby available mechanics
  // radiusKm (5–20, default 15): controls the H3 kRing search depth — matches broadcastRadiusKm on
  // SOS
  // problemType is optional: omit to search all specializations, provide to filter (e.g.,
  // "flat_tire")
  @GetMapping("/available")
  @PreAuthorize("hasRole('USER')")
  public List<MechanicSummaryResponse> findAvailable(
      @RequestParam @DecimalMin("10.41") @DecimalMax("14.69") double lat,
      @RequestParam @DecimalMin("102.5") @DecimalMax("107.63") double lng,
      @RequestParam(required = false) String problemType,
      @RequestParam(defaultValue = "15") @Min(5) @Max(20) int radiusKm) {
    return mechanicService.findAvailable(lat, lng, problemType, radiusKm);
  }

  // GET /api/v1/mechanics/{mechanicId}/skills — List a mechanic's declared skills
  // An empty array means the mechanic exists but is not currently dispatchable
  @GetMapping("/{mechanicId}/skills")
  public List<MechanicSkillResponse> getSkills(@PathVariable UUID mechanicId) {
    return mechanicService.getSkills(mechanicId);
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
      @AuthMechanic CurrentUser mechanic) {
    Caller.mustMatch(mechanic.id(), mechanicId);
    mechanicService.updateLocation(mechanicId, lat, lng, sosId);
  }

  // GET /api/mechanics/{mechanicId}/location — On-demand read of a mechanic's current position
  // Reads the last Postgres snapshot; asOf is the time of that fix (at most one write-throttle
  // window behind the latest ping), null if the row has no stamp.
  // Continuous updates during an active job still arrive via WebSocket — use this only for
  // initial screen load or reconnect, not for polling.
  // Readable by that mechanic, and by a driver they are currently on a job for.
  @GetMapping("/{mechanicId}/location")
  public MechanicLocationResponse getCurrentLocation(
      @PathVariable UUID mechanicId, @AuthUser CurrentUser caller) {
    return mechanicService.getCurrentLocation(mechanicId, caller.id());
  }

  // PATCH /api/mechanics/{mechanicId}/availability?available= — Toggle mechanic online/offline
  // Offline mechanics are excluded from all SOS fan-out and availability searches
  @PatchMapping("/{mechanicId}/availability")
  public MechanicResponse setAvailability(
      @PathVariable UUID mechanicId,
      @RequestParam boolean available,
      @AuthMechanic CurrentUser mechanic) {
    Caller.mustMatch(mechanic.id(), mechanicId);
    return mechanicService.setAvailability(mechanicId, available);
  }
}
