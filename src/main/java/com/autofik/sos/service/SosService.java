package com.autofik.sos.service;

import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.ApproveStatusChangeRequest;
import com.autofik.sos.dto.request.CreateSosRequest;
import com.autofik.sos.dto.request.ProposeStatusChangeRequest;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.dto.response.SosStatusHistoryResponse;

import java.util.List;
import java.util.UUID;

public interface SosService {
    SosRequestResponse create(CreateSosRequest request);
    SosRequestResponse getById(UUID sosId);
    List<SosRequestResponse> getByUser(UUID userId);
    List<SosRequestResponse> getByStatus(SosStatus status);
    SosStatusHistoryResponse proposeStatusChange(ProposeStatusChangeRequest request);
    SosRequestResponse approveStatusChange(ApproveStatusChangeRequest request);
    SosRequestResponse cancel(UUID sosId, UUID userId);
}
