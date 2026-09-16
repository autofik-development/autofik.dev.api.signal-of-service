package com.autofik.sos.service;

import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.ApproveStatusChangeRequest;
import com.autofik.sos.dto.request.CreateSosRequest;
import com.autofik.sos.dto.request.ProposeStatusChangeRequest;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.dto.response.SosStatusHistoryResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Core Service Interface defining the business use cases for the Emergency SOS module. Manages the
 * entire lifecycle of a breakdown ticket from creation to resolution.
 */
public interface SosService {

  /**
   * Use Case: A stranded driver presses the SOS button to request roadside assistance.
   *
   * @param request Input data containing vehicle details, initial coordinates, and problem
   *     description.
   * @return The freshly opened SOS ticket details.
   */
  SosRequestResponse create(CreateSosRequest request);

  /**
   * Use Case: fetch data for specified sos
   *
   * @param sosId identify the target sos
   * @param callerId the proven identity of the requester
   * @return match sos request
   * @throws jakarta.persistence.EntityNotFoundException when the caller may not see it — a 403 here
   *     would confirm that someone else's sosId exists
   */
  SosRequestResponse getById(UUID sosId, UUID callerId);

  /**
   * May this identity see this SOS? True for the driver who raised it and for any mechanic who bid
   * on it. Shared by the REST reads and by the WebSocket SUBSCRIBE check, which guards the same
   * data on /topic/sos/{sosId}/**.
   */
  boolean canView(UUID sosId, UUID callerId);

  /**
   * Use Case: while waiting for a bid, the customer's screen shows who's actually nearby right now
   * — live, not the frozen list from when the ticket was first broadcast. Reuses the ticket's own
   * location, problem type, and current (possibly auto-expanded) radius, so the caller never has to
   * reconstruct those themselves or worry about the radius having grown past whatever they
   * originally picked.
   *
   * @throws jakarta.persistence.EntityNotFoundException when the caller may not see it, same rule
   *     as {@link #getById}
   */
  List<MechanicResponse> getNearbyMechanics(UUID sosId, UUID callerId);

  /**
   * Use case: View history sos
   *
   * @param userId identify specified user
   * @return list of sos records create by this user
   */
  Page<SosRequestResponse> getByUser(UUID userId, Pageable pageable);

  /**
   * Use case: the caller filters their own history by state.
   *
   * @param status The target state to look up (e.g., PENDING, ACTIVE, COMPLETED)
   * @return list of the user's sos records currently matching
   */
  Page<SosRequestResponse> getByUserAndStatus(UUID userId, SosStatus status, Pageable pageable);

  /**
   * Use case : update the operation status of a sos request
   *
   * @param sosId identify the target sos
   * @param mechanicId identify the mechanic
   * @param toStatus target status matric boundary
   * @return the modified sos request details
   */
  SosRequestResponse updateStatus(UUID sosId, UUID mechanicId, SosStatus toStatus);

  /**
   * Use case : handshake Register mechanic proposal to change the live sos status
   *
   * @param request contain the sosId mechanicId and proposed status
   * @return A status history log showing that change request is awaiting user confirmation
   */
  SosStatusHistoryResponse proposeStatusChange(UUID sosId, ProposeStatusChangeRequest request);

  /**
   * Use case : user confirmation to approve the machanic's status proposal
   *
   * @param request contains verification details and sosId
   * @return the finalized sos request details
   */
  SosRequestResponse approveStatusChange(ApproveStatusChangeRequest request);

  /**
   * Use case: borts an ongoing emergency assistance request before work has officially begun
   *
   * @param sosId identify the target sosId
   * @param userId Security check parameter ensuring users can only cancel their own tickets.
   * @return cancel ticket summary data
   */
  SosRequestResponse cancel(UUID sosId, UUID userId);

  /**
   * Use case: re-opens an EXPIRED SOS request and re-broadcasts to nearby mechanics. Capped at
   * MAX_REBROADCAST attempts; throws TooManyRequestsException when exhausted.
   *
   * @param sosId identify the target SOS
   * @param userId security check — only the owner can rebroadcast
   * @return the updated SOS ticket now back in PENDING state
   */
  SosRequestResponse rebroadcast(UUID sosId, UUID userId);
}
