package com.autofik.sos.controller;

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

@RestController
@RequestMapping("/api/bids")
@RequiredArgsConstructor
public class BidController {

    private final BidService bidService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BidSosResponse create(@Valid @RequestBody CreateBidRequest request) {
        return bidService.create(request);
    }

    @GetMapping("/sos/{sosId}")
    public List<BidSosResponse> getBySos(@PathVariable UUID sosId) {
        return bidService.getBySosRequest(sosId);
    }

    @GetMapping("/mechanic/{mechanicId}")
    public List<BidSosResponse> getByMechanic(@PathVariable UUID mechanicId) {
        return bidService.getByMechanic(mechanicId);
    }

    @PatchMapping("/{bidId}/status")
    public BidSosResponse updateStatus(@PathVariable UUID bidId,
                                       @Valid @RequestBody UpdateBidStatusRequest request) {
        return bidService.updateStatus(bidId, request);
    }
}
