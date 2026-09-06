package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.MechanicSkill;
import com.autofik.sos.dto.response.MechanicSkillResponse;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface MechanicSkillMapper {

  // mechanic is a LAZY @ManyToOne; reading only its id resolves off the proxy without a query.
  @Mapping(target = "mechanicId", source = "mechanic.mechanicId")
  MechanicSkillResponse toResponse(MechanicSkill skill);

  List<MechanicSkillResponse> toResponseList(List<MechanicSkill> skills);
}
