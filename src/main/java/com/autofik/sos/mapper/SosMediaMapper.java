package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.SosMedia;
import com.autofik.sos.dto.response.SosMediaResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SosMediaMapper {

  // url is a presigned S3 GET URL, generated fresh on read — not a stored field
  @Mapping(target = "url", ignore = true)
  SosMediaResponse toResponse(SosMedia sosMedia);
}
