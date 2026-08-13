package com.autofik.sos.controller;

import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.ApproveStatusChangeRequest;
import com.autofik.sos.dto.request.CreateSosRequest;
import com.autofik.sos.dto.request.ProposeStatusChangeRequest;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.dto.response.SosStatusHistoryResponse;
import com.autofik.sos.security.Caller;
import com.autofik.sos.service.SosService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller for the core SOS emergency request lifecycle.
 * Base URL: /api/sos
 * Handles creation, status progression, two-step COMPLETED approval, and cancellation.
 *
 * <p>The acting identity always comes from {@code @AuthenticationPrincipal} — the header APISIX
 * writes — never from a parameter the caller chose. The {@code userId} / {@code mechanicId} fields
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
    public SosRequestResponse create(@Valid @RequestBody CreateSosRequest request,
                                     @AuthenticationPrincipal UUID callerId) {
        Caller.mustMatch(callerId, request.userId());
        return sosService.create(request);
    }

    // GET /api/v1/sos?status=&page=&size= — List the caller's own SOS tickets
    // status: optional filter by state (PENDING, ACCEPTED, …). Scope is always the caller —
    // unscoped, this listed every breakdown in the system.
    @GetMapping
    public Page<SosRequestResponse> getAll(
            @RequestParam(required = false) SosStatus status,
            @AuthenticationPrincipal UUID callerId,
            @PageableDefault(size = 20) Pageable pageable) {
        return status != null
                ? sosService.getByUserAndStatus(callerId, status, pageable)
                : sosService.getByUser(callerId, pageable);
    }

    // GET /api/v1/sos/{sosId} — Fetch a single SOS ticket by its unique ID
    // Readable by the driver who owns it and by any mechanic who bid on it.
    @GetMapping("/{sosId}")
    public SosRequestResponse getById(@PathVariable UUID sosId,
                                      @AuthenticationPrincipal UUID callerId) {
        return sosService.getById(sosId, callerId);
    }

    // PATCH /api/v1/sos/{sosId}/status — Mechanic sets ON_THE_WAY or ARRIVED (no approval needed)
    // COMPLETED must go through POST /completion → PATCH /completion instead
    @PatchMapping("/{sosId}/status")
    public SosRequestResponse updateStatus(@PathVariable UUID sosId,
                                           @AuthenticationPrincipal UUID callerId,
                                           @RequestParam SosStatus toStatus) {
        return sosService.updateStatus(sosId, callerId, toStatus);
    }

    // POST /api/v1/sos/{sosId}/completion — Mechanic proposes COMPLETED; creates a PENDING_APPROVAL record
    // The user is notified via WebSocket and must confirm via PATCH /completion
    @PostMapping("/{sosId}/completion")
    @ResponseStatus(HttpStatus.CREATED)
    public SosStatusHistoryResponse proposeCompletion(@PathVariable UUID sosId,
                                                      @Valid @RequestBody ProposeStatusChangeRequest request,
                                                      @AuthenticationPrincipal UUID callerId) {
        Caller.mustMatch(callerId, request.mechanicId());
        return sosService.proposeStatusChange(sosId, request);
    }

    // PATCH /api/v1/sos/completion — Driver approves or rejects the mechanic's COMPLETED proposal
    @PatchMapping("/completion")
    public SosRequestResponse approveCompletion(@Valid @RequestBody ApproveStatusChangeRequest request,
                                                @AuthenticationPrincipal UUID callerId) {
        Caller.mustMatch(callerId, request.userId());
        return sosService.approveStatusChange(request);
    }

    // PATCH /api/v1/sos/{sosId}/cancel — Driver cancels their own active SOS request
    @PatchMapping("/{sosId}/cancel")
    public SosRequestResponse cancel(@PathVariable UUID sosId,
                                     @AuthenticationPrincipal UUID callerId) {
        return sosService.cancel(sosId, callerId);
    }

    // POST /api/v1/sos/{sosId}/rebroadcast — Driver re-opens an EXPIRED SOS and fans out to nearby mechanics again
    // Capped at 3 attempts; returns 429 when exhausted
    @PostMapping("/{sosId}/rebroadcast")
    public SosRequestResponse rebroadcast(@PathVariable UUID sosId,
                                          @AuthenticationPrincipal UUID callerId) {
        return sosService.rebroadcast(sosId, callerId);
    }
}
