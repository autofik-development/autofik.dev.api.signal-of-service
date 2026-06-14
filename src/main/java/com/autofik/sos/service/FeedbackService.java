package com.autofik.sos.service;

import com.autofik.sos.dto.request.CreateFeedbackRequest;
import com.autofik.sos.dto.response.FeedbackResponse;

import java.util.List;
import java.util.UUID;

public interface FeedbackService {
    FeedbackResponse create(CreateFeedbackRequest request);
    FeedbackResponse getBySosRequest(UUID sosId);
    List<FeedbackResponse> getByMechanic(UUID mechanicId);
}
