package com.autofik.sos.controller;

import com.autofik.sos.domain.enums.BidSortFilter;
import com.autofik.sos.dto.request.CreateBidRequest;
import com.autofik.sos.dto.request.UpdateBidStatusRequest;
import com.autofik.sos.dto.response.BidSosResponse;
import com.autofik.sos.service.BidService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for managing mechanic bids on SOS requests.
 * Base URL: /api/bids
 * Handles bid submission, listing, accept/reject by the driver, and mechanic-initiated cancellation.
 */
@RestController
@RequestMapping("/api/bids")
@RequiredArgsConstructor
public class BidController {

    private final BidService bidService;

    // POST /api/bids — Mechanic submits a price bid on an open SOS request
    // Returns 201 CREATED with the new bid record
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BidSosResponse create(@Valid @RequestBody CreateBidRequest request) {
        return bidService.create(request);
    }

    // GET /api/bids — Admin: retrieve all bids across all SOS requests
    @GetMapping
    public List<BidSosResponse> getAll() {
        return bidService.getAll();
    }

    // GET /api/bids/sos/{sosId}?sortBy= — Driver views all bids on their active SOS
    // sortBy is optional: NEAREST | CHEAPEST | TOP_RATED (defaults to insertion order if omitted)
    @GetMapping("/sos/{sosId}")
    public List<BidSosResponse> getBySos(@PathVariable UUID sosId,
                                          @RequestParam(required = false) BidSortFilter sortBy) {
        return bidService.getBySosRequest(sosId, sortBy);
    }

    // GET /api/bids/mechanic/{mechanicId} — Mechanic views their own full bid history
    @GetMapping("/mechanic/{mechanicId}")
    public List<BidSosResponse> getByMechanic(@PathVariable UUID mechanicId) {
        return bidService.getByMechanic(mechanicId);
    }

    // PATCH /api/bids/{bidId}/status — Driver accepts or rejects a specific bid
    // Accepting one bid automatically rejects all other PENDING bids on the same SOS
    @PatchMapping("/{bidId}/status")
    public BidSosResponse updateStatus(@PathVariable UUID bidId,
                                       @Valid @RequestBody UpdateBidStatusRequest request) {
        return bidService.updateStatus(bidId, request);
    }

    // PATCH /api/bids/{bidId}/cancel — Mechanic withdraws their own PENDING bid
    @PatchMapping("/{bidId}/cancel")
    public BidSosResponse cancel(@PathVariable UUID bidId, @RequestParam UUID mechanicId) {
        return bidService.cancel(bidId, mechanicId);
    }
}
