package com.autofik.sos.controller;

import com.autofik.sos.config.SecurityConfig;
import com.autofik.sos.dto.response.FeedbackResponse;
import com.autofik.sos.exception.ConflictException;
import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.service.FeedbackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FeedbackController.class)
@Import({SecurityConfig.class, GatewayIdentityFilter.class})
class FeedbackControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean FeedbackService feedbackService;

    private final UUID userId = UUID.randomUUID();
    private final UUID mechanicId = UUID.randomUUID();
    private final UUID sosId = UUID.randomUUID();

    private String body(UUID author, String rating) {
        return """
                {"userId":"%s","mechanicId":"%s","sosId":"%s","rating":%s,"comment":"quick and polite"}
                """.formatted(author, mechanicId, sosId, rating);
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        when(feedbackService.create(any())).thenReturn(new FeedbackResponse(
                UUID.randomUUID(), "quick and polite", BigDecimal.valueOf(5), null, null, null));

        mvc.perform(post("/api/v1/feedback")
                        .header(GatewayIdentityFilter.USER_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(userId, "5")))
                .andExpect(status().isCreated());
    }

    // Ratings are 1–5; anything else would skew the cached average on MechanicRate.
    @Test
    void create_ratingOutOfRange_returns400() throws Exception {
        mvc.perform(post("/api/v1/feedback")
                        .header(GatewayIdentityFilter.USER_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(userId, "9")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.rating").exists());

        verify(feedbackService, never()).create(any());
    }

    // Rating on someone else's behalf is how a mechanic would review themselves.
    @Test
    void create_bodyUserIdDoesNotMatchCaller_returns403() throws Exception {
        mvc.perform(post("/api/v1/feedback")
                        .header(GatewayIdentityFilter.USER_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), "5")))
                .andExpect(status().isForbidden());

        verify(feedbackService, never()).create(any());
    }

    @Test
    void create_jobNotCompleted_returns409() throws Exception {
        when(feedbackService.create(any()))
                .thenThrow(new ConflictException("Feedback can only be submitted for COMPLETED requests"));

        mvc.perform(post("/api/v1/feedback")
                        .header(GatewayIdentityFilter.USER_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(userId, "5")))
                .andExpect(status().isConflict());
    }
}
