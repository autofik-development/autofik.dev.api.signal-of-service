package com.autofik.sos.service;

import com.autofik.sos.dto.request.CreateMechanicRequest;
import com.autofik.sos.dto.response.MechanicResponse;

import java.util.List;
import java.util.UUID;

public interface MechanicService {
    MechanicResponse create(CreateMechanicRequest request);
    MechanicResponse getById(UUID mechanicId);
    List<MechanicResponse> findAvailable(double lat, double lng);

    List<MechanicResponse> findAvailable(double lat, double lng, String problemType);
    MechanicResponse updateLocation(UUID mechanicId, double lat, double lng);
}
