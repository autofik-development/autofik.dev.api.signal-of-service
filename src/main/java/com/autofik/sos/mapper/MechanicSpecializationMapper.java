package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.MechanicSpecialization;
import com.autofik.sos.dto.response.MechanicSpecializationResponse;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring", uses = {SpecializationMapper.class})
public interface MechanicSpecializationMapper {

    MechanicSpecializationResponse toResponse(MechanicSpecialization mechanicSpecialization);

    List<MechanicSpecializationResponse> toResponseList(List<MechanicSpecialization> specializations);
}
