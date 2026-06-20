package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.dto.response.SosRequestResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring", uses = {UserMapper.class, VehicleMapper.class})
public interface SosMapper {

    // location is a JTS Point: X = longitude, Y = latitude (WGS-84)
    @Mapping(target = "latitude",        expression = "java(sosRequest.getLocation() != null ? sosRequest.getLocation().getY() : null)")
    @Mapping(target = "longitude",       expression = "java(sosRequest.getLocation() != null ? sosRequest.getLocation().getX() : null)")
    @Mapping(target = "locationAddress", source = "locationAddress")
    @Mapping(target = "mechanic",        ignore = true)
    SosRequestResponse toResponse(SosRequest sosRequest);

    List<SosRequestResponse> toResponseList(List<SosRequest> sosRequests);
}
