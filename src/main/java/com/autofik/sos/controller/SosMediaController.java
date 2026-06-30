package com.autofik.sos.controller;

import com.autofik.sos.dto.response.SosMediaResponse;
import com.autofik.sos.service.SosMediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/sos/{sosId}/media")
@RequiredArgsConstructor
public class SosMediaController {

    private final SosMediaService sosMediaService;

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public SosMediaResponse upload(@PathVariable UUID sosId,
                                    @RequestParam MultipartFile file,
                                    @RequestParam(required = false) UUID userId,
                                    @RequestParam(required = false) UUID mechanicId) {
        return sosMediaService.upload(sosId, file, userId, mechanicId);
    }

    @GetMapping
    public List<SosMediaResponse> getBySos(@PathVariable UUID sosId) {
        return sosMediaService.getBySosRequest(sosId);
    }
}
