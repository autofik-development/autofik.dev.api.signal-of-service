package com.autofik.sos.controller;

import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.ApproveStatusChangeRequest;
import com.autofik.sos.dto.request.CreateSosRequest;
import com.autofik.sos.dto.request.ProposeStatusChangeRequest;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.dto.response.SosStatusHistoryResponse;
import com.autofik.sos.security.AuthMechanic;
import com.autofik.sos.security.AuthUser;
import com.autofik.sos.security.Caller;
import com.autofik.sos.security.CurrentUser;
import com.autofik.sos.service.SosService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for the core SOS emergency request lifecycle. Base URL: /api/sos Handles
 * creation, status progression, two-step COMPLETED approval, and cancellation.
 *
 * <p>The acting identity always comes from the gateway's {@code user-context} / {@code
 * mechanic-context}, via {@link AuthUser} or {@link AuthMechanic} — never from a parameter the
 * caller chose. Mechanic-only routes take {@link AuthMechanic}, which answers a driver with 403;
 * driver-only routes add {@code hasRole('USER')}. The {@code userId} / {@code mechanicId} fields
 * still present in the request bodies are compared against it and then ignored; they are removed
 * once no client sends them.
 */
@RestController
@RequestMapping("/api/v1/sos")
@RequiredArgsConstructor
@Validated
public class SosController {

  private final SosService sosService;

  // POST /api/v1/sos — Driver submits a new SOS breakdown request
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('USER')")
  public SosRequestResponse create(
      @Valid @RequestBody CreateSosRequest request, @AuthUser CurrentUser caller) {
    Caller.mustMatch(caller.id(), request.userId());
    return sosService.create(request);
  }

  // GET /api/v1/sos?status=&page=&size= — List the caller's own SOS tickets
  // status: optional filter by state (PENDING, ACCEPTED, …). Scope is always the caller —
  // unscoped, this listed every breakdown in the system.
  @GetMapping
  public Page<SosRequestResponse> getAll(
      @RequestParam(required = false) SosStatus status,
      @AuthUser CurrentUser caller,
      @PageableDefault(size = 20) Pageable pageable) {
    return status != null
        ? sosService.getByUserAndStatus(caller.id(), status, pageable)
        : sosService.getByUser(caller.id(), pageable);
  }

  // GET /api/v1/sos/{sosId} — Fetch a single SOS ticket by its unique ID
  // Readable by the driver who owns it and by any mechanic who bid on it.
  @GetMapping("/{sosId}")
  public SosRequestResponse getById(@PathVariable UUID sosId, @AuthUser CurrentUser caller) {
    return sosService.getById(sosId, caller.id());
  }

  // GET /api/v1/sos/{sosId}/nearby-mechanics — Live list of mechanics eligible for this ticket
  // right now, for the "waiting for a bid" screen. Same visibility rule as reading the SOS itself.
  @GetMapping("/{sosId}/nearby-mechanics")
  public List<MechanicResponse> getNearbyMechanics(
      @PathVariable UUID sosId, @AuthUser CurrentUser caller) {
    return sosService.getNearbyMechanics(sosId, caller.id());
  }

  // PATCH /api/v1/sos/{sosId}/status — Mechanic sets ON_THE_WAY or ARRIVED (no approval needed)
  // COMPLETED must go through POST /completion → PATCH /completion instead
  @PatchMapping("/{sosId}/status")
  public SosRequestResponse updateStatus(
      @PathVariable UUID sosId,
      @AuthMechanic CurrentUser mechanic,
      @RequestParam SosStatus toStatus) {
    return sosService.updateStatus(sosId, mechanic.id(), toStatus);
  }

  // POST /api/v1/sos/{sosId}/completion — Mechanic proposes COMPLETED; creates a PENDING_APPROVAL
  // record
  // The user is notified via WebSocket and must confirm via PATCH /completion
  @PostMapping("/{sosId}/completion")
  @ResponseStatus(HttpStatus.CREATED)
  public SosStatusHistoryResponse proposeCompletion(
      @PathVariable UUID sosId,
      @Valid @RequestBody ProposeStatusChangeRequest request,
      @AuthMechanic CurrentUser mechanic) {
    Caller.mustMatch(mechanic.id(), request.mechanicId());
    return sosService.proposeStatusChange(sosId, request);
  }

  // PATCH /api/v1/sos/completion — Driver approves or rejects the mechanic's COMPLETED proposal
  @PatchMapping("/completion")
  @PreAuthorize("hasRole('USER')")
  public SosRequestResponse approveCompletion(
      @Valid @RequestBody ApproveStatusChangeRequest request, @AuthUser CurrentUser caller) {
    Caller.mustMatch(caller.id(), request.userId());
    return sosService.approveStatusChange(request);
  }

  // PATCH /api/v1/sos/{sosId}/cancel — Driver cancels their own active SOS request
  @PatchMapping("/{sosId}/cancel")
  @PreAuthorize("hasRole('USER')")
  public SosRequestResponse cancel(@PathVariable UUID sosId, @AuthUser CurrentUser caller) {
    return sosService.cancel(sosId, caller.id());
  }

  // POST /api/v1/sos/{sosId}/rebroadcast — Driver re-opens an EXPIRED SOS and fans out to nearby
  // mechanics again
  // Capped at 3 attempts; returns 429 when exhausted
  @PostMapping("/{sosId}/rebroadcast")
  @PreAuthorize("hasRole('USER')")
  public SosRequestResponse rebroadcast(@PathVariable UUID sosId, @AuthUser CurrentUser caller) {
    return sosService.rebroadcast(sosId, caller.id());
  }
}
