package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.dto.response.VehicleResponse;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface VehicleMapper {

  VehicleResponse toResponse(Vehicle vehicle);

  List<VehicleResponse> toResponseList(List<Vehicle> vehicles);
}
