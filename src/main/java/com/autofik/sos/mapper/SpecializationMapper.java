package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.Specialization;
import com.autofik.sos.dto.response.SpecializationResponse;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface SpecializationMapper {

    SpecializationResponse toResponse(Specialization specialization);

    List<SpecializationResponse> toResponseList(List<Specialization> specializations);
}
