package com.autofik.sos.service;

import com.autofik.sos.dto.request.CreateVehicleRequest;
import com.autofik.sos.dto.response.VehicleResponse;

import java.util.List;
import java.util.UUID;

public interface VehicleService {
    VehicleResponse create(CreateVehicleRequest request);
    VehicleResponse getById(UUID vehicleId);
    List<VehicleResponse> getByUser(UUID userId);
}
