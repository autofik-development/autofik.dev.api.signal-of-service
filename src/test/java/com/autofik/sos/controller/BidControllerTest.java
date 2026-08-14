package com.autofik.sos.controller;

import com.autofik.sos.config.SecurityConfig;
import com.autofik.sos.domain.enums.BidSortFilter;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.dto.response.BidSosResponse;
import com.autofik.sos.exception.ConflictException;
import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.service.BidService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
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

@WebMvcTest(BidController.class)
@Import({SecurityConfig.class, GatewayIdentityFilter.class})
class BidControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean BidService bidService;

    private final UUID mechanicId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID sosId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();

    private BidSosResponse response(BidStatus status) {
        return new BidSosResponse(bidId, BigDecimal.valueOf(500), status,
                null, null, null, null, null, null, null);
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        when(bidService.create(any())).thenReturn(response(BidStatus.PENDING));

        mvc.perform(post("/api/v1/bids")
                        .header(GatewayIdentityFilter.MECHANIC_HEADER, mechanicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mechanicId":"%s","sosId":"%s","price":500}
                                """.formatted(mechanicId, sosId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bidId").value(bidId.toString()));
    }

    // Bidding on behalf of another mechanic would let anyone flood a request with fake quotes.
    @Test
    void create_bodyMechanicIdDoesNotMatchCaller_returns403() throws Exception {
        mvc.perform(post("/api/v1/bids")
                        .header(GatewayIdentityFilter.MECHANIC_HEADER, mechanicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mechanicId":"%s","sosId":"%s","price":500}
                                """.formatted(UUID.randomUUID(), sosId)))
                .andExpect(status().isForbidden());

        verify(bidService, never()).create(any());
    }

    @Test
    void create_priceBelowMinimum_returns400() throws Exception {
        mvc.perform(post("/api/v1/bids")
                        .header(GatewayIdentityFilter.MECHANIC_HEADER, mechanicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mechanicId":"%s","sosId":"%s","price":0}
                                """.formatted(mechanicId, sosId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.price").exists());

        verify(bidService, never()).create(any());
    }

    @Test
    void getBySos_passesCallerAndSortFilter() throws Exception {
        when(bidService.getBySosRequest(eq(sosId), eq(BidSortFilter.CHEAPEST), eq(userId)))
                .thenReturn(List.of(response(BidStatus.PENDING)));

        mvc.perform(get("/api/v1/bids/sos/{sosId}", sosId)
                        .param("sortBy", "CHEAPEST")
                        .header(GatewayIdentityFilter.USER_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bidId").value(bidId.toString()));
    }

    // Accepting a bid that was already answered is the double-accept path — 409, not a silent
    // second ACCEPTED row.
    @Test
    void updateStatus_bidAlreadyAnswered_returns409() throws Exception {
        when(bidService.updateStatus(eq(bidId), any()))
                .thenThrow(new ConflictException("This bid has already been answered: ACCEPTED"));

        mvc.perform(patch("/api/v1/bids/{bidId}/status", bidId)
                        .header(GatewayIdentityFilter.USER_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","status":"ACCEPTED"}
                                """.formatted(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This bid has already been answered: ACCEPTED"));
    }

    @Test
    void updateStatus_bodyUserIdDoesNotMatchCaller_returns403() throws Exception {
        mvc.perform(patch("/api/v1/bids/{bidId}/status", bidId)
                        .header(GatewayIdentityFilter.USER_HEADER, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","status":"ACCEPTED"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        verify(bidService, never()).updateStatus(any(), any());
    }

    @Test
    void cancel_usesCallerAsTheMechanic() throws Exception {
        when(bidService.cancel(bidId, mechanicId)).thenReturn(response(BidStatus.CANCELLED));

        mvc.perform(patch("/api/v1/bids/{bidId}/cancel", bidId)
                        .header(GatewayIdentityFilter.MECHANIC_HEADER, mechanicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(bidService).cancel(bidId, mechanicId);
    }
}
