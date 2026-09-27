package com.autofik.sos.controller;

import com.autofik.sos.domain.enums.BidSortFilter;
import com.autofik.sos.dto.request.CreateBidRequest;
import com.autofik.sos.dto.request.UpdateBidStatusRequest;
import com.autofik.sos.dto.response.BidSosResponse;
import com.autofik.sos.security.AuthMechanic;
import com.autofik.sos.security.AuthUser;
import com.autofik.sos.security.Caller;
import com.autofik.sos.security.CurrentUser;
import com.autofik.sos.service.BidService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for managing mechanic bids on SOS requests. Base URL: /api/bids Handles bid
 * submission, listing, accept/reject by the driver, and mechanic-initiated cancellation.
 */
@RestController
@RequestMapping("/api/v1/bids")
@RequiredArgsConstructor
public class BidController {

  private final BidService bidService;

  // POST /api/bids — Mechanic submits a price bid on an open SOS request
  // Returns 201 CREATED with the new bid record
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public BidSosResponse create(
      @Valid @RequestBody CreateBidRequest request, @AuthMechanic CurrentUser mechanic) {
    Caller.mustMatch(mechanic.id(), request.mechanicId());
    return bidService.create(request);
  }

  // GET /api/bids/sos/{sosId}?sortBy= — Driver views all bids on their active SOS
  // sortBy is optional: NEAREST | CHEAPEST | TOP_RATED (defaults to insertion order if omitted)
  // Visible to the driver who owns the SOS and to mechanics who bid on it.
  @GetMapping("/sos/{sosId}")
  public List<BidSosResponse> getBySos(
      @PathVariable UUID sosId,
      @RequestParam(required = false) BidSortFilter sortBy,
      @AuthUser CurrentUser caller) {
    return bidService.getBySosRequest(sosId, sortBy, caller.id());
  }

  // GET /api/bids/mechanic/{mechanicId} — Mechanic views their own full bid history
  @GetMapping("/mechanic/{mechanicId}")
  public Page<BidSosResponse> getByMechanic(
      @PathVariable UUID mechanicId,
      @AuthMechanic CurrentUser mechanic,
      @PageableDefault(size = 20) Pageable pageable) {
    Caller.mustMatch(mechanic.id(), mechanicId);
    return bidService.getByMechanic(mechanicId, pageable);
  }

  // PATCH /api/bids/{bidId}/status — Driver accepts or rejects a specific bid
  // Accepting one bid automatically rejects all other PENDING bids on the same SOS
  @PatchMapping("/{bidId}/status")
  @PreAuthorize("hasRole('USER')")
  public BidSosResponse updateStatus(
      @PathVariable UUID bidId,
      @Valid @RequestBody UpdateBidStatusRequest request,
      @AuthUser CurrentUser caller) {
    Caller.mustMatch(caller.id(), request.userId());
    return bidService.updateStatus(bidId, request);
  }

  // PATCH /api/bids/{bidId}/cancel — Mechanic withdraws their own PENDING bid
  @PatchMapping("/{bidId}/cancel")
  public BidSosResponse cancel(@PathVariable UUID bidId, @AuthMechanic CurrentUser mechanic) {
    return bidService.cancel(bidId, mechanic.id());
  }
}
