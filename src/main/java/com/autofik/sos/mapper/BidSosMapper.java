package com.autofik.sos.mapper;

import com.autofik.sos.config.BidPricing;
import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.dto.response.BidSosResponse;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(
    componentModel = "spring",
    uses = {MechanicMapper.class, BidPricing.class})
public interface BidSosMapper {

  @Mapping(target = "price", source = "price", qualifiedByName = "visiblePrice")
  BidSosResponse toResponse(BidSos bidSos);

  List<BidSosResponse> toResponseList(List<BidSos> bids);
}
