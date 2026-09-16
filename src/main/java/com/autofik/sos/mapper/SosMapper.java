package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.dto.response.SosRequestResponse;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(
    componentModel = "spring",
    uses = {UserMapper.class, VehicleMapper.class})
public interface SosMapper {

  // location is a JTS Point: X = longitude, Y = latitude (WGS-84)
  @Mapping(
      target = "latitude",
      expression =
          "java(sosRequest.getLocation() != null ? sosRequest.getLocation().getY() : null)")
  @Mapping(
      target = "longitude",
      expression =
          "java(sosRequest.getLocation() != null ? sosRequest.getLocation().getX() : null)")
  @Mapping(target = "locationAddress", source = "locationAddress")
  @Mapping(target = "broadcastRadiusKm", source = "broadcastRadiusKm")
  @Mapping(target = "mechanic", ignore = true)
  @Mapping(target = "durationSeconds", expression = "java(durationSeconds(sosRequest))")
  SosRequestResponse toResponse(SosRequest sosRequest);

  List<SosRequestResponse> toResponseList(List<SosRequest> sosRequests);

  // Null until both timestamps are set — job never started, or started but not yet completed.
  default Long durationSeconds(SosRequest sosRequest) {
    if (sosRequest.getJobStartedAt() == null || sosRequest.getJobCompletedAt() == null) {
      return null;
    }
    return java.time.Duration.between(sosRequest.getJobStartedAt(), sosRequest.getJobCompletedAt())
        .getSeconds();
  }
}
