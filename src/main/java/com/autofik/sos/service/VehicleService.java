package com.autofik.sos.service;

import com.autofik.sos.dto.response.VehicleResponse;
import java.util.List;
import java.util.UUID;

// READ-ONLY. Vehicle registration is managed by the SSO team.
// This service only reads vehicles to attach them to SOS requests.
public interface VehicleService {

  /**
   * Use case : fetch a single vehicle's details by its ID (e.g. to attach to an SOS request)
   *
   * @param vehicleId the ID of the vehicle to fetch
   * @return the vehicle's details as a VehicleResponse
   */
  VehicleResponse getById(UUID vehicleId);

  /**
   * Use case : fetch all vehicles registered by a given user
   *
   * @param userId the ID of the user whose vehicles are being fetched
   * @return list of the user's vehicles as VehicleResponse
   */
  List<VehicleResponse> getByUser(UUID userId);
}
