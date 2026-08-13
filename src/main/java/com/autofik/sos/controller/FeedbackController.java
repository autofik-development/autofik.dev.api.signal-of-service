package com.autofik.sos.controller;

import com.autofik.sos.dto.request.CreateFeedbackRequest;
import com.autofik.sos.dto.response.FeedbackResponse;
import com.autofik.sos.security.Caller;
import com.autofik.sos.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller for submitting and retrieving post-service feedback and ratings.
 * Base URL: /api/feedback
 * Feedback can only be submitted after a SOS request reaches COMPLETED status.
 */
@RestController
@RequestMapping("/api/v1/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    // POST /api/feedback — Driver submits a rating and optional comment after a completed job
    // Returns 201 CREATED; also triggers a recalculation of the mechanic's average rating
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FeedbackResponse create(@Valid @RequestBody CreateFeedbackRequest request,
                                   @AuthenticationPrincipal UUID callerId) {
        Caller.mustMatch(callerId, request.userId());
        return feedbackService.create(request);
    }

    // GET /api/feedback/sos/{sosId} — Fetch the single feedback record tied to a completed SOS
    @GetMapping("/sos/{sosId}")
    public FeedbackResponse getBySos(@PathVariable UUID sosId) {
        return feedbackService.getBySosRequest(sosId);
    }

    // GET /api/feedback/mechanic/{mechanicId} — Fetch all reviews ever written about a mechanic
    // Used to display review history and compute reputation on the mechanic's profile page
    @GetMapping("/mechanic/{mechanicId}")
    public Page<FeedbackResponse> getByMechanic(@PathVariable UUID mechanicId,
                                                 @PageableDefault(size = 20) Pageable pageable) {
        return feedbackService.getByMechanic(mechanicId, pageable);
    }
}
