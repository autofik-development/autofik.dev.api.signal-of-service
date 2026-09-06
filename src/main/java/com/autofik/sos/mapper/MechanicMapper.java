package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.dto.response.MechanicResponse;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface MechanicMapper {

  @Mapping(target = "avgRate", ignore = true)
  @Mapping(target = "rateCount", ignore = true)
  // currentLocation is a JTS Point: X = longitude, Y = latitude (WGS-84)
  @Mapping(
      target = "latitude",
      expression =
          "java(mechanic.getCurrentLocation() != null ? mechanic.getCurrentLocation().getY() : null)")
  @Mapping(
      target = "longitude",
      expression =
          "java(mechanic.getCurrentLocation() != null ? mechanic.getCurrentLocation().getX() : null)")
  MechanicResponse toResponse(Mechanic mechanic);

  List<MechanicResponse> toResponseList(List<Mechanic> mechanics);
}
