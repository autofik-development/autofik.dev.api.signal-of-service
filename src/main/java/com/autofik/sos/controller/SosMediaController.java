package com.autofik.sos.controller;

import com.autofik.sos.dto.response.SosMediaResponse;
import com.autofik.sos.service.SosMediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
    // Exactly one of userId or mechanicId must be provided to identify the uploader
    // consumes multipart/form-data because the request carries a raw binary file stream
    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public SosMediaResponse upload(@PathVariable UUID sosId,
                                    @RequestParam MultipartFile file,
                                    @RequestParam(required = false) UUID userId,
                                    @RequestParam(required = false) UUID mechanicId) {
        if ((userId == null) == (mechanicId == null)) {
            throw new IllegalArgumentException("Exactly one of userId or mechanicId must be provided");
        }
        return sosMediaService.upload(sosId, file, userId, mechanicId);
    }

    // GET /api/sos/{sosId}/media — Retrieve all media files attached to a SOS ticket
    // Each response includes a presigned S3 URL valid for 15 minutes
    @GetMapping
    public List<SosMediaResponse> getBySos(@PathVariable UUID sosId) {
        return sosMediaService.getBySosRequest(sosId);
    }
}
