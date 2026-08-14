package com.autofik.sos.controller;

import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.service.MechanicService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * GPS pings over the open socket instead of one HTTP request each.
 *
 * <p>A mechanic pings every couple of seconds for the whole drive. Over REST that is a full
 * request/response through two proxy hops, and the app waits for the 200 before sending the next
 * one. Here it is a frame on a socket that is already open, with nothing to wait for.
 *
 * <p>{@code PATCH /api/v1/mechanics/{id}/location} stays: it is the fallback for a dropped socket
 * and for the first ping after a cold start, before the client has reconnected.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class MechanicLocationSocketController {

    private final MechanicService mechanicService;

    /**
     * SEND to {@code /app/mechanic/location}. Nothing is sent back — an acknowledgement would
     * reintroduce the round trip this exists to remove.
     *
     * @param sosId optional; supplying it skips the active-bid lookup on the server
     */
    public record LocationPing(
            @NotNull @DecimalMin("10.41") @DecimalMax("14.69") Double latitude,
            @NotNull @DecimalMin("102.5") @DecimalMax("107.63") Double longitude,
            UUID sosId
    ) {}

    @MessageMapping("/mechanic/location")
    public void ping(@Valid @Payload LocationPing ping, Authentication caller) {
        // The socket's proven identity, never a field in the frame — otherwise any connected
        // client could drive another mechanic's marker across the customer's map.
        boolean isMechanic = caller.getAuthorities().stream()
                .anyMatch(a -> GatewayIdentityFilter.ROLE_MECHANIC.equals(a.getAuthority()));
        if (!isMechanic) {
            throw new AccessDeniedException("Only mechanics report a location");
        }
        mechanicService.updateLocation((UUID) caller.getPrincipal(),
                ping.latitude(), ping.longitude(), ping.sosId());
    }

    /**
     * Without this a rejected frame closes the socket, and the client reconnect-loops through a
     * failure it cannot see. One error frame on the caller's own queue instead.
     */
    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public Map<String, String> handle(Exception ex) {
        log.warn("Rejected socket message: {}", ex.getMessage());
        return Map.of("error", ex.getMessage() == null ? "Rejected" : ex.getMessage());
    }
}
