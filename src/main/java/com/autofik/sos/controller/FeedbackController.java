package com.autofik.sos.controller;

import com.autofik.sos.dto.request.CreateFeedbackRequest;
import com.autofik.sos.dto.response.FeedbackResponse;
import com.autofik.sos.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FeedbackResponse create(@Valid @RequestBody CreateFeedbackRequest request) {
        return feedbackService.create(request);
    }

    @GetMapping("/sos/{sosId}")
    public FeedbackResponse getBySos(@PathVariable UUID sosId) {
        return feedbackService.getBySosRequest(sosId);
    }

    @GetMapping("/mechanic/{mechanicId}")
    public List<FeedbackResponse> getByMechanic(@PathVariable UUID mechanicId) {
        return feedbackService.getByMechanic(mechanicId);
    }
}
