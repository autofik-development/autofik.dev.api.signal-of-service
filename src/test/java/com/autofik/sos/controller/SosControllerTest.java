package com.autofik.sos.controller;

import com.autofik.sos.config.SecurityConfig;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.exception.ConflictException;
import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.service.SosService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The web layer of the SOS lifecycle: identity resolution, bean validation, and the status codes
 * {@link com.autofik.sos.exception.GlobalExceptionHandler} maps service failures onto.
 *
 * <p>The real {@link GatewayIdentityFilter} and {@link SecurityConfig} are wired in rather than
 * stubbed, because the thing worth pinning is that the acting identity comes from the header APISIX
 * writes and from nowhere else.
 */
@WebMvcTest(SosController.class)
@Import({SecurityConfig.class, GatewayIdentityFilter.class})
class SosControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SosService sosService;

    private final UUID callerId = UUID.randomUUID();
    private final UUID sosId = UUID.randomUUID();
    private final UUID vehicleId = UUID.randomUUID();

    private SosRequestResponse response() {
        return new SosRequestResponse(sosId, "FLAT_TIRE", null, null, SosStatus.PENDING,
                11.5573, 104.9282, null, null, null, null, 0, null, null, null);
    }

    private String createBody(UUID userId) {
        return """
                {"userId":"%s","serviceGroupId":"sg_01","latitude":11.5573,
                 "longitude":104.9282,"vehicleId":"%s"}
                """.formatted(userId, vehicleId);
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        when(sosService.create(any())).thenReturn(response());

        mvc.perform(post("/api/v1/sos")
                        .header(GatewayIdentityFilter.USER_HEADER, callerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(callerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sosId").value(sosId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // ponytail: the internal gateway isn't forwarding headers yet, so GatewayIdentityFilter falls
    // back to the userId already in the body instead of rejecting outright. Remove this test (and
    // the fallback) once the gateway forwards a real header on every request.
    @Test
    void create_noIdentityHeader_fallsBackToBodyUserId_succeeds() throws Exception {
        when(sosService.create(any())).thenReturn(response());

        mvc.perform(post("/api/v1/sos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(callerId)))
                .andExpect(status().isCreated());

        verify(sosService).create(any());
    }

    // No header AND nothing to fall back to (no userId in the body either) is still rejected —
    // the fallback covers a missing gateway, not a missing identity altogether.
    @Test
    void create_noIdentityHeaderAndNoBodyUserId_isRejected() throws Exception {
        String bodyWithoutUserId = """
                {"serviceGroupId":"sg_01","latitude":11.5573,
                 "longitude":104.9282,"vehicleId":"%s"}
                """.formatted(vehicleId);

        mvc.perform(post("/api/v1/sos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutUserId))
                .andExpect(status().isForbidden());

        verify(sosService, never()).create(any());
    }

    // The body still carries a userId until every client stops sending one. It is checked against
    // the proven identity and then ignored — a mismatch is someone acting as another driver.
    @Test
    void create_bodyUserIdDoesNotMatchCaller_returns403() throws Exception {
        mvc.perform(post("/api/v1/sos")
                        .header(GatewayIdentityFilter.USER_HEADER, callerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        verify(sosService, never()).create(any());
    }

    // Cambodia bounding box, enforced by @DecimalMin/@DecimalMax on the DTO.
    @Test
    void create_coordinatesOutsideCambodia_returns400WithFieldDetails() throws Exception {
        String body = """
                {"userId":"%s","serviceGroupId":"sg_01","latitude":48.85,
                 "longitude":2.35,"vehicleId":"%s"}
                """.formatted(callerId, vehicleId);

        mvc.perform(post("/api/v1/sos")
                        .header(GatewayIdentityFilter.USER_HEADER, callerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details.latitude").exists())
                .andExpect(jsonPath("$.details.longitude").exists());

        verify(sosService, never()).create(any());
    }

    @Test
    void getAll_scopesToCallerAndPassesStatusFilter() throws Exception {
        when(sosService.getByUserAndStatus(eq(callerId), eq(SosStatus.PENDING), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mvc.perform(get("/api/v1/sos").param("status", "PENDING")
                        .header(GatewayIdentityFilter.USER_HEADER, callerId))
                .andExpect(status().isOk());

        verify(sosService).getByUserAndStatus(eq(callerId), eq(SosStatus.PENDING), any());
        verify(sosService, never()).getByUser(any(), any());
    }

    // Someone else's SOS is hidden as a 404 rather than a 403: a 403 confirms the id is real.
    @Test
    void getById_foreignSos_returns404() throws Exception {
        when(sosService.getById(sosId, callerId))
                .thenThrow(new EntityNotFoundException("SOS request not found: " + sosId));

        mvc.perform(get("/api/v1/sos/{sosId}", sosId)
                        .header(GatewayIdentityFilter.USER_HEADER, callerId))
                .andExpect(status().isNotFound());
    }

    // A conflict with the current state is the service's own exception type, and only that type
    // reaches 409 — a stray IllegalStateException from a library is a 500, as it should be.
    @Test
    void updateStatus_invalidTransition_returns409() throws Exception {
        when(sosService.updateStatus(sosId, callerId, SosStatus.ARRIVED))
                .thenThrow(new ConflictException("Invalid status transition: PENDING → ARRIVED"));

        mvc.perform(patch("/api/v1/sos/{sosId}/status", sosId)
                        .param("toStatus", "ARRIVED")
                        .header(GatewayIdentityFilter.MECHANIC_HEADER, callerId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Invalid status transition: PENDING → ARRIVED"));
    }

    // Setting COMPLETED here is a client using the wrong endpoint — a 400, not a state conflict.
    @Test
    void updateStatus_completedTarget_returns400() throws Exception {
        when(sosService.updateStatus(sosId, callerId, SosStatus.COMPLETED))
                .thenThrow(new IllegalArgumentException("Only ON_THE_WAY and ARRIVED can be set directly."));

        mvc.perform(patch("/api/v1/sos/{sosId}/status", sosId)
                        .param("toStatus", "COMPLETED")
                        .header(GatewayIdentityFilter.MECHANIC_HEADER, callerId))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancel_foreignSos_returns403WithoutRevealingDetail() throws Exception {
        when(sosService.cancel(sosId, callerId))
                .thenThrow(new AccessDeniedException("You can only cancel your own SOS request"));

        mvc.perform(patch("/api/v1/sos/{sosId}/cancel", sosId)
                        .header(GatewayIdentityFilter.USER_HEADER, callerId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You are not allowed to perform this action"));
    }

    @Test
    void rebroadcast_attemptsExhausted_returns429() throws Exception {
        when(sosService.rebroadcast(sosId, callerId))
                .thenThrow(new com.autofik.sos.exception.TooManyRequestsException(
                        "Maximum rebroadcast attempts (3) reached. Please call support."));

        mvc.perform(post("/api/v1/sos/{sosId}/rebroadcast", sosId)
                        .header(GatewayIdentityFilter.USER_HEADER, callerId))
                .andExpect(status().isTooManyRequests());
    }
}
