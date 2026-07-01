package com.autofik.sos.controller;

import com.autofik.sos.dto.request.CreateMechanicRequest;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.service.MechanicService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for mechanic account management, availability, and real-time GPS tracking.
 * Base URL: /api/mechanics
 */
@RestController
@RequestMapping("/api/mechanics")
@RequiredArgsConstructor
public class MechanicController {

    private final MechanicService mechanicService;

    // POST /api/mechanics — Register a new mechanic account
    // Returns 201 CREATED with the created mechanic profile
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MechanicResponse create(@Valid @RequestBody CreateMechanicRequest request) {
        return mechanicService.create(request);
    }

    // GET /api/mechanics — Admin: list all mechanic accounts
    @GetMapping
    public List<MechanicResponse> getAll() {
        return mechanicService.getAll();
    }

    // GET /api/mechanics/{mechanicId} — Fetch a single mechanic profile by ID
    @GetMapping("/{mechanicId}")
    public MechanicResponse getById(@PathVariable UUID mechanicId) {
        return mechanicService.getById(mechanicId);
    }

    // GET /api/mechanics/available?lat=&lng=&problemType= — Find nearby available mechanics
    // problemType is optional: omit to search all specializations, provide to filter (e.g., "flat_tire")
    // Uses two-stage H3 + PostGIS geo-search under the hood
    @GetMapping("/available")
    public List<MechanicResponse> findAvailable(@RequestParam double lat,
                                                @RequestParam double lng,
                                                @RequestParam(required = false) String problemType) {
        return problemType != null
                ? mechanicService.findAvailable(lat, lng, problemType)
                : mechanicService.findAvailable(lat, lng);
    }

    // PATCH /api/mechanics/{mechanicId}/location?lat=&lng=&sosId= — Update mechanic's real-time GPS
    // sosId is optional: if provided skips the active-bid DB lookup for better performance
    // Called frequently by the mobile app while a mechanic is on an active job
    @PatchMapping("/{mechanicId}/location")
    public MechanicResponse updateLocation(@PathVariable UUID mechanicId,
                                           @RequestParam double lat,
                                           @RequestParam double lng,
                                           @RequestParam(required = false) UUID sosId) {
        return mechanicService.updateLocation(mechanicId, lat, lng, sosId);
    }

    // PATCH /api/mechanics/{mechanicId}/availability?available= — Toggle mechanic online/offline
    // Offline mechanics are excluded from all SOS fan-out and availability searches
    @PatchMapping("/{mechanicId}/availability")
    public MechanicResponse setAvailability(@PathVariable UUID mechanicId,
                                            @RequestParam boolean available) {
        return mechanicService.setAvailability(mechanicId, available);
    }
}
