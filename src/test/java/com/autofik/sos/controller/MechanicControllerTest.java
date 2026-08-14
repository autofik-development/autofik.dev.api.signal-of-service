package com.autofik.sos.controller;

import com.autofik.sos.config.SecurityConfig;
import com.autofik.sos.dto.response.MechanicLocationResponse;
import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.service.MechanicService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MechanicController.class)
@Import({SecurityConfig.class, GatewayIdentityFilter.class})
class MechanicControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean MechanicService mechanicService;

    private final UUID mechanicId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void updateLocation_validCoordinates_returns204() throws Exception {
        mvc.perform(patch("/api/v1/mechanics/{id}/location", mechanicId)
                        .param("lat", "11.5573").param("lng", "104.9282")
                        .header(GatewayIdentityFilter.MECHANIC_HEADER, mechanicId))
                .andExpect(status().isNoContent());

        verify(mechanicService).updateLocation(mechanicId, 11.5573, 104.9282, null);
    }

    // @RequestParam constraints raise ConstraintViolationException, not the binding-result kind —
    // without its own handler this reached the fallback and a client mistake became a 500.
    @Test
    void updateLocation_coordinatesOutsideCambodia_returns400WithParameterName() throws Exception {
        mvc.perform(patch("/api/v1/mechanics/{id}/location", mechanicId)
                        .param("lat", "48.85").param("lng", "2.35")
                        .header(GatewayIdentityFilter.MECHANIC_HEADER, mechanicId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details.lat").exists());

        verify(mechanicService, never()).updateLocation(any(), anyDouble(), anyDouble(), any());
    }

    // A mechanic pushing a position under someone else's id would put a stranger on the map.
    @Test
    void updateLocation_forAnotherMechanic_returns403() throws Exception {
        mvc.perform(patch("/api/v1/mechanics/{id}/location", UUID.randomUUID())
                        .param("lat", "11.5573").param("lng", "104.9282")
                        .header(GatewayIdentityFilter.MECHANIC_HEADER, mechanicId))
                .andExpect(status().isForbidden());

        verify(mechanicService, never()).updateLocation(any(), anyDouble(), anyDouble(), any());
    }

    // Availability search is the driver's view; a mechanic has no business enumerating rivals.
    @Test
    void findAvailable_calledByMechanic_returns403() throws Exception {
        mvc.perform(get("/api/v1/mechanics/available")
                        .param("lat", "11.5573").param("lng", "104.9282")
                        .header(GatewayIdentityFilter.MECHANIC_HEADER, mechanicId))
                .andExpect(status().isForbidden());

        verify(mechanicService, never()).findAvailable(anyDouble(), anyDouble(), anyString(), anyInt());
    }

    @Test
    void findAvailable_calledByDriver_appliesDefaultRadius() throws Exception {
        when(mechanicService.findAvailable(eq(11.5573), eq(104.9282), eq(null), eq(15)))
                .thenReturn(List.of());

        mvc.perform(get("/api/v1/mechanics/available")
                        .param("lat", "11.5573").param("lng", "104.9282")
                        .header(GatewayIdentityFilter.USER_HEADER, userId))
                .andExpect(status().isOk());

        verify(mechanicService).findAvailable(11.5573, 104.9282, null, 15);
    }

    @Test
    void findAvailable_radiusOutsideAllowedRange_returns400() throws Exception {
        mvc.perform(get("/api/v1/mechanics/available")
                        .param("lat", "11.5573").param("lng", "104.9282").param("radiusKm", "99")
                        .header(GatewayIdentityFilter.USER_HEADER, userId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.radiusKm").exists());
    }

    // A mechanic's live position is visible to themselves and to the driver they are working for.
    @Test
    void getCurrentLocation_notOnTheJob_returns403() throws Exception {
        when(mechanicService.getCurrentLocation(mechanicId, userId))
                .thenThrow(new AccessDeniedException("You are not on an active job with this mechanic"));

        mvc.perform(get("/api/v1/mechanics/{id}/location", mechanicId)
                        .header(GatewayIdentityFilter.USER_HEADER, userId))
                .andExpect(status().isForbidden());
    }

    @Test
    void getCurrentLocation_onTheJob_returnsCoordinates() throws Exception {
        when(mechanicService.getCurrentLocation(mechanicId, userId))
                .thenReturn(new MechanicLocationResponse(11.5573, 104.9282, Instant.now()));

        mvc.perform(get("/api/v1/mechanics/{id}/location", mechanicId)
                        .header(GatewayIdentityFilter.USER_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(11.5573))
                .andExpect(jsonPath("$.longitude").value(104.9282));
    }

    @Test
    void addSkill_emptyTagList_returns400() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/mechanics/{id}/skills", mechanicId)
                        .header(GatewayIdentityFilter.MECHANIC_HEADER, mechanicId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"skillTags":[],"vehicleSpecialization":"SEDAN"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.skillTags").exists());

        verify(mechanicService, never()).addSkill(any(), any());
    }
}
