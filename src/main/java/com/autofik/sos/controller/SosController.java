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
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for the core SOS emergency request lifecycle.
 * Base URL: /api/sos
 * Handles creation, status progression, two-step COMPLETED approval, and cancellation.
 */
@RestController // Marks this as a REST API controller — all methods return JSON by default
@RequestMapping("/api/sos") // All endpoints in this class share this base URL prefix
@RequiredArgsConstructor
public class SosController {

    private final SosService sosService;

    // POST /api/sos — Driver submits a new SOS breakdown request
    // Returns 201 CREATED with the new ticket details
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SosRequestResponse create(@Valid @RequestBody CreateSosRequest request) {
        return sosService.create(request);
    }

    // GET /api/sos — Admin: retrieve all SOS tickets in the system
    @GetMapping
    public List<SosRequestResponse> getAll() {
        return sosService.getAll();
    }

    // GET /api/sos/{sosId} — Fetch a single SOS ticket by its unique ID
    @GetMapping("/{sosId}")
    public SosRequestResponse getById(@PathVariable UUID sosId) {
        return sosService.getById(sosId);
    }

    // GET /api/sos/user/{userId} — Fetch a driver's full SOS history
    @GetMapping("/user/{userId}")
    public List<SosRequestResponse> getByUser(@PathVariable UUID userId) {
        return sosService.getByUser(userId);
    }

    // GET /api/sos/status/{status} — Filter tickets by current state (e.g., PENDING, COMPLETED)
    @GetMapping("/status/{status}")
    public List<SosRequestResponse> getByStatus(@PathVariable SosStatus status) {
        return sosService.getByStatus(status);
    }

    // PATCH /api/sos/{sosId}/status — Mechanic directly sets ON_THE_WAY or ARRIVED (no approval needed)
    // COMPLETED must go through /propose-status → /approve-status instead
    @PatchMapping("/{sosId}/status")
    public SosRequestResponse updateStatus(@PathVariable UUID sosId,
                                           @RequestParam UUID mechanicId,
                                           @RequestParam SosStatus toStatus) {
        return sosService.updateStatus(sosId, mechanicId, toStatus);
    }

    // POST /api/sos/propose-status — Mechanic requests COMPLETED; creates a PENDING_APPROVAL history record
    // The user is notified via WebSocket and must confirm via /approve-status
    @PostMapping("/propose-status")
    @ResponseStatus(HttpStatus.CREATED)
    public SosStatusHistoryResponse proposeStatus(@Valid @RequestBody ProposeStatusChangeRequest request) {
        return sosService.proposeStatusChange(request);
    }

    // POST /api/sos/approve-status — Driver approves or rejects the mechanic's COMPLETED proposal
    @PostMapping("/approve-status")
    public SosRequestResponse approveStatus(@Valid @RequestBody ApproveStatusChangeRequest request) {
        return sosService.approveStatusChange(request);
    }

    // PATCH /api/sos/{sosId}/cancel — Driver cancels their own active SOS request
    @PatchMapping("/{sosId}/cancel")
    public SosRequestResponse cancel(@PathVariable UUID sosId, @RequestParam UUID userId) {
        return sosService.cancel(sosId, userId);
    }
}
