package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.dto.response.BidSosResponse;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper(
    componentModel = "spring",
    uses = {MechanicMapper.class})
public interface BidSosMapper {

  BidSosResponse toResponse(BidSos bidSos);

  List<BidSosResponse> toResponseList(List<BidSos> bids);
}
