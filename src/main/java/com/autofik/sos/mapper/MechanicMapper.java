package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.dto.response.MechanicResponse;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MechanicMapper {

    // location (Point) is not exposed in the response — MapStruct ignores unmapped source fields
    MechanicResponse toResponse(Mechanic mechanic);

    List<MechanicResponse> toResponseList(List<Mechanic> mechanics);
}
