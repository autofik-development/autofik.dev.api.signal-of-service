package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.dto.response.BidSosResponse;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring", uses = {MechanicMapper.class})
public interface BidSosMapper {

    BidSosResponse toResponse(BidSos bidSos);

    List<BidSosResponse> toResponseList(List<BidSos> bids);
}

