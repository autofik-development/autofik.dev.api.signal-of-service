package com.autofik.sos.dto.response;

import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

/**
 * One step of a ticket's timeline, as GET /sos/{id}/history returns it. Deliberately not {@link
 * SosStatusHistoryResponse}: that one embeds the mechanic's phone and email, and a timeline needs
 * neither.
 *
 * @param at when the step took effect: the decision time, or the proposal time while it still waits
 * @param decision ACCEPTED, REJECTED, or PENDING_APPROVAL while the customer has not answered
 */
public record SosStatusHistoryEntry(
    SosStatus fromStatus,
    SosStatus toStatus,
    @JsonFormat(pattern = "MMM d yyyy, h:mm:ss a", locale = "en") LocalDateTime at,
    ApprovedStatus decision) {}
