package com.autofik.sos.controller;

import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.ApproveStatusChangeRequest;
import com.autofik.sos.dto.request.CreateSosRequest;
import com.autofik.sos.dto.request.ProposeStatusChangeRequest;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.dto.response.SosStatusHistoryResponse;
import com.autofik.sos.service.SosService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller for the core SOS emergency request lifecycle.
 * Base URL: /api/sos
 * Handles creation, status progression, two-step COMPLETED approval, and cancellation.
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
    public SosRequestResponse create(@Valid @RequestBody CreateSosRequest request) {
        return sosService.create(request);
    }

    // GET /api/v1/sos?userId=&status=&page=&size= — List SOS tickets with optional filters
    // userId: filter by driver; status: filter by state (PENDING, ACCEPTED, …); both are optional
    @GetMapping
    public Page<SosRequestResponse> getAll(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) SosStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        if (userId != null) return sosService.getByUser(userId, pageable);
        if (status != null) return sosService.getByStatus(status, pageable);
        return sosService.getAll(pageable);
    }

    // GET /api/v1/sos/{sosId} — Fetch a single SOS ticket by its unique ID
    @GetMapping("/{sosId}")
    public SosRequestResponse getById(@PathVariable UUID sosId) {
        return sosService.getById(sosId);
    }

    // PATCH /api/v1/sos/{sosId}/status — Mechanic sets ON_THE_WAY or ARRIVED (no approval needed)
    // COMPLETED must go through POST /completion → PATCH /completion instead
    @PatchMapping("/{sosId}/status")
    public SosRequestResponse updateStatus(@PathVariable UUID sosId,
                                           @RequestParam UUID mechanicId,
                                           @RequestParam SosStatus toStatus) {
        return sosService.updateStatus(sosId, mechanicId, toStatus);
    }

    // POST /api/v1/sos/{sosId}/completion — Mechanic proposes COMPLETED; creates a PENDING_APPROVAL record
    // The user is notified via WebSocket and must confirm via PATCH /completion
    @PostMapping("/{sosId}/completion")
    @ResponseStatus(HttpStatus.CREATED)
    public SosStatusHistoryResponse proposeCompletion(@PathVariable UUID sosId,
                                                      @Valid @RequestBody ProposeStatusChangeRequest request) {
        return sosService.proposeStatusChange(sosId, request);
    }

    // PATCH /api/v1/sos/completion — Driver approves or rejects the mechanic's COMPLETED proposal
    @PatchMapping("/completion")
    public SosRequestResponse approveCompletion(@Valid @RequestBody ApproveStatusChangeRequest request) {
        return sosService.approveStatusChange(request);
    }

    // PATCH /api/v1/sos/{sosId}/cancel — Driver cancels their own active SOS request
    @PatchMapping("/{sosId}/cancel")
    public SosRequestResponse cancel(@PathVariable UUID sosId, @RequestParam UUID userId) {
        return sosService.cancel(sosId, userId);
    }
}
