package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.dto.response.VehicleResponse;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface VehicleMapper {

    VehicleResponse toResponse(Vehicle vehicle);

    List<VehicleResponse> toResponseList(List<Vehicle> vehicles);
}
