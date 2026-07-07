package com.autofik.sos.service;

import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.ApproveStatusChangeRequest;
import com.autofik.sos.dto.request.CreateSosRequest;
import com.autofik.sos.dto.request.ProposeStatusChangeRequest;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.dto.response.SosStatusHistoryResponse;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;
/**
 * Core Service Interface defining the business use cases for the Emergency SOS module.
 * Manages the entire lifecycle of a breakdown ticket from creation to resolution.
 */
public interface SosService {

    /**
     * Use Case: A stranded driver presses the SOS button to request roadside assistance.
     * @param request Input data containing vehicle details, initial coordinates, and problem description.
     * @return The freshly opened SOS ticket details.
     */
    SosRequestResponse create(CreateSosRequest request);

    /**
     * Use Case: fetch data for specified sos
     * @param sosId identify the target sos
     * @return match sos request
     */
    SosRequestResponse getById(UUID sosId);

    /**
     * Use case: View history sos
     * @param userId identify specified user
     * @return list of sos records create by this user
     */
    Page<SosRequestResponse> getByUser(UUID userId, Pageable pageable);

    /**
     * Use case: filter sos by their current state
     * @param status The target state to look up (e.g., PENDING, ACTIVE, COMPLETED)
     * @return list of sos records currently matching
     */
    Page<SosRequestResponse> getByStatus(SosStatus status, Pageable pageable);

    /**
     * Use case : update the operation status of a sos request
     * @param sosId identify the target sos
     * @param mechanicId identify the mechanic
     * @param toStatus target status matric boundary
     * @return the modified sos request details
     */
    SosRequestResponse updateStatus(UUID sosId, UUID mechanicId, SosStatus toStatus);

    /**
     * Use case : handshake Register mechanic proposal to change the live sos status
     * @param request contain the sosId mechanicId and proposed status
     * @return A status history log showing that change request is awaiting user confirmation
     */
    SosStatusHistoryResponse proposeStatusChange(UUID sosId, ProposeStatusChangeRequest request);

    /**
     * Use case : user confirmation to approve the machanic's status proposal
     * @param request contains verification details and sosId
     * @return the finalized sos request details
     */
    SosRequestResponse approveStatusChange(ApproveStatusChangeRequest request);

    /**
     * Use case: borts an ongoing emergency assistance request before work has officially begun
     * @param sosId identify the target sosId
     * @param userId Security check parameter ensuring users can only cancel their own tickets.
     * @return cancel ticket summary data
     */
    SosRequestResponse cancel(UUID sosId, UUID userId);

    /**
     * Retrieves a master list of every single emergency ticket recorded in the database system.
     * Mainly used for administrative management panels and analytical metrics.
     * * @return A master list of all SOS records.
     */
    Page<SosRequestResponse> getAll(Pageable pageable);
}
