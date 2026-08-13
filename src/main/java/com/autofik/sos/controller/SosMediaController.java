package com.autofik.sos.controller;

import com.autofik.sos.dto.response.SosMediaResponse;
import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.service.SosMediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for uploading and retrieving media files attached to an SOS request.
 * Base URL: /api/sos/{sosId}/media
 * Supports photo and video uploads (max 50 MB). Files are stored in S3 and served via presigned URLs.
 */
@RestController
@RequestMapping("/api/v1/sos/{sosId}/media")
@RequiredArgsConstructor
public class SosMediaController {

    private final SosMediaService sosMediaService;

    // POST /api/sos/{sosId}/media — Upload a photo or video to an active SOS ticket
    // Which role is uploading comes from the identity header, not from a form field: a caller
    // could otherwise upload as the other party.
    // consumes multipart/form-data because the request carries a raw binary file stream
    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public SosMediaResponse upload(@PathVariable UUID sosId,
                                   @RequestParam MultipartFile file,
                                   Authentication caller) {
        UUID callerId = (UUID) caller.getPrincipal();
        boolean isMechanic = caller.getAuthorities().stream()
                .anyMatch(a -> GatewayIdentityFilter.ROLE_MECHANIC.equals(a.getAuthority()));
        return isMechanic
                ? sosMediaService.upload(sosId, file, null, callerId)
                : sosMediaService.upload(sosId, file, callerId, null);
    }

    // GET /api/sos/{sosId}/media — Retrieve all media files attached to a SOS ticket
    // Each response includes a presigned S3 URL valid for 15 minutes
    // Same visibility rule as reading the SOS itself.
    @GetMapping
    public List<SosMediaResponse> getBySos(@PathVariable UUID sosId,
                                           @AuthenticationPrincipal UUID callerId) {
        return sosMediaService.getBySosRequest(sosId, callerId);
    }
}
