package com.autofik.sos.service;

import com.autofik.sos.dto.request.CreateFeedbackRequest;
import com.autofik.sos.dto.response.FeedbackResponse;

import java.util.List;
import java.util.UUID;
/**
 * Service Interface defining the business use cases for handling customer Feedback and Ratings.
 * Acts as the boundary contract between your Web/Mobile Controllers and the underlying business logic.
 */
public interface FeedbackService {

    /**
     * use case: driver submits a rating and review after an emergency
     * @param request contain data such as rating and comment userId and mechanicId.
     * @return feedback details
     */
    FeedbackResponse create(CreateFeedbackRequest request);

    /**
     * use case: fetch the specific feedback from specified sos request
     * @param sosId identify the target sos request
     * @return the matching feedback to target sos request
     */
    FeedbackResponse getBySosRequest(UUID sosId);

    /**
     * Use Case: View all historical feedback reviews left for a specific mechanic.
     * * Why it returns a List: Because over time, a professional mechanic will service
     * many drivers and accumulate multiple reviews.
     * @param mechanicId identify specified mechanic
     * @return list of review wirtten about the mechanic
     */
    List<FeedbackResponse> getByMechanic(UUID mechanicId);
}
