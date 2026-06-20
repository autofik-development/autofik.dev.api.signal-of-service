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

@RestController
@RequestMapping("/api/mechanics")
@RequiredArgsConstructor
public class MechanicController {

    private final MechanicService mechanicService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MechanicResponse create(@Valid @RequestBody CreateMechanicRequest request) {
        return mechanicService.create(request);
    }

    @GetMapping("/{mechanicId}")
    public MechanicResponse getById(@PathVariable UUID mechanicId) {
        return mechanicService.getById(mechanicId);
    }

    @GetMapping("/available")
    public List<MechanicResponse> findAvailable(@RequestParam double lat,
                                                @RequestParam double lng,
                                                @RequestParam(required = false) String problemType) {
        return problemType != null
                ? mechanicService.findAvailable(lat, lng, problemType)
                : mechanicService.findAvailable(lat, lng);
    }

    @PatchMapping("/{mechanicId}/location")
    public MechanicResponse updateLocation(@PathVariable UUID mechanicId,
                                           @RequestParam double lat,
                                           @RequestParam double lng) {
        return mechanicService.updateLocation(mechanicId, lat, lng);
    }
}
