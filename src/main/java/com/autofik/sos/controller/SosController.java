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

@RestController
@RequestMapping("/api/sos")
@RequiredArgsConstructor
public class SosController {

    private final SosService sosService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SosRequestResponse create(@Valid @RequestBody CreateSosRequest request) {
        return sosService.create(request);
    }

    @GetMapping
    public List<SosRequestResponse> getAll() {
        return sosService.getAll();
    }

    @GetMapping("/{sosId}")
    public SosRequestResponse getById(@PathVariable UUID sosId) {
        return sosService.getById(sosId);
    }

    @GetMapping("/user/{userId}")
    public List<SosRequestResponse> getByUser(@PathVariable UUID userId) {
        return sosService.getByUser(userId);
    }

    @GetMapping("/status/{status}")
    public List<SosRequestResponse> getByStatus(@PathVariable SosStatus status) {
        return sosService.getByStatus(status);
    }

    @PatchMapping("/{sosId}/status")
    public SosRequestResponse updateStatus(@PathVariable UUID sosId,
                                           @RequestParam UUID mechanicId,
                                           @RequestParam SosStatus toStatus) {
        return sosService.updateStatus(sosId, mechanicId, toStatus);
    }

    @PostMapping("/propose-status")
    @ResponseStatus(HttpStatus.CREATED)
    public SosStatusHistoryResponse proposeStatus(@Valid @RequestBody ProposeStatusChangeRequest request) {
        return sosService.proposeStatusChange(request);
    }

    @PostMapping("/approve-status")
    public SosRequestResponse approveStatus(@Valid @RequestBody ApproveStatusChangeRequest request) {
        return sosService.approveStatusChange(request);
    }

    @PatchMapping("/{sosId}/cancel")
    public SosRequestResponse cancel(@PathVariable UUID sosId, @RequestParam UUID userId) {
        return sosService.cancel(sosId, userId);
    }
}
